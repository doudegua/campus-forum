package net.doudegua.buffer;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.utils.Const;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 点赞计数的 Redis 缓冲。
 * <p>
 * 它只负责一件事：把"还没写回 MySQL 的点赞增量"攒在 Redis 里，让点赞接口
 * 不必每次都去抢 {@code db_topic} 那一行的行锁。真正把增量写回库的是
 * {@code LikeCountFlusher}（定时任务，下一步加）。
 * <p>
 * <b>这里存的是增量，不是计数</b>：{@code +7} 表示这条帖子还有 7 个赞没落库。
 * 对外展示的最终值 = MySQL 里的 {@code db_topic.like_count} + 这里的增量。
 * <p>
 * <b>它不是权威数据</b>。谁点过赞在 {@code db_topic_like} 里，一条都不能丢；
 * 这里丢了最坏是计数少算几个，一条 SQL 就能从关系表重算回来。
 * <b>正因为它可丢，才敢放在 Redis 里</b> —— 这个边界是整个方案的前提。
 */
@Slf4j
@Component
public class LikeCountBuffer {

    /**
     * 一笔增量 = INCRBY 一次 + SADD 一次，必须一起成功。
     * <p>
     * 拆成两次调用的话，INCRBY 成功而 SADD 失败（连接断、进程正好被杀）时，
     * 这个增量就再也不会被落库任务看到 —— 成了永远算不进去的孤儿。
     * 反过来 SADD 成功、INCRBY 失败只是多扫一个空键，无害，所以顺序不能颠倒。
     * <p>
     * 用 Lua 而不是 MULTI/EXEC：两个命令、一次往返，而且项目里已经有先例
     * （{@code FlowLimitFilter} 的限流计数脚本），写法保持一致。
     * <p>
     * {@code KEYS[1]} = delta 键，{@code KEYS[2]} = dirty 集合；
     * {@code ARGV[1]} = 增量，{@code ARGV[2]} = 帖子 id（集合成员）。
     */
    private static final RedisScript<Long> ADD_DELTA_SCRIPT = RedisScript.of("""
            redis.call('INCRBY', KEYS[1], ARGV[1])
            redis.call('SADD', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    @Resource
    StringRedisTemplate stringRedisTemplate;

    /**
     * 记一笔还没落库的增量。{@code delta} 可正可负：点赞 +1，取消赞 -1。
     * <p>
     * 调用方必须先确认 {@code db_topic_like} 真的插入/删除了一行。
     * 幂等就是靠这个前置条件：同一个用户重复点赞在关系表那一步就被复合主键
     * 挡掉了，根本走不到这里，所以 Redis 这边不需要再判断"是不是第一次"。
     * <p>
     * Redis 不可用时这里会抛异常，让调用方的事务回滚 —— 不能出现
     * "关系写进去了、计数却没人记"的情况。取舍留到改 like() 时再展开。
     */
    public void addDelta(int topicId, long delta) {
        stringRedisTemplate.execute(
                ADD_DELTA_SCRIPT,
                List.of(deltaKey(topicId), Const.LIKE_DIRTY),
                String.valueOf(delta),
                String.valueOf(topicId));
    }

    /**
     * 读当前还没落库的增量，没有就是 0。
     * <p>
     * 点赞/取消赞的响应要用它：返回给用户的是"DB 快照 + 这个增量"，
     * 否则刚点完的那一下要等最多 5 秒才在数字上出现。多这一次 Redis GET 是划算的，
     * 它只在写路径上，QPS 远低于详情页读路径。
     */
    public long getDelta(int topicId) {
        return parseDelta(stringRedisTemplate.opsForValue().get(deltaKey(topicId)), topicId);
    }

    /**
     * 原子地把增量取走并删除键（Redis 的 {@code GETDEL}）。
     * <p>
     * 落库任务用它"认领"一笔增量：取走之后即使进程被杀，这笔也不会被另一个
     * flusher 重复加一遍。代价是取走之后、UPDATE 之前崩溃会少算 —— 能重算，接受。
     * <p>
     * 没有增量时返回 0。调用方不需要区分"没有"和"增量正好是 0"：
     * 两种情况下都只需要把脏标记清掉。
     */
    public long takeDelta(int topicId) {
        String raw = stringRedisTemplate.opsForValue().getAndDelete(deltaKey(topicId));
        return parseDelta(raw, topicId);
    }

    /**
     * 落库失败时把刚取走的增量还回去，等下一轮重试。
     * <p>
     * 只 INCRBY、不 SADD：脏标记是在 UPDATE 成功之后才删的，失败路径上它还在，
     * 不需要再补一次。这也让"还回去"少写一条命令。
     */
    public void restoreDelta(int topicId, long delta) {
        stringRedisTemplate.opsForValue().increment(deltaKey(topicId), delta);
    }

    /**
     * 当前有待落库增量的帖子 id。
     * <p>
     * 返回 {@code Integer} 而不是原始字符串：Redis 集合里存的是字符串，
     * 但调用方需要的是"哪条帖子"，类型在这一层转掉，脏数据的处理也收在这里。
     * <p>
     * 万一集合里被手工塞进非数字成员，直接删掉并告警。不删的话落库任务
     * 每 5 秒都会再遇到它一次，等于一个永久噪声源。
     */
    public Set<Integer> dirtyTopicIds() {
        Set<String> members = stringRedisTemplate.opsForSet().members(Const.LIKE_DIRTY);
        if (members == null || members.isEmpty()) {
            return Set.of();
        }

        Set<Integer> ids = new HashSet<>(members.size());
        for (String member : members) {
            try {
                ids.add(Integer.parseInt(member));
            } catch (NumberFormatException e) {
                log.warn("like:dirty 里有非数字成员（{}），已删除", member);
                stringRedisTemplate.opsForSet().remove(Const.LIKE_DIRTY, member);
            }
        }
        return ids;
    }

    /**
     * 这笔增量已经落库（或确认没有增量），把脏标记清掉。
     * 重复调用没有副作用 —— {@code SREM} 本来就是幂等的。
     */
    public void clearDirty(int topicId) {
        stringRedisTemplate.opsForSet().remove(Const.LIKE_DIRTY, String.valueOf(topicId));
    }

    /**
     * 解析 Redis 里的增量字符串。
     * <p>
     * 正常路径下它一定是本类写进去的数字。但 Redis 是可以被人手工改的，
     * 而"一个坏值"不应该让点赞接口直接 500 —— 计数是派生值，丢掉它以后
     * 还能从 {@code db_topic_like} 重算。所以这里吞掉异常并打一条 warn。
     * <p>
     * ⚠️ 对 {@link #takeDelta} 来说，"吞掉"意味着坏值已经被 GETDEL 删掉了。
     * 这是有意的：它无法参与加法，留着只会每 5 秒告警一次。
     */
    private long parseDelta(String raw, int topicId) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            log.warn("like:delta:{} 的值不是数字（{}），按 0 处理", topicId, raw);
            return 0L;
        }
    }

    private String deltaKey(int topicId) {
        return Const.LIKE_DELTA + topicId;
    }
}
