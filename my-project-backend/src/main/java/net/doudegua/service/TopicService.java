package net.doudegua.service;

import com.baomidou.mybatisplus.extension.service.IService;
import net.doudegua.entity.dto.Topic;
import net.doudegua.entity.vo.request.CreateTopicVo;
import net.doudegua.entity.vo.request.TopicListQueryVo;
import net.doudegua.entity.vo.response.TopicDetailVo;
import net.doudegua.entity.vo.response.TopicPreviewListVo;

/**
 * 帖子。
 * <p>
 * 一个 service 管一张表 + 它自己的业务。跨表的逻辑放"以谁为主"的那个 service 里 ——
 * 比如发帖要校验类型，那属于帖子的业务，所以方法在这里，内部去问 {@link TopicTypeService}。
 * <p>
 * 方法返回值遵循本项目约定：null 表示成功，非空字符串是给用户看的失败原因。
 * 但 {@link #fetchTopicList} 不一样：它查的不是"成功/失败"，而是一份数据，
 * 所以直接返回对象；出错就抛异常，让全局异常处理器去管。
 */
public interface
TopicService extends IService<Topic> {

    /** 发帖。成功返回 null */
    String createTopic(int uid, CreateTopicVo vo);

    String editTopic(int uid, CreateTopicVo vo, int id);

    String deleteTopic(int uid, int id);

    /**
     * 首页/版块/个人页共用的列表查询。游标分页，不用 OFFSET。
     *
     * @param viewerId 谁在看。只在一种情况下用得上：查的是**别人**的帖子（vo 里带了 uid），
     *                 而那个人把 {@code show_topics} 关了 —— 这时返回空列表。
     *                 自己看自己不受这个开关限制，否则把主页设成隐藏之后
     *                 你自己也看不到自己的帖子了。
     */
    TopicPreviewListVo fetchTopicPreviewList(TopicListQueryVo vo, int viewerId);

    /**
     * 帖子详情。
     * <p>
     * 两个参数都是 int，调用时极容易传反 —— 所以名字必须能一眼分清。
     * {@code id} 是要看的帖子，{@code viewerId} 是"谁在看"（从 JWT 里来的，不是请求参数）。
     * 后者现在还没被用到，等点赞表建好之后它要用来算 {@code liked}。
     */
    TopicDetailVo fetchTopic(int id, int viewerId);

}
