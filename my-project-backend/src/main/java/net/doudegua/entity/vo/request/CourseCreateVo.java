package net.doudegua.entity.vo.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 管理员新建课程。
 * <p>
 * 这是“建课”，不是“开放选课”：建完后还要调 preheat 才 ready。
 * 容量建完不允许改（课程表的 selected_count 依赖它），要改只能删了重建
 * （本轮不做删除）。
 */
@Data
public class CourseCreateVo {

    @NotBlank(message = "课程名不能为空")
    @Size(max = 128, message = "课程名最多 128 个字符")
    private String name;

    @Size(max = 64, message = "教师名最多 64 个字符")
    private String teacher;

    @NotNull(message = "学分不能为空")
    @DecimalMin(value = "0.0", message = "学分不能为负")
    @Digits(integer = 2, fraction = 1, message = "学分最多两位整数、一位小数")
    private BigDecimal credit;

    @NotNull(message = "容量不能为空")
    @Min(value = 1, message = "容量至少为 1")
    @Max(value = 10000, message = "容量不能超过 10000")
    private Integer capacity;

    @NotNull(message = "选课开始时间不能为空")
    private Date selectStart;

    @NotNull(message = "选课结束时间不能为空")
    private Date selectEnd;
}
