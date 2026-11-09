// 第 1 课：最小的 k6 脚本。
//
// 运行：
//   /tmp/k6bin/k6 run perf-test/k6/tutorial/01-hello.js
//
// 不写 options 时，k6 默认 1 个 VU 跑 1 次 default()。
// 一个 VU = 一个"虚拟用户"，它把 default() 从头到尾执行一遍叫一次 iteration。
import http from 'k6/http';
import { sleep } from 'k6';

// __ENV 是 k6 注入的环境变量对象。`k6 run -e BASE=http://xxx` 传进来。
// 给个默认值，这样不带参数也能跑。
const BASE = __ENV.BASE || 'http://127.0.0.1:18080';

export default function () {
  const res = http.get(`${BASE}/actuator/health`);

  // console.log 会打到终端。正式压测别留（几千个 VU 会刷屏，还影响性能）。
  console.log(`状态码=${res.status} 响应体=${res.body}`);

  // 睡 1 秒，模拟"用户看完页面再点一下"。
  // 压测里 sleep 决定了一个 VU 每秒能发多少请求，删掉它可以压出更高 QPS。
  sleep(1);
}
