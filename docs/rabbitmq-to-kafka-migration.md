# RabbitMQ → Kafka 迁移文档

> 状态：**迁移方案已定，代码未动**。按 §7 的 Phase 0–4 顺序执行，每个 Phase 有自己的验收命令。
> 范围：本项目里 RabbitMQ 承担的两条链路（验证码邮件、抢课落库）+ `docker-compose.yml` + 相关文档与环境变量。
> 不在范围：Kafka Streams、Schema Registry、Kafka Connect、多节点集群、Exactly-Once、事务消息。
> 口径：版本号来自本机 `~/.m2` 里 `spring-boot-dependencies:3.5.11`（`spring-kafka 3.3.13`、`kafka-clients 3.9.1`）。行为类断言以 spring-kafka 3.3 参考文档与源码、apache/kafka 3.9 官方文档为准 —— 踩过版本差异的地方（比如 DLT 后缀）在文中单独标了出来。标了"未测"的就真是没测。

---

## 0. 一页纸

现状三句话：

1. 全项目只有 **两条** MQ 链路：`mail`（验证码邮件，已在用）和 `course.select` / `course.select.dlq`（抢课，**消费者是骨架、生产者还没写**）。
2. **没有用 exchange**：`convertAndSend("mail", data)` 的 routing key 就是队列名，走默认交换机；死信用 `x-dead-letter-exchange=""` + 队列名当 routing key。少一层路由，迁移面因此很小。
3. `mail` 队列**没有死信**，消费端靠"catch 住不抛"来避免无限重投 —— 这是被实测逼出来的妥协（5 秒重投 16 次）。

结论：

| 判断 | 内容 |
|---|---|
| **技术上可行性** | 高。7 个 Java 文件（含 2 个 Rabbit 配置类）+ pom + 1 个 compose 服务 + 1 段 yaml，无分布式事务、无消息 TTL、无优先级、无延迟队列，Kafka 的"能力缺口"在这个项目里全都用不上 |
| **抢课链路** | **推荐随实现一起换成 Kafka**。消费者还是骨架、生产者还没写，此刻迁移的边际成本最低；而且按 `courseId` 分区后，"同一门课串行、不同课程并行"是抢课天然需要的语义 |
| **邮件链路** | **收益最小、风险最大的一条**。Kafka 不是为"工作队列"设计的：消息读完不删、消费者组换新游标会把历史验证码重放一遍。真要做，必须显式设 `auto-offset-reset: latest` + 短 retention（§4.2、§8 坑 2 有具体值） |
| **如果目的是"让项目更好用"** | 别迁。两条低频链路用 RabbitMQ 更省事：内存约 100MB vs Kafka 单节点 512MB 起步，管理界面开箱即用（15672），语义直觉（消费即删） |
| **如果目的是"练 Kafka"** | 迁，但按 §7 分阶段。收益是真实的：积压看得见（lag）、坏消息不再丢（DLT 可重放）、`courseId` 分区让抢课的并发模型能讲清楚 |
| **工作量** | 一个人、本地：**Phase 1+2 约 3 小时**（只迁 mail），Phase 3 和抢课实现一起做，Phase 4 清理 0.5 小时 |
| **风险 top3** | ① 发送语义变了 —— 有一半失败只体现在 future 上，抢课"发送失败回补库存"照抄旧代码会漏（坑 1）；② 邮件重放：`earliest` 或改 groupId 就会重发历史验证码（坑 2）；③ 单节点 KRaft 的 compose 变量写错，broker 起不来且日志不直白（§6.2 已给完整片段） |

一句话：**这不是"RabbitMQ 不行了"，是"抢课要用分区 + 需要能回放的消息日志"。邮件顺带迁过去，但它不是迁的理由。**

---

## 1. 现状盘点

### 1.1 代码清单

| 文件 | 行数 | 现在做什么 | 迁移后 |
|---|---|---|---|
| `pom.xml:116` | 1 个依赖 | `spring-boot-starter-amqp` | 换成 `org.springframework.kafka:spring-kafka`（版本由 Boot 管，不写 `<version>`） |
| `config/EmailRabbitConfiguration.java` | 32 | 声明 durable 队列 `mail`；全局 `Jackson2JsonMessageConverter`；自定义 `RabbitTemplate` | **删除**。Kafka 的序列化器在 yaml 里配，不需要模板 Bean |
| `config/CourseRabbitConfiguration.java` | 52 | 声明 `course.select`（带死信参数）+ `course.select.dlq` | **删除**，换成一个声明 `NewTopic` 的配置类 |
| `listener/MailQueueListener.java` | 129 | `@RabbitListener(queues="mail")`，`Map<String,Object>` 入参，SMTP 失败时 `log.error` 后**不 rethrow** | `@KafkaListener(topics=..., groupId=...)`，入参换成 DTO；失败**抛出去**，交给 `DefaultErrorHandler` 有限重试 + DLT |
| `listener/CourseSelectListener.java` | 61 | 骨架：只打日志，会 ack 并丢弃 | 骨架保留，注解换成 `@KafkaListener`，concurrency 由分区数决定 |
| `service/impl/AccountServiceImpl.java:71` | 1 行 | `amqpTemplate.convertAndSend("mail", data)` | `kafkaTemplate.send(...)`，**多一步**：等 future，否则接口 200 但邮件可能永远不来 |
| `service/impl/CourseServiceImpl.java:79,120` | 骨架 | 注入了 `RabbitTemplate` 但没用（TODO 里写着要发 MQ） | 注入 `KafkaTemplate`，TODO 注释里的补偿逻辑要按异步语义重写 |
| `utils/Const.java:72` | 2 个常量 | `COURSE_SELECT_QUEUE = "course.select"`、`COURSE_SELECT_DLQ = "course.select.dlq"` | 改成 `..._TOPIC`，DLT 名字用固定后缀拼出来（坑 5） |
| `src/main/resources/application.yaml:82-85` | 4 行 | `spring.rabbitmq.addresses/username/password` | 换成 `spring.kafka.*`（§6.1） |
| `docker-compose.yml:281-296,324,341-343,459` | 服务+卷+依赖+环境变量 | `rabbitmq:3.13-management-alpine`，`rabbitmq-data` 卷，backend `depends_on: service_healthy` | 换成单节点 KRaft 的 `apache/kafka`（§6.2），卷名 `kafka-data` |
| `.env.example:55-61` | 4 行 | `RABBITMQ_USERNAME/PASSWORD` | 换成 `KAFKA_BOOTSTRAP_SERVERS` 等（§6.4） |
| `AGENT.md:30` | 1 行 | 运行前提表里写 "RabbitMQ 5672" | 改成 Kafka 9092/29092 + 一句"看积压用 `kafka-consumer-groups.sh`" |

### 1.2 运行时拓扑（现在）

```text
浏览器 ──> nginx ──> backend:8080 ──┬──> MySQL
                                    ├──> Redis（JWT 黑名单 / 抢课库存 / 结果键）
                                    ├──> MinIO
                                    └──> RabbitMQ:5672
                                           ├─ mail              ← AccountServiceImpl 生产，MailQueueListener 消费
                                           ├─ course.select     ← 生产端未写（骨架）
                                           └─ course.select.dlq ← 无消费者
```

### 1.3 五条必须先接受的事实

1. **`mail` 队列没有死信**。`EmailRabbitConfiguration` 里只有 `QueueBuilder.durable("mail")`，没有任何参数。所以发信失败只体现在日志里，事后无法重发 —— 用户得自己再点一次"获取验证码"。
2. **`CourseSelectListener` 现在会静默丢消息**。它 `log.warn` 之后正常返回，Spring AMQP 就 ack 了。类注释里已经写明"接生产端之前必须先实现"。
3. **抢课的生产端也不存在**。`CourseServiceImpl.select()` 直接返回 `NOT_IMPLEMENTED`，Controller 翻成 501。前端没有轮询代码。**所以抢课这条链路上的"迁移"其实是"从一开始就用 Kafka 写"，不涉及任何在途数据。**
4. **`spring.rabbitmq` 只配了地址和账号**，没配 listener 并发/prefetch —— 也就是全用默认值（单消费者、prefetch 250）。这两个数在 Kafka 里换成"分区数"和"`max.poll.records`"，见 §4.3。
5. **没有任何监控采集 RabbitMQ**。`observability/prometheus/prometheus.yml` 只抓 backend 的 `/actuator/prometheus`。迁到 Kafka 后如果还想看积压，要么加 `kafka-exporter`，要么用命令行查 lag（§10）。

---

## 2. 该不该换

这一节是给"半年后回来看这份文档的自己"写的。如果只想照着做，跳到 §3。

### 2.1 Kafka 在这里真正的收益

| 收益 | 对哪个功能 | 是工程价值还是学习价值 |
|---|---|---|
| **消息读完不删，可回放** | mail | 工程价值。现在发信失败只能看日志，迁过去坏消息进 `<topic>-dlt`，3 分钟窗口内可以捞出来手工重发 |
| **分区 + key 决定顺序** | course | 工程价值。`key=courseId` → 同一门课的落库严格串行，不同课程并行。RabbitMQ 单队列也能做到"同一门课串行"，但做不到"按课程并行且不互相阻塞"而不引入多个队列 |
| **积压是一个数字（lag）** | course | 工程价值。抢课最怕的是"消息堆着但没人知道"，lag 能直接报警 |
| **有限重试 + 死信是一等公民** | mail / course | 工程价值。现在为了躲开"无限重投"，代码里写的是"catch 住不抛"（等于丢消息）。Kafka 里 `FixedBackOff(1s, 3)` 一行配置就把上限定死，不需要再用吞异常来换安静 |
| **至少一次 + 幂等键** | course | 中性。RabbitMQ 也是至少一次，没有变好也没有变坏 |
| **换一套技术栈的练习** | — | 学习价值。这是本项目最诚实的收益 |

### 2.2 代价（也是真实存在的那部分）

| 代价 | 具体表现 |
|---|---|
| **内存** | RabbitMQ 容器空载约 100MB 上下；Kafka 单节点 KRaft 的 JVM 至少要 512MB 堆（`KAFKA_HEAP_OPTS` 压到 512m 只是下限），宿主机整体多 600MB 左右（**未测，按经验值**，跑起来用 `docker stats` 记一笔） |
| **启动慢** | RabbitMQ `start_period: 40s`；Kafka 首次格式化存储 + 建内部主题，冷启动更慢。compose 里 backend 的 `depends_on: service_healthy` 会因此等更久 |
| **语义变复杂** | 队列是"一条消息被一个消费者拿走就没了"；topic 是"每个消费者组各有一份游标，消息按 retention 过期"。这个差别会直接咬到邮件（§8 坑 2） |
| **丢掉的能力** | 单条消息 TTL、优先级队列、延迟消息（RabbitMQ 用 TTL+DLX 能做）、per-message 选择性 ack。**本项目一个都没用**，所以这条现在不构成障碍，但要写下来备查 |
| **运维命令全换** | `rabbitmqctl list_queues` → `kafka-consumer-groups.sh --describe`。管理界面从开箱的 15672 变成要额外起一个 kafka-ui |
| **配置面积变大** | 序列化器、`acks`、`max.block.ms`、`delivery.timeout.ms`、`auto-offset-reset`、`ack-mode`、错误处理器 —— 每一项错了都有具体的、难查的症状。§8 就是为此写的 |

### 2.3 分功能结论

**mail：可以迁，但它不是迁的理由。**
- 迁的理由只有一条：失败邮件不再丢（DLT）。
- 不迁的理由更强：验证码是"3 分钟内有效、旧码无意义"的数据，Kafka 的"保留历史"对它没有价值，反而是风险（重放）。
- 所以：**如果 Phase 2 只做一半就想停，那就停在这里 —— 把 RabbitMQ 留着，只把抢课写成 Kafka 版**。双栈在这个项目里不是耻辱，`pom.xml` 里多一个 starter 而已。

**course：推荐迁。**
- 它还没实现，不存在"改坏"的问题。
- 它的并发模型（按课程分区）比 RabbitMQ 的"一个队列一个消费者"更贴设计文档里的不变量。
- `docs/course-grab-design.md` 里的口径（"权威是选课关系表，MQ 只是限速"）在 Kafka 下不需要改一个字 —— 说明这个设计本来就不依赖 RabbitMQ 的特性。
  （顺带记一个和本次迁移无关的不一致：那份文档写 `db_course_enroll`，而 `db/schema.sql` 里现在叫 `db_course_selection`。改代码时以 `schema.sql` 为准。）

**结论一句话：先让 mail 在 Kafka 上跑通（Phase 2），再把 course 写进 Kafka（Phase 3）；如果 Phase 2 就发现代价不能接受，停在那里，回滚成本是 `git checkout` 两个文件。**

---

## 3. 概念映射

### 3.1 对照表

| RabbitMQ（本项目实际用法） | Kafka | 差别要注意的地方 |
|---|---|---|
| `Queue("mail")` | `NewTopic("mail")` + consumer group | 队列"消费即删"；topic 按 retention 保留。同名 topic 被两个不同 group 消费 = 两套独立游标，两边都会收到全部消息 |
| `convertAndSend("mail", data)`（默认交换机 + routing key=队列名） | `kafkaTemplate.send("mail", key, value)` | RabbitMQ 的 routing key 决定"投给哪个队列"；Kafka 的 key 决定"落到哪个分区"。本项目 key 用邮箱/courseId |
| `Exchange` / `Binding` | —（无对应物） | **本项目从没用过 exchange**，迁移反而少一层 |
| `durable queue` | `retention.ms` + `cleanup.policy=delete` | 一个保证"重启不丢"，一个保证"留多久"。Kafka 里没有"队列"这个可清空的对象 |
| `basic.ack` / `nack` | offset commit（`ack-mode`） | 粒度从"单条消息"变成"一个位置"。提交了 offset 3 就代表 0–3 都算处理完 |
| `x-dead-letter-exchange` + routing key | `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` | 目标主题名**默认**是 `<原主题>-dlt`，不用自己起名（坑 5） |
| `default-requeue-rejected=true`（默认，踩过） | `FixedBackOff` 在**消费端内存里**重试 | 不再是 broker 重投。重试次数有上限，不会 5 秒 16 次 |
| `prefetch` | `max.poll.records` + `fetch.min.bytes` | prefetch 是"推"的窗口，poll 是"拉"的批量 |
| `spring.rabbitmq.listener.simple.concurrency` | `spring.kafka.listener.concurrency` | **有硬上限**：一个组里最多 topic 分区数个线程有效（坑 3） |
| `rabbitmqctl list_queues` / 15672 | `kafka-consumer-groups.sh --describe` / kafka-ui | 看的是 lag（积压条数），不是队列长度 |
| 消息体 `Map.of(...)` + `Jackson2JsonMessageConverter` | DTO + `JsonSerializer` / `JsonDeserializer` + `__TypeId__` 头 | 类型头里带的是**全限定类名**，消费端必须在 `spring.json.trusted.packages` 白名单里（坑 6） |

### 3.2 三个"Kafka 里没有对应物"的东西

1. **单条消息 TTL**。RabbitMQ 可以给队列或消息设 `x-message-ttl`，过期自动丢。Kafka 只有 topic 级 retention。本项目**没用**这个能力（验证码的 3 分钟有效期是存在 Redis 里的，不是 MQ 的 TTL），所以无影响。
2. **优先级**。没用到。
3. **"消费完就没了"的直觉**。这条最危险，因为它会让排障习惯整个失效：在 RabbitMQ 里"队列空了 = 都处理完了"；在 Kafka 里"topic 里有 100 万条"完全不代表有积压，**只有 lag 才代表积压**。把这条写在脑子里再去改代码。

---

## 4. 目标架构

### 4.1 拓扑

```text
浏览器 ──> nginx ──> backend:8080 ──┬──> MySQL
                                    ├──> Redis
                                    ├──> MinIO
                                    └──> kafka:9092（KRaft 单节点）
                                           ├─ mail            (1 分区, key=email)      ← group: mail-consumer
                                           ├─ mail-dlt        (1 分区)                  ← 上面重试 3 次后进来，没人消费，人工/脚本处理
                                           ├─ course.select   (3 分区, key=courseId)    ← group: course-select-consumer
                                           └─ course.select-dlt (3 分区)                ← 同上
```

### 4.2 主题定义

| topic | 分区 | 副本 | key | retention | 消费组 | DLT |
|---|---|---|---|---|---|---|
| `mail` | 1 | 1 | email（便于按人排查；单分区其实用不上） | 1 天 | `mail-consumer` | `mail-dlt` |
| `mail-dlt` | 1 | 1 | 原样 | 7 天 | 无（人工/脚本） | — |
| `course.select` | 3 | 1 | **courseId** | 3 天 | `course-select-consumer` | `course.select-dlt` |
| `course.select-dlt` | 3 | 1 | 原样 | 7 天 | 无 | — |

几个决定：

- **`course.select` 的分区数 = 3，不是拍脑袋**：抢课的实际并发瓶颈在 MySQL 那一行锁（`UPDATE ... WHERE selected_count < capacity`），不在 MQ。3 个消费线程已经足够把一个班的落库打满；分区多了只会让"同一门课串行"这个保证更难解释。压测之后要调就调，见坑 9。
- **key = `courseId`（不是 uid）**：同一门课的消费者必须串行，否则"条件 UPDATE + 回写结果"会互相打脸。同一门课的吞吐本来就被那一行锁限死了，按课程分区正好对齐。
- **`mail` 用 1 个分区**：邮件量一天几十封，多分区没有意义；少一个分区就少一个"为什么两条邮件乱序"的解释。代价是坑 3 的反面 —— 一条坏消息重试时会卡住后面 3 秒（可接受）。
- **DLT 的分区数 ≥ 源主题**：`DeadLetterPublishingRecoverer` 默认**把消息发到源记录所在的同一个分区号**。恢复器会先检查那个分区存不存在（`verifyPartition` 默认 true）—— 不存在时**不会报错**，而是把分区置空、让 producer 自己选。看起来"能跑"，代价是死信和原消息的分区对应关系丢了，事后想按课程对账就得扫全表。所以还是配齐（坑 5）。
- **retention 不是"越短越省事"**：`mail` 留 1 天是为了"今天早上那封没到的邮件能捞回来"；`course.select` 留 3 天是为了压测之后对账（Redis 结果键 vs DB 行 vs topic 里的消息）。再短就没法事后复盘了。

### 4.3 从"一条队列"到"一个 group + N 个分区"

| | 现在（RabbitMQ） | 之后（Kafka） |
|---|---|---|
| 并行度由谁决定 | `concurrency`（消费者线程数），可以超过队列数，超了就是多个消费者抢同一个队列 | **分区数**。`concurrency > 分区数` 的线程分不到分区，**空转**，看起来"加了线程但没变快" |
| 一条消息给谁 | 队列里任意一个空闲消费者 | 该分区唯一的消费者（组内） |
| 加机器扩容 | 加消费者就行（队列不限制） | 只能先加分区；**加分区会打乱 key→分区的映射**，同一门课的历史顺序不再保证（坑 9） |
| 顺序 | 单队列 FIFO（全局） | 分区内有序，跨分区无序 |

**在这个项目里**：`mail` 1 分区 → 1 个消费线程，`spring.kafka.listener.concurrency` 全局保持默认 1；`course.select` 在注解上写 `concurrency = "3"`，和分区数对齐。**不要**在 yaml 里设一个全局 `concurrency: 3` —— 那会让 mail 的 2 个线程白等分区，日志里看不出异常，只是"怎么不干活"。

---

## 5. 代码改造清单

下面每一小节都给"改成什么"，不逐行给 diff（diff 会因为注释风格变化而看不出重点）。

### 5.1 依赖

```xml
<!-- pom.xml，删掉 spring-boot-starter-amqp -->
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
    <!-- 不写 version：spring-boot-dependencies:3.5.11 已经把 spring-kafka 锁在 3.3.13，
         kafka-clients 锁在 3.9.1。自己写版本号等于放弃这条兼容性保证 -->
</dependency>
```

两个提醒：

- **不需要 `@EnableKafka`**。Boot 的 `KafkaAnnotationDrivenConfiguration` 已经带了这个注解（本机 `spring-boot-autoconfigure-3.5.11.jar` 里能直接看到 `KafkaAnnotationDrivenConfiguration$EnableKafkaConfiguration`）。网上大量教程让你加，加了也不报错，但它是多余的。
- 3.5.x **没有** `spring-boot-starter-kafka`（这个 artifact 到 4.0 的 milestone 才出现，3.5.11 的 BOM 里查不到它）。`spring-kafka` 本身带自动配置，再引 `spring-boot-starter` 只会重复。

### 5.2 配置类：删两个，加两个

**删** `EmailRabbitConfiguration`、`CourseRabbitConfiguration`。里面三样东西的去向：

| 原来 | 现在 |
|---|---|
| `QueueBuilder.durable("mail")` | `NewTopic` Bean（下面第一个类） |
| `Jackson2JsonMessageConverter` + `RabbitTemplate` | `spring.kafka.producer.value-serializer: ...JsonSerializer`（yaml，§6.1） |
| 死信参数 `x-dead-letter-exchange` / `x-dead-letter-routing-key` | `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`（下面第二个类） |

```java
package net.doudegua.config;

import net.doudegua.utils.Const;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 主题声明。
 * <p>
 * 这四个 {@link NewTopic} Bean 由 Boot 自动配置的 {@code KafkaAdmin} 在**启动时**创建。
 * 也就是说：主题不存在时不需要手工建，但**创建动作只发生在启动那一刻** ——
 * broker 当时没起来的话，主题不会在你后来连上时补建（见 §8 坑 8）。
 * <p>
 * 为什么不用 broker 的自动建主题（{@code auto.create.topics.enable}）：
 * 它的默认分区数由 broker 的 {@code num.partitions} 决定，主题一多就没人知道
 * 每个主题到底几个分区。显式声明之后，"分区数"变成代码里的一个数字，能 review。
 */
@Configuration
public class KafkaTopicConfiguration {

    /**
     * 验证码邮件。
     * <p>
     * 1 个分区：量小，且不希望出现"两封验证码的先后顺序不确定"。
     * retention 1 天：这封邮件如果今天没发出去，明天捞回来也没用了
     * （验证码 3 分钟过期），但留一天足够人工补发/排查。
     */
    @Bean
    public NewTopic mailTopic() {
        return TopicBuilder.name(Const.MAIL_TOPIC)
                .partitions(1)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(24 * 60 * 60 * 1000L))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    /**
     * 抢课。
     * <p>
     * ⚠️ 分区数不是"越多越快"：真正的串行点在 {@code UPDATE ... WHERE selected_count < capacity}
     * 那一行锁上。3 个分区只是让不同课程能并行，同一门课仍然排队 —— 这正是要的。
     * <p>
     * ⚠️ 分区数**只能加不能减**。加分区会让 key→分区 的映射变化，
     * 同一门课的历史消息顺序不再保证（见 §8 坑 9）。改之前想清楚。
     */
    @Bean
    public NewTopic courseSelectTopic() {
        return TopicBuilder.name(Const.COURSE_SELECT_TOPIC)
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(3 * 24 * 60 * 60 * 1000L))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    /**
     * 两个死信主题。
     * <p>
     * ⚠️ 必须显式建：主题不存在时恢复器发布失败 → 那条记录被 seek 回去重新走重试 →
     * 又失败 → 日志里同一个异常无限刷，而 DLT 里一条都没有（坑 5）。
     * {@code auto.create.topics.enable} 我们已经关了，所以"没建"不会被 broker 兜住。
     * <p>
     * ⚠️ 分区数和源主题**一样多**：默认发到源记录的同一个分区号。
     * 分区不够时恢复器不会抛异常，而是把分区置空让 producer 选 ——
     * 死信和原消息的分区对应关系因此丢失（按课程对账时用不上）。
     * <p>
     * 名字必须是 {@code <源主题>-dlt}：这是恢复器在 spring-kafka 3.3 的默认命名
     * （注意是短横线 + 小写，不是 {@code .DLT}）。要改就只能自己实现
     * {@code BiFunction<ConsumerRecord, Exception, TopicPartition>} 传进去。
     * 写死两遍容易写错，所以用常量拼。
     */
    @Bean
    public NewTopic mailDltTopic() {
        return TopicBuilder.name(Const.MAIL_TOPIC + Const.DLT_SUFFIX)
                .partitions(1)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(7 * 24 * 60 * 60 * 1000L))
                .build();
    }

    @Bean
    public NewTopic courseSelectDltTopic() {
        return TopicBuilder.name(Const.COURSE_SELECT_TOPIC + Const.DLT_SUFFIX)
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(7 * 24 * 60 * 60 * 1000L))
                .build();
    }
}
```

```java
package net.doudegua.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 消费失败怎么办 —— 对应 RabbitMQ 那边的 {@code x-dead-letter-exchange}。
 * <p>
 * ⚠️ 这个 Bean 会被 Boot 自动装到所有 {@code @KafkaListener} 上：
 * {@code KafkaAnnotationDrivenConfiguration} 的构造器里有个
 * {@code ObjectProvider<CommonErrorHandler>}，容器工厂会把唯一的那个塞进去。
 * <b>所以不要再手工 setCommonErrorHandler，也不要有第二个 CommonErrorHandler Bean</b> ——
 * 两个的话 {@code getIfUnique()} 返回 null，配置就被静默忽略，全都退回默认
 * （默认是 {@code FixedBackOff(0, 9)}：10 次尝试后只写一条日志，消息就过去了 —— 没有 DLT）。
 * <p>
 * 重试节奏：首次 + 3 次，每次间隔 1 秒。这是给"MySQL 抖了一下""SMTP 暂时连不上"
 * 这类**有救**的失败准备的；4 次都救不回来就进 DLT，人工处理。
 * <p>
 * 对比一下现在 RabbitMQ 的行为：{@code default-requeue-rejected=true} 会把消息
 * 立刻重投，实测 5 秒 16 次、刷了 1271 行日志。这里的上限是硬的（4 次），
 * 而且**每次重试都在消费线程里，不占 broker**。
 */
@Configuration
public class KafkaErrorHandlingConfiguration {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        // 默认目标主题 = 源主题 + "-dlt"，分区号沿用源记录的分区号
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));

        // 这几类"重试一万次也一样"的错误直接进 DLT，不浪费那 3 次重试。
        // ⚠️ 清单要按实测补：先让它们走一遍可重试，观察 DLT 里都是什么，再决定谁可以提前放弃。
        handler.addNotRetryableExceptions(
                MailAuthenticationException.class,  // SMTP 授权码错/被拒 —— 就是当年 5 秒重投 16 次的那个
                MailParseException.class,           // 地址非法、消息构造失败
                IllegalArgumentException.class      // 见 MailQueueListener：未知 type 走这条
        );

        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        return handler;
    }
}
```

两个 DTO（`entity/dto/` 下，和 `Account`、`Course` 放一起）：

```java
package net.doudegua.entity.dto;

/**
 * 验证码邮件任务。字段和原来的 {@code Map.of("type", ..., "email", ..., "code", ...)} 一一对应，
 * 只是有了类型 —— 这是坑 6 的正解：消息体不该是个无类型的 map。
 * <p>
 * 用 {@code record}：Jackson 从 2.12 起原生支持 record 反序列化（不需要 -parameters），
 * 而项目里本来就有 Jackson。
 */
public record MailTask(String type, String email, int code) {
}
```

```java
package net.doudegua.entity.dto;

/**
 * 抢课任务。{@code (uid, courseId)} 就是 {@code db_course_selection} 复合主键的两个字段，
 * 所以幂等键在消息外面（数据库），消息里不需要再塞一个 requestId。
 */
public record CourseSelectTask(int uid, int courseId) {
}
```

### 5.3 生产者

#### AccountServiceImpl（邮件）

```java
// 字段：AmqpTemplate amqpTemplate  →  KafkaTemplate<String, Object> kafkaTemplate
@Resource
KafkaTemplate<String, Object> kafkaTemplate;

// registerEmailVerifyCode 里原来那一行 convertAndSend 换成：
try {
    // ⚠️ 必须等 future，不能"发出去就算成功"。KafkaTemplate 的失败**分两类**：
    //   · 立刻失败（消息超过 1MB、序列化失败、元数据拿不到）→ send() 自己就抛
    //     KafkaException("Send failed", cause)，try-catch 接得住
    //   · 发出去之后才失败（delivery.timeout.ms 到期、broker 拒绝）→ 只体现在 future 上
    // 所以 try-catch 的位置和现在一样，但**多一个 get() 不能省** ——
    // 只接住前一类，"消息进了缓冲区然后丢了"就完全静默了。
    // 不等它 → 接口返回 200、Redis 里存下验证码、用户等一封永远不来的邮件。
    kafkaTemplate.send(Const.MAIL_TOPIC, email, new MailTask(type, email, code))
            .get(3, TimeUnit.SECONDS);
} catch (Exception e) {
    // ⚠️ 两个小前提，改的时候别漏：
    //   1. AccountServiceImpl 现在**没有 @Slf4j**（日志都写在 listener 里），
    //      要用 log 得先加注解；
    //   2. maskEmail() 现在在 MailQueueListener 里。失败日志同样不能出现完整邮箱，
    //      所以这个方法要挪到 utils（或复制一份）—— 别在这里直接 log email。
    log.error("验证码邮件入队失败：to={} —— 检查 Kafka 是否可达（KAFKA_BOOTSTRAP_SERVERS）",
            maskEmail(email), e);
    return "邮件发送失败，请稍后再试";
}
// 之后照旧写 Redis（3 分钟 TTL）。注意顺序：入队成功才写 Redis ——
// 写反了的话，Kafka 挂了用户还会拿到一个"已发送"的验证码，但它永远寄不出去
```

两个配套的配置（§6.1 里有）：`max.block.ms: 2000`、`delivery.timeout.ms: 5000`。不配这两个的话，`send()` 在拿不到元数据时最多会**阻塞 60 秒**（`max.block.ms` 默认值），`get(3, SECONDS)` 只能挡住 future，挡不住前面那一段。

> 另一条路是彻底异步（`whenComplete` 里处理失败 + 前端"没收到？重发"），那样接口更快，但前端要改。本项目现在没有前端改动需求，选阻塞 3 秒 —— 和现在的 RabbitMQ 行为等价，风险最小。

#### CourseServiceImpl（抢课）

TODO 注释里那段"发 MQ 失败要回补库存"**照抄会漏一半**：Kafka 的失败有一类只在 future 上出现。改成：

```java
try {
    kafkaTemplate.send(Const.COURSE_SELECT_TOPIC,          // topic
                       String.valueOf(courseId),           // key：同一门课进同一分区
                       new CourseSelectTask(uid, courseId)) // value
            .get(2, TimeUnit.SECONDS);
} catch (Exception e) {
    // ⚠️ 到这里说明消息**没进 broker**，而 Redis 的 Lua 已经 DECR 过库存了。
    // 不回补 = 这个名额永远少一个（少卖），而且用户看到一个"失败"却占了一个坑。
    // 顺序不能反：先回补，再返回失败。
    stringRedisTemplate.opsForValue().increment(Const.COURSE_STOCK + courseId);
    stringRedisTemplate.opsForSet().remove(Const.COURSE_CHOSEN + courseId, String.valueOf(uid));
    log.error("抢课消息入队失败，已回补库存：uid={} courseId={}", uid, courseId, e);
    // ⚠️ CourseSelectResultVo 现在只有 QUEUED / SUCCESS / SOLD_OUT / DUPLICATE /
    //    NOT_STARTED 五个状态，**没有"入队失败"这一档**。两条路：
    //      a) 在这里抛出去，让 Controller 变 500，前端提示"网络错误，请重试"；
    //      b) 加一个 FAILED 常量，前端加一个分支。
    //    别硬塞进 SOLD_OUT —— 那会让用户以为名额没了而直接放弃。
    throw new IllegalStateException("抢课消息入队失败，库存已回补", e);
}
return new CourseSelectResultVo(CourseSelectResultVo.QUEUED, null);
```

这段和 §5.3 邮件那段是同一个坑的两副面孔：`convertAndSend` 的失败是**同步抛**；Kafka 里只有一部分同步抛，另一部分只体现在 future 上。抢课把后果放大成"少卖一个名额"，邮件那边是"白等一封信"。

`@Resource` 而不是 `@Autowired`：Boot 自动配置的那个 Bean 声明类型是 `KafkaTemplate<?, ?>`，按名字注入最省事，不用和泛型匹配较劲。

`key` 用 `String.valueOf(courseId)` 而不是 uid，理由见 §4.2。还有一点：`KafkaTemplate` 要 `KafkaTemplate<String, Object>` —— 和 `JsonSerializer` 匹配；用 `KafkaTemplate<String, Object>` 但 yaml 里 value-serializer 写成 `StringSerializer`，运行时报的是 `ClassCastException`，不看配置很难想到。

### 5.4 消费者

#### MailQueueListener

```java
@KafkaListener(topics = Const.MAIL_TOPIC, groupId = "mail-consumer")
public void sendMailMessage(MailTask task) {
    // 入参从 Map<String,Object> 换成 DTO。原因不是"更优雅"，
    // 是因为 Map.of(...) 的类型头是 java.util.ImmutableCollections$MapN，
    // 反序列化端要么把 java.util 加进 trusted.packages，要么自己解头 —— 两条路都脏（坑 6）。
    String email = task.email();
    int code = task.code();
    String type = task.type();

    SimpleMailMessage mailMessage = switch (type) {
        case "register" -> createMessage("欢迎注册网站", "您的邮件注册验证码为：" + code + "，有效时间3分钟，打死也不要告诉别人哦～", email);
        case "reset"    -> createMessage("密码重置", "您正在重置密码，验证码为：" + code + "，有效时间3分钟，打死也不要告诉别人哦～", email);
        default -> null;
    };

    if (mailMessage == null) {
        // ⚠️ 这里从"log.warn 后 return"改成抛异常。
        // 原来 return 等于 ack，这条消息就**静默消失**了；现在它会进 mail-dlt
        // （IllegalArgumentException 在 KafkaErrorHandlingConfiguration 里被判为不可重试，
        //  所以不浪费时间重试），两边 type 对不上这件事因此变成可查的事实，而不是一行日志。
        throw new IllegalArgumentException("未知的邮件任务类型：" + type);
    }

    // 发信失败不 catch。理由同上：抛出去 → 有限重试 → DLT。
    // 而且 DLT 里的记录**保留了完整 payload**（含 code），
    // 这是现在"catch 住只打日志"做不到的 —— 现在的失败邮件没有任何残留。
    javaMailSender.send(mailMessage);
    log.debug("验证码邮件已发送：type={} to={}", type, maskEmail(email));
}
```

原类注释里那三大段关于"异步失败不可见""不记验证码"的教训**全部保留**，只把最后"故意不 rethrow"那一段的结论改掉：

> 旧结论是"抛出去会被无限重投，所以吞掉"。新结论是"重投有了上限（4 次），所以抛出去是对的，而且它带来的 DLT 让失败可重发"。**不要因为旧的注释写着"故意不 rethrow"就照抄。**

#### CourseSelectListener

```java
@KafkaListener(topics = Const.COURSE_SELECT_TOPIC,
               groupId = "course-select-consumer",
               concurrency = "3")   // = 分区数。多了空转，少了浪费分区（坑 3）
public void onCourseSelect(CourseSelectTask task) {
    ...
}
```

类注释里那段"⚠️ 骨架版会确认并丢弃消息"**必须删掉**：Kafka 里"方法正常返回"= 提交 offset，效果同样是丢；但因为 `DefaultErrorHandler` 现在接住了异常，真正的风险从"丢消息"变成"吞掉异常地丢消息"。所以结论要改成：

> 业务失败（名额满、重复）→ 正常返回 + 写结果键（不许抛，抛了会重试 4 次再做同样的事，最后进 DLT 制造假告警）；
> 基础设施失败（DB 连不上）→ 抛出去，交给重试；4 次不成就进 DLT 等人工。

### 5.5 Const

```java
/** 验证码邮件主题。见 KafkaTopicConfiguration */
public static final String MAIL_TOPIC = "mail";

/** 抢课主主题。key = courseId，分区数 3，见 KafkaTopicConfiguration */
public static final String COURSE_SELECT_TOPIC = "course.select";

/**
 * 死信主题后缀。
 * <p>
 * ⚠️ 这个字符串**不能改**：它是 {@code DeadLetterPublishingRecoverer} 在
 * spring-kafka 3.3 的默认命名（短横线 + 小写）。改成 ".DLT" 或者 ".dlq" 之后，
 * 恢复器仍然往 "xxx-dlt" 投，而你没建那个主题 ——
 * 于是坏消息的处理方式变成"发布失败 + seek 回去重试"，比不处理还糟（坑 5）。
 */
public static final String DLT_SUFFIX = "-dlt";
```

命名变化对照（写进 commit message，方便日后 grep）：

| 旧 | 新 |
|---|---|
| `mail`（队列） | `mail`（主题） |
| `course.select`（队列） | `course.select`（主题） |
| `course.select.dlq`（队列） | `course.select-dlt`（主题，名字由框架定） |
| `Const.COURSE_SELECT_QUEUE` | `Const.COURSE_SELECT_TOPIC` |
| `Const.COURSE_SELECT_DLQ` | 删掉，用 `COURSE_SELECT_TOPIC + DLT_SUFFIX` |

⚠️ 后缀是 `-dlt` 而不是 `.DLT`，这一点值得单独记一笔：很多 2020 年前后的教程写的是
`.DLT`（那是老版本的默认值），照着抄会得到一个"日志里一直在发 DLT、DLT 里一条没有"的系统。
落笔前以当前版本的参考文档为准（本机 `spring-kafka 3.3.13` → `-dlt`）。

### 5.6 删完要 grep 一遍

```bash
grep -rn "amqp\|Rabbit\|rabbit" my-project-backend/src docker-compose.yml .env.example AGENT.md
```

预期只剩注释里的历史说明。**`spring-boot-starter-amqp` 忘了删的后果**不是编译失败（没有引用就不会报错），而是 `spring.rabbitmq.*` 若还留着、broker 又没了，启动时会尝试连 5672 并刷一堆 `Connection refused` —— 看着像 Kafka 配错了。

---

## 6. 配置与部署

### 6.1 application.yaml

把 `spring.rabbitmq`（82–85 行）整段换成：

```yaml
  kafka:
    # compose 里由 compose 注入 KAFKA_BOOTSTRAP_SERVERS=kafka:9092；
    # 本机 mvn spring-boot:run 用默认值。注意端口是 **29092** 不是 9092 ——
    # 9092 只在 compose 内部网络里可达，宿主机连不上（§6.2 的双 listener 解释）
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:29092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      # all = 等所有 ISR 确认。单节点就是等它自己，但配 all 是对的：
      # 将来加 broker 不用改配置，而且 acks=all 才会开启幂等发送（防重）
      acks: all
      properties:
        # ⚠️ 键里带点，用**方括号**写法。这是 Boot 文档规定的方式（"key 含 . 时用 [ ]"），
        #    写成裸的 max.block.ms: 2000 有时也能绑上，但那是在赌绑定器的行为。
        #
        # 默认 60 秒。broker 不可达、元数据拿不到时，send() 会先在这里阻塞，
        # 然后才轮到你的 future.get()。不压下来，接口挂 60 秒，而且 future 早就超时了。
        "[max.block.ms]": 2000
        # 默认 120 秒。这是"多久之后认定这条消息没发出去"：配 120 秒，future 一直挂着；
        # 配 5 秒，失败能在请求内被看到。它必须 > request.timeout.ms + linger.ms。
        "[delivery.timeout.ms]": 5000
        # 类型头开关默认 true，这里不写：消费端靠 __TypeId__ 决定反序列化成
        # MailTask 还是 CourseSelectTask。关掉的话就得给每个 listener 单独配默认类型（坑 6）。
    consumer:
      # 全局默认组。单个 listener 上有 groupId 时会覆盖它。
      # ⚠️ 必须存在：没有 groupId 的 @KafkaListener 会让容器启动失败
      #    （IllegalStateException: Consumer group id must be provided），不是警告
      group-id: campus-forum
      # ⚠️ 显式写 latest。Kafka 客户端默认也是 latest，但**别依赖默认值**：
      #    谁把它改成 earliest，下次部署就会把 topic 里留着的验证码全发一遍（坑 2）
      auto-offset-reset: latest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        # ⚠️ 必须把 DTO 的包名加进来。默认白名单只有 java.util / java.lang，
        #    于是 net.doudegua.entity.dto.MailTask 会被拒：
        #    "The class ... is not in the trusted packages"（坑 6）
        # 同样用方括号：键里带点。
        "[spring.json.trusted.packages]": net.doudegua.entity.dto
    listener:
      # record：一条一条提交。默认是 BATCH —— 一批里第 3 条失败、重试成功之后，
      # offset 可能已经越过前两条，重启后丢的是"前两条"这种反直觉的东西
      ack-mode: record
      # 主题不存在时**不要**让容器启动失败。默认已经是 false，写出来是为了防手滑
      missing-topics-fatal: false
    admin:
      # broker 比后端晚起来时，别让整个应用启动失败。
      # 默认为 false。改成 true 的话 "docker compose up" 时后端会直接退出，靠 restart 重试
      fail-fast: false
```

**不要**在这里写 `spring.kafka.listener.concurrency`（理由见 §4.3：mail 只有 1 个分区）。

### 6.2 docker-compose.yml：Kafka 服务

替换掉 281–296 行的 `rabbitmq` 服务：

```yaml
  # ---------------------------------------------------------------------------
  # Kafka —— 单节点 KRaft（无 ZooKeeper），验证码邮件 + 抢课落库
  #
  # 官方 apache/kafka 镜像的约定：KAFKA_ 前缀 + 配置项名（点换下划线）。
  # 具体变量名以镜像自带 README 为准 —— 换镜像小版本前先对一遍。
  # ---------------------------------------------------------------------------
  kafka:
    image: apache/kafka:3.9.1          # 和 kafka-clients 3.9.1 对齐（Boot 3.5.11 锁的版本）
    container_name: campus-forum-kafka
    restart: unless-stopped
    environment:
      # --- KRaft：一个进程同时当 broker 和 controller ---
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      # 这一项**不是必须的**：镜像自带一个默认 cluster id（官方 README 明确写了）。
      # 写出来的理由有两个：① 数据卷是用哪个 id 格式化的，在文件里看得见；
      # ② 换机器/重建卷时不用担心"默认值和卷里 meta.properties 记的不一致"。
      # ⚠️ 一旦设了就不要再改：卷里记的是旧 id，改了 broker 直接起不来（坑 10）。
      # 取值是合法的 base64-UUID；想要新的：docker run --rm apache/kafka:3.9.1 \
      #   /opt/kafka/bin/kafka-storage.sh random-uuid
      CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qk

      # --- 两个 listener：容器之间走 kafka:9092，宿主机走 localhost:29092 ---
      # 只有一个 listener 是不够的：advertised 里写 kafka:9092 宿主机解析不了，
      # 写 localhost:9092 容器之间连不到自己以外的名字。
      KAFKA_LISTENERS: INTERNAL://:9092,CONTROLLER://:9093,EXTERNAL://:29092
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:9092,EXTERNAL://localhost:29092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      # 注：KAFKA_LISTENERS 其实可以省 —— 镜像会拿 ADVERTISED 里的 host 换成 0.0.0.0 推出来。
      # 但 INTER_BROKER_LISTENER_NAME 在"定义了多个 listener"时**必须给**，
      # 否则 broker 不知道副本同步该走哪个 listener（症状是启动即失败）。
      # 另外环境变量名的映射规则是：KAFKA_ 前缀，"." → "_"，"_" → "__"，"-" → "___"，
      # 所以 log.dirs 写成 KAFKA_LOG_DIRS 是对的，别写成 KAFKA_LOG__DIRS。

      # --- 单节点必须改成 1。Kafka 默认值是 3，单节点凑不出 3 个副本 →
      #     __consumer_offsets 建不出来 → 消费组用不了，启动日志一片 ERROR ---
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_DEFAULT_REPLICATION_FACTOR: 1

      # --- 关掉自动建主题 ---
      # 打开它的话，打错一个主题名不会报错，而是悄悄建一个新主题并丢消息进去。
      # 我们的主题由 NewTopic Bean 显式声明（§5.2），分区数才在代码里可 review。
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"

      # --- JVM 堆 ---
      # 不设的话走 kafka-run-class.sh 的默认 -Xmx1G -Xms1G（固定值，不是按容器内存算）。
      # 512m 是单节点学习的下限；再压到 256m 容易在创建内部主题/日志压缩时 OOM。
      KAFKA_HEAP_OPTS: "-Xmx512m -Xms512m"
      # 不用动 KAFKA_JVM_PERFORMANCE_OPTS：默认已经带 G1 + MaxGCPauseMillis=20，
      # 手写一份等于把默认值抄一遍，抄漏一个参数反而更难查。
      # 和上面的 volume 对齐。镜像默认在 /tmp 下，容器重建就丢。
      KAFKA_LOG_DIRS: /var/lib/kafka/data
    volumes:
      # 数据目录。⚠️ 必须和上面的 KAFKA_LOG_DIRS 路径一致：
      # 镜像默认是 /tmp/kraft-combined-logs，不显式改的话卷挂上了也是空的，
      # 数据全在容器临时目录里，`docker compose down` 之后连 offset 一起消失（坑 10）。
      - kafka-data:/var/lib/kafka/data
    ports:
      # 只发布 EXTERNAL。9092 不给宿主机，避免和本机别的东西抢端口
      - "29092:29092"
    healthcheck:
      # 不用 nc 探端口：端口通了不代表 broker 可用（还在格式化/选举）。
      # 这个脚本会真的发一次 ApiVersions 请求。
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1 || exit 1"]
      interval: 15s
      timeout: 10s
      retries: 5
      start_period: 30s
    networks: [app]
```

backend 服务要改两处（原来在 324、341–343 行）：

```yaml
    depends_on:
      # …… MySQL / redis / minio / db-init 那几项保持原样，只把 rabbitmq 换成 kafka
      kafka:
        condition: service_healthy   # 原来这里是 rabbitmq
    environment:
      # …… 其余环境变量保持原样
      # 容器内部走 INTERNAL listener
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
```

卷声明（459 行附近）：

```yaml
volumes:
  kafka-data:
  # rabbitmq-data 先留着，等 Phase 4 再删（§9 回滚）
```

### 6.3 要不要 kafka-ui

15672 那个管理界面在这里是有实际价值的（"队列里现在有没有积压"一眼能看到），Kafka 没有等价的开箱界面。两个选择：

- **命令行够用**（推荐先这样）：lag 用 §10 的 `kafka-consumer-groups.sh`。
- **想要界面**：加一个可选服务，`docker compose --profile ui up -d` 才启动。
  下面的镜像是 `kafbat/kafka-ui`（`provectuslabs/kafka-ui` 已归档，别再用了）：

```yaml
  kafka-ui:
    # ⚠️ 别用 provectuslabs/kafka-ui：那个仓库已经归档，tag 停在旧版本。
    #    现在还在维护的是社区分支 kafbat/kafka-ui，环境变量名基本一致。
    image: kafbat/kafka-ui:latest
    container_name: campus-forum-kafka-ui
    profiles: ["ui"]              # 不加 --profile ui 就不会启动
    depends_on:
      kafka:
        condition: service_healthy
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092
    ports:
      - "8089:8080"               # 8080 是 backend，别撞
    networks: [app]
```

### 6.4 .env.example

```dotenv
# --- Kafka ----------------------------------------------------------------
# 只有本机直连（mvn spring-boot:run）才需要这一行；compose 会自动注入 kafka:9092。
# 端口是 29092（EXTERNAL listener），不是 9092。
# KAFKA_BOOTSTRAP_SERVERS=localhost:29092
#
# 不再需要用户名/密码：单节点 PLAINTEXT。真要上生产，认证是 SASL + ACL 一套，
# 不是改两个环境变量能解决的 —— 那边另说。
```

同时把文件头部"至少要改 DB_PASSWORD、JWT_KEY、MINIO_ROOT_PASSWORD、RABBITMQ_PASSWORD"里的 `RABBITMQ_PASSWORD` 删掉。

### 6.5 版本对齐

| 组件 | 版本 | 依据 |
|---|---|---|
| Spring Boot | 3.5.11 | `pom.xml` |
| spring-kafka | 3.3.13 | `spring-boot-dependencies:3.5.11` 第 195 行 |
| kafka-clients | 3.9.1 | 同上，第 117 行 |
| broker 镜像 | `apache/kafka:3.9.1` | 官方 Docker 文档里就是 `docker pull apache/kafka:3.9.1`，tag 存在；和客户端同一条 3.9 线。Kafka 双向兼容跨 2 个小版本，但没必要冒这个险 |
| Java | 17+ | spring-kafka 3.3 要求 17；本项目用 21 跑，没冲突 |

**不要**给 `spring-kafka` 手写 `<version>`。写了就会和 Boot 管的 `kafka-clients` 版本打架，症状是启动时 `NoSuchMethodError`，而且栈里一句人话都没有。

---

## 7. 实施步骤

原则：**每一步都能单独验收、单独回滚**。别一次性改完再启动。

| Phase | 动作 | 验收（命令可直接跑） | 回滚 |
|---|---|---|---|
| **0** 基线（10 分钟） | `git tag before-kafka`；`docker compose config > /tmp/compose.before.yml`；记录现在 `rabbitmqctl list_queues name messages` 的输出 | `git tag -l before-kafka` 有输出 | — |
| **1** 基础设施（1 小时） | 只改 `docker-compose.yml`：加 `kafka` 服务、`kafka-data` 卷、backend 的 `depends_on` 和 `KAFKA_BOOTSTRAP_SERVERS`。**不动 Java 代码**，RabbitMQ 服务先留着 | 见下方"Phase 1 验收" | 注释掉 kafka 服务，恢复 `depends_on: rabbitmq` |
| **2** 只迁 mail（2 小时） | pom 换依赖；加两个配置类 + DTO；改 `MailQueueListener` / `AccountServiceImpl` / `Const`；yaml 换 `spring.kafka` 段 | 见下方"Phase 2 验收" | `git checkout` 这几个文件 + `git checkout docker-compose.yml` 的 rabbit 段；RabbitMQ 容器和卷一直在，起来就能用 |
| **3** course 随实现一起迁（和抢课功能同一个 PR） | `CourseServiceImpl.select()` 实现（含 §5.3 的补偿）、`CourseSelectListener` 补核心逻辑 | 抢课设计的验收标准（`docs/course-grab-design.md` §6），加一条 `kafka-consumer-groups.sh` 看 lag 归零 | 单独 revert 这个 PR |
| **4** 清理（30 分钟） | 删 `spring-boot-starter-amqp`（若 Phase 2 留着没删）、删 compose 的 rabbitmq 服务与 `rabbitmq-data` 卷、改 `.env.example` / `AGENT.md` / `README.md` 里的端口说明 | `grep -rn "rabbit" --include="*.java" --include="*.yml" .` 只剩注释 | 卷删了就回不去了 —— **建议 Phase 4 隔一周再做** |

### Phase 1 验收

```bash
docker compose up -d kafka
docker compose ps kafka                      # 状态要是 healthy，不是 starting
docker exec campus-forum-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list   # 单节点 KRaft 至少有 __consumer_offsets
docker exec campus-forum-kafka /opt/kafka/bin/kafka-broker-api-versions.sh \
  --bootstrap-server localhost:9092 | head -3
# 宿主机能不能连（验证 EXTERNAL listener 的 advertised 地址对不对）
# macOS / Windows 上 --network host 实际上不通，用 host.docker.internal：
docker run --rm apache/kafka:3.9.1 \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server host.docker.internal:29092 --list
# Linux 上可以：docker run --rm --network host apache/kafka:3.9.1 ... localhost:29092
```

最后一条最容易失败，也最容易被忽略：**advertised 地址写错了，元数据能拿到、连上就被踢**，报错是 `Connection to node -1 could not be established`，看着像网络问题。宿主机连不上就检查 `KAFKA_ADVERTISED_LISTENERS` 里的 `EXTERNAL://localhost:29092`。

### Phase 2 验收

```bash
# 1) 主题和分区数对了
docker exec campus-forum-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic mail
# 期望 PartitionsCount 1, ReplicationFactor 1

docker exec campus-forum-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic mail-dlt

# 2) 端到端：注册验证码真的能收到
curl -s 'http://localhost:8080/api/auth/ask-code?email=你的邮箱@qq.com&type=register'
# 注意是 GET（AuthorizeController 里 @GetMapping），不是 POST
# 收件箱里要有信；没有就查下面两条

docker compose logs backend | grep -i "验证码邮件"
docker exec campus-forum-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic mail --from-beginning --max-messages 1 \
  --property print.key=true --property print.timestamp=true
# ⚠️ 这条命令会真的再消费一遍！只是在验证消息长什么样。
#    想避免干扰，用 --group probe-group-$(date +%s) 单独建个组

# 3) lag 归零（消费完了）
docker exec campus-forum-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group mail-consumer
# LAG 列应该是 0

# 4) 失败进 DLT —— 分两个场景，别混在一起看
#
# 4a) 认证失败：把 MAIL_PASSWORD 改成错的，重启后端，请求一次验证码
#     预期：日志 1 条错误（0 次重试，因为 MailAuthenticationException 被判为不可重试），
#           消息直接出现在 mail-dlt 里，含 code。
#           对比现在：默认 requeue 会重投到爆（实测 5 秒 16 次、1271 行日志）。
docker exec campus-forum-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic mail-dlt --from-beginning --timeout-ms 5000
#
# 4b) 暂时性失败：只把 MAIL_HOST 指到一个连不通的地址（比如 10.255.255.1）并保留正确密码，
#     请求一次验证码。
#     预期：日志 4 次尝试（首次 + 3 次重试，间隔 1 秒），然后进 mail-dlt。
#           SMTP 连接超时抛的是 MailSendException，属于可重试。
# 两个场景都要跑：4a 验证"分类"，4b 验证"重试真的有限且有间隔"。
```

**第 4 条是这次迁移唯一"必须做"的验证**。它同时证明了三件事：重试有上限、
坏消息没丢（DLT 里能捞回 code）、DLT 名字和分区都对。

---

## 8. 十个坑

每一条都是"现象 → 原因 → 做法"。

### 坑 1：`send()` 有两类失败，只 catch 住一类 → 抢课的库存补偿会漏

- **现象**：Kafka 挂了（或网络闪断），抢课接口返回 `QUEUED`，Redis 里库存少了一个，DB 里没有那一行，`course:result:*` 永远是空。用户反复重试，名额一路少下去。
- **原因**：`KafkaTemplate.send()` 的失败**不是一种**：
  - **立刻失败**（消息过大、序列化失败、元数据拿不到）→ `send()` 自己抛 `KafkaException("Send failed", cause)`；
  - **发出去之后失败**（`delivery.timeout.ms` 到期、broker 拒绝）→ 只让 future 异常完成。

  只写 `try { send(...) } catch` 就只接住了第一类。第二类要 `future.get(...)` 或 `whenComplete` 才看得见 —— 而"消息进了缓冲区、5 秒后悄悄丢了"恰恰是最常见的那类。
- **做法**：`.get(3, TimeUnit.SECONDS)`（或 `whenComplete` 里做补偿），并且把 `max.block.ms` 压到 2 秒、`delivery.timeout.ms` 压到 5 秒。**别用默认值**：默认 `max.block.ms` 60 秒 + `delivery.timeout.ms` 120 秒，最坏情况一个 HTTP 请求挂两分钟。

### 坑 2：`auto-offset-reset: earliest` 会把历史验证码全发一遍

- **现象**：第一次部署、或者改了 `group-id`、或者 DLT 那边的组被重置之后，收件箱炸了 —— 几十封验证码邮件，全是旧码，全都没用。
- **原因**：RabbitMQ 里队列被消费完就空了，"重新消费"这个词不存在。Kafka 里消息按 retention 留着（`mail` 留 1 天），而 consumers group 没有 offset 时的策略由 `auto-offset-reset` 决定；`earliest` = 从头读。
- **做法**：显式写 `latest`（§6.1），不要依赖客户端默认值。另外把 `mail` 的 retention 设成 1 天而不是 7 天 —— 万一真被重放，也只炸一天的量。改 `group-id` 之前先问自己"这会不会重放"。

### 坑 3：`concurrency` 超过分区数 → 线程空转

- **现象**：把 `spring.kafka.listener.concurrency` 从 1 调到 5，压测吞吐一点没变，CPU 也不忙，日志里看不出任何异常。
- **原因**：一个消费组里，一个分区只能分给一个消费者线程。5 个线程抢 1 个分区 → 4 个永远 `poll()` 到空。
- **做法**：`concurrency` 永远 ≤ 分区数。`mail` 留 1（yaml 全局不设），`course.select` 在注解上写 `concurrency = "3"` 并在注释里写"=分区数"。要更多并行就先加分区，但见坑 9。

### 坑 4：吞掉异常 = 提交 offset = 丢消息（和 RabbitMQ 的 ack 是同一个坑，换了个名字）

- **现象**：某条消息处理失败了，日志有一行 ERROR，然后再也找不到它。
- **原因**：监听方法**正常返回** → 容器提交 offset → 这条消息在逻辑上"处理完了"。RabbitMQ 那边叫 ack，这里叫 commit，本质一样。
- **做法**：可恢复的失败**抛**；不可恢复的业务失败显式写结果（比如 `course:result=0`）后正常返回。要让"失败"这件事留下痕迹，就靠 DLT —— 这正是 `DefaultErrorHandler` 存在的意义。`MailQueueListener` 里"故意不 rethrow"的旧注释必须删掉，否则下一个人会照着抄。

### 坑 5：DLT 主题不存在 / 分区不够 → 错误处理本身在报错

- **现象**：日志里同一个异常反复出现，但 DLT 里一条消息都没有；偶尔还有 `TimeoutException: Topic xxx-dlt not present in metadata`。
- **原因**：两件事都可能 ——（a）DLT 没建（我们关掉了 `auto-create`，`NewTopic` Bean 里漏了这个主题）→ 恢复器发布失败，记录被 seek 回去重新走重试，于是"重试 → 恢复 → 恢复也失败 → 再重试"地转圈；（b）后缀写错（写成 `.DLT` / `.dlq`，而默认是 `-dlt`）→ 和第 (a) 条一模一样，因为那个主题当然也不存在。
- **做法**：每个源主题都配一个 `<topic>-dlt`，分区数 ≥ 源主题；名字用常量拼（`Const.MAIL_TOPIC + Const.DLT_SUFFIX`），别在两个地方各写一遍字面量。上线前用 §7 Phase 2 验收的第 4 条实测一次 —— 这条验收会同时暴露 (a) 和 (b)。

### 坑 6：类型头导致反序列化失败

- **现象**：消费者一条都收不到，日志里 `SerializationException: The class 'java.util.ImmutableCollections$MapN' is not in the trusted packages` 或 `class not found`。
- **原因**：生产端 `JsonSerializer` 会在消息头写 `__TypeId__` = payload 的**全限定类名**。原来代码里用的是 `Map.of("type", ...)`，它的实际类是 `java.util.ImmutableCollections$MapN`；消费端 `JsonDeserializer` 默认只信任 `java.util, java.lang`，于是 `net.doudegua.entity.dto.MailTask` 被拒。
- **做法**：两层。① 入参从 `Map` 换成 DTO（`MailTask` / `CourseSelectTask`），入参类型就是真相，别让消息体是个无类型的 map；② 消费端配 `"[spring.json.trusted.packages]": net.doudegua.entity.dto`（**方括号**，键里带点时不加括号会绑不上，症状和"没配"完全一样）。
  - 另一条路是关掉类型头（`spring.json.use.type.headers: false`）+ 给每个 listener 单独配默认类型 —— 更"安全"，但要为两个主题写两个 `ConsumerFactory`。**学习项目不值得**，记住这条就够了。
  - 附带风险：DTO 改名/挪包之后，**存量的未过期消息**（retention 内的）会反序列化失败进 DLT。改 DTO 前先看眼 `mail` 的 retention。

### 坑 7："topic 里有 100 万条"不等于积压

- **现象**：第一天用 kafka-ui 看 `mail` 里有几万条消息，以为炸了，实际功能完全正常。
- **原因**：RabbitMQ 的队列长度 = 待处理数；Kafka 的 topic 大小 = 保留量。消息读完还在，直到 retention 过期。
- **做法**：只看 **lag**（`kafka-consumer-groups.sh --describe` 的 `LAG` 列，或 kafka-ui 的 Consumer Groups 页）。`current-offset` 和 `log-end-offset` 的差才是积压。

### 坑 8：启动顺序与"主题没人建"

- **现象**：先 `docker compose up -d kafka` 再起后端，一切正常；反过来先起后端、Kafka 晚 30 秒起来，后端不报错，但之后所有 `send()` 都超时，topic 一直不存在。
- **原因**：`KafkaAdmin` 只**在启动时**创建 `NewTopic` Bean，之后不再重试；`fail-fast=false`（默认）让它失败也不吭声。于是"应用起来了"和"主题建好了"是两件事。
- **做法**：compose 里 `depends_on: kafka: condition: service_healthy`（§6.2）。本地手工起 Kafka 时，先看到 `kafka-topics.sh --list` 有输出再起后端。真遇到这个状态，重启后端一次就好 —— 但要先知道是这个原因，否则会去查序列化器。

### 坑 9：分区只能加，加分区会打乱 key 的顺序保证

- **现象**：给 `course.select` 从 3 个分区加到 6 个，之后偶尔出现同一门课的两条消息被两个消费者同时处理，条件 UPDATE 的"名额刚好用完"那一瞬间出现异常行为。
- **原因**：`partition = hash(key) % 分区数`。分区数变了，同一个 `courseId` 映射到的分区也就变了 —— **切换那一刻**，旧分区还有未消费完的消息，新分区已经开始收新消息，同一门课的两条消息在短时间内失去了串行保证。
- **做法**：加分区要挑没有在途消息的时候（先停生产者、等 lag 归零）。这个项目的正确做法是**一开始就定好 3 个**，压测之后再说。别指望"以后加分区"是个无痛操作。

### 坑 10：数据卷"挂上去是空的"，或者 cluster id 对不上

- **现象**：两种，都很像"Kafka 坏了"：
  - 重启后 offset 全丢、DLT 里的东西没了 —— 但 `docker compose ps` 一切正常；
  - 或者 broker 起不来，日志说 `Inconsistent cluster ID` / `... is not a valid cluster id`。
- **原因**：
  - 前者：镜像默认的 `log.dirs` 是 `/tmp/kraft-combined-logs`（在容器临时目录里）。只挂 `- kafka-data:/var/lib/kafka/data` 而不设 `KAFKA_LOG_DIRS`，卷就是挂了个空目录，数据全在 `/tmp`（§6.2）。
  - 后者：`CLUSTER_ID` 和数据卷里 `meta.properties` 记的值不一致。注意 `CLUSTER_ID` **不是必填项**，镜像自带默认值；出问题的是"你设过、后来又改了"。
- **做法**：`KAFKA_LOG_DIRS` 和 volume 路径对齐，且 `CLUSTER_ID` 写死一个值并跟着代码走，**设了就别改**。真要重置：`docker compose rm -sf kafka && docker volume rm my-project_kafka-data`，确认 `CLUSTER_ID` 没变，再 `up`。删卷之前想清楚：`__consumer_offsets` 也在里面，offset 全丢意味着按 `auto-offset-reset` 重新开始。

---

## 9. 回滚方案

### 9.1 本项目（本地、无流量）

回滚成本 ≈ `git checkout`：

```bash
# 回到 RabbitMQ 版本（Phase 2 之后还没做 Phase 4 时）
git checkout before-kafka -- my-project-backend/pom.xml \
    my-project-backend/src/main/java/net/doudegua \
    my-project-backend/src/main/resources/application.yaml \
    docker-compose.yml
docker compose up -d --remove-orphans
docker compose ps    # rabbitmq 应该是 healthy
```

前提：**Phase 4 之前不删 `rabbitmq` 服务和 `rabbitmq-data` 卷**。留着它几乎不花代价（容器不起就没内存占用）。

### 9.2 如果这台机器已经有真实流量（不是本项目的情况，但写下来）

不能"停 RabbitMQ → 起 Kafka"，因为中间的在途消息没人处理。顺序：

1. **停生产者**（后端先不接收新请求，或只读）；
2. **排空旧队列**：`rabbitmqctl list_queues name messages` 等到 `mail` 为 0；
3. 切消费端到 Kafka（此时 `mail` 里是空的，不会有消息被漏掉，也不会双发）；
4. 切生产端；
5. 观察 24 小时（一个 retention 周期），再删 RabbitMQ。

**明确不做双写**：邮件双写就是发两封，抢课双写就是扣两次库存。双写在"两个系统都幂等"的场合才有意义，这里不成立。

---

## 10. 验收清单

### 10.1 命令速查（替换掉 `rabbitmqctl`）

| 想干什么 | 命令 |
|---|---|
| 看有哪些 topic | `docker exec campus-forum-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list` |
| 看 topic 的分区/副本 | `... kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic mail` |
| **看积压** | `... kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group mail-consumer` |
| 看有哪些消费组 | `... kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list` |
| 手工灌一条消息 | `... kafka-console-producer.sh --bootstrap-server localhost:9092 --topic mail`（然后输入 JSON） |
| 看消息内容 | `... kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic mail --from-beginning --max-messages 3` |
| 看 DLT 里有什么 | 同上，`--topic mail-dlt` |
| 重置某组 offset（危险） | `... kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group mail-consumer --reset-offsets --to-latest --topic mail --execute` |

### 10.2 端到端（这个必须全绿）

- [ ] 注册流程：请求验证码 → 收到邮件 → 用码注册成功
- [ ] 重置密码流程同上
- [ ] 认证失败（改坏 `MAIL_PASSWORD`）→ 请求验证码 → 不收到信、日志 1 条错误、`mail-dlt` 里有 1 条含 code 的记录（不重试）。改回来 → 重启 → 正常
- [ ] 暂时性失败（`MAIL_HOST` 指到连不通的地址）→ 日志 4 次尝试后才进 `mail-dlt`
- [ ] 坏消息（手工往 `mail` 灌一条没有 `__TypeId__` 头的 `{"type":"foo"}`）→ 进 DLT，不是静默消失。
      注意它会死在**反序列化**这一步（`DeserializationException` 默认就是不可重试），
      不会走到"未知 type"那条 `IllegalArgumentException` 分支 —— 两件事都通向 DLT，原因不同
- [ ] broker 停掉后请求验证码 → 接口在 **3 秒内**返回失败（不是挂 60 秒）
- [ ] broker 停掉再起来 → 不用重启后端，新的请求正常
- [ ] `docker compose down && up` → 后端能起来、能发信（验证卷和 `CLUSTER_ID` 都对）
- [ ] 抢课（Phase 3 之后）：`course.select` 的 LAG 在压测后归零，`course:result:*` 与 DB 行一致

---

## 11. 不做的事（明确写下来，避免下次又讨论一遍）

| 不做 | 理由 |
|---|---|
| Exactly-Once / Kafka 事务 | 本项目靠"至少一次 + 幂等键（复合主键 + `INSERT IGNORE`）"就够了，而 EOS 需要 `transactional.id`、消费端 `isolation.level=read_committed`、以及"事务只覆盖 Kafka 内"这个前提 —— 我们的写入落在 MySQL，EOS 根本管不到 |
| 多节点集群 / 3 副本 | 单机学习，1 副本。副本数在这个环境里只会让 broker 拒绝启动（ISR 不够） |
| 非阻塞重试主题（`@RetryableTopic`） | Boot 3.5 已经有 `spring.kafka.retry.topic.enabled`，功能是现成的；但"重试也排到另一个 topic"会让排障路径变长（消息在 5 个 topic 之间跳）。`FixedBackOff` + DLT 已经解决"无限重投"这个真问题 |
| Schema Registry / Avro / Protobuf | 两个 DTO，JSON 够了。引入 Registry 要先起一个服务、写 schema、配序列化器 —— 投入产出比在这个规模是负的 |
| Kafka Connect / 用 CDC 替掉 MyBatis 写入 | 完全另一个话题 |
| kafka-exporter + Grafana 面板 | 有价值，但可以后补。`kafka-consumer-groups.sh` 已经能回答"有没有积压" |
| 让 backend 通过 `/actuator/health` 反映 Kafka 状态 | Boot 没有 Kafka 的 health indicator（本机 `spring-boot-autoconfigure-3.5.11.jar` 里没有这个类）。想要就得自己写 `HealthIndicator`，不在本次范围 |

---

## 12. 附录

### A. 新旧配置对照

| 旧（`application.yaml`） | 新 |
|---|---|
| `spring.rabbitmq.addresses` | `spring.kafka.bootstrap-servers` |
| `spring.rabbitmq.username/password` | 无（PLAINTEXT 单节点） |
| 全局 `Jackson2JsonMessageConverter` Bean | `spring.kafka.producer.value-serializer` + `spring.kafka.consumer.value-deserializer` |
| （无） | `spring.kafka.consumer.group-id`（没有它容器起不来） |
| （无，默认 requeue=true） | `spring.kafka.listener.ack-mode: record` + `DefaultErrorHandler` |
| （无，默认 per-queue concurrency 1、prefetch 250） | 分区数 + `max.poll.records` |

### B. 迁移后各文件的一句话状态

| 文件 | 状态 |
|---|---|
| `config/KafkaTopicConfiguration.java` | 新增：4 个 `NewTopic` |
| `config/KafkaErrorHandlingConfiguration.java` | 新增：`DefaultErrorHandler` + DLT |
| `entity/dto/MailTask.java` / `CourseSelectTask.java` | 新增：`record`，替代 `Map.of(...)` |
| `config/EmailRabbitConfiguration.java` / `CourseRabbitConfiguration.java` | 删除 |
| `listener/*` / `AccountServiceImpl` / `CourseServiceImpl` / `Const` | 改（§5.3–5.5） |
| `docker-compose.yml` / `.env.example` / `AGENT.md` | 改（§6） |

### C. 参考资料

- [Spring for Apache Kafka 3.3 — Handling Exceptions](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html)：
  `DefaultErrorHandler`、`DeadLetterPublishingRecoverer` 的默认目标主题（`<topic>-dlt`）、默认 `FixedBackOff(0, 9)`、
  "Boot 里加个 Bean 就会自动装上"这句话的出处
- [Spring Boot 3.5 — Messaging / Kafka](https://docs.spring.io/spring-boot/3.5/reference/messaging/kafka.html)：
  `spring.kafka.*` 全部属性、`KafkaAdmin` 与 `NewTopic`
- [Apache Kafka 3.9 — Docker](https://kafka.apache.org/39/getting-started/docker/) 与
  [官方单节点 compose 示例](https://github.com/apache/kafka/blob/3.9/docker/examples/docker-compose-files/single-node/plaintext/docker-compose.yml)：
  `KAFKA_*` 环境变量命名规则、KRaft 必需项、`CLUSTER_ID` 非必填、`KAFKA_LOG_DIRS` 默认值
- 本项目 `docs/course-grab-design.md`：抢课的不变量与"权威在 DB、MQ 只限速"的口径 —— 迁移不改变这些结论
