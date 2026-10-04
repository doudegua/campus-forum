package net.doudegua.service;

import com.baomidou.mybatisplus.extension.service.IService;
import net.doudegua.entity.dto.TopicLike;
import net.doudegua.entity.vo.response.TopicLikeVo;

import java.util.Collection;
import java.util.Set;

/**
 * 帖子点赞。
 * <p>
 * 这个接口的注释是**规格说明**：它描述的是"必须成立什么"，
 * 不是"该怎么写"。实现方式归 {@code TopicLikeServiceImpl}。
 *
 * <h3>为什么单独一个 service，而不是塞进 TopicService</h3>
 * 判据还是"管什么数据"：{@code db_topic_like} 是它自己的一张表，
 * 所以它自己一个 service。它需要顺手更新 {@code db_topic.like_count}，
 * 但那是"借别人的表写一个计数"，和 {@code CommentService} 更新
 * {@code comment_count} 是同一件事，用的是同一套写法。
 *
 * <h3>方法返回值约定（和本项目其它 service 一致）</h3>
 * 返回对象 = 成功；返回 {@code null} = 帖子不存在。
 * 失败原因用字符串表达的那套在这里用不上 —— 点赞没有"参数不合法"这种
 * 需要给用户看的原因，只有"帖子没了"和"成功"两种。
 */
public interface TopicLikeService extends IService<TopicLike> {

    /**
     * 点赞。<b>必须是幂等的</b>：已经点过的人再点一次，不能报错，也不能让计数变大。
     * <p>
     * 实现要满足的三条：
     * <ol>
     *   <li>帖子不存在时返回 {@code null}，并且<b>不能留下任何痕迹</b>
     *       （不能先点了赞才发现帖子不在）</li>
     *   <li>只有"这次真的新增了一条记录"才给 {@code db_topic.like_count} 加一。
     *       重复点赞时计数<b>一个都不能多</b></li>
     *   <li>写点赞记录和改计数必须在<b>同一个事务</b>里 ——
     *       否则插进去一条记录、加一失败，之后就永远对不上了</li>
     * </ol>
     * <p>
     * 提示：{@link net.doudegua.mapper.TopicLikeMapper#insertIgnore} 的返回值
     * 就是"这次是真新增还是重复"的答案，读一下它的注释。
     *
     * @return 操作后的新状态；帖子不存在时返回 null
     */
    TopicLikeVo like(int uid, int topicId);

    /**
     * 取消点赞。<b>同样必须幂等</b>：没点过的人来取消，不能报错，计数也不能变成负数。
     * <p>
     * 实现要满足的两条：
     * <ol>
     *   <li>只有"这次真的删掉了一条记录"才给计数减一</li>
     *   <li>帖子不存在时返回 {@code null}（和 {@link #like} 保持一致）</li>
     * </ol>
     * <p>
     * 提示：{@code DELETE} 语句的返回值同样是影响行数，语义和 insertIgnore 对称。
     * 用 {@code BaseMapper} 自带的 {@code delete(Wrapper)} 就能拿到它。
     *
     * @return 操作后的新状态；帖子不存在时返回 null
     */
    TopicLikeVo unlike(int uid, int topicId);

    /**
     * 这个人点过这条帖子没有。给帖子详情页的 {@code liked} 字段用。
     * <p>
     * 这是个纯读方法，不需要事务，一次按主键索引的查询就够了
     * （复合主键 (uid, topic_id) 正好是它的索引）。
     * <p>
     * <b>列表页不要用这个</b> —— 20 条就是 20 次查询。列表要用
     * {@link #likedTopicIds}，一次查完。
     */
    boolean isLiked(int uid, int topicId);

    /**
     * 一次查出"这个人在这批帖子里点过哪些"，给列表页的 {@code liked} 字段用。
     * <p>
     * 存在的理由就是避免 N+1：列表一次 20 条，逐条调 {@link #isLiked} 是 20 次查询，
     * 这里是 1 次。做法是 {@code WHERE uid = ? AND topic_id IN (...)}。
     * <p>
     * 实现要注意两件事：
     * <ol>
     *   <li><b>空集合必须短路返回</b>，不能进 {@code IN} —— 会生成 {@code IN ()}，
     *       那是 SQL 语法错误。（{@code TopicListQueryVo.types} 那个"全部类型"的判断
     *       也是为同一个坑而存在的。）</li>
     *   <li>只 {@code select} 那个 topic_id 列就行，不必把整行捞回来</li>
     * </ol>
     *
     * @param topicIds 这一页的所有帖子 id（可以为空）
     * @return 其中这个人点过赞的 id 集合；永远不为 null
     */
    Set<Integer> likedTopicIds(int uid, Collection<Integer> topicIds);
}
