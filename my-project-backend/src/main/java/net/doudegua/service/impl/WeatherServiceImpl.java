package net.doudegua.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.entity.RestBean;
import net.doudegua.entity.vo.response.WeatherVo;
import net.doudegua.service.QWeatherClient;
import net.doudegua.service.WeatherService;
import net.doudegua.utils.TtlCache;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Slf4j
@Service
public class WeatherServiceImpl implements WeatherService {

    /**
     * 实时天气缓存 30 分钟。这个 TTL 直接决定每月请求量：
     * 10 分钟 TTL 是 144 次/天/键，30 分钟降到 48 次，是最省额度的一刀。
     * 免费额度是每月 5 万次，天气和 GeoAPI 共用同一个池子。
     */
    private static final long CURRENT_TTL = 30 * 60 * 1000L;
    /** 小时预报缓存 60 分钟 */
    private static final long HOURLY_TTL = 60 * 60 * 1000L;
    /** 坐标到城市名的映射缓存 30 天，行政区划基本不会变，摊下来成本趋近于零 */
    private static final long GEO_TTL = 30L * 24 * 60 * 60 * 1000L;
    private static final int FORECAST_HOURS = 24;

    @Resource
    private QWeatherClient client;

    private final TtlCache<JSONObject> currentCache = new TtlCache<>();
    private final TtlCache<JSONArray> hourlyCache = new TtlCache<>();
    private final TtlCache<JSONObject> geoCache = new TtlCache<>();

    @Override
    public RestBean<WeatherVo> fetchWeather(double longitude, double latitude) {
        // 经纬度是连续量，直接拿来当缓存键会无限膨胀。量化到两位小数（约 1.1 公里），
        // 这正好也是 v1 接口允许的最大精度，等于不丢精度地把键变成有限集合。
        String key = String.format(Locale.ROOT, "%.1f,%.1f", latitude, longitude);

        try {
            JSONObject current = currentCache.get(key, CURRENT_TTL,
                    () -> client.current(latitude, longitude));

            JSONArray hourly = hourlyCache.get(key, HOURLY_TTL,
                    () -> client.hourly(latitude, longitude, FORECAST_HOURS).getJSONArray("hours"));

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
            return geoCache.get(key, GEO_TTL, () -> {
                JSONArray locations = client.cityLookup(longitude, latitude).getJSONArray("location");
                return locations == null || locations.isEmpty() ? null : locations.getJSONObject(0);
            });
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
