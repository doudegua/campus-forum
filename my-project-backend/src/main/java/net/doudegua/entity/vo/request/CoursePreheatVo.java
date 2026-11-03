package net.doudegua.entity.vo.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Date;

/**
 * 预热请求里的单门课。
 * <p>
 * 时间字段本轮只做预留：预热以 {@code db_course.select_start/select_end} 为准，
 * 不从请求体改库。要手动改时间窗，先走 SQL 或下一轮加专门的接口。
 */
@Data
public class CoursePreheatVo {

    @NotNull(message = "课程 id 不能为空")
    private Integer courseId;

    /** 预留：本轮不落库，也不参与预热 */
    private Date selectStart;

    /** 预留：本轮不落库，也不参与预热 */
    private Date selectEnd;
}
