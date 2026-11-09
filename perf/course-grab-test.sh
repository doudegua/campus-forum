#!/usr/bin/env bash
# ===========================================================================
# course-grab-test.sh —— 抢课压测的一键编排 + 正确性核对
#
# 它做五件事，顺序不能乱：
#   ① 重置      —— 清掉上一轮的选课记录 / 计数 / Redis 键
#   ② 预热      —— 让 Redis 里的 stock 等于 capacity
#   ③ 压测      —— 跑 k6
#   ④ 等落库    —— MQ 是异步的，压测结束时 DB 里还不全
#   ⑤ 核对      —— COUNT(*) 必须**恰好等于** capacity
#
# ## 为什么④不能省
#
# 抢课是「Redis 预扣 → 发 MQ → 消费者落库」三段式。
# k6 打完时，Redis 那边已经定了成败，但 DB 里可能只落了一半。
# 这时候直接 COUNT，会看到"抢到了 400 个"这种假象，
# 然后你会以为是少卖 —— 其实只是消费者还没追上。
#
# 所以这里轮询等到「连续两次计数不再变化」，再核对。
#
# ## 用法
#
#   ./perf/course-grab-test.sh <courseId> <vus>
#
# 例：50 个名额、2000 人抢
#   ./perf/course-grab-test.sh 1 2000
#
# 前提：
#   - 在项目根目录跑（要能 docker compose exec 到 mysql / redis）
#   - 根目录 .env 里有 DB_NAME / DB_USERNAME / DB_PASSWORD
#   - 已有 scripts/gen_tokens.py 和 k6 镜像
# ===========================================================================

set -euo pipefail

COURSE_ID="${1:?用法: $0 <courseId> <vus>}"
VUS="${2:?用法: $0 <courseId> <vus>}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# 和 reset-demo.sh 同一套读取方式：不 source .env，避免密码里的 $ 被 shell 展开
DB_NAME="${DB_NAME:-$(grep -E '^DB_NAME=' .env 2>/dev/null | cut -d= -f2- || echo test)}"
DB_USERNAME="${DB_USERNAME:-$(grep -E '^DB_USERNAME=' .env 2>/dev/null | cut -d= -f2- || echo root)}"
DB_PASSWORD="${DB_PASSWORD:-$(grep -E '^DB_PASSWORD=' .env 2>/dev/null | cut -d= -f2- || echo '')}"

sql() {
  docker compose exec -T -e MYSQL_PWD="$DB_PASSWORD" mysql \
    mysql --default-character-set=utf8mb4 -u "$DB_USERNAME" "$DB_NAME" -N -B -e "$1"
}

redis_del() {
  docker compose exec -T redis redis-cli --scan --pattern "$1" 2>/dev/null \
    | tr -d '\r' | while read -r k; do [ -n "$k" ] && docker compose exec -T redis redis-cli DEL "$k" >/dev/null; done
}

ts() { date '+%H:%M:%S'; }

echo "=============================================================="
echo " 抢课压测  courseId=$COURSE_ID  vus=$VUS"
echo "=============================================================="

# ---------- 读 capacity ----------
CAPACITY="$(sql "SELECT capacity FROM db_course WHERE id=$COURSE_ID")"
if [ -z "$CAPACITY" ]; then
  echo "❌ 课程 $COURSE_ID 不存在"; exit 1
fi
echo "[$(ts)] 课程名额 capacity = $CAPACITY"

# ---------- ① 重置 ----------
echo "[$(ts)] ① 重置上一轮状态"
sql "DELETE FROM db_course_selection WHERE course_id=$COURSE_ID;
     UPDATE db_course SET selected_count=0 WHERE id=$COURSE_ID;" >/dev/null
redis_del "course:stock:$COURSE_ID"
redis_del "course:chosen:$COURSE_ID"
redis_del "course:result:$COURSE_ID:*"
echo "[$(ts)]    已清空选课记录 / 计数 / Redis"

# ---------- ② 预热 ----------
echo "[$(ts)] ② 预热（把 stock 设成 $CAPACITY）"
# 预热接口在 /api/admin/** 下，需要 ROLE_ADMIN 的 token。
# 这里现签一个：gen_tokens.py 只签 JWT，不查库，uid 给管理员那个。
ADMIN_UID="${ADMIN_UID:-4}"
mkdir -p /tmp/perfgrab
python3 scripts/gen_tokens.py "$ADMIN_UID" 1 /tmp/perfgrab/admin.txt ROLE_ADMIN >/dev/null 2>&1 || {
  echo "❌ 签 admin token 失败：检查 scripts/gen_tokens.py 和 .env 里的 JWT_KEY"; exit 1
}
ADMIN_TOKEN="$(head -1 /tmp/perfgrab/admin.txt)"

PREHEAT_RESP="$(curl -s --max-time 20 -X POST \
  -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d "[{\"courseId\":$COURSE_ID}]" \
  "http://localhost/api/admin/course/preheat")"
echo "[$(ts)]    预热响应: $(echo "$PREHEAT_RESP" | head -c 200)"
if ! echo "$PREHEAT_RESP" | grep -q "OPENED"; then
  echo "❌ 预热没成功（期望 OPENED）。常见原因：不在选课时间窗内，或 Redis 连接失败。"
  exit 1
fi

# 确认 Redis 里的 stock 真的是 capacity
STOCK="$(docker compose exec -T redis redis-cli HGET "course:stock:$COURSE_ID" ready 2>/dev/null | tr -d '\r')"
echo "[$(ts)]    Redis ready=$STOCK"

# ---------- ③ 压测 ----------
echo "[$(ts)] ③ 准备 $VUS 个不同用户的 token"
# uid 从 500 起，避开 demo / 测试账号
python3 scripts/gen_tokens.py 500 "$VUS" /tmp/perfgrab/tokens.txt >/dev/null
echo "[$(ts)]    已生成 $(wc -l < /tmp/perfgrab/tokens.txt | tr -d ' ') 个 token"

echo "[$(ts)] ③ 跑 k6"
docker run --rm --network host \
  -v /tmp/perfgrab:/scripts \
  -v "$ROOT/perf/k6:/k6" \
  -e BASE=http://localhost -e VUS="$VUS" -e COURSE_ID="$COURSE_ID" \
  grafana/k6 run /k6/course-grab.js 2>&1 | tail -40

# ---------- ④ 等异步落库 ----------
echo "[$(ts)] ④ 等消费者把队列消化完"
pre=0; stable=0
for i in $(seq 1 60); do
  cur="$(sql "SELECT COUNT(*) FROM db_course_selection WHERE course_id=$COURSE_ID")"
  if [ "$cur" = "$pre" ]; then
    stable=$((stable + 1))
  else
    stable=0
  fi
  pre="$cur"
  printf "\r[$(ts)]    已落库 %s / %s 行（连续稳定 %s 次）" "$cur" "$CAPACITY" "$stable"
  if [ "$stable" -ge 3 ]; then echo; break; fi
  sleep 2
done

# ---------- ⑤ 核对 ----------
echo
echo "=============================================================="
echo " 核对"
echo "=============================================================="
ROWS="$(sql "SELECT COUNT(*) FROM db_course_selection WHERE course_id=$COURSE_ID")"
COUNT_COL="$(sql "SELECT selected_count FROM db_course WHERE id=$COURSE_ID")"
DUP="$(sql "SELECT COUNT(*) FROM (SELECT uid, COUNT(*) c FROM db_course_selection WHERE course_id=$COURSE_ID GROUP BY uid HAVING c>1) t")"

echo "  capacity（名额）      : $CAPACITY"
echo "  抢到的人数（明细行）  : $ROWS"
echo "  selected_count（计数）: $COUNT_COL"
echo "  同一人多条记录        : $DUP"
echo

fail=0
if [ "$ROWS" = "$CAPACITY" ]; then
  echo "  ✅ 零超卖：恰好 $CAPACITY 行，一行不多一行不少"
else
  echo "  ❌ 账不平：期望 $CAPACITY 行，实际 $ROWS 行"
  fail=1
fi
if [ "$COUNT_COL" = "$ROWS" ]; then
  echo "  ✅ 计数列与明细一致（$COUNT_COL = $ROWS）"
else
  echo "  ❌ 计数列与明细不一致：selected_count=$COUNT_COL，明细=$ROWS"
  fail=1
fi
if [ "$DUP" = "0" ]; then
  echo "  ✅ 没有同一人抢到多次"
else
  echo "  ❌ 有 $DUP 个人拿到了多条记录"
  fail=1
fi

echo
if [ "$fail" = "0" ]; then
  echo " 结论：$VUS 人抢 $CAPACITY 个名额，零超卖、零重复。"
else
  echo " 结论：有断言没过，往上翻 k6 输出看 QUEUED / SOLD_OUT 分布。"
fi
exit "$fail"
