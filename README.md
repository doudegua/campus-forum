# 校园论坛

一个校园论坛（贴吧）项目。Spring Boot 3 + Vue 3，前后端分离。

主要功能：账号注册与邮箱验证码登录、JWT 鉴权、发帖（富文本 + 图片上传）、
帖子列表（游标分页 + 版块筛选）、帖子详情、评论、点赞、个人资料与隐私开关、
天气小组件。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 3.5 / Java 17 / Maven |
| 持久层 | MyBatis-Plus / MySQL 8 |
| 鉴权 | Spring Security + JWT（HMAC256），登出用 Redis 黑名单 |
| 图片 | MinIO 对象存储 |
| 邮件 | RabbitMQ 异步发验证码，163 SMTP |
| 富文本 | Quill（`@vueup/vue-quill`），服务端 jsoup 白名单清洗 |
| 前端 | Vue 3 / Vite / Element Plus / Pinia / axios |
| 部署 | Docker Compose（6 个服务）+ Nginx |

## 目录结构

```
.
├── my-project-backend          Spring Boot 后端
│   ├── src/main/java/net/doudegua
│   │   ├── config              安全、CORS、MinIO、RabbitMQ、和风天气
│   │   ├── controller          HTTP 接口（23 个）
│   │   ├── service / impl      业务逻辑
│   │   ├── mapper              MyBatis-Plus Mapper
│   │   ├── entity              DTO / 请求 VO / 响应 VO
│   │   ├── filter              JWT 鉴权、限流、请求日志、CORS
│   │   └── utils               JWT、HTML 清洗、雪花 ID、限流工具
│   ├── src/main/resources
│   │   ├── application.yaml    配置模板（${环境变量:默认值}）
│   │   └── db
│   │       ├── schema.sql      建表语句，**每次启动重放，所以只能放幂等语句**
│   │       ├── init/           种子数据（版块分类）
│   │       └── migration/      增量脚本，升级已有库时手动执行一次
│   └── scripts
│       └── verify-schema.sh    验证 schema.sql 与真实库结构一致
└── my-project-frontend         Vue 3 前端
    └── src
        ├── views               页面（论坛 / 设置 / 登录注册）
        ├── components          通用组件（游标分页列表、卡片、天气）
        ├── net                 接口封装（axios + token 存取）
        └── router / store      路由与状态
```

## 本地跑起来

需要本机有：JDK 17+、Maven、Node 20+、MySQL、Redis、RabbitMQ、MinIO。

```bash
# 1. 准备数据库（只需一次）
mysql -uroot -e "CREATE DATABASE test DEFAULT CHARSET utf8mb4;"
# schema.sql 会在应用启动时自动执行，建表不用手动跑

# 2. 后端（默认 8080）
cd my-project-backend
mvn spring-boot:run

# 3. 前端（默认 5173，/api 已代理到 8080）
cd my-project-frontend
npm install
npm run dev
```

打开 http://localhost:5173。

**配置**：`application.yaml` 里全是 `${环境变量:默认值}` 的形式，默认值就是
本地开发的常用值，所以不配任何环境变量也能跑。要改数据库地址、密钥之类，
复制 `.env.example` 成 `.env` 填进去，或者直接用环境变量覆盖。

> 唯一没有默认值的是邮箱授权码（`MAIL_PASSWORD`）。不填不影响启动，
> 只是"发验证码"会失败。

### 起第二个实例（避免占用 8080）

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

## Docker 部署

一条命令起 6 个服务（MySQL + Redis + MinIO + RabbitMQ + 后端 + 前端 Nginx）。

### 前置：如果服务器拉不到 Docker Hub，要配镜像加速器

`docker pull` 报 `dial tcp ... i/o timeout` 时，说明服务器连不上
`registry-1.docker.io`。注意 **`hub.docker.com` 能打开不代表能拉镜像** ——
镜像仓库是另一个域名。

```bash
cat > /etc/docker/daemon.json <<'JSON'
{
  "registry-mirrors": ["https://docker.1panel.live", "https://docker.m.daocloud.io"]
}
JSON
systemctl restart docker
docker pull alpine   # 验证一下
```

### 在服务器上（推荐）

```bash
# 用 HTTPS 克隆：公开仓库不需要认证，服务器上也不用配 GitHub 密钥
git clone --depth 1 https://github.com/doudegua/campus-forum.git
cd campus-forum
bash scripts/deploy-server.sh
```

脚本会把该做的检查都过一遍：确认 Docker 装了**且 daemon 在跑**、生成 `.env`
（里面的 `JWT_KEY` / `DB_PASSWORD` / MinIO 与 RabbitMQ 密码**直接生成随机值**，
不给"先跑起来再说、密钥以后补"留机会）、构建、启动、等健康、最后 curl 确认接口通。
可重复执行，不会覆盖已有的 `.env`。

### 手动

```bash
cp .env.example .env
# 编辑 .env，至少改这几个：
#   DB_PASSWORD            MySQL 密码
#   JWT_KEY                随机串，openssl rand -base64 48
#   MINIO_ROOT_PASSWORD    MinIO 管理员密码
#   MAIL_PASSWORD          163 SMTP 授权码（不发验证码可以不填）
docker compose up -d --build
```

起来后访问 `http://<服务器IP>`。

### 常用命令

```bash
docker compose ps                  # 看各服务状态
docker compose logs -f backend     # 跟后端日志
docker compose up -d --build       # 改了代码后重建
docker compose down                # 停止（数据保留）
docker compose down -v             # 停止并删除数据（慎用）
```

### 为什么这样设计

- **后端不暴露端口**：只有 Nginx 的 80 对外，其余服务都在内部网络里用服务名
  互相访问（`mysql` / `redis` / `minio` / `rabbitmq`）。少开一个端口就少一个攻击面。
- **`depends_on` 带 `condition: service_healthy`**：等 MySQL 真的能接受连接了
  才启动后端。不加的话后端会比 MySQL 先起来、连接失败退出、靠 restart 反复重试，
  日志里一片红，看着像坏了其实只是在等。
- **前端用 Nginx 提供，不用 Node**：`npm run build` 之后只剩静态文件，
  Node 完全不需要出现在运行环境里，最终镜像就是 nginx + 一堆 js/css。
- **`client_max_body_size 10m`**：Nginx 默认只允许 1M 请求体，不调大的话
  头像或配图稍大就返回 413，而且 413 是 Nginx 直接吐的 HTML，前端拿到的不是 JSON，
  表现为"上传失败但看不出原因"。

### 数据持久化

MySQL 数据、Redis 数据、MinIO 图片、RabbitMQ 队列都挂在命名 volume 上。

| 命令 | 数据 |
|---|---|
| `docker compose restart` | 保留 |
| `docker compose down` + `up` | **保留** |
| `docker compose down -v` | **删除** |

### 可选：启用天气功能

天气需要和风天气的 Ed25519 私钥，它**不在仓库里也不在镜像里**。
把它放到 `secrets/ed25519-private.pem` 后重启后端即可（详见 `secrets/README.md`）。

不放也能跑 —— 应用启动、登录、发帖、评论、点赞全都不受影响，
只有天气接口会返回"找不到 Ed25519 私钥"。这是实测过的行为。

### 限流在反向代理后面怎么拿到真实用户 IP

`FlowLimitFilter` 用 `request.getRemoteAddr()` 作限流粒度。在容器拓扑里
（浏览器 → Nginx → 后端），后端的 `getRemoteAddr()` 默认拿到的是 **Nginx 容器的 IP**，
于是所有用户共用同一个计数器 —— 阈值本来是"每 IP 每秒 1 万次"，
实际变成"所有人加起来每秒 1 万次"，等于没有限制。

修法是让后端认 Nginx 传来的 `X-Forwarded-For`：

```yaml
server:
  forward-headers-strategy: NATIVE
```

它会启用 Tomcat 的 `RemoteIpValve`：读 `X-Forwarded-For`，
**确认来源可信之后**改写 `getRemoteAddr()` 的返回值。
好处是 `FlowLimitFilter` 一行都不用改。

**但这引出一个安全问题**：`X-Forwarded-For` 是客户端能自己伪造的普通请求头。
如果无条件信任它，攻击者每次请求换一个假 IP 就能绕过限流 —— 比不修还糟。
所以 `RemoteIpValve` 用 `internalProxies` 白名单兜底：**只有当请求来自白名单里的
地址时才采信这个头**。它的默认值包含 `172.16.0.0/12`，而 Docker 网桥正好在这个段内
（容器地址形如 `172.18.0.x`），所以：

| 请求来源 | 采信 X-Forwarded-For 吗 |
|---|---|
| Nginx 容器（`172.18.0.x`） | 会 —— 这是设计内的 |
| 外部直连（绕过 Nginx） | 不会 —— 伪造的头被忽略 |

而 compose 里 backend **没有暴露端口**，外部本来也连不到 8080，等于这层防护没有缺口。

`docker-compose.yml` 里另外显式设了 `FLOW_LIMIT_SKIP_LOOPBACK=false`：
"跳过回环地址"是本地开发用的（防止把自己刷进限流），部署环境不该有免检来源。

## 数据库迁移

`schema.sql` 每次启动重放，所以它里面**只能放幂等语句**
（`CREATE TABLE IF NOT EXISTS`）。`ALTER TABLE` 不幂等，重放会报
`Duplicate column name` 并让应用起不来 —— 所以改已有表要写进 `db/migration/`。

| 你的情况 | 要跑什么 |
|---|---|
| 全新数据库（Docker 起的、换台机器重来） | **只跑应用，`schema.sql` 自己建好全部 7 张表。一条迁移都不要跑** |
| 已有的老数据库 | 跑 `db/migration/001~003` 各一次 |

迁移脚本里写明了这一点 —— 在全新库上跑迁移会报错中止，而 `mysql` 批处理
模式遇错即停，会把库停在半初始化状态。

验证 `schema.sql` 和某个真实库结构是否一致：

```bash
cd my-project-backend
./scripts/verify-schema.sh          # 默认对比 test 库
```

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 登录，返回 JWT |
| GET | `/api/auth/logout` | 登出（把 jti 拉进 Redis 黑名单） |
| POST | `/api/auth/register-email` | 发注册验证码 |
| POST | `/api/auth/register` | 用验证码注册 |
| POST | `/api/auth/reset-password` | 请求重置密码 |
| POST | `/api/auth/reset-confirm` | 确认重置 |
| GET | `/api/user/info` | 当前登录用户 |
| GET/POST | `/api/user/profile` | 读 / 改个人资料 |
| POST | `/api/user/avatar` | 上传头像 |
| GET | `/api/user/profile/{id}` | 看别人的公开资料（受隐私开关约束） |
| GET/POST | `/api/user/privacy` | 读写 5 个隐私开关 |
| POST | `/api/forum/topic` | 发帖（限流 60 秒/人，HTML 白名单清洗） |
| GET | `/api/forum/topic/{id}` | 帖子详情 |
| GET | `/api/forum/list-topic` | 帖子列表（游标分页，支持 `types` / `uid` 筛选） |
| GET | `/api/forum/topic_type` | 版块列表 |
| POST/GET | `/api/forum/comment` | 发评论 / 评论列表 |
| POST/DELETE | `/api/forum/topic/{topicId}/like` | 点赞 / 取消（幂等） |
| POST | `/api/image` | 上传图片（按文件头魔数验类型，禁 SVG） |
| GET | `/api/image/{key}` | 取图（公开，一年长缓存） |
| GET | `/api/weather` | 天气（和风天气） |
| GET | `/api/validate/code` | 校验邮箱验证码 |

## 一些实现上的取舍

- **游标分页而不是 offset 分页**：`WHERE id < cursor ORDER BY id DESC` 避免了
  深翻页时 `LIMIT 100000, 20` 要扫掉前 10 万行的问题。
- **帖子列表的计数用冗余列**（`comment_count` / `like_count`）而不是每次
  `COUNT(*)`：详情页本来就 `getById` 一次，计数跟着那一行一起回来，省一次聚合查询。
- **点赞用复合主键 `(uid, topic_id)` 保证幂等**，不靠"先查有没有再插入" ——
  后者在并发下有 TOCTOU 窗口（实测同一个人并发点 20 次赞，天真版 1 个成功 19 个 500）。
  计数用 `SET n = n + 1` 原子加，不在 Java 里读出来加完写回去。
- **富文本清洗、纯文本转义**：帖子和评论是两种内容，用反了不是"松一点"而是数据丢失
  （`HtmlSanitizer` 会把 `<String>` 当未知标签整个删掉）。
- **`db/migration` 里的私钥不进仓库**：和风天气用 Ed25519 私钥签 JWT，
  私钥在 `.gitignore` 里。没有它天气接口失败，但其它功能正常。

## License

没有。学习项目，代码随意参考。
