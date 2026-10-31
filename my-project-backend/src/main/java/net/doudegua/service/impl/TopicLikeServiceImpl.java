package net.doudegua.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.buffer.LikeCountBuffer;
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
 * <p>
 * <b>点赞计数从这里挪走了</b>：本类只写权威关系 {@code db_topic_like}，
 * 计数增量交给 {@link LikeCountBuffer}（Redis），再由定时任务批量落库。
 * 这样并发点赞不再抢 {@code db_topic} 同一行的行锁；代价是详情页看到的计数
 * 最多滞后一个落库周期。谁点过赞仍然是数据库说了算，计数只是可重算的派生值。
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

    /**
     * 点赞计数的 Redis 缓冲。
     * <p>
     * 和 {@code topicMapper} 的分工：后者写的是<b>权威关系</b>（谁点过赞），
     * 前者只记<b>还没落库的计数增量</b>。两者不能互相替代 ——
     * 关系丢了用户能重复点赞，计数丢了重算一条 SQL 就回来了。
     */
    @Resource
    LikeCountBuffer likeCountBuffer;

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
        // 这一句就替代了"先 selectCount 查一遍"，而且没有并发窗口。
        // 它同时是【幂等的来源】：只有真插进去了一行，下面才记增量；
        // 重复点赞在数据库这一层就被挡掉，Redis 一个字节都不会动。
        if (baseMapper.insertIgnore(uid, topicId) == 1) {

            // ── 第 3 步：不写 db_topic，只把增量记进 Redis ───────────────
            // 原来的 UPDATE ... like_count = like_count + 1 会让几百个并发点赞
            // 全部串行在 db_topic 同一行的行锁上 —— 这是本项目第一个真实的写热点。
            // 现在热点变成 Redis 里一个键的 INCRBY（单线程、无锁、内存操作），
            // 真正的 UPDATE 交给 LikeCountFlusher 每 5 秒批量做一次。
            //
            // ⚠️ 这一步在事务里，顺序是「先写关系、再记增量」。
            //    Redis 写成功、但事务最终提交失败（连接断、进程被杀）会多算一笔；
            //    计数是派生值，能用 005-repair-like-count.sql 从 db_topic_like 重算。
            //    改成 afterCommit 能把这个窗口关小，但那样"自己刚点的这一下"
            //    在响应里就不可见（要靠额外补偿），这一轮明确保持现有顺序。
            likeCountBuffer.addDelta(topicId, 1);
        }

        // ── 第 4 步：DB 快照 + 还没落库的增量 ────────────────────────────
        // 不再重查 db_topic：刚加的 1 还在 Redis 里，重查也看不到它。
        // 重复点赞同样适用 —— 快照可能是几秒前的，但加上 delta 已经是
        // 当前能给出的最准的值。详情页不走这里，它读 DB 快照，最多滞后一个落库周期。
        int likeCount = (int) (topic.getLikeCount() + likeCountBuffer.getDelta(topicId));
        return new TopicLikeVo(true, likeCount);
    }

    @Override
    @Transactional
    public TopicLikeVo unlike(int uid, int topicId) {
        // 和第 1 步一样，先把快照留下来 —— 后面"重复取消"那条路径要用它的 likeCount
        Topic topic = topicMapper.selectById(topicId);
        if (topic == null) {
            return null;
        }

        // delete(条件) 的返回值同样是影响行数：
        //   1 = 真删掉了一行（这次取消有效）
        //   0 = 本来就没点过（重复取消）
        //
        // 注意 <TopicLike> 是显式写出来的，不是 <>：
        // new 后面直接接 .eq(...) 时，菱形推断会失败（编译器推不出类型参数），
        // 报的是"方法引用无效"这种看不懂的错。TopicServiceImpl 里踩过一次。
        int removed = baseMapper.delete(new LambdaQueryWrapper<TopicLike>()
                .eq(TopicLike::getUid, uid)          // WHERE uid = ?
                .eq(TopicLike::getTopicId, topicId)); // AND topic_id = ?

        // 和 like 对称：只有真的删掉了一行才记 -1。
        // 否则连点两次取消，第二次会拿着 removed=0 也去减一，把别人的赞减掉。
        if (removed == 1) {
            likeCountBuffer.addDelta(topicId, -1);
        }

        // DB 快照 + 未落库增量（这里可能是负数）。
        // 一行都没删也不提前返回：多读一次 Redis，换来的是一致的计数口径 ——
        // 否则"重复取消"会返回一个没算上别人未落库增量的旧快照。
        int likeCount = (int) (topic.getLikeCount() + likeCountBuffer.getDelta(topicId));
        return new TopicLikeVo(false, likeCount);
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
