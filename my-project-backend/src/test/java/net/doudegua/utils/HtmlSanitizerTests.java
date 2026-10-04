package net.doudegua.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清洗是安全边界，所以每一条用例断言的都是"攻击面被堵住了"，
 * 不是"函数返回了某个字符串"。
 * <p>
 * 用例里的内层 key 是 {@code ImageServiceImpl} 那种 32 位 hex 的形状 ——
 * 清洗器认的就是这个形状，随便编一个短一点的 key 会被当成外站图片删掉，
 * 那是测试写错了，不是清洗器错了。
 */
class HtmlSanitizerTests {

    private static final String KEY = "a6b0130ef0bf4acebaf781a5d6145c34";

    // ---------- 正常内容不能被动坏 ----------

    /** 库里真实的正文长这样，清洗后必须一模一样 —— 清洗器不该顺手改用户的排版 */
    @Test
    void keepsRealPostContentUntouched() {
        String real = "<p>整理了一下自己理解的索引，主要是 B+ 树这块。</p>" +
                "<ul><li>为什么用 B+ 树而不是二叉树：磁盘 IO 次数</li><li>聚簇索引和二级索引的区别</li></ul>" +
                "<p>有写错的地方欢迎指出来。</p>";

        assertEquals(real, HtmlSanitizer.clean(real));
    }

    @Test
    void keepsInternalImage() {
        String html = "<p><img src=\"/api/image/" + KEY + "\"></p>";

        assertEquals(html, HtmlSanitizer.clean(html));
    }

    /**
     * 绝对地址就算指向本站也会被删 —— 因为"哪个 origin 算本站"在服务端没法凭字符串判断。
     * 想放行就必须拿配置里的域名去比，而"不比较就等于放行"正是这条规则最初的 bug：
     * 那时正则允许任意 origin，于是 {@code https://evil.com/api/image/<32hex>} 也能过。
     */
    @Test
    void dropsAbsoluteImageUrlEvenIfItLooksInternal() {
        String html = "<p><img src=\"http://localhost:8080/api/image/" + KEY + "\"></p>";

        assertFalse(HtmlSanitizer.clean(html).contains("img"));
    }

    @Test
    void keepsQuillFormattingClasses() {
        String html = "<p class=\"ql-align-center ql-indent-1\">居中缩进</p>";

        assertEquals(html, HtmlSanitizer.clean(html));
    }

    // ---------- 攻击面 ----------

    @Test
    void stripsScriptTag() {
        String cleaned = HtmlSanitizer.clean("<p>正常</p><script>alert(1)</script>");

        assertEquals("<p>正常</p>", cleaned);
    }

    /** onerror 不在属性白名单里，所以就算 src 合法也会被摘掉 */
    @Test
    void stripsEventAttributes() {
        String cleaned = HtmlSanitizer.clean(
                "<img src=\"/api/image/" + KEY + "\" onerror=\"alert(1)\">");

        assertFalse(cleaned.contains("onerror"), cleaned);
        assertTrue(cleaned.contains("/api/image/" + KEY), cleaned);
    }

    @Test
    void stripsStyleAttribute() {
        String cleaned = HtmlSanitizer.clean(
                "<p style=\"background:url(https://evil.com/track)\">x</p>");

        assertFalse(cleaned.contains("style"), cleaned);
        assertFalse(cleaned.contains("evil.com"), cleaned);
    }

    /**
     * 外站图片必须整个删掉，不是只把属性摘掉。
     * 留着 src 就等于让发帖人拿到每个读者的 IP 和阅读时间。
     */
    @Test
    void dropsForeignImageEntirely() {
        String cleaned = HtmlSanitizer.clean(
                "<p>看图</p><p><img src=\"https://evil.com/track.gif\"></p>");

        assertFalse(cleaned.contains("img"), cleaned);
        assertFalse(cleaned.contains("evil.com"), cleaned);
        assertTrue(cleaned.contains("看图"), cleaned);
    }

    /** 伪装成本站图片：路径对了但 key 不是 UUID 形状 / 前面挂了别的域名 */
    @Test
    void dropsLookalikeImagePaths() {
        assertFalse(HtmlSanitizer.clean("<img src=\"/api/image/1\">").contains("img"));
        assertFalse(HtmlSanitizer.clean("<img src=\"/api/image/../../etc/passwd\">").contains("img"));
        assertFalse(HtmlSanitizer.clean(
                "<img src=\"https://evil.com/api/image/" + KEY + "\">").contains("img"));
    }

    @Test
    void stripsJavascriptHref() {
        String cleaned = HtmlSanitizer.clean("<a href=\"javascript:alert(1)\">点我</a>");

        assertFalse(cleaned.contains("javascript"), cleaned);
        assertTrue(cleaned.contains("点我"), cleaned);
    }

    /** 外链要新窗口打开，并且断开 window.opener 引用 */
    @Test
    void hardensExternalLinks() {
        String cleaned = HtmlSanitizer.clean("<a href=\"https://example.com\">外链</a>");

        assertTrue(cleaned.contains("target=\"_blank\""), cleaned);
        assertTrue(cleaned.contains("noopener"), cleaned);
    }

    /** 任意 class 能去命中页面里已有的 CSS，所以只留 ql- 前缀的 */
    @Test
    void dropsNonQuillClasses() {
        String cleaned = HtmlSanitizer.clean("<p class=\"ql-align-center el-button evil\">x</p>");

        assertTrue(cleaned.contains("ql-align-center"), cleaned);
        assertFalse(cleaned.contains("el-button"), cleaned);
        assertFalse(cleaned.contains("evil"), cleaned);
    }

    // ---------- 边界 ----------

    @Test
    void blankInputReturnsEmptyString() {
        assertEquals("", HtmlSanitizer.clean(null));
        assertEquals("", HtmlSanitizer.clean(""));
        assertEquals("", HtmlSanitizer.clean("   "));
    }

    /** 一段纯 script 的正文清完什么都不剩，调用方据此判"请输入帖子内容" */
    @Test
    void scriptOnlyContentBecomesEmpty() {
        assertEquals("", HtmlSanitizer.clean("<script>alert(1)</script>"));
    }

    /** 不能给标签之间塞换行缩进，否则 <pre> 里的代码会被悄悄改动 */
    @Test
    void doesNotPrettyPrint() {
        assertEquals("<p>a</p><p>b</p>", HtmlSanitizer.clean("<p>a</p><p>b</p>"));
    }
}
