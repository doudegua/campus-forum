package net.doudegua.service;

import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import net.doudegua.config.QWeatherConfiguration;
import net.doudegua.utils.QWeatherTokenService;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 和风 v1 接口的薄封装：只负责发请求、带上 JWT、把响应解析成 JSON，不含业务判断。
 * <p>
 * 注意这里用的是 v1 的坐标接口，不是老教程里的 /v7/weather/now?location={城市ID}。
 */
@Component
public class QWeatherClient {

    @Resource
    private QWeatherConfiguration properties;

    @Resource
    private QWeatherTokenService tokenService;

    @Resource
    private RestTemplate restTemplate;

    /** 实时天气：GET /weather/v1/current/{latitude}/{longitude} */
    public JSONObject current(double latitude, double longitude) {
        return get("/weather/v1/current/" + coord(latitude) + "/" + coord(longitude));
    }

    /** 逐小时预报：GET /weather/v1/hourly/{latitude}/{longitude}?hours=24 */
    public JSONObject hourly(double latitude, double longitude, int hours) {
        return get("/weather/v1/hourly/" + coord(latitude) + "/" + coord(longitude) + "?hours=" + hours);
    }

    /**
     * 经纬度反查城市：GET /geo/v2/city/lookup?location={lon},{lat}
     * 注意路径是 /geo/v2/，不是老文档里的 /v2/。
     */
    public JSONObject cityLookup(double longitude, double latitude) {
        String location = URLEncoder.encode(coord(longitude) + "," + coord(latitude), StandardCharsets.UTF_8);
        return get("/geo/v2/city/lookup?location=" + location);
    }

    /** 接口规定经纬度最多支持小数点后两位。用 Locale.ROOT，避免在逗号作小数点的区域输出 39,92 */
    private String coord(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private JSONObject get(String path) {
        // 必须传 URI 对象，不能传 String。
        // exchange(String, ...) 会把字符串当 URI 模板再编码一次，把预先编码好的 %2C 变成 %252C，
        // 和风就会把它当成字面量、返回"找不到位置"。URI 对象不会走模板展开。
        URI uri = URI.create("https://" + properties.getApiHost() + path);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + tokenService.token());
        // 明确索要 gzip，并自己解压。
        // 不要指望 HttpURLConnection "透明解压"：实测（Boot 3.5 + JDK 21）拿回来的就是压缩字节，
        // 直接按 UTF-8 解析会炸在 gzip 魔数 0x1F 上。
        headers.set("Accept-Encoding", "gzip");

        // 也用 byte[] 接收。RestTemplate 默认的 StringHttpMessageConverter 是 ISO-8859-1，
        // "少云"这类中文会直接变成乱码 —— 这是最经典的坑。
        ResponseEntity<byte[]> response;
        try {
            response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        } catch (HttpStatusCodeException e) {
            String body = e.getResponseBodyAsByteArray().length == 0
                    ? "(空响应体)"
                    : new String(e.getResponseBodyAsByteArray(), StandardCharsets.UTF_8);
            if (e.getStatusCode().value() == 401) {
                // token 可能是过期或凭据配错，作废缓存让下次重新签
                tokenService.invalidate();
                throw new IllegalStateException(
                        "和风认证失败(401)。检查 kid / iss / sub 是否与控制台一致，私钥与上传的公钥是否配对。"
                                + " 原始响应：" + body, e);
            }
            throw new IllegalStateException("和风返回 " + e.getStatusCode().value() + "：" + body, e);
        }

        String body = new String(ungzip(response), StandardCharsets.UTF_8);
        JSONObject json = JSONObject.parseObject(body);
        if (json == null) {
            throw new IllegalStateException("和风返回了无法解析的内容：" + body);
        }
        return json;
    }

    /** 按 Content-Encoding 决定要不要解压。服务端也可能回 identity，所以不能无条件解。 */
    private byte[] ungzip(ResponseEntity<byte[]> response) {
        byte[] raw = response.getBody() == null ? new byte[0] : response.getBody();
        String encoding = response.getHeaders().getFirst("Content-Encoding");
        if (encoding == null || !encoding.toLowerCase().contains("gzip")) {
            return raw;
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return gzip.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("解压和风响应失败：" + e.getMessage(), e);
        }
    }
}
