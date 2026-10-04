package net.doudegua.entity.vo.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 评论列表的查询条件。和 {@link TopicListQueryVo} 是同一个套路：
 * 游标分页，条件对象走 {@code @ModelAttribute} 从 query 参数绑。
 * <p>
 * 没有 types / uid 这些筛选 —— 评论平铺、不筛。要加就在这儿加一个字段，
 * service 里多一个条件重载，前端多一个 prop，别的地方不用动。
 */
@Data
public class CommentListQueryVo {

    /** 看哪条帖子的评论。没这个就不知道要查谁，所以是必填 */
    @NotNull(message = "缺少帖子 id")
    Integer topicId;

    /** 游标：上一页最后一条的 id。不传 = 从头开始 */
    Integer cursor;

    @Min(value = 1, message = "至少要取 1 条")
    @Max(value = 50, message = "一次最多取 50 条")
    Integer size = 20;
}
