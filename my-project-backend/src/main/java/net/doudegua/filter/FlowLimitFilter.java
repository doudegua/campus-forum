package net.doudegua.filter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.entity.RestBean;
import net.doudegua.utils.Const;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 按 IP 的粗粒度限流。
 * <p>
 * 执行顺序排在 CORS（-102）之后、Spring Security 之前，见 {@link Const#ORDER_LIMIT}。
 * 放在 CORS 后面是有意的：被限流拒绝的请求也要带上 CORS 头，
 * 否则前端看到的是跨域错误而不是真正的 403。
 * <p>
 * 两类请求用两套完全不同的策略，因为它们的"成本"不是一回事：
 * <ul>
 *   <li><b>API</b>：按请求数。一次调用的代价和它传多少数据无关</li>
 *   <li><b>图片</b>：秒级请求数（阈值高得多）+ 日流量按字节。
 *       一个带图的页面会在同一秒里并发拉几十张，用 API 那套阈值会把正常浏览也拦掉</li>
 * </ul>
 */
@Slf4j
@Component
@Order(Const.ORDER_LIMIT)
public class FlowLimitFilter extends HttpFilter {

    /* ==================== API 接口：按请求数 ==================== */

    /** 一个窗口的长度。窗口从该 IP 本轮第一次请求起算，不是滑动窗口 */
    private static final Duration WINDOW = Duration.ofSeconds(3);

    /**
     * 一个窗口内允许的 API 请求数，超过就拉黑。
     * <p>
     * 调它之前先想清楚要防的是什么：防前端写出死循环的话，几百到几千都够
     * （每 10ms 一次的循环是 100 次/秒，几秒内必然触发）；
     * 想防有意的攻击，这个机制按 IP 计数、封 30 秒，本身就不适合当防线。
     * <p>
     * 图片已经不占这个额度了，所以这个数可以按"一次页面加载约 5~6 次 API 调用"来估。
     */
    private static final long MAX_REQUESTS = 10000;

    /* ==================== 图片：秒限流 + 日流量 ==================== */

    /** 图片路径前缀 */
    private static final String IMAGE_PATH = "/api/image";

    /**
     * 图片每秒请求数上限。比 API 那个宽松得多：
     * 浏览器会为同一个源开 6 个并发连接，一个挂了几十张图的页面全在同一秒里拉。
     * 这一层只挡明显异常的突发，管总量的是下面的日流量。
     */
    private static final long IMAGE_MAX_PER_SECOND = 100;

    /**
     * 图片日流量上限（单 IP），按字节算。
     * <p>
     * 为什么用字节而不是请求数：100 次请求可能是 1MB，也可能是 90MB，
     * 请求数和实际成本无关。
     * <p>
     * 2GB 的依据：一张压缩后的图约 300KB，2GB ≈ 7000 张。正常翻帖子到不了
     * （而且图片带 immutable 长缓存，回访根本不发请求），抓取则会被卡在这个量级。
     * <p>
     * 键里带日期，触发之后要等到次日才恢复。开发时反复硬刷新会绕过缓存、
     * 有可能把自己刷进去，所以本机回环地址默认是跳过的（见 {@link #skipLoopback}）。
     */
    private static final long IMAGE_DAILY_BYTES = 2L * 1024 * 1024 * 1024;

    /** 拉黑时长。只用于秒级那一层 —— 日流量超了不是"封一会儿"，而是等到次日 */
    private static final Duration BLOCK_TIME = Duration.ofSeconds(30);

    /** 日流量键的存活时间。键里已带日期，跨天本就是新键，这个 TTL 只为不堆积垃圾 */
    private static final Duration BYTES_KEY_TTL = Duration.ofDays(2);

    /**
     * 是否跳过本机回环地址（127.0.0.1 / ::1）的限流。
     * <p>
     * 开发时开着，免得自己把自己刷封 —— 尤其日流量那个，硬刷新绕过缓存很容易撞上，
     * 而后果是等一整天。
     * <p>
     * <b>上线前必须处理这个坑：</b> {@code getRemoteAddr()} 在反向代理后面拿到的是
     * <b>代理的 IP</b>。如果 nginx 和这个应用跑在同一台机器上，那么所有请求看起来都来自
     * 127.0.0.1 —— 这个开关一开，限流就对<b>所有人</b>失效，而且不留任何痕迹：
     * 页面上一切正常，只是限流再也不起作用了。
     * <p>
     * 所以一旦前面挂了代理，要么把它关掉，要么改成从 X-Forwarded-For 取真实来源
     * （并且只信任自己那层代理写入的值）。
     */
    @Value("${flow-limit.skip-loopback:true}")
    private boolean skipLoopback;

    /**
     * 自增计数，并保证这个键一定带过期时间。
     * <p>
     * 必须做成原子操作。原先拆成"先 hasKey 再 increment"两步，存在 TOCTOU：
     * 键恰好在这两步之间过期的话，INCR 会新建一个<b>没有 TTL</b> 的键，
     * 而补 TTL 的那一支从此再也走不到 —— 计数器就永久化了，只增不减；
     * 攒过上限之后该 IP 会被反复封锁 30 秒，等同于永久拉黑。
     * 这个 bug 在本机开发时真实发生过（flow:counter:... 的 TTL 是 -1）。
     * <p>
     * 脚本里额外判一次 TTL &lt; 0，是为了修复已经存在的、没有过期时间的旧键。
     */
    private static final RedisScript<Long> COUNT_SCRIPT = RedisScript.of("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 or redis.call('TTL', KEYS[1]) < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    @Resource
    StringRedisTemplate stringRedisTemplate;

    /**
     * 把"跳过回环"这件事打到启动日志里。
     * 它默认是开的，而一旦前面有同机代理，它的效果就是"限流静默失效" ——
     * 这种事必须在日志里留个痕迹，不能让人靠猜。
     */
    @PostConstruct
    void warnAboutLoopbackSkip() {
        if (skipLoopback) {
            log.warn("限流已跳过本机回环地址（flow-limit.skip-loopback=true）。"
                    + "若前面挂了同机的反向代理，所有请求都会表现为来自 127.0.0.1，限流将形同虚设");
        }
    }

    @Override
    public void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // 只能用 IP 做粒度：这个过滤器跑在 Spring Security 之前，拿不到用户身份。
        // 另外 getRemoteAddr() 在反向代理后面拿到的是代理的 IP，
        // 将来真上线要改读 X-Forwarded-For，并且只信任自己那层代理写入的值。
        String address = request.getRemoteAddr();

        // 本机直接放行，两层都不计数
        if (isLoopback(address)) {
            chain.doFilter(request, response);
            return;
        }

        if (request.getServletPath().startsWith(IMAGE_PATH)) {
            this.handleImage(address, request, response, chain);
            return;
        }
        if(this.tryCount(address)) {
            chain.doFilter(request, response);
        } else {
            this.writeBlockMessage(response, "操作频繁，稍后再试");
        }
    }

    /** 判定是否本机回环地址。关掉开关就永远返回 false */
    private boolean isLoopback(String ip) {
        if (!skipLoopback || ip == null) {
            return false;
        }
        // 127.0.0.0/8 整段都是回环，不只是 127.0.0.1
        if (ip.startsWith("127.")) {
            return true;
        }
        // IPv6 回环：JDK 可能给成 ::1，也可能给成展开形式；IPv4 映射形式也一并认了
        return "::1".equals(ip)
                || "0:0:0:0:0:0:0:1".equals(ip)
                || ip.startsWith("::ffff:127.");
    }

    /**
     * 图片的两层限制。
     * <p>
     * 日流量的累加只能放在请求<b>之后</b>：请求进来时还不知道这次要传多少字节，
     * 得等控制器把 Content-Length 定下来才拿得到。所以超限拦的是"之后"的请求，
     * 当前这个已经放出去了 —— 对配额来说没关系，误差最多就是最后一张图。
     */
    private void handleImage(String ip, HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (usedBytesToday(ip) >= IMAGE_DAILY_BYTES) {
            writeBlockMessage(response, "今日图片流量已用完，明天再来");
            return;
        }
        if (!tryImageCount(ip)) {
            writeBlockMessage(response, "请求太快了，稍等一下");
            return;
        }

        chain.doFilter(request, response);

        // 响应头这时候已经定下来了，才拿得到这一次实际传了多少字节
        String length = response.getHeader("Content-Length");
        if (length != null) {
            try {
                addBytesToday(ip, Long.parseLong(length));
            } catch (NumberFormatException ignored) {
                // 记不上就算了，不值得为了统计影响响应
            }
        }
    }

    /**
     * 先看有没有被拉黑，再走计数判断。
     * <p>
     * 这里不需要再自己加锁：计数那步由 Redis 脚本保证原子。
     * （原来用 synchronized (ip.intern()) 串行化，但它只在单个 JVM 内有效，
     * 多实例部署挡不住，还要占用全局的字符串锁。）
     */
    private boolean tryCount(String ip) {
        if(stringRedisTemplate.hasKey(Const.FLOW_LIMIT_BLOCK + ip)) {
            return false;
        }
        Long count = stringRedisTemplate.execute(
                COUNT_SCRIPT,
                List.of(Const.FLOW_LIMIT_COUNTER + ip),
                String.valueOf(WINDOW.toSeconds()));
        if(count != null && count > MAX_REQUESTS) {
            stringRedisTemplate.opsForValue().set(Const.FLOW_LIMIT_BLOCK + ip, "", BLOCK_TIME);
            return false;
        }
        return true;
    }

    /**
     * 图片的秒级计数，用和 API 完全独立的键。
     * 不做拉黑：一秒的窗口偶尔漏一点无所谓，没必要为此封 30 秒。
     */
    private boolean tryImageCount(String ip) {
        Long count = stringRedisTemplate.execute(
                COUNT_SCRIPT,
                List.of(Const.FLOW_IMAGE_SECOND + ip),
                "1");
        return count == null || count <= IMAGE_MAX_PER_SECOND;
    }

    /**
     * 键里带日期，跨天自然就是新键，不需要任何"重置"逻辑。
     * 这也让设 TTL 那一步变成纯粹的清理而非正确性依赖 ——
     * 万一它失败（进程正好挂掉），也只是留个昨天的键，不会像 API 计数器那样把自己封死。
     */
    private String dailyBytesKey(String ip) {
        return Const.FLOW_IMAGE_BYTES + ip + ":" + LocalDate.now();
    }

    private long usedBytesToday(String ip) {
        String value = stringRedisTemplate.opsForValue().get(dailyBytesKey(ip));
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void addBytesToday(String ip, long bytes) {
        String key = dailyBytesKey(ip);
        Long total = stringRedisTemplate.opsForValue().increment(key, bytes);
        if (total != null && total == bytes) {
            // 只在刚创建时设过期，思路和 COUNT_SCRIPT 一致
            stringRedisTemplate.expire(key, BYTES_KEY_TTL);
        }
    }

    private void writeBlockMessage(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        // 写出去的是 JSON，Content-Type 就得是 JSON。之前声明成 text/html 是错的
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write(RestBean.failure(403, message).asJsonString());
    }

}
