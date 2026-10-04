package net.doudegua.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.entity.RestBean;
import net.doudegua.entity.dto.ImageStorage;
import net.doudegua.mapper.ImageStorageMapper;
import net.doudegua.service.ImageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
public class ImageServiceImpl implements ImageService {

    /**
     * 900KB，刻意低于 Spring 默认的单文件上限（1MB）。
     * 这样正常用户撞到的是这里的友好提示；只有绕过前端直接发大文件时，
     * 才会碰到容器那层，由 ValidationController 兜底。
     */
    private static final long MAX_IMAGE_SIZE = 900 * 1024;

    /** MinIO 对象前缀。只在这里出现一次，改前缀不用满项目找 */
    private static final String KEY_PREFIX = "/image/";

    private static final int HEAD_LENGTH = 12;

    @Resource
    private MinioClient minioClient;

    @Resource
    private ImageStorageMapper imageStorageMapper;

    @Value("${minio.bucket}")
    private String bucket;

    @Override
    public RestBean<String> uploadImage(int uid, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return RestBean.failure(400, "没有选择图片");
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            return RestBean.failure(400, "图片不能超过 900KB，请压缩后再上传");
        }

        String contentType;
        try {
            contentType = sniffContentType(readHead(file));
        } catch (IOException e) {
            log.error("读取上传文件失败: {}", e.getMessage(), e);
            return RestBean.failure(400, "读取图片失败，请重试");
        }
        if (contentType == null) {
            // 不能信 file.getContentType()：那是请求里带的，上传方随便写。
            // 只能按文件头的魔数判断真实类型。
            return RestBean.failure(400, "只支持 JPG / PNG / GIF / WebP 格式的图片");
        }

        String key = UUID.randomUUID().toString().replace("-", "");
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    // 用校验过的类型，不用客户端声明的那份
                    .contentType(contentType)
                    .object(KEY_PREFIX + key)
                    .build());
        } catch (Exception e) {
            log.error("上传图片到 MinIO 失败: {}", e.getMessage(), e);
            return RestBean.failure(500, "图片上传失败，请重试");
        }

        try {
            imageStorageMapper.insert(new ImageStorage(
                    null, uid, key, originalName(file), contentType, (int) file.getSize(), new Date()));
        } catch (Exception e) {
            // 入库失败必须把刚传上去的对象删掉，否则每次失败都留下一个没人引用的孤儿文件
            log.error("图片记录入库失败，回滚已上传的对象 {}: {}", key, e.getMessage(), e);
            removeQuietly(key);
            return RestBean.failure(500, "图片上传失败，请重试");
        }

        log.info("图片上传成功 {}（{}，{} 字节）", key, contentType, file.getSize());
        return RestBean.success(key);
    }

    @Override
    public void fetchImage(String key, HttpServletResponse response) {
        ImageStorage image = imageStorageMapper.selectOne(
                new LambdaQueryWrapper<ImageStorage>().eq(ImageStorage::getImageKey, key));
        if (image == null) {
            // 不存在就是 404。以前统一走异常 → 500，前端分不清"图没了"和"服务器炸了"
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        try (InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(KEY_PREFIX + key)
                .build())) {
            // 类型和长度都取自数据库里那份（上传时按文件头校验过的），
            // 这样既不信客户端，也不用为此多调一次 MinIO 的 statObject
            response.setContentType(image.getContentType());
            response.setContentLength(image.getSize());
            // 每次上传都是一个新的 UUID，同一个 key 的内容永不改变，所以可以放心长缓存。
            // 这是降低图片请求量最有效的一招：回访用户根本不会再发这些请求，
            // 比任何限流都管用。必须在这里显式覆盖 —— Spring Security 默认
            // 会给所有响应加 Cache-Control: no-cache, no-store，那样每翻一页都要重下所有图。
            response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
            stream.transferTo(response.getOutputStream());
        } catch (Exception e) {
            log.error("读取图片失败 {}: {}", key, e.getMessage(), e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void deleteImage(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        // 顺序是有讲究的：先删记录，再删对象。
        //   记录没了但对象还在 → 留下一个没人引用的孤儿文件，谁也发现不了，但无害
        //   对象没了但记录还在 → 取图会先查到记录、再去 MinIO 拿，然后抛异常变成 500，
        //                        用户看到的是"服务器炸了"，而不是"图没了"
        // 所以让脏数据落在无害的那一侧。
        imageStorageMapper.delete(new LambdaQueryWrapper<ImageStorage>().eq(ImageStorage::getImageKey, key));
        removeQuietly(key);
    }

    /** 只读前 12 字节做类型判断。getInputStream() 每次调用都从头开始，不影响后面的上传 */
    private byte[] readHead(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(HEAD_LENGTH);
        }
    }

    /**
     * 按文件头魔数判断真实类型，返回 null 表示不在白名单里。
     * <p>
     * 注意 SVG 是 XML 文本，匹配不上这里任何一条，会被自然挡掉 —— 这正是我们要的：
     * SVG 能内嵌 &lt;script&gt;，一旦和前端同源吐出去就是存储型 XSS，
     * 而同源的脚本能直接读走 localStorage 里的登录 token。
     * 用"二进制魔数白名单"而不是"扩展名/Content-Type 黑名单"，这条就自动成立了。
     */
    private String sniffContentType(byte[] head) {
        if (head.length >= 3
                && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (head.length >= 8 && (head[0] & 0xFF) == 0x89 && startsWith(head, 1, "PNG\r\n\u001A\n")) {
            return "image/png";
        }
        if (startsWith(head, 0, "GIF8")) {
            return "image/gif";
        }
        if (startsWith(head, 0, "RIFF") && startsWith(head, 8, "WEBP")) {
            return "image/webp";
        }
        return null;
    }

    private boolean startsWith(byte[] data, int offset, String ascii) {
        if (data.length < offset + ascii.length()) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if ((data[offset + i] & 0xFF) != ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** 只保留文件名部分并截断。有些客户端会把完整路径塞进 originalFilename */
    private String originalName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "";
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        // 从前面截断，保住扩展名
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private void removeQuietly(String key) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(KEY_PREFIX + key)
                    .build());
        } catch (Exception e) {
            // 回滚也失败就没别的办法了，只能记下来等人工清理
            log.warn("回滚对象失败，可能残留孤儿文件 {}: {}", key, e.getMessage());
        }
    }
}
