# 抢课压测（k6）

> 目标：验证 **预热 → Redis Lua 闸门 → MQ → DB 落库** 这条链路不超卖、不重复，
> 并记录吞吐/延迟基线。本文不预填任何数字，跑完把原始输出贴进来。
>
> 第一次接触 k6：先看 [`k6/TUTORIAL.md`](k6/TUTORIAL.md)（从零开始，6 个可独立运行的练习脚本）。

## 文件

```
perf-test/
├── k6/
│   ├── course-grab.js           # k6 脚本（shared-iterations，按 token 轮转）
│   ├── TUTORIAL.md              # 从零学 k6（新手先看这个）
│   └── tutorial/                # 教程配套：假接口 + 6 个练习脚本
├── scripts/gen_tokens.py        # 生成压测用 JWT token（不需要真实账号）
└── sql/
    ├── course-setup.sql         # 造一门独立的 [perftest] 课程
    ├── course-check.sql         # 压完核对 DB
    └── course-cleanup.sql       # 清理测试数据
```

## 前置

- 后端已起（本地 8081 或服务器 80）；
- Redis / RabbitMQ / MySQL 正常；
- 有 500 个 uid 的 token 文件（用 `perf-test/scripts/gen_tokens.py` 生成，不要用真实用户）；
- k6 可用：
  - 本机：`brew install k6`，或下载官方二进制（见 `k6/TUTORIAL.md` 第 1 节）；
  - 服务器：`grafana/k6` 镜像。注意 `--network host` 只适用于 Linux，macOS 的 Docker Desktop 不支持。

## 1. 造课并拿 courseId

```bash
mysql -uroot --default-character-set=utf8mb4 test < perf-test/sql/course-setup.sql

CID=$(mysql -uroot --default-character-set=utf8mb4 -N -e \
  "SELECT id FROM test.db_course WHERE name LIKE '[perftest]%' ORDER BY id DESC LIMIT 1;")
echo "courseId=$CID"
```

> 用 `LIKE '[perftest]%'` 而不是中文全等：mysql 客户端没指定 `utf8mb4` 时中文会变 `????`，
> 等值查不到。`ORDER BY id DESC LIMIT 1` 保证拿到刚插进去的那门课。

课程默认 capacity=100、时间窗是“现在前 1 小时 ~ 现在后 1 天”。

## 2. 生成 token

用仓库里的生成脚本（自己按项目 JWT 规则签发，不需要真实账号）：

```bash
python3 perf-test/scripts/gen_tokens.py 1 500 /tmp/perftest/tokens.txt --admin
```

- `--admin` 会额外带 `ROLE_ADMIN`，因为第 3 步的 `/api/admin/**` 要求管理员角色；
  只压 `/api/course/select` 的话不加也能跑（普通登录态即可）。
- 密钥默认取 `JWT_KEY`，没设就用本地默认 `666666`；服务器部署改过密钥就传环境变量。
- uid 从 1 开始，和真实用户表无关（压测完清理掉）。

> `ITERATIONS <= token 数` 时每个请求是不同用户，适合验证“不超卖/不重复”；
> `ITERATIONS > token 数` 会轮转，重复用户返回 `DUPLICATE`，适合只看吞吐。

## 3. 预热

需要一枚带 `ROLE_ADMIN` 的 token（`SecurityConfiguration` 里 `/api/admin/**` 要求 `hasRole("ADMIN")`，
用上面 `--admin` 生成的即可）：

```bash
T=$(head -1 /tmp/perftest/tokens.txt)

curl -s -X POST \
  -H "Authorization: Bearer $T" \
  -H "Content-Type: application/json" \
  -d "[{\"courseId\":$CID}]" \
  "${BASE:-http://localhost:8081}/api/admin/course/preheat"
```

期望 `OPENED`（同一门课重复预热会返回 `SKIPPED`，所以每轮前重跑第 1 步造新课）。检查：

```bash
redis-cli HGETALL course:meta:$CID     # capacity/start/end/ready=1
redis-cli SCARD course:chosen:$CID     # 0（新库）
```

## 4. 跑 k6

服务器上（脚本挂到 `/tmp/perftest`）。**本地没有 Docker 或用 macOS 时，把下面命令里的
`docker run --rm --network host -v /tmp/perftest:/scripts grafana/k6` 换成
`k6`（或二进制路径），`/scripts/...` 换成 `/tmp/perftest/...`，`BASE=http://localhost` 换成
`http://127.0.0.1:8081`（macOS 的 Docker Desktop 不支持 `--network host`，`localhost` 在容器里是容器自己）。**

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
redis-cli GET course:result:$CID:1       # 第一个用户（uid=1），应为 "1"
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

- 测前关掉慢查询日志、MyBatis SQL 日志和 `RequestLogFilter` 的每请求 INFO 日志，否则吞吐会被日志拖掉；
  实测本机 50 VU 跑 15 秒能写出 83MB 请求日志；
- `/actuator/health` 本地可能返回 DOWN/503（`MailHealthIndicator` 用空 `MAIL_PASSWORD` 连 smtp 失败，见 AGENT.md）；判断后端是否可用请直接打业务接口，别等 health 变 UP；
- 项目异常响应是 **HTTP 200 + `body.code`**，鉴权失败（401/403）也不会让 `http_req_failed` 变红；
  以 body 和自定义 Counter 为准，`other > 0` 先查原因；
- k6 和后端同机，绝对数字是“同机竞争”下的表现，报告里要写；
- 只跑一轮的数字别当结论；至少看队列积压、DB 是否同向；
- 不要把 `SOLD_OUT` 当成错误：它说明闸门在正常工作。
