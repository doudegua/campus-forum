package net.doudegua.entity.vo.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发帖请求。
 * <p>
 * 各项限制集中放在这里，改的时候一眼能看到全部。
 * 注意其中的字数都是"剥掉 HTML 标签后的纯文本字符数"，
 * 要和前端 TopicEditor 里 plainContent 的口径一致。
 */
@Data
public class CreateTopicVo {

    /** 标题上限，按字符算。db_topic.title 是 varchar(255)，255 是字符不是字节，60 远在范围内 */
    public static final int TITLE_MAX = 60;

    /**
     * 正文上限，按纯文本字符数算。
     * <p>
     * 刻意比前端的 10000 松一点：前端负责"友好地拦住"，后端负责"兜底"。
     * 两边卡同一个数的话，只要有一点点口径差异（比如标签、实体、全半角），
     * 就会出现"前端放行、后端拒绝"，用户完全不知道为什么。
     */
    public static final int CONTENT_TEXT_MAX = 12000;

    /**
     * 正文原始 HTML 的字符数硬上限。
     * <p>
     * 和 CONTENT_TEXT_MAX 不是一回事：那个管"写了多少字"，这个管"传了多大的东西"。
     * 没有它的话，写 1 个字配 400KB 的 {@code <span>} 标签就能绕过字数检查，
     * 而 MEDIUMTEXT 能吃到 16MB。
     */
    public static final int CONTENT_RAW_MAX = 400_000;

    @NotBlank(message = "请填写标题")
    @Size(max = TITLE_MAX, message = "标题不能超过 " + TITLE_MAX + " 个字")
    String title;

    @NotNull(message = "请选择帖子类型")
    Integer type;

    @NotBlank(message = "请输入帖子内容")
    @Size(max = CONTENT_RAW_MAX, message = "内容太长了")
    String content;
}
