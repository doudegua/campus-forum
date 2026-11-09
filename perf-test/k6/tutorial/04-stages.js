// 第 4 课：阶梯加压（ramping-vus）—— 压测真正常用的形状。
//
// 运行：
//   /tmp/k6bin/k6 run perf-test/k6/tutorial/04-stages.js
//
// 为什么要阶梯：一次固定 100 VU 只能告诉你"100 VU 时行不行"；
// 阶梯能告诉你"加到多少个 VU 开始劣化"，那才叫容量。
//
// stages 是 VU 数量曲线，每段从上一段的 target 线性爬/降到本段 target：
//
//   VU
//   5 |    ┌────┐
//   3 |   /      \
//   0 |__/        \__  (最后 gracefulRampDown 收尾)
//     +-------------> 时间 3s  3s  2s
import http from 'k6/http';
import { sleep } from 'k6';

const BASE = __ENV.BASE || 'http://127.0.0.1:18080';

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '3s', target: 5 }, // 3 秒内从 0 爬到 5 个 VU
        { duration: '3s', target: 5 }, // 保持 5 个 VU
        { duration: '2s', target: 0 }, // 2 秒内降到 0，脚本自然结束
      ],
      gracefulRampDown: '1s', // 降 VU 时给正在进行的 iteration 1 秒收尾
    },
  },
};

export default function () {
  http.get(`${BASE}/api/course/list`);
  sleep(0.5);
}
