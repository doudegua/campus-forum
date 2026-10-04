package net.doudegua.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.entity.dto.Topic;
import net.doudegua.entity.dto.TopicLike;
import net.doudegua.entity.vo.response.TopicLikeVo;
import net.doudegua.mapper.TopicLikeMapper;
import net.doudegua.mapper.TopicMapper;
import net.doudegua.service.TopicLikeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 帖子点赞。
 * <p>
 * 这份是**能跑的完整实现**，每个不眼熟的零件都在注释里标了它是从哪来的。
 * 别再照着它抄一遍 —— 抄完你还是不知道那些 import 是干嘛的。
 * 拿去对着看，然后自己想：如果要把"点赞"换成"收藏"，哪几行要改。
 */
@Slf4j
@Service
public class TopicLikeServiceImpl extends ServiceImpl<TopicLikeMapper, TopicLike> implements TopicLikeService {

    /**
     * {@code topicMapper} 是我们自己声明的（TopicMapper 是手写的接口）。
     * <p>
     * 而下面用到的 {@code baseMapper} <b>不用声明</b> ——
     * 它是父类 {@code ServiceImpl<M, T>} 里的一个 protected 字段，类型就是 M，
     * 也就是我们填进去的 {@code TopicLikeMapper}。
     * 所以 {@code baseMapper.insertIgnore(...)} 能调到我们在 mapper 里手写的方法。
     */
    @Resource
    TopicMapper topicMapper;

    @Override
    @Transactional
    public TopicLikeVo like(int uid, int topicId) {
        // ── 第 1 步：帖子在不在 ──────────────────────────────────────────
        // 这一步必须在任何写入之前（回滚只认异常，不认 return）。
        // selectById 是 BaseMapper 白送的，不用自己写 SQL
        Topic topic = topicMapper.selectById(topicId);
        if (topic == null) {
            return null;
        }

        // ── 第 2 步：一条语句完成"判断 + 写入" ───────────────────────────
        // insertIgnore 返回影响行数：
        //   1 = 这次真插进去了（第一次点）
        //   0 = 撞了主键被跳过（早就点过了）
        // 这一句就替代了"先 selectCount 查一遍"，而且没有并发窗口
        if (baseMapper.insertIgnore(uid, topicId) == 1) {

            // ── 第 3 步：只有真插进去了才给计数加一 ──────────────────────
            //
            // topicMapper.update(实体, 条件) —— 第一个参数是 null，先说为什么：
            // MyBatis-Plus 的 update 有两个参数：
            //   第 1 个：一个实体对象，它里面**非 null 的字段**会被拼成 SET
            //   第 2 个：条件是哪个 Wrapper
            // 我们不用"实体塞值"那套（那要先读出对象），而是自己用 Wrapper 写 SET，
            // 所以第 1 个参数传 null。这是 MP 最反直觉的一处，记住形状就行。
            //
            // setSql 是往 SET 子句里塞一段**原始 SQL**。
            // 换成 .set(Topic::getLikeCount, 5) 就是把计数**设为 5**（绝对值），
            // 而我们要的是在数据库里做加法，所以必须用 setSql。
            // "在数据库里做加法"就是原子的来源 —— 加法不是在我们 Java 里算的。
            topicMapper.update(null, new LambdaUpdateWrapper<Topic>()
                    .setSql("like_count = like_count + 1")
                    .eq(Topic::getId, topicId));   // WHERE id = ?
        }

        // ── 第 4 步：把权威的新状态报回去 ────────────────────────────────
        // 为什么重新查一次：第 1 步拿到的那个 topic 对象是"改之前"的快照，
        // 它的 likeCount 是旧的。而且并发时别人可能也加过，本地算 old+1 会少报。
        // 多这一次查询换来"前端看到的数字永远和服务端一致"，值。
        return new TopicLikeVo(true, topicMapper.selectById(topicId).getLikeCount());
    }

    @Override
    @Transactional
    public TopicLikeVo unlike(int uid, int topicId) {
        if (topicMapper.selectById(topicId) == null) {
            return null;
        }

        // delete(条件) 的返回值同样是影响行数：
        //   1 = 真删掉了一行（这次取消有效）
        //   0 = 本来就没点过（重复取消）
        // LambdaQueryWrapper 和 LambdaUpdateWrapper 是两套类：
        //   查/删 用 LambdaQueryWrapper（在 core.conditions.query 包里）
        //   改    用 LambdaUpdateWrapper（在 core.conditions.update 包里）
        // 写错了编译不过，但记住这个对应关系能省你翻包的时间
        //
        // 注意 <TopicLike> 是显式写出来的，不是 <>：
        // new 后面直接接 .eq(...) 时，菱形推断会失败（编译器推不出类型参数），
        // 报的是"方法引用无效"这种看不懂的错。TopicServiceImpl 里踩过一次。
        int removed = baseMapper.delete(new LambdaQueryWrapper<TopicLike>()
                .eq(TopicLike::getUid, uid)          // WHERE uid = ?
                .eq(TopicLike::getTopicId, topicId)); // AND topic_id = ?

        // 这一句是 unlike 唯一需要小心的地方：
        // 必须先确认"这次真的删掉了一行"，否则连点两次取消，
        // 第二次会拿着 removed=0 也去减一，把别人的赞减掉
        if (removed == 1) {
            topicMapper.update(null, new LambdaUpdateWrapper<Topic>()
                    .setSql("like_count = like_count - 1")
                    .eq(Topic::getId, topicId));
        }

        return new TopicLikeVo(false, topicMapper.selectById(topicId).getLikeCount());
    }

    @Override
    public boolean isLiked(int uid, int topicId) {
        // 纯读，不需要事务。走复合主键 (uid, topic_id) 的索引
        return baseMapper.selectCount(new LambdaQueryWrapper<TopicLike>()
                .eq(TopicLike::getUid, uid)
                .eq(TopicLike::getTopicId, topicId)) > 0;
    }

    @Override
    public Set<Integer> likedTopicIds(int uid, Collection<Integer> topicIds) {
        // 空集合必须在这儿就返回，绝不能进 IN。
        // `IN ()` 是 SQL 语法错误，MyBatis-Plus 不会替你把空集合处理掉 ——
        // 它会老老实实拼出一个 IN ()，然后数据库报错。
        //
        // 这个坑项目里已经出现过一次：TopicListQueryVo 的 types 那个
        // `boolean all = types == null || types.isEmpty()` 判断，存在的意义就是
        // "全部类型"时**跳过 .in(...) 这个条件**，而不是往里面塞个空集合。
        if (topicIds == null || topicIds.isEmpty()) {
            return Set.of();
        }
        // 这里**故意不写 .select(TopicLike::getTopicId)** 只取一列。
        // 试过，结果是 500：TopicLike 当时只有 @AllArgsConstructor，
        // MyBatis 就会走"构造函数映射"，而结果集只有 1 列、构造函数要 3 个参数 →
        // "The constructor takes '3' arguments, but there are only '1' columns"。
        //
        // 现在给实体加了 @NoArgsConstructor 兜住了这种情况，但那两个小列
        // （uid + created_at）本来也不值得为它冒这个险 —— 20 行而已。
        // 教训：**精简 select 的列数和实体的构造函数形状是耦合的**，
        // 这种耦合不写在代码里根本看不出来，出事时又是一个 500。
        return baseMapper.selectList(new LambdaQueryWrapper<TopicLike>()
                        .eq(TopicLike::getUid, uid)
                        .in(TopicLike::getTopicId, topicIds))
                .stream()
                .map(TopicLike::getTopicId)
                .collect(Collectors.toSet());
    }
}
