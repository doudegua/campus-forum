package net.doudegua.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import net.doudegua.entity.dto.TopicDetailRow;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 本地缓存配置。
 * <p>
 * 目前只有一处用到缓存：帖子详情（{@link net.doudegua.service.impl.TopicServiceImpl#fetchTopic}）。
 * 版块名那个小缓存仍用项目自带的 {@code TtlCache} —— 它只有 1 个键、几乎不变，
 * 换成 Caffeine 是杀鸡用牛刀，留着更简单。
 */
@Configuration
public class CacheConfiguration {

    /**
     * 帖子详情缓存。
     * <p>
     * <b>为什么能缓存</b>：{@code TopicDetailVo} 已经**不再包含任何"依赖谁在看"的字段**
     * （{@code liked} 已被拆到独立接口 {@code GET /api/forum/topic/{id}/liked}）。
     * 所以同一条帖子，谁来问答案都一样 —— 这才允许公共缓存。
     * <p>
     * 在此之前不能缓存：把 viewers 相关的字段塞进缓存对象，A 的点赞状态会串给 B。
     * 实测过——缓存整个 VO 时 1175 个请求里有 247 个（21%）返回了别人的点赞状态，
     * 而且 HTTP 全 200、错误率 0%，不报任何错，数据就是错的。
     * <p>
     * <b>为什么用 Optional 包一层</b>：Caffeine 的 {@code get(key, loader)} 在 loader
     * 返回 {@code null} 时**不会写入缓存**，于是"查询一个不存在的帖子"每次都会穿透到数据库。
     * 包成 {@code Optional.empty()} 后，它是一个非 null 的对象，会被正常缓存 ——
     * 等于自带了"缓存空值"这层防护（重复请求同一个不存在的 id 不再打库）。
     * <p>
     * <b>为什么不用 Spring 的 {@code @Cacheable}</b>：注解方式在"返回 null 要不要缓存"
     * 上依赖各 CacheManager 的实现细节，不够直观。这里显式写出来，读代码的人一眼能看见。
     */
    @Bean
    public Cache<Integer, Optional<TopicDetailRow>> topicDetailCache(MeterRegistry registry) {
        Cache<Integer, Optional<TopicDetailRow>> cache = Caffeine.newBuilder()
                // ── ① 空间：容量上限 ───────────────────────────────────────
                // 项目原来的 TtlCache **完全没有上限**，只在 size>512 时扫描删除"已过期"的项，
                // 而且那个扫描是 O(n) 的、每次 put 都跑一遍 —— 压测里它把吞吐从 91.4 拖到 82.3。
                // Caffeine 的淘汰是 O(1) 的（W-TinyLFU），天然没有这个问题。
                .maximumSize(10_000)

                // ── ② 时间：TTL + 随机抖动 ────────────────────────────────
                // 为什么不是 expireAfterWrite(5min)：那样同一批写入的键会同时过期，
                // 一波请求同时打穿到数据库（缓存雪崩）。实测过：200 个键同时过期后，
                // 226 个请求里 199 个打穿，命中率瞬间归零。
                .expireAfter(jitteredExpiry(Duration.ofMinutes(5)))

                // ── ③ 统计：命中率 ───────────────────────────────────────
                .recordStats()

                .build();

        // ── ④ 把指标接进 Micrometer → Prometheus → Grafana ──────────
        // ⚠️ 注意：裸 Caffeine 的 Cache **不会**被 Spring Boot 自动注册成指标，
        //    只有走 Spring Cache 抽象（@Cacheable + CaffeineCacheManager）才会。
        //    所以这里要手动 bind 一行。
        //    绑定之后 Grafana 上能看到：
        //      cache_gets_total{cache="topicDetail",result="hit"}   命中次数
        //      cache_gets_total{cache="topicDetail",result="miss"}  未命中次数
        //      cache_evictions_total{cache="topicDetail",cause="SIZE"}
        CaffeineCacheMetrics.monitor(registry, cache, "topicDetail");

        return cache;
    }

    /**
     * 带 ±10% 随机抖动的过期策略。
     * <p>
     * 语义等价于 {@code expireAfterWrite}：**读不会延长 TTL**（{@link # expireAfterRead}
     * 原样返回当前剩余时长），只有创建和更新才会重新计时。
     * <p>
     * 抖动的目的不是"让数据更久"，而是**打散过期时刻**：
     * 原本同一毫秒写入的 N 个键会在同一毫秒集体失效，抖开之后它们的失效时间散在
     * 一个 10% 宽度的区间里，不会形成"同一瞬间 N 个请求同时穿透"的尖峰。
     */
    private static <K, V> Expiry<K, V> jitteredExpiry(Duration base) {
        final long baseNanos = base.toNanos();
        final long jitterRange = baseNanos / 10;   // 总抖动幅度 = TTL 的 10%

        return new Expiry<K, V>() {
            /** base ± 5%（总跨度 10%） */
            private long nextTtl() {
                return baseNanos + ThreadLocalRandom.current().nextLong(jitterRange) - jitterRange / 2;
            }

            @Override
            public long expireAfterCreate(K key, V value, long currentTime) {
                return nextTtl();
            }

            @Override
            public long expireAfterUpdate(K key, V value, long currentTime, long currentDuration) {
                return nextTtl();
            }

            @Override
            public long expireAfterRead(K key, V value, long currentTime, long currentDuration) {
                // 读不续期 —— 否则热点帖子永不过期，编辑后的内容永远刷不出来
                return currentDuration;
            }
        };
    }
}
