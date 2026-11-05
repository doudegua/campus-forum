package net.doudegua.controller;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import net.doudegua.entity.RestBean;
import net.doudegua.entity.vo.request.CourseCreateVo;
import net.doudegua.entity.vo.response.AdminCourseVo;
import net.doudegua.service.CourseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理员课程列表。
 * <p>
 * 和 {@link CoursePreheatController} 共用 {@code /api/admin/course} 前缀；
 * 一个负责看状态，一个负责改状态。SecurityConfiguration 已要求 ROLE_ADMIN。
 */
@RestController
@RequestMapping("/api/admin/course")
public class CourseAdminController {

    @Resource
    CourseService courseService;

    @GetMapping("/list")
    public RestBean<List<AdminCourseVo>> listCourses() {
        return RestBean.success(courseService.listAdminCourses());
    }

    /**
     * 新建课程。只建记录，不开放；开放仍走 {@code /preheat}。
     */
    @PostMapping
    public RestBean<Integer> createCourse(@RequestBody @Valid CourseCreateVo vo) {
        try {
            return RestBean.success(courseService.createCourse(vo));
        } catch (IllegalArgumentException e) {
            return RestBean.failure(400, e.getMessage());
        }
    }
}
