# Kafka 课程队列迁移：设计（仅设计，未实现）

> 分支：`feature/kafka-course`
> 目标：**学习 Kafka 的可靠消费语义**，不是性能优化。
> 结论先说：这个量级 MQ 不是瓶颈；不要用 Rabbit vs Kafka 的吞吐数字做结论。

## 1. 范围

只迁抢课链路：

```text
course.select 生产 → course.select 消费 → DB 落库
```

**不迁**：

- mail 队列（继续 RabbitMQ）；
- 论坛/点赞/天气；
- 前端、Redis 预热、结果查询逻辑。

## 2. 拓扑

单节点 KRaft，不引入 ZooKeeper：

```text
topic: course.select
partitions: 3
replication-factor: 1
min.insync.replicas: 1
key: courseId
consumer group: course-select
DLT: course.select.DLT
```

- 同一门课的消息按 `courseId` 进同一分区，天然串行；
- 不同课程可以并行；
- 单节点下 `acks=all` + RF=1 只能防“生产者没收到确认”，防不了 broker 磁盘坏。

## 3. 生产端

- `acks=all`、`enable.idempotence=true`、有限 `retries`；
- 消息体保持 `{uid, courseId}`；
- 发送成功 ≠ 落库成功，接口仍返回 `QUEUED`；
- 发送失败：回补 `SREM chosen`，返回 FAILURE（和现在 Rabbit 版一致）。

## 4. 消费端

- 手动 ack，处理完 DB/结果键再 ack；
- 至少一次投递 → DB 复合主键 `(uid, course_id)` 做幂等；
- `OK/DUPLICATE` → 结果 `"1"`；`SEAT_FULL` → `SREM chosen` + 结果 `"0"`；
- 消费异常：重试 3 次（退避），耗尽后：
  - 写结果 `"0"`；
  - 记 ERROR（uid/courseId/cause）；
  - 投 DLT（或按 A 方案直接 ack 丢弃，二选一，先按 DLT 设计）；
- **不自动重放 DLT**：没有 owner 的 DLT 就是停尸房，和 Rabbit 的 DLQ 讨论同理。

## 5. 与现有代码的差异

| 项 | Rabbit 版 | Kafka 版 |
|---|---|---|
| 生产者 | `RabbitTemplate.convertAndSend` | `KafkaTemplate.send(topic, key, payload)` |
| 消费者 | `@RabbitListener` + 容器工厂 | `@KafkaListener` + `ConcurrentKafkaListenerContainerFactory` |
| 重试 | Spring Retry advice | `DefaultErrorHandler` + `FixedBackOff` |
| 死信 | `course.select.dlq` | `course.select.DLT` |
| ack | 容器自动 ack | 手动 `Acknowledgment.acknowledge()` |
| 结果键 | 不变 | 不变 |

## 6. 部署/资源

- 服务器 2 vCPU / 3.5 GiB；当前 MySQL/Redis/Rabbit/Prometheus/Grafana/MinIO 已占约 1.4 GiB；
- Kafka（KRaft + JVM）预计再加 500–700 MiB，可能和 k6/build 抢内存；
- 迁移期间 Rabbit 不能停（mail 还在用），所以是**双 MQ 并存**；
- 如果内存不够，先只在本机或临时机器验证，不要直接上服务器。

## 7. 验收

1. 100 个名额 / 500 个不同用户：不超卖、不重复；
2. 重复消息/重复投递：DB 仍只有一行；
3. DB 挂：重试耗尽 → 结果 `"0"` → DLT 0/1 条符合预期；
4. 队列 lag 能归零；
5. 不跑 Rabbit vs Kafka 性能对比；如果要跑，只写“这个量级测不出差异”。

## 8. 不做

- 不做多 broker、RF=3、跨机房；
- 不做 Kafka Streams；
- 不做事务消息（`@Transactional` + Kafka transaction）；
- 不替换 mail；
- 不为了“简历上有 Kafka”而合并到 dev/main。
