package net.doudegua.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 极简 TTL 缓存。
 * <p>
 * 个人项目单实例跑，内存里存一份就够，不必为了天气接口去依赖 Redis（少一个故障点）。
 * 想换成 Redis，把 get 里那几行换成 StringRedisTemplate 的读写即可。
 *
 * @deprecated 已全部替换为 Caffeine（见 {@code net.doudegua.config.CacheConfiguration}），
 *             新代码不要再用。这个实现有三个实测出来的问题：
 *             <ol>
 *               <li><b>清理是 O(n) 的</b>：{@code if (store.size() > 512)} 时对整个 map 做
 *                   {@code removeIf}，而且只要超过 512，<b>每次 put 都会跑一遍</b>。
 *                   天气那三个缓存（键由用户坐标决定，键空间 648 万）会稳定踩中这条路径。</li>
 *               <li><b>没有容量上限</b>：只删"已经过期"的项，过期之前一直涨。</li>
 *               <li><b>没有单飞</b>：热点键过期瞬间，N 个并发线程同时执行 loader。</li>
 *             </ol>
 *             实测后果：详情页用它缓存后，吞吐从 91.4 掉到 82.3（比不加缓存还慢）。
 */
@Deprecated
public class TtlCache<V> {

    private record Entry<V>(V value, long expireAt) {
    }

    private final Map<String, Entry<V>> store = new ConcurrentHashMap<>();

    /**
     * 命中且未过期就返回缓存值，否则调用 loader 重新取值并缓存。
     * loader 抛异常时不会写入缓存，下次会重试。
     */
    public V get(String key, long ttlMillis, Supplier<V> loader) {
        long now = System.currentTimeMillis();
        Entry<V> entry = store.get(key);
        if (entry != null && now < entry.expireAt()) {
            return entry.value();
        }
        V value = loader.get();
        store.put(key, new Entry<>(value, now + ttlMillis));
        if (store.size() > 512) {
            store.entrySet().removeIf(e -> System.currentTimeMillis() >= e.getValue().expireAt());
        }
        return value;
    }

    public void clear() {
        store.clear();
    }
}
