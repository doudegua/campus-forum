-- ===========================================================================
-- 005 —— 用点赞关系重算 like_count（2026-10-30）
--
-- ⚠️ 这不是启动迁移，**不会被应用自动执行**。只在"计数确认坏了"时手工跑一次。
--
-- 手动跑：
--     mysql -u root test < src/main/resources/db/migration/005-repair-like-count.sql
--
-- 什么时候会坏（都是"关系没错、计数错了"）：
--   · Redis 在 GETDEL 之后、UPDATE 之前挂了，或者进程正好被杀 —— 那笔增量丢了
--   · Redis 整个丢了，而且没开持久化
--   · 有人手工改过 like:delta:* 或 db_topic.like_count
--
-- 为什么能修：like_count 的唯一来源就是 db_topic_like 的 COUNT(*)。
-- 关系表是权威的，计数只是派生值 —— 这也是整个 Redis 化方案敢丢增量的前提。
--
-- 为什么用 LEFT JOIN + COALESCE：
--   一个赞都没有的帖子在子查询里**没有行**，INNER JOIN 会把它漏掉，
--   它的 like_count 就永远归不了零。LEFT JOIN 保住每一行，COALESCE 补 0。
--
-- ⚠️ 跑之前先确认 Redis 里的增量已经 flush 干净：
--     SCAN like:delta:*     应该为空
--     SCARD like:dirty      应该为 0
--   否则重算会以 DB 为准，把还没落库的增量覆盖掉；
--   而下一轮 flusher 再把这些增量加回来，就会多加一遍。
--   最稳的做法：等一轮 flusher 跑完（或先停后端）再跑这条 SQL。
--
-- WHERE t.like_count <> ... 只是少写没变化的行，去掉也正确。
-- ===========================================================================

UPDATE db_topic t
LEFT JOIN (
    SELECT topic_id, COUNT(*) AS c
    FROM db_topic_like
    GROUP BY topic_id
) l ON l.topic_id = t.id
SET t.like_count = COALESCE(l.c, 0)
WHERE t.like_count <> COALESCE(l.c, 0);

-- ---------------------------------------------------------------------------
-- 自检：期望 still_mismatch = 0。
-- 如果不为 0，说明 db_topic_like 本身也有问题，不是这条 SQL 能修的。
-- ---------------------------------------------------------------------------
SELECT COUNT(*) AS still_mismatch
FROM db_topic t
LEFT JOIN (
    SELECT topic_id, COUNT(*) AS c
    FROM db_topic_like
    GROUP BY topic_id
) l ON l.topic_id = t.id
WHERE t.like_count <> COALESCE(l.c, 0);
