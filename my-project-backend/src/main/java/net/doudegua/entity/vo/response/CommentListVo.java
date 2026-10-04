package net.doudegua.entity.vo.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一页评论。形状和 {@link TopicPreviewListVo} 完全一样 —— 同一个游标分页套路，
 * 只是元素类型不同，所以没法直接复用那个类。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CommentListVo {
    List<CommentVo> commentList;

    /**
     * 下一页的游标。null 表示"没有更多了"。
     * <p>
     * 和帖子列表同样的坑：这里必须是包装类型，而且到底了要老老实实给 null，
     * 不能拿 0 当"到底了"的暗号 —— 前端判断的是 {@code == null}，
     * 给 0 的话它永远不认为到底。
     */
    Integer nextCursor;
}
