-- ===========================================================================
-- 006 —— 统一角色大小写
--
-- 背景：
--   db_account.role 早期存的是小写 "user"，而 Spring Security 的
--   hasRole("ADMIN") 期望 authority 是 ROLE_ADMIN；roles() 会自动加 ROLE_ 前缀，
--   但不会改大小写。结果就是 role='admin' 会变成 ROLE_admin，和 ROLE_ADMIN 对不上。
--
-- 做法：
--   把库里的角色统一成大写；代码里 loadUserByUsername 也做了 toUpperCase 兜底，
--   所以这条 SQL 不是"必须马上跑"，但跑完能让库和代码一致。
--
-- 注意：
--   1. 这是**改旧表**，按项目约定放 db/migration/，手动执行一次；
--      不要塞进 schema.sql（每次启动重放，幂等语句才行）。
--   2. 执行后旧 JWT 里的 authorities 不会变，管理员需要重新登录。
--   3. 如果某行是 'ROLE_ADMIN'（已经带前缀），这里会变成 'ROLE_ADMIN' → 仍是错的；
--      正确值应该是 'ADMIN'。本脚本只做大小写，不处理双前缀。
-- ===========================================================================

UPDATE db_account
SET role = UPPER(role)
WHERE role IS NOT NULL
  -- ⚠️ 不能直接写 role <> UPPER(role)：默认 collation 大小写不敏感，
  -- 'user' = 'USER' 为真，WHERE 会把所有行都过滤掉。必须用 BINARY 强制区分。
  AND BINARY role <> BINARY UPPER(role);
