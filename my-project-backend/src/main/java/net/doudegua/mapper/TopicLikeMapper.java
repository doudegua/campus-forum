package net.doudegua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import net.doudegua.entity.dto.TopicLike;

@Mapper
public interface TopicLikeMapper extends BaseMapper<TopicLike> {

    /**
     * 点个赞。已经点过就什么都不做。
     * <p>
     * <b>返回值是关键</b>：它是有影响的行数。
     * <ul>
     *   <li>{@code 1} —— 这次真的插进去了，说明是"第一次点"，计数器该 +1</li>
     *   <li>{@code 0} —— 主键冲突、被 IGNORE 掉了，说明"早就点过了"，计数器不该动</li>
     * </ul>
     * 这一条 SQL 同时完成了"判断有没有点过"和"点上"两件事，
     * 中间没有并发窗口 —— 这正是它值得单独写个 {@code @Insert} 的原因。
     * <p>
     * 换成先 {@code selectCount} 判断、再 {@code insert} 的写法会有 TOCTOU：
     * 两个人同时点，都查到"没点过"，然后都去插入，一个成功一个报主键冲突
     * （或者更糟，计数器被 +2）。唯一索引就是数据库替你加的那把锁，
     * 用它比用分布式锁便宜得多。
     * <p>
     * {@code IGNORE} 会把**所有**可恢复的错误降级成警告，不只是唯一键冲突：
     * 值超长会被截断、类型不对会用默认值、NULL 插进 NOT NULL 列会用默认值 ——
     * 全都不报错。所以它只该用在"我明确知道这里唯一可能出问题的就是唯一键"的地方。
     * 这张表正好符合：两列都是 int，不存在超长和类型问题。
     * <p>
     * 如果哪天真要往一张有 varchar 的表上用这个模式，换成
     * {@code INSERT ... ON DUPLICATE KEY UPDATE <某个字段> = <它自己>} ——
     * 它对重复键的处理一样（影响行数同样是 0），但其他错误照常抛。
     * 代价是语法更绕、可读性差。
     * <p>
     * 另外 {@code INSERT IGNORE} 是 <b>MySQL 方言</b>，不是标准 SQL。
     * PostgreSQL 里对应的是 {@code ON CONFLICT DO NOTHING}。
     */
    @Insert("INSERT IGNORE INTO db_topic_like (uid, topic_id, created_at) VALUES (#{uid}, #{topicId}, NOW())")
    int insertIgnore(@Param("uid") int uid, @Param("topicId") int topicId);
}
