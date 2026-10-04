package net.doudegua.entity.vo.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.doudegua.entity.dto.Topic;

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
    /**
     * <b>"我"点过赞没有。</b>
     * <p>
     * 注意这是这个 VO 里<b>第一个依赖"谁在看"的字段</b>：同一条帖子，
     * 你和我请求它，这个值不一样。前面所有字段都是"谁来问都一样"。
     * <p>
     * 它由 service 单独设进来（不能从 topic 那一行推出来，那行里没有"我"的信息）。
     * 这也是为什么 {@code fetchTopic} 那个 {@code viewerId} 参数终于有了用处 ——
     * 之前它一直是个"先留着"的空参数。
     * <p>
     * 顺带一提：只要 VO 里有了这种字段，这个响应就**不能被公共缓存**了。
     * 要么缓存里不含它、读出来再补一次查询，要么缓存 key 带上用户 id（等于没缓存）。
     * 现在不用管，但以后给详情页加缓存时，第一件撞上的就是这个。
     */
    Boolean liked;

    /**
     * 名字和头像由外面查好了传进来 —— <b>VO 自己不查库</b>，
     * 这也是为什么参数比 {@code Topic} 多：多出来的两个值是 topic 里根本没有的。
     * 和 {@link TopicPreviewVo} 是同一个约定。
     */
    public TopicDetailVo(Topic topic, String typeName, String authorName, String authorAvatar) {
        this.id = topic.getId();
        this.title = topic.getTitle();
        this.content = topic.getContent();
        this.typeName = typeName;
        this.authorId = topic.getUid();
        this.authorName = authorName;
        this.authorAvatar = authorAvatar;
        this.time = topic.getTime();
        // 这两个不是"查好了传进来"的，是跟着 topic 那一行一起回来的
        this.commentCount = topic.getCommentCount();
        this.likeCount = topic.getLikeCount();
    }
}
