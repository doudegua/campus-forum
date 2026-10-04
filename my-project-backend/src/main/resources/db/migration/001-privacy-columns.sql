-- ===========================================================================
-- 001 —— 隐私开关（2026-09-30）
--
-- ⚠️ 先判断你的库是"新库"还是"老库"，这决定要不要跑这个文件：
--
--   【全新库】（Docker 起的空库、换台机器从零开始）
--       **不要跑，一条迁移都不要跑。**
--       schema.sql 里 db_account_details 的建表语句**已经包含**下面这 5 个列。
--       再跑这个 ALTER 会报 ERROR 1060 Duplicate column name 然后中止，
--       而 mysql 批处理模式下**报错就停止执行剩余语句**，库会停在半初始化状态。
--
--   【已有的老库】（比如本机那个 test 库，是在这些列还不存在时建的）
--       跑，且只跑一次。
--
-- 这个文件**不会**被应用自动执行，请手动跑：
--     mysql -u root test < src/main/resources/db/migration/001-privacy-columns.sql
--
-- 为什么不能放进 schema.sql：那个文件每次启动都重放，而 MySQL 8 不支持
-- ADD COLUMN IF NOT EXISTS，重放会报 Duplicate column name 并让应用起不来。
-- 详见 schema.sql 顶部的说明。
-- ===========================================================================

-- 直接加在 db_account_details 上，没有单开一张表。理由：
--   1. 它和用户是 1:1，跟资料同一行，读资料时不用多一次 JOIN
--   2. 这些开关的作用就是"资料里的某个字段给不给人看"，
--      而字段就在这张表里，放在一起改的时候只有一处
-- 等开关多到十几个、或者出现"按人去配"的需求，再拆表不迟。
--
-- 用 TINYINT(1) 而不是 BOOLEAN：MySQL 的 BOOLEAN 本来就是 TINYINT(1) 的别名；
-- 而 TINYINT 以后要扩成 0/1/2 三态（所有人/仅好友/仅自己）时不用改列类型。
--
-- DEFAULT 1 是刻意的：现在所有人的资料都是全公开的，
-- 默认 0 会让升级那一瞬间所有老用户的主页直接变空 —— 那是事故，不是功能。
ALTER TABLE db_account_details
    ADD COLUMN show_gender      TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否公开性别',
    ADD COLUMN show_phone       TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否公开手机号',
    ADD COLUMN show_qq          TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否公开 QQ',
    ADD COLUMN show_description TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否公开简介',
    ADD COLUMN show_topics      TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否公开我发的帖子列表';
