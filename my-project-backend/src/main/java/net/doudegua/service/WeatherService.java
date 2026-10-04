package net.doudegua.service;

import net.doudegua.entity.RestBean;
import net.doudegua.entity.vo.response.WeatherVo;

public interface WeatherService {
    public RestBean<WeatherVo> fetchWeather(double longitude, double latitude);
}
