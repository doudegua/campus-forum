#!/usr/bin/env bash
# ===========================================================================
# verify-schema.sh —— 验证 schema.sql 建出来的表结构和真实数据库一致
#
# 解决什么问题：
#   schema.sql 里那几张表的定义是照着真实库写的。但"照着写"这件事
#   如果只有写的人自己核对过，别人（包括三个月后的你）没法复核。
#   这个脚本把两边的列定义都导成文本再 diff —— 机器对比，不靠肉眼。
#
# 怎么判断结果：
#   输出里应该**只有你刻意改过的地方**。任何意外差异都是 bug，
#   因为那意味着"新库和线上库结构不一样"，症状会是某台机器上某个功能莫名其妙地坏。
#
# 用法：
#   ./scripts/verify-schema.sh                    # 对比 test 库
#   ./scripts/verify-schema.sh my_other_db        # 对比指定库
#   ./scripts/verify-schema.sh test keep          # 保留临时库，方便进去手动查
#
# 它做了什么（为什么是这样）：
#   1. 建一个临时库（名字带 tmp，一眼看得出是垃圾）
#   2. 把 schema.sql 喂进去 —— 这正是"全新数据库初始化"的真实过程
#   3. 对每张表，分别从两个库里查 information_schema 并排序输出
#   4. diff
#   5. 删掉临时库
#
# 只对比**结构**（列名/类型/可空/默认值/键），不对比列的 COMMENT：
#   注释是给人看的文字，改一个字不该让验证失败。
#   而类型、可空性、默认值这些改一个字就是真 bug。
# ===========================================================================
set -u

REAL_DB="${1:-test}"
KEEP_TMP="${2:-}"

MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
TMP_DB="schema_verify_tmp"

# 脚本在 backend/scripts/ 下，schema.sql 在 backend/src/main/resources/db/
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCHEMA="$SCRIPT_DIR/../src/main/resources/db/schema.sql"

MYSQL="mysql -u$MYSQL_USER -h$MYSQL_HOST -P$MYSQL_PORT"

# -p 只在需要密码时加。本机是 root 空密码，所以默认不加
if [ -n "${MYSQL_PASSWORD:-}" ]; then
    MYSQL="$MYSQL -p$MYSQL_PASSWORD"
fi

cleanup() {
    if [ "$KEEP_TMP" = "keep" ]; then
        echo ""
        echo "临时库 $TMP_DB 已保留（你要求 keep）。手动清掉："
        echo "    mysql -u$MYSQL_USER -e 'DROP DATABASE $TMP_DB;'"
    else
        $MYSQL -e "DROP DATABASE IF EXISTS $TMP_DB;" 2>/dev/null
    fi
}
trap cleanup EXIT

if [ ! -f "$SCHEMA" ]; then
    echo "找不到 schema.sql：$SCHEMA" >&2
    exit 1
fi

# 真实库存在吗？不存在就没法对比，早点说清楚
if ! $MYSQL -e "USE \`$REAL_DB\`;" 2>/dev/null; then
    echo "连不上数据库 '$REAL_DB'（或者它不存在）。" >&2
    echo "检查一下 MySQL 起没起、库名对不对：" >&2
    echo "    mysql -u$MYSQL_USER -e 'SHOW DATABASES;'" >&2
    exit 1
fi

echo "对比库：$REAL_DB   ←→   schema.sql 新建的库"
echo ""

# --- 1. 建临时空库 ---------------------------------------------------------
$MYSQL -e "DROP DATABASE IF EXISTS $TMP_DB; CREATE DATABASE $TMP_DB DEFAULT CHARSET utf8mb4;" || exit 1

# --- 2. 喂 schema.sql ------------------------------------------------------
if ! $MYSQL "$TMP_DB" < "$SCHEMA"; then
    echo "schema.sql 执行失败 —— 它自己就有问题，不用往下比了。" >&2
    exit 1
fi

# --- 3. 逐表对比 -----------------------------------------------------------
# 从真实库拿表清单，而不是从 schema.sql 拿：
# 这样如果 schema.sql **漏了**某张表，也会被发现（反向检查）
TABLES=$($MYSQL -N -e \
    "SELECT TABLE_NAME FROM information_schema.TABLES
     WHERE TABLE_SCHEMA='$REAL_DB' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;")

if [ -z "$TABLES" ]; then
    echo "库 $REAL_DB 里一张表都没有？那没什么可比的。" >&2
    exit 1
fi

# 每列输出：列名 | 类型 | 可空 | 默认值 | 键
column_query() {
    cat <<SQL
SELECT CONCAT_WS(' | ', COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE,
                 IFNULL(COLUMN_DEFAULT, 'NULL'), COLUMN_KEY)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA='$1' AND TABLE_NAME='$2'
ORDER BY COLUMN_NAME;
SQL
}

TMPDIR_V="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_V"; cleanup' EXIT

DIFF_COUNT=0
MISSING_COUNT=0

for t in $TABLES; do
    # schema.sql 里根本没建这张表 —— 这是最严重的情况
    exists=$($MYSQL -N -e \
        "SELECT COUNT(*) FROM information_schema.TABLES
         WHERE TABLE_SCHEMA='$TMP_DB' AND TABLE_NAME='$t';")
    if [ "$exists" = "0" ]; then
        echo "❌ $t —— schema.sql 里**没有**这张表（真实库里有）"
        MISSING_COUNT=$((MISSING_COUNT + 1))
        continue
    fi

    $MYSQL -N -e "$(column_query "$REAL_DB" "$t")" > "$TMPDIR_V/real.txt"
    $MYSQL -N -e "$(column_query "$TMP_DB" "$t")"  > "$TMPDIR_V/new.txt"

    if diff -q "$TMPDIR_V/real.txt" "$TMPDIR_V/new.txt" > /dev/null; then
        echo "✅ $t"
    else
        echo "⚠️  $t —— 有差异（< 真实库 / > schema.sql）"
        diff "$TMPDIR_V/real.txt" "$TMPDIR_V/new.txt" | sed 's/^/      /'
        DIFF_COUNT=$((DIFF_COUNT + 1))
    fi
done

# 反向：schema.sql 建了、真实库没有的表。不是错，但要知道
EXTRA=$($MYSQL -N -e \
    "SELECT TABLE_NAME FROM information_schema.TABLES
     WHERE TABLE_SCHEMA='$TMP_DB' AND TABLE_TYPE='BASE TABLE'
       AND TABLE_NAME NOT IN (SELECT TABLE_NAME FROM information_schema.TABLES
                              WHERE TABLE_SCHEMA='$REAL_DB' AND TABLE_TYPE='BASE TABLE');")
if [ -n "$EXTRA" ]; then
    echo ""
    echo "ℹ️  这些表在 schema.sql 里有、$REAL_DB 里没有（通常是新加的功能，正常）："
    for t in $EXTRA; do echo "      $t"; done
fi

echo ""
echo "─────────────────────────────────────────────"
if [ "$MISSING_COUNT" -gt 0 ]; then
    echo "结果：有 $MISSING_COUNT 张表漏了 —— 全新库会缺表，必须补进 schema.sql"
    exit 1
elif [ "$DIFF_COUNT" -gt 0 ]; then
    echo "结果：$DIFF_COUNT 张表有差异。"
    echo "      **逐条确认每一处是不是你刻意改的**。不是刻意改的，就是 bug。"
    exit 1
else
    echo "结果：全部一致。schema.sql 能完整重建 $REAL_DB 的结构。"
fi
