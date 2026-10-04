package net.doudegua.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.doudegua.utils.Const;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 跨域响应头。
 * <p>
 * 前端跑在 Vite dev server（5173），后端在 8080，属于跨源；浏览器要求后端
 * 在响应里显式声明允许，否则前端拿不到数据。
 * <p>
 * 执行顺序放在最前面（Order -102，见 {@link Const#ORDER_CORS}）。这一点是必须的：
 * 如果限流或鉴权先把请求拒了，响应里就不会有 CORS 头，浏览器会把 403/401
 * 报成 "CORS 错误"，真正的原因反而被盖住，排查时会绕远路。
 */
@Component
@Order(Const.ORDER_CORS)
public class CorsFilter extends HttpFilter {

    @Override
    public void doFilter(HttpServletRequest request,
                            HttpServletResponse response,
                            FilterChain chain) throws IOException, ServletException {
        // 只加响应头，不做拦截：不从这里短路 OPTIONS 预检，加完头继续往下走。
        // 注意预检请求是不带 Authorization 的（浏览器不会把自定义头放进预检），
        // 所以它必须能在"未认证"的情况下通过，实测当前返回 200。
        // 以后如果收紧安全配置，预检这条路要一并确认。
        this.addCorsHeader(request, response);
        chain.doFilter(request, response);
    }

    private void addCorsHeader(HttpServletRequest request, HttpServletResponse response) {
        // 把请求里的 Origin 原样回显，等于允许任意来源访问。
        // 这里刻意没有 Access-Control-Allow-Credentials，所以浏览器不会跨源带 Cookie，
        // 认证靠的是 Authorization 头而不是 Cookie —— 这两件事是配套的。
        // 哪天要加 withCredentials，就必须把这里换成来源白名单，不能再回显任意 Origin。
        response.addHeader("Access-Control-Allow-Origin", request.getHeader("Origin"));
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        // Authorization 必须登记在这里，否则带 token 的请求过不了预检，前端只会看到
        // 一个语焉不详的 CORS 报错。以后前端再加自定义请求头，也要来这一行补上。
        response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
    }
}
