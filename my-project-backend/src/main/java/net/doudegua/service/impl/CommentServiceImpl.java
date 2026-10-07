package net.doudegua.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.entity.dto.Account;
import net.doudegua.entity.dto.Comment;
import net.doudegua.entity.dto.Topic;
import net.doudegua.entity.vo.request.CommentListQueryVo;
import net.doudegua.entity.vo.request.CreateCommentVo;
import net.doudegua.entity.vo.response.CommentListVo;
import net.doudegua.entity.vo.response.CommentVo;
import net.doudegua.mapper.CommentMapper;
import net.doudegua.mapper.TopicMapper;
import net.doudegua.service.AccountService;
import net.doudegua.service.CommentService;
import net.doudegua.utils.Const;
import net.doudegua.utils.FlowUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.util.*;

@Slf4j
@Service
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements CommentService {

    /** 同一用户两次评论的最小间隔（秒）。比发帖短得多 —— 回帖本来就是连着发的 */
    private static final int COMMENT_INTERVAL_SECONDS = 5;

    @Resource
    AccountService accountService;

    @Resource
    FlowUtils flowUtils;

    /**
     * 这里直接注入的是 TopicMapper，而不是 TopicService。
     * <p>
     * 有意为之：TopicServiceImpl 那边要用 Topic 实体读 comment_count，
     * 如果这里改用 TopicService，两边就互相依赖了（循环依赖）。
     * 而这里要做的只是一句 UPDATE，用不着绕一层业务。
     */
    @Resource
    TopicMapper topicMapper;

    @Override
    @Transactional
    public String createComment(int uid, CreateCommentVo vo) {
        // 顺序和 createTopic 一样：先做便宜的、可修正的校验，最后才动频次和写库。
        //
        // 但这里多了一条**更硬的规矩**，因为它涉及事务：
        //
        //   所有 return "失败原因" 都必须发生在第一次写之前。
        //
        // 原因是 @Transactional 默认**只对抛出的异常回滚**。你 return 一个字符串
        // 表示"失败"，Spring 认为方法正常结束了，于是照常 COMMIT。
        // 如果已经 insert 了评论再 return "失败"，那条评论就留在库里了，
        // 而调用方以为没发出去 —— 用户刷新一下就看见自己"没发成功"的那条。
        if (topicMapper.selectById(vo.getTopicId()) == null) {
            return "帖子不存在";
        }

        // 先转义，再判空 —— 顺序不能反：
        // 全空白（只有空格和换行）的输入要在这里被挡住，而 @Size 拦不住它
        String content = escapeToHtml(vo.getContent());
        if (content.isEmpty()) {
            return "请输入评论内容";
        }

        if (!flowUtils.limitOnceCheck(Const.COMMENT_CREATE_LIMIT + uid, COMMENT_INTERVAL_SECONDS)) {
            return "评论太频繁了，请 " + COMMENT_INTERVAL_SECONDS + " 秒后再试";
        }

        // ↓↓↓ 从这里开始才是写。下面任何一步失败都要靠抛异常回滚（不能 return） ↓↓↓

        this.save(new Comment(null, vo.getTopicId(), uid, content, new Date()));

        // 计数和插入必须在同一个事务里，否则插入成功、加一失败就永久对不上了。
        //
        // setSql 让它编译成 comment_count = comment_count + 1 这一条**原子**的 UPDATE。
        // 千万别写成"先 getById 读出来、在 Java 里 +1、再 update 回去" ——
        // 那是典型的 read-modify-write，两个人同时评论就会丢掉一次计数。
        // 数据库的行锁在这里帮了我们：+1 是在服务端算的，不是在我们这儿算的。
        topicMapper.update(null, new LambdaUpdateWrapper<Topic>()
                .setSql("comment_count = comment_count + 1")
                .eq(Topic::getId, vo.getTopicId()));

        return null;
    }

    @Override
    @Transactional
    public String deleteComment(int uid, int id) {
        Comment exist = this.getById(id);
        if (exist == null)                  return "评论不存在！";
        if (!exist.getUid().equals(uid))    return "没有权限删除这条帖子";
        this.remove(new LambdaQueryWrapper<Comment>().eq(Comment::getTopicId, id));
        return null;
    }

    @Override
    public CommentListVo fetchComments(CommentListQueryVo vo) {
        Integer size = vo.getSize();

        // 和帖子列表一模一样的套路：多取一条来判断"还有没有下一页"
        List<Comment> comments = this.list(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getTopicId, vo.getTopicId())
                .lt(vo.getCursor() != null, Comment::getId, vo.getCursor())
                .orderByDesc(Comment::getId)
                .last("limit " + (size + 1)));

        boolean hasMore = comments.size() > size;
        if (hasMore) {
            // subList 返回的是原列表的视图，后面只用它读，不会再改，所以安全
            comments = comments.subList(0, size);
        }

        // 批量查作者名，不要一条评论查一次 —— 那样 20 条评论就是 21 次查询
        Set<Integer> uids = new HashSet<>();
        for (Comment c : comments) {
            uids.add(c.getUid());
        }
        Map<Integer, String> authorNames = new HashMap<>();
        if (!uids.isEmpty()) {
            for (Account a : accountService.listByIds(uids)) {
                authorNames.put(a.getId(), a.getUsername());
            }
        }

        List<CommentVo> result = comments.stream()
                .map(c -> new CommentVo(c, authorNames.get(c.getUid())))
                .toList();

        // 到底了就给 null，不能给 0 —— 前端判断的是 == null，
        // 给 0 的话它永远不认为到底，会一直拿着 cursor=0 往下问
        return new CommentListVo(result, hasMore ? result.get(result.size() - 1).getId() : null);
    }

    /**
     * 纯文本 → 可以直接交给 v-html 的 HTML 片段。
     * <p>
     * 为什么是转义而不是清洗：评论的输入框是 textarea，进来的是**纯文本**。
     * 用 HtmlSanitizer（白名单过滤器）会把它当富文本处理，不认识的标签整个删掉 ——
     * {@code List<String>} 会被当成 {@code <String>} 标签，
     * 于是用户打的字被静默吃掉一半。对纯文本，转义才是无损且安全的。
     * <p>
     * 存转义后的结果而不是原文，是为了让"库里这一列永远是安全 HTML"这条成立：
     * 以后谁想给它加个 v-html 也不会立刻变成一个洞。
     * <p>
     * 先转义再替换换行，顺序反了会把刚生成的 {@code <br>} 自己转义成 {@code &lt;br&gt;}。
     */
    private static String escapeToHtml(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return HtmlUtils.htmlEscape(trimmed).replace("\n", "<br>");
    }
}
