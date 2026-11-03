package net.doudegua.entity.vo.response;

import lombok.Getter;

/**
 * 抢课 Lua 脚本的返回码 —— 全项目只在这里定义一次。
 * <p>
 * 脚本返回 {@code >= 1} 表示成功（值是扣减前的余量），其余是固定负码/0：
 * <pre>
 *   >= 1  成功（剩余 = 返回值 - 1）
 *      0  售罄
 *     -1  同一用户重复
 *     -2  未就绪（没预热 / ready 不在）
 *     -3  未开始
 *     -4  已结束
 * </pre>
 * service 拿到 Lua 返回值后第一件事就是 {@link #from(long)}，
 * 之后只认枚举，不再出现裸数字。
 */
@Getter
public enum GrabLuaResult {

    OK(1, CourseSelectResultVo.QUEUED),
    SOLD_OUT(0, CourseSelectResultVo.SOLD_OUT),
    DUPLICATE(-1, CourseSelectResultVo.DUPLICATE),
    NOT_READY(-2, CourseSelectResultVo.NOT_READY),
    NOT_STARTED(-3, CourseSelectResultVo.NOT_STARTED),
    CLOSED(-4, CourseSelectResultVo.CLOSED),
    UNKNOWN(-99, CourseSelectResultVo.FAILURE);

    /** 脚本里的码；OK 实际可能是任意 >= 1，这里只是名义值 */
    private final long code;
    /** 对应给前端的状态字符串 */
    private final String status;

    GrabLuaResult(long code, String status) {
        this.code = code;
        this.status = status;
    }

    public boolean isSuccess() {
        return this == OK;
    }

    /** 把 Lua 返回值翻译成枚举；>=1 一律是成功 */
    public static GrabLuaResult from(long code) {
        if (code >= 1) {
            return OK;
        }
        return switch ((int) code) {
            case 0 -> SOLD_OUT;
            case -1 -> DUPLICATE;
            case -2 -> NOT_READY;
            case -3 -> NOT_STARTED;
            case -4 -> CLOSED;
            default -> UNKNOWN;
        };
    }

    /**
     * 本次扣减后的剩余量：成功时是 {@code code - 1}；
     * 失败时把原码带回去，保持和以前一致的响应。
     */
    public static Long remaining(long code) {
        return code >= 1 ? code - 1 : code;
    }
}
