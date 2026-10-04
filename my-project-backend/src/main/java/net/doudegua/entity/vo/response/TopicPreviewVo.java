package net.doudegua.entity.vo.response;

import lombok.Data;
import lombok.NoArgsConstructor;
import net.doudegua.entity.dto.Topic;

import java.util.Date;

/**
 * 列表里的一条帖子。
 * <p>
 * ⚠️ 这个 VO 里有一个字段是**依赖"谁在看"的**：{@code liked}。
 * 列表响应里有这种字段，就意味着**它不能被公共缓存**了 ——
 * 你和我请求同一页，答案不一样。
 * <p>
 * 现在不用管（还没做缓存），但以后给列表加缓存时，第一件撞上的就是这个。
 * 到时候两条路：要么缓存里不含 {@code liked}、读出来再补一次批次查询；
 * 要么 key 带上用户 id（那等于每人一份，缓存就没什么意义了）。
 */
@Data
@NoArgsConstructor
public class TopicPreviewVo {
    Integer id;
    String title;
    String typeName;
    String authorName;
    Date time;

    /** 评论数。跟着 topic 那一行一起回来，不花额外查询 */
    Integer commentCount;
    /** 点赞数。同上 */
    Integer likeCount;
    /**
     * <b>"我"点过赞没有。</b>
     * <p>
     * 它没法从 topic 那一行推出来（那行里没有"我"），所以得单独查。
     * 但列表一次 20 条，**绝不能一条一条查** —— 那是 20 次查询。
     * 做法是：把这 20 条的 id 收集起来，一次 {@code WHERE uid = ? AND topic_id IN (...)} 查完。
     * 见 {@code TopicServiceImpl.fetchTopicPreviewList}。
     */
    Boolean liked;

    /**
     * 名字由外面查好了传进来 —— VO 自己不查库。
     * {@code liked} 也一样，由外面批次查好后 set 进来（这里是 null，调用方补）。
     */
    public TopicPreviewVo(Topic topic, String typeName, String authorName) {
        this.id = topic.getId();
        this.title = topic.getTitle();
        this.typeName = typeName;
        this.authorName = authorName;
        this.time = topic.getTime();
        this.commentCount = topic.getCommentCount();
        this.likeCount = topic.getLikeCount();
    }
}
