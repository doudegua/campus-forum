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
