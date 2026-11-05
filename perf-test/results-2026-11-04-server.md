# 抢课压测结果：2026-11-04 服务器

> 环境：阿里云 2 vCPU / 3.5 GiB；k6 与后端同机（`--network host`）；
> 课程 capacity=100；100 VU；单轮，没有重复跑。
> 所有数字来自 k6 原始 summary，没有补测、没有估算。

## 1. 正确性：500 个不同用户各一次

命令：

```bash
MODE=iterations VUS=100 ITERATIONS=500 \
  k6 run -e COURSE_ID=1 -e TOKENS=/scripts/tokens.txt /scripts/k6/course-grab.js
```

结果：

| 指标 | 值 |
|---|---|
| iterations | 500（45.06 req/s） |
| accepted | **100** |
| sold_out | **400** |
| duplicate / not_ready / not_started / closed / other | 0 |
| checks | 100%（500/500） |
| http_req_failed | 0% |
| http_req_duration | avg 2.09s / p50 2.03s / p95 3.34s / p99 3.68s |

核对：

- `db_course_selection`：`COUNT(*)=100`，`COUNT(DISTINCT uid)=100`；
- `db_course.selected_count=100`，`capacity=100`；
- `SCARD(course:chosen)=100`；
- `course.select` 0 条、DLQ 0 条。

结论：**100 个名额、500 个不同用户，最终正好 100 行、100 个 uid，没有超卖/重复。**

## 2. 吞吐：100 VU 持续 30 秒

命令：

```bash
MODE=duration VUS=100 DURATION=30s \
  k6 run -e COURSE_ID=2 -e TOKENS=/scripts/tokens.txt /scripts/k6/course-grab.js
```

结果：

| 指标 | 值 |
|---|---|
| iterations | 2105（**68.22 req/s**） |
| accepted | 100 |
| duplicate | 400 |
| sold_out | 1605 |
| checks | 100%（2105/2105） |
| http_req_failed | 0% |
| http_req_duration | avg 1.44s / p50 1.35s / p95 2.28s / p99 2.40s |

核对：

- `db_course_selection`：`COUNT(*)=100`，`COUNT(DISTINCT uid)=100`；
- `selected_count=100`，`SCARD(chosen)=100`；
- 队列 0、DLQ 0；
- 后端日志无 ERROR/Exception。

结论：**持续 30 秒 2105 次请求，业务上没有超卖；数量对得上
（100 + 400 + 1605 = 2105）。**

## 3. 口径与限制

- k6 和后端共用 2 个核，绝对吞吐是“同机竞争”下的表现，不是纯服务端能力；
- 只跑了一轮，没有方差估计；`p95` 波动可能较大；
- 这个基线是“RabbitMQ + 当前代码”，没有 Kafka 对照；
- `duplicate` 是 token 轮转造成的，不是错误；`sold_out` 是闸门正常工作的结果；
- 没有测 Redis 不可达、publisher confirm 丢失、DLQ 重放；
- 测前没有关闭 slow query / MyBatis SQL 日志（服务器默认配置），如果有日志开销，吞吐可能偏低。
