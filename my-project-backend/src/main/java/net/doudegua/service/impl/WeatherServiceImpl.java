package net.doudegua.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.github.benmanes.caffeine.cache.Cache;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.entity.RestBean;
import net.doudegua.entity.vo.response.WeatherVo;
import net.doudegua.service.QWeatherClient;
import net.doudegua.service.WeatherService;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
public class WeatherServiceImpl implements WeatherService {

    private static final int FORECAST_HOURS = 24;

    @Resource
    private QWeatherClient client;

    /*
     * 三个缓存的 Bean 定义（含 TTL）都在 CacheConfiguration 里 —— 这里只注入。
     *
     * 为什么从 TtlCache 换成 Caffeine：原来的实现在条目超过 512 之后，
     * 每次 put 都会全表扫描一遍找过期项（O(n)），而且完全没有容量上限。
     * 这三个缓存的键是量化后的坐标，键空间 648 万，条目很容易越过 512。
     *
     * 注意 geoCache 的值类型是 Optional<JSONObject>：Caffeine 不缓存 null，
     * 而"这个地方反查不到城市"是一个需要被缓存住的正常结果
     * （不缓存的话每次都会重新打一次 GeoAPI，白烧额度）。
     */
    @Resource
    private Cache<String, JSONObject> weatherCurrentCache;

    @Resource
    private Cache<String, JSONArray> weatherHourlyCache;

    @Resource
    private Cache<String, Optional<JSONObject>> weatherGeoCache;

    /**
     * 失败缓存：坐标 → 失败原因，60 秒。
     * <p>
     * 这是"缓存穿透"的补丁。原来的实现里 loader 抛异常 → 什么都没缓存 →
     * 同一个坏坐标每次请求都重新打一次和风（实测：lon=999&lat=999 连打三次
     * 是 176/76/80ms，从来没落到命中时的 24ms 区间，说明每次都在真打）。
     * <p>
     * 代价说清楚：这个坐标的**临时性**故障会被放大成"60 秒内不可用"。
     * 权衡下来值得 —— 天气是非关键功能，而额度是会被烧光的。
     */
    @Resource
    private Cache<String, String> weatherFailureCache;

    @Override
    public RestBean<WeatherVo> fetchWeather(double longitude, double latitude) {
        // 经纬度是连续量，直接拿来当缓存键会无限膨胀，所以先量化到 0.1 度（约 11 公里）。
        //
        // ⚠️ 这里是 %.1f，**一位**小数。原来的注释写的是"两位小数（约 1.1 公里）"，
        //    和代码对不上。真按两位小数，键空间会是 36000 × 18000 = 6.48 亿，
        //    比现在大一百倍。当前键空间是 3600 × 1800 = 648 万 —— 仍然很大，
        //    所以穿透防护靠的是"范围校验 + 失败缓存 + 限流"三件套（见 WeatherController），
        //    而不是指望量化本身把键空间压小。
        String key = String.format(Locale.ROOT, "%.1f,%.1f", latitude, longitude);

        // ── 防穿透：这个坐标刚失败过，就不要再打一次和风 ────────────────────
        // 命中说明 60 秒内刚失败过，直接返回同一个失败原因，一次 API 都不消耗。
        String cachedFailure = weatherFailureCache.getIfPresent(key);
        if (cachedFailure != null) {
            return RestBean.failure(502, cachedFailure);
        }

        try {
            JSONObject current = weatherCurrentCache.get(key,
                    k -> client.current(latitude, longitude));

            JSONArray hourly = weatherHourlyCache.get(key,
                    k -> client.hourly(latitude, longitude, FORECAST_HOURS).getJSONArray("hours"));

            WeatherVo vo = new WeatherVo();
            vo.setNow(current);
            vo.setHourly(hourly);
            vo.setLocation(city(key, longitude, latitude));
            vo.setAttribution(attributionOf(current));

            // 成功了就把旧的失败记录清掉：虽然它 60 秒后会自己过期，
            // 但主动清掉能让"恢复后立刻可用"，不用让用户干等
            weatherFailureCache.invalidate(key);

            return RestBean.success(vo);
        } catch (Exception e) {
            // 天气拿不到不该让前端白屏，返回一个明确的失败码和信息
            String message = "获取天气失败：" + e.getMessage();

            // ⚠️ 失败也要缓存（60 秒）—— 否则同一个坏坐标会无限穿透。
            //    这是本方法唯一一处"改变语义"的地方，取舍见 weatherFailureCache 的注释。
            weatherFailureCache.put(key, message);

            return RestBean.failure(502, message);
        }
    }

    /** 城市名只是锦上添花，反查失败不该拖垮整个天气接口。但失败原因必须记下来，否则查都没法查。 */
    private JSONObject city(String key, double longitude, double latitude) {
        try {
            return weatherGeoCache.get(key, k -> {
                JSONArray locations = client.cityLookup(longitude, latitude).getJSONArray("location");
                JSONObject found = locations == null || locations.isEmpty()
                        ? null : locations.getJSONObject(0);
                // 包成 Optional 才能把"没有城市"这个结果也缓存住（Caffeine 不缓存 null）
                return Optional.ofNullable(found);
            }).orElse(null);
        } catch (Exception e) {
            log.warn("城市反查失败，卡片将只显示天气不显示地名：{}", e.getMessage());
            return null;
        }
    }

    private JSONArray attributionOf(JSONObject current) {
        JSONObject metadata = current.getJSONObject("metadata");
        return metadata == null ? null : metadata.getJSONArray("attributions");
    }
}
