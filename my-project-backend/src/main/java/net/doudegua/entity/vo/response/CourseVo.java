package net.doudegua.entity.vo.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 课程列表里的一行。
 * <p>
 * {@link #remaining} 是<b>算出来的</b>，不落库：骨架阶段用
 * {@code capacity - selectedCount}；接上 Redis 之后改成读 {@code course:stock:{id}}，
 * 键不存在再回 DB 兜底。改的时候注意：这个值在抢课过程中会一直变，
 * <b>它只能是参考，不能当成"一定抢得到"的承诺</b> ——
 * 用户看到还剩 1 个，点下去仍然可能返回 SOLD_OUT（别人先扣走了）。
 */
@Data
@AllArgsConstructor
public class CourseVo {

    private Integer id;

    private String name;

    private String teacher;

    private BigDecimal credit;

    private Integer capacity;

    /** 剩余名额。来源随阶段不同，见类注释 */
    private Integer remaining;

    private Date selectStart;

    private Date selectEnd;
}
