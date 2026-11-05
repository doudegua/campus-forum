# 管理员角色与抢课预热权限

## 现状

`/api/admin/**` 现在要求 `ROLE_ADMIN`：

```java
.requestMatchers("/api/admin/**").hasRole("ADMIN")
```

- 未登录 → 401（现有 `authenticationEntryPoint`）；
- 已登录但不是管理员 → 403（现有 `accessDeniedHandler`，body 仍是 `RestBean`）；
- 管理员 → 正常进入 `CoursePreheatController`。

## 为什么需要 ADMIN 账号

`hasRole("ADMIN")` 查的是 authority **`ROLE_ADMIN`**，而它来自登录时：

```java
User.withUsername(...).roles(account.getRole())
```

`roles("ADMIN")` 会自动加前缀 `ROLE_`。所以 DB 里的 `role` 必须是：

```text
ADMIN
```

不是 `admin`，也不是 `ROLE_ADMIN`（后者会变成 `ROLE_ROLE_ADMIN`）。

现有普通用户历史数据是 `role='user'`，登录代码会 `toUpperCase` 成 `USER`，
authority 变成 `ROLE_USER`；不影响登录，只是他们进不了 `/api/admin/**`。

## 统一角色大小写（历史库执行一次）

代码已经在 `loadUserByUsername` 里做 `toUpperCase`，新注册账号也直接写 `USER`；
老库可以手工执行：

```bash
mysql -uroot test < my-project-backend/src/main/resources/db/migration/006-normalize-roles.sql
```

脚本用 `BINARY` 比较，因为默认 collation 大小写不敏感，
`role <> UPPER(role)` 会把所有行都过滤掉（这个坑踩过）。

## 开一个管理员（手工 SQL）

```sql
-- 把用户名换成你自己的账号
UPDATE db_account SET role = 'ADMIN' WHERE username = 'doudegua';
```

然后：

1. **退出重新登录**。JWT 里的 `authorities` 是签发时写死的，
   改库不会让旧 token 变管理员；
2. 用新 token 调：

```bash
curl -s -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '[{"courseId":1}]' \
  http://localhost:8081/api/admin/course/preheat
```

## 验证矩阵

| 请求 | 期望 |
|---|---|
| 不带 token | HTTP 200 + body `code=401`（项目约定） |
| 普通用户 token | HTTP 200 + body `code=403` |
| 管理员 token | HTTP 200 + body `code=200`，data 是 `PreheatResult` 列表 |

> 手工签测试 token 时，管理员 token 的 `authorities` 要写 `["ROLE_ADMIN"]`；
> 普通用户写 `["ROLE_USER"]`。`JwtAuthorizeFilter` 只验签和取 authorities，
> 不会回库查角色，所以手工 token 能直接验证路径规则。

## 不要做的事

- 不要在代码里硬编码“用户名 = 管理员”；
- 不要给所有登录用户 `ADMIN`；
- 不要把 `role` 改成 `ROLE_ADMIN`（会双前缀）；
- 不要把 `/api/admin/**` 加进 `permitAll`。
