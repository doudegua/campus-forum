package net.doudegua.utils;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class JwtUtils {

    @Value("${spring.security.jwt.key}")
    String key;

    @Value("${spring.security.jwt.expire}")
    int expire;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    public boolean invalidateJwt(String headerToken) {
        String token = this.convertToken(headerToken);
        if(token == null) return false;
        Algorithm algorithm = Algorithm.HMAC256(key);
        JWTVerifier verifier = JWT.require(algorithm).build();
        try {
            DecodedJWT decodedJWT = verifier.verify(token);
            String id = decodedJWT.getId();
            return deleteToken(id, decodedJWT.getExpiresAt());
        } catch (JWTVerificationException exception) {
            return false;
        }
    }

    private boolean deleteToken(String uuid, Date expireTime) {
        if(this.isInvalidToken(uuid)) return false;
        Date now = new Date();
        long expire = Math.max(expireTime.getTime() - now.getTime(), 0);
        stringRedisTemplate.opsForValue().set(Const.JWT_BLACK_LIST + uuid, "", expire, TimeUnit.MILLISECONDS);
        return true;
    }

    private boolean isInvalidToken(String uuid) {
        return stringRedisTemplate.hasKey(Const.JWT_BLACK_LIST + uuid);
    }

    public DecodedJWT resolveJwt(String headerToken) {
        String token = convertToken(headerToken);
        if (token == null) {return null;}
        Algorithm algorithm = Algorithm.HMAC256(key);
        JWTVerifier verifier = JWT.require(algorithm).build();
        try {
            DecodedJWT decodedJWT = verifier.verify(token);
            String jwtId = decodedJWT.getId();
            if (jwtId != null && this.isInvalidToken(jwtId)) return null;
            Date expiresAt = decodedJWT.getExpiresAt();
            return new Date().after(expiresAt) ? null : decodedJWT;
        } catch (JWTVerificationException exception) {
            return null;
        }
    }

    public String createJwt(UserDetails userDetails, int id, String username) {
        Algorithm algorithm = Algorithm.HMAC256(key);
        Date expire = this.expireTimer();
        return JWT.create()
                .withJWTId(UUID.randomUUID().toString())
                .withClaim("id", id)
                .withClaim("name", username)
                .withClaim("authorities", userDetails.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList())
                .withExpiresAt(expire)
                .withIssuedAt(new Date())
                .sign(algorithm);
    }

    public Date expireTimer() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.HOUR, expire * 24);
        return calendar.getTime();
    }

    public UserDetails decodedJwtToUserDetails(DecodedJWT decodedJWT) {
        Map<String, Claim> claims = decodedJWT.getClaims();
        Claim authoritiesClaim = claims.get("authorities");
        List<String> authorities = authoritiesClaim != null && !authoritiesClaim.isNull()
                ? authoritiesClaim.asList(String.class)
                : new ArrayList<>();
        return User
                .withUsername(claims.get("name").asString())
                .password("******")
                .authorities(authorities.toArray(new String[0]))
//                .authorities(claims.get("authorities").asString()) user无法正常创建，导致最终302
                //从声明Claim到三元运算符，都是ai自己新加的东西
                .build();
    }

    public Integer decodedJwtToUserId(DecodedJWT decodedJWT) {
        Map<String, Claim> claims = decodedJWT.getClaims();
        return claims.get("id").asInt();
    }

    private String convertToken(String token) {
        if(token == null || !token.startsWith("Bearer ")) {
            return null;
        }
        return token.substring(7);
    }
}
