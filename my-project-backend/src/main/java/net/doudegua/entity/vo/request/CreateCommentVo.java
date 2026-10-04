package net.doudegua.entity.vo.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发评论的入参。
 * <p>
 * <b>评论是纯文本，不是 HTML</b> —— 前端的输入框是个 textarea，没有富文本。
 * 这一点决定了下面两件事，别搞混：
 * <ol>
 *   <li>服务端不做 HTML 白名单清洗，而是把整段文本**转义**（见 CommentServiceImpl）</li>
 *   <li>长度就是文本长度，不需要"原始 HTML"和"纯文本"两个口径</li>
 * </ol>
 * <p>
 * 为什么不能用 HtmlSanitizer.clean：那是给富文本帖子用的白名单过滤器，
 * 它会把不认识的标签**整个删掉**。而评论里 {@code List<String>} 这种写法
 * 会被 jsoup 当成一个 {@code <String>} 标签 —— 于是用户打的字被静默吃掉一半，
 * 而且不报错。这是个程序员论坛，尖括号天天出现。
 * 对纯文本，转义才是对的：{@code <} 原样显示，不会消失，也不会变成标签。
 */
@Data
public class CreateCommentVo {

    /** 评论长度上限。按字符算 */
    public static final int TEXT_MAX = 500;

    @NotNull(message = "缺少帖子 id")
    Integer topicId;

    /**
     * 正文。<b>纯文本</b>，不是 HTML。
     * <p>
     * 这里只做"非空 + 不超长"的粗筛；转义和换行处理在 service 里。
     * 不能用 {@code @NotBlank} 之外的判断来替代 service 那步 ——
     * 全空白的字符串（空格、换行）也会通过校验，得在 service 里 trim 完再看。
     */
    @NotNull(message = "请输入评论内容")
    @Size(max = TEXT_MAX, message = "评论不能超过 500 个字")
    String content;
}
