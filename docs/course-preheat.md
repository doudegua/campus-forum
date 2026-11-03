# 抢课预热：设计、实现与验收

> **状态：预热/关停已实现并实测。** retry 已做 A 方案（写失败结果 + ack 丢弃，不进 DLQ）；
> publisher confirm、权限、对账、监控仍未做。
> 核心实现见 `CoursePreheatServiceImpl`；本文保留设计推导和验收步骤。

---

## 1. 目标模型

```text
preheat（随时可做）     → chosen 重建 + meta（capacity/start/end）+ ready 最后写
select（每次请求）      → 先看 ready，再看 now 是否在 start/end 之间，再抢
close                   → 先摘 ready，再清 chosen
```

- 时间存 **epoch 毫秒**，不存格式化字符串；时区在 DB → `Date.getTime()` 那一步就消失了。
- `start/end` 来自 `db_course.select_start/select_end`；`CoursePreheatVo` 里的时间字段本轮不落库。
- **预热 ≠ 开门**：ready 可以在开始前写好，`select` 用时间判断是否放人。

## 2. Redis 键

| 键 | 类型 | 字段/值 | 说明 |
|---|---|---|---|
| `course:meta:{id}` | Hash | `capacity` / `start` / `end` / `ready` | ready 必须最后写 |
| `course:chosen:{id}` | Set | uid | 已占名额（预热 + 抢课共用） |
| `course:chosen:staging:{id}` | Set | uid | 预热临时集合，建完 RENAME |
| `course:preheat:lock:{id}` | String | "1" | SETNX + 30s TTL |

## 3. `preheatOne` 的步骤

```text
1. SETNX lock 1 EX 30           失败 → BUSY
2. ready 已存在                  → SKIPPED（不覆盖进行中的数据）
3. 查 course                    不存在 → NOT_FOUND
4. 查该课所有 db_course_selection 行
   COUNT > capacity              → COUNT_MISMATCH
5. DEL staging
   SADD staging <所有 uid>       一次多 member
   SCARD(staging) == COUNT       不等 → 抛异常/ERROR
6. HSET meta capacity/start/end  start/end 用 Date.getTime()
7. RENAME staging → chosen       原子替换
8. HSET meta ready 1             最后一步
9. finally 释放锁
```

为什么：

- **ready 最后写**：写之前 select 返回未就绪，不会读到半个集合；
- **staging + RENAME**：直接 `DEL chosen` 再 `SADD` 有一个“集合空着”的窗口，用户会抢进来然后被覆盖；
- **锁**：两个 preheat 同时跑会互相 RENAME；
- **以关系表行数为准**：`selected_count` 是计数器，可能漂。

## 4. `closeOne` 的步骤

```text
1. HDEL meta ready     先停售
2. DEL chosen
3. DEL meta            可选
```

## 5. 接口

```http
POST /api/admin/course/preheat
Content-Type: application/json
Authorization: Bearer <token>

[
  {"courseId": 1},
  {"courseId": 2}
]
```

```http
POST /api/admin/course/close
Content-Type: application/json

[1, 2]
```

返回：

```json
{"code":200,"data":[{"courseId":1,"status":"OPENED","message":null}],"message":"Request Success"}
```

状态：`OPENED / SKIPPED / BUSY / NOT_FOUND / COUNT_MISMATCH / CLOSED / ERROR`。

> 现在没有 `ROLE_ADMIN`，任何登录用户都能调；下一轮在 SecurityConfiguration 加
> `/api/admin/**` → `hasRole("ADMIN")` 即可，不用改这里。

## 6. 验收（实现完 core 之后）

准备：课程 1，capacity=50，DB 0 条选课；token 用 AGENT.md 第二节的签法。

```bash
# 1) 预热
curl -s -X POST -H "Authorization: Bearer $T" -H "Content-Type: application/json" \
  -d '[{"courseId":1}]' localhost:8081/api/admin/course/preheat

redis-cli HGETALL course:meta:1
redis-cli SCARD course:chosen:1
redis-cli TYPE course:chosen:staging:1     # 应为 none（已 RENAME）
```

预期：`HGETALL` 有 capacity/start/end/ready=1；`SCARD=0`。

```bash
# 2) 重复预热 → SKIPPED
curl -s -X POST ... -d '[{"courseId":1}]' .../preheat

# 3) 把 start 改到未来 1 分钟（直接改 DB），重新 preheat 前先 close
mysql -uroot -e "UPDATE test.db_course SET select_start = NOW() + INTERVAL 1 MINUTE WHERE id=1;"
curl -s -X POST ... -d '[1]' .../close
curl -s -X POST ... -d '[{"courseId":1}]' .../preheat
# select 应返回 NOT_STARTED（等 select 切换后）

# 4) 到点后 select → QUEUED；重复 → DUPLICATE
```

> ⚠️ 历史注意：预热和 `select` 切换必须一起上线。切换之前 `select` 还是“查 DB + chosen”，
> 预热期间的用户会被 RENAME 覆盖；本实现已经切到 meta，保留这条是为了以后重写时别踩。

## 7. 这一轮的学习检查点

1. ready 为什么最后写？提前写会怎样？
2. 为什么用 staging + RENAME，直接 DEL + SADD 有什么窗口？
3. 锁防什么？TTL 为什么不能省？
4. 为什么用 `SCARD(chosen)` 而不是 `selected_count`？
5. 预热到一半进程挂了，用户会看到什么？
6. 为什么时间存 epoch 毫秒？
7. `select` 切换后，`-2/-3/-4` 分别是什么状态？
