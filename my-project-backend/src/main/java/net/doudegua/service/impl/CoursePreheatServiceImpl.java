package net.doudegua.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.entity.dto.Course;
import net.doudegua.entity.dto.CourseSelection;
import net.doudegua.entity.vo.request.CoursePreheatVo;
import net.doudegua.entity.vo.response.PreheatResult;
import net.doudegua.mapper.CourseMapper;
import net.doudegua.mapper.CourseSelectionMapper;
import net.doudegua.service.CoursePreheatService;
import net.doudegua.utils.Const;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class CoursePreheatServiceImpl implements CoursePreheatService {

    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    CourseMapper courseMapper;

    @Resource
    CourseSelectionMapper courseSelectionMapper;

    @Override
    public List<PreheatResult> preheat(List<CoursePreheatVo> vos) {
        if (vos == null || vos.isEmpty()) {
            return List.of();
        }

        List<PreheatResult> results = new ArrayList<>(vos.size());
        for (CoursePreheatVo vo : vos) {
            if (vo == null || vo.getCourseId() == null) {
                results.add(new PreheatResult(null, PreheatResult.ERROR, "courseId 为空"));
                continue;
            }
            try {
                results.add(preheatOne(vo));
            } catch (Exception e) {
                // 单门课失败不能拖垮整批；具体堆栈留给日志
                log.error("预热失败：courseId={}", vo.getCourseId(), e);
                results.add(new PreheatResult(vo.getCourseId(), PreheatResult.ERROR, e.getMessage()));
            }
        }
        return results;
    }

    @Override
    public List<PreheatResult> close(List<Integer> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            return List.of();
        }

        List<PreheatResult> results = new ArrayList<>(courseIds.size());
        for (Integer courseId : courseIds) {
            if (courseId == null) {
                results.add(new PreheatResult(null, PreheatResult.ERROR, "courseId 为空"));
                continue;
            }
            try {
                results.add(closeOne(courseId));
            } catch (Exception e) {
                log.error("关停失败：courseId={}", courseId, e);
                results.add(new PreheatResult(courseId, PreheatResult.ERROR, e.getMessage()));
            }
        }
        return results;
    }

    /**
     * <ol>
     *   <li>拿锁：{@code SETNX course:preheat:lock:{id} 1 EX 30}，失败返回 BUSY</li>
     *   <li>ready 已存在 → SKIPPED（不要覆盖正在用的数据）</li>
     *   <li>查 course；不存在 → NOT_FOUND</li>
     *   <li>查这门课所有选课记录；COUNT &gt; capacity → COUNT_MISMATCH</li>
     *   <li>DEL staging；SADD 所有 uid；校验 SCARD == COUNT</li>
     *   <li>HSET meta capacity/start/end（start/end 用 Date.getTime()）</li>
     *   <li>RENAME staging → chosen（原子替换）</li>
     *   <li>HSET meta ready 1（最后一步）</li>
     *   <li>finally 释放锁</li>
     * </ol>
     * 查选课记录不用写新 mapper：
     * <pre>
     * courseSelectionMapper.selectList(new LambdaQueryWrapper&lt;CourseSelection&gt;()
     *         .eq(CourseSelection::getCourseId, courseId));
     * </pre>
     * 一次 SADD 多个成员：
     * <pre>
     * stringRedisTemplate.opsForSet().add(stagingKey, uids.toArray(new String[0]));
     * </pre>
     */
    private PreheatResult preheatOne(CoursePreheatVo vo) {
        int courseId = vo.getCourseId();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey(courseId), "1", lockTtl());
        if(!Boolean.TRUE.equals(locked)) {
            return new PreheatResult(courseId, PreheatResult.BUSY, null);
        }

        try {
            // 预热好则跳过
            if (stringRedisTemplate.opsForHash()
                    .hasKey(metaKey(courseId), Const.COURSE_META_READY)) {
                return new PreheatResult(courseId, PreheatResult.SKIPPED, null);
            }

            // 滚木课程不让选
            Course course = courseMapper.selectById(courseId);
            if(course == null) {return new PreheatResult(courseId, PreheatResult.NOT_FOUND, null);}

            // 校验已经选课人数与预设人数
            List<CourseSelection> courseSelectionList = courseSelectionMapper.selectList(
                    new LambdaQueryWrapper<CourseSelection>()
                            .eq(CourseSelection::getCourseId, courseId));
            int selectedCount = courseSelectionList.size();
            if(selectedCount > course.getCapacity()) {
                return new PreheatResult(courseId, PreheatResult.COUNT_MISMATCH, null);
            }
            // 较正课程库中人数容量
            if (course.getSelectedCount() == null
                    || course.getSelectedCount() != selectedCount) {
                courseMapper.update(null, new LambdaUpdateWrapper<Course>()
                        .eq(Course::getId, courseId)
                        .set(Course::getSelectedCount, selectedCount));
            }

            // 重建已选课程，支持并发预热
            stringRedisTemplate.delete(stagingKey(courseId));
            if(!courseSelectionList.isEmpty()) {
                String[] uids = courseSelectionList.stream()
                        .map(cs -> String.valueOf(cs.getUid()))
                        .toArray(String[]::new);
                stringRedisTemplate.opsForSet().add(stagingKey(courseId), uids);
            }
            // 再校验一下 staging 结果，疑似有点过于安全了
            Long staged = stringRedisTemplate.opsForSet().size(stagingKey(courseId));
            if (!Objects.equals(staged, (long) courseSelectionList.size())) {
                throw new IllegalStateException("staging 人数不一致");
            }

            // 输入元数据，马上开放
            Map<String, String> metaFields = Map.of(
                    "capacity", String.valueOf(course.getCapacity()),
                    "start", String.valueOf(course.getSelectStart().getTime()),
                    "end", String.valueOf(course.getSelectEnd().getTime())
            );
            stringRedisTemplate.opsForHash().putAll(metaKey(courseId), metaFields);

            // 开放staging
            if(courseSelectionList.isEmpty()) {
                stringRedisTemplate.delete(chosenKey(courseId)); // staging 还是 chosen？
            } else {
                stringRedisTemplate.rename(stagingKey(courseId), chosenKey(courseId));
            }
            // 终于开放了
            stringRedisTemplate.opsForHash().put(metaKey(courseId), Const.COURSE_META_READY, "1");
        } finally {
            stringRedisTemplate.delete(lockKey(courseId));
        }
        return new PreheatResult(courseId, PreheatResult.OPENED, null);
    }

    /**
     * <ol>
     *   <li>HDEL meta ready（先停售）</li>
     *   <li>DEL chosen</li>
     *   <li>DEL meta（可选；保留也可以，下一轮再定）</li>
     * </ol>
     * 返回 CLOSED；本来就没 ready 的话返回 SKIPPED。
     */
    private PreheatResult closeOne(Integer courseId) {
        if(!stringRedisTemplate.opsForHash().hasKey(metaKey(courseId), Const.COURSE_META_READY)) {
            return new PreheatResult(courseId, PreheatResult.SKIPPED, null);
        }
        stringRedisTemplate.opsForHash().delete(metaKey(courseId), Const.COURSE_META_READY);
        stringRedisTemplate.delete(chosenKey(courseId));
        stringRedisTemplate.delete(stagingKey(courseId));
//        stringRedisTemplate.delete(metaKey(courseId)); 再说，无所谓
        return new PreheatResult(courseId, PreheatResult.CLOSED, null);
    }

    /* ---------- 以下是给你的小工具，直接用 ---------- */

    protected String metaKey(int courseId) {
        return Const.COURSE_META + courseId;
    }

    protected String chosenKey(int courseId) {
        return Const.COURSE_CHOSEN + courseId;
    }

    protected String stagingKey(int courseId) {
        return Const.COURSE_CHOSEN_STAGING + courseId;
    }

    protected String lockKey(int courseId) {
        return Const.COURSE_PREHEAT_LOCK + courseId;
    }

    protected Duration lockTtl() {
        return LOCK_TTL;
    }

    /** 预热时写 meta 的字段；start/end 用 DB Date 的 epoch 毫秒 */
    protected Map<String, String> metaFields(Course course) {
        return Map.of(
                Const.COURSE_META_CAPACITY, String.valueOf(course.getCapacity()),
                Const.COURSE_META_START, String.valueOf(course.getSelectStart().getTime()),
                Const.COURSE_META_END, String.valueOf(course.getSelectEnd().getTime())
        );
    }
}
