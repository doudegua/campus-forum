package net.doudegua.entity.vo.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一门课的预热/关停结果。
 * <p>
 * 一个 list 可能部分成功、部分失败，所以不能只返回一个布尔值。
 * 状态是字符串，和 {@code CourseSelectResultVo} 保持一致，前端只做字符串比较。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PreheatResult {

    private Integer courseId;
    private String status;
    private String message;

    public static final String OPENED = "OPENED";
    public static final String CLOSED = "CLOSED";
    public static final String SKIPPED = "SKIPPED";
    public static final String BUSY = "BUSY";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String COUNT_MISMATCH = "COUNT_MISMATCH";
    public static final String ERROR = "ERROR";
}
