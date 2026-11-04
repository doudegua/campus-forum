# Kafka 课程队列迁移：实现记录

> 分支：`feature/kafka-course`
> 对应设计：`docs/kafka-course-migration.md`
> 范围：只迁抢课链路（`course.select`），mail 继续走 RabbitMQ。

## 1. 结论先说

- 抢课的生产、消费、重试、死信已经切到 Kafka；RabbitMQ 只保留 mail 链路。
- 选择 **DLT 方案**，没有选设计文档里的 A 方案（重试耗尽后直接 ack 丢弃）。
  理由：失败消息留在 `course.select.DLT` 里可以事后核对 payload；A 方案只剩一行日志，
  出问题无法复盘。DLT 不自动重放。
- 这是学习/实验分支，不构成任何性能结论，也没做 Rabbit vs Kafka 对比。

## 2. 改了什么

### 2.1 依赖

`my-project-backend/pom.xml`

- 新增 `org.springframework.kafka:spring-kafka`，版本由
  `spring-boot-dependencies:3.5.11` 管理（不手写版本，避免和它管的
  `kafka-clients` 打架）。
- **保留** `spring-boot-starter-amqp`：mail 还在用 RabbitMQ。

### 2.2 主题与常量

`my-project-backend/src/main/java/net/doudegua/utils/Const.java`

```text
COURSE_SELECT_TOPIC  = "course.select"
COURSE_SELECT_DLT    = "course.select.DLT"
COURSE_SELECT_GROUP  = "course-select"
```

旧的 `COURSE_SELECT_QUEUE` / `COURSE_SELECT_DLQ` 常量保留，只给
`CourseRabbitConfiguration` 的队列声明用（回滚时不用改常量）；当前没有消费者绑定它们。

### 2.3 消息体

新增 `my-project-backend/src/main/java/net/doudegua/entity/dto/CourseSelectTask.java`：

```java
public record CourseSelectTask(int uid, int courseId) {}
```

不再用 `Map.of("uid", ..., "courseId", ...)`。Kafka 的 JSON 反序列化需要一个明确类型；
用 Map 会让类型头变成 `java.util.ImmutableCollections$MapN`，消费端还得处理
`trusted.packages` 或自己解析类型头。

### 2.4 主题与消费者配置

新增 `my-project-backend/src/main/java/net/doudegua/config/KafkaCourseConfiguration.java`

- `NewTopic courseSelectTopic()`：3 分区、RF=1、`min.insync.replicas=1`、retention 3 天。
- `NewTopic courseSelectDltTopic()`：3 分区、RF=1、retention 7 天。
  分区数和源主题一致，因为 `DeadLetterPublishingRecoverer` 默认把死信投到
  源记录所在的同一个分区号。
- `courseKafkaListenerContainerFactory`：
  - `AckMode.MANUAL_IMMEDIATE`；
  - `concurrency=3`（与分区数一致；多了空转，少了浪费分区）；
  - `DefaultErrorHandler` + `FixedBackOff(1000ms, 3)`：首次 + 3 次重试，间隔 1 秒；
  - 重试耗尽交给 `CourseSelectFailureRecoverer`；
  - `missing-topics-fatal=false`，Kafka 晚于后端启动时不至于让应用起不来。
- 消费者反序列化：`JsonDeserializer<CourseSelectTask>`，显式 `setUseTypeHeaders(false)`，
  消费端始终按 `CourseSelectTask` 解析，不依赖消息头里的全限定类名。

### 2.5 生产端

`my-project-backend/src/main/java/net/doudegua/service/impl/CourseServiceImpl.java`

- 注入 `KafkaTemplate<String, Object>`（`@Resource` 按名字注入）。
- Lua 预扣成功之后：

```java
kafkaTemplate.send(
        Const.COURSE_SELECT_TOPIC,
        String.valueOf(courseId),          // key = courseId
        new CourseSelectTask(uid, courseId))
    .get(6, TimeUnit.SECONDS);
```

- **必须等 future**：Kafka 的发送失败有一类只体现在 future 上（delivery timeout、
  broker 拒绝），只 try-catch 同步异常会漏掉。
- 发送失败（含 future 超时）：记 ERROR、回补 `SREM course:chosen:{courseId}`、
  返回 `FAILURE`，和原 Rabbit 版一致。
- `SEND_TIMEOUT_SECONDS = 6` 大于 producer 的 `delivery.timeout.ms=5000`。
  如果 future 先超时、消息稍后仍投递成功，就会出现"接口说失败、DB 却多一条"的账。

### 2.6 消费端

`my-project-backend/src/main/java/net/doudegua/listener/CourseSelectListener.java`

- `@RabbitListener` → `@KafkaListener(topics = "course.select", groupId = "course-select",
  containerFactory = "courseKafkaListenerContainerFactory")`。
- 复用 `CourseEnrollmentService.enroll(uid, courseId)`，业务语义不变：
  - `OK` / `DUPLICATE` → 结果 `"1"`；
  - `SEAT_FULL` → `SREM chosen` + 结果 `"0"`。
- 手动 ack：DB 事务和结果键都写完之后才 `ack.acknowledge()`。
- 业务失败（重复、名额满）**不抛异常**，因为抛了只会让同一件事重试 4 次再进 DLT，
  制造假告警；基础设施异常（DB/Redis）向上抛，交给 `DefaultErrorHandler`。

### 2.7 重试耗尽兜底

新增 `my-project-backend/src/main/java/net/doudegua/listener/CourseSelectFailureRecoverer.java`

实现 `ConsumerRecordRecoverer`，按顺序做三件事：

1. 写结果键 `course:result:{courseId}:{uid} = "0"`（TTL 48 小时）；
2. 记 ERROR 日志（uid、courseId、topic/partition/offset、cause）；
3. 投 `course.select.DLT`。

目标主题在构造 `DeadLetterPublishingRecoverer` 时显式指定：

```java
new DeadLetterPublishingRecoverer(
        kafkaTemplate,
        (record, ex) -> new TopicPartition(Const.COURSE_SELECT_DLT, record.partition()));
```

因为框架默认目标名是 `<源主题>-dlt`（小写短横线），和本项目定的
`course.select.DLT` 不一致；不显式指定的话会往一个不存在的主题发。

### 2.8 Rabbit 侧

- `EmailRabbitConfiguration`、`MailQueueListener`、`AccountServiceImpl` **未改**，
  mail 继续走 RabbitMQ。
- `CourseRabbitConfiguration` 的队列声明和容器工厂保留（回滚用），但
  `CourseSelectListener` 上已经没有 `@RabbitListener`，所以**没有任何消费者绑定
  `course.select` 的 Rabbit 队列**，不存在 Rabbit/Kafka 双消费。
- `CourseFailureRecoverer` 保留，但当前运行期只有 `CourseRabbitConfiguration`
  引用它；它不会参与 Kafka 消费路径。

### 2.9 配置

`my-project-backend/src/main/resources/application.yaml` 新增 `spring.kafka`：

- `bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`
- producer：`StringSerializer` / `JsonSerializer`、`acks=all`、`enable.idempotence=true`、
  `retries=3`、`max.block.ms=3000`、`request.timeout.ms=2000`、
  `delivery.timeout.ms=5000`
- consumer：`group-id=course-select`、`auto-offset-reset=earliest`、
  `enable-auto-commit=false`、`JsonDeserializer`
- listener：`missing-topics-fatal=false`
- admin：`fail-fast=false`

> ⚠️ **不要**在 `spring.kafka.consumer.properties` 里再放 `spring.json.*` 配置项
> （例如 `spring.json.trusted.packages`）。抢课的消费者工厂是用 setter
> （`new JsonDeserializer<>(CourseSelectTask.class)` + `setUseTypeHeaders(false)`）
> 配置反序列化器的；spring-kafka 规定 setter 和配置属性只能二选一，同时存在会在
> 消费者启动时抛
> `JsonDeserializer must be configured with property setters, or via configuration
> properties; not both`。消费端已经固定用 `CourseSelectTask`，也不需要 trusted-packages。

> ⚠️ `delivery.timeout.ms` 不能单独配小：Kafka 要求
> `delivery.timeout.ms >= linger.ms + request.timeout.ms`，而 `request.timeout.ms`
> 默认 30 秒。只写 `delivery.timeout.ms=5000` 会在 Producer 启动时直接抛
> `ConfigException`。所以这里同时把 `request.timeout.ms` 压到 2000。

`.env.example` 增加了 `KAFKA_BOOTSTRAP_SERVERS` 的说明（默认值不填也能本地跑）。

### 2.10 独立 Kafka compose

新增根目录 `docker-compose.kafka.yml`：

- `apache/kafka:3.9.1`，单节点 KRaft（无 ZooKeeper）；
- 宿主机 `9092`，后端默认 `localhost:9092` 直接可连；
- `KAFKA_HEAP_OPTS=-Xmx512m -Xms512m`；
- 单节点内部主题副本数全部设为 1；
- 关闭 broker 自动建主题，主题由 `NewTopic` Bean 在应用启动时声明；
- 数据放命名卷 `kafka-data`。

**主 `docker-compose.yml` 未改**。主 compose 的 backend 目前不会自动连这个 Kafka；
要在 compose 网络里用，需要自行把 `KAFKA_BOOTSTRAP_SERVERS` 设成 `kafka:9092`
并加入依赖/网络（本分支没做，避免动主部署配置）。

## 3. 怎么跑

### 3.1 起 Kafka

```bash
docker compose -f docker-compose.kafka.yml up -d
docker compose -f docker-compose.kafka.yml ps
docker compose -f docker-compose.kafka.yml logs -f kafka
```

等 healthcheck 变成 healthy 后，确认主题（应用启动后才会由 `NewTopic` 创建）：

```bash
docker exec campus-forum-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list
```

预期至少能看到：

```text
course.select
course.select.DLT
```

查看分区：

```bash
docker exec campus-forum-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic course.select
```

预期 `PartitionCount: 3`、`ReplicationFactor: 1`。

### 3.2 起后端

后端仍需要 MySQL / Redis / RabbitMQ（mail）/ MinIO。本机直接跑：

```bash
cd my-project-backend
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 mvn spring-boot:run
```

不设 `KAFKA_BOOTSTRAP_SERVERS` 时默认也是 `localhost:9092`。

### 3.3 看消费状态

lag（积压）要看 consumer group，不要看 topic 里有多少条：

```bash
docker exec campus-forum-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group course-select
```

重试耗尽后看 DLT（不自动重放）：

```bash
docker exec campus-forum-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic course.select.DLT \
  --from-beginning --max-messages 10 \
  --property print.key=true --property print.headers=true
```

### 3.4 停 Kafka

```bash
docker compose -f docker-compose.kafka.yml down      # 保留 kafka-data 卷
docker compose -f docker-compose.kafka.yml down -v   # 连数据一起删
```

## 4. 验证了什么

- `cd my-project-backend && mvn -q -DskipTests compile`：通过（exit 0）。
- `docker compose -f docker-compose.kafka.yml config`：通过（只做配置解析，不启动容器）。
- 代码层面确认：
  - `CourseSelectListener` 上已经没有 `@RabbitListener`，课程链路不会双消费；
  - `EmailRabbitConfiguration`、`MailQueueListener`、`AccountServiceImpl` 无改动；
  - 生产端 `send(...).get(...)` 覆盖同步异常和 future 失败，失败会回补 chosen；
  - 消费端手动 ack 在 DB + 结果键之后；
  - 重试耗尽 recoverer 写 `"0"`、记 ERROR、投 `course.select.DLT`。

## 5. 没验证什么

以下都**没有**实际跑，不能当作已验证行为：

- 没有启动 Kafka broker，没有做真实的生产/消费端到端测试；
- 没有连真实 MySQL/Redis，没有发真实抢课请求，没有写任何业务数据；
- 没有验证 DLT 实际收到消息、没有做故障注入（DB 挂、Redis 挂、broker 挂）；
- 没有验证 consumer lag 归零，没有压测，没有性能对比；
- 没有在 Docker 主 compose 网络里验证后端连 Kafka（主 compose 未改）；
- 没有跑 `mvn test` / 集成测试。

## 6. 已知限制与坑

1. **单节点、RF=1**：`acks=all` 只能保证 broker 收到确认，防不了单节点磁盘损坏。
   设计目标本来就是学习，不追求高可用。
2. **`auto-offset-reset=earliest` 会重放**：消费组没有 offset 时（首次部署、换组、
   offset 过期）会重放 topic 里 3 天内的消息。抢课消费是幂等的（DB 复合主键 +
   `INSERT IGNORE`），重复写结果键也无害；但如果有人把 group 改掉，仍应预期到
   一大段重复消费。
3. **DLT 不自动重放**：失败消息留在 `course.select.DLT`，需要人工确认原因后处理。
   这是刻意的，避免"定时任务无脑重投坏消息"。
4. **DLT 名字不是框架默认值**：`course.select.DLT` 需要自定义
   destinationResolver；如果以后改名，`Const.COURSE_SELECT_DLT` 和
   `CourseSelectFailureRecoverer` 里的 resolver 必须一起改。
5. **重试计数在内存里**：`FixedBackOff` 的计数不持久化。应用在重试过程中重启时，
   同一条消息会重新从 1 开始计数。跨重启可能超过 4 次尝试，但最终仍会进 DLT
   或被正常处理。
6. **DB 异常耗尽不释放 chosen**：只有 `SEAT_FULL` 会 `SREM`。DB 连接失败重试耗尽后
   只写结果 `"0"` + DLT，不释放 chosen —— 这是为了和设计文档一致，也避免 DLT 重放
   时把名额卖掉两次。代价是故障期间可能泄漏名额，需要人工对账（DLT 的 payload
   里有 uid/courseId）。**这是已知的、有意保留的限制。**
7. **Redis 写结果失败**：recoverer 会记 ERROR，但为了保证 DLT 能收到消息，不会因为
   结果键写失败而阻止投递。此时 DLT 有消息，结果键可能缺失，需要人工补。
8. **生产端接口会等发送结果**：最坏情况下 `max.block.ms` 3 秒 + `delivery.timeout.ms`
   5 秒，接口可能比纯异步慢数秒；这是为了对齐原 Rabbit 版"发送失败就返回 FAILURE"的语义。
9. **`KafkaAdmin` 只在启动时建主题**：如果应用启动时 Kafka 不可达，主题不会在 Kafka
   恢复后自动补建，需要重启应用或手工建。`missing-topics-fatal=false` 保证应用能起来，
   但不保证主题已存在。
10. **`concurrency=3` 必须和分区数一致**：改 `course.select` 分区数时要同步改
    `KafkaCourseConfiguration`，否则多出来的线程空转。
11. **保留的 Rabbit 课程声明**：`CourseRabbitConfiguration` 仍会声明
    `course.select` / `course.select.dlq` 队列，但没有消费者。`course.select`
    同时是 Kafka topic 名，只是两个独立系统里同名，不冲突；回滚时把
    `@KafkaListener` 换回 `@RabbitListener` 即可。
12. **没有监控**：没有接 Kafka lag/exporter；目前只能靠
    `kafka-consumer-groups.sh --describe` 查。

## 7. 回滚

本分支不 merge、不部署；要回滚到 Rabbit 版本，至少需要：

1. `CourseServiceImpl` 改回 `RabbitTemplate.convertAndSend(...)`；
2. `CourseSelectListener` 改回 `@RabbitListener(queues = Const.COURSE_SELECT_QUEUE,
   containerFactory = "courseListenerContainerFactory")`；
3. `pom.xml` 去掉 `spring-kafka`（`spring-boot-starter-amqp` 本来就保留着）；
4. `application.yaml` 删除 `spring.kafka` 段；
5. 停掉 `docker-compose.kafka.yml`，不影响主 compose / Rabbit mail。

`CourseRabbitConfiguration` 和 `CourseFailureRecoverer` 保留着就是为了减少回滚改动。
