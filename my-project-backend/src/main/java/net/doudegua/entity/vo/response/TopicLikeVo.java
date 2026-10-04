package net.doudegua.entity.vo.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一次点赞/取消点赞之后的新状态。
 * <p>
 * 为什么把结果返回去，而不是让前端自己把数字加一：
 * 前端只知道"我刚点了"，不知道"这一下是不是真的生效了"（可能早就点过、或者被回滚了）。
 * 让服务端把权威结果报回来，前端就只需要照着画 —— 少一整类"数字慢慢飘掉"的 bug。
 * <p>
 * 代价是服务端要多查一次计数。对这个数据量完全可以忽略，
 * 等真的到了要省这一次查询的时候再说。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TopicLikeVo {
    /** 这次操作之后，"我"到底点上赞了没有 */
    boolean liked;
    /** 这条帖子当前的总点赞数 */
    int likeCount;
}
