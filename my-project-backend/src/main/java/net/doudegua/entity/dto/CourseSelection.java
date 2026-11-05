package net.doudegua.entity.dto;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 一条选课记录 —— 这门课"谁选上了"的<b>权威数据</b>。
 * <p>
 * <b>这里没有 {@code @TableId}。</b>主键是 {@code (uid, course_id)} 复合主键，
 * 而 MyBatis-Plus 的 {@code @TableId} 只能标一个字段。
 * 用时注意：不要调用 {@code getById / updateById / deleteById} 这类
 * "靠单一主键定位"的方法，改用带条件的 Wrapper 或自己写 SQL。
 * （同样的写法先例是 {@code TopicLike}，那里踩过构造函数映射的坑，
 * 所以这里显式保留 {@code @NoArgsConstructor}。）
 * <p>
 * 复合主键不只是"防重"：它是<b>幂等消费的支点</b>。MQ 重复投递时，
 * {@code INSERT IGNORE} 影响 0 行，消费者就知道"这人是老消息，别再 +1"。
 * 如果只靠应用层"先查有没有"，重复消息在并发下照样能挤进去。
 */
@Data
@NoArgsConstructor
@TableName("db_course_selection")
public class CourseSelection {

    /** 选课学生，对应 db_account.id */
    private Integer uid;

    /** 课程，对应 db_course.id */
    private Integer courseId;

    /** 1 = 已选上。0/2 先留给退课和候补，这一轮用不到 */
    private Integer status;

    private Date createdAt;
}
