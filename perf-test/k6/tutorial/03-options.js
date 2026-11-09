// 第 3 课：VU、iteration、duration 到底什么关系。
//
// 运行（换档位不用改文件，命令行 -e 传参）：
//   /tmp/k6bin/k6 run -e VUS=3 -e ITERATIONS=6 perf-test/k6/tutorial/03-options.js
//   /tmp/k6bin/k6 run -e VUS=3 -e DURATION=5s  perf-test/k6/tutorial/03-options.js
//
// 一句话：
//   VU      = 并发执行 default() 的"虚拟用户"数量
//   iteration = default() 被执行一次
//   吞吐 ≈ VU 数 ÷ 单次 iteration 的平均耗时（利特尔法则）
//
// 两种最常见的压法：
//   vus + iterations -> shared-iterations：总共跑 N 次，由 VU 抢着做完
//   vus + duration   -> constant-vus：VU 数固定，跑固定时长，能跑多少次算多少次
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';

const BASE = __ENV.BASE || 'http://127.0.0.1:18080';
const VUS = Number(__ENV.VUS || 2);

export const options = __ENV.DURATION
  ? { vus: VUS, duration: __ENV.DURATION }
  : { vus: VUS, iterations: Number(__ENV.ITERATIONS || 4) };

export default function () {
  // exec.vu.idInTest：当前 VU 的编号，从 1 开始
  // exec.scenario.iterationInTest：整个 scenario 里第几次 iteration，从 0 开始
  //（注意是"全局第几次"，不是"这个 VU 的第几次"——所以这俩数不是一回事）
  const vu = exec.vu.idInTest;
  const seq = exec.scenario.iterationInTest;

  http.get(`${BASE}/api/course/list`);
  console.log(`VU ${vu} 执行第 ${seq + 1} 次 iteration`);

  sleep(0.5);
}
