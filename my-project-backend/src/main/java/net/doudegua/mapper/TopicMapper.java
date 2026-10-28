package net.doudegua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.doudegua.entity.dto.Topic;
import net.doudegua.entity.dto.TopicDetailRow;
import net.doudegua.entity.dto.TopicWithAuthor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TopicMapper extends BaseMapper<Topic> {

    /**
     * 列表页专用查询：帖子 + 作者名，一次 JOIN 拿完。
     * <p>
     * 和原来 {@code LambdaQueryWrapper + this.list()} 的等价性：
     * <ul>
     *   <li>条件：{@code id < cursor}（游标分页）、{@code uid = ?}（个人主页）、
     *       {@code type IN (...)}（版块筛选）—— 三者都可选，和原来一样</li>
     *   <li>排序：{@code ORDER BY id DESC}，和原来一样</li>
     *   <li>条数：外部传 {@code size + 1}，多取一条用来判断"还有没有下一页"，
     *       和原来的 {@code .last("limit " + (size + 1))} 一致</li>
     * </ul>
     * <p>
     * 用 <b>LEFT JOIN</b> 而不是 INNER JOIN：项目里没有外键约束，理论上可能存在
     * uid 指向一个已被删掉的账号的帖子。用 INNER JOIN 的话这种帖子会<b>整条消失</b>，
     * 而原来是"作者名查不到显示 null"—— 优化不能改变行为，数据不能悄悄少几条。
     * <p>
     * 为什么用注解 + {@code <script>} 而不是 XML：项目里没有 mapper XML 目录，
     * 加一个目录还要配 {@code mybatis-plus.mapper-locations}，为一个查询不值得。
     * <p>
     * ⚠️ {@code <} 在 XML 里必须写成 {@code &lt;}，否则解析器会当成标签开头。
     */
    @Select("""
            <script>
            SELECT t.id, t.title, t.type, t.content, t.uid, t.time,
                   t.comment_count AS commentCount,
                   t.like_count    AS likeCount,
                   a.username      AS authorName
            FROM db_topic t
            LEFT JOIN db_account a ON a.id = t.uid
            <where>
                <if test="cursor != null"> AND t.id &lt; #{cursor} </if>
                <if test="uid != null"> AND t.uid = #{uid} </if>
                <if test="types != null and types.size() > 0">
                    AND t.type IN
                    <foreach item="x" collection="types" open="(" separator="," close=")">#{x}</foreach>
                </if>
            </where>
            ORDER BY t.id DESC
            LIMIT #{size}
            </script>
            """)
    List<TopicWithAuthor> selectPreviewListWithAuthor(@Param("cursor") Integer cursor,
                                                      @Param("uid") Integer uid,
                                                      @Param("types") List<Integer> types,
                                                      @Param("size") int size);

    /**
     * 详情页专用查询：帖子 + 作者名 + 作者头像，一条 SQL 拿完。
     * <p>
     * 原来这里是 4 条查询（帖子、版块、作者、作者资料），压测实测每条 SQL
     * 要花约 1.69ms CPU，而 MySQL 执行只要 0.6-0.9ms —— 差额全是
     * JDBC 往返和 MyBatis 映射的固定开销。把作者和头像 JOIN 进来省掉两条。
     * <p>
     * <b>两次 JOIN 都必须是 LEFT</b>：
     * <ul>
     *   <li>{@code db_account} —— 账号被删了，帖子不能整条消失</li>
     *   <li>{@code db_account_details} —— 用户没填过资料（那张表没有他的行），
     *       帖子同样不能消失，只是 avatar 为 null</li>
     * </ul>
     * 用 INNER JOIN 会让这两类帖子查不出来 —— 优化不能改变行为。
     * <p>
     * ⚠️ 关联键：{@code db_account_details} 的主键本身就是用户 id（它是
     * {@code db_account} 的一对一扩展表），所以是 {@code p.id = t.uid}，
     * <b>不是</b> {@code p.uid = t.uid}（那张表根本没有 uid 列）。
     * <p>
     * ⚠️ 这里必须带 {@code t.content} —— 列表页不需要正文，详情页必须要。
     */
    @Select("""
            SELECT t.id, t.title, t.type, t.content, t.uid, t.time,
                   t.comment_count AS commentCount,
                   t.like_count    AS likeCount,
                   a.username      AS authorName,
                   p.avatar        AS authorAvatar
            FROM db_topic t
            LEFT JOIN db_account a         ON a.id = t.uid
            LEFT JOIN db_account_details p ON p.id = t.uid
            WHERE t.id = #{id}
            """)
    TopicDetailRow selectDetailWithAuthor(@Param("id") int id);
}
