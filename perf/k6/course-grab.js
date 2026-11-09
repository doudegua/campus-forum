// ===========================================================================
// course-grab.js —— 抢课接口压测（正确性 + 吞吐）
//
// 被测接口：POST /api/course/select   body: {"courseId": N}
//
// 这个脚本要回答的不是"有多快"，而是**"并发下账平不平"**：
//   1000 个名额、5000 个人同时抢 → db_course_selection 里必须**恰好** 1000 行，
//   不能 1001（超卖），也不能 999（少卖）。
//
// ## 为什么用 per-vu-iterations 而不是 constant-vus
//
// 抢课的现实模型是「每个学生只抢一次」：
//   同一个 uid 抢第二次会被 Redis 的 SADD 去重挡成 DUPLICATE，
//   那不是压测，是测去重逻辑。
// 所以一个 VU 只跑一次迭代，每个 VU 用一个不同的 token。
// 想要 5000 人抢，就 VUS=5000 TOKENS 里有 5000 个不同用户。
//
// ## 各状态的期望值（这是本脚本的核心断言）
//
//   QUEUED      == capacity      （真的抢到的人数，一个不能多、一个不能少）
//   SOLD_OUT    == VUS - capacity（没抢到的）
//   DUPLICATE   == 0             （token 没重复时）
//   NOT_STARTED == 0             （预热做过、时间窗有效时）
//
// 任何一项不符，就说明 Redis 预扣那一段有问题。
// 注意 QUEUED 只代表"进了队列"，最终是否落库要看 DB 行数 ——
// 所以还要配合 course-grab-test.sh 做落库后的核对。
//
// ## 环境变量
//
//   BASE       默认 http://localhost
//   VUS        并发用户数（= 每个用户抢一次，所以也就是总请求数）
//   COURSE_ID  目标课程 id
//   TOKENS     token 文件，一行一个，必须**互不相同**（每人一个用户）
// ===========================================================================

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const BASE = __ENV.BASE || 'http://localhost';
const VUS = parseInt(__ENV.VUS || '100');
const COURSE_ID = parseInt(__ENV.COURSE_ID || '1');
const TOKENS_PATH = __ENV.TOKENS || '/scripts/tokens.txt';

// 按业务状态分开计数，最后看每个状态各有多少
const cQueued = new Counter('grab_queued');
const cSoldOut = new Counter('grab_sold_out');
const cDuplicate = new Counter('grab_duplicate');
const cNotStarted = new Counter('grab_not_started');
const cOther = new Counter('grab_other');

const TOKENS = new SharedArray('tokens', function () {
    return open(TOKENS_PATH)
        .split('\n')
        .map(function (s) { return s.trim(); })
        .filter(function (s) { return s.length > 0; });
});

export const options = {
    scenarios: {
        grab: {
            // 每个 VU 跑 1 次就结束：模拟"N 个学生各抢一次"
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '3m',
        },
    },
    // 不设阈值：这次目的不是拦截，是收集分布。
    // 想让它当回归测试用，把下面打开：
    // thresholds: { 'grab_duplicate': ['count==0'] },
};

export default function () {
    // 每个 VU 拿自己那一个 token。VU 编号从 __VU 来（1 起），
    // 用取模防止 VUS 大于 token 数时越界 —— 那种情况下会出现 DUPLICATE，
    // 属于配置错误，不掩盖它。
    const token = TOKENS[(__VU - 1) % TOKENS.length];

    const res = http.post(
        BASE + '/api/course/select',
        JSON.stringify({ courseId: COURSE_ID }),
        {
            headers: {
                'Content-Type': 'application/json',
                'Authorization': 'Bearer ' + token,
            },
            tags: { name: 'course-select' },
        },
    );

    let ok = false;
    let status = 'PARSE_FAIL';

    try {
        const body = res.json();
        // 这个项目的约定：HTTP 可能是 200，业务码在 body.code 里。
        // QUEUED 不是错误，body.code 仍是 200。
        if (body && body.data && body.data.status) {
            status = body.data.status;
        } else if (body && body.code && body.code !== 200) {
            // 401 / 403 / 429 之类，没有 data
            status = 'HTTP_' + body.code;
        }
        ok = res.status === 200;
    } catch (e) {
        status = 'PARSE_FAIL_HTTP_' + res.status;
    }

    check(res, {
        'http 200': function (r) { return r.status === 200; },
        '有 status 字段': function () { return status !== 'PARSE_FAIL'; },
    });

    if (status === 'QUEUED') cQueued.add(1);
    else if (status === 'SUCCESS') cQueued.add(1); // 同步落库的情况也算抢到
    else if (status === 'SOLD_OUT') cSoldOut.add(1);
    else if (status === 'DUPLICATE') cDuplicate.add(1);
    else if (status === 'NOT_STARTED') cNotStarted.add(1);
    else cOther.add(1);

    // 方便在 --verbose 下看单条结果
    if (__ENV.DEBUG === '1') {
        console.log('vu=' + __VU + ' status=' + status + ' http=' + res.status);
    }
}
