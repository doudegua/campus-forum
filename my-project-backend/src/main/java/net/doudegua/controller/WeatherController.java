package net.doudegua.controller;

import jakarta.annotation.Resource;
import net.doudegua.entity.RestBean;
import net.doudegua.entity.vo.response.WeatherVo;
import net.doudegua.service.WeatherService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    @Resource
    private WeatherService weatherService;

    /**
     * GET /api/weather?lon=116.41&lat=39.92
     * <p>
     * 走本项目自己的登录鉴权（SecurityConfiguration 里 anyRequest().authenticated()），
     * 前端带上自己的登录 token 访问即可。和风的私钥始终留在后端，不会下发到浏览器。
     */
    @GetMapping
    public RestBean<WeatherVo> weather(@RequestParam double lon, @RequestParam double lat) {
        return weatherService.fetchWeather(lon, lat);
    }
}
