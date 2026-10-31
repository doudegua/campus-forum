package net.doudegua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.doudegua.entity.dto.Course;
import org.apache.ibatis.annotations.Mapper;

/**
 * 课程表 Mapper。
 * <p>
 * 这里只有 {@code BaseMapper} 白送的方法（{@code selectList / selectById}），
 * 够 {@code GET /api/course/list} 用了。
 * <p>
 * ⚠️ <b>核心 TODO（三段里的第 2 段）：条件 UPDATE。</b>骨架阶段故意不写，
 * 让应用能编译、能启动；写的时候把下面两行注释取消并补上 {@code @Update} 和 import：
 * <pre>
 * &#64;Update("UPDATE db_course SET selected_count = selected_count + 1 " +
 *         "WHERE id = #{id} AND selected_count &lt; capacity")
 * int incrementSelectedIfAvailable(@Param("id") int id);
 * </pre>
 * <b>为什么必须是这一条语句</b>：它把"判断还有没有名额"和"把名额用掉"压进了
 * 同一个原子操作。换成先 {@code selectById} 看余量、再 {@code updateById}，
 * 中间就是一个 TOCTOU 窗口 —— 两个消费者同时看到余量 1，都会写，结果超卖。
 * 这个坑在点赞那里被咬过一次（AGENT.md 第六节）。
 * <p>
 * <b>返回值是判断成败的唯一依据</b>：1 = 占位成功，0 = 名额已满（或课程不存在），
 * 0 不是异常，是"Redis 和 DB 账本对不上"的信号，调用方要回补 Redis。
 * <p>
 * <b>为什么不是乐观锁版本号 + 重试</b>：所有抢同一门课的人都改同一行，
 * 版本号 CAS 会集体失败、集体重试，重试风暴比行锁更糟。为什么不 {@code SELECT ... FOR UPDATE}：
 * 那是把并发变串行，而且锁要一直持有到事务提交。详见 docs/course-selection.md。
 */
@Mapper
public interface CourseMapper extends BaseMapper<Course> {

    // TODO(核心 2/3)：在这里补上 incrementSelectedIfAvailable，SQL 见类注释。
}
