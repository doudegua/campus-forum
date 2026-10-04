# ===========================================================================
# 前端镜像：多阶段构建（Node 编译 → Nginx 提供静态文件）
#
# 关键认识：**前端"运行"不需要 Node。** `npm run build` 把 .vue 编译成
# 普通 .js/.css/.html，之后只需要一个能吐静态文件的 Web 服务器。
# 所以最终镜像里既没有 Node 也没有源码，只有 nginx + 一堆静态文件（几十 M）。
#
# 为什么用 Node 22 而不是 20：
#   package.json 里写了 engines: node ^20.19.0 || >=22.12.0。
#   20.19 是自举要求（Vite 7 需要），用 22 更省事。
# ===========================================================================

# ---------------------------------------------------------------------------
# 阶段 1：构建
# ---------------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /build

# 和后面 Java 那边同一个道理：先只拷依赖清单，让"装依赖"这层能被缓存。
# 源码改一百次，只要 package.json/package-lock.json 没变，这层都不会重跑。
COPY package.json package-lock.json ./

# npm ci 而不是 npm install：
#   它严格按 package-lock.json 装，版本和 CI 完全一致；
#   install 会依情况更新 lock 文件，可能装出和开发时不同的版本。
#   （顺带：这就是 package-lock.json 必须进仓库的原因。）
RUN npm ci

COPY . .

# 这一步做两件事：编译，以及**把语法错误暴露成构建失败**。
# 前端很多问题是"编译通过、跑起来才知道"，能在构建阶段炸出来就值得。
RUN npm run build

# ---------------------------------------------------------------------------
# 阶段 2：运行
# ---------------------------------------------------------------------------
FROM nginx:1.27-alpine

# 换掉默认配置。默认那份只吐静态文件，不会把 /api 反代到后端 ——
# 不换的话页面能打开，但所有接口请求都会 404。
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80

# 健康检查：用 127.0.0.1，不要用 localhost。
# ⚠️ 容器里 localhost 会先解析到 IPv6 的 ::1，而 nginx 在这个镜像里只听 IPv4，
#    于是报 "can't connect to remote host: Connection refused"，容器一直 unhealthy
#    —— 但 nginx 其实是好的（从宿主机 curl 它的公网 IP 得到 200）。
#    显式写 127.0.0.1 走 IPv4 就避开了。这个镜像里 wget 和 curl 都有。
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q --spider http://127.0.0.1/ || exit 1
