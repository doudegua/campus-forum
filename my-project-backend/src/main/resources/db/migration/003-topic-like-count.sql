-- ===========================================================================
-- 003 —— db_topic 加点赞计数（2026-09-30）
--
-- ⚠️ 同 001、002：**全新库不要跑这个文件**。schema.sql 里 db_topic 的建表语句
--    已经带了 like_count，再跑会 ERROR 1060 并中止。只有"老库升级"才跑一次。
--
-- 手动跑一次：
--     mysql -u root test < src/main/resources/db/migration/003-topic-like-count.sql
--
-- 新表 db_topic_like 放在 schema.sql 里（CREATE IF NOT EXISTS 幂等，每次启动自动补建）；
-- 这个 ALTER 必须放迁移，因为它不幂等，重放会让应用起不来。
-- ===========================================================================

-- 和 comment_count 是同一个套路，理由也一样：让详情页不用每次 COUNT(*)。
--
-- 但点赞比评论多一层麻烦 —— **它是热行**。
-- 一条热门帖子被几百人同时点赞时，所有请求都串行在
--     UPDATE db_topic SET like_count = like_count + 1 WHERE id = ?
-- 这一行的行锁上。这是本项目第一个真实的写热点，
-- 也是以后要用 Redis INCR + 异步落库（或计数分片）的原因。
--
-- 现在先别管它：2 个用户、42 条帖子，这个锁连排队的机会都没有。
-- 先写出正确的版本，压出问题，再优化。
ALTER TABLE db_topic
    ADD COLUMN like_count INT NOT NULL DEFAULT 0 COMMENT '点赞数（冗余计数，也是本项目第一个写热点）';
