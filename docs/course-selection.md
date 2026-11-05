# 抢课（Redis + MQ 高并发练习）— 设计文档

> ⚠️ **已被取代**：这是上一轮按旧命名（`db_course_selection`、`/api/course/select`）
> 写的草图。正式设计见 [course-grab-design.md](course-grab-design.md)
> （`db_course_enroll`、`/api/course/grab`）。实现前会合并或删除本文件。
>
> 状态：**骨架已就位，三段核心待写**。本文件是“为什么这么设计”的说明书，
> 代码里的 `TODO` 是填空位置。压测与前端不在本轮范围。
>
> 关联文件：`schema.sql`（表）、`db/init/011-seed-course.sql`（种子课）、
> `CourseMapper` / `CourseSelectionMapper`（两段核心 SQL）、
> `CourseServiceImpl`（Lua 预扣 + 发 MQ）、`CourseSelectListener`（幂等消费）。

---

## 1. 目标与不变量

1000 个名额、几万人同时点“抢课”。先定三条不变量，后面所有代码都为它们服务：

| # | 不变量 | 违反的后果 |
|---|---|---|
| 1 | **绝不超卖**：`selected_count <= capacity` 恒成立 | 教室坐不下，要人工劝退，是事故 |
| 2 | **同一人同一课最多成功一次** | 重复占位、名额浪费，用户也困惑 |
| 3 | **最终一致**：可以少卖，不可以错卖 | 少卖 = 用户重试或人工补；错卖 = 第 1 条被破坏 |

“少卖可接受”是这套方案能成立的前提：任何一步失败，宁可少一个名额，
也不允许凭空多卖。所有补偿路径都是朝这个方向倒的。

---

## 2. 为什么是三段式

### 2.1 朴素写法为什么必错

```text
余量 = SELECT capacity - selected_count FROM db_course WHERE id = ?
if (余量 > 0) INSERT INTO db_course_selection ...
UPDATE db_course SET selected_count = selected_count + 1
```

InnoDB 默认 REPEATABLE READ 下，普通 `SELECT` 是 **MVCC 快照读，不加锁**。
两个请求可以同时读到“余量 = 1”，然后都去插、都去 +1。
这是本项目被咬过一次的 TOCTOU（点赞并发 20 次：天真版 1 个 200 + 19 个 500）。

**结论：判断和写入必须在同一条语句、或同一个原子边界内。**

### 2.2 数据库兜底的写法对比

| 写法 | 正确性 | 代价 |
|---|---|---|
| `SELECT ... FOR UPDATE` + 更新 | 对 | 并发变串行；锁持有到事务提交；连接池被占满 |
| 版本号 CAS + 重试 | 对 | 同一行上所有人集体失败、集体重试，重试风暴比锁更糟 |
| **`UPDATE ... WHERE selected_count < capacity`** | 对 | 所有抢同一门课的人仍排队在一行锁上，但**每次只排一个极短的写事务** |

条件 UPDATE 是数据库层的最后防线：

```sql
UPDATE db_course SET selected_count = selected_count + 1
WHERE id = ? AND selected_count < capacity;
-- 影响行数 1 = 占位成功；0 = 名额已满（不是异常）
```

它不能单独扛流量：一行锁串行，吞吐上限就是这一行的写速率。
所以还要 Redis 在前面挡掉绝大多数请求。

### 2.3 为什么还要 Redis

Redis 是单线程执行命令的，一段 Lua 在执行期间**不会插入别的命令**。
于是“查重 + 查余量 + 扣减”三件事可以放进一个脚本里，成为一个原子操作，
一次 RTT 完成。这正好补上 2.1 的缺口。

为什么不是别的方案：

- **`WATCH/MULTI`**：乐观事务，并发下大量 EXEC 失败重试，吞吐反而更差。
- **分布式锁（Redisson）**：把并发变成串行，和抢课目标相反；还多一套锁续期/脑裂问题。
- **裸 `DECR`**：挡不住同一用户重复请求（同一个人能把库存扣光），也无法在售罄时
  回滚已扣的值。

---

## 3. 数据模型

见 `schema.sql` 末尾两张表。要点：

- `db_course_selection` 复合主键 `(uid, course_id)`：不变量 2 由数据库保证，
  同时是 MQ 幂等消费的支点（`INSERT IGNORE` 影响 0 行 = 重复消息）。
- `db_course.selected_count` 只能由条件 UPDATE 修改。
  **不要**用“Redis 增量 + 定时回写”那套点赞的做法——那是给“可重算的派生值”用的，
  选课人数不是。
- 没有外键，和项目其余表一致。

种子数据在 `db/init/011-seed-course.sql`（Docker 的 db-init 会按文件名顺序执行）。
本地手工灌：`mysql -uroot --default-character-set=utf8mb4 test < db/init/011-seed-course.sql`。

---

## 4. Redis 侧

| 键 | 类型 | 含义 |
|---|---|---|
| `course:stock:{courseId}` | string | 还能抢几个（预扣库存） |
| `course:chosen:{courseId}` | set | 已抢到的人（uid），Lua 里挡重复 |
| `course:result:{courseId}:{uid}` | string + TTL | 异步结果 "1"/"0"，前端轮询读它 |

### 4.1 预扣脚本（`CourseServiceImpl.SELECT_SCRIPT`）

```lua
-- KEYS[1]=stock  KEYS[2]=chosen  ARGV[1]=uid
local stock = redis.call('GET', KEYS[1])
if not stock then return -2 end                             -- 未预热
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -1 end  -- 重复
if tonumber(stock) <= 0 then return 0 end                    -- 售罄
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return tonumber(stock) - 1
```

| 返回 | 语义 | 接口状态 |
|---|---|---|
| `-2` | 库存键不存在（没预热） | `NOT_STARTED` |
| `-1` | 这人已经在 chosen 里 | `DUPLICATE` |
| `0` | 售罄 | `SOLD_OUT` |
| `>=0` | 扣减成功，值是剩余 | `QUEUED`（继续发 MQ） |

### 4.2 四个坑

1. **键不存在 ≠ 0**。当成 0，预热失败就全站售罄；当成无限，直接超卖。
   所以单列一个 `-2`。
2. **去重和扣减必须在同一个脚本**。拆成两次调用，中间就是 TOCTOU，
   同一用户并发两次都能过。
3. **预热要同时恢复 stock 和 chosen**。只 SET 一个数字、不从
   `db_course_selection` 重建集合，重复请求会再扣一次库存——库存慢慢漏光。
4. **活动进行中不要覆盖 stock**。预热/清理由管理操作在活动开始前完成；
   代码里的“懒预热”（键不存在就补）在并发下会把刚扣的库存重置。

---

## 5. MQ 侧

- 主队列 `course.select`（durable，死信到 `course.select.dlq`，默认交换机）。
- 消息体 `{uid, courseId}`；幂等键 = 复合主键，不需要额外 requestId。
- 生产端：Lua 扣减成功后 `convertAndSend`，接口返回 `QUEUED`。
- 消费端三步（见 `CourseSelectListener`）：
  1. `INSERT IGNORE db_course_selection`，影响 0 行 → 重复消息，直接 ack；
  2. `UPDATE db_course ... WHERE selected_count < capacity`，影响 0 行 → 账本已满，
     回滚并回补 Redis；
  3. 写 `course:result:{id}:{uid}`。

### 5.1 异步不是“更快”

它换来的是：DB 每单位时间只处理“消费者数 × 消费速率”那么多次写，
把尖峰摊成可控的队列长度。代价是用户要等、结果要轮询、失败要补偿。
**如果接口等落库再返回，MQ 就白加了**——延迟又接回 HTTP 线程了。

### 5.2 失败矩阵

| 窗口 | 后果 | 处理 | 可接受？ |
|---|---|---|---|
| Redis 扣了，发 MQ 抛异常 | 少卖 1 个 | 同步补偿：`INCR` stock + `SREM` chosen，返回失败 | 是（补偿后无损） |
| MQ 收了，消费者挂 | 用户一直 `QUEUED` | durable 队列 + 重启后重投；结果键不写 | 暂时是，恢复后继续 |
| 消费者插了关系，UPDATE 影响 0 行 | 超卖被 DB 拦下 | 事务回滚 + 回补 Redis + 写结果 "0" | 是（DB 是最后防线） |
| 消息重复投递 | 多占名额 | `INSERT IGNORE` 影响 0 行 → 不再 +1 | 是 |
| 补偿代码写在事务里 | 事务回滚后补偿照样执行 → 库存凭空变多 | 补偿放到事务结果确定之后 | **不可接受** |
| Redis 重启丢数据 | stock/chosen 没了 | 停抢 → 对账 → 从 DB 重建两个键 → 再开 | 需人工介入 |
| 无限重投坏消息 | 日志/CPU 打满（邮件实测 16 次/5 秒） | 有限重试或直接进死信 | 是 |

### 5.3 当前消息可靠性缺口（明确记录）

- `RabbitTemplate` 没开 **publisher confirm**：消息可能没到 broker，生产端不知道。
- 监听器是 **AUTO ack**：方法返回即确认，处理到一半崩溃会丢消息。
- 没配 prefetch/concurrency：默认单消费者、prefetch 250。

这三点在本轮骨架里**故意保持原样**，等第 4 阶段再补：
confirm callback + `acknowledge-mode: manual` + `prefetch`/`concurrency`。

---

## 6. 接口与状态机

| 接口 | 说明 |
|---|---|
| `GET /api/course/list` | 课程列表（已实现，remaining 暂由 DB 算） |
| `POST /api/course/select` `{courseId}` | 抢课，返回 `QUEUED/SUCCESS/SOLD_OUT/DUPLICATE/NOT_STARTED` |
| `GET /api/course/result?courseId=` | 轮询结果 |

```text
         ┌────────────┐   Lua 成功   ┌────────┐   消费成功   ┌──────────┐
请求 ───▶│ Redis 预扣 │─────────────▶│ QUEUED │─────────────▶│ SUCCESS  │
         └────────────┘              └────────┘              └──────────┘
              │ -2        │ -1         │ 0
              ▼           ▼            ▼
        NOT_STARTED   DUPLICATE    SOLD_OUT
```

- `QUEUED` 不是错误，HTTP 200 + body code 200（项目约定）。
- 前端用**禁用按钮 + 轮询**，不要“多点几次提高成功率”：后端幂等，多点只是白打 Redis。
- `result` 必须是一次 Redis GET；查 DB 会让轮询把刚削掉的峰又打回来。

---

## 7. 对账

三个数在一致性成立时满足：

```text
capacity - selected_count  ==  COUNT(db_course_selection WHERE course_id=? AND status=1)
Redis stock              ==  上面那个数 - （已扣减但尚未落库的消息数）
```

对账任务（第 5 阶段）做两件事：
1. 比对 DB 两个数；不等 → 说明补偿漏了，告警并修 `selected_count`；
2. 活动结束后用 DB 的真实值重建 Redis（或直接删键），防止键残留。

修复 SQL 思路和 `005-repair-like-count.sql` 一样：以关系表为准重算。

---

## 8. 分阶段验收

| 阶段 | 动作 | 判定标准（怎么知道对了） |
|---|---|---|
| 0 | 种子课 + 1000 个 uid 的 token + 压测脚本 | 基线吞吐/P99 有记录，不预设数字 |
| 1 | 朴素版（先查再插） | 压完 `COUNT(*) > capacity`，附送唯一键 500 |
| 2 | 条件 UPDATE 兜底 | `COUNT(*) == capacity`，无 500；TPS 明显低于 3 |
| 3 | Redis Lua 预扣（先同步落库） | 吞吐跳升；同一 uid 压 100 次只成功 1 次；手工改 stock 验证 DB 能拦 |
| 4 | 接 MQ + 死信 + 结果查询 | 杀消费者看积压恢复；重复投递不重复占位 |
| 5 | 对账 + 指标 | 三个数一致；人为破坏一处能被告警抓到 |
| 6 | 前端（可选） | 开抢/倒计时/轮询可用 |

---

## 9. 压测注意（这个项目特有）

1. `FlowLimitFilter` 按 IP 限流 10000 请求/3 秒；但 `flow-limit.skip-loopback=true`，
   本机回环地址直接跳过——**本机压测正好不受影响**。一旦走 nginx/容器，10k 会误伤，
   压测前确认这条开关。
2. 每个虚拟用户必须用**不同 uid**，否则全被 `DUPLICATE` 挡掉，压出来的是假数据。
   `db_course_selection` 没有外键，可以直接签假 uid 的 JWT，不必建 1000 个账号。
3. 先盯 **Tomcat 线程（默认 200）→ HikariCP（默认 10 连接）→ DB 行锁 → Redis RTT**。
   它们很可能比 Redis 先成为瓶颈，别把连接池排队误判成“Redis 慢”。
4. 压测工具：本机只有 `ab`，它没法给每个 VU 换 token。建议 `brew install k6`。

---

## 10. 待写清单（按依赖顺序）

- [ ] `CourseMapper.incrementSelectedIfAvailable` —— 条件 UPDATE（第 2 段）
- [ ] `CourseSelectionMapper.insertIgnore` —— 幂等插入（第 3 段）
- [ ] `CourseServiceImpl.SELECT_SCRIPT` + `select()` —— Lua 预扣 + 发 MQ + 发送失败补偿（第 1 段）
- [ ] `CourseSelectListener.onCourseSelect` —— 幂等消费 + 事务 + 补偿 + 写结果
- [ ] `CourseServiceImpl.result()` —— 结果键优先、DB 兜底、回填 TTL
- [ ] 预热入口（启动/管理接口）—— 同时建 stock 和 chosen
- [ ] `application.yaml` —— listener prefetch/concurrency、publisher confirm
- [ ] 对账任务 + Micrometer 指标

---

## 11. 决策记录

| 决策 | 选择 | 放弃了什么 |
|---|---|---|
| 库存放哪 | Redis 预扣 + DB 兜底 | 单靠 DB（吞吐不够）/ 单靠 Redis（持久性不够） |
| 原子性手段 | Lua | WATCH/MULTI（重试风暴）、分布式锁（串行化） |
| 落库方式 | MQ 异步 | 同步落库（DB 仍被打穿）、定时批量（延迟不可控） |
| 幂等依据 | DB 复合主键 | Redis 标记（Redis 丢了就失效） |
| 状态表达 | 字符串 | 枚举（前端只做字符串比较） |
| 时间窗/退课 | 本轮不做 | 减少变量，先把“不超卖/不重复”练透 |
