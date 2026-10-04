package net.doudegua.controller.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.entity.RestBean;
import org.springframework.validation.FieldError;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Objects;

@Slf4j
@RestControllerAdvice
public class ValidationController {

    @ExceptionHandler(ValidationException.class)
    public RestBean<Void> validateException(ValidationException e) {
        log.warn("Resolve [{}: {}]", e.getClass().getName(), e.getMessage());
        return RestBean.failure(400, "请求参数有误");
    }

    /**
     * 参数校验失败时抛的异常。这里特意用父类 {@link BindException} 接，因为它下面有两个分支：
     * <ul>
     *   <li>{@code MethodArgumentNotValidException} —— {@code @Valid @RequestBody} 失败（POST 的 JSON 体）</li>
     *   <li>{@code BindException} —— {@code @Valid} 加在 query 参数对象上失败（GET 的条件对象）</li>
     * </ul>
     * 两个都得接。以前只接了前者，所以后者返回的是 Spring 默认错误体：
     * <pre>{"timestamp":...,"status":400,"error":"Bad Request","path":"..."}</pre>
     * 而正常业务失败返回的是项目自己的格式 {@code {"code":400,"data":null,"message":"..."}}。
     * <p>
     * 前端的 net/index.js 判断的是 {@code data.code === 200}，这种情况下 code 是 undefined，
     * 提示文案也不存在 —— 用户看到一片空白，而排查的人会以为是前端的问题。
     * <p>
     * 这里把 VO 注解里写的 message 直接取出来给用户看，所以文案要写成能读懂的话。
     */
    @ExceptionHandler(BindException.class)
    public RestBean<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("请求参数有误");
        log.warn("参数校验未通过: {}", message);
        return RestBean.failure(400, message);
    }

    /**
     * 方法参数上的约束（类上标了 {@code @Validated}，约束写在方法参数上）失败时走这里。
     * 和上面那个是两套机制，都得接。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public RestBean<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("请求参数有误");
        log.warn("参数校验未通过: {}", message);
        return RestBean.failure(400, message);
    }

    /**
     * 文件超过容器上限（Spring 默认单文件 1MB）时，异常在 multipart 解析阶段就抛出来了，
     * 根本进不到 controller 里。没有这个 handler 的话用户只会看到一个空泛的 500。
     * <p>
     * ImageService 自己的上限（900KB）更低，正常情况会先给出更具体的提示；
     * 这里是"有人绕过前端直接发大文件"时的兜底。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public RestBean<Void> uploadTooLarge(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过限制: {}", e.getMessage());
        return RestBean.failure(400, "图片太大，请压缩后再上传");
    }

    /**
     * 路径变量或 query 参数的类型转不过去，比如 {@code GET /api/user/abc}（要的是 int）。
     * <p>
     * 这个走的不是校验那两条路 —— 参数还没进到方法里，Spring 在绑定时就抛了，
     * 所以上面三个 handler 一个都接不住，最后返回的是 Spring 默认错误体：
     * <pre>{"timestamp":...,"status":400,"error":"Bad Request","path":"/api/user/abc"}</pre>
     * <p>
     * 前端判断的是 {@code data.code === 200}，这时 code 是 undefined，
     * {@code data.message} 也是 undefined —— 用户看到"加载失败：undefined"。
     * 和上面 BindException 那段是同一个坑，只是入口不同。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public RestBean<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        // 不要把 e.getMessage() 直接给用户看，那里面带着完整的方法签名和包名
        log.warn("参数类型不匹配: {} = {}", e.getName(), e.getValue());
        return RestBean.failure(400, "请求参数有误");
    }
}
