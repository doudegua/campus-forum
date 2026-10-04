package net.doudegua.listener;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 验证码邮件的消费者 —— 监听 "mail" 队列，真正把信发出去。
 * <p>
 * 这里是整个项目里**唯一一条跑在异步线程上的链路**，而这一点决定了它的
 * 排查方式和别的代码完全不同：
 * <p>
 * 同期对比一下两条路径就清楚了 ——
 * <pre>
 *   同步（HTTP 请求）
 *     Controller 抛异常 → @RestControllerAdvice 接住 → 转成 RestBean JSON
 *     → 前端拿得到错误，RequestLogFilter 也记了一行日志
 *
 *   异步（本类，RabbitMQ 消费线程）
 *     没有 request、没有 response、没有返回通道
 *     → 抛异常只是线程池日志里的一段堆栈，**调用方永远不知道**
 * </pre>
 * <p>
 * 所以 {@code ask-code} 接口返回的 200 只代表"消息进了队列"，
 * 不代表"邮件发出去了"。这意味着**发信失败必须在这里自己记下来**，
 * 否则没有任何地方能发现它 —— 用户看到"发送成功"然后一直等一封不会来的信。
 * <p>
 * 加日志之前就吃过这个亏：发件地址是空字符串导致每一封都失败，
 * 而日志里只有 Spring 的堆栈、没有"给谁发、什么类型"，定位花了好几轮。
 */
@Slf4j
@Component
@RabbitListener(queues = "mail")
public class MailQueueListener {

    @Resource
    JavaMailSender javaMailSender;

    @Value("${spring.mail.username}")
    private String username;

    @RabbitHandler
    public void sendMailMessage(Map<String, Object> data) {
        String email = data.get("email").toString();
        Integer code = (Integer) data.get("code");
        String type = (String) data.get("type");

        SimpleMailMessage mailMessage = switch (type) {
            case "register" -> createMessage("欢迎注册网站",
                            "您的邮件注册验证码为：" + code + "，有效时间3分钟，打死也不要告诉别人哦～", email);
            case "reset" -> createMessage("密码重置",
                    "您正在重置密码，验证码为：" + code + "，有效时间3分钟，打死也不要告诉别人哦～", email);
            default -> null;
        };

        if (mailMessage == null) {
            // 走到这里说明生产端发了一个本类不认识的 type。不是异常，但说明两边对不上，
            // 值得留一条 warn —— 否则这条消息会**静默消失**（消息已被确认消费掉了）。
            log.warn("收到未知类型的邮件任务，已忽略：type={}，已知类型只有 register / reset", type);
            return;
        }

        try {
            javaMailSender.send(mailMessage);
            // 成功也记一条，但用 debug：默认级别下不打，需要时开日志就能看到完整链路
            log.debug("验证码邮件已发送：type={} to={}", type, maskEmail(email));
        } catch (MailException e) {
            // ⚠️ 这一段是本次改动的重点：让"异步失败"变得可见。
            //
            // 记什么、不记什么，是有讲究的：
            //   ✓ 记 type 和收件人域名 —— 排查时最需要的两个信息
            //     （"所有 @qq.com 都失败"和"只有某个地址失败"是两种完全不同的原因）
            //   ✓ 记完整异常 —— 认证失败/连接超时/地址非法 都靠它区分
            //   ✗ **不记验证码** —— 日志会流传、会被收集、会被翻出来看，
            //     记了等于把"能注册任意账号的凭据"写进日志，等于自己削弱验证码的意义。
            //     真需要对照时，查 Redis 里那份（它有 3 分钟过期时间）。
            //   ✗ 不记完整邮箱 —— 只留域名，够定位又不至于把用户邮箱堆进日志。
            log.error("验证码邮件发送失败：type={} to={} —— 检查 MAIL_PASSWORD（163 的 SMTP 授权码）"
                    + "和 MAIL_USERNAME（发件地址，不能为空）", type, maskEmail(email), e);

            // 这里**故意不 rethrow**，理由是实测出来的：
            //
            // 抛出去会让 Spring AMQP 把这条消息判为处理失败，而
            // `default-requeue-rejected` 默认是 **true** —— 于是消息被立刻重新投递，
            // 又失败，再投递…… 实测一次失败的注册在 5 秒内重投了 **16 次**、
            // 刷出 1271 行日志。对"认证失败"这种错误，重试一万次也还是失败，
            // 只是把日志淹掉（真问题反而被埋了）。
            //
            // 吞掉之后：消息被确认并丢弃，而**失败这件事已经被上面那条 ERROR 记下来了** ——
            // 这正是"让异步失败可见"的目的。代价是这条验证码不会自动重发，
            // 用户需要重新点一次"获取验证码"，而对 3 分钟有效期的验证码来说可以接受。
            //
            // 想做得更好就是死信队列：失败的消息进 DLQ，能事后重发、也能看积压。
            // 那是"正确但重"的方案，现在不做 —— 等真有量了再说。
        }
    }

    private SimpleMailMessage createMessage(String title, String content, String email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setSubject(title);
        message.setText(content);
        message.setTo(email);
        message.setFrom(username);
        return message;
    }

    /**
     * 把邮箱打码成 {@code ***@qq.com} 的形式。
     * <p>
     * 为什么按域名保留而不是整个打码：排查"发信失败"时，域名的价值很高 ——
     * {@code smtp.163.com} 拒收某个域的地址、和认证失败，是两种完全不同的原因。
     * 而本地部分对定位没有帮助，隐去即可。
     */
    private String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return "(空地址)";
        }
        int at = email.indexOf('@');
        return at < 0 ? "***" : "***" + email.substring(at);
    }
}
