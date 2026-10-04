package net.doudegua.entity.vo.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TopicPreviewListVo {
    List<TopicPreviewVo> topicList;
    /**
     * 下一页的游标。null 表示"没有更多了"。
     * <p>
     * 必须是包装类型 Integer，不能用 int —— int 不能为 null，
     * 那"到底了"就只能用一个魔法值（0 或 -1）表示，
     * 而 0 恰好又是合法的 id 边界。这就是你最讨厌的那种写法。
     */
    Integer nextCursor;

}
