package net.doudegua.utils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 帖子正文的 HTML 白名单清洗。
 * <p>
 * 为什么必须有这个东西：正文是用户提交的 HTML，前端要用 {@code v-html} 渲染。
 * 而接口是裸的 —— 一条 curl 就能绕过 Quill 直接存进去
 * {@code <img src=x onerror=fetch('/api/user/info')>}，然后每个点开帖子的人都会执行它。
 * Quill 本身只产出安全的 HTML，但"客户端产出什么"从来不是服务端可以依赖的假设。
 * <p>
 * 为什么是白名单而不是黑名单：属性里能藏东西的地方太多，
 * {@code onerror} / {@code onload} / {@code href="javascript:"} / {@code style="background:url()"}，
 * 还有大小写、换行、实体编码这些绕过手法。列黑名单永远是漏的，只有"不认识的统统删掉"才是收敛的。
 * <p>
 * 白名单的内容不是猜的，是照着库里已有的 41 条真实正文定的 ——
 * 实际用到的标签只有 p / ul / li / strong / pre / img，属性只有 img 的 src。
 * 下面留了 Quill 其它格式可能用到的标签，但一个多余的属性都没放。
 */
public final class HtmlSanitizer {

    private HtmlSanitizer() {
    }

    /**
     * 正文里允许出现的图片地址：本站取图接口，且必须是<b>相对路径</b>。
     * <p>
     * 规则是 {@code /api/image/} + 32 位十六进制，也就是 {@code ImageServiceImpl}
     * 用 {@code UUID.randomUUID()} 去掉横线之后的形状。
     * <p>
     * 为什么连 origin 都不允许：一开始这里写的是
     * {@code ^(?:https?://[^/]+)?/api/image/[0-9a-f]{32}$}，本意是"换环境时前端可能存绝对地址"，
     * 结果 {@code https://evil.com/api/image/<32hex>} 也能通过 —— 只要路径编对，
     * 任何域名都放行。那这个校验就白做了，追踪像素照样能塞进来。
     * 要放行 origin 就必须拿白名单去比域名，而"不比较就等于放行"这种事不该出现在安全代码里。
     * <p>
     * 用正则单独判、而不是靠 jsoup 的 addProtocols，是因为这里要表达的规则不是
     * "协议是 http/https"，而是"必须指向我们自己的图片"。
     * <p>
     * 代价：如果哪天真存了绝对地址，图片会被删掉而不是放行。这是<b>失败向安全</b>的方向 ——
     * 用户丢一张图，比所有读者被第三方统计强。
     */
    private static final Pattern INTERNAL_IMAGE =
            Pattern.compile("^/api/image/[0-9a-fA-F]{32}$");

    /** Quill 的 class 前缀。见下面 filterQuillClasses 的说明 */
    private static final String QUILL_CLASS_PREFIX = "ql-";

    private static final Safelist SAFELIST = buildSafelist();

    private static Safelist buildSafelist() {
        Safelist safelist = Safelist.none()
                // 块级：段落、换行、分割线、引用
                .addTags("p", "br", "hr", "blockquote", "div")
                // 行内：加粗、斜体、下划线、删除线、上下标、颜色/字号用的 span
                .addTags("strong", "b", "em", "i", "u", "s", "del", "sub", "sup", "span")
                // 标题
                .addTags("h1", "h2", "h3", "h4", "h5", "h6")
                // 列表
                .addTags("ul", "ol", "li")
                // 代码块：Quill 的代码块是 <pre class="ql-syntax">，里面直接是文本
                .addTags("pre", "code")
                // 链接和图片
                .addTags("a", "img");

        // class 要留。Quill 的对齐/缩进/字号全靠它（ql-align-center / ql-indent-1 / ql-size-large），
        // 一个都不留的话这些格式会被静默吃掉，用户只会觉得"我排的版没了"。
        for (String tag : new String[]{"p", "span", "li", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "div"}) {
            safelist.addAttributes(tag, "class");
        }

        // 但 style 一个都不留 —— 这是 CSS 注入面：
        // background:url(...) 能把读者的 IP 发给第三方，position:fixed 能盖住整个页面做钓鱼。
        // 代价是 Quill 用内联 style 存的前景色/背景色会丢。这个取舍是故意的：
        // 丢一点颜色，换掉一整类洞。
        safelist.addAttributes("pre", "spellcheck");
        safelist.addAttributes("ol", "start");
        safelist.addAttributes("a", "href", "title");
        safelist.addAttributes("img", "src", "alt", "title");

        // 链接协议只放行这三种，javascript: 和 data: 在这一步被摘掉 href
        safelist.addProtocols("a", "href", "http", "https", "mailto");
        // 外链一律新窗口打开，并且断开 opener 引用 ——
        // 不加 noopener 的话，被打开的页面能通过 window.opener 反过来改我们的页面
        safelist.addEnforcedAttribute("a", "target", "_blank");
        safelist.addEnforcedAttribute("a", "rel", "noopener noreferrer");

        return safelist;
    }

    /**
     * 剥掉 HTML 标签后取纯文本，用来数字数。
     * <p>
     * 口径必须和前端 {@code TopicEditor} 里的 plainContent 完全一致：
     * <pre>content.replace(/&lt;[^&gt;]*&gt;/g, '').replace(/&amp;nbsp;/g, ' ').trim()</pre>
     * 不一致就会出现"前端放行、后端拒绝"，而用户看到的是一句没有依据的报错。
     * <p>
     * 注意这里只用来<b>数字数</b>，不是净化 —— 净化是上面那个 {@link #clean}。
     * 用正则去标签永远挡不住 XSS，别把这两个函数的用途搞混。
     * <p>
     * 放在这个类里而不是某个 service 的私有方法，是因为帖子和评论都要用它：
     * 两个地方各写一份正则的话，改口径时必然只改一处。
     */
    public static String toPlainText(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ")
                .trim();
    }

    /**
     * 清洗一段用户提交的 HTML。
     *
     * @return 洗干净、可以安全交给 {@code v-html} 的 HTML；入参为空时返回空串（不是 null）
     */
    public static String clean(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        Document dirty = Jsoup.parseBodyFragment(html);
        Document clean = new Cleaner(SAFELIST).clean(dirty);
        // 关掉 pretty print：默认的格式化会往标签之间插换行和缩进，
        // 那会悄悄改动用户的内容（尤其 <pre> 里的代码）
        clean.outputSettings().prettyPrint(false);

        dropForeignImages(clean);
        filterQuillClasses(clean);

        return clean.body().html();
    }

    /**
     * 白名单只能管到"img 允许有 src 属性"，管不到"src 指向哪里"，所以这一步单独做。
     * 指向外站的图片直接删掉：它能让发帖人看到每个读者的 IP 和阅读时间。
     */
    private static void dropForeignImages(Document clean) {
        for (Element img : clean.select("img")) {
            if (!INTERNAL_IMAGE.matcher(img.attr("src")).matches()) {
                img.remove();
            }
        }
    }

    /**
     * 只保留 {@code ql-} 开头的 class。
     * <p>
     * 任意 class 本身不算漏洞，但它会去命中页面里已经存在的 CSS ——
     * 比如给一个元素挂上 Element Plus 的类名，就能把详情页排得乱七八糟。
     * 既然我们已知用到的只有 Quill 自己那套，就按前缀收窄，
     * 免得白名单里躺着一堆"我们也不认识、但先放行"的类名。
     */
    private static void filterQuillClasses(Document clean) {
        for (Element element : clean.select("[class]")) {
            String kept = Arrays.stream(element.attr("class").split("\\s+"))
                    .filter(name -> name.startsWith(QUILL_CLASS_PREFIX))
                    .collect(Collectors.joining(" "));
            if (kept.isEmpty()) {
                element.removeAttr("class");
            } else {
                element.attr("class", kept);
            }
        }
    }
}
