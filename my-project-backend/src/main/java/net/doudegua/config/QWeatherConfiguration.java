package net.doudegua.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 和风天气配置，对应 application.yaml 里的 qweather 块。
 */
@Data
@Component
@ConfigurationProperties(prefix = "qweather")
public class QWeatherConfiguration {

    /** 你的专属 API Host，形如 ma487v6uam.re.qweatherapi.com（控制台-设置） */
    private String apiHost;

    /** 凭据 ID，填进 JWT Header 的 kid（控制台-项目管理） */
    private String kid;

    /** 开发者 ID，Q 开头，填进 JWT Payload 的 iss（控制台-设置） */
    private String developerId;

    /** 项目 ID，填进 JWT Payload 的 sub（控制台-项目管理） */
    private String progId;

    /** Ed25519 私钥位置，支持 classpath: / file: / 绝对路径 */
    private String privateKeyLocation = "classpath:qweather/ed25519-private.pem";

    /** 单个 JWT 有效期（秒），和风上限 86400 */
    private long tokenLifeSeconds = 900;
}
