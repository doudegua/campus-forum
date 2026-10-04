package net.doudegua.controller;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.entity.RestBean;
import net.doudegua.entity.dto.TopicType;
import net.doudegua.entity.vo.request.CommentListQueryVo;
import net.doudegua.entity.vo.request.CreateCommentVo;
import net.doudegua.entity.vo.request.CreateTopicVo;
import net.doudegua.entity.vo.request.TopicListQueryVo;
import net.doudegua.entity.vo.response.CommentListVo;
import net.doudegua.entity.vo.response.TopicDetailVo;
import net.doudegua.entity.vo.response.TopicLikeVo;
import net.doudegua.entity.vo.response.TopicPreviewListVo;
import net.doudegua.service.CommentService;
import net.doudegua.service.TopicLikeService;
import net.doudegua.service.TopicService;
import net.doudegua.service.TopicTypeService;
import net.doudegua.utils.ControllerUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 论坛的 HTTP 入口。
 * <p>
 * 注意这里的划分：<b>controller 按"给谁用"分，service 按"管什么数据"分。</b>
 * 所以这个类注入两个 service 是正常的 —— /api/forum/* 是个 URL 命名空间，
 * 不代表"一个类只能对应一个实体"。
 * <p>
 * 这里只管三件事：收参数、调 service、包 RestBean。一行业务逻辑都不该有。
 */
@Slf4j
@RestController
@RequestMapping("/api/forum")
public class ForumController {

    @Resource
    TopicService topicService;

    @Resource
    TopicTypeService topicTypeService;

    @Resource
    CommentService commentService;

    @Resource
    TopicLikeService topicLikeService;

    /** 帖子类型列表，给发帖抽屉里那个下拉框用 */
    @GetMapping("/topic_type")
    public RestBean<List<TopicType>> topicTypeList() {
        return RestBean.success(topicTypeService.listAll());
    }

    /**
     * 发帖。
     * <p>
     * 作者是从 JWT 里取的（{@code @RequestAttribute("id")}，由 JwtAuthorizeFilter 写入），
     * <b>绝不能从请求体里读</b> —— 那样谁都能以别人的身份发帖。
     */
    @PostMapping("/topic")
    public RestBean<Void> createTopic(@RequestAttribute("id") int uid,
                                      @RequestBody @Valid CreateTopicVo vo) {
        return ControllerUtils.messageHandle(() -> topicService.createTopic(uid, vo));
    }

    /**
     * 帖子详情。
     * <p>
     * ⚠️ 两个参数都是 int，编译器分不出谁是谁 —— 这里原来就把顺序写反了
     * （传的是 {@code (viewerId, id)}，而 service 要的是 {@code (id, viewerId)}），
     * 结果不管点哪条帖子都返回同一个人的那条，且不报任何错。
     * 所以参数名必须写清楚，别用 {@code uuid} 这种和用户 id 无关的词。
     */
    @GetMapping("/topic/{id}")
    public RestBean<TopicDetailVo> fetchTopic(@PathVariable("id") int id,
                                              @RequestAttribute("id") int viewerId) {
        TopicDetailVo detailVo = topicService.fetchTopic(id, viewerId);
        if (detailVo == null) {
            // 帖子不存在。注意 HTTP 状态码仍然是 200 —— 本项目把 code 放在 body 里，
            // 全站都是这个约定，别在这儿破例改成 ResponseEntity
            return RestBean.failure(404, "帖子不存在");
        }
        return RestBean.success(detailVo);
    }

    /**
     * 列表。首页 / 版块页 / 个人主页共用这一个接口，靠 query 里的条件区分。
     * <p>
     * 这里<b>不能</b>写 {@code @RequestParam}：那个注解是"从请求里取名叫 vo 的那一个参数"，
     * 而我们是要把一个对象按字段名逐个从 query 里绑。不写注解时 Spring 默认按
     * {@code @ModelAttribute} 处理，正好是这个行为。写成 {@code @RequestParam} 会直接 400。
     */
    @GetMapping("/list-topic")
    public RestBean<TopicPreviewListVo> listTopic(@Valid TopicListQueryVo vo,
                                                  @RequestAttribute("id") int viewerId) {
        return RestBean.success(topicService.fetchTopicPreviewList(vo, viewerId));
    }

    /**
     * 发评论。
     * <p>
     * 作者从 JWT 里取（{@code @RequestAttribute("id")}），和发帖一个道理 ——
     * <b>绝不能从请求体里读</b>，那样谁都能以别人的身份评论。
     */
    @PostMapping("/comment")
    public RestBean<Void> createComment(@RequestAttribute("id") int uid,
                                        @RequestBody @Valid CreateCommentVo vo) {
        return ControllerUtils.messageHandle(() -> commentService.createComment(uid, vo));
    }

    /**
     * 某条帖子的评论列表。游标分页，和 /list-topic 是同一套。
     * <p>
     * 和 list-topic 一样不能写 {@code @RequestParam} —— 那样会变成
     * "从请求里取名叫 vo 的那一个参数"，而我们是要按字段名逐个绑，直接 400。
     */
    @GetMapping("/comment")
    public RestBean<CommentListVo> listComment(@Valid CommentListQueryVo vo) {
        return RestBean.success(commentService.fetchComments(vo));
    }

    /* ---------------- 点赞 ---------------- */

    /**
     * 点赞。
     * <p>
     * 路径挂在 {@code /topic/{topicId}} 底下而不是 {@code /like/{topicId}}：
     * 语义是"85 号帖子的那个赞"，读起来更直接。
     * 它和上面那个 {@code GET /topic/{id}} 不冲突 —— 路径深度和方法都不同。
     * <p>
     * 为什么是 POST/DELETE 两个接口，而不是一个 {@code POST /toggle}：
     * <b>客户端要表达的是"我要它变成已赞"，不是"给我翻一下"</b>。
     * toggle 在双击、重试、两开标签页的情况下会翻两次翻回原样，
     * 而且服务端无法判断这次请求到底想干嘛。
     * POST/DELETE 天然幂等 —— 连点十次和点一次结果一样。
     * <p>
     * {@code uid} 从 JWT 取，和发帖、评论一个道理：绝不能从请求里读。
     */
    @PostMapping("/topic/{topicId}/like")
    public RestBean<TopicLikeVo> like(@PathVariable("topicId") int topicId,
                                      @RequestAttribute("id") int uid) {
        TopicLikeVo vo = topicLikeService.like(uid, topicId);
        if (vo == null) {
            return RestBean.failure(404, "帖子不存在");
        }
        return RestBean.success(vo);
    }

    /**
     * 取消点赞。幂等 —— 没点过的人来取消也不报错。
     * <p>
     * 这里就用 DELETE 了，没有跟着项目"全用 POST"的习惯走：
     * 资源的删除本来就该是 DELETE，而且它和上面的 POST 是严格对称的一对。
     * 前端 axios 那边写法一样，多传一个 method 而已。
     */
    @DeleteMapping("/topic/{topicId}/like")
    public RestBean<TopicLikeVo> unlike(@PathVariable("topicId") int topicId,
                                        @RequestAttribute("id") int uid) {
        TopicLikeVo vo = topicLikeService.unlike(uid, topicId);
        if (vo == null) {
            return RestBean.failure(404, "帖子不存在");
        }
        return RestBean.success(vo);
    }
}
