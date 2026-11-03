package net.doudegua.service;

import net.doudegua.entity.vo.request.CoursePreheatVo;
import net.doudegua.entity.vo.response.PreheatResult;

import java.util.List;

/**
 * 抢课预热 / 关停。
 * <p>
 * 预热 = 把 DB 里的课程元数据和选课记录搬进 Redis，并最后写 ready；
 * 关停 = 先摘掉 ready，再清 chosen。
 * 时间窗由 {@code db_course.select_start/select_end} 决定，预热本身不判时间。
 */
public interface CoursePreheatService {

    /** 逐门课预热；单门失败不影响其他课，结果按入参顺序返回 */
    List<PreheatResult> preheat(List<CoursePreheatVo> vos);

    /** 逐门课关停；同样允许部分成功 */
    List<PreheatResult> close(List<Integer> courseIds);
}
