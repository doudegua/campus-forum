package net.doudegua.entity.vo.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.doudegua.entity.dto.Topic;
import net.doudegua.entity.dto.TopicDetailRow;

import java.util.Date;

/**
 * 帖子详情。
 * <p>
 * 它和 {@link Topic} <b>不是一回事</b>，所以不能直接返回实体，也不能只靠 topic 造出来：
 * <ul>
 *   <li>{@code Topic.type} 是类型 id，这里要给 {@code typeName} ——
 *       否则前端为了显示一个标签还得再去拉一次类型表</li>
 *   <li>{@code Topic.uid} 是作者 id，这里要给 {@code authorName} / {@code authorAvatar}，
 *       同时<b>保留</b> {@code authorId}，因为"点作者进主页"要用它</li>
 *   <li>正文只有详情页才给，所以这个 VO 不能和 {@link TopicPreviewVo} 合并 ——
 *       列表页一次 20 条，把 HTML 正文也带上纯属浪费</li>
 * </ul>
 * <p>
 * 点赞数、评论数、"我点没点过赞"要等 db_topic_like / db_comment 建好之后再加。
 * 现在不加的理由很实在：没有数据源。加一个恒为 0 的字段等于在 VO 里撒谎，
 * 前端会照着它渲染出一个"0 赞"，而那个 0 什么都不代表。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TopicDetailVo {
    Integer id;
    String title;
    /**
     * Quill 输出的 HTML，前端用 v-html 渲染。
     * <p>
     * ⚠️ 渲染之前必须先做服务端白名单清洗（在<b>写入时</b>做，不是读取时）：
     * 列表页不显示正文所以没事，一旦上 v-html 这就是个真口子 ——
     * 接口是裸的，一条 curl 就能存进去 {@code <img src=x onerror=...>}。
     */
    String content;
    /**
     * 帖子类型的 <b>id</b>，对应 db_topic_type.id。
     * <p>
     * 和下面的 {@code typeName} 是两件事，都得给：名字是给人看的，id 是给
     * "编辑抽屉把下拉框预选上"用的。只给名字的话前端就得拿名字去类型列表里
     * 反查 id —— 重名会选错，类型改过名之后反查出来的 id 还可能根本对不上。
     */
    Integer type;
    String typeName;
    /** 作者 id。点作者进主页要用它，所以不能只给名字 */
    Integer authorId;
    String authorName;
    /**
     * MinIO 里的对象名（形如 {@code /avatar/xxx}），上传头像时存的就是这个值。
     * 可能为 null —— null 时前端显示默认头像。
     */
    String authorAvatar;
    Date time;
    /**
     * 评论数。直接取自 {@code db_topic.comment_count} 那个冗余计数列 ——
     * 详情页的 getById 已经把这行捞回来了，所以它不花任何额外查询。
     * <p>
     * 这就是那个列存在的全部理由：不然每看一次帖子都要 COUNT(*) 一遍。
     * 换句话说，<b>这一列本身就是缓存</b>，不用再往 Redis 上想。
     */
    Integer commentCount;
    /**
     * 点赞数。和 commentCount 一样，跟着 topic 那一行一起回来，不花额外查询。
     */
    Integer likeCount;

    /*
     * ⚠️ 这里【故意没有】liked 字段 —— 这是本次改造的核心。
     *
     * 原来有，而且它是整个 VO 里唯一一个依赖"谁在看"的字段。只要它在，
     * 这个响应就【不能被公共缓存】：缓存下来的 liked 是所有用户共享的，
     * A 点过赞，B 也会看到 true。
     *
     * 这不是假想。用 TtlCache 缓存整个 VO 后实测过：1175 个请求里
     * 247 个（21%）返回了别人的点赞状态，而 HTTP 全是 200、错误率 0%
     * —— 不报任何错，数据就是错的。
     *
     * 现在 liked 走独立接口 GET /api/forum/topic/{id}/liked，
     * 于是这个 VO 变成"谁来问都一样"，可以整体缓存。
     * 前端加载完详情后单独问一次 liked。
     */

    /**
     * 名字和头像由外面查好了传进来 —— <b>VO 自己不查库</b>，
     * 这也是为什么参数比 {@code Topic} 多：多出来的两个值是 topic 里根本没有的。
     * 和 {@link TopicPreviewVo} 是同一个约定。
     */
    public TopicDetailVo(Topic topic, String typeName, String authorName, String authorAvatar) {
        this.id = topic.getId();
        this.title = topic.getTitle();
        this.content = topic.getContent();
        // type 是 id（topic 行里本来就有），typeName 是外面查好了传进来的名字。
        // 一个来自 topic、一个来自参数，别把两者弄混
        this.type = topic.getType();
        this.typeName = typeName;
        this.authorId = topic.getUid();
        this.authorName = authorName;
        this.authorAvatar = authorAvatar;
        this.time = topic.getTime();
        // 这两个不是"查好了传进来"的，是跟着 topic 那一行一起回来的
        this.commentCount = topic.getCommentCount();
        this.likeCount = topic.getLikeCount();
    }

    /**
     * 详情页现在的构造方式：作者名和头像已经在同一条 SQL 里 JOIN 出来了。
     * <p>
     * 老构造器保留着 —— 万一将来有别的地方只有 {@code Topic} 而没走 JOIN 查询，
     * 还能用它。但 {@code fetchTopic} 已经改走这一条了。
     */
    public TopicDetailVo(TopicDetailRow row, String typeName) {
        this.id = row.getId();
        this.title = row.getTitle();
        this.content = row.getContent();
        this.type = row.getType();
        this.typeName = typeName;
        this.authorId = row.getUid();
        this.authorName = row.getAuthorName();
        this.authorAvatar = row.getAuthorAvatar();
        this.time = row.getTime();
        this.commentCount = row.getCommentCount();
        this.likeCount = row.getLikeCount();
    }
}
