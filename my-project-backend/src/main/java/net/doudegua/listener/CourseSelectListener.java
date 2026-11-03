package net.doudegua.listener;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.service.CourseEnrollmentService;
import net.doudegua.utils.Const;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 抢课消费者。
 * <p>
 * DB 事务在 {@link CourseEnrollmentService#enroll} 里完成；本类只在事务结束后
 * 写结果键，以及 DB 判满时把 Redis 里的 chosen 名额还回去。
 * Redis 不参与 DB 事务，所以补偿必须放在事务边界之后。
 */
@Slf4j
@Component
@RabbitListener(
        queues = Const.COURSE_SELECT_QUEUE,
        containerFactory = "courseListenerContainerFactory")
public class CourseSelectListener {

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
        // TODO: 预热上线后，TTL 跟随活动周期调整
        stringRedisTemplate.opsForValue().set(
                Const.COURSE_RESULT + courseId + ":" + uid, result, Duration.ofHours(48));
    }
}
