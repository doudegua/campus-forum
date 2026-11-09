# k6 手把手：从没跑过压测到看懂本项目的抢课压测

> 读者假设：会用命令行、认识 JS 的 `{}`、函数和箭头函数，就够了。
> 不需要懂 Go，不需要 Docker，不需要先把后端跑起来。
>
> 配套练习脚本在 `perf-test/k6/tutorial/`，每一课都能单独跑（自带假接口）。
> 学完第 6 课，第 7 课拆项目正式脚本 `perf-test/k6/course-grab.js`，第 8 课按真实流程压一遍。
>
> 项目里已有的材料：
> - `perf-test/README.md`：正式压测的跑法（命令、判据、清理）
> - `perf-test/results-2026-11-04-server.md`：上一次在服务器上跑出来的结果
> - 本文：为什么这么跑、那些命令在干什么

---

## 0. 先搞清楚 k6 是什么

一句话：**k6 是一个命令行压测工具，你用 JS 描述"一个用户会做哪些请求"，k6 按你要求的并发重复执行，并统计延迟、错误和自定义指标。**

几个容易误解的点，先摆平：

| 你可能以为 | 实际 |
|---|---|
| k6 脚本在 Node 里跑 | 不是。k6 自带 JS 引擎（goja），没有 `window`、`document`、`require`，也不能 `npm install` |
| 压测脚本要写 `for` 循环刷请求 | 不用。你只写"一次操作"，k6 负责重复调用 |
| k6 能测页面打开速度 | 不能（它不渲染页面）。它测的是接口，浏览器端的指标要用 Lighthouse/WebPageTest |
| 压测就是比谁 QPS 高 | 不是。压测是找"加到多少开始变慢/出错"，以及验证业务规则（不超卖、不重复） |
| 请求返回值都是 200 就算成功 | 这个项目不是。项目约定 HTTP 永远 200，成败在 `body.code` / `body.data.status` 里 |

压测里只有三个词要先记住：

```
脚本（script）   你写的 .js 文件
VU（虚拟用户）   并发执行脚本的"假用户"。默认 1 个
iteration        一个 VU 把 default() 完整执行一遍
```

所以 `100 VU` 不是"发 100 个请求"，而是"100 个假用户各自不停地跑脚本"，直到你规定的次数或时间到。

---

## 1. 装 k6

三种方式选一种。**后文命令统一写 `k6`**，如果你用的是第 2 种（二进制），先把路径加进来：

```bash
export PATH="/tmp/k6bin/k6-v2.3.0-macos-arm64:$PATH"
```

### 方式 A：Homebrew（最省事，前提是 `/opt/homebrew` 可写）

```bash
brew install k6
k6 version
```

如果报 `Error: /opt/homebrew/Cellar is not writable`，说明这台机器上 Homebrew 目录不属于当前用户，先别折腾权限，直接用方式 B。

### 方式 B：官方二进制（这台机器上验证过的做法）

```bash
mkdir -p /tmp/k6bin
curl -sSL -o /tmp/k6.zip \
  https://github.com/grafana/k6/releases/download/v2.3.0/k6-v2.3.0-macos-arm64.zip
unzip -o -q /tmp/k6.zip -d /tmp/k6bin
/tmp/k6bin/k6-v2.3.0-macos-arm64/k6 version
```

应该看到类似：

```
k6 v2.3.0 (commit/e088784614, go1.26.8, darwin/arm64)
```

### 方式 C：服务器上用 Docker（Linux）

```bash
docker run --rm -i grafana/k6 run -e BASE=http://host.docker.internal:8080 - < script.js
```

两个坑：
- 脚本用 `-` 从 stdin 读，意味着 `open()` 读本地 token 文件会失败——要挂卷 `-v /tmp/perftest:/scripts` 并用 `/scripts/...` 路径。
- **macOS 的 Docker Desktop 不支持 `--network host`**，`localhost` 在容器里指的是容器自己。要用 `host.docker.internal`。项目 README 里那条 `--network host` 命令是给 Linux 服务器的，在 mac 上改了才知道。

---

## 2. 第 1 课：第一个脚本（10 分钟）

### 2.1 准备一个假接口

学 k6 不该被"后端没起、数据库没数据、没有 token"卡住。项目里带了一个假接口：

```bash
# 另开一个终端，保持运行
python3 perf-test/k6/tutorial/00-mock-server.py
```

看到 `mock server: http://127.0.0.1:18080` 就对了。验证一下：

```bash
curl -s http://127.0.0.1:18080/actuator/health
# {"status": "UP"}
```

### 2.2 跑第一个脚本

```bash
k6 run perf-test/k6/tutorial/01-hello.js
```

我在这里跑出来的原文（省略了 k6 的 logo 和进度刷新）：

```
     execution: local
        script: perf-test/k6/tutorial/01-hello.js
        output: -

     scenarios: (100.00%) 1 scenario, 1 max VUs, 10m30s max duration (incl. graceful stop):
              * default: 1 iterations for each of 1 VUs (maxDuration: 10m0s, gracefulStop: 30s)

time=... level=info msg="状态码=200 响应体={\"status\": \"UP\"}" source=console

  █ TOTAL RESULTS

    HTTP
    http_req_duration..............: avg=914µs min=914µs med=914µs max=914µs p(90)=914µs p(95)=914µs
    http_req_failed................: 0.00% 0 out of 1
    http_reqs......................: 1     0.988722/s

    EXECUTION
    iteration_duration.............: avg=1.01s min=1.01s med=1.01s max=1.01s p(90)=1.01s p(95)=1.01s
    iterations.....................: 1     0.988722/s
    vus............................: 1     min=1      max=1
    vus_max........................: 1     min=1      max=1

    NETWORK
    data_received..................: 175 B 173 B/s
    data_sent......................: 86 B  85 B/s

default ✓ [ 100% ] 1 VUs  00m01.0s/10m0s  1/1 iters, 1 per VU
```

### 2.3 这份输出怎么读

从上往下，先看这四块：

1. **`scenarios`**：这次跑的是什么形状。这里是"1 个 VU 跑 1 次"——因为脚本里没写 `options`，k6 的默认值就是 1 VU / 1 iteration。
2. **`TOTAL RESULTS`**
   - `http_req_duration`：**一次 HTTP 请求**花了多久。`p(95)` 是 95% 的请求都快于这个值，比 `avg` 更抗极端值。
   - `http_req_failed`：HTTP 层失败率。4xx/5xx、超时、连不上都算。**注意：业务失败（返回 200 但 body 里说没抢到）不算。**
   - `http_reqs`：一共发了几个请求，以及每秒几个（rate）。
   - `iteration_duration`：**一次 iteration 总共**多久——包含了 `sleep(1)`，所以是 1.01s，而 HTTP 只花了 914µs。
   - `iterations`：完成了几次 iteration，以及每秒几次。
   - `vus` / `vus_max`：实际/最大并发用户数。
3. **最后一行 `default ✓ [100%]`**：`✓` 只表示这个 scenario 正常跑完了，**不是"压测通过"**。过没过看阈值（第 5 课）和退出码。
4. **退出码**：`echo $?`，0 表示没有阈值失败。

三个反直觉的地方：

- **循环是 k6 帮你做的**。`default()` 只写一次请求，跑多少次由 options 决定。所以脚本里不要写 `while(true)`。
- **想加并发不用改脚本**。`k6 run --vus 5 --iterations 10 01-hello.js` 就够了（这一课之外，我们更推荐写在 options 里，见第 3 课）。
- **`sleep()` 会算进 iteration 耗时**。所以它直接决定吞吐：VU 数 ÷ 单次耗时 ≈ 每秒 iteration 数。

### 2.4 动手改

1. 把 `sleep(1)` 改成 `sleep(0.2)`，再跑一次。看 `iterations` 的 rate 和 `iteration_duration` 怎么变。
2. 跑 `k6 run --vus 3 --iterations 6 01-hello.js`，看 `vus_max` 变成 3、`iterations` 变成 6。
3. 把 `BASE` 改成一个不存在的端口（比如 `-e BASE=http://127.0.0.1:19999`），看 `http_req_failed` 变成多少、报什么错。这一步很重要：**真实压测里 URL 打错是常见事故**。

---

## 3. 第 2 课：check —— 让脚本自己判断对错

```bash
k6 run perf-test/k6/tutorial/02-checks.js
```

输出节选：

```
    scenarios: (100.00%) 1 scenario, 3 max VUs, 10m30s max duration ...
              * default: 9 iterations shared among 3 VUs ...

    checks_total.......: 36     24590.163934/s
    checks_succeeded...: 75.00% 27 out of 36
    checks_failed......: 25.00% 9 out of 36

    ✓ HTTP 200
    ✓ Content-Type 是 JSON
    ✓ body.status 是 UP
    ✗ 故意失败：body 里有 READY
      ↳  0% — ✓ 0 / ✗ 9

    http_req_duration..............: avg=243.66µs ...
    http_reqs......................: 9      6147.540984/s
```

### 3.1 看懂

- `check(res, { '断言名': (r) => 布尔表达式 })` 是断言，但**失败不中断脚本**，也不让退出码变 99。
- `checks` 指标 = 所有断言的总通过率。36 = 9 次请求 × 4 条断言；其中第 4 条是我故意写成必失败的（body 里根本没有 READY）。
- 每条断言单独有通过率，summary 里 `✓` / `✗` 列出来，失败会写 `✓ 0 / ✗ 9`。

### 3.2 check 和 threshold 的分工

| | check | threshold |
|---|---|---|
| 作用 | 记录"这次对不对" | 决定"这次压测算不算过" |
| 失败时 | 只记指标，继续跑 | 退出码 99 |
| 用在哪 | 校验响应内容 | CI 门禁、压测结论 |

所以两边都要有：check 负责**产生证据**，threshold 负责**下结论**。

### 3.3 动手

1. 删掉最后那条故意失败的断言，重跑，`checks` 应该 100%。
2. 加一条 `'响应体包含 status'`：`(r) => r.body.includes('status')`。
3. 故意把 URL 改成 `/actuator/health2`，看 checks 和 http_req_failed 分别变成什么样——这就是"URL 打错"在压测里的样子。

---

## 4. 第 3 课：VU、iteration、duration 到底什么关系

同一个脚本，用命令行换档位：

```bash
# 总共跑 6 次，分给 3 个 VU
k6 run -e VUS=3 -e ITERATIONS=6 perf-test/k6/tutorial/03-options.js

# 3 个 VU 持续跑 5 秒，能跑几次算几次
k6 run -e VUS=3 -e DURATION=5s perf-test/k6/tutorial/03-options.js
```

第一次的日志（注意 VU 编号和全局序号对不上，这是正常的）：

```
VU 1 执行第 2 次 iteration
VU 3 执行第 1 次 iteration
VU 2 执行第 3 次 iteration
VU 1 执行第 5 次 iteration
VU 3 执行第 6 次 iteration
VU 2 执行第 4 次 iteration
...
default ✓ [ 100% ] 3 VUs  6/6 shared iters
```

第二次的结果：

```
    iterations.....................: 30     5.713449/s
    vus....................: 3      min=3 max=3
default ✓ [ 100% ] 3 VUs  5s
```

### 4.1 两种压法，对应两个 executor

| 你写的 | k6 选的 executor | 含义 |
|---|---|---|
| `vus` + `iterations` | `shared-iterations` | 总共 N 次，N 个 VU 抢着做完 |
| `vus` + `duration` | `constant-vus` | VU 数固定，跑固定时长 |
| `stages`（第 4 课） | `ramping-vus` | VU 数按曲线变化 |

日志里的 `6/6 shared iters` 和 `30 complete` 就是这两种模式的区别。

### 4.2 吞吐量怎么估

用第二次的数算一遍：

```
单次 iteration 平均耗时 ≈ 525ms（其中 HTTP 24ms + sleep 500ms）
3 个 VU 每秒能跑 ≈ 3 / 0.525 ≈ 5.7 次
5 秒 ≈ 30 次  ← 和 iterations 的 30 对得上
```

这就是**利特尔法则**：`吞吐 ≈ VU 数 ÷ 单次耗时`。

由此得到两个实用推论：

- 加了 `sleep` 的脚本，吞吐被 sleep 限制。想压满服务端，要么减 VU 的思考时间，要么加 VU。
- `http_req_duration` 和 `iterations/s` 要一起看。如果 VU 很多但 iterations/s 上不去，说明单次请求变慢了（排队），不是服务端在"没干活"。

### 4.3 `exec` 的两个常用字段

```js
exec.vu.idInTest               // 当前 VU 编号，1..N
exec.scenario.iterationInTest  // 整个 scenario 里第几次 iteration，0..N-1
```

第二个是**全局计数器**，不是"这个 VU 的第几次"——第 7 课项目脚本就是靠它给每个请求分配不同用户的。

### 4.4 动手

1. `VUS=1 ITERATIONS=6` 和 `VUS=6 ITERATIONS=6` 各跑一次，比较总耗时（前者串行）。
2. `DURATION=5s` 时把 `VUS` 从 3 改成 6，看 iterations 是不是大约翻倍。
3. 看 `vus` 那行的 `min/max`：`constant-vus` 下它们都等于设置的 VU 数；`ramping-vus` 下 `min` 会是 0。

---

## 5. 第 4 课：阶梯加压（压测真正常用的形状）

```bash
k6 run perf-test/k6/tutorial/04-stages.js
```

输出节选：

```
     scenarios: (100.00%) 1 scenario, 5 max VUs, 9s max duration ...
              * ramp: Up to 5 looping VUs for 8s over 3 stages ...

running (1.0s), 1/5 VUs, 0 complete and 0 interrupted iterations
ramp   [  13% ] 1/5 VUs  1.0s/8.0s
running (2.0s), 3/5 VUs, 3 complete ...
running (3.0s), 5/5 VUs, 10 complete ...
...
    vus............................: 2      min=1       max=5
    vus_max........................: 5      min=5       max=5
    iterations.....................: 55     6.480083/s
ramp ✓ [ 100% ] 0/5 VUs  8s
```

### 5.1 为什么要阶梯

固定 100 VU 只能回答"100 VU 时行不行"。阶梯能回答"加到多少个 VU 开始劣化"——那才是容量。

真实压测通常是这样的曲线：

```
VU
20 |          ┌──── 观察稳态 ────┐
10 |      ┌───┘                  └───┐
 0 |──────┘                          └────
   +-----------------------------------> 时间
```

每个台阶要够长（至少 1~2 分钟），让连接池预热、JIT 编译、缓存都能稳定下来；只看第一个台阶的数会低估或高估。

### 5.2 语法

```js
scenarios: {
  ramp: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '3s', target: 5 },  // 3 秒内爬到 5 个 VU
      { duration: '3s', target: 5 },  // 保持
      { duration: '2s', target: 0 },  // 降到 0，脚本结束
    ],
    gracefulRampDown: '1s',
  },
}
```

`gracefulRampDown`：降 VU 时给正在进行的 iteration 一点收尾时间，避免把"被中断的请求"算成失败。

### 5.3 动手

1. 把 3 个 stage 改成 `10s → 20 VU`，看 `vus_max` 和 iterations。
2. 故意在 `default()` 里加 500ms 的 `sleep`，看同样的 stages 下 iterations 掉多少——这就是"思考时间"对压测结果的影响。

---

## 6. 第 5 课：自定义指标 + threshold

```bash
k6 run perf-test/k6/tutorial/05-metrics-thresholds.js
echo "退出码=$?"     # 99
```

输出节选：

```
  █ THRESHOLDS

    http_req_failed
    ✓ 'rate<0.01' rate=0.00%

    json_ok
    ✓ 'rate>0.99' rate=100.00%

    select_latency
    ✓ 'p(95)<300' p(95)=22.5636

    slow_latency
    ✗ 'p(95)<300' p(95)=505.6025

  █ TOTAL RESULTS

    CUSTOM
    json_ok........................: 100.00% 4 out of 4
    select_calls...................: 4       3.785839/s
    select_latency.................: avg=21.9385 ... p(95)=22.5636
    slow_latency...................: avg=505.47625 ... p(95)=505.6025

    http_req_failed................: 0.00%   0 out of 8
```

### 6.1 四种自定义指标

| 类型 | 记什么 | 典型用途 |
|---|---|---|
| `Counter` | 只增不减的次数 | 成功抢到几个、发出去几条消息、业务状态各几次 |
| `Trend` | 一组数值 | 某个接口的延迟、响应体大小 |
| `Rate` | 0/1 比例 | JSON 解析成功率、非 2xx 比例 |
| `Gauge` | 只保留最后一个值 | 队列积压（一般直接读监控，少用） |

```js
const selectCalls = new Counter('select_calls');
const selectLatency = new Trend('select_latency');
const jsonOk = new Rate('json_ok');
```

指标名自己起，跑起来的 summary 里就会多出 `CUSTOM` 区对应的行。

### 6.2 threshold 决定退出码

```js
thresholds: {
  http_req_failed: ['rate<0.01'],        // 内置指标
  select_latency: ['p(95)<300'],        // 自定义 Trend
  json_ok: ['rate>0.99'],               // 自定义 Rate
}
```

- 退出码 `0`：全部通过
- 退出码 `99`：至少一个失败（数字照常打印，并在最后打出 `thresholds on metrics 'xxx' have been crossed`）

这是 k6 能进 CI 的原因：不用人肉看日志，`k6 run` 的退出码就是门禁。

> 一个常见误区：把阈值定得太理想，第一次跑就红。阈值应该来自"业务能接受的上限"或"上一轮基线"，不是拍脑袋。

### 6.3 动手

1. 把 `slow_latency` 的阈值改成 `p(95)<600`，重跑，退出码变 0。
2. 加一个 `http_req_duration: ['p(95)<100']`，重跑——必然失败，因为平均就有 260ms。体会一下"阈值失败但请求本身没错"。
3. 把 mock server 的 `MOCK_LATENCY` 改成 `0.1` 重启，观察 `select_latency` 的 p95 变化。

---

## 7. 第 6 课：带登录态的 POST + 业务状态 + `open()`

```bash
# 假服务只检查 "Bearer " 前缀，随便几行字都能当 token
mkdir -p /tmp/perftest
printf 'mock-token-1\nmock-token-2\nmock-token-3\n' > /tmp/perftest/tokens.txt

k6 run -e BASE=http://127.0.0.1:18080 -e TOKENS=/tmp/perftest/tokens.txt \
  perf-test/k6/tutorial/06-post-auth.js
```

输出节选：

```
    checks_total.......: 27      387.157831/s
    checks_succeeded...: 100.00% 27 out of 27
    ✓ content-type is json
    ✓ 业务 code 是 200
    ✓ 状态是已知值

    CUSTOM
    queued.........................: 9      129.05261/s

    http_req_failed................: 0.00%  0 out of 9
```

### 7.1 这一课有三个新东西

**① `open()` 只能在 init 阶段用**

```js
const tokens = open(TOKENS_FILE).split('\n').filter(Boolean);
```

`open()` 读本地文件，但 k6 规定它只能在脚本最外层（init 上下文）调用，不能在 `default()` 里。原因：每个 VU 启动时都会各自初始化一次，读文件的结果被所有 iteration 共享。

**② 全局序号决定用哪个 token**

```js
const idx = exec.scenario.iterationInTest;
const token = tokens[idx % tokens.length];
```

`iterationInTest` 是全局递增的，所以每个 iteration 会拿到不同的 token；超过 token 数后取模轮转。

**③ 业务状态要自己分类**

```js
const body = res.json();
switch (body.data && body.data.status) {
  case 'QUEUED': queued.add(1); break;
  default: other.add(1);
}
```

项目的接口 **HTTP 永远 200**（限流/鉴权失败除外），"抢到了/没名额了/重复了"都在 `body.data.status` 里。所以：

- `http_req_failed` 只能抓 401/5xx/超时，抓不到业务失败；
- `check` 用来确认"响应格式对不对"；
- `Counter` 用来数"每种业务状态各几次"，这才是对账的依据。

**④ `tags: { name: 'course-select' }`**

```js
http.post(url, body, { tags: { name: 'course-select' } });
```

给请求打标签，summary 里会多出 `http_req_duration{name:course-select}` 这样的细分行。一个脚本打多个接口时，靠它区分各接口延迟。

### 7.2 动手

1. 把 `iterations` 改成 6，token 只有 3 个——观察第 4 次开始重复用 token。真实后端这时会返回 `DUPLICATE`（第 8 课会看到）。
2. 把 token 文件清空，跑一次，看 k6 在 init 阶段怎么报错（脚本直接起不来，一个请求都不发）。
3. 去掉 `Authorization` 头，看 `http_req_failed` 变成多少（假服务返回 401）。

---

## 8. 第 7 课：逐段拆解项目正式脚本

打开 `perf-test/k6/course-grab.js`，对照下面看。

### 第 1 段：为什么不能只看 HTTP 状态码（注释，第 6-16 行）

```js
/**
 * 注意：接口 HTTP 永远是 200，业务状态在 body.data.status 里，
 * 所以不能只看 http_req_failed。
 */
```

这就是第 6 课第 ③ 点。压测脚本的"正确性判定"必须建立在业务语义上，不然会出现"HTTP 全 200，压测全绿，但一个都没抢到"。

### 第 2 段：用环境变量选 executor（第 17-27 行）

```js
const mode = (__ENV.MODE || 'iterations').toLowerCase();
const vus = Number(__ENV.VUS || 100);

const grabScenario = mode === 'duration'
  ? { executor: 'constant-vus', vus, duration: __ENV.DURATION || '30s' }
  : {
      executor: 'shared-iterations',
      vus,
      iterations: Number(__ENV.ITERATIONS || 500),
      maxDuration: __ENV.MAX_DURATION || '60s',
    };
```

一个脚本同时支持两种压法：

- `MODE=iterations`：固定总次数，用来验证"500 个不同用户各抢一次，不超卖不重复"；
- `MODE=duration`：固定 VU 跑固定时长，用来测吞吐。

`maxDuration` 是兜底：如果服务端慢到 60 秒还没跑完 500 次，k6 会停下来，不会无限挂着。

### 第 3 段：thresholds（第 29-39 行）

```js
thresholds: {
  http_req_failed: ['rate<0.01'],   // 401/5xx 才算失败
  checks: ['rate>0.99'],           // 防止 URL 拼错打到 nginx 的 SPA 回退
}
```

第二条值得展开：如果 `BASE` 写错或者端口少写，请求可能打到 nginx 的静态站点回退，返回 `index.html`。这时 HTTP 是 200，`http_req_failed` 是 0%，看起来一切正常。脚本里的 `check('content-type is json')` 会失败，`checks` 阈值就会把整轮压测标红——**压测脚本要防止"测了个寂寞"**。

### 第 4 段：七个业务 Counter（第 41-47 行）

```js
const accepted = new Counter('accepted');   // QUEUED = 受理，最终大概率抢到
const soldOut = new Counter('sold_out');    // 名额没了
const duplicate = new Counter('duplicate'); // 这个人已经抢到过
const notReady = new Counter('not_ready');  // 没预热
const notStarted = new Counter('not_started');
const closed = new Counter('closed');
const other = new Counter('other');         // 兜底：包括 401、非 JSON、未知状态
```

为什么分这么细：第 8 课的"通过标准"要靠这些数对账。

```
accepted + sold_out + duplicate + not_ready + not_started + closed + other
应该等于 iterations
```

对不上就说明有请求没被分类（比如 401 被记到 other 里、或者脚本逻辑漏了分支）。

### 第 5 段：init 阶段读 token（第 49-65 行）

```js
const base = (__ENV.BASE || 'http://localhost').replace(/\/$/, '');
const courseId = __ENV.COURSE_ID;
if (!courseId) throw new Error('缺少 -e COURSE_ID=<课程 id>');

const tokens = open(tokenFile).split('\n').map(s => s.trim()).filter(Boolean);
if (tokens.length === 0) throw new Error('token 文件为空');
```

两个细节：

- `COURSE_ID` 缺失时在 **init 阶段**直接 `throw`。k6 会在发第一个请求之前退出——这是好事，避免压了半天发现参数错了。
- `.replace(/\/$/, '')` 去掉 BASE 末尾的 `/`，避免拼出 `http://host//api/...`。

### 第 6 段：取 token + 发请求（第 67-82 行）

```js
const idx = exec.scenario.iterationInTest;
const token = tokens[idx % tokens.length];

const res = http.post(
  `${base}/api/course/select`,
  JSON.stringify({ courseId: Number(courseId) }),
  {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    tags: { name: 'course-select' },
  },
);
```

`iterationInTest % tokens.length` 是**正确性压测的关键**：`ITERATIONS <= token 数` 时，每个请求都是不同用户，才能验证"不超卖、不重复"。如果 `ITERATIONS > token 数`，token 会轮转，重复用户返回 `DUPLICATE`——这时的压测只能看吞吐，不能看正确性。

### 第 7 段：先校验格式，再解析，再分类（第 84-127 行）

```js
const isJson = check(res, {
  'content-type is json': (r) => (r.headers['Content-Type'] || '').includes('application/json'),
});
if (!isJson) { other.add(1); return; }

let body;
try { body = res.json(); } catch (e) { other.add(1); return; }

if (body.code !== 200) { other.add(1); return; }

switch (body.data && body.data.status) {
  case 'QUEUED': accepted.add(1); break;
  case 'SOLD_OUT': soldOut.add(1); break;
  case 'DUPLICATE': duplicate.add(1); break;
  // ...
  default: other.add(1);
}
```

顺序很重要：**content-type → JSON 解析 → body.code → data.status**。每一步失败都归到 `other`，这样 summary 里 `other > 0` 就是在提醒你"有请求没被正常分类"。

这段不是"断言"，是**分类器**：它不判断对错，只记录每种结果各多少。对错由压完之后的 DB/Redis 核对和通过标准决定。

### 8.1 对照练习

- 找出脚本里哪些代码属于 init 阶段（只执行一次），哪些属于每个 iteration（执行 N 次）。
- 如果把 `open()` 挪进 `default()`，会发生什么？（运行时报 `GoError: the "open" function is only available in the init stage`，试试看）
- 把 `MODE` 从 `iterations` 改成 `duration`，`options.scenarios` 会变成什么？

---

## 9. 第 8 课：按真实流程跑一遍项目压测

前置：后端能起（本地 8080 或 8081）、MySQL/Redis/RabbitMQ 正常。

> 这一节和 `perf-test/README.md` 是同一套流程。README 是速查，这里解释每一步为什么。

### 9.1 起后端

```bash
# 用 JDK21（AGENT.md 要求；mvn 默认可能是 23，稳妥起见显式指定）
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -f my-project-backend/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments=--server.port=8081
```

或者用构建好的 jar（更快）：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  java -jar my-project-backend/target/my-project-backend-0.0.1-SNAPSHOT.jar \
  --server.port=8081
```

**坑**：本机 `/actuator/health` 可能返回 503/DOWN，因为 `MailHealthIndicator` 用空 `MAIL_PASSWORD` 去连 `smtp.163.com` 认证失败（AGENT.md 第十节）。这不代表后端不可用。要 health 干净可以加：

```bash
--management.health.mail.enabled=false
```

判断后端就绪，直接打接口更可靠：

```bash
T=$(head -1 /tmp/perftest/tokens.txt)
curl -s -H "Authorization: Bearer $T" http://127.0.0.1:8081/api/course/list
```

### 9.2 造课并拿 courseId

```bash
mysql -uroot --default-character-set=utf8mb4 test < perf-test/sql/course-setup.sql

CID=$(mysql -uroot --default-character-set=utf8mb4 -N -e \
  "SELECT id FROM test.db_course WHERE name LIKE '[perftest]%' ORDER BY id DESC LIMIT 1;")
echo "courseId=$CID"
```

课程默认 `capacity=100`，时间窗是"现在前 1 小时 ~ 现在后 1 天"。**每次压测前重新执行一遍 setup**，它会先删掉旧的 `[perftest]` 课程和选课记录，保证从干净状态开始。

> 用 `LIKE` 而不是中文全等是有原因的：mysql 客户端没指定 `--default-character-set=utf8mb4` 时，
> 中文列会显示成 `????`，等值查询永远查不到。`ORDER BY id DESC LIMIT 1` 则是为了拿到刚插进去的那门课。

### 9.3 生成 token

```bash
python3 perf-test/scripts/gen_tokens.py 1 500 /tmp/perftest/tokens.txt --admin
```

为什么要 `--admin`：预热接口 `/api/admin/course/preheat` 在 `SecurityConfiguration` 里要求 `ROLE_ADMIN`（README 里"还没加角色限制"那句话已经过时了）。

脚本做的事：按项目 JWT 规则（HMAC256、key 默认 `666666`、claims 带 `id/name/authorities/jti/iat/exp`）签 500 个"假用户"token，uid 1..500。注意 `authorities` 必须是 JSON 数组，写成字符串会让后端 NPE 500。

> token 是伪造的登录态，uid 不要求真的存在于用户表——抢课记录表不校验用户外键。压测完记得清理数据。

### 9.4 预热

```bash
T=$(head -1 /tmp/perftest/tokens.txt)
curl -s -X POST \
  -H "Authorization: Bearer $T" \
  -H "Content-Type: application/json" \
  -d "[{\"courseId\":$CID}]" \
  http://127.0.0.1:8081/api/admin/course/preheat
```

期望返回 `"status":"OPENED"`。

> ⚠️ 权限不足时后端返回的是 **HTTP 200 + `body.code=403`**（项目约定 HTTP 永远 200），
> curl 不加 `-i` 会以为成功了。所以预热必须看 body 里的 `status` 是不是 `OPENED`：
> 如果预热失败，压测会得到 `accepted=0`、`not_ready=500`，但 k6 的 threshold 仍然全绿。
> 这就是为什么 9.7 的对账不能省。

然后检查 Redis（键名以 `Const.java` 为准）：

```bash
redis-cli HGETALL course:meta:$CID      # capacity / start / end / ready=1
redis-cli SCARD course:chosen:$CID      # 0（干净状态）
```

预热做的事：校验 MySQL 里的已选人数和容量，把已选名单写进 `course:chosen`，把 `capacity/start/end` 和 `ready=1` 写进 `course:meta`。只有 `ready=1` 之后接口才放行；没预热就压，所有请求都会是 `NOT_READY`。同一门课重复预热会返回 `SKIPPED`（不覆盖正在用的数据），所以每轮压测前要重新跑 setup 造新课。

### 9.5 先冒烟，再正式

```bash
# 冒烟：小规模先确认链路通
k6 run -q \
  -e BASE=http://127.0.0.1:8081 \
  -e COURSE_ID=$CID \
  -e TOKENS=/tmp/perftest/tokens.txt \
  -e MODE=iterations \
  -e VUS=10 \
  -e ITERATIONS=50 \
  perf-test/k6/course-grab.js
```

冒烟通过（`checks` 100%、`http_req_failed` 0%、`accepted + sold_out = 50`）再跑正式。

> ⚠️ 冒烟会**真的占掉名额**（50 次里被受理的那些）。跑正式之前要重跑 9.2 的 setup 和 9.4 的预热，
> 换一门干净课程，否则 `accepted` 和 DB 行数会对不上。同理，吞吐那轮也要用新课程。

**正确性：500 个不同用户各一次**

```bash
k6 run -q \
  -e BASE=http://127.0.0.1:8081 \
  -e COURSE_ID=$CID \
  -e TOKENS=/tmp/perftest/tokens.txt \
  -e MODE=iterations \
  -e VUS=100 \
  -e ITERATIONS=500 \
  perf-test/k6/course-grab.js
```

**吞吐：100 VU 跑 30 秒**

```bash
k6 run -q --summary-trend-stats "avg,min,med,max,p(90),p(95),p(99)" \
  -e BASE=http://127.0.0.1:8081 \
  -e COURSE_ID=$CID \
  -e TOKENS=/tmp/perftest/tokens.txt \
  -e MODE=duration \
  -e VUS=100 \
  -e DURATION=30s \
  perf-test/k6/course-grab.js
```

注意 `duration` 模式下 token 会轮转，出现 `DUPLICATE` 是**预期**，不是错误。

### 9.6 等队列排空再核对

压测结束 ≠ 落库结束。MQ 是异步的，要等消费完再查：

```bash
# RabbitMQ 队列积压（管理接口，guest/guest）
curl -s -u guest:guest http://127.0.0.1:15672/api/queues/%2F/course.select \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['messages'],d['messages_unacknowledged'])"
# 期望 0 0；管理插件没开就用 rabbitmqctl list_queues name messages（可能慢，别当卡死）

mysql -uroot --default-character-set=utf8mb4 test < perf-test/sql/course-check.sql

redis-cli SCARD course:chosen:$CID
redis-cli GET course:result:$CID:1        # 任意一个成功用户，应为 "1"
```

### 9.7 通过标准

| # | 判据 | 在哪看 |
|---|---|---|
| 1 | `rows_ <= capacity` | `course-check.sql` |
| 2 | `rows_ == accepted` | 对照 k6 summary 的 `accepted` |
| 3 | `rows_ == uids`（不重复） | `course-check.sql` |
| 4 | `db_course.selected_count == rows_` | `course-check.sql` |
| 5 | `SCARD(chosen) == rows_` | `redis-cli` |
| 6 | `course.select` 和 `course.select.dlq` 都是 0 | RabbitMQ |

再加一条总的：

```
accepted + sold_out + duplicate + not_ready + not_started + closed + other == iterations
```

> `SOLD_OUT` 不是错误，它证明 Redis 闸门在正常工作。`DUPLICATE` 在 duration 模式是预期的。

### 9.8 清理

```bash
mysql -uroot --default-character-set=utf8mb4 test < perf-test/sql/course-cleanup.sql
redis-cli DEL course:meta:$CID course:chosen:$CID course:chosen:staging:$CID
redis-cli --scan --pattern "course:result:$CID:*" | while read k; do redis-cli DEL "$k" >/dev/null; done
```

压测数据必须清：`db_course_selection` 里的行、Redis 的 `course:*` 键都会影响下一轮结果。

### 9.9 本机实测记录（2026-11-09，Apple Silicon macOS）

> 口径：后端与 k6 同机（`127.0.0.1`），MySQL/Redis/RabbitMQ 也在同一台机器上；
> 后端用 jar 起在 8081，加了 `--management.health.mail.enabled=false`；
> 每轮重新 setup 课程，`capacity=100`；单轮，没有重复跑。
> **这是学习环境的数字，不是服务器基线**，不能和 `results-2026-11-04-server.md` 直接比较。

**冒烟：10 VU / 50 次（courseId=8）**

```bash
k6 run -e BASE=http://127.0.0.1:8081 -e COURSE_ID=8 \
  -e TOKENS=/tmp/perftest/tokens.txt -e MODE=iterations -e VUS=10 -e ITERATIONS=50 \
  perf-test/k6/course-grab.js
```

```
checks                 ✓ 100.00%
http_req_failed        ✓ 0.00%
accepted................: 50
http_req_duration......: avg=19.54ms p(95)=75.87ms
iterations.............: 50   (487.2/s)
```

冒烟后马上查 DB 就是 50 行、`selected_count=50`、`SCARD=50`，说明 RabbitMQ 消费链路实时生效。

**正确性：100 VU / 500 个不同用户（courseId=9）**

```
accepted.......................: 100
sold_out.......................: 400
http_req_duration..............: avg=27.97ms p(95)=56.36ms
http_req_failed................: 0.00% 0 out of 500
checks_succeeded...............: 100.00% 500 out of 500
iterations.....................: 500   (3368.8/s)
```

分类合计 `100 + 400 = 500 = ITERATIONS`；`duplicate / not_ready / other` 在 summary 里没出现，
是因为 k6 **零值 counter 不打印**，不是没统计。

等队列排空后对账：

| 判据 | 实测 | 结论 |
|---|---|---|
| `rows_ <= capacity` | 100 ≤ 100 | ✅ |
| `rows_ == accepted` | 100 == 100 | ✅ |
| `rows_ == uids` | 100 == 100 | ✅ |
| `selected_count == rows_` | 100 == 100 | ✅ |
| `SCARD(chosen) == rows_` | 100 == 100 | ✅ |
| `course.select` / `dlq` | 0 / 0 | ✅ |

结论：100 个名额、500 个不同用户，最终正好 100 行、100 个 uid，没有超卖、没有重复。
`SOLD_OUT` 是闸门正常工作的结果，不是错误。

**吞吐：50 VU 持续 15 秒（courseId=10）**

```
iterations.....................: 167,044   (11,134.5/s)
accepted.......................: 100
duplicate......................: 33,344
sold_out.......................: 133,600
http_req_duration..............: avg=4.38ms p(95)=7.36ms p(99)=11.34ms
```

`100 + 33,344 + 133,600 = 167,044`，对得上。

读这组数要注意两件事：

- 名额抢完之后，绝大多数请求走的是"快速失败"路径，所以 p95 7ms 是**闸门 RTT**，不是落库 RTT；
- 这台机器上 `RequestLogFilter` 每个请求打一条 INFO 日志，15 秒后 `backend.log` 涨到 83MB。
  日志本身也在消耗 CPU/IO，绝对数字只能当学习环境参考。

**和服务器那次（68.22 req/s、p50 1.35s）为什么差这么多**

不是代码变快了：区别在网络栈（loopback vs 容器网络）、机器规格（Apple Silicon vs 2 vCPU）、
以及服务器上 k6 和后端、MySQL、Redis、MQ 全挤在 2 个核上互相抢。**跨环境的绝对数字没有比较意义**——
要比较就同机、同档位、同数据，跑"改前 vs 改后"。

---

## 10. 怎么读结果、怎么写结论

### 10.1 只看四个数

| 数 | 在哪 | 说明 |
|---|---|---|
| 吞吐 | `iterations` 的 rate | 每秒完成多少次完整操作 |
| 延迟 | `http_req_duration` 的 p95/p99 | p95 比 avg 更能代表"大多数用户" |
| 错误率 | `http_req_failed` + `checks` | HTTP 层 + 业务层各看一层 |
| 业务计数 | 自定义 Counter | 对账用，决定结论可不可信 |

### 10.2 写结论前先写口径

`results-2026-11-04-server.md` 开头那段值得抄：

> 环境：阿里云 2 vCPU / 3.5 GiB；k6 与后端同机（`--network host`）；课程 capacity=100；100 VU；单轮，没有重复跑。

必须写清楚的四件事：

1. 机器规格和部署方式（k6 是否和后端同机——同机会抢 CPU，绝对数字偏低）；
2. 压测档位（VU、时长/次数、capacity）；
3. 跑了几轮（单轮不能当结论，至少 3 轮看波动）；
4. 这轮数据有没有清理干净、服务端有没有额外开销。`logging.level.net.doudegua.mapper=debug`（MyBatis SQL 日志）
   和 `RequestLogFilter` 的每请求 INFO 日志都会拖吞吐——实测 15 秒能写出 83MB 日志，正式压测前先关掉/调高级别。

### 10.3 对比优化前后

- 同机、同档位、同数据、同轮数，才可比；
- 先跑基线再改代码，别改完再"回忆"基线；
- 别拿 200 VU 的数字和 100 VU 比——那是在比档位，不是比优化；
- 瓶颈不在 MQ 时，不要拿"Kafka vs RabbitMQ"的压测数字下结论（项目 AGENT.md 里也是这么写的）。

---

## 11. 常见报错对照表

| 现象 | 原因 | 处理 |
|---|---|---|
| `k6: command not found` | 没装 / 没进 PATH | 第 1 节；或把二进制目录加进 PATH |
| `brew install k6` 报 `/opt/homebrew/Cellar is not writable` | Homebrew 目录不属于当前用户 | 用官方二进制，或先修权限 |
| `Error: no such file` 指向 token 文件 | `open()` 路径错了；容器里没挂卷 | 用绝对路径；Docker 用 `-v` 挂载 |
| `missing -e COURSE_ID` | 环境变量没传 | 检查 `-e` 参数顺序（`-e` 要放在脚本路径前） |
| 所有请求 401 | token 过期 / `authorities` 不是数组 / 预热用了普通用户 token | 用 `--admin` 重新生成；换新 token |
| 指标全绿但接口其实 401/403 | 项目异常响应 **HTTP 200 + `body.code=401/403`**，`http_req_failed` 和 `checks` 都抓不到 | 看 `other` 计数和 body；给关键业务计数配 threshold |
| summary 里看不到某个 Counter | k6 零值 counter 不打印（为 0 就不出现） | 别把"没这一行"当"没统计"，需要就自己看分类合计 |
| `SELECT id ... WHERE name='[perftest]...'` 查不到 | mysql 连接字符集不是 utf8mb4，中文变 `????` | 给 mysql 加 `--default-character-set=utf8mb4`，或改用主键 / `LIKE '[perftest]%'` |
| `checks` 通过率低但 `http_req_failed` 是 0 | `BASE` 打到了前端/nginx，返回 HTML | 检查端口和路径；看 `content-type is json` 那条 |
| 压测全绿但 `accepted=0` | 没预热 / 课程不在时间窗 / Redis 里没有 `ready` | 重新执行 9.4，检查 `course:meta` |
| `iterations` 比预期少 | `maxDuration` 到了被截断；或服务端太慢 | 看 summary 末尾的 `interrupted iterations`；调大 `MAX_DURATION` |
| 吞吐只有几十 req/s、p95 很高 | k6 和后端抢同一块 CPU；开了 SQL 日志 | 分机压测；测前关慢查询/MyBatis 日志 |
| `duration` 模式出现大量 `DUPLICATE` | token 轮转，同一用户重复请求 | 预期行为，不是 bug |
| 压完 DB 里没有数据 | 队列还没消费完 | 等队列排空再核对（9.6） |
| 压完数据没清 | 忘了 cleanup | 每次压测前跑 setup，结束后跑 cleanup + Redis 删除 |

---

## 12. 学完下一步

按这个顺序继续，别一次跳太远：

1. **给项目脚本加 `handleSummary`**：把 summary 导出成 JSON/Markdown，方便多次对比（`k6 run --summary-export` 也能导，但自定义格式更灵活）。
2. **多轮次与方差**：同一档位跑 3 次，记录 min/median/max，而不是只贴一次。
3. **阶梯找拐点**：用第 4 课的 `ramping-vus` 从 10 VU 加到 200 VU，找 p95 开始飙升的档位。
4. **配合服务端指标**：只压客户端看不出瓶颈。看 JVM（GC/线程池）、MySQL（慢查询/连接数）、Redis（延迟）、队列积压。
5. **进 CI**：把冒烟档位放进流水线，用退出码卡住劣化（阈值从基线来，不是拍脑袋）。

---

## 附录 A：命令速查

```bash
# 跑脚本
k6 run script.js
k6 run --vus 10 --iterations 100 script.js
k6 run -e BASE=http://127.0.0.1:8081 -e COURSE_ID=1 script.js
k6 run -q script.js                                   # 安静模式（不要进度刷新）
k6 run --summary-trend-stats "avg,p(90),p(95),p(99)" script.js

# 检查环境
k6 version

# 本仓库练习脚本
python3 perf-test/k6/tutorial/00-mock-server.py      # 假接口
k6 run perf-test/k6/tutorial/01-hello.js
# 02 ~ 06 同理
```

## 附录 B：术语对照

| 英文 | 中文 | 一句话 |
|---|---|---|
| VU (virtual user) | 虚拟用户 | 并发跑脚本的假用户 |
| iteration | 迭代 | 一次 `default()` 执行 |
| scenario | 场景 | 一组执行策略，可以多个并行 |
| executor | 执行器 | 决定 VU 怎么变化（shared-iterations / constant-vus / ramping-vus …） |
| check | 校验 | 断言，失败不中断 |
| threshold | 阈值 | 决定退出码和"过没过" |
| metric | 指标 | 内置或自定义的统计量 |
| trend / counter / rate / gauge | 趋势/计数/比例/瞬时值 | 四种指标类型 |
| graceful stop | 优雅停止 | 等正在进行的 iteration 收尾 |
