package net.doudegua.entity.dto;

import lombok.Data;

import java.util.Date;

/**
 * 详情页查询的中间结果：帖子本身 + 作者名 + 作者头像。
 * <p>
 * 为什么需要它：原来详情页要 <b>4 条查询</b>才凑齐这些数据 ——
 * {@code getById(topic)}、{@code getById(type)}、{@code getById(author)}、
 * {@code findAccountProfileById(author)}。压测实测每多一条 SQL 就多约 1.69ms CPU
 * （其中 MySQL 执行只要 0.6-0.9ms，大头是 JDBC 往返和 MyBatis 映射的固定开销）。
 * 用一次 JOIN 把作者和头像合进来，直接省掉两条。
 * <p>
 * 和列表页的 {@link TopicWithAuthor} 的唯一区别：这里多了 {@code authorAvatar}，
 * 因为它多 JOIN 了一张 {@code db_account_details}。
 * <p>
 * ⚠️ 有 {@code content}！列表页不需要正文，详情页**必须**有 ——
 * 少了这一列，页面就是空的。
 * <p>
 * ⚠️ 只有 {@code @Data}（它自带无参构造），<b>不要加 {@code @AllArgsConstructor}</b>：
 * 一旦只有全参构造，MyBatis 会走"构造函数映射"，结果集少一列就 500。
 * （这个坑在 {@code TopicLike} 上踩过。）
 */
@Data
public class TopicDetailRow {

    Integer id;
    String title;
    /** 版块 id，对应 db_topic_type.id */
    Integer type;
    /** 正文 HTML。详情页必须要有 */
    String content;
    Integer uid;
    Date time;
    Integer commentCount;
    Integer likeCount;

    /** 来自 {@code db_account.username}。作者被删了就是 null */
    String authorName;
    /**
     * 来自 {@code db_account_details.avatar}（MinIO 的对象名）。
     * <p>
     * 两种情况会是 null：这个人没填过资料（那张表没有他的行），
     * 或者填了但没传头像。前端这时候显示默认头像。
     */
    String authorAvatar;
}
