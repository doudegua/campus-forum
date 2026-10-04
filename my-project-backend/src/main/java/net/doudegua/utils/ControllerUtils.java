package net.doudegua.utils;

import net.doudegua.entity.RestBean;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 控制器里反复出现的样板。
 * <p>
 * 本项目 service 层的约定是"返回 null 表示成功，返回字符串表示失败原因"，
 * 控制器负责把它翻译成 RestBean。这段翻译以前在 AccountController 和
 * AuthorizeController 里各抄了一份，现在集中到这里。
 */
public class ControllerUtils {

    private ControllerUtils() {
        // 纯静态工具类，不需要实例
    }

    /** service 方法需要先收 VO 时用这个重载 */
    public static <T> RestBean<Void> messageHandle(T vo, Function<T, String> function) {
        return messageHandle(() -> function.apply(vo));
    }

    /** service 返回 null 就是成功，返回非空字符串就是失败原因 */
    public static RestBean<Void> messageHandle(Supplier<String> action) {
        String message = action.get();
        return message == null ? RestBean.success() : RestBean.failure(400, message);
    }
}
