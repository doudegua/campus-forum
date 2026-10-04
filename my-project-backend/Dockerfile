# ===========================================================================
# 后端镜像：多阶段构建
#
# 为什么要"多阶段"：
#   编译需要 Maven + JDK + 几十个依赖（合起来 1G 上下），但运行只需要一个 JRE。
#   如果只写一个 Dockerfile 从头装到尾，最终镜像里会带着整套 Maven 和源码，
#   镜像能到 1G 多。多阶段让编译产物被"拷出来"给第二个精简镜像用，
#   第一个阶段整个丢掉 —— 最终镜像只装得下 jar 和 JRE，200M 出头。
#
# 为什么构建阶段要在容器里跑，而不是本机 build 完再拷：
#   这样服务器上 `docker compose up --build` 一条命令就能从源码到镜像，
#   不需要服务器装 Maven/JDK/Node。换了机器也能原样复现。
# ===========================================================================

# ---------------------------------------------------------------------------
# 阶段 1：编译
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom.xml 再下依赖 —— 这一步是刻意拆开的：
#   Docker 每一层都有缓存，而缓存会在"这一层用到的文件"变化时失效。
#   如果一上来就 COPY . .，那改任何一行源码都会让下面下载依赖那层缓存失效，
#   整个依赖树重下（几分钟）。先单独 COPY pom.xml，依赖层就只在 pom 变了才失效。
#
# ⚠️ COPY 这一行不能省。少了它，mvn 在 /build 里找不到 pom.xml 会直接报
#      Goal requires a project to execute but there is no POM in this directory
#    而且这个错误看起来像"Maven 配置有问题"，其实只是文件没拷进来。
#    （这个坑真踩过：注释里写满了"要先单独 COPY pom.xml"的理由，代码却没写。）
COPY pom.xml .
RUN mvn -B dependency:go-offline

# 再拷源码。这一层会随源码变化而重建，但上面的依赖还在缓存里
COPY . .
# -DskipTests：构建镜像时只要 jar，测试在开发机上跑更合适。
# （注意它属于这一句，不属于上面那条 go-offline —— 之前那行注释放错了位置。）
RUN mvn -B package -DskipTests

# ---------------------------------------------------------------------------
# 阶段 2：运行
# ---------------------------------------------------------------------------
# jammy = Ubuntu 22.04，和部署服务器同版本。用 alpine 镜像更小，
# 但它是 musl libc 而不是 glibc，个别依赖会踩坑 —— 这种环境差异不值得为省 80M 冒险。
FROM eclipse-temurin:21-jre-jammy

# tzdata：容器默认是 UTC，不装这个 JVM 里的时间会比北京时间早 8 小时，
#         表现为"帖子时间不对"，但代码看不出问题。设了 TZ 还需要它才生效。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*
ENV TZ=Asia/Shanghai

# 用非 root 用户跑应用。容器里以 root 运行的话，一旦应用被攻破，
# 攻击者在容器内就是 root 权限，逃逸到宿主机的门槛会低不少。
RUN useradd -r -u 1001 -m appuser
WORKDIR /app

# 只拷最终的 jar。--chown 让 appuser 有读权限
COPY --from=build --chown=appuser:appuser /build/target/*.jar app.jar

USER appuser
EXPOSE 8080

# 健康检查：判断"进程还在不在服务请求"，不判断业务是否正常。
#
# ⚠️ 不能用 curl -f 打 /error。原来写的是
#      CMD curl -fsS http://localhost:8080/error
#   结果是容器一直 unhealthy，但服务其实是好的：
#      -f 要求 HTTP 2xx，而 /error 这个路径返回 500（它的语义就是"出错了"）
#      - 容器内 localhost 会先解析到 IPv6 ::1，Spring 内嵌 Tomcat 只听 IPv4，
#        也可能直接连不上
#   所以改成：打一个**确定能路由到 Spring 但不要求 2xx**的路径，
#   并显式用 127.0.0.1 走 IPv4；然后用 grep 判断"是不是应用返回的"。
#   /api/auth/login 存在但只接受 POST，所以 GET 会得到 404 或 405 ——
#   拿到这两个码恰恰说明应用活着（真挂了是连不上，curl 会非 0 退出）。
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/auth/login | grep -qE '40[0-9]|2[0-9]{2}' || exit 1

# MaxRAMPercentage=75：让 JVM 按容器内存上限的 75% 自动定堆大小。
# 不写这个的话，老版本 JVM 会去读宿主机的总内存（在容器里看到的是宿主机的大内存），
# 堆开得过大，最后被 OOM Killer 干掉 —— 这是容器里跑 JVM 最经典的坑。
# -Duser.timezone 和上面的 TZ 一起保证时间正确。
ENTRYPOINT ["sh", "-c", "java -XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Shanghai -jar app.jar"]
