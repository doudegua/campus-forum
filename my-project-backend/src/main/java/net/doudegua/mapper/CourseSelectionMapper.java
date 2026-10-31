package net.doudegua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.doudegua.entity.dto.CourseSelection;
import org.apache.ibatis.annotations.Mapper;

/**
 * 选课记录 Mapper。
 * <p>
 * ⚠️ <b>核心 TODO（三段里的第 3 段）：幂等插入。</b>同样先留空。
 * 写法照抄 {@code TopicLikeMapper.insertIgnore}：
 * <pre>
 * &#64;Insert("INSERT IGNORE INTO db_course_selection (uid, course_id, status, created_at) " +
 *         "VALUES (#{uid}, #{courseId}, 1, NOW())")
 * int insertIgnore(@Param("uid") int uid, @Param("courseId") int courseId);
 * </pre>
 * <b>返回值 1 / 0 是整个消费端的分岔点</b>：
 * <ul>
 *   <li>{@code 1} —— 这条消息第一次处理，接下来才允许给 {@code db_course.selected_count} +1</li>
 *   <li>{@code 0} —— 重复投递（或这人早就抢到了），直接确认掉这条消息，
 *       <b>绝不能再 +1</b>，否则一次重复投递就吃掉一个名额</li>
 * </ul>
 * 这条 SQL 和复合主键合起来，才让"同一个消息投递几次结果都一样"成立。
 * <p>
 * {@code INSERT IGNORE} 会把所有可恢复错误降级成警告（超长、类型、NOT NULL），
 * 这里两列都是 int，不存在那些问题，可以放心用；换成 varchar 就得改用
 * {@code ON DUPLICATE KEY UPDATE}。
 */
@Mapper
public interface CourseSelectionMapper extends BaseMapper<CourseSelection> {

    // TODO(核心 3/3)：在这里补上 insertIgnore，SQL 见类注释。
}
