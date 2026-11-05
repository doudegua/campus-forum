import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

/**
 * 抢课接口正确性/吞吐压测。
 *
 * 用法：
 *   COURSE_ID 必填；TOKENS 每行一个 JWT；ITERATIONS 默认 500，VUS 默认 100。
 *   每个 iteration 用 iterationInTest 取一个不同 token（超过 token 数会轮转，
 *   轮转后重复用户会返回 DUPLICATE，这是预期，不是错误）。
 *
 * 注意：接口 HTTP 永远是 200，业务状态在 body.data.status 里，
 * 所以不能只看 http_req_failed。
 */
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

export const options = {
  scenarios: {
    grab: grabScenario,
  },
  thresholds: {
    // 401/5xx 才算失败；业务上的 SOLD_OUT/DUPLICATE 仍是 200
    http_req_failed: ['rate<0.01'],
    // 防止 URL 拼错打到 nginx SPA 回退、返回 index.html 被当成成功
    checks: ['rate>0.99'],
  },
};

const accepted = new Counter('accepted');
const soldOut = new Counter('sold_out');
const duplicate = new Counter('duplicate');
const notReady = new Counter('not_ready');
const notStarted = new Counter('not_started');
const closed = new Counter('closed');
const other = new Counter('other');

const base = (__ENV.BASE || 'http://localhost').replace(/\/$/, '');
const courseId = __ENV.COURSE_ID;
if (!courseId) {
  throw new Error('缺少 -e COURSE_ID=<课程 id>');
}

const tokenFile = __ENV.TOKENS;
if (!tokenFile) {
  throw new Error('缺少 -e TOKENS=<token 文件>');
}
const tokens = open(tokenFile)
  .split('\n')
  .map((s) => s.trim())
  .filter(Boolean);
if (tokens.length === 0) {
  throw new Error('token 文件为空');
}

export default function () {
  // 全局唯一序号，保证同一轮里每个 iteration 用不同 token（轮转除外）
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

  const isJson = check(res, {
    'content-type is json': (r) =>
      (r.headers['Content-Type'] || '').includes('application/json'),
  });
  if (!isJson) {
    other.add(1);
    return;
  }

  let body;
  try {
    body = res.json();
  } catch (e) {
    other.add(1);
    return;
  }

  if (body.code !== 200) {
    other.add(1);
    return;
  }

  switch (body.data && body.data.status) {
    case 'QUEUED':
      accepted.add(1);
      break;
    case 'SOLD_OUT':
      soldOut.add(1);
      break;
    case 'DUPLICATE':
      duplicate.add(1);
      break;
    case 'NOT_READY':
      notReady.add(1);
      break;
    case 'NOT_STARTED':
      notStarted.add(1);
      break;
    case 'CLOSED':
      closed.add(1);
      break;
    default:
      other.add(1);
  }
}
