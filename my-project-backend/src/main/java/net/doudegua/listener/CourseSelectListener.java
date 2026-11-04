package net.doudegua.listener;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.entity.dto.CourseSelectTask;
import net.doudegua.service.CourseEnrollmentService;
import net.doudegua.utils.Const;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 抢课消费者（Kafka 版）。
 * <p>
 * DB 事务在 {@link CourseEnrollmentService#enroll} 里完成；本类只在事务结束后
 * 写结果键，以及 DB 判满时把 Redis 里的 chosen 名额还回去。
 * Redis 不参与 DB 事务，所以补偿必须放在事务边界之后。
 * <p>
 * <b>ack 的时机</b>：SQL 和结果键都写完之后才 {@code acknowledge()}。
 * 顺序反了（先 ack 再处理）在"消息已确认、进程却崩了"时会丢消息；现在最坏是
 * 重复消费，而重复由 {@code db_course_selection} 的复合主键 + {@code INSERT IGNORE}
 * 挡掉，写结果键也是幂等的。
 * <p>
 * <b>异常策略</b>：业务失败（重复、名额满）正常返回并写结果，<b>不抛异常</b>
 * —— 抛出去只会让同一件事重试 4 次，最后进 DLT 制造假告警。基础设施失败
 * （DB 连不上、Redis 写失败）直接向上抛，交给
 * {@code DefaultErrorHandler}：重试 3 次（间隔 1 秒），耗尽后由
 * {@link CourseSelectFailureRecoverer} 写结果 {@code "0"} 并投
 * {@code course.select.DLT}。
 * <p>
 * ⚠️ 原来 Rabbit 版的类注释说"骨架版会 ack 并丢弃消息"，那一版是刻意留的坑；
 * 现在正常返回 = 提交 offset，效果同样是"消息没了"，所以只有真的处理完才返回。
 */
@Slf4j
@Component
public class CourseSelectListener {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    CourseEnrollmentService courseEnrollmentService;

    @KafkaListener(
            topics = Const.COURSE_SELECT_TOPIC,
            groupId = Const.COURSE_SELECT_GROUP,
            containerFactory = "courseKafkaListenerContainerFactory")
    public void onCourseSelect(CourseSelectTask task, Acknowledgment ack) {
        int uid = task.uid();
        int courseId = task.courseId();

        // 异常往上冒：交给容器的 DefaultErrorHandler 做有限重试 + DLT
        CourseEnrollmentService.EnrollResult r = courseEnrollmentService.enroll(uid, courseId);

        switch (r) {
            case OK, DUPLICATE -> writeResult(uid, courseId, "1");
            case SEAT_FULL -> {
                log.warn("DB 名额已满，回补 chosen：uid={}, courseId={}", uid, courseId);
                releaseChosen(uid, courseId);
                writeResult(uid, courseId, "0");
            }
        }

        // 处理完 DB 和结果键才提交 offset。中途抛异常时不会执行到这里，
        // DefaultErrorHandler 会在重试耗尽后自己提交"已恢复"的那条记录。
        ack.acknowledge();
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
