package net.doudegua.entity;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

public record RestBean<T>(int code, T data, String message) {

    public static <T> RestBean<T> success(T data) {
        return new RestBean<>(200, data, "Request Success");
    }

    public static <T> RestBean<T> success() {
        return success(null);
    }

    public static <T> RestBean<T> failure(int code, String message) {
        return new RestBean<T>(code, null, message);
    }

    public String asJsonString() {
        return JSONObject.toJSONString(this, JSONWriter.Feature.WriteNulls);
    }
}
