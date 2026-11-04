package net.doudegua.listener;

import lombok.extern.slf4j.Slf4j;
import net.doudegua.entity.dto.CourseSelectTask;
import net.doudegua.utils.Const;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 抢课消费重试耗尽后的兜底：写结果 {@code "0"}、记 ERROR、投 DLT。
 * <p>
 * 对应 Rabbit 版的 {@link CourseFailureRecoverer}，但行为不同：Rabbit 版是 A 方案
 * （重试耗尽后正常返回，消息被 ack 丢弃，不进 DLQ）；这里按设计文档选择 DLT，
 * 失败消息会保留在 {@code course.select.DLT} 里，方便事后人工核对。
 * <p>
 * <b>不自动重放 DLT</b>：没有 owner 的 DLT 就是停尸房。以后要重放，必须由人
 * 确认原因、修好再手动执行，而不是让一个定时任务无脑重投。
 * <p>
 * 为什么不用框架默认的 {@code DeadLetterPublishingRecoverer} 直接当 recoverer：
 * 它只负责投 DLT，不会写 Redis 结果键。而接口 {@code GET /api/course/result}
 * 不写结果键就会一直返回 QUEUED（或者 chosen 还在时永远排队）。所以这里先写
 * 结果、再投 DLT，两件事在同一个 recoverer 里按顺序做。
 * <p>
 * ⚠️ Redis 写失败时只记 ERROR、仍然尝试投 DLT：DB 已经回滚（enroll 是事务），
 * 但"结果键"丢了。消息在 DLT 里，DB 没有记录，人工可以用 DLT 里的 payload
 * 补一条结果。反过来如果因为 Redis 失败就不投 DLT，消息会被反复 seek 重试，
 * 而重试对"Redis 挂了"没有帮助。
 */
@Slf4j
@Component
public class CourseSelectFailureRecoverer implements ConsumerRecordRecoverer {

    /** 和 CourseSelectListener 保持一致：结果键是缓存，48 小时后自然过期。 */
    private static final Duration RESULT_TTL = Duration.ofHours(48);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 目标主题固定为 {@code course.select.DLT}，分区沿用源记录。
     * <p>
     * 框架默认目标是 {@code <topic>-dlt}（短横线），和设计文档定的
     * {@code course.select.DLT} 不一致，所以必须显式传 destinationResolver。
     */
    private final DeadLetterPublishingRecoverer deadLetterPublisher;

    public CourseSelectFailureRecoverer(StringRedisTemplate stringRedisTemplate,
                                        KafkaTemplate<?, ?> kafkaTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.deadLetterPublisher = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(Const.COURSE_SELECT_DLT, record.partition()));
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception ex) {
        // 正常情况下 value 已经被 JsonDeserializer 转成 CourseSelectTask。
        // 反序列化失败时 value 可能是 null / byte[]，此时没有 uid/courseId 可写，
        // 只能记日志后把原始记录投进 DLT（保留排查线索）。
        if (record.value() instanceof CourseSelectTask task) {
            try {
                stringRedisTemplate.opsForValue().set(
                        Const.COURSE_RESULT + task.courseId() + ":" + task.uid(),
                        "0",
                        RESULT_TTL);
            } catch (Exception redisEx) {
                log.error("选课重试耗尽后写结果键失败：uid={}, courseId={}。消息仍会投 DLT",
                        task.uid(), task.courseId(), redisEx);
            }
            log.error("选课落库重试耗尽，标记失败并投 DLT：uid={}, courseId={}, topic={}, partition={}, offset={}",
                    task.uid(), task.courseId(),
                    record.topic(), record.partition(), record.offset(), ex);
        } else {
            log.error("选课消息无法解析为 CourseSelectTask，直接投 DLT：topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset(), ex);
        }

        // 投 DLT 失败会向上抛，DefaultErrorHandler 会让这条记录重新投递。
        // 主题不存在时 DLT 投递必然失败（KafkaCourseConfiguration 已在启动时声明）。
        deadLetterPublisher.accept(record, ex);
    }
}
