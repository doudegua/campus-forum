package net.doudegua.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.entity.dto.Course;
import net.doudegua.entity.dto.CourseSelection;
import net.doudegua.entity.vo.response.CourseSelectResultVo;
import net.doudegua.entity.vo.response.CourseVo;
import net.doudegua.mapper.CourseMapper;
import net.doudegua.mapper.CourseSelectionMapper;
import net.doudegua.service.CourseService;
import net.doudegua.utils.Const;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 抢课实现 —— <b>骨架，三段核心留空</b>。
 * <p>
 * 已经实现的部分只有 {@link #listCourses()}：它不是这轮的重点，留着是为了
 * 验证"表建对了、种子数据灌进去了、读路径是通的"。两个写路径故意不写，
 * 因为写它们的过程才是这次练习本身（AGENT.md 第五节：AI 写的东西他会看但记不住）。
 * <p>
 * <b>正确的写法分三段，缺一段就不成立</b>：
 * <ol>
 *   <li><b>Redis 预扣</b>（{@link #SELECT_SCRIPT}）：去重 + 判断余量 + 扣减，
 *       三件事必须在同一个 Lua 里。拆开就是 TOCTOU，后果是超卖。</li>
 *   <li><b>数据库兜底</b>（{@code CourseMapper.incrementSelectedIfAvailable}）：
 *       一条带 {@code selected_count < capacity} 的 UPDATE。它是最后一道防线，
 *       影响 0 行就说明 Redis 和 DB 的账对不上。</li>
 *   <li><b>MQ 削峰 + 幂等消费</b>（{@code CourseSelectListener}）：
 *       让 DB 只承接"真的抢到的人"，并且重复投递不会多占名额。</li>
 * </ol>
 * 每一步"不这么做会怎样"写在 docs/course-selection.md，
 * 代码里只留填空位置和判断依据。
 */
@Slf4j
@Service
public class CourseServiceImpl implements CourseService {

    /**
     * 返回码约定：{@code -2} 未预热 / {@code -1} 重复 / {@code 0} 售罄 / {@code >=0} 扣完后的余量。
     * <b>为什么不直接用 {@code DECR} 再判断返回值</b>：DECR 会先扣到负数，
     * 你还得再 INCR 还回去 —— 中间那次负数在并发下会被别的请求读到。
     * 而且 DECR 单独用挡不住"同一用户重复请求"，库存会被同一个人扣两次。
     * <p>
     * 项目里的先例写法：{@code FlowLimitFilter.COUNT_SCRIPT}、{@code LikeCountBuffer.ADD_DELTA_SCRIPT}。
     */
    private static final RedisScript<Long> SELECT_SCRIPT =
            RedisScript.of("""
            -- KEYS[1]=chosen  KEYS[2]=ready  ARGV[1]=uid  ARGV[2]=cap
            -- local cap = redis.call('GET', KEYS[2]) 这个加了redis预热之后再用
            -- if not cap then return -2 end
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then return -1 end
            local used = redis.call('SCARD', KEYS[1])
            if used >= tonumber(ARGV[2]) then return 0 end
            redis.call('SADD', KEYS[1], ARGV[1])
            return tonumber(ARGV[2]) - used          -- >=1，扣完后的剩余 = 返回值 - 1
            """, Long.class);

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    CourseMapper courseMapper;

    @Resource
    CourseSelectionMapper courseSelectionMapper;

    @Resource
    RabbitTemplate rabbitTemplate;

    /**
     * 课程列表。已实现 —— 不是这轮的重点，但能验证建表/种子数据/读路径。
     * <p>
     * remaining 现在用 {@code capacity - selectedCount} 算。接 Redis 之后要改成
     * 先读 {@code course:stock:{id}}，键不存在再用这个兜底：
     * <b>键不存在不能当成 0</b>，否则预热没做时全站显示"已满"。
     */
    @Override
    public List<CourseVo> listCourses() {
        List<Course> courses = courseMapper.selectList(
                new LambdaQueryWrapper<Course>().orderByAsc(Course::getId));
        return courses.stream()
                .map(c -> new CourseVo(
                        c.getId(),
                        c.getName(),
                        c.getTeacher(),
                        c.getCredit(),
                        c.getCapacity(),
                        c.getCapacity() - c.getSelectedCount(),
                        c.getSelectStart(),
                        c.getSelectEnd()))
                .toList();
    }

    @Override
    public CourseSelectResultVo select(int uid, int courseId) {

        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            return new CourseSelectResultVo(CourseSelectResultVo.NOT_STARTED, -2L);
        }
        long now   = System.currentTimeMillis();
        long start = course.getSelectStart().getTime();
        long end   = course.getSelectEnd().getTime();
        if (now < start) return new CourseSelectResultVo(CourseSelectResultVo.NOT_STARTED, -2L);
        if (now > end)   return new CourseSelectResultVo(CourseSelectResultVo.CLOSED, -2L);

        // 目前redis在开放中崩溃会导致比较剩余容量所用键消失，导致超卖，下次一定
        Long r = stringRedisTemplate.execute(SELECT_SCRIPT,
                                            List.of(Const.COURSE_CHOSEN + courseId
                                            ),
                                            String.valueOf(uid), String.valueOf(course.getCapacity()));

        if (r == null) {
            log.error("选课脚本返回 null：uid={}, courseId={}", uid, courseId);
            return new CourseSelectResultVo(CourseSelectResultVo.FAILURE, null);
        }

        if(r == 0) return new CourseSelectResultVo(CourseSelectResultVo.SOLD_OUT, r);
        if(r == -1) return new CourseSelectResultVo(CourseSelectResultVo.DUPLICATE, r);
        if(r == -2) return new CourseSelectResultVo(CourseSelectResultVo.NOT_STARTED, r);

        if(r >= 1) {
            try {
                rabbitTemplate.convertAndSend(Const.COURSE_SELECT_QUEUE,
                        Map.of("uid", uid, "courseId", courseId));
            } catch (Exception e) {
                // 扣减已经发生，发送失败必须把 chosen 还回去，否则名额永远卡住
                log.error("选课消息发送失败，回补 chosen：uid={}, courseId={}", uid, courseId, e);
                try {
                    stringRedisTemplate.opsForSet()
                            .remove(Const.COURSE_CHOSEN + courseId, String.valueOf(uid));
                } catch (Exception releaseError) {
                    log.error("回补 chosen 失败，名额可能泄漏：uid={}, courseId={}",
                            uid, courseId, releaseError);
                }
                return new CourseSelectResultVo(CourseSelectResultVo.FAILURE, r);
            }
            return new CourseSelectResultVo(CourseSelectResultVo.QUEUED, r - 1);
        }

        return new CourseSelectResultVo(CourseSelectResultVo.NOT_STARTED, null);
    }

    /**
     * 1. 先读 course:result:{courseId}:{uid}：
     *      "1" → SUCCESS
     *      "0" → 失败（具体原因可以在 value 里带，或者查 DB 区分）
     *      没有 → 说明还在排队，或者这个用户根本没参与
     * 2. 没有结果键时的判断顺序很关键：
     *      先查 db_course_selection（权威）：有记录 → SUCCESS，并回填结果键
     *      再查 course:chosen:{courseId} 里有没有这个 uid：
     *          有 → QUEUED（扣了库存、还没落库）
     *          没有 → NOT_STARTED / 未参与（HTTP 仍 200）
     * 3. 回填结果键要带 TTL：这是缓存不是账本，活动结束后自然过期。
     * 别在这里返回 404 表示"还没结果" —— 前端无法区分"没抢到"和"还在排队"。
     */
    @Override
    public CourseSelectResultVo result(int uid, int courseId) {
        String key = Const.COURSE_RESULT + courseId + ":" + uid;
        String value = stringRedisTemplate.opsForValue().get(key);

        if("0".equals(value)) {return new CourseSelectResultVo(CourseSelectResultVo.FAILURE, null);}
        if("1".equals(value)) {return new CourseSelectResultVo(CourseSelectResultVo.SUCCESS, null);}

        if(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(Const.COURSE_CHOSEN + courseId, String.valueOf(uid)))) {
            return new CourseSelectResultVo(CourseSelectResultVo.QUEUED, null);
        }

        if(courseSelectionMapper.selectCount(new LambdaQueryWrapper<CourseSelection>()
                .eq(CourseSelection::getUid, uid)
                .eq(CourseSelection::getCourseId, courseId)) > 0) {
            return new CourseSelectResultVo(CourseSelectResultVo.SUCCESS, null);
        }
        return new CourseSelectResultVo(CourseSelectResultVo.NOT_STARTED, null);
    }
}
