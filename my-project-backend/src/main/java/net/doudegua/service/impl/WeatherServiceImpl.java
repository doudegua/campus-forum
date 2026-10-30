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

    @Override
    public RestBean<WeatherVo> fetchWeather(double longitude, double latitude) {
        // 经纬度是连续量，直接拿来当缓存键会无限膨胀。量化到两位小数（约 1.1 公里），
        // 这正好也是 v1 接口允许的最大精度，等于不丢精度地把键变成有限集合。
        String key = String.format(Locale.ROOT, "%.1f,%.1f", latitude, longitude);

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
            return RestBean.success(vo);
        } catch (Exception e) {
            // 天气拿不到不该让前端白屏，返回一个明确的失败码和信息
            return RestBean.failure(502, "获取天气失败：" + e.getMessage());
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
