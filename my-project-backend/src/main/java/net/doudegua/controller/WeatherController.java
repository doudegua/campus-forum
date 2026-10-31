package net.doudegua.controller;

import jakarta.annotation.Resource;
import net.doudegua.entity.RestBean;
import net.doudegua.entity.vo.response.WeatherVo;
import net.doudegua.service.WeatherService;
import net.doudegua.utils.FlowUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    /**
     * 同一用户两次天气请求的最小间隔（秒）。
     * <p>
     * 天气是页面自动拉的，正常人一次页面加载最多拉一次，3 秒足够宽松；
     * 而脚本会被压到 0.33 次/秒 —— 配合下面的范围校验和失败缓存，
     * 靠这个接口烧和风额度的成本就很高了。
     * <p>
     * <b>为什么这个接口比别的更需要限流</b>：它背后是**计费的**第三方 API
     * （每月 5 万次免费额度，天气和 GeoAPI 共用）。CPU 打满只是慢，
     * 额度烧完是所有用户的天气功能一起挂掉。
     */
    private static final int WEATHER_INTERVAL_SECONDS = 3;

    @Resource
    private WeatherService weatherService;

    @Resource
    private FlowUtils flowUtils;

    /**
     * GET /api/weather?lon=116.41&lat=39.92
     * <p>
     * 走本项目自己的登录鉴权（SecurityConfiguration 里 anyRequest().authenticated()），
     * 前端带上自己的登录 token 访问即可。和风的私钥始终留在后端，不会下发到浏览器。
     */
    @GetMapping
    public RestBean<WeatherVo> weather(@RequestParam double lon,
                                       @RequestParam double lat,
                                       @RequestAttribute("id") int uid) {

        // ── ① 范围校验 ──────────────────────────────────────────────────
        // 原来完全没有校验：lon=999&lat=999 会一路打到和风、拿回一个 400，
        // 而且**每次仍算一次调用**。更根本的问题是：缓存键是从坐标算出来的，
        // 用户输入直接决定键空间 —— 不做约束，"键空间"就是"任何 double"，等于没有边界。
        //
        // 这里没用 Bean Validation（@DecimalMin/@DecimalMax）：那要 @Validated 加全局
        // 异常处理器配合，抛出的 ConstraintViolationException 默认会变成 500。
        // 手写这三行、返回本项目统一的 RestBean.failure，前端一行都不用改。
        if (lon < -180 || lon > 180 || lat < -90 || lat > 90) {
            return RestBean.failure(400, "经纬度超出有效范围");
        }

        // ── ② 按用户限流 ────────────────────────────────────────────────
        // 和发帖/评论/点赞用的是同一个工具，语义是"N 秒内只认第一次"。
        if (!flowUtils.limitOnceCheck("weather:" + uid, WEATHER_INTERVAL_SECONDS)) {
            return RestBean.failure(429, "天气刷新太频繁，请稍后再试");
        }

        return weatherService.fetchWeather(lon, lat);
    }
}
