-- 抢课压测：压完等队列排空后核对
-- 用法：mysql -uroot --default-character-set=utf8mb4 test < course-check.sql

SET @cid = (SELECT id FROM db_course WHERE name = '[perftest] 抢课压测课' LIMIT 1);

SELECT @cid AS course_id;

SELECT COUNT(*) AS rows_, COUNT(DISTINCT uid) AS uids
FROM db_course_selection
WHERE course_id = @cid;

SELECT capacity, selected_count
FROM db_course
WHERE id = @cid;
