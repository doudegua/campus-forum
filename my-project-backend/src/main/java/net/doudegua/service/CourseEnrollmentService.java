package net.doudegua.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import net.doudegua.entity.dto.CourseSelection;
import net.doudegua.mapper.CourseMapper;
import net.doudegua.mapper.CourseSelectionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseEnrollmentService {

    @Resource
    CourseSelectionMapper courseSelectionMapper;

    @Resource
    CourseMapper courseMapper;

    @Transactional
    public EnrollResult enroll(int uid, int courseId) {
        // 1. 幂等插入：0 = 重复消息/已经选过
        if (courseSelectionMapper.insertIgnore(uid, courseId) == 0) {
            return EnrollResult.DUPLICATE;
        }

        // 2. DB 容量兜底：0 = DB 已满，把刚插的行删掉
        if (courseMapper.incrementSelectedIfAvailable(courseId) == 0) {
            int deleted = courseSelectionMapper.delete(
                    new LambdaQueryWrapper<CourseSelection>()
                            .eq(CourseSelection::getUid, uid)
                            .eq(CourseSelection::getCourseId, courseId));
            if (deleted != 1) {
                // 删不掉就让事务回滚，别留下半条记录
                throw new IllegalStateException("seat-full rollback failed: " + uid + "/" + courseId);
            }
            return EnrollResult.SEAT_FULL;
        }

        return EnrollResult.OK;
    }

    public enum EnrollResult { OK, DUPLICATE, SEAT_FULL }
}
