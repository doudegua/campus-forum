-- 抢课压测：清理测试数据（先删关系，再删课程）
-- 用法：mysql -uroot --default-character-set=utf8mb4 test < course-cleanup.sql

DELETE e FROM db_course_selection e
JOIN db_course c ON c.id = e.course_id
WHERE c.name LIKE '[perftest]%';

DELETE FROM db_course WHERE name LIKE '[perftest]%';

SELECT COUNT(*) AS remaining_perftest_courses
FROM db_course
WHERE name LIKE '[perftest]%';
