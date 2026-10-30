package net.doudegua.config;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import net.doudegua.entity.dto.TopicDetailRow;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 本地缓存配置。
 * <p>
 * 项目里所有进程内缓存集中在这里：帖子详情、版块名、天气三件套。
 * <p>
 * <b>为什么统一改用 Caffeine、并废弃了原来的 TtlCache</b>：
 * 那个 40 行的极简实现在压测里暴露了三个问题（详见 {@link #topicDetailCache} 的注释）——
 * 无容量上限、清理是 O(n) 且每次 put 都跑、无单飞、无 TTL 抖动。
 * 实测后果：详情页加了缓存反而比不加慢 10%（吞吐 91.4 → 82.3）。
 * <p>
 * 有一处曾经误判的地方值得记下来：版块名那个缓存**只有一个字面量键**，
 * 永远不会走到 {@code size() > 512} 那个分支，所以性能上其实没问题。
 * 真正会被 O(n) 扫描拖垮的是**天气** —— 它的键由用户输入的坐标决定，键空间六百万。
 * 换库是为了消除这个隐患，不只是为了好看。
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
     * 版块 id → 名字，10 分钟。
     * <p>
     * 只有这一个键（{@code "typeNames"}），而且 {@code db_topic_type} 没有写接口、
     * 实际是只读表，所以内容几乎不变。换 Caffeine 主要是为了一致性 ——
     * 让全项目只剩一种缓存实现，`TtlCache` 才能安心废弃。
     */
    @Bean
    public Cache<String, Map<Integer, String>> typeNamesCache(MeterRegistry registry) {
        Cache<String, Map<Integer, String>> cache = Caffeine.newBuilder()
                .maximumSize(16)                    // 实际上只会用 1 个键，给点余量
                .expireAfter(jitteredExpiry(Duration.ofMinutes(10)))
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(registry, cache, "typeNames");
        return cache;
    }

    // =======================================================================
    // 天气三件套
    // =======================================================================
    //
    // ⚠️ 这三处从 TtlCache 迁过来，TTL 一个都没动：
    //    实时天气 30 分钟 / 小时预报 60 分钟 / 坐标反查城市 30 天。
    //
    // 迁过来的直接原因是 TtlCache 的清理是 O(n) 的：
    //     if (store.size() > 512) store.entrySet().removeIf(已过期)
    // 只要条目超过 512，**每次 put 都全表扫描一遍**。
    // 天气的键是 "%.1f,%.1f" 量化后的坐标，键空间 3600 × 1800 = 648 万 ——
    // 一旦被刷起来，条目轻松越过 512，之后一直在 O(n) 里打转。
    // Caffeine 的淘汰是 O(1) 的，天然没有这个问题。
    //
    // ⚠️ 这里【还没有】解决缓存穿透（键由用户输入决定、参数没有校验、异常不缓存）。
    //    那是另一次改造，不要混在一起做。
    //
    // 关于 TTL 怎么定的：免费额度每月 5 万次，天气和 GeoAPI 共用同一个池子。
    // 实时天气 30 分钟 = 48 次/天/键（10 分钟会是 144 次），是最省额度的一刀。

    /** 实时天气，30 分钟 */
    @Bean
    public Cache<String, JSONObject> weatherCurrentCache(MeterRegistry registry) {
        return buildCache("weatherCurrent", Duration.ofMinutes(30), 10_000, registry);
    }

    /** 逐小时预报，60 分钟 */
    @Bean
    public Cache<String, JSONArray> weatherHourlyCache(MeterRegistry registry) {
        return buildCache("weatherHourly", Duration.ofMinutes(60), 10_000, registry);
    }

    /**
     * 坐标 → 城市名，30 天。
     * <p>
     * 类型里多包了一层 {@code Optional}，原因和帖子详情那个一样：反查不到城市时
     * 原来的实现返回 null，而 Caffeine 不缓存 null —— 不包的话"这个地方没有城市"
     * 会每次重新打一次 GeoAPI，白白消耗额度。
     */
    @Bean
    public Cache<String, Optional<JSONObject>> weatherGeoCache(MeterRegistry registry) {
        return buildCache("weatherGeo", Duration.ofDays(30), 10_000, registry);
    }

    /**
     * 通用构造：容量上限 + TTL 抖动 + 统计 + 监控绑定。
     * <p>
     * 容量上限按"单实例这点内存"给，不是按业务需求算的：一条实时天气响应约 15 KB，
     * 10000 条的理论上限约 150 MB；实际缓存大小在 Grafana 的 {@code cache_size} 上能看。
     */
    private <V> Cache<String, V> buildCache(String name, Duration ttl, long maxSize, MeterRegistry registry) {
        Cache<String, V> cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfter(jitteredExpiry(ttl))
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(registry, cache, name);
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
