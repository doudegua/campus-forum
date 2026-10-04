package net.doudegua.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 极简 TTL 缓存。
 * <p>
 * 个人项目单实例跑，内存里存一份就够，不必为了天气接口去依赖 Redis（少一个故障点）。
 * 想换成 Redis，把 get 里那几行换成 StringRedisTemplate 的读写即可。
 */
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
