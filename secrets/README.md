# secrets/ —— 存放不进仓库的密钥文件

这个目录**不会进 git**（见根目录 `.gitignore`）。它存在的意义是给
`docker-compose.yml` 提供一个挂载点，把敏感文件送进容器。

## 里面该放什么

### `ed25519-private.pem`（可选）

和风天气签发 JWT 用的 Ed25519 私钥。**不放也能跑**，只是天气小组件会失败 ——
应用启动、登录、发帖、评论、点赞全都不受影响（私钥是懒加载的）。

要启用天气功能：

```bash
# 在服务器上，项目根目录
mkdir -p secrets
# 把你从和风天气控制台下载的私钥内容写进去
vi secrets/ed25519-private.pem
chmod 600 secrets/ed25519-private.pem
docker compose restart backend
```

**格式要求（踩过就知道的坑）**：必须是 **PKCS#8**，文件第一行是

```
-----BEGIN PRIVATE KEY-----
```

如果第一行是 `-----BEGIN RSA PRIVATE KEY-----`，那是 PKCS#1，Java 的
EdDSA 读不了，会报 `InvalidKeySpecException`。两种格式可以互转：

```bash
openssl pkcs8 -topk8 -nocrypt -in pkcs1.pem -out ed25519-private.pem
```

## 为什么用一个目录而不是直接挂文件

`docker-compose.yml` 里挂的是 `./secrets:/app/config:ro`，也就是整个目录。

如果改成挂单个文件（`./secrets/x.pem:/app/config/x.pem:ro`），而那个文件
在宿主机上**不存在**，Docker 不会报错，而是会在宿主机上建一个**同名目录**顶上。
结果应用读到的是个目录，报错信息完全看不出真正原因。挂目录就没这个问题。
