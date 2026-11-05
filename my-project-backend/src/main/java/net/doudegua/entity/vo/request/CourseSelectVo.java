package net.doudegua.entity.vo.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 抢课请求体。
 * <p>
 * ⚠️ 里面<b>没有 uid</b>，而且不能有。uid 只能从 JWT 里取
 * （{@code @RequestAttribute("id")}），否则谁都能替别人抢课 ——
 * 发帖、评论、点赞都是这个规矩，见 {@code ForumController} 的注释。
 */
@Data
public class CourseSelectVo {

    @NotNull(message = "课程 id 不能为空")
    private Integer courseId;
}
