package net.doudegua.config;

import net.doudegua.entity.dto.CourseSelectTask;
import net.doudegua.listener.CourseSelectFailureRecoverer;
import net.doudegua.utils.Const;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * 抢课链路迁到 Kafka 之后的主题与消费者配置。
 * <p>
 * <b>只碰抢课</b>：mail 继续走 {@code EmailRabbitConfiguration} / {@code MailQueueListener}，
 * 这里不声明任何 mail 主题，也不改 {@code spring.rabbitmq}。
 * <p>
 * 为什么主题由 {@link NewTopic} 显式声明，而不是让 broker 自动建：
 * 自动建主题使用 broker 的 {@code num.partitions} 默认值，分区数会变成一个
 * 没人 review 的隐式配置；抢课依赖 "key=courseId 落到同一分区"，分区数必须
 * 在代码里看得见。
 * <p>
 * ⚠️ {@link org.springframework.kafka.config.KafkaAdmin} 只在应用启动时建主题。
 * 如果启动时 Kafka 不在，主题不会在稍后自动补建（需重启应用或手工建）。
 */
@Configuration
@ConditionalOnProperty(name = "course.mq", havingValue = "kafka")
public class KafkaCourseConfiguration {

    /**
     * 抢课主主题。
     * <p>
     * 3 个分区 + RF=1：单机 KRaft，副本数只能给 1；3 个分区让不同课程并行，
     * 同一门课因为 key=courseId 仍然串行。分区数只能加不能减，加分区还会
     * 改变 key→分区映射，现有活动期间不要动。
     * <p>
     * {@code min.insync.replicas=1} 是单节点下唯一能给的值。配 {@code acks=all}
     * 只能保证"broker 确认了"，单副本仍然防不了磁盘损坏 —— 这不是本项目的目标。
     */
    @Bean
    public NewTopic courseSelectTopic() {
        return TopicBuilder.name(Const.COURSE_SELECT_TOPIC)
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1")
                // 3 天：够压测后拿 topic 里的消息和 DB / Redis 结果键对账
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(3L * 24 * 60 * 60 * 1000))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG,
                        TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    /**
     * 死信主题。
     * <p>
     * 分区数必须和源主题一样（3）：{@code DeadLetterPublishingRecoverer} 默认把
     * 死信投到源记录所在的同一个分区号；分区不存在时它不会报错，而是让 producer
     * 自己选分区 —— 那样死信和原消息的分区对应关系就丢了。
     * <p>
     * 名为 {@code course.select.DLT}（不是框架默认的 {@code -dlt}），所以
     * {@link CourseSelectFailureRecoverer} 里显式指定了目标 TopicPartition。
     */
    @Bean
    public NewTopic courseSelectDltTopic() {
        return TopicBuilder.name(Const.COURSE_SELECT_DLT)
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1")
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(7L * 24 * 60 * 60 * 1000))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG,
                        TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    /**
     * 抢课专用容器工厂：手动 ack + 3 个消费线程 + 有限重试。
     * <p>
     * 不直接用 Boot 自动配置的那个 {@code kafkaListenerContainerFactory}，原因：
     * <ol>
     *   <li>抢课需要 {@code AckMode.MANUAL_IMMEDIATE}，而全局改 ack 模式会影响
     *       以后可能出现的其它 Kafka 监听器；</li>
     *   <li>并发数要和分区数对齐。{@code concurrency > 分区数} 的线程分不到分区，
     *       只是空转；{@code concurrency < 分区数} 又浪费分区。</li>
     * </ol>
     * <p>
     * 重试节奏：首次 + 3 次重试，每次间隔 1 秒。4 次都失败就交给
     * {@link CourseSelectFailureRecoverer}：写结果 {@code "0"}、记 ERROR、投 DLT。
     * 业务失败（重复 / 名额满）不会抛异常，所以不会触发重试。
     */
    @Bean("courseKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, CourseSelectTask> courseKafkaListenerContainerFactory(
            KafkaProperties kafkaProperties,
            CourseSelectFailureRecoverer recoverer) {

        ConcurrentKafkaListenerContainerFactory<String, CourseSelectTask> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(courseConsumerFactory(kafkaProperties));
        // 3 = topic 分区数。改分区数时这里要一起改，否则多出来的线程空转。
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // 主题还没建好时不要让应用启动失败（Kafka 可能比后端晚起来）。
        factory.setMissingTopicsFatal(false);

        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        // 每次重试的异常按 WARN 打；真正的"耗尽"由 recoverer 记 ERROR，避免日志重复两遍。
        errorHandler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }

    /**
     * 抢课消费者的反序列化。
     * <p>
     * 用 {@link JsonDeserializer} 并<b>关掉 type header</b>：生产者发送时
     * {@code JsonSerializer} 会写一个 {@code __TypeId__} 头，里面是全限定类名。
     * 消费端已经明确知道类型就是 {@link CourseSelectTask}，没必要再让消息头决定
     * 反序列化成什么类。关掉之后也不必配置 {@code spring.json.trusted.packages}。
     * <p>
     * ⚠️ 这里用 setter 配置了 JsonDeserializer，所以 <b>不能</b>再在
     * {@code spring.kafka.consumer.properties} 里放 {@code spring.json.*} 配置项：
     * spring-kafka 会抛
     * {@code JsonDeserializer must be configured with property setters, or via
     * configuration properties; not both}。两者只能选一种。
     */
    private ConsumerFactory<String, CourseSelectTask> courseConsumerFactory(
            KafkaProperties kafkaProperties) {

        Map<String, Object> props = kafkaProperties.buildConsumerProperties();
        props.put(ConsumerConfig.GROUP_ID_CONFIG, Const.COURSE_SELECT_GROUP);
        // 手动 ack 的前提：关掉自动提交，否则消息还在重试时 offset 已经被提交了。
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        JsonDeserializer<CourseSelectTask> valueDeserializer =
                new JsonDeserializer<>(CourseSelectTask.class);
        valueDeserializer.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(), valueDeserializer);
    }
}
