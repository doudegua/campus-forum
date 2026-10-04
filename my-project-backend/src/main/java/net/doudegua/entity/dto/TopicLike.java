package net.doudegua.entity.dto;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 一条点赞记录。
 * <p>
 * <b>注意这里没有 {@code @TableId}。</b>这张表的主键是 (uid, topic_id) 复合主键，
 * 而 MyBatis-Plus 的 {@code @TableId} 只能标一个字段。
 * 不标是可行的 —— 前提是别用 {@code getById / updateById / deleteById} 这类
 * "靠单一主键定位"的方法，改用 {@code Wrapper} 带条件的那种。
 * 本项目点赞只用 insert / delete(条件) / 按两列查，正好都不需要它。
 * <p>
 * 关于两个构造函数：{@code @NoArgsConstructor} <b>不是可有可无的</b>。
 * 只有 {@code @AllArgsConstructor} 时，MyBatis 会用「构造函数自动映射」，
 * 于是**结果集里必须包含实体的每一个字段**，少一列就报
 * {@code The constructor takes '3' arguments, but there are only '1' columns}。
 * 有了无参构造，MyBatis 才会退回"无参构造 + setter"那套，缺的列留 null。
 * （这个坑真踩过：一条只 {@code select} 单列的批次查询直接把接口打成 500。）
 */
@Data
@TableName("db_topic_like")
@AllArgsConstructor
@NoArgsConstructor
public class TopicLike {
    /** 点赞的人 db_account.id */
    Integer uid;
    /** 被点赞的帖子 db_topic.id */
    Integer topicId;
    Date createdAt;
}
