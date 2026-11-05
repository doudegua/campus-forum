# 抢课压测（k6）

> 目标：验证 **预热 → Redis Lua 闸门 → MQ → DB 落库** 这条链路不超卖、不重复，
> 并记录吞吐/延迟基线。本文不预填任何数字，跑完把原始输出贴进来。

## 文件

```
perf-test/
├── k6/course-grab.js        # k6 脚本（shared-iterations，按 token 轮转）
└── sql/
    ├── course-setup.sql     # 造一门独立的 [perftest] 课程
    ├── course-check.sql     # 压完核对 DB
    └── course-cleanup.sql   # 清理测试数据
```

## 前置

- 后端已起（本地 8081 或服务器 80）；
- Redis / RabbitMQ / MySQL 正常；
- 有 500 个 uid 的 token 文件（沿用项目已有的 `gen_tokens.py`，不要用真实用户）；
- k6 可用：服务器上用 `grafana/k6` 镜像，本地没装就只准备脚本，不硬跑。

## 1. 造课并拿 courseId

```bash
mysql -uroot --default-character-set=utf8mb4 test < perf-test/sql/course-setup.sql

CID=$(mysql -uroot -N -e \
  "SELECT id FROM test.db_course WHERE name='[perftest] 抢课压测课' LIMIT 1;")
echo "courseId=$CID"
```

课程默认 capacity=100、时间窗是“现在前 1 小时 ~ 现在后 1 天”。

## 2. 生成 token

沿用现有脚本（路径按你的环境改）：

```bash
python3 scripts/gen_tokens.py 504 500 /tmp/perftest/tokens.txt
```

> `ITERATIONS <= token 数` 时每个请求是不同用户，适合验证“不超卖/不重复”；
> `ITERATIONS > token 数` 会轮转，重复用户返回 `DUPLICATE`，适合只看吞吐。

## 3. 预热

需要一枚已登录的 token（任意登录用户都行，当前 `/api/admin/**` 还没加角色限制）：

```bash
T=$(head -1 /tmp/perftest/tokens.txt)

curl -s -X POST \
  -H "Authorization: Bearer $T" \
  -H "Content-Type: application/json" \
  -d "[{\"courseId\":$CID}]" \
  "${BASE:-http://localhost:8081}/api/admin/course/preheat"
```

期望 `OPENED`。检查：

```bash
redis-cli HGETALL course:meta:$CID     # capacity/start/end/ready=1
redis-cli SCARD course:chosen:$CID     # 0（新库）
```

## 4. 跑 k6

服务器上（脚本挂到 `/tmp/perftest`）。

**正确性：500 个不同用户各一次，验证不超卖/不重复**

```bash
docker run --rm --network host -v /tmp/perftest:/scripts grafana/k6 run -q \
  -e BASE=http://localhost \
  -e COURSE_ID=$CID \
  -e TOKENS=/scripts/tokens.txt \
  -e MODE=iterations \
  -e VUS=100 \
  -e ITERATIONS=500 \
  /scripts/k6/course-grab.js
```

**吞吐：100 VU 持续 30 秒（token 会轮转，重复返回 DUPLICATE）**

```bash
docker run --rm --network host -v /tmp/perftest:/scripts grafana/k6 run -q \
  --summary-trend-stats "avg,min,med,max,p(90),p(95),p(99)" \
  -e BASE=http://localhost \
  -e COURSE_ID=$CID \
  -e TOKENS=/scripts/tokens.txt \
  -e MODE=duration \
  -e VUS=100 \
  -e DURATION=30s \
  /scripts/k6/course-grab.js
```

- `MODE=iterations`（默认）：`ITERATIONS <= token 数` 时每个请求是不同用户，适合正确性；
- `MODE=duration`：固定 VU 跑固定时长，适合吞吐；token 轮转，出现 `DUPLICATE` 是预期；
- `VUS=100`：2 vCPU 上用于对比的档位；`VUS=200` 只看稳定性，**不要**拿它比较优化效果；
- 先跑一次小规模（`VUS=10 ITERATIONS=50`）冒烟，再跑正式。

## 5. 等队列排空再核对

```bash
# 队列 0、DLQ 0；消费者还在但队列空
curl -s -u guest:guest http://127.0.0.1:15672/api/queues/%2F/course.select \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['messages'],d['messages_unacknowledged'])"

mysql -uroot --default-character-set=utf8mb4 test < perf-test/sql/course-check.sql

redis-cli SCARD course:chosen:$CID
redis-cli GET course:result:$CID:504     # 任意一个成功用户，应为 "1"
```

### 通过标准

| # | 判据 |
|---|---|
| 1 | `rows_ <= capacity` |
| 2 | `rows_ == accepted`（k6 输出的 `accepted`） |
| 3 | `rows_ == uids`（不重复） |
| 4 | `db_course.selected_count == rows_` |
| 5 | `SCARD(chosen) == rows_` |
| 6 | `course.select` 和 `course.select.dlq` 都是 0 |

> `accepted + sold_out + duplicate + not_ready + not_started + closed + other`
> 应等于 k6 的 `ITERATIONS`；对不上说明有请求没被分类，先查脚本/日志。

## 6. 清理

```bash
mysql -uroot --default-character-set=utf8mb4 test < perf-test/sql/course-cleanup.sql
redis-cli DEL course:meta:$CID course:chosen:$CID course:chosen:staging:$CID
redis-cli --scan --pattern "course:result:$CID:*" | while read k; do redis-cli DEL "$k" >/dev/null; done
```

## 口径提醒

- 测前关掉慢查询日志和 MyBatis SQL 日志，否则吞吐会掉一半；
- k6 和后端同机，绝对数字是“同机竞争”下的表现，报告里要写；
- 只跑一轮的数字别当结论；至少看队列积压、DB 是否同向；
- 不要把 `SOLD_OUT` 当成错误：它说明闸门在正常工作。
