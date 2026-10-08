package net.doudegua.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import net.doudegua.entity.dto.*;
import net.doudegua.entity.vo.request.CreateTopicVo;
import net.doudegua.entity.vo.request.TopicListQueryVo;
import net.doudegua.entity.vo.response.TopicDetailVo;
import net.doudegua.entity.vo.response.TopicPreviewListVo;
import net.doudegua.entity.vo.response.TopicPreviewVo;
import net.doudegua.mapper.TopicMapper;
import net.doudegua.service.*;
import net.doudegua.service.AccountProfileService;
import net.doudegua.utils.Const;
import net.doudegua.utils.FlowUtils;
import net.doudegua.utils.HtmlSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class TopicServiceImpl extends ServiceImpl<TopicMapper, Topic> implements TopicService {

    /** 同一用户两次发帖的最小间隔（秒）。要改就改这里 */
    private static final int CREATE_INTERVAL_SECONDS = 60;

    @Resource
    TopicTypeService topicTypeService;

    @Resource
    FlowUtils flowUtils;

    @Resource
    AccountService accountService;

    @Resource
    AccountProfileService accountProfileService;

    @Resource
    TopicLikeService topicLikeService;

    @Resource
    CommentService commentService;

    @Override
    public String createTopic(int uid, CreateTopicVo vo) {
        // 顺序是有讲究的：先做便宜的校验，最后才动频率限制。
        // 反过来的话，用户因为可修正的原因（比如内容超长）失败一次，
        // 那 60 秒的额度就被白白烧掉了，改完还得再等。
        if (topicTypeService.getById(vo.getType()) == null) {
            // 本项目没有用数据库外键，只能在应用层挡。
            // 不挡的话 type=999 照样入库，这条帖子永远列不出来，而且不报任何错
            return "帖子类型不存在";
        }

        // 先清洗，再校验。
        // 顺序反过来的话，用户会因为一段马上就被删掉的东西（外站图片、<script>）收到
        // "内容超长"或者直接通过校验，而报错依据的是那份根本没入库的内容。
        // 校验口径必须等于入库口径 —— 这也是为什么下面存的是 content 而不是 vo.getContent()
        String content = HtmlSanitizer.clean(vo.getContent());

        String plain = stripHtml(content);
        if (plain.isEmpty()) {
            // 只有图片没有文字会走到这里：图片不贡献任何文本。
            // 清洗后为空也算 —— 一段纯 <script> 的正文清完什么都不剩，不该入库
            return "请输入帖子内容";
        }
        if (plain.length() > CreateTopicVo.CONTENT_TEXT_MAX) {
            return "内容不能超过 " + CreateTopicVo.CONTENT_TEXT_MAX + " 个字";
        }

        // 按用户 id 限，不是按 IP —— 同一个 IP 后面可能是不同的人
        if (!flowUtils.limitOnceCheck(Const.TOPIC_CREATE_LIMIT + uid, CREATE_INTERVAL_SECONDS)) {
            return "发帖太频繁了，请 " + CREATE_INTERVAL_SECONDS + " 秒后再试";
        }

        Topic topic = new Topic(null, vo.getTitle().trim(), vo.getType(), content, uid, new Date(), 0, 0);
        // this.save 是 ServiceImpl 白送的，不用再注入 TopicMapper
        return this.save(topic) ? null : "发帖失败，请重试";
    }

    @Override
    public String editTopic(int uid, CreateTopicVo vo, int id) {
        Topic exist = this.getById(id);
        if (exist == null) {
            return "帖子不存在";
        }
        // 作者校验。少了这一句，任何登录用户 POST 一下 /api/forum/topic/{别人的帖子id}
        // 就能改掉别人的帖子 —— 而且比"删得掉别人的帖"更隐蔽：
        // 编辑不新增行、不报错、也没有任何通知，作者只会某天发现内容变了。
        // 和 deleteTopic 里那句是同一个判断，两处都不能少
        if (!exist.getUid().equals(uid)) {
            return "没有权限编辑这条帖子";
        }
        if (topicTypeService.getById(vo.getType()) == null) {
            return "帖子类型不存在";
        }
        String content = HtmlSanitizer.clean(vo.getContent());

        String plain = stripHtml(content);
        if (plain.isEmpty()) {
            return "请输入帖子内容";
        }
        if (plain.length() > CreateTopicVo.CONTENT_TEXT_MAX) {
            return "内容不能超过 " + CreateTopicVo.CONTENT_TEXT_MAX + " 个字";
        }
        if (!flowUtils.limitOnceCheck(Const.TOPIC_CREATE_LIMIT + uid, CREATE_INTERVAL_SECONDS)) {
            return "修改太频繁了，请 " + CREATE_INTERVAL_SECONDS + " 秒后再试";
        }

        return this.update(new LambdaUpdateWrapper<Topic>()
                .set(Topic::getTitle, vo.getTitle().trim())
                .set(Topic::getType, vo.getType())
                .set(Topic::getContent, content)
                .eq(Topic::getId, id))
                ? null : "修改失败，请重试";
    }

    @Override
    @Transactional // 只有通过Spring代理掉该Service，transactional方生效，否则不行
    public String deleteTopic(int uid, int id) {
        Topic exist = this.getById(id);
        if (exist == null)                    return "帖子不存在";
        if (!exist.getUid().equals(uid))      return "没有权限删除这条帖子";
        commentService.remove(new LambdaQueryWrapper<Comment>().eq(Comment::getTopicId, id));
        topicLikeService.remove(new LambdaQueryWrapper<TopicLike>().eq(TopicLike::getTopicId, id));
        this.removeById(id);
        return null;
    }

    @Override
    public TopicPreviewListVo fetchTopicPreviewList(TopicListQueryVo vo, int viewerId) {
        Integer size = vo.getSize();
        Integer cursor = vo.getCursor();
        Integer uid = vo.getUid();
        List<Integer> types = vo.getTypes();

        // 隐私开关 show_topics 的强制点**在这里**，不在 UserProfileVo 里。
        // 原因：它不是"某个字段该不该显示"，而是"这个请求该不该被满足" ——
        // 用 null 表达不了"列表不给你看"，因为 null 的语义是"这个字段没有内容"。
        //
        // 自己看自己不受限制：否则你把自己主页设成隐藏之后，你自己也看不到自己的帖子了。
        if (uid != null && uid != viewerId && !topicsVisible(uid)) {
            // 返回一个空列表，而不是 403。
            // 403 等于明确告诉访问者"他设置了不公开" —— 那本身就是一条泄露。
            // 空列表配一句中性的文案（"暂无内容"），访问者分不清是没发过还是不给看，
            // 而这正是我们要的
            return new TopicPreviewListVo(new ArrayList<>(), null);
        }

        boolean all = types == null || types.isEmpty() || types.size() == 1 && types.get(0).equals(0);

        LambdaQueryWrapper<Topic> queryWrapper = new LambdaQueryWrapper<Topic>()
                .lt(cursor != null, Topic::getId, cursor)
                .eq(uid != null, Topic::getUid, uid)
                .in(!all, Topic::getType, types)
                .orderByDesc(Topic::getId)
                .last("limit " + (size + 1));
        List<Topic> topics = this.list(queryWrapper);

        Map<Integer, String> typeNames = new HashMap<>();
        for (TopicType t : topicTypeService.listAll()) {
            typeNames.put(t.getId(), t.getName());
        }

        Set<Integer> uids = new HashSet<>();
        for (Topic t : topics) {
            uids.add(t.getUid());
        }
        Map<Integer, String> authorNames = new HashMap<>();
        if (!uids.isEmpty()) {
            for (Account a : accountService.listByIds(uids)) {
                authorNames.put(a.getId(), a.getUsername());
            }
        }

        boolean hasMore = topics.size() > size;
        if(hasMore) {
            topics = topics.subList(0, size);
        }

        List<TopicPreviewVo> result = topics.stream()
                .map(t -> new TopicPreviewVo(t, typeNames.get(t.getType()), authorNames.get(t.getUid())))
                .toList();

        // liked 是唯一一个"看的人不同、答案不同"的字段，只能单独查。
        // 但绝不能一条一条查 —— 20 条帖子就是 20 次查询（N+1）。
        // 做法：把这 20 条的 id 收集起来，一次 WHERE uid = ? AND topic_id IN (...) 查完。
        // 这也是为什么它是这个 VO 里唯一"外面查好了 set 进来"的字段。
        Set<Integer> topicIds = new HashSet<>();
        for (Topic t : topics) {
            topicIds.add(t.getId());
        }
        Set<Integer> likedIds = topicLikeService.likedTopicIds(viewerId, topicIds);
        for (TopicPreviewVo preview : result) {
            preview.setLiked(likedIds.contains(preview.getId()));
        }

        TopicPreviewListVo listVo = new TopicPreviewListVo();
        listVo.setTopicList(result);
        listVo.setNextCursor(hasMore ? result.get(result.size() - 1).getId() : null);

        return listVo;
    }

    @Override
    public TopicDetailVo fetchTopic(int id, int viewerId) {
        Topic topic = this.getById(id);
        if (topic == null) {
            // getById 查不到返回的是 null，不挡的话下面 topic.getType() 直接 NPE → 500。
            // 帖子被删掉、或者有人手输一个不存在的 id，都会走到这里。
            // 返回 null 让 controller 去包一个"帖子不存在"
            return null;
        }

        // 三份"名字"数据，都是 topic 里没有的，所以只能在这儿查好了再塞进 VO。
        // 这里查三次是合理的：详情页只有一个作者、一个类型，不存在列表页那种 N+1
        TopicType type = topicTypeService.getById(topic.getType());
        Account author = accountService.getById(topic.getUid());
        AccountProfile profile = accountProfileService.findAccountProfileById(topic.getUid());

        // 都做空值兜底：db_account_details 里可能压根没有这个人的行，
        // 类型也可能被管理员删了。少一个名字不该让整个详情页 500
        TopicDetailVo vo = new TopicDetailVo(topic,
                type == null ? null : type.getName(),
                author == null ? null : author.getUsername(),
                profile == null ? null : profile.getAvatar());

        // liked 是唯一一个"看的人不同、答案不同"的字段，没法从 topic 那一行推出来，
        // 只能单独查一次。viewerId 这个参数到这儿才终于有了用处
        vo.setLiked(topicLikeService.isLiked(viewerId, id));
        return vo;
    }

    /**
     * 这个人是否公开自己的帖子列表。
     * <p>
     * 资料行不存在、或者列是 NULL，都按"公开"处理 —— 和建表时的 DEFAULT 1 一致。
     * 别让"还没建过资料行"变成"帖子全部消失"。
     */
    private boolean topicsVisible(int uid) {
        AccountProfile profile = accountProfileService.findAccountProfileById(uid);
        return profile == null || !Boolean.FALSE.equals(profile.getShowTopics());
    }

    /**
     * 剥掉 HTML 标签后取纯文本，用来数字数。
     * <p>
     * 实现搬到了 {@link HtmlSanitizer#toPlainText} —— 评论那边也要用同一把尺子。
     * 两个地方各留一份正则的话，改口径时必然只改一处，
     * 然后就会出现"帖子放行、评论拒绝"这种没有依据的报错。
     */
    private String stripHtml(String html) {
        return HtmlSanitizer.toPlainText(html);
    }
}
