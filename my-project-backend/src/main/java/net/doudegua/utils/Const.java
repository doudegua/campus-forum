package net.doudegua.utils;

public class Const {
    public static final String JWT_BLACK_LIST = "jwt:blacklist:";

    public static final int ORDER_CORS = -102;
    public static final int ORDER_LIMIT = -101;

    public static final String VERIFY_EMAIL_LIMIT = "verify:email:limit:";
    public static final String VERIFY_EMAIL_DATA = "verify:email:data:";

    /** 发帖频率限制，按用户 id 计（不是按 IP：同一个 IP 后面可能是不同的人） */
    public static final String TOPIC_CREATE_LIMIT = "topic:create:limit:";

    /** 评论频率限制。间隔比发帖短得多，回帖本来就是连着发的 */
    public static final String COMMENT_CREATE_LIMIT = "comment:create:limit:";

    public static final String FLOW_LIMIT_COUNTER = "flow:counter:";
    public static final String FLOW_LIMIT_BLOCK = "flow:block:";
    /** 图片的秒级计数器。和 API 的分开，这样"页面挂了几张图"不会挤占 API 的额度 */
    public static final String FLOW_IMAGE_SECOND = "flow:image:second:";
    /** 图片日流量累计（字节）。键里带日期，跨天自然就是新键 */
    public static final String FLOW_IMAGE_BYTES = "flow:image:bytes:";

    /**
     * 还没落库的点赞增量，键形如 {@code like:delta:123}，值是数字，可正可负。
     * <p>
     * 故意不设 TTL：它不是缓存，是"还没写进数据库的待办"。设了 TTL 就等于
     * 到点自动少算几个赞 —— 宁可让它一直留着等落库，也不要偷偷丢掉。
     */
    public static final String LIKE_DELTA = "like:delta:";

    /**
     * 有未落库增量的帖子 id 集合。
     * <p>
     * 落库任务靠它拿待办清单，而不是 {@code SCAN like:delta:*} 遍历整个 keyspace。
     * 里面只会有"还有增量没写回"的帖子，正常情况下很小。
     */
    public static final String LIKE_DIRTY = "like:dirty";

    /* ==================== 抢课 ==================== */

    public static final String COURSE_READY = "course:ready:";
    /**
     * 预扣库存，键形如 {@code course:stock:123}，值是"还能抢几个"。
     * <p>
     * ⚠️ <b>键不存在 ≠ 0</b>。不存在表示"还没预热"，接口要回 NOT_STARTED；
     * 当成 0 的话，预热一旦失败，全站课程立刻显示售罄。
     * 这个键是闸门不是缓存，丢了不能靠 DB 再算一遍就继续卖 ——
     * 必须先停抢、对账、再重新预热（见 docs/course-selection.md）。
     */
    public static final String COURSE_STOCK = "course:stock:";

    /**
     * 这门课已经抢到的人（SET），键形如 {@code course:chosen:123}，成员是 uid。
     * <p>
     * 它的唯一作用是<b>在 Lua 里挡重复请求</b>：同一个人并发点 10 次，
     * 只会有一次走到 DECR。光靠数据库的唯一键不够 —— 那时库存已经被扣掉 9 次了。
     * 预热时要和 stock 一起从 {@code db_course_selection} 重建，
     * 只补 stock 不补它，重复请求会再扣一次库存。
     */
    public static final String COURSE_CHOSEN = "course:chosen:";

    /**
     * 课程元数据（Hash），键形如 {@code course:meta:123}。
     * 字段：capacity / start / end / ready，时间存 epoch 毫秒。
     * ready 必须最后写，写之前 select 一律返回"未就绪"。
     */
    public static final String COURSE_META = "course:meta:";

    /** 预热用的临时集合，键形如 {@code course:chosen:staging:123}；建完 RENAME 到正式键 */
    public static final String COURSE_CHOSEN_STAGING = "course:chosen:staging:";

    /** 预热锁，键形如 {@code course:preheat:lock:123}，防止两个请求同时重建 */
    public static final String COURSE_PREHEAT_LOCK = "course:preheat:lock:";

    public static final String COURSE_META_CAPACITY = "capacity";
    public static final String COURSE_META_START = "start";
    public static final String COURSE_META_END = "end";
    public static final String COURSE_META_READY = "ready";

    /**
     * 异步结果，键形如 {@code course:result:123:4}（课程 id + 用户 id），值是 "1"/"0"。
     * <p>
     * 消费端落库后写它，{@code GET /api/course/result} 读它 —— 前端轮询因此
     * 只打 Redis，不打 DB。它<b>带 TTL</b>：这是缓存，不是账本；
     * 权威数据永远是 {@code db_course_selection}。
     */
    public static final String COURSE_RESULT = "course:result:";

    /** 抢课主队列。durable，失败进死信，见 CourseRabbitConfiguration */
    public static final String COURSE_SELECT_QUEUE = "course.select";

    /** 抢课死信队列。消息判为"重试也没用"之后进这里，不自动重投 */
    public static final String COURSE_SELECT_DLQ = "course.select.dlq";

    /**
     * 当前登录用户 id 在 request attribute 里的键名。
     * <p>
     * 必须和 JwtAuthorizeFilter 里 setAttribute 用的名字一致，否则读的人永远拿到 null。
     * 这里原来写的是 "userId"，而 JwtAuthorizeFilter 写的是 "id"，
     * 结果 RequestLogFilter 的身份那一栏一直输出"未验证"。
     * 更彻底的做法是让 JwtAuthorizeFilter 和所有 @RequestAttribute 都用这个常量，
     * 而不是各自写字符串字面量。
     */
    public final static String ATTR_USER_ID = "id";
}
