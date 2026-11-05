# 抢课接口设计方案（Redis 预扣 + MQ 异步落库）

> 状态：**设计待确认，未写实现代码**。
> 范围：一个接口 `POST /api/course/grab?courseId=` + 两张表 + 一个消费者 + 一个 k6 正确性脚本。
> 明确不做：课程列表、我的课表、退课、候补、开放时间控制、分布式事务、Seata/TCC、改现有接口。
> 口径：本文所有数字都是**设计参数**，不是实测结果；实测数字等到压测后用原始输出填，测不出来就写“未测”。

---

## 0. 一页纸

```text
同步段：JWT uid → courseId → Lua(查重 + 查余量 + DECR + SADD) → 发 MQ → 200 已受理
异步段：MQ → 消费者 INSERT IGNORE db_course_enroll → ack
```

三个数：

| 数 | 在哪 | 含义 |
|---|---|---|
| `capacity` | MySQL `db_course` | 名额上限，活动期间不改 |
| `stock` | Redis `course:stock:{id}` | 还能抢几个（预扣余量） |
| `COUNT(*)` | MySQL `db_course_enroll` | 实际占位人数 |

稳态关系：`stock == capacity - COUNT(enroll) - 在途消息数`；队列排空后，
`stock == capacity - COUNT(enroll)`。

**权威**：`db_course_enroll` 是“谁选上了”的唯一权威；Redis 是可重建的预扣视图。

> 另外：**MQ 不会减少总写入量**，只是把 `INSERT` 挪出请求路径并限速。
> 消息总数不变，DB 的写次数就不变；收益是“请求线程不等 DB”，不是“写得少”。

> ⚠️ 本轮**不做**数据库侧容量校验（不做 `enrolled_count < capacity` 的条件更新），
> 所以“不超卖”的唯一执行者是 Redis Lua，它的正确性依赖两件事：
> **预热算得对**，以及 **Redis 丢数据时先停抢、排空、重建**。
> 这是有意的范围取舍，残余风险和升级方案写在 §4.5。

### 本轮做 / 不做

| 做 | 不做 |
|---|---|
| 两张表（课程、选课关系） | 课程列表 / 课表 / 退课 / 候补 |
| 一个 `grab` 接口 + Lua 预扣 | 结果查询接口（`accepted` 之后只能查库） |
| MQ 异步落库 + 唯一索引幂等 | 分布式事务 / outbox / Seata |
| 消费者有限重试 + 死信队列 | 自动补偿对账任务（先手工） |
| 一个 k6 正确性脚本 | 性能优化对比（没有“改前”基线可比） |

---

## 1. 数据模型

### 1.1 DDL

```sql
CREATE TABLE IF NOT EXISTS db_course (
    id         INT          NOT NULL AUTO_INCREMENT,
    name       VARCHAR(128) NOT NULL,
    capacity   INT          NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_course_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程';

CREATE TABLE IF NOT EXISTS db_course_enroll (
    uid        INT      NOT NULL,
    course_id  INT      NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 复合主键就是那条唯一索引：一个人一门课只能有一行
    PRIMARY KEY (uid, course_id),
    -- 查“这门课谁选了”、COUNT、对账都走它
    KEY idx_course_uid (course_id, uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选课记录（权威关系）';
```

几个决定：

- **复合主键而不是自增 id + 唯一索引**：和 `db_topic_like` 一致。`INSERT IGNORE`
  撞的就是它，返回 0 就是“重复消息/已经选过”。自增 id 只会让索引更大，没有收益。
- **没有 `enrolled_count`**：本轮没有列表/课表接口，不需要展示；预热和对账用
  `COUNT(*)` 就够（一门课几百到几千行，主键/索引覆盖）。加冗余计数就必须同时加
  “谁维护它”的规则，而本轮没有条件更新（见 §4.5）。
- **没有外键**：全项目一致。删课程不级联，靠清理 SQL。
- **`capacity` 活动期间不可变**：变容量会让“预扣”和“重建”都失去参照。

### 1.2 迁移策略

这两张表**尚未提交、未上线**（上一轮骨架里叫 `db_course_selection`，在本地测试库建过）。
实现时直接改 `schema.sql` 并 `DROP TABLE db_course, db_course_selection;` 重建，
不需要 `db/migration` 的 ALTER 脚本。服务器上还没有这两张表。

> 沿用项目约定：**新表放 `schema.sql`（幂等，每次启动重放）；改旧表才放 `db/migration/`**。

### 1.3 测试数据与清理

- 课程名固定前缀 `[perftest]`，压测课程建议 `capacity=100`；
- 用户用 500 个测试 uid（token 不要求真实账号，`db_course_enroll` 没有外键）；
- 清理一条链：先删 enroll，再删课程，再删 Redis 键（顺序反了会留下孤儿行）。

---

## 2. Redis 设计

| 键 | 类型 | 内容 | TTL | 作用 |
|---|---|---|---|---|
| `course:stock:{id}` | String | 剩余可抢数 | 无 | **闸门**；键不存在 = 未就绪 |
| `course:chosen:{id}` | Set | 已受理的 uid | 无 | 请求级去重，防同一人重复扣 |

- **不设 TTL**：TTL 到期等于闸门静默失效（要么按 0 卖完、要么按未就绪 503），
  宁可显式 `DEL`。清理放在活动结束/压测收尾。
- **`chosen` 用 Set 不用 Bitmap**：需要 `SISMEMBER` + `SADD` 原子判断，Set 最直接；
  内存是 O(已受理人数)，一门课量级可接受。
- **没有结果键**：本轮不做结果查询接口，消费者只落库 + 打日志/指标；
  等加查询接口时再加 `course:result:{id}:{uid}`（带 TTL）。

### 2.1 抢课脚本（核心）

```lua
-- KEYS[1]=stock, KEYS[2]=chosen, ARGV[1]=uid
local stock = redis.call('GET', KEYS[1])
if not stock then return -2 end                      -- 未预热/已停抢
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1
then return -1 end                                   -- 这个人已经受理过
if tonumber(stock) <= 0 then return 0 end            -- 已抢完
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1                                             -- 受理成功
```

| 返回 | 语义 | 接口 |
|---|---|---|
| `1` | 扣减成功 | 200 已受理，继续发 MQ |
| `0` | 余量 0 | 400 已抢完 |
| `-1` | 同一 uid 已受理 | 409 已参与 |
| `-2` | `stock` 键不存在 | 503 未就绪 |

要点：

- **查重、查余量、扣减、记录必须在同一个脚本**。拆成 `SISMEMBER` → `GET` → `DECR`
  三次调用，中间就是并发窗口：同一用户能扣两次、余量能被扣成负数。
- **不要先 `DECR` 再判断**。扣到负数再 `INCR` 回来，中间别的请求会看到负数，
  而且“回滚”本身也可能失败。
- **`-2` 必须单列**。“键不存在”当成 0 会让预热失败变成全站售罄；当成无限会直接超卖。

### 2.2 补偿脚本

MQ 发失败时要把“扣掉的这一个”还回去。`INCR` 和 `SREM` 也必须在一个脚本里：

```lua
-- KEYS[1]=stock, KEYS[2]=chosen, ARGV[1]=uid
if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
    redis.call('INCR', KEYS[1])
end
return 1
```

- **先 `SREM`、只有真的移除了才 `INCR`**。否则一次补偿失败后重试，会把库存加两次。
- 补偿脚本本身失败（Redis 不可达）→ 记 ERROR + 指标；库存状态未知，按 §2.3 重建。

### 2.3 预热 / 重建（顺序就是正确性）

```text
1. 停抢：DEL course:stock:{id}        → 所有 grab 立刻 503（fail-closed）
2. 等队列排空：MQ messages = 0，消费者 ack 完
3. 算数：n = SELECT COUNT(*) FROM db_course_enroll WHERE course_id = ?
4. 重建集合：DEL chosen; SADD chosen <所有 uid...>（分批，别一次塞十万个）
5. 最后写闸门：SET stock = capacity - n
6. 校验：stock >= 0 且 SCARD(chosen) == n
```

为什么要这个顺序：

- **`stock` 最后写**：写之前所有请求都被 `-2` 挡住。反过来先写 `stock`、再补 `chosen`，
  这中间到达的重复请求会再扣一次库存。
- **必须先排空队列**：在途消息还没落库，`COUNT` 偏小 → `stock` 偏大。
  例：capacity=100，已落库 50，队列里还有 50 条。此时重建 `stock=50`，
  新请求把这 50 抢走，旧消息再落 50 条 → 150 条。**这是本方案最危险的一处，必须靠流程挡住。**

启动时：只在 `stock` 键缺失、且队列 ready 消息为 0 时预热（避免重启把进行中的库存重置）；
条件不满足就跳过并打 WARN，接口保持 503。恢复流程仍按上面 5 步手工执行。

---

## 3. 时序图

### 3.1 成功路径

```text
客户端             Controller            Redis              RabbitMQ          消费者            MySQL
  |                   |                    |                    |                 |                |
  | POST /grab?cid=1  |                    |                    |                 |                |
  |------------------>|                    |                    |                 |                |
  |                   | JWT -> uid         |                    |                 |                |
  |                   | EVALSHA grab       |                    |                 |                |
  |                   |------------------->|                    |                 |                |
  |                   | 1 = 受理           |                    |                 |                |
  |                   |<-------------------|                    |                 |                |
  |                   | convertAndSend(cid, uid)                |                 |                |
  |                   |---------------------------------------->|                 |                |
  | 200 已受理        |                    |                    | deliver         |                |
  |<------------------|                    |                    |---------------->|                |
  |                   |                    |                    |                 | INSERT IGNORE  |
  |                   |                    |                    |                 |--------------->|
  |                   |                    |                    |                 | 1 / 0          |
  |                   |                    |                    |                 |<---------------|
  |                   |                    |                    | ack             |                |
  |                   |                    |                    |<----------------|                |
```

### 3.2 MQ 发送失败（Redis 已扣）

```text
Controller -> Redis: EVALSHA grab -> 1（已扣）
Controller -> RabbitMQ: convertAndSend 抛 AmqpException
Controller -> Redis: EVALSHA compensate（SREM + INCR）
   ├─ 成功 → 返回 503「系统繁忙，请重试」；名额复原
   └─ 失败 → ERROR + 指标；名额可能少 1（少卖）
              → 停抢 → 等队列空 → 按 §2.3 重建
```

这里有个必须说清的口径：**没开 publisher confirm 时，“发送没抛异常”不等于 broker 收到了**。
v1 只处理“抛异常”这一种；更隐蔽的丢失（连接半开、broker 内存压力）只能靠对账/重建兜底。
升级方案见 §8。

### 3.3 消费失败 / 重投

```text
deliver -> 消费者:
  INSERT IGNORE db_course_enroll
    ├─ 影响 1（首次）→ ack，结束
    └─ 影响 0（重复）→ 直接 ack，不做事
  DB 异常/超时 → 抛异常
    → Spring retry（有限次，指数退避）
    → 仍失败 → reject（default-requeue-rejected=false）→ DLQ
```

重投为什么安全：

- **DB 已提交、ack 丢了** → 重投时 `INSERT IGNORE` 影响 0 → 无副作用；
- **DB 没提交/回滚**（v2 加事务后）→ 重投时重新插入，最终仍只有一行（唯一索引）；
- **业务失败（重复）不能重试**：它不会因为重试变好，直接 ack。

### 3.4 Redis 挂

```text
A. 抢课时 Redis 不可达
   EVALSHA 抛连接异常 → 503；不发 MQ、不碰 MySQL。

B. 扣减成功、发 MQ 前 Redis 挂
   补偿脚本也执行不了；若 Redis 数据还在，恢复后按 §2.3 重建/补偿；
   若 Redis 丢数据（无持久化重启），stock/chosen 都没了：
   重建前 stock 键不存在 → 所有 grab 503，不会继续卖。

C. 消费者侧 Redis 操作
   v1 消费者不写 Redis，无影响。

恢复（人工）：
   1) 确认 grab 全部 503
   2) 等 MQ messages=0、ack 完
   3) 从 DB 重建 chosen + stock（§2.3）
```

**不降级打 DB**：Redis 不可用时直接写库会退化成“行锁 + 无流量控制”，
在抢课峰值下会把 MySQL 打穿，而且 v1 的 DB 没有容量校验，直接写会超卖。
宁可 503。

---

## 4. 四个必答题

### 4.1 超卖怎么防

1. **Lua 原子**：查重、查余量、`DECR`、`SADD` 在 Redis 单线程里一次执行，
   不存在“两个请求都看到余量 1”的窗口。
2. **预热算对**：`stock = capacity - COUNT(enroll)`，且预热时队列必须为空（§2.3）。
3. **丢数据就停售**：`stock` 键不存在返回 `-2`，接口 503；绝不“没库存就当无限”。
4. **验证**：压测后 `COUNT(*) <= capacity` 且 `COUNT(DISTINCT uid) == COUNT(*)`。

残余风险：Redis 数据被手工改错、或预热时队列没排空，DB 不会兜底（本轮无容量条件更新），
**可能超卖**。这是 §4.5 要你拍板的取舍。

### 4.2 重复怎么办

| 层 | 手段 | 挡什么 |
|---|---|---|
| 请求 | Lua `SISMEMBER` + `SADD` | 同一 uid 并发/连点，只扣一次 |
| 落库 | 复合主键 `(uid, course_id)` + `INSERT IGNORE` | 消息重复投递、重试、手工重放 |
| 失败 | 补偿脚本 `SREM` | 发 MQ 失败后允许用户重新抢 |

边界：**`chosen` 丢了但 `stock` 还在**，重复请求会再扣一次。所以重建/预热必须同时恢复两者，
不能只补 `stock`。

### 4.3 Redis 挂了库存怎么办

- 抢课请求：**fail-closed**，503，不写库、不发 MQ。
- 恢复：确认可用 → 停抢（`DEL stock`）→ **等队列排空** → 从 DB 重算 `COUNT` →
  重建 `chosen` → 最后 `SET stock` → 恢复。重建期间新请求全部 503。
- 持久化：开 AOF（`appendonly yes`，`everysec`）能把“丢数据”窗口缩到约 1 秒，
  但**不能替代重建流程**——丢 1 秒的预扣也会让 `stock` 和 DB 对不上。
- 不要“重算一下继续卖”：`stock` 能算，但在途消息和 `chosen` 的丢失会让重算结果偏大。

### 4.4 谁说了算（缓存扣减 vs 数据库）

**数据库是权威，Redis 是投影。**

- `db_course_enroll`：谁真的选上了，一条都不能丢；
- `db_course.capacity`：名额上限的唯一来源；
- Redis `stock`：`capacity - 已占位 - 在途` 的**预扣视图**，可丢、可重建；
- 冲突处理：**以 DB 重算为准，重建 Redis；绝不用 Redis 覆盖 DB**。

一句话：**预扣只负责“挡请求”，不负责“记事实”**。`stock` 和 DB 对不上时，
先停售，再从 DB 重建，而不是反过来修 DB。

### 4.5 要不要加数据库兜底（需要你拍板）

方案 B：`db_course` 加 `enrolled_count`，消费者事务里执行

```sql
UPDATE db_course SET enrolled_count = enrolled_count + 1
WHERE id = ? AND enrolled_count < capacity;
```

- 影响 1：占位成功；影响 0：DB 已满（Redis 扣多了）→ 回滚刚插的 enroll，
  补偿 Redis（`SREM` + `INCR`），ack。
- 收益：**即使 Redis 预热错/丢数据，DB 也拦得住超卖**；恢复流程不必严格排空。
- 代价：每条消息多一次 UPDATE（多一次 JDBC 往返）、多一条补偿路径、
  压测对账多一个状态（“受理了但 DB 判满”），范围变大。
- ⚠️ 实现坑：`@Transactional` **只认异常**，`return` 不会回滚（项目踩过）。
  方案 B 里“DB 已满”必须抛异常或手动删掉刚插的行，不能只 `return` 一个结果。
- 本轮建议：**不做**。按任务书“只做一个接口/消费者靠唯一索引”，先把 Redis 闸门练透；
  如果要求“任何情况下都不超卖”，那就必须加，我可以按方案 B 改。

---

## 5. 接口契约

```http
POST /api/course/grab?courseId=1
Authorization: Bearer <JWT>
```

- `uid` **只从 JWT 取**（`@RequestAttribute("id")`），请求里没有 uid；
- 不查 MySQL：课程存在性由“`stock` 键是否存在”表达；
- 成功只代表**已受理**，不代表已落库（本轮没有结果查询接口）。

| HTTP | body code | 含义 |
|---|---|---|
| 200 | 200 | 已受理（`data: null` 即可） |
| 200 | 400 | 已抢完（Lua 返回 0） |
| 200 | 409 | 你已参与（Lua 返回 -1） |
| 200 | 503 | 未就绪 / Redis 不可用（Lua 返回 -2 或异常） |
| 200 | 401 | 未登录（Security 现有逻辑） |

> 全站约定：业务错误放在 body 的 `code`，HTTP 200；只有鉴权/限流才改 HTTP 状态。
> 409 是新用的码，前端只 `code === 200` 判断成功，不受影响。

**不改任何现有接口**：只新增 `CourseController` 的一个方法；`SecurityConfiguration`
的 `anyRequest().authenticated()` 已经覆盖 `/api/course/**`，不需要动。

---

## 6. 实现落点与 SQL 写法

### 6.1 文件

| 文件 | 动作 |
|---|---|
| `db/schema.sql` | 把上一轮的 `db_course_selection` 改成 `db_course_enroll`；去掉 `selected_count` |
| `entity/dto/Course.java` | 改：只留 id/name/capacity/createdAt |
| `entity/dto/CourseEnroll.java` | 新（替代 `CourseSelection`），复合主键，无 `@TableId` |
| `mapper/CourseMapper.java` | 只留 `BaseMapper`（grab 不查库） |
| `mapper/CourseEnrollMapper.java` | 一条 `@Insert("INSERT IGNORE ...")` |
| `service/CourseGrabService` + impl | Lua 预扣 + 发 MQ + 发送失败补偿 |
| `controller/CourseController.java` | 只留 `grab`（删掉上一轮的 list/select/result） |
| `listener/CourseGrabListener.java` | `INSERT IGNORE` + 有限重试/死信 |
| `config/CourseRabbitConfiguration.java` | 队列改名 `course.grab` / `course.grab.dlq` |
| `utils/Const.java` | 键和队列名常量 |
| `application.yaml` | listener retry/prefetch（见 §6.3） |
| `perf-test/k6/course-grab.js` | 压测脚本（§7） |

> 上一轮骨架是另一套命名（`db_course_selection`、`/api/course/select|list|result`），
> 与本次规格冲突。实现时按本文件收缩；**要不要先删掉旧骨架，等你确认**。

### 6.2 SQL 写法：为什么不用 Lambda Wrapper

**结论：`INSERT IGNORE` 用注解 SQL；如果加方案 B 的条件 UPDATE，也用注解 SQL。**
Wrapper 不是不能用，是这里不划算。

**① 幂等插入，Wrapper 表达不了 `IGNORE`**

- `saveOrUpdate` 依赖单一 `@TableId`（这张表是复合主键，直接不适用）；就算能用，
  也是先查后写：多一次查询，中间还有 TOCTOU；
- `insert` + catch `DuplicateKeyException`：能跑，但“重复消息”是消费者的正常路径，
  靠异常处理既贵又会把日志刷满；`default-requeue-rejected` 还可能把异常放大成重投；
- MyBatis-Plus 没有 `insert ignore` 的 DSL。

项目已有先例，照抄即可：

```java
@Insert("INSERT IGNORE INTO db_course_enroll (uid, course_id) VALUES (#{uid}, #{courseId})")
int insertIgnore(@Param("uid") int uid, @Param("courseId") int courseId);
```

**② 条件更新（方案 B 才需要），Wrapper 能做但有两个坑**

```java
// 能跑，但 capacity 必须已经读到，且不能为 null
int n = courseMapper.update(null, new LambdaUpdateWrapper<Course>()
        .setSql("enrolled_count = enrolled_count + 1")
        .eq(Course::getId, courseId)
        .lt(Course::getEnrolledCount, capacity));
```

- `lt` 是“列 vs 值”。`enrolled_count < capacity` 是“列 vs 列”，Wrapper 只能：
  先查 `capacity` 再传值（多一次查询；capacity 若变更，守卫用旧值），
  或 `.apply("enrolled_count < capacity")`（退回裸 SQL，等于没避开手写）；
- `setSql` 没有占位符，是原始 SQL。这里 `+1` 是常量，安全；但项目在
  `TopicMapper.addLikeCount` 里明确避过 `setSql`，风格不一致；
- `capacity` 为 null 时 `enrolled_count < null` 恒假，**静默 0 行**，看起来像“名额满”；
- 一条 `@Update` 能把 WHERE 和 SET 放在一行里 review，链式调用里两者是分开的。

```java
@Update("UPDATE db_course SET enrolled_count = enrolled_count + 1 " +
        "WHERE id = #{id} AND enrolled_count < capacity")
int incrementIfAvailable(@Param("id") int id);
```

**别把“用不用 Wrapper”当成正确性问题**：两种写法生成的 SQL 语义一样，
决定不超卖的是“判断+写入在同一条语句”。Wrapper 的问题是往返次数、踩坑面、可读性。
项目之前实测过每条 SQL 的固定开销（详情页口径约 1.69ms/条），
消费者路径没单独测过，不能直接把那个数搬过来，但“多一条 SELECT 就多一次往返”是确定的。

### 6.3 消费者配置（有限重试 + 死信）

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        prefetch: 1          # 先小；消息处理是单条 INSERT，别一次预取一堆
        concurrency: 1       # 2 vCPU，先不加并发
        default-requeue-rejected: false   # 重试耗尽后进 DLQ，不无限重投
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 500ms
          multiplier: 2
```

- 不配的话：抛异常 → 立刻重投 → 再抛 → 再投（邮件那里实测 5 秒 16 次）；
- `default-requeue-rejected=false` 必须配，否则重试耗尽仍然会 requeue；
- 消费者对“重复”正常返回（ack），只有 DB 瞬时异常才抛。
- v1 **不开** publisher confirm（理由与升级路径见 §8）。

---

## 7. 压测方案

### 7.1 数据准备

- 1 门课：`[perftest] 抢课压测课`，`capacity=100`；
- 500 个 uid 的 token（不需要真实账号；`db_course_enroll` 没有外键）；
- token 生成沿用项目脚本：`python3 scripts/gen_tokens.py 504 500 /tmp/perftest/tokens.txt`；
- 压完按 `[perftest]` 前缀清理（课程 → enroll → Redis 键）。

种子与清理（一条链，顺序不能反）：

```sql
-- 造课（capacity 按本轮设计参数；实测前先确认表里没有同名旧课）
INSERT INTO db_course (name, capacity) VALUES ('[perftest] 抢课压测课', 100);

-- 清理：先删关系，再删课程
DELETE e FROM db_course_enroll e
JOIN db_course c ON c.id = e.course_id
WHERE c.name LIKE '[perftest]%';
DELETE FROM db_course WHERE name LIKE '[perftest]%';
```

```bash
# Redis 侧（课程 id 从上一步查到）
DEL course:stock:{id} course:chosen:{id}
```

### 7.2 怎么造并发

用 k6 的 **shared-iterations**，不是“每个 VU 循环打”：

```js
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// 500 个请求 = 500 个不同 uid 各一次，总数可控，账好对
export const options = {
  scenarios: {
    grab: { executor: 'shared-iterations', vus: 200, iterations: 500, maxDuration: '60s' },
  },
};

const accepted  = new Counter('accepted');
const soldOut   = new Counter('sold_out');
const duplicate = new Counter('duplicate');
const notReady  = new Counter('not_ready');

const tokens = open(__ENV.TOKENS).split('\n').filter(Boolean);

export default function () {
  // 全局唯一序号 0..iterations-1，保证 500 次请求用 500 个不同 uid
  const token = tokens[exec.scenario.iterationInTest % tokens.length];
  const res = http.post(
    `${__ENV.BASE}/api/course/grab?courseId=${__ENV.COURSE_ID}`,
    null,
    { headers: { Authorization: `Bearer ${token}` } },
  );

  // 必须是 JSON：项目踩过“URL 拼错打到 nginx SPA 回退，200 + index.html”
  const isJson = check(res, {
    'content-type is json': (r) => (r.headers['Content-Type'] || '').includes('application/json'),
  });
  if (!isJson) return;

  const code = res.json('code');
  if (code === 200) accepted.add(1);
  else if (code === 400) soldOut.add(1);
  else if (code === 409) duplicate.add(1);
  else if (code === 503) notReady.add(1);
}
```

- 每个请求带自己的 token，`POST /api/course/grab?courseId=<ID>`；
- 运行时比通用命令多传一个 `-e COURSE_ID=<课程 id>`（其余 `-e BASE/VUS/DURATION/TOKENS` 照旧）；
- **断言响应是 JSON**（项目踩过：URL 拼错打到 nginx SPA 回退，返回 `index.html`，
  HTTP 200，压测报告里看起来“全成功”）；
- 自定义 Counter 分别记 `accepted / sold_out / duplicate / not_ready / other`。

### 7.3 验证什么、通过标准

压完先等队列排空（`rabbitmqctl list_queues` 的 messages 归 0、消费端 ack 完），再查：

```sql
SELECT COUNT(*) AS rows_, COUNT(DISTINCT uid) AS uids
FROM db_course_enroll WHERE course_id = ?;
SELECT capacity FROM db_course WHERE id = ?;
```

```bash
GET  course:stock:{id}       # 剩余
SCARD course:chosen:{id}     # 已受理人数
```

| # | 判据 | 通过条件 |
|---|---|---|
| 1 | 不超卖 | `rows_ <= capacity` |
| 2 | 受理数 == 落库数 | `rows_ == accepted`（队列排空后） |
| 3 | 不重复 | `rows_ == uids`（复合主键保证，仍要查） |
| 4 | Redis 与 DB 一致 | `stock == capacity - rows_`，`SCARD == rows_` |
| 5 | 无未处理失败 | DLQ messages = 0 |
| 6 | 响应可解释 | `accepted + sold_out + duplicate + not_ready == 500` |

**测试 B：同一用户并发重复**

- 1 个 token，100 个并发请求（shared-iterations，vus=100，iterations=100）；
- 期望：`accepted=1`、`duplicate=99`（capacity 足够大时不会 sold_out）；
- DB 1 行；`stock` 只减 1；`SCARD=1`。

**测试 C（可选）：重放**

- 手工把一条消息重新投递/重放，确认 `INSERT IGNORE` 影响 0、DB 行数不变。

### 7.4 性能数字（可选，别和正确性混在一起）

- 只有需要“优化前后对比”时才测；本轮没有“改前”基线，**不要编造提升比例**；
- 如果测：100 VU 以内（项目实测 200 VU 时 k6 与后端抢同一 2 核，改进被淹没）；
- 前 90 秒 @10 VU 预热；测前关慢查询日志和 MyBatis SQL 日志；
- k6 与后端同机，绝对吞吐只能算“同机竞争下的表现”，报告里必须写这句；
- 如果查 `performance_schema` 反推 SQL 条数，时间单位是**皮秒**，除 `1e9` 才是毫秒。

### 7.5 结果记录（跑完再填，测不出来写“未测”）

| 场景 | 请求 | accepted | sold_out | duplicate | DB 行数 | 去重 uid | stock | DLQ | 结论 |
|---|---|---|---|---|---|---|---|---|---|
| A 500 用户抢 100 | 500 | 待测 | 待测 | 待测 | 待测 | 待测 | 待测 | 待测 | 待测 |
| B 同一用户 ×100 | 100 | 待测 | — | 待测 | 待测 | 待测 | 待测 | 待测 | 待测 |

---

## 8. 未验证 / 风险清单

1. **没开 publisher confirm**：`convertAndSend` 不抛异常 ≠ broker 收到。v1 只补偿异常；
   更隐蔽的丢失会让 `accepted > rows`（少卖），靠对账/重建发现。升级：`publisher-confirm-type=correlated`
   + `CorrelationData` 回调，nack 时补偿；这会引入“异步补偿”的复杂度，本轮先不做。
2. **没有结果查询接口**：用户只知道“已受理”，最终是否选上要查库。这是范围限定，不是遗漏。
3. **DB 不校验容量**：Redis 数据错/预热漏会超卖（§4.5）。
4. **恢复流程是手工的**：停抢、排空、重建，每一步的顺序都不能换；没有自动化对账任务。
5. **DLQ 只声明，没有消费者**：先靠 `rabbitmqctl` 看积压；要不要做重放任务，后续再说。
6. **启动预热与消费者启动有竞态**：靠“只补缺失键 + 队列为空”降低影响，但没做并发测试。
7. **Redis 持久化现状未确认**：需要看服务器 `appendonly`；不开 AOF 则重启丢全部预扣。
8. **k6 脚本还没跑过**：`iterationInTest` 的用法、token 文件格式、nginx 转发后的限流
   （本机回环会跳过 `FlowLimitFilter`）都要在第一次跑时验证。
9. **压测数字全部待测**：本文没有填任何吞吐/延迟数字。

---

## 9. 待确认

1. **命名**：按本规格用 `db_course_enroll` + `/api/course/grab`，并删掉上一轮骨架的
   `db_course_selection` + `select/list/result`？还是保留旧骨架、只加新接口？
2. **DB 兜底**：本轮不加（推荐，范围最小）；还是加 `enrolled_count` 条件更新（更稳，范围变大）？
3. **MQ 可靠性**：v1 只补偿发送异常（推荐）；还是同时上 publisher confirm？
4. **压测参数**：capacity=100 / 500 用户 / 200 VU shared-iterations，是否按这个来？
5. **预热方式**：启动时“键缺失且队列空才预热” + 恢复手工流程，能否接受？
6. **旧文档** `docs/course-selection.md`：删除、还是留作上一轮记录？

---

## 10. 分步实施（每步停下等确认）

| 步 | 内容 | 怎么验证 |
|---|---|---|
| 1 | 本文档 | 你确认 §9 |
| 2 | 表 + 实体/Mapper + 预热 + 清理 SQL | 起 8081，`stock` 键正确；grab 未实现时返回 501/404 |
| 3 | Lua + service + controller + MQ 生产端 | curl 打出 200 / 409 / 400；`stock` 和 `SCARD` 对得上 |
| 4 | 消费者 + 重试 + DLQ | 手工重投两次，DB 仍一行；停 MySQL 看 DLQ |
| 5 | k6 测试 A/B + 清理 | §7.3 六条判据全过；记录原始输出 |
| 6 | （可选）性能数字 | 只在需要对比时做，按 §7.4 口径 |

部署顺序（沿用项目已知坑）：

```bash
cd /root/campus-forum
docker compose build backend
docker compose up -d --no-deps backend
docker compose restart frontend      # 不能省：nginx 缓存旧后端 IP → 504 且日志空白
# 等 healthy，再预热，再跑 smoke
```
