package net.doudegua.entity.vo.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 管理员课程列表：在 {@link CourseVo} 的基础上多给预热状态和 chosen 人数。
 * <p>
 * {@code selectedCount} 是 DB 已落库人数；{@code chosenCount} 是 Redis 里
 * 已经预扣的名额。两者可能短暂不一致（消息还在队列里），管理员页面要同时看。
 */
@Data
@AllArgsConstructor
public class AdminCourseVo {

    private Integer id;

    private String name;

    private String teacher;

    private BigDecimal credit;

    private Integer capacity;

    private Integer selectedCount;

    private Integer chosenCount;

    private Integer remaining;

    private Boolean ready;

    private Date selectStart;

    private Date selectEnd;
}
