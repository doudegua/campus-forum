package net.doudegua.entity.vo.response;

/**
 * 一次抢课请求的结果。
 * <p>
 * <b>状态是字符串而不是枚举</b>，理由和 {@code RestBean} 的 code 一样：
 * 这个值要直接给前端看，前端只做字符串比较。加枚举还得处理序列化名字，
 * 为 5 个常量不值得。
 * <p>
 * 状态机（缺一不可，前端靠它决定"继续等"还是"别试了"）：
 * <pre>
 *   QUEUED      已进队列，最终结果还没落库 —— 前端继续轮询
 *   SUCCESS     已经写进 db_course_selection，真正抢到
 *   SOLD_OUT    名额没了（Redis 余量 0）
 *   DUPLICATE   这个人已经抢到过（Redis 去重命中，或 DB 唯一键命中）
 *   NOT_STARTED 课程不存在 / 库存还没预热 / 不在选课时间内
 * </pre>
 * ⚠️ {@code QUEUED} 不是错误，HTTP 仍然是 200（项目约定 code 在 body 里）。
 * 把"排队中"当失败返回，前端就会提示"抢课失败"然后让用户重试，
 * 而重试会被幂等挡住 —— 用户永远不知道到底成了没有。
 */
public record CourseSelectResultVo(String status, Integer remaining) {

    /** 骨架阶段占位：这三段核心还没写 */
    public static final String NOT_IMPLEMENTED = "NOT_IMPLEMENTED";

    public static final String QUEUED = "QUEUED";
    public static final String SUCCESS = "SUCCESS";
    public static final String SOLD_OUT = "SOLD_OUT";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String NOT_STARTED = "NOT_STARTED";
}
