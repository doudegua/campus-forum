package net.doudegua.utils;

import jakarta.annotation.Resource;
import net.doudegua.config.QWeatherConfiguration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * 和风天气 JWT 签发，是整个项目里唯一接触 Ed25519 私钥的地方。
 * <p>
 * 别和 {@link JwtUtils} 搞混：那边用对称密钥（HMAC256）保护本项目的用户登录态，
 * 签发方和验证方都是我们自己；这里用非对称密钥向第三方证明"请求来自我们的项目"，
 * 私钥留在本地、公钥交给和风，两边是不同信任域，所以只有这里需要密钥对。
 */
@Component
public class QWeatherTokenService {

    private static final String PEM_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_END = "-----END PRIVATE KEY-----";

    @Resource
    private QWeatherConfiguration properties;

    @Resource
    private ResourceLoader resourceLoader;

    private volatile String cachedToken;
    private volatile long cachedUntil;

    /**
     * 取一个可用的 JWT。内部缓存到过期前 60 秒，
     * 避免每次调天气接口都重新签名。
     */
    public String token() {
        String token = cachedToken;
        if (token != null && System.currentTimeMillis() < cachedUntil) {
            return token;
        }
        synchronized (this) {
            if (cachedToken != null && System.currentTimeMillis() < cachedUntil) {
                return cachedToken;
            }
            // iat 往前推 30 秒，防止本机和服务端之间的时钟误差导致验签失败
            long iat = System.currentTimeMillis() / 1000 - 30;
            long exp = iat + properties.getTokenLifeSeconds();
            cachedToken = sign(iat, exp);
            cachedUntil = System.currentTimeMillis() + (properties.getTokenLifeSeconds() - 60) * 1000L;
            return cachedToken;
        }
    }

    /** 作废缓存，收到 401 后强制换一个新 token */
    public void invalidate() {
        cachedToken = null;
        cachedUntil = 0;
    }

    private String sign(long iat, long exp) {
        // 只放协议规定的字段。和风文档明确要求不要加 aud / nbf，
        // typ 若出现必须是 "JWT" —— 干脆一个都不加最安全。
        String header = "{\"alg\":\"EdDSA\",\"kid\":\"" + properties.getKid() + "\"}";
        String payload = "{\"iss\":\"" + properties.getDeveloperId()
                + "\",\"sub\":\"" + properties.getProgId()
                + "\",\"iat\":" + iat + ",\"exp\":" + exp + "}";

        // 必须是 Base64URL，且和风要求不带填充的 '='
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String data = encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        try {
            Signature signature = Signature.getInstance("EdDSA");
            signature.initSign(loadPrivateKey());
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return data + "." + encoder.encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("和风 JWT 签名失败：" + e.getMessage(), e);
        }
    }

    private PrivateKey loadPrivateKey() throws Exception {
        // 用全限定名：jakarta.annotation.Resource 已经占用了 Resource 这个简名
        org.springframework.core.io.Resource resource =
                resourceLoader.getResource(properties.getPrivateKeyLocation());
        if (!resource.exists()) {
            throw new IllegalStateException("找不到 Ed25519 私钥：" + properties.getPrivateKeyLocation());
        }
        String pem;
        try (InputStream in = resource.getInputStream()) {
            pem = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 去掉 PEM 头尾和所有空白，只留 Base64 主体
        String base64 = pem.replace(PEM_BEGIN, "").replace(PEM_END, "").replaceAll("\\s", "");
        byte[] pkcs8 = Base64.getDecoder().decode(base64);
        // Java 15+ 原生支持 EdDSA；本项目用的 java-jwt 4.3.0 不支持，
        // 所以这里走 JDK 自带的 KeyFactory/Signature，不引新依赖。
        return KeyFactory.getInstance("EdDSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }
}
