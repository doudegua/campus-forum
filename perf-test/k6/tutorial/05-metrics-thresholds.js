// 第 5 课：自定义指标 + threshold（阈值）—— 让压测自己判"过不过"。
//
// 运行：
//   /tmp/k6bin/k6 run perf-test/k6/tutorial/05-metrics-thresholds.js
//   echo "退出码=$?"     # 你会看到 99：有 threshold 没过
//
// k6 内置四种指标：
//   Counter  只增不减的计数（发了多少个请求）
//   Trend    记录一组数值，最后算 avg/min/med/max/p(90)/p(95)（延迟、响应大小）
//   Rate     0/1 比例（失败率、通过率）
//   Gauge    只保留最后一个值（当前队列长度之类，用得少）
//
// threshold 的用法：写在 options.thresholds 里，[指标名]: ['表达式']。
// 不看打印、直接看退出码就知道压测过没过——这是 k6 能塞进 CI 的原因。
//   退出码 0  = 全部 threshold 通过
//   退出码 99 = 至少一个 threshold 失败（数字仍会打印，方便定位）
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://127.0.0.1:18080';

// 指标名自己起，跑起来的 summary 里就会出现同名行
const selectCalls = new Counter('select_calls'); // 计"我处理了几次"
const selectLatency = new Trend('select_latency'); // 单独统计 list 接口耗时
const slowLatency = new Trend('slow_latency'); // 单独统计慢接口耗时
const jsonOk = new Rate('json_ok'); // content-type 是不是 JSON

export const options = {
  vus: 2,
  iterations: 4,
  thresholds: {
    // 内置指标：HTTP 层失败率（4xx/5xx、超时、连接失败）低于 1%
    http_req_failed: ['rate<0.01'],
    // 自定义 Trend：这个 p95 能过（mock 的 list 只睡 20ms）
    select_latency: ['p(95)<300'],
    // 自定义 Rate：能过
    json_ok: ['rate>0.99'],
    // ↓ 故意会失败：mock 的 /api/course/slow 固定睡 500ms，p95 必然 > 300ms
    slow_latency: ['p(95)<300'],
  },
};

export default function () {
  const fast = http.get(`${BASE}/api/course/list`);
  selectCalls.add(1);
  selectLatency.add(fast.timings.duration);
  jsonOk.add((fast.headers['Content-Type'] || '').includes('application/json'));
  check(fast, { 'list 是 200': (r) => r.status === 200 });

  const slow = http.get(`${BASE}/api/course/slow`);
  slowLatency.add(slow.timings.duration);
  check(slow, { 'slow 是 200': (r) => r.status === 200 });
}
