package net.doudegua.config;

import net.doudegua.utils.Const;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 抢课的两条队列。
 * <p>
 * 和 {@code RabbitConfiguration} 里那条 mail 队列分开放：那个是账号功能，
 * 这个是抢课功能，生命周期不同。混在一个类里，将来删掉抢课实验时很难拆干净。
 * <p>
 * 死信用<b>默认交换机</b>（{@code x-dead-letter-exchange=""}）+ 队列名当 routing key，
 * 不再额外声明 exchange：项目里 mail 也是直接往队列发，没有 exchange 的概念。
 * 少一个对象，少一处配错的机会。真要做路由（比如按课程分片）再引入 exchange。
 * <p>
 * ⚠️ 队列参数一旦声明，<b>之后修改会报 PRECONDITION_FAILED</b>（比如给已有队列
 * 加/删参数），而且 RabbitMQ 不会自动重建。改参数时要先删队列，或者换个新名字。
 */
@Configuration
public class CourseRabbitConfiguration {

    /**
     * 抢课主队列。durable + 死信，配置和 mail 队列保持一致。
     * <p>
     * 消息体就是 {@code {uid, courseId}} 两个字段，用全局那个
     * {@code Jackson2JsonMessageConverter} 序列化。幂等键是数据库的复合主键，
     * 不需要在消息里再塞一个 requestId —— 同一对 (uid, courseId) 天然是同一件事。
     */
    @Bean
    public Queue courseSelectQueue() {
        return QueueBuilder.durable(Const.COURSE_SELECT_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", Const.COURSE_SELECT_DLQ)
                .build();
    }

    /**
     * 死信队列。消费者把消息判为"重试也没用"之后丢到这里，人工/定时任务再处理。
     * <p>
     * <b>不能只靠重投</b>：{@code default-requeue-rejected} 默认 true，
     * 抛异常的消息会被立刻重新投递，坏消息能把日志和 CPU 打满
     * （邮件那里实测 5 秒重投 16 次）。所以消费端要么吞掉并记 ERROR，
     * 要么有限重试后 reject 进死信 —— 不能无脑 rethrow。
     */
    @Bean
    public Queue courseSelectDeadLetterQueue() {
        return QueueBuilder.durable(Const.COURSE_SELECT_DLQ).build();
    }
}
