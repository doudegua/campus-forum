-- ===========================================================================
-- 012 —— 种子数据：访客演示账号（demo / demo123）
--
-- 目的：
--   公网访客不用注册，点登录页的「访客体验」就能用一个固定账号进入站点。
--   这个文件由 compose 的 db-init 容器执行（见 docker-compose.yml），
--   每次 `docker compose up` 都会重放一次，所以**必须幂等**。
--
-- 为什么密码是 BCrypt 哈希而不是明文：
--   db_account.password 存的就是 BCrypt 哈希（见 AccountServiceImpl），
--   明文写进去反而登录不了。
--   哈希由项目自己的 BCryptPasswordEncoder（默认强度 10）生成，可以用
--   jshell / 单元测试重新生成；改这里的值必须同步改 scripts/reset-demo.sh
--   里的兜底哈希，否则"每日重置"和"初始化"会把密码改成两个不同的值。
--
-- 为什么邮箱用 demo@example.com：
--   example.com 是 RFC 2606 保留给文档示例的域名，**永远不会有人收到信**。
--   这一点是安全设计的一部分：改密码只能走 /api/auth/reset-password，
--   而它要求邮箱验证码。收不到信 = 没人能拿到验证码 = 没人能改掉 demo 密码。
--   所以不需要 ROLE_DEMO、不需要给 demo 单独加权限，权限体系完全不用动。
--
-- 为什么用 INSERT IGNORE + 按 username 精确 UPDATE，而不是一条
-- INSERT ... ON DUPLICATE KEY UPDATE：
--   1. 这个文件会被重复执行，普通 INSERT 第二次会报 ERROR 1062，
--      而 mysql 批处理模式下遇到错误会中止后面的语句（和 010/011 同一个坑）。
--   2. username 和 email **都是唯一键**。假如库里已经有一个别的用户名占用了
--      demo@example.com，ON DUPLICATE KEY UPDATE 会命中那一行，把那个真实用户的
--      password 改成 demo123 —— 这是一次账号接管。概率极低，但没必要留这个口子。
--      INSERT IGNORE 冲突时整行跳过，绝不改别人的行；下面的 UPDATE 只认
--      username='demo'，于是"幂等"和"不误伤"同时成立。
--   3. 就算库里已经有 demo，也顺手把密码/角色/邮箱修正回演示值 ——
--      防止有人手工改过密码后演示入口登不进去，或把 demo 的邮箱改成
--      一个真实可达的地址（那样"收不到验证码"这道安全前提就没了）。
--      不更新 username：它是登录名，也是唯一键，改了会让人找不到这个账号；
--      而且 username 就是我们用来定位这一行的键。
--
-- 如果 demo 行不存在、而 demo@example.com 又被别人占着，INSERT 会被 IGNORE，
-- 下面的 UPDATE 影响 0 行，最终没有 demo 账号。这是"邮箱被占用"的异常状态，
-- 应该人工处理，而不是让脚本去抢别人的行。
--
-- 为什么角色是 USER 而不是新建一个 ROLE_DEMO：
--   demo 能做的普通用户都能做；多一套角色只会多一处权限判断。
--   真正限制 demo 的是"邮箱不可达 + 无法改密码"，不是权限。
-- ===========================================================================

INSERT IGNORE INTO db_account (username, password, email, role, registration_date)
VALUES ('demo',
        '$2a$10$hl.DfW8gXG16iMGkh3QiC.S3wc0hGWw/Noo7DxkGJ93RNgkyr37ce',
        'demo@example.com',
        'USER',
        NOW());

-- 只更新 username='demo' 这一行。这样即使上面的 INSERT 因为邮箱冲突被 IGNORE，
-- 也不会误改到别的账号；影响 0 行就代表"demo 没建起来"，需要人工看日志。
UPDATE db_account
SET password = '$2a$10$hl.DfW8gXG16iMGkh3QiC.S3wc0hGWw/Noo7DxkGJ93RNgkyr37ce',
    email    = 'demo@example.com',
    role     = 'USER'
WHERE username = 'demo';

-- ---------------------------------------------------------------------------
-- 资料行：db_account 和 db_account_details 是 1:1（同一个 id），
-- 缺了这行，"个人主页/改资料"会因为 update 影响 0 行而静默失败
-- （这个坑在 AccountServiceImpl.registerEmailAccount 的注释里记着）。
--
-- 只填 id 和 gender，其余留 NULL / 用列默认值：
--   - 不臆造 status（这张表没有 status 列）
--   - 不设默认头像：avatar 存的是 MinIO 的 image_key，随便编一个只会得到破图
--   - show_* 隐私开关用建表时的 DEFAULT 1，和普通注册用户一致
--
-- INSERT IGNORE 而不是 ON DUPLICATE：这里没有任何字段需要"更新"，
-- 重复执行时直接跳过即可；用 ON DUPLICATE KEY UPDATE id = id 反而绕。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO db_account_details (id, gender)
SELECT id, 0
FROM db_account
WHERE username = 'demo';
