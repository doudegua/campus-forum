package net.doudegua.entity.dto;

/**
 * 抢课消息体：{@code {uid, courseId}}。
 * <p>
 * 两个字段就是 {@code db_course_selection} 复合主键的全部内容，所以幂等键在
 * 数据库里，消息里不需要再塞 requestId —— 同一对 {@code (uid, courseId)}
 * 天然就是同一件事，重复投递由 {@code INSERT IGNORE} 挡掉。
 * <p>
 * 用 {@code record} 而不是 {@code Map.of(...)}：Kafka 的 JSON 反序列化需要
 * 一个确定的类型。原来 Rabbit 版靠全局的 {@code Jackson2JsonMessageConverter}
 * + {@code Map<String, Object>} 就能工作；Kafka 侧如果还传 Map，类型头里会是
 * {@code java.util.ImmutableCollections$MapN}，消费端要么把整个 java.util
 * 加进 trusted packages，要么自己解析头，两条路都不干净。
 * <p>
 * Jackson 从 2.12 起原生支持 record，项目里的 Jackson 版本远高于此。
 */
public record CourseSelectTask(int uid, int courseId) {
}
