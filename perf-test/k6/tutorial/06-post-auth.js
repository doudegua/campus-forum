// 第 6 课：带登录态的 POST + 业务状态分类 + 用 open() 读 token 文件。
//
// 这一课基本就是项目脚本 perf-test/k6/course-grab.js 的简化版。
//
// 准备 token 文件（假服务只检查 "Bearer " 前缀，所以随便一行字都能过；
// 真后端要真 JWT，见第 8 课）：
//   printf 'mock-token-1\nmock-token-2\nmock-token-3\n' > /tmp/perftest/tokens.txt
//
// 运行：
//   /tmp/k6bin/k6 run \
//     -e BASE=http://127.0.0.1:18080 \
//     -e TOKENS=/tmp/perftest/tokens.txt \
//     perf-test/k6/tutorial/06-post-auth.js
//
// 三个新东西：
//   1. open()：读本地文件。只能在 init 阶段（最外层）调用，不能在 default() 里调。
//      所以 token 文件是"启动时读一次、所有 VU 共享"。
//   2. exec.scenario.iterationInTest：全局第几次 iteration，拿它当 token 下标。
//   3. 业务状态分类：项目的接口 HTTP 永远 200，成败在 body 里，
//      所以不能只看 http_req_failed，要自己数 body.data.status。
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://127.0.0.1:18080';
const TOKENS_FILE = __ENV.TOKENS;
if (!TOKENS_FILE) {
  throw new Error('缺少 -e TOKENS=<token 文件路径>');
}

// init 阶段读文件：整个测试只读一次
const tokens = open(TOKENS_FILE)
  .split('\n')
  .map((s) => s.trim())
  .filter(Boolean);
if (tokens.length === 0) {
  throw new Error(`token 文件是空的：${TOKENS_FILE}`);
}

export const options = {
  vus: 3,
  iterations: 9,
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

// 按业务状态分别计数，最后在 summary 里逐个看
const queued = new Counter('queued');
const other = new Counter('other');

export default function () {
  // 每个 iteration 换一个 token；超过 token 数会从头轮转
  const idx = exec.scenario.iterationInTest;
  const token = tokens[idx % tokens.length];

  const res = http.post(
    `${BASE}/api/course/select`,
    JSON.stringify({ courseId: 1 }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      // 给请求起个名字：summary 里 http_req_duration{name:course-select} 就按它聚合
      tags: { name: 'course-select' },
    },
  );

  // 先确认拿到的是 JSON，再解析——防止 URL 打错、打到了前端 index.html 还当成功
  const isJson = check(res, {
    'content-type is json': (r) =>
      (r.headers['Content-Type'] || '').includes('application/json'),
  });
  if (!isJson) {
    other.add(1);
    return;
  }

  const body = res.json();
  check(res, {
    '业务 code 是 200': () => body.code === 200,
    '状态是已知值': () =>
      ['QUEUED', 'SUCCESS', 'SOLD_OUT', 'DUPLICATE', 'NOT_READY', 'NOT_STARTED', 'CLOSED'].includes(
        body.data && body.data.status,
      ),
  });

  switch (body.data && body.data.status) {
    case 'QUEUED':
      queued.add(1);
      break;
    default:
      other.add(1);
  }
}
