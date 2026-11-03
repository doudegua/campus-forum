-- 抢课压测：造一门独立的 [perftest] 课程（可重复执行）
-- 用法：mysql -uroot --default-character-set=utf8mb4 test < course-setup.sql

DELETE e FROM db_course_selection e
JOIN db_course c ON c.id = e.course_id
WHERE c.name LIKE '[perftest]%';

DELETE FROM db_course WHERE name LIKE '[perftest]%';

INSERT INTO db_course (name, capacity, selected_count, select_start, select_end)
VALUES ('[perftest] 抢课压测课', 100, 0, NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 1 DAY);

SELECT id, name, capacity, select_start, select_end
FROM db_course
WHERE name = '[perftest] 抢课压测课';
