package net.doudegua.entity.vo.response;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

/**
 * 和风 v1 接口的响应直接透传。
 * <p>
 * 字段内容和老教程里的 v7 完全不同，前端取值时注意：
 * <ul>
 *   <li>{@code now.temperature.value} / {@code now.temperature.unit} —— 数值和单位是分开的</li>
 *   <li>{@code now.condition.text} —— 中文描述，如"少云"</li>
 *   <li>{@code now.humidity} / {@code now.cloudCover} —— 是 0~1 的小数，显示要 ×100</li>
 *   <li>{@code now.wind.direction.compass} —— 'sw' 这类缩写，不是中文"西南风"，需要自己映射</li>
 *   <li>{@code hourly[i].forecastTime} —— 默认是 UTC 时间</li>
 * </ul>
 */
@Data
public class WeatherVo {
    /** GeoAPI 反查到的城市信息，查不到时为 null */
    JSONObject location;
    /** v1 实时天气 */
    JSONObject now;
    /** v1 逐小时预报的 hours 数组 */
    JSONArray hourly;
    /** 数据归因，和风条款要求必须与数据共同显示 */
    JSONArray attribution;
}
