#!/usr/bin/env bash
# ===========================================================================
# reset-demo.sh —— 每日重置「访客演示账号」产生的数据
#
# 为什么需要每天重置：
#   公网陌生人共用 demo 这一个账号，一天下来会留下帖子、评论、点赞、选课记录。
#   不清理的话，第二天来的访客看到的是上一批人留下的内容；选课人数也会越积越多。
#   这个脚本把所有"demo 亲手产生"的东西清掉，让站点回到干净的演示状态。
#
# 做什么：
#   1. 删 demo 的帖子、评论、点赞、选课记录（连带别人挂在 demo 帖子下的评论/点赞，
#      否则帖子删了它们会变成孤儿行）
#   2. db_course.selected_count 归零
#   3. 清 Redis 的 course:stock:* / course:chosen:* / course:result:*（抢课在途状态）
#   4. 兜底把 demo 账号恢复成演示值（用户名 demo、邮箱 demo@example.com、
#      角色 USER、密码 demo123 的 BCrypt 哈希）
#      —— 现有资料编辑接口允许改用户名，这一步能把被改坏的登录名改回来
#
# 怎么用（在项目根目录执行；脚本自己会 cd 到根目录）：
#   ./scripts/reset-demo.sh
#
# cron 示例（每天凌晨 4 点；路径换成服务器上的实际路径）：
#   0 4 * * * cd /opt/my-project && ./scripts/reset-demo.sh >> /var/log/campus-forum-reset-demo.log 2>&1
#
# 连接方式：
#   和部署一致，走 `docker compose exec` 进 mysql / redis 容器，不额外配置账号密码。
#   数据库名、用户名、密码从项目根目录的 .env 读取（环境变量优先，可覆盖），
#   下面这几个变量都可以在环境里覆盖，便于在别的目录/别的 compose 项目里复用。
#
# ⚠️ 不要用 `docker compose run` 另起临时容器：那样会拿到一个新的网络命名空间，
#    连不上正在跑的 mysql/redis；`exec` 是进已有容器，行为才对。
# ===========================================================================

set -euo pipefail

# 解析脚本所在目录，保证从 cron（工作目录是 / 或 $HOME）调用也能找到 .env 和 compose 文件
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_DIR}"

# ---------------------------------------------------------------------------
# 配置（优先用环境变量，其次从项目根目录的 .env 读，最后用 compose 的默认值）
#
# 为什么不用 `source .env`：
#   .env 是给 docker compose 看的 KEY=VALUE 文本，不是 shell 脚本。
#   直接 source 会被 shell 解释：密码里如果有 `$`、反引号、空格，
#   轻则取到错误的值，重则执行一段意料之外的命令。
#   这里只按行解析需要的几个键，值原样保留，副作用最小。
# ---------------------------------------------------------------------------
read_env_value() {
    local name="$1" line value
    [[ -f "${PROJECT_DIR}/.env" ]] || return 0

    # 取最后一次出现的值（和 compose 的覆盖直觉一致），去掉行尾的 \r（Windows 编辑过）
    line="$(grep -E "^[[:space:]]*${name}[[:space:]]*=" "${PROJECT_DIR}/.env" | tail -n 1 || true)"
    [[ -n "${line}" ]] || return 0

    value="${line#*=}"
    value="${value%$'\r'}"
    # 去掉成对的引号（compose 的 .env 允许 "值" / '值'）
    if [[ ${#value} -ge 2 ]]; then
        case "${value}" in
            \"*\") value="${value:1:${#value}-2}" ;;
            \'*\') value="${value:1:${#value}-2}" ;;
        esac
    fi
    printf '%s' "${value}"
}

DB_NAME="${DB_NAME:-$(read_env_value DB_NAME)}"
DB_NAME="${DB_NAME:-test}"
DB_USERNAME="${DB_USERNAME:-$(read_env_value DB_USERNAME)}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-$(read_env_value DB_PASSWORD)}"
DB_PASSWORD="${DB_PASSWORD:-}"
MYSQL_SERVICE="${MYSQL_SERVICE:-mysql}"
REDIS_SERVICE="${REDIS_SERVICE:-redis}"

# demo 密码 demo123 的 BCrypt 哈希（强度 10，和项目里的 BCryptPasswordEncoder 一致）
# 直接写在下面 SQL 的字面量里，而不是先存成 shell 变量：
#   下面的 heredoc 用了带引号的 <<'SQL'（防止 SQL 注释里的反引号被 shell 当成
#   命令替换执行），带引号的 heredoc 不会展开 $ 变量，所以变量放进去反而会
#   原样变成 "$DEMO_PASSWORD_HASH" 这几个字，登录必然失败。
# ⚠️ 必须和 db/init/012-seed-demo-account.sql 里的值保持一致：
#    两处不一样时，"初始化"和"每日重置"会把密码改成不同的东西，表现是
#    "昨天还能访客体验，今天突然密码错误"。

log() {
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

die() {
    log "错误：$*" >&2
    exit 1
}

# ---------------------------------------------------------------------------
# 前置检查：docker / compose / 两个容器都得在跑
# ---------------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || die "找不到 docker 命令（cron 的 PATH 可能和登录 shell 不同，建议用绝对路径）"
docker compose version >/dev/null 2>&1 || die "docker compose 不可用（需要 Compose v2：docker compose，而不是 docker-compose）"

for svc in "${MYSQL_SERVICE}" "${REDIS_SERVICE}"; do
    # 用 exec 一条无害命令来判断服务是否真的在跑；比 `docker compose ps` 的
    # 输出格式判断更稳（不同 compose 版本的 --status 支持情况不一样）。
    docker compose exec -T "${svc}" sh -c true >/dev/null 2>&1 \
        || die "服务 ${svc} 未运行，或 docker compose 找不到它（先在项目根目录 docker compose up -d）"
done

# mysql 客户端封装。MYSQL_PWD 比 -p 参数好在：密码不会出现在进程列表里，
# 也不会触发 "Using a password on the command line interface can be insecure" 警告。
mysql_exec() {
    docker compose exec -T \
        -e MYSQL_PWD="${DB_PASSWORD}" \
        "${MYSQL_SERVICE}" \
        mysql --default-character-set=utf8mb4 -u "${DB_USERNAME}" "${DB_NAME}" "$@"
}

# ---------------------------------------------------------------------------
# 0. 先确认 demo 账号存在，拿到 uid 便于日志
#
# 用 username 或 email 两个条件找，而不是只认 username：
#   现有的 /api/user/profile 允许用户改用户名（UserSettings 里就有输入框），
#   所以公网访客完全可能把 demo 改成别的名字。一旦改名，只按 username='demo'
#   找就找不到，脚本会直接退出 —— 演示入口从此永久坏掉，直到人工介入。
#   邮箱 demo@example.com 没有任何入口能改，用它兜底才能找到这一行，
#   再由下面的 SQL 把 username 恢复成 demo。
# ---------------------------------------------------------------------------
DEMO_UID="$(mysql_exec -N -B -e "SELECT id FROM db_account WHERE username = 'demo' OR email = 'demo@example.com' LIMIT 1" | tr -d '[:space:]')"
[[ -n "${DEMO_UID}" ]] || die "db_account 里没有 username='demo' / email='demo@example.com' 的账号，请先执行 db/init/012-seed-demo-account.sql"
[[ "${DEMO_UID}" =~ ^[0-9]+$ ]] || die "查出来的 demo uid 不是数字：'${DEMO_UID}'"
log "开始重置演示数据（demo uid=${DEMO_UID}, db=${DB_NAME}）"

# ---------------------------------------------------------------------------
# 1 + 2 + 4. 清理 MySQL
#
# 为什么用 @demo_uid 变量而不是每条 SQL 都写
#   `uid = (SELECT id FROM db_account WHERE username='demo')`：
#   一次次重复查子查询没必要，而且给后面的维护者一个误解 ——
#   以为 MySQL 会缓存它。变量只算一次，语义也更像"这次操作的 demo 是谁"。
#
# ⚠️ MySQL 的 ERROR 1093（不能在 UPDATE/DELETE 的子查询里引用同一张目标表）
#   在这里不会发生：删除的目标是 db_topic/db_comment/db_topic_like/
#   db_course_selection，而 @demo_uid 早在单独的 SELECT 里取好了。
#   如果以后改成 `DELETE FROM db_account WHERE id = (SELECT ... FROM db_account ...)`，
#   就必须用派生表包一层（`(SELECT * FROM (...) AS t)`）——这不是本脚本要做的事。
# ---------------------------------------------------------------------------
mysql_exec >/dev/null <<'SQL'
SET @demo_uid := (SELECT id FROM db_account
                  WHERE username = 'demo' OR email = 'demo@example.com'
                  LIMIT 1);

-- 别人挂在 demo 帖子下的评论/点赞。必须先按 topic 删，再删帖子，
-- 否则 db_topic 行没了，这些行就成了永远查不到主人的孤儿（本项目没有外键）。
DELETE c FROM db_comment c
JOIN db_topic t ON t.id = c.topic_id
WHERE t.uid = @demo_uid;

DELETE l FROM db_topic_like l
JOIN db_topic t ON t.id = l.topic_id
WHERE t.uid = @demo_uid;

-- demo 自己产生的评论、点赞（可能挂在别人的帖子下）
DELETE FROM db_comment WHERE uid = @demo_uid;
DELETE FROM db_topic_like WHERE uid = @demo_uid;

-- demo 的帖子（上面的关联行已清）
DELETE FROM db_topic WHERE uid = @demo_uid;

-- demo 的选课记录。db_course.selected_count 不在这里减 —— 下一步统一重算。
DELETE FROM db_course_selection WHERE uid = @demo_uid;

-- 选课人数按明细表重算，而不是直接归零。
-- 直接 `SET selected_count = 0` 隐含一个假设：库里只有 demo 会选课。
-- 一旦出现真实用户的选课记录，清零就会把真实人数抹掉 —— 这就是超卖。
-- 重算的语义是"账实相符"：明细表里有多少行，计数列就是多少，
-- 对纯演示库和混有真实用户的库都成立。
UPDATE db_course c
SET selected_count = (
    SELECT COUNT(*) FROM db_course_selection s WHERE s.course_id = c.id
);

-- 直接删评论/点赞会绕过业务层对 comment_count / like_count 的增减，
-- 计数列会和实际行数对不上（帖子详情页显示"3 条评论"但一条都加载不出来）。
-- 演示库很小，这里全表重算一次，代价可以接受；真实大表应该只重算受影响的行。
UPDATE db_topic t
LEFT JOIN (SELECT topic_id, COUNT(*) AS cnt FROM db_comment GROUP BY topic_id) c
       ON c.topic_id = t.id
LEFT JOIN (SELECT topic_id, COUNT(*) AS cnt FROM db_topic_like GROUP BY topic_id) l
       ON l.topic_id = t.id
SET t.comment_count = COALESCE(c.cnt, 0),
    t.like_count    = COALESCE(l.cnt, 0);

-- 兜底：把登录名、邮箱、角色、密码全部恢复成演示值。
--
-- 只改 password 不够：访客能在 /api/user/profile 里改 username，
-- 一旦改成别的名字，前端写死的 demo 登录就再也进不来。
-- 邮箱/角色一起恢复，是为了守住"收不到验证码"和"不是管理员"这两条前提。
-- WHERE id = @demo_uid 而不是 WHERE username='demo'：
-- 这样即使这一行已经被改名，也能按上面查到的 id 精确改回来。
--
-- ⚠️ 如果已经有人注册了新的 'demo'（本地开发时可能），这条 UPDATE 会撞
--    username 唯一键并报错，cron 日志里能看到。这是需要人工处理的冲突，
--    不让脚本静默跳过 —— 静默跳过等于演示入口悄悄坏掉。
UPDATE db_account
SET username = 'demo',
    password = '$2a$10$hl.DfW8gXG16iMGkh3QiC.S3wc0hGWw/Noo7DxkGJ93RNgkyr37ce',
    email    = 'demo@example.com',
    role     = 'USER'
WHERE id = @demo_uid;
SQL

log "MySQL 清理完成：帖子/评论/点赞/选课记录已删，课程人数已归零，demo 账号已恢复"

# ---------------------------------------------------------------------------
# 3. 清 Redis 里的抢课在途状态
#
# 这些键不是数据库的镜像，而是"预热/预扣/结果缓存"：
#   course:stock:*   预扣库存（Lua 原子扣减）
#   course:chosen:*  已经抢到名额的 uid 集合（含预热 staging）
#   course:result:*  结果缓存
# 只清 DB 不清它们，会出现"数据库没记录、Redis 里名额已经没了"的鬼状态，
# 所以每天必须一起清。course:meta:* 留着不动：下一轮预热会覆盖。
#
# 用 SCAN 而不是 KEYS：KEYS 在大 keyspace 上会阻塞 Redis，虽然是演示站，
# 但没必要留一个会在数据量上来时卡住整个实例的写法。
# ---------------------------------------------------------------------------
clear_redis_pattern() {
    local pattern="$1"
    local keys key
    local count=0

    if ! keys="$(docker compose exec -T "${REDIS_SERVICE}" redis-cli --scan --pattern "${pattern}")"; then
        die "扫描 Redis 键失败：${pattern}"
    fi

    # 逐条 DEL 而不是拼一个超长命令：键的数量很少（课程数 × 几个），
    # 而且这样即使某个键刚好过期也不会让整批失败。
    while IFS= read -r key; do
        key="${key%$'\r'}"
        [[ -z "${key}" ]] && continue
        docker compose exec -T "${REDIS_SERVICE}" redis-cli DEL "${key}" >/dev/null
        count=$((count + 1))
    done <<< "${keys}"

    log "Redis ${pattern}：清理 ${count} 个键"
}

clear_redis_pattern 'course:stock:*'
clear_redis_pattern 'course:chosen:*'
clear_redis_pattern 'course:result:*'

log "重置完成"
