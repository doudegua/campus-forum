package net.doudegua.listener;

import lombok.extern.slf4j.Slf4j;
import net.doudegua.utils.Const;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 抢课的消费者 —— <b>骨架，核心留空</b>。
 * <p>
 * 现在它只做一件事：把收到的消息打进日志。因为生产端还没写，
 * 队列里不会有消息，所以它不会丢任何东西。<b>接生产端之前必须先实现下面这段</b>，
 * 否则消息会被这个"只打日志"的消费者确认掉，等于静默丢弃 —— 库存扣了、DB 没写。
 * <p>
 * 要实现的三步（顺序不能换）：
 * <pre>
 * 1. int inserted = courseSelectionMapper.insertIgnore(uid, courseId);
 *      inserted == 0 → 重复投递/早就抢到，直接 return（消息会被 ack，不再 +1）
 *
 * 2. int updated = courseMapper.incrementSelectedIfAvailable(courseId);
 *      updated == 1 → 成功，写结果键 course:result:{courseId}:{uid} = "1"
 *      updated == 0 → DB 名额已满（Redis 和 DB 账本不一致）：
 *                     事务回滚（把第 1 步插的那行删掉）→ 回补 Redis（INCR + SREM）
 *                     → 写结果 "0"
 *
 * 3. 1 和 2 必须在同一个 {@code @Transactional} 方法里：
 *      第 2 步失败要能把第 1 步插进去的关系行一起回滚掉，否则这份记录是脏的。
 * </pre>
 * <p>
 * <b>失败处理是本类的重点，不是"try-catch 包一下"</b>：
 * <ul>
 *   <li>DB 临时故障 → 重试有意义。但别用无限重投（见 {@code MailQueueListener} 的教训）；
 *       简单做法是 catch 后 {@code log.error} + 不抛（消息丢失但可查），
 *       更正确是 {@code throwing} + 有限重试 + 死信。</li>
 *   <li>业务失败（名额满、重复）→ 重试一万次也一样，绝不能进重试。</li>
 *   <li>补偿（INCR + SREM）必须放在事务结果确定之后执行 ——
 *       写在事务里的话，事务回滚后那段补偿照样会跑，库存就多回来了。</li>
 * </ul>
 * <p>
 * 消费并发度和 prefetch 现在都是 Spring AMQP 默认值（单消费者、prefetch 250）。
 * 接上生产端之后要显式配 {@code spring.rabbitmq.listener.simple.concurrency/prefetch}，
 * 否则一个消费者打满 DB 连接池，或者一次预取几百条把内存和延迟一起拉高。
 */
@Slf4j
@Component
@RabbitListener(queues = Const.COURSE_SELECT_QUEUE)
public class CourseSelectListener {

    /**
     * ⚠️ 骨架版：只记录，不处理。这个方法目前会<b>确认并丢弃</b>消息。
     * 在实现真正的消费逻辑之前，不要接生产端。
     */
    @RabbitHandler
    public void onCourseSelect(Map<String, Object> message) {
        log.warn("收到选课消息，但消费端尚未实现，消息已被确认丢弃：{}", message);
        // TODO(核心)：insertIgnore → 条件 UPDATE → 写结果键；失败补偿见类注释。
    }
}
