// 第 2 课：check —— 让脚本自己判断"这次请求对不对"。
//
// 运行：
//   /tmp/k6bin/k6 run perf-test/k6/tutorial/02-checks.js
//
// 重点：
//   1. check 失败不会中断脚本，也不会让 k6 退出码变 99（那是 threshold 的事）；
//      它只把"通过/失败"记进 checks 指标，summary 里能看到通过率。
//   2. 一条请求可以写多个断言，最后统计的是所有断言的总通过率。
//   3. 下面第 4 条是故意写的"必失败"断言，用来让你看到 checks 不是 100% 时长什么样。
//      看完把第 4 条删掉，再跑一次，checks 就变 100% 了。
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://127.0.0.1:18080';

// 这一课故意写死 3 个 VU、共 9 次，方便你数 checks 的总数：9 次请求 × 4 条断言 = 36
export const options = {
  vus: 3,
  iterations: 9,
};

export default function () {
  const res = http.get(`${BASE}/actuator/health`);

  check(res, {
    'HTTP 200': (r) => r.status === 200,
    'Content-Type 是 JSON': (r) =>
      (r.headers['Content-Type'] || '').includes('application/json'),
    'body.status 是 UP': (r) => r.json('status') === 'UP',

    // ↓↓↓ 故意失败：这个接口的 body 是 {"status":"UP"}，没有 READY ↓↓↓
    '故意失败：body 里有 READY': (r) => (r.body || '').includes('READY'),
  });
}
