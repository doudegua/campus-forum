package net.doudegua.filter;

import com.alibaba.fastjson2.JSONObject;
import net.doudegua.utils.Const;
import net.doudegua.utils.SnowflakeIdGenerator;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 请求日志过滤器，用于记录所有用户请求信息
 */
@Slf4j
@Component
public class RequestLogFilter extends OncePerRequestFilter {

    @Resource
    SnowflakeIdGenerator generator;

    /**
     * 不走日志包装的路径。
     * <p>
     * 图片和头像接口返回的是二进制大文件。ContentCachingResponseWrapper 会把<b>整个</b>响应体
     * 缓存在内存里，这有两个后果：
     * 一是抵消掉 ImageServiceImpl 特意做的流式输出（本来 1MB 的图可以不进堆）；
     * 二是 logRequestEnd 会把上兆的二进制当成字符串写进日志，日志里全是乱码。
     * 所以这类接口直接跳过包装，不是"少记点日志"，是必须绕开。
     */
    private final Set<String> ignores = Set.of("/swagger-ui", "/v3/api-docs", "/api/image", "/api/user/avatar");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if(this.isIgnoreUrl(request.getServletPath())) {
            filterChain.doFilter(request, response);
        } else {
            long startTime = System.currentTimeMillis();
            this.logRequestStart(request);
            ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
            filterChain.doFilter(request, wrapper);
            this.logRequestEnd(wrapper, startTime);
            wrapper.copyBodyToResponse();
        }
    }

    /**
     * 判定当前请求url是否不需要日志打印
     * @param url 路径
     * @return 是否忽略
     */
    private boolean isIgnoreUrl(String url){
        for (String ignore : ignores) {
            if(url.startsWith(ignore)) return true;
        }
        return false;
    }

    /**
     * 请求结束时的日志打印，包含处理耗时以及响应结果
     * @param wrapper 用于读取响应结果的包装类
     * @param startTime 起始时间
     */
    public void logRequestEnd(ContentCachingResponseWrapper wrapper, long startTime){
        long time = System.currentTimeMillis() - startTime;
        int status = wrapper.getStatus();
        String content;
        if (status != 200) {
            content = status + " 错误";
        } else if (isTextResponse(wrapper)) {
            // 显式指定 UTF-8。原来用平台默认字符集，换台机器就可能把中文打成乱码
            content = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        } else {
            // 非文本响应只记长度。兜底：以后再加返回二进制/文件的接口，
            // 忘了加进 ignores 也不会把日志刷爆
            content = "(二进制响应 " + wrapper.getContentSize() + " 字节)";
        }
        log.info("请求处理耗时: {}ms | 响应结果: {}", time, content);
    }

    /** 只有文本类响应才适合把内容打进日志 */
    private boolean isTextResponse(ContentCachingResponseWrapper wrapper) {
        String type = wrapper.getContentType();
        if (type == null) {
            return true;
        }
        String lower = type.toLowerCase();
        return lower.contains("json") || lower.startsWith("text/");
    }

    /**
     * 请求开始时的日志打印，包含请求全部信息，以及对应用户角色
     * @param request 请求
     */
    public void logRequestStart(HttpServletRequest request){
        long reqId = generator.nextId();
        MDC.put("reqId", String.valueOf(reqId));
        JSONObject object = new JSONObject();
        request.getParameterMap().forEach((k, v) -> object.put(k, v.length > 0 ? v[0] : null));
        // 这个属性名必须和 JwtAuthorizeFilter 里 setAttribute 用的键一致。
        // 常量值原本是 "userId"、而那边写的是 "id"，导致这里永远拿到 null，
        // 日志里的身份一直是"未验证"。
        Object id = request.getAttribute(Const.ATTR_USER_ID);
        if(id != null) {
            // 能走到这里说明 JwtAuthorizeFilter 已经认证过，authentication 必然不是空，
            // 两个是由同一个过滤器一起写入的
            User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            log.info("请求URL: \"{}\" ({}) | 远程IP地址: {} │ 身份: {} (UID: {}) | 角色: {} | 请求参数列表: {}",
                    request.getServletPath(), request.getMethod(), request.getRemoteAddr(),
                    user.getUsername(), id, user.getAuthorities(), object);
        } else {
            log.info("请求URL: \"{}\" ({}) | 远程IP地址: {} │ 身份: 未验证 | 请求参数列表: {}",
                    request.getServletPath(), request.getMethod(), request.getRemoteAddr(), object);
        }
    }
}
