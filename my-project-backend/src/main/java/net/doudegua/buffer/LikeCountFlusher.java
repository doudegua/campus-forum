package net.doudegua.buffer;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.mapper.TopicMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 点赞增量的定时落库。
 * <p>
 * 点赞接口只把增量记在 Redis（{@link LikeCountBuffer}），这里每 5 秒把攒下的
 * 增量合并成一条 {@code UPDATE} 写回 {@code db_topic.like_count}。
 * 原来几百个并发点赞要抢几百次行锁，现在 5 秒只抢一次 —— 这就是整个改造的收益来源。
 * <p>
 * <b>它不保证"不丢"</b>：GETDEL 之后、UPDATE 之前进程被杀，这笔增量就没了。
 * 但关系表 {@code db_topic_like} 是权威的，计数能从它重算（005 修复 SQL）。
 * 用"可能少算几秒"换"点赞不抢行锁"，这个取舍是有意的。
 */
@Slf4j
@Component
public class LikeCountFlusher {

    @Resource
    LikeCountBuffer likeCountBuffer;

    @Resource
    TopicMapper topicMapper;

    /**
     * 每 5 秒把 Redis 里攒的增量写回 {@code db_topic}。
     * <p>
     * <b>为什么是 fixedDelay 而不是 fixedRate</b>：fixedDelay 是"上一轮结束后
     * 再等 5 秒"，某一轮因为数据库慢拖久了，下一轮不会立刻补跑；fixedRate 是
     * "每 5 秒必须开始一次"，拖慢之后会越积越多。这里宁可周期变长，也不要堆积。
     * <p>
     * <b>为什么不开一个大事务</b>：每个帖子一条 UPDATE，本身就是原子的；
     * 包成一个大事务会让"一条失败拖垮整批"，而且事务越长、行锁持有越久，
     * 正好和这次优化的目标相反。
     */
    @Scheduled(fixedDelay = 5000)
    public void flushLikeCounts() {
        Set<Integer> topicIds = likeCountBuffer.dirtyTopicIds();
        if (topicIds.isEmpty()) {
            return;
        }

        for (Integer topicId : topicIds) {
            // 先原子取走再落库。取走之后即使进程被杀，也不会被另一个 flusher
            // 重复加一遍；代价是取走之后、UPDATE 之前崩溃会少算 —— 能重算，接受。
            long delta = likeCountBuffer.takeDelta(topicId);

            if (delta == 0) {
                // 没有增量，或者攒出来正好是 0（比如点了又取消）。
                // 脏标记必须清掉，否则这个 id 会永远留在集合里，每 5 秒被扫一次。
                likeCountBuffer.clearDirty(topicId);
                continue;
            }

            try {
                int updated = topicMapper.addLikeCount(delta, topicId);

                // 0 行不代表失败：帖子在这期间被删了而已。
                // 增量没法再落库，只能丢掉 —— 但脏标记照样要清，否则它永远留在待办里。
                if (updated == 0) {
                    log.info("帖子已不存在，丢弃这笔点赞增量，topicId={} delta={}", topicId, delta);
                }

                // 顺序不能反：先确认 UPDATE 成功，再清脏标记。
                // 反过来的话，UPDATE 失败时标记已经没了，这笔增量就再也没人处理。
                likeCountBuffer.clearDirty(topicId);
            } catch (Exception e) {
                // 失败补偿：把 delta 还回去，下一轮重试。
                // 脏标记本来就没删，所以只还 delta、不需要再 SADD。
                try {
                    likeCountBuffer.restoreDelta(topicId, delta);
                    log.warn("点赞增量落库失败，delta 已归还，topicId={} delta={}", topicId, delta, e);
                } catch (Exception restoreError) {
                    // Redis 也不可用：这笔增量彻底丢了。
                    // 关系表还在，能靠 005 修复 SQL 重算，所以这里只把账记清楚。
                    e.addSuppressed(restoreError);
                    log.error("点赞增量落库失败且归还失败，这笔增量已丢失，topicId={} delta={}",
                            topicId, delta, e);
                }
            }
        }
    }
}
