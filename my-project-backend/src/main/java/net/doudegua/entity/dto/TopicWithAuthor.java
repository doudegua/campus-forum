package net.doudegua.entity.dto;

import lombok.Data;

import java.util.Date;

/**
 * 列表查询的中间结果：帖子本身 + 作者名。
 * <p>
 * 为什么需要这个类：列表页每行既要帖子字段（标题、时间、计数），又要作者的用户名。
 * 原来是<b>两条 SQL</b> —— 先查 21 条帖子，再把 21 个 uid 收集起来查一次 {@code db_account}。
 * 压测实测这条额外的查库要花约 1.7ms CPU（其中 MySQL 执行只要 0.9ms，
 * 剩下全是 JDBC 往返和 MyBatis 映射的固定开销），而列表接口总共才 4 条 SQL。
 * 用一次 JOIN 把它合进来，直接省掉这条查询。
 * <p>
 * 字段名刻意和 {@code db_topic} 的列一一对应（SQL 里用 AS 别名对齐驼峰），
 * 这样 MyBatis 不用额外配置就能映射。
 * <p>
 * ⚠️ 它有 {@code @Data}（含无参构造），没有 {@code @AllArgsConstructor} ——
 * 这是给 MyBatis 反序列化用的，不是给业务代码 new 的。别给它加全参构造：
 * 一旦只有全参构造，MyBatis 会走"构造函数映射"，结果集少一列就 500。
 * （这个坑在 {@code TopicLike} 上踩过。）
 */
@Data
public class TopicWithAuthor {

    Integer id;
    String title;
    /** 版块 id，对应 db_topic_type.id */
    Integer type;
    String content;
    Integer uid;
    Date time;
    Integer commentCount;
    Integer likeCount;

    /** 来自 {@code db_account.username}，JOIN 出来的。作者被删了就是 null */
    String authorName;
}
