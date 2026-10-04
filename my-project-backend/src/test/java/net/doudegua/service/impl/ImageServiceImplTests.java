package net.doudegua.service.impl;

import net.doudegua.entity.RestBean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 只覆盖校验分支。这几条都在碰 MinIO 之前就返回了，所以直接 new 一个实例即可，
 * 不需要注入 minioClient / imageStorageMapper。
 * <p>
 * 上传成功的完整链路（写 MinIO + 入库 + 取回）没有在这里测，
 * 那种要连真实存储的验证放在端到端跑，别用 mock 假装它过了。
 */
class ImageServiceImplTests {

    private final ImageServiceImpl service = new ImageServiceImpl();

    /** 一张真的 1x1 PNG */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @Test
    void rejectsEmptyFile() {
        RestBean<String> result = service.uploadImage(1,
                new MockMultipartFile("file", "empty.png", "image/png", new byte[0]));

        assertEquals(400, result.code());
        assertEquals("没有选择图片", result.message());
    }

    @Test
    void rejectsOversizedFile() {
        byte[] oversize = new byte[900 * 1024 + 1];
        System.arraycopy(PNG, 0, oversize, 0, PNG.length);

        RestBean<String> result = service.uploadImage(1,
                new MockMultipartFile("file", "big.png", "image/png", oversize));

        assertEquals(400, result.code());
        assertEquals("图片不能超过 900KB，请压缩后再上传", result.message());
    }

    /**
     * 内容是 SVG（XML 文本），但文件名和 Content-Type 都伪装成 png。
     * 按文件头魔数判断就挡得住 —— 这就是禁 SVG 的方式，
     * 不需要再单独维护一条"禁止 svg"的黑名单规则。
     */
    @Test
    void rejectsSvgDisguisedAsPng() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);

        RestBean<String> result = service.uploadImage(1,
                new MockMultipartFile("file", "evil.png", "image/png", svg));

        assertEquals(400, result.code());
        assertEquals("只支持 JPG / PNG / GIF / WebP 格式的图片", result.message());
    }

    /** 声明成 image/png 但其实是纯文本，同样挡掉 —— 证明判断依据不是客户端给的类型 */
    @Test
    void ignoresClientDeclaredContentType() {
        RestBean<String> result = service.uploadImage(1,
                new MockMultipartFile("file", "note.txt", "image/png", "not an image".getBytes(StandardCharsets.UTF_8)));

        assertEquals(400, result.code());
        assertEquals("只支持 JPG / PNG / GIF / WebP 格式的图片", result.message());
    }
}
