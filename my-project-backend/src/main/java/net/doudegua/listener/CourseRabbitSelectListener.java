package net.doudegua.listener;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.service.CourseEnrollmentService;
import net.doudegua.utils.Const;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 抢课消费者（RabbitMQ 版），默认启用。
 * <p>
 * Kafka 版见 {@link CourseSelectListener}；用 {@code course.mq=rabbit|kafka} 切换，
 * 默认 rabbit，保证 dev 不装 Kafka 也能跑。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "course.mq", havingValue = "rabbit", matchIfMissing = true)
@RabbitListener(
        queues = Const.COURSE_SELECT_QUEUE,
        containerFactory = "courseListenerContainerFactory")
public class CourseRabbitSelectListener {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    CourseEnrollmentService courseEnrollmentService;

    @RabbitHandler
    public void onCourseSelect(Map<String, Object> message) {
        int uid = ((Number) message.get("uid")).intValue();
        int courseId = ((Number) message.get("courseId")).intValue();

        // 异常往上冒：交给容器的重试/死信策略
        CourseEnrollmentService.EnrollResult r = courseEnrollmentService.enroll(uid, courseId);

        switch (r) {
            case OK, DUPLICATE -> writeResult(uid, courseId, "1");
            case SEAT_FULL -> {
                log.warn("DB 名额已满，回补 chosen：uid={}, courseId={}", uid, courseId);
                releaseChosen(uid, courseId);
                writeResult(uid, courseId, "0");
            }
        }
    }

    private void releaseChosen(int uid, int courseId) {
        stringRedisTemplate.opsForSet()
                .remove(Const.COURSE_CHOSEN + courseId, String.valueOf(uid));
    }

    private void writeResult(int uid, int courseId, String result) {
        stringRedisTemplate.opsForValue().set(
                Const.COURSE_RESULT + courseId + ":" + uid, result, Duration.ofHours(48));
    }
}
