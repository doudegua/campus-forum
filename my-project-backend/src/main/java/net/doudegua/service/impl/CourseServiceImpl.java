package net.doudegua.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.entity.dto.Course;
import net.doudegua.entity.dto.CourseSelectTask;
import net.doudegua.entity.dto.CourseSelection;
import net.doudegua.entity.vo.request.CourseCreateVo;
import net.doudegua.entity.vo.response.AdminCourseVo;
import net.doudegua.entity.vo.response.CourseSelectResultVo;
import net.doudegua.entity.vo.response.CourseVo;
import net.doudegua.entity.vo.response.GrabLuaResult;
import net.doudegua.mapper.CourseMapper;
import net.doudegua.mapper.CourseSelectionMapper;
import net.doudegua.service.CourseService;
import net.doudegua.utils.Const;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
     * 返回码：{@code -2} 未就绪 / {@code -3} 未开始 / {@code -4} 已结束 /
     * {@code -1} 重复 / {@code 0} 售罄 / {@code >=1} 成功（剩余 = 返回值 - 1）。
     * <p>
     * 所有判断必须在同一个脚本里完成：ready、时间窗、查重、余量、扣减。
     * 拆开就是 TOCTOU。
     * <p>
     * 字段名在这里硬编码（ready/capacity/start/end），预热写入时用的是
     * {@code Const.COURSE_META_*}, 两处必须一致。
     */
    private static final RedisScript<Long> SELECT_SCRIPT =
            RedisScript.of("""
            -- KEYS[1]=chosen  KEYS[2]=meta  ARGV[1]=uid  ARGV[2]=nowMillis
            if redis.call('HGET', KEYS[2], 'ready') ~= '1' then return -2 end

            local cap   = tonumber(redis.call('HGET', KEYS[2], 'capacity'))
            local start = tonumber(redis.call('HGET', KEYS[2], 'start'))
            local endT  = tonumber(redis.call('HGET', KEYS[2], 'end'))
            if not cap or not start or not endT then return -2 end

            local now = tonumber(ARGV[2])
            if now < start then return -3 end
            if now > endT  then return -4 end

            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then return -1 end
            local used = redis.call('SCARD', KEYS[1])
            if used >= cap then return 0 end
            redis.call('SADD', KEYS[1], ARGV[1])
            return cap - used
            """, Long.class);

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    CourseMapper courseMapper;

    @Resource
    CourseSelectionMapper courseSelectionMapper;

    /**
     * 抢课消息生产者。用 {@code @Resource} 按名字注入而不是按类型/泛型匹配：
     * Boot 自动配置的 KafkaTemplate Bean 声明类型是 {@code KafkaTemplate<?, ?>}，
     * 按类型注入 {@code KafkaTemplate<String, Object>} 容易在泛型匹配上报错。
     * 运行时的序列化仍由 yaml 里的 StringSerializer / JsonSerializer 决定。
     */
    @Resource
    KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 等发送结果的上限。必须大于 producer 的 {@code delivery.timeout.ms}（5 秒），
     * 否则 future 超时的时候消息可能还在 producer 缓冲区里，稍后仍然会投递成功 ——
     * 那时我们已经回补了 chosen 并返回 FAILURE，DB 却可能再插入一条，账就乱了。
     */
    private static final long SEND_TIMEOUT_SECONDS = 6;

    @Override
    public int createCourse(CourseCreateVo vo) {
        String name = vo.getName() == null ? "" : vo.getName().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("课程名不能为空");
        }
        if (!vo.getSelectEnd().after(vo.getSelectStart())) {
            throw new IllegalArgumentException("结束时间必须晚于开始时间");
        }

        Long exists = courseMapper.selectCount(
                new LambdaQueryWrapper<Course>().eq(Course::getName, name));
        if (exists != null && exists > 0) {
            throw new IllegalArgumentException("课程名已存在");
        }

        Course course = new Course();
        course.setName(name);
        course.setTeacher(vo.getTeacher() == null ? "" : vo.getTeacher().trim());
        course.setCredit(vo.getCredit());
        course.setCapacity(vo.getCapacity());
        course.setSelectedCount(0);
        course.setSelectStart(vo.getSelectStart());
        course.setSelectEnd(vo.getSelectEnd());

        try {
            courseMapper.insert(course);
        } catch (DuplicateKeyException e) {
            // 并发建同名课时，唯一索引是最后一道防线
            throw new IllegalArgumentException("课程名已存在");
        }
        return course.getId();
    }

    /**
     * 课程列表。
     * <p>
     * remaining 优先读 Redis：ready=1 时用 {@code meta.capacity - SCARD(chosen)}；
     * 没预热（meta 不存在/ready 不是 1）才回 DB 的 {@code capacity - selectedCount}。
     * 不能把“key 不存在”当成 0 个名额，否则没预热时全站显示已满。
     */
    @Override
    public List<CourseVo> listCourses() {
        List<Course> courses = courseMapper.selectList(
                new LambdaQueryWrapper<Course>().orderByAsc(Course::getId));
        return courses.stream()
                .map(c -> {
                    Map<Object, Object> meta = stringRedisTemplate.opsForHash()
                            .entries(Const.COURSE_META + c.getId());
                    boolean ready = "1".equals(meta.get(Const.COURSE_META_READY));
                    int capacity = intValue(meta.get(Const.COURSE_META_CAPACITY), c.getCapacity());
                    int selected = c.getSelectedCount() == null ? 0 : c.getSelectedCount();
                    int remaining = ready
                            ? Math.max(0, capacity - chosenCount(c.getId()))
                            : Math.max(0, capacity - selected);
                    return new CourseVo(
                            c.getId(),
                            c.getName(),
                            c.getTeacher(),
                            c.getCredit(),
                            capacity,
                            remaining,
                            c.getSelectStart(),
                            c.getSelectEnd(),
                            ready);
                })
                .toList();
    }

    /** 管理员列表：DB 已落库人数 + Redis 已预扣人数同时给出，方便看差值。 */
    @Override
    public List<AdminCourseVo> listAdminCourses() {
        List<Course> courses = courseMapper.selectList(
                new LambdaQueryWrapper<Course>().orderByAsc(Course::getId));
        return courses.stream()
                .map(c -> {
                    Map<Object, Object> meta = stringRedisTemplate.opsForHash()
                            .entries(Const.COURSE_META + c.getId());
                    boolean ready = "1".equals(meta.get(Const.COURSE_META_READY));
                    int capacity = intValue(meta.get(Const.COURSE_META_CAPACITY), c.getCapacity());
                    int selected = c.getSelectedCount() == null ? 0 : c.getSelectedCount();
                    int chosen = chosenCount(c.getId());
                    int remaining = ready
                            ? Math.max(0, capacity - chosen)
                            : Math.max(0, capacity - selected);
                    return new AdminCourseVo(
                            c.getId(),
                            c.getName(),
                            c.getTeacher(),
                            c.getCredit(),
                            capacity,
                            selected,
                            chosen,
                            remaining,
                            ready,
                            c.getSelectStart(),
                            c.getSelectEnd());
                })
                .toList();
    }

    private int chosenCount(int courseId) {
        Long n = stringRedisTemplate.opsForSet().size(Const.COURSE_CHOSEN + courseId);
        return n == null ? 0 : n.intValue();
    }

    private int intValue(Object raw, int fallback) {
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public CourseSelectResultVo select(int uid, int courseId) {

        Long r = stringRedisTemplate.execute(SELECT_SCRIPT,
                                            List.of(Const.COURSE_CHOSEN + courseId,
                                                    Const.COURSE_META + courseId),
                                            String.valueOf(uid),
                                            String.valueOf(System.currentTimeMillis()));

        if (r == null) {
            log.error("选课脚本返回 null：uid={}, courseId={}", uid, courseId);
            return new CourseSelectResultVo(CourseSelectResultVo.FAILURE, null);
        }

        GrabLuaResult result = GrabLuaResult.from(r);

        if (result.isSuccess()) {
            try {
                // key 用 courseId：同一门课的消息进同一个分区，消费端天然串行；
                // 不同课程可以并行。key 用 uid 的话，同一门课的并发落库会互相打脸。
                kafkaTemplate.send(Const.COURSE_SELECT_TOPIC,
                                String.valueOf(courseId),
                                new CourseSelectTask(uid, courseId))
                        // 必须等 future：KafkaTemplate 的失败分两类，一类 send() 时同步抛，
                        // 另一类（delivery.timeout 到期、broker 拒绝）只体现在 future 上。
                        // 不等的话，"接口返回 QUEUED、消息其实没发出去"会完全静默。
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                // 扣减已经发生，发送失败必须把 chosen 还回去，否则名额永远卡住。
                // 中断标记要还回去，否则线程池里的后续任务会一直被打断。
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.error("选课消息发送失败，回补 chosen：uid={}, courseId={}", uid, courseId, e);
                releaseChosen(uid, courseId);
                return new CourseSelectResultVo(CourseSelectResultVo.FAILURE, r);
            }
        }

        return new CourseSelectResultVo(result.getStatus(), GrabLuaResult.remaining(r));
    }

    /**
     * 把 Lua 预扣的名额还回去。
     * <p>
     * 回补本身也可能失败（Redis 挂了），这时只能记 ERROR —— 名额泄漏比抛异常
     * 更糟，但抛出去也无法改变已经发生的事实。日志里带 uid/courseId，方便对账。
     */
    private void releaseChosen(int uid, int courseId) {
        try {
            stringRedisTemplate.opsForSet()
                    .remove(Const.COURSE_CHOSEN + courseId, String.valueOf(uid));
        } catch (Exception releaseError) {
            log.error("回补 chosen 失败，名额可能泄漏：uid={}, courseId={}",
                    uid, courseId, releaseError);
        }
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
