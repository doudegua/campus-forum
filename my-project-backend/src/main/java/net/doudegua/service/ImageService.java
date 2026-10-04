package net.doudegua.service;

import jakarta.servlet.http.HttpServletResponse;
import net.doudegua.entity.RestBean;
import org.springframework.web.multipart.MultipartFile;

/**
 * 论坛图片的存取。帖子配图和头像都走这里。
 * <p>
 * 头像以前是独立的（自己拼 MinIO 路径、自己存 {@code /avatar/<uuid>}），
 * 现在并进来了。并的理由不只是"少一套代码"：那套**没有按文件头校验类型**，
 * 而头像一旦也走公开取图，一个伪装成 png 的 SVG 就是存储型 XSS。
 * 校验逻辑只应该有一份。
 */
public interface ImageService {

    /**
     * 上传一张图片。
     *
     * @return 成功时 data 是取图用的 key（UUID）；失败时是带原因的 RestBean
     */
    RestBean<String> uploadImage(int uid, MultipartFile file);

    /** 把图片流式写进响应。key 不存在时置 404 */
    void fetchImage(String key, HttpServletResponse response);

    /**
     * 删掉一张图（MinIO 对象 + 数据库记录）。
     * <p>
     * 换头像时必须调，否则每换一次就永久留下一个没人引用的对象和一行记录。
     * key 不存在或删除失败都不抛异常 —— 清理旧图失败不该让"换头像"这个操作失败。
     */
    void deleteImage(String key);
}
