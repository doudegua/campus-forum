package net.doudegua.controller;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import net.doudegua.entity.RestBean;
import net.doudegua.entity.vo.request.CourseSelectVo;
import net.doudegua.entity.vo.response.CourseSelectResultVo;
import net.doudegua.entity.vo.response.CourseVo;
import net.doudegua.service.CourseService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 抢课的 HTTP 入口。
 * <p>
 * 路径是独立的 {@code /api/course}，没有塞进 {@code /api/forum}：
 * 抢课和论坛是两个业务，共用的是基础设施（Redis/MQ/鉴权/限流），不是领域。
 * <p>
 * 和 {@code ForumController} 同一条硬规矩：<b>uid 只能从 JWT 取</b>
 * （{@code @RequestAttribute("id")}，由 {@code JwtAuthorizeFilter} 写入），
 * 绝不能从请求体或 query 里读。
 * <p>
 * 全站约定不变：HTTP 状态码永远是 200（限流/鉴权失败除外），
 * 成败看 body 里的 {@code code}。所以 {@code QUEUED} 是 200 + code 200。
 */
@RestController
@RequestMapping("/api/course")
public class CourseController {

    @Resource
    CourseService courseService;

    /** 课程列表。未登录也能看？—— 不能，SecurityConfiguration 里 anyRequest().authenticated() */
    @GetMapping("/list")
    public RestBean<List<CourseVo>> listCourses() {
        return RestBean.success(courseService.listCourses());
    }

    /**
     * 抢课。同步只做"预扣 + 入队"，不等数据库落库 —— 这是整个设计的关键取舍，
     * 理由见 {@code CourseService.select} 的注释。
     */
    @PostMapping("/select")
    public RestBean<CourseSelectResultVo> select(@RequestAttribute("id") int uid,
                                                 @RequestBody @Valid CourseSelectVo vo) {
        CourseSelectResultVo result = courseService.select(uid, vo.getCourseId());
        if (CourseSelectResultVo.NOT_IMPLEMENTED.equals(result.getStatus())) {
            return RestBean.failure(501,
                    "抢课功能尚未实现：骨架已就位，核心见 docs/course-selection.md");
        }
        return RestBean.success(result);
    }

    /**
     * 查最终结果。前端在收到 QUEUED 之后轮询这个接口，直到不再是 QUEUED。
     * <p>
     * courseId 用 {@code @RequestParam} 而不是路径参数：它和上面的 POST 是成对的，
     * 前端拼 query 更顺手；路径参数版本将来加"取消抢课"也不会冲突。
     */
    @GetMapping("/result")
    public RestBean<CourseSelectResultVo> result(@RequestAttribute("id") int uid,
                                                 @RequestParam("courseId") int courseId) {
        CourseSelectResultVo result = courseService.result(uid, courseId);
        if (CourseSelectResultVo.NOT_IMPLEMENTED.equals(result.getStatus())) {
            return RestBean.failure(501,
                    "抢课结果查询尚未实现：骨架已就位，核心见 docs/course-selection.md");
        }
        return RestBean.success(result);
    }
}
