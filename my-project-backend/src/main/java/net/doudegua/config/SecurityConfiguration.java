package net.doudegua.config;

import com.auth0.jwt.JWT;
import jakarta.annotation.Resource;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.doudegua.entity.RestBean;
import net.doudegua.entity.dto.Account;
import net.doudegua.entity.vo.response.AuthorizeVo;
import net.doudegua.filter.JwtAuthorizeFilter;
import net.doudegua.service.AccountService;
import net.doudegua.utils.JwtUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;

@Configuration
public class SecurityConfiguration {

    @Resource
    JwtUtils jwtUtils;

    @Resource
    JwtAuthorizeFilter jwtAuthorizeFilter;

    @Resource
    AccountService accountService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(conf -> conf
                        .requestMatchers("/api/auth/**", "/error").permitAll()
                        // 取图必须放行。帖子内容里的图片是 <img src="/api/image/xxx">，
                        // 浏览器发这种请求时不会带 Authorization 头（前端把 token 存在
                        // localStorage 里、靠请求头传），所以只要还要求登录，
                        // 每张图都只会拿到 401 的 JSON，渲染成破图。
                        // 只放行 GET，上传（POST /api/image/upload）仍然需要登录。
                        // 代价：知道 URL 的人就能看图。key 是 128 位随机 UUID，猜不出来，
                        // 效果等同"拿到链接就能看"，这也是论坛图片的常规模型。
                        .requestMatchers(HttpMethod.GET, "/api/image/*").permitAll()
//                        .requestMatchers("/api/test/**").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(conf -> conf
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler(this::onAuthenticationSuccess)
                        .failureHandler(this::onAuthenticationFailure)
                        .permitAll()
                )
                .logout(conf -> conf
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(this::onLogoutSuccess)
                )
                .exceptionHandling(conf -> conf
                        .authenticationEntryPoint(this::onUnauthorized)
                        .accessDeniedHandler(this::onAccessDenied)
                )
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(conf -> conf
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtAuthorizeFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    public void onAccessDenied(HttpServletRequest request,
                               HttpServletResponse response,
                               AccessDeniedException accessDeniedException) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(RestBean.failure(403, accessDeniedException.getMessage()).asJsonString());
    }

    public void onUnauthorized(HttpServletRequest request,
                               HttpServletResponse response,
                               AuthenticationException authenticationException) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(RestBean.failure(401, authenticationException.getMessage()).asJsonString());
    }

    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        User user = (User) authentication.getPrincipal();
        Account account = accountService.findAccountByNameOrEmail(user.getUsername());
        String token = jwtUtils.createJwt(user, account.getId(), account.getUsername());
//        AuthorizeVo vo = new AuthorizeVo();
//        vo.setExpire(JWT.decode(token).getExpiresAt());
//        vo.setRole(account.getRole());
//        vo.setToken(token);
//        vo.setUsername(account.getUsername());
        AuthorizeVo authorizeVo = account.asViewObject(AuthorizeVo.class, v -> {
            v.setExpire(JWT.decode(token).getExpiresAt());
            v.setToken(token);
        });
        response.getWriter().write(RestBean.success(authorizeVo).asJsonString());
    }

    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException authenticationException) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(RestBean.failure(401, "用户名或密码错误").asJsonString());
    }

    public void onLogoutSuccess(HttpServletRequest request,
                                HttpServletResponse response,
                                Authentication authentication) throws IOException, ServletException {
        response.setContentType("application/json;charset=UTF-8");
        PrintWriter writer = response.getWriter();
        String authorization = request.getHeader("Authorization");
        if(jwtUtils.invalidateJwt(authorization)) {
            writer.write(RestBean.success(new AuthorizeVo()).asJsonString());
        } else {
            writer.write(RestBean.failure(400, "退出登录失败").asJsonString());
        }
    }
}
