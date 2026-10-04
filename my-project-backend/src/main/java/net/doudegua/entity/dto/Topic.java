package net.doudegua.entity.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;

@Data
@TableName("db_topic")
@AllArgsConstructor
public class Topic {
    @TableId(type = IdType.AUTO)
    Integer id;
    String title;
    /** 帖子类型 id，对应 db_topic_type.id。列名就叫 type —— TYPE 不是 MySQL 保留字（DESC 才是） */
    Integer type;
    /**
     * 正文。Quill 输出的 HTML，图片以 {@code <img src="/api/image/{uuid}">} 的形式嵌在里面。
     * 也就是说图文关系是隐式的，想知道一个帖子用了哪些图必须解析这段 HTML。
     */
    String content;
    /** 作者，对应 db_account.id */
    Integer uid;
    Date time;
    /**
     * 评论数。<b>冗余计数列</b>，不是查出来的。
     * <p>
     * 它存在的唯一意义就是让详情页不用每次 {@code COUNT(*)} ——
     * 这一列本身就是那个"缓存"，所以别再往 Redis 上想一层。
     * <p>
     * 维护方式：发评论时和 INSERT 放同一个事务，用
     * {@code UPDATE db_topic SET comment_count = comment_count + 1} 原子加一。
     * 别写成"先读出来、在 Java 里 +1、再写回去"—— 并发下会丢更新。
     * <p>
     * 字段放在这儿（而不是让 CommentService 去 COUNT）是有意的：
     * 详情页本来就 {@code getById} 一次，计数跟着这一行一起回来，
     * 一次查询搞定。要是拿不到它，那个列就白加了。
     */
    Integer commentCount;
    /**
     * 点赞数。和 {@link #commentCount} 同一个套路：冗余计数列，避免每次 COUNT(*)。
     * <p>
     * 但它是本项目第一个**写热点** —— 热门帖子被同时点赞时，
     * 所有请求都串行在 {@code UPDATE db_topic SET like_count = like_count + 1 WHERE id = ?}
     * 那一行的行锁上。等真压出问题，再上 Redis INCR + 异步落库。
     */
    Integer likeCount;
}
