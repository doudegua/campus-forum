package net.doudegua.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.entity.RestBean;
import net.doudegua.service.ImageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 论坛图片的 HTTP 入口。这里只做三件事：收参数、调 service、包 RestBean。
 * MinIO 调用、入库、类型校验都在 {@link ImageService} 里。
 */
@Slf4j
@RestController
@RequestMapping("/api/image")
public class ImageController {

    @Resource
    ImageService imageService;

    /** 上传。表单字段名固定为 file，返回的 data 是取图用的 key */
    @PostMapping("/upload")
    public RestBean<String> uploadImage(@RequestAttribute("id") int id,
                                        @RequestParam("file") MultipartFile file) {
        return imageService.uploadImage(id, file);
    }

    /**
     * 取图。GET /api/image/{key}
     * <p>
     * 注意它不按上传者过滤：帖子里的图必须让所有登录用户都能看到，
     * 不能照抄头像那套（那个用的是当前登录用户的 id，只能取到自己的）。
     */
    @GetMapping("/{key}")
    public void fetchImage(@PathVariable("key") String key,
                           HttpServletResponse response) {
        imageService.fetchImage(key, response);
    }
}
