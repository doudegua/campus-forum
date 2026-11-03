package net.doudegua.controller;

import jakarta.annotation.Resource;
import net.doudegua.entity.RestBean;
import net.doudegua.entity.vo.request.CoursePreheatVo;
import net.doudegua.entity.vo.response.PreheatResult;
import net.doudegua.service.CoursePreheatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 抢课预热 / 关停入口。
 * <p>
 * 路径放在 {@code /api/admin/**} 下，是为下一轮权限留的边界：
 * 现在 {@code anyRequest().authenticated()}，任何登录用户都能调；
 * 加权限时只需在 SecurityConfiguration 里补一条 {@code hasRole("ADMIN")}。
 * <p>
 * 控制器不写业务：逐门课的结果由 service 返回，允许部分成功。
 */
@RestController
@RequestMapping("/api/admin/course")
public class CoursePreheatController {

    @Resource
    CoursePreheatService coursePreheatService;

    @PostMapping("/preheat")
    public RestBean<List<PreheatResult>> preheat(@RequestBody List<CoursePreheatVo> vos) {
        if (vos == null || vos.isEmpty()) {
            return RestBean.failure(400, "课程列表不能为空");
        }
        return RestBean.success(coursePreheatService.preheat(vos));
    }

    @PostMapping("/close")
    public RestBean<List<PreheatResult>> close(@RequestBody List<Integer> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            return RestBean.failure(400, "课程 id 列表不能为空");
        }
        return RestBean.success(coursePreheatService.close(courseIds));
    }
}
