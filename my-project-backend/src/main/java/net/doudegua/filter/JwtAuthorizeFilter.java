package net.doudegua.filter;

import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.doudegua.utils.JwtUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 把请求头里的 JWT 翻译成 Spring Security 认识的认证信息。
 * <p>
 * 在 SecurityConfiguration 里被注册到 UsernamePasswordAuthenticationFilter 之前，
 * 所以它先于表单登录那条链执行，每个请求都会经过。
 * <p>
 * 关键设计：这个类只负责"识别身份"，不负责"拒绝请求"。
 * token 缺失或无效时它什么都不做，直接放行，SecurityContext 里保持空的。
 * 真正决定"这个接口要不要登录"的是 SecurityConfiguration 里的
 * anyRequest().authenticated()。
 * <p>
 * 把这两件事分开，白名单接口（登录、注册、邮件验证码）才能带着一个坏 token
 * 也正常通过 —— 否则 token 一过期，用户连重新登录都做不到。
 */
@Component
public class JwtAuthorizeFilter extends OncePerRequestFilter {

    // 继承 OncePerRequestFilter 而不是直接实现 Filter：保证一次请求只认证一遍。
    // 请求被 forward / include 到别的资源时不会重复执行。

    @Resource
    JwtUtils jwtUtils;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        // resolveJwt 内部依次做了三件事：验签、查 Redis 黑名单（登出后 token 会被拉黑）、
        // 查过期时间。任何一步不过都返回 null，这里不需要再判断失败原因。
        DecodedJWT decodedJWT = jwtUtils.resolveJwt(authorization);
        if(decodedJWT != null) {
            UserDetails userDetails = jwtUtils.decodedJwtToUserDetails(decodedJWT);
            // 第三个参数传 authorities：Security 后面靠它做角色/权限判断。
            // 第二个参数 credentials 传 null —— JWT 认证不需要密码，密码只在登录那一次用得上。
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            // 放进 SecurityContext，本次请求内 Security 就知道"当前是谁"。
            // SecurityContextHolder 底层是 ThreadLocal，请求结束时由 Spring Security 清理，
            // 所以必须配合 SecurityConfiguration 里的 STATELESS 会话策略 —— 它不靠 Session 保存登录态。
            SecurityContextHolder.getContext().setAuthentication(authentication);
            // 把用户 id 挂到请求属性上，控制器用 @RequestAttribute("id") 取（见 AccountController）。
            // 这是本项目里控制器得知"当前用户是谁"的唯一途径，删掉它会有一批接口直接报错。
            request.setAttribute("id", jwtUtils.decodedJwtToUserId(decodedJWT));
        }
        // 认证成功与否都继续往下走：放不放行交给后面的 Security 授权规则决定。
        filterChain.doFilter(request, response);
    }
}
