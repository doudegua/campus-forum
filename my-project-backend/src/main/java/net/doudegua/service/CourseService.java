package net.doudegua.service;

import net.doudegua.entity.vo.response.AdminCourseVo;
import net.doudegua.entity.vo.response.CourseSelectResultVo;
import net.doudegua.entity.vo.response.CourseVo;

import java.util.List;

/**
 * 抢课。
 * <p>
 * 三个方法对应三个接口，也对应三种读法：
 * <ul>
 *   <li>{@link #listCourses()} —— 读多写少，展示用</li>
 *   <li>{@link #select(int, int)} —— 写热点，唯一需要抗并发的地方</li>
 *   <li>{@link #result(int, int)} —— 异步结果查询，前端轮询</li>
 * </ul>
 * <b>为什么 select 要拆成"同步返回结果"和"异步查询结果"两个方法</b>：
 * 真正的落库发生在 MQ 消费端，HTTP 线程只能保证"消息发出去了"。
 * 如果接口要等落库完成才返回，那就把 DB 的延迟又接回 HTTP 线程，
 * MQ 白加了 —— 限流的意义就在于"承认这件事现在做不完"。
 */
public interface CourseService {

    /** 课程列表。remaining 的来源见 {@code CourseVo} 的注释 */
    List<CourseVo> listCourses();

    /** 管理员课程列表：多返回 ready 和 chosen 人数，供预热/关停页面使用 */
    List<AdminCourseVo> listAdminCourses();

    /**
     * 抢课入口。
     *
     * @return 一定是非 null 的结果对象；失败用 status 表达，不抛业务异常
     */
    CourseSelectResultVo select(int uid, int courseId);

    /** 查最终结果。前端每隔几百毫秒调一次，直到 status 不再是 QUEUED */
    CourseSelectResultVo result(int uid, int courseId);
}
