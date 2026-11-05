package net.doudegua.listener;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.utils.Const;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 重试耗尽后的兜底（A 方案）。
 * <p>
 * 这个类只做三件事：把失败写进结果键、记一条能排查的日志、正常返回让消息被 ack 丢弃。
 * 它不补偿名额、不重放、不进 DLQ —— 业务上就是“这个用户这次失败”。
 */
@Slf4j
@Component
public class CourseFailureRecoverer implements MessageRecoverer {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    MessageConverter messageConverter;

    @Override
    public void recover(Message message, Throwable cause) {
        Map<String, Object> body =
                (Map<String, Object>) messageConverter.fromMessage(message);
        int uid = ((Number) body.get("uid")).intValue();
        int courseId = ((Number) body.get("courseId")).intValue();

        stringRedisTemplate.opsForValue().set(
                Const.COURSE_RESULT + courseId + ":" + uid,
                "0",
                Duration.ofHours(48));

        log.error("选课落库重试耗尽，标记失败：uid={}, courseId={}", uid, courseId, cause);
    }
}
