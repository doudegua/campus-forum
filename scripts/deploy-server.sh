#!/usr/bin/env bash
# ===========================================================================
# scripts/deploy-server.sh —— 在服务器上把项目跑起来
#
# 用法（在服务器上，项目根目录）：
#     bash scripts/deploy-server.sh
#
# 它做四件事，每件事都会先检查再做，可重复执行：
#     1. 确认 Docker 装了（没装就提示怎么装，不擅自装）
#     2. 准备 .env —— 不存在就从模板生成，并把里面的示例密码换成随机值
#     3. 拉镜像 / 构建
#     4. 起服务，并等到健康
#
# 为什么把随机密钥的生成放在脚本里：
#   .env.example 里 JWT_KEY 是空的、DB_PASSWORD 也是空的。
#   手填的话很容易"先跑起来再说"，最后带着空密钥上了公网 ——
#   JWT_KEY 是签发登录 token 的密钥，空值/默认值等于谁都能伪造任意用户。
#   所以这里直接生成随机值，不给"忘了改"留机会。
#
# 这个脚本**不会**：装 Docker、改系统配置、动防火墙、碰别的项目。
# ===========================================================================
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[注意] %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m[失败] %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Docker
# ---------------------------------------------------------------------------
say "检查 Docker"
if ! command -v docker >/dev/null 2>&1; then
    cat <<'EOT'
Docker 没装。在这台机器上装（Ubuntu 用官方脚本）：

    curl -fsSL https://get.docker.com | sh

装完确认：

    docker --version
    docker compose version

然后重新运行本脚本。
EOT
    die "缺少 docker"
fi
docker --version
docker compose version >/dev/null 2>&1 || die "docker compose 子命令不可用（需要 Docker Compose v2）"

# daemon 真的在跑吗 —— 装了但没启动是最常见的"看起来装了却用不了"
if ! docker info >/dev/null 2>&1; then
    die "docker 装了但 daemon 没跑。试：systemctl start docker"
fi

# ---------------------------------------------------------------------------
# 2. .env
# ---------------------------------------------------------------------------
say "准备 .env"
if [ -f .env ]; then
    echo ".env 已存在，保持不动（不覆盖你已有的配置）"
    # 空密钥是危险状态，显式提醒
    if grep -qE '^JWT_KEY=\s*$' .env; then
        warn ".env 里 JWT_KEY 是空的 —— 这等于没有鉴权密钥，任何人都能伪造登录 token"
    fi
    if grep -qE '^DB_PASSWORD=\s*$' .env; then
        warn ".env 里 DB_PASSWORD 是空的"
    fi
else
    [ -f .env.example ] || die "既没有 .env 也没有 .env.example，是不是不在项目根目录？"
    cp .env.example .env

    # 生成随机值。用 openssl，不行就退回 /dev/urandom
    gen() {
        if command -v openssl >/dev/null 2>&1; then
            openssl rand -base64 36 | tr -d '\n=+/' | cut -c1-32
        else
            head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'
        fi
    }
    JWT="$(gen)"; DBP="$(gen)"; MINIOP="$(gen)"

    # 用 | 作分隔符，避免密码里的 / 破坏 sed 表达式
    sed -i "s|^JWT_KEY=.*|JWT_KEY=${JWT}|"                     .env
    sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=${DBP}|"             .env
    sed -i "s|^MINIO_ROOT_PASSWORD=.*|MINIO_ROOT_PASSWORD=${MINIOP}|" .env
    sed -i "s|^RABBITMQ_PASSWORD=.*|RABBITMQ_PASSWORD=${MINIOP}|"     .env

    echo ".env 已生成，并写入了随机生成的 JWT_KEY / DB_PASSWORD / MinIO 与 RabbitMQ 密码"
    warn "MAIL_PASSWORD 仍是空的 —— 不影响启动，但'发邮箱验证码'会失败。"
    warn "要启用就编辑 .env 填 163 的 SMTP 授权码，然后 docker compose restart backend"
fi

# .env 绝不能进 git
if git -C "$ROOT" check-ignore -q .env 2>/dev/null; then
    echo ".env 已被 .gitignore 忽略 ✓"
else
    warn ".env 没有被 .gitignore 忽略！确认一下再继续"
fi

# ---------------------------------------------------------------------------
# 3. 构建
# ---------------------------------------------------------------------------
say "构建镜像（第一次会慢，要下载基础镜像和依赖）"
docker compose build

# ---------------------------------------------------------------------------
# 4. 启动
# ---------------------------------------------------------------------------
say "启动服务"
docker compose up -d

# ---------------------------------------------------------------------------
# 5. 等健康
# ---------------------------------------------------------------------------
say "等待服务健康（最多 180 秒）"
deadline=$(( SECONDS + 180 ))
while [ $SECONDS -lt $deadline ]; do
    unhealthy="$(docker compose ps --format '{{.Service}} {{.State}} {{.Health}}' 2>/dev/null \
        | awk '$2!="running" || ($3!="" && $3!="healthy") {print $1}' || true)"
    if [ -z "$unhealthy" ]; then
        break
    fi
    sleep 5
done

say "当前状态"
docker compose ps

# ---------------------------------------------------------------------------
# 6. 自检
# ---------------------------------------------------------------------------
say "自检"
IP="$(curl -s --max-time 5 ifconfig.me 2>/dev/null || echo '<服务器IP>')"

echo
echo "--- 前端首页 ---"
if curl -fsS -o /dev/null --max-time 10 http://localhost/ ; then
    echo "  ✓ 首页返回 200"
else
    echo "  ✗ 首页不通"
fi

echo "--- 后端接口（经 nginx 反代）---"
code="$(curl -s -o /tmp/dep.json -w '%{http_code}' --max-time 10 http://localhost/api/forum/topic_type || echo 000)"
echo "  HTTP $code  $(head -c 120 /tmp/dep.json 2>/dev/null || true)"

echo
if [ "$code" = "401" ] || [ "$code" = "200" ]; then
    printf '\033[1;32m部署看起来成功了。浏览器打开： http://%s\033[0m\n' "$IP"
else
    warn "接口没通。排查顺序："
    echo "    docker compose ps                     # 哪个服务不健康"
    echo "    docker compose logs -f frontend       # nginx 有没有起"
    echo "    docker compose logs -f backend        # 后端连不上 MySQL/MinIO？"
    echo "    docker compose logs minio-init        # bucket 建成功了吗"
fi
