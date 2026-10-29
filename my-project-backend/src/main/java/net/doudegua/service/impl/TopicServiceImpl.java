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
import net.doudegua.utils.TtlCache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class TopicServiceImpl extends ServiceImpl<TopicMapper, Topic> implements TopicService {

    /** 同一用户两次发帖的最小间隔（秒）。要改就改这里 */
    private static final int CREATE_INTERVAL_SECONDS = 60;

    /**
     * 版块名字的缓存时长。
     * <p>
     * {@code db_topic_type} 只有几行、内容基本不变，但原来**每一个列表请求都要查它一次**。
     * 压测实测每查一次库约 1.7ms CPU —— 大头是 JDBC 往返和 MyBatis 映射的固定开销，
     * MySQL 自己执行这条 SQL 只要 0.6ms。列表接口一共才 4 条 SQL，等于这一条白吃了约 18% 的 CPU。
     * <p>
     * 为什么 TTL 敢取 10 分钟：版块是运营数据，由 SQL 种子文件灌入，
     * 应用里**没有**增删改版块的接口（{@code ForumController} 里只有
     * {@code GET /api/forum/topic_type}）。所以它实际是一张只读表。
     * 将来真加了管理接口，改完最多 10 分钟生效，也不会出错。
     * <p>
     * 写入路径不受影响：{@code createTopic} 校验类型走的是
     * {@code topicTypeService.getById}，不经过这个缓存。
     */
    private static final long TYPE_NAME_TTL_MILLIS = 10 * 60 * 1000L;

    /**
     * 版块 id → 名字。复用天气接口那套进程内 {@link TtlCache}，**不引入 Redis**：
     * 单实例部署，多一个依赖只是多一个故障点。
     * <p>
     * 这也正是压测报告里"单实例时进程内缓存比 Redis 划算"那条结论的落地 ——
     * Redis 要付网络往返 + 序列化的 CPU，而这里只是一个 HashMap 查找。
     */
    private final TtlCache<Map<Integer, String>> typeNameCache = new TtlCache<>();

    /** 取版块名映射：命中缓存直接返回，未命中才查库。调用方只读，不要修改返回的 map */
    private Map<Integer, String> typeNamesCached() {
        return typeNameCache.get("typeNames", TYPE_NAME_TTL_MILLIS, () -> {
            Map<Integer, String> names = new HashMap<>();
            for (TopicType t : topicTypeService.listAll()) {
                names.put(t.getId(), t.getName());
            }
            return names;
        });
    }

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

        // "全部版块"表达为**不传 types**（null），而不是传一个哨兵值。
        // 原来是在 Java 里算出 all、再决定要不要 .in(...)；现在交给 SQL 的 <if>：
        // 传 null 时那段条件整个不生成。
        // ⚠️ 不能传空 List —— <foreach> 会生成 IN ()，那是语法错误。
        boolean all = types == null || types.isEmpty()
                || (types.size() == 1 && types.get(0).equals(0));

        // 一条 SQL 拿回「帖子 + 作者名」（原来是两条）。
        // 多取一条（size + 1）来判断有没有下一页，和原来的 .last("limit " + (size + 1)) 等价。
        List<TopicWithAuthor> rows = baseMapper.selectPreviewListWithAuthor(
                cursor, uid, all ? null : types, size + 1);

        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = rows.subList(0, size);
        }

        // 版块名走进程内缓存（原来每个请求查一次库，约 1.7ms CPU）
        Map<Integer, String> typeNames = typeNamesCached();

        List<TopicPreviewVo> result = rows.stream()
                .map(r -> new TopicPreviewVo(r, typeNames.get(r.getType())))
                .toList();

        // liked 是唯一一个"看的人不同、答案不同"的字段，只能单独查。
        // 但绝不能一条一条查 —— 20 条帖子就是 20 次查询（N+1）。
        // 做法：把这 20 条的 id 收集起来，一次 WHERE uid = ? AND topic_id IN (...) 查完。
        // 这也是为什么它是这个 VO 里唯一"外面查好了 set 进来"的字段。
        Set<Integer> topicIds = new HashSet<>();
        for (TopicWithAuthor r : rows) {
            topicIds.add(r.getId());
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
    public TopicDetailVo fetchTopic(int id) {
        // 一条 SQL 拿回「帖子 + 作者名 + 作者头像」（原来是三条：帖子 / 作者 / 作者资料）。
        // 查不到返回 null —— 帖子被删了、或者有人手输一个不存在的 id，都会走到这里。
        TopicDetailRow row = baseMapper.selectDetailWithAuthor(id);
        if (row == null) {
            return null;
        }

        // 版块名走进程内缓存。和列表页共用同一个 TtlCache ——
        // 如果列表页刚访问过，这里连那一次查库都省了。
        //
        // ⚠️ 这里【不再查 liked】。它已经拆到独立接口
        // GET /api/forum/topic/{id}/liked（见 ForumController）。
        //
        // 这不是为了少写几行，而是为了一个性质：
        // 【这个方法返回的对象，谁来看都一样】—— 只有满足这一点，
        // 详情响应才能被公共缓存。之前 liked 混在里面时，缓存它会让
        // A 的点赞状态串给 B（实测 21% 的请求出错）。
        return new TopicDetailVo(row, typeNamesCached().get(row.getType()));
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
