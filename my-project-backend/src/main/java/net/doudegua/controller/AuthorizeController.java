package net.doudegua.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import net.doudegua.entity.RestBean;
import net.doudegua.entity.vo.request.ConfirmResetVo;
import net.doudegua.entity.vo.request.EmailRegisterVo;
import net.doudegua.entity.vo.request.EmailResetVo;
import net.doudegua.service.AccountService;
import net.doudegua.utils.ControllerUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/auth")
public class AuthorizeController {

    @Resource
    AccountService accountService;

    /**
     * 访客演示模式总开关，默认开启（见 application.yaml 里 app.demo.* 的说明）。
     * <p>
     * 普通用户登录**不受它影响**：前端「访客体验」按钮调用的仍然是
     * /api/auth/login 这个标准表单登录接口，这里没有、也不要加免密入口。
     */
    @Value("${app.demo.enabled:true}")
    private boolean demoEnabled;

    /**
     * 演示模式下是否还允许自助注册 / 发送邮箱验证码。默认开启，保证本地开发不受影响。
     * <p>
     * 公网演示部署时关掉它：陌生人不能再注册，也不能用 ask-code 刷邮件；
     * 已存在的普通用户登录、发帖、选课都不受影响。
     */
    @Value("${app.demo.registration-enabled:true}")
    private boolean demoRegistrationEnabled;

    /**
     * 「需要发邮件」的两个入口是否已经被演示模式关掉。
     * <p>
     * 注意这是"演示模式开启 **且** 注册开关关闭"才关。
     * 演示模式整个关掉（demoEnabled=false）时应该恢复普通站点，注册自然要跟着恢复 ——
     * 否则关掉演示之后站点反而永久不能注册，运维会被这个反向开关坑到。
     * <p>
     * 为什么 register 和 ask-code 都要关：
     *   ask-code 是注册的前置步骤；只关 register 的话，陌生人仍然能把
     *   SMTP 当免费发信通道刷。reset 类型的验证码也一起关 ——
     *   演示站没有需要在线重置密码的普通用户，而 demo 的邮箱本来也收不到信。
     */
    private boolean mailEntryClosed() {
        return demoEnabled && !demoRegistrationEnabled;
    }

    @GetMapping("/ask-code")
    public RestBean<Void> askVerifyCode(@RequestParam @Email String email,
                                        @RequestParam @Pattern(regexp = "(register|reset)") String type,
                                        HttpServletRequest request) {
        if (mailEntryClosed()) {
            // 403 而不是 400：这不是请求参数错了，是配置层面的"禁止"。
            // 前端 defaultFailure 只看 message，所以文案要能直接告诉访客下一步做什么。
            return RestBean.failure(403, "访客演示模式已开启，邮箱验证码发送暂不可用；请直接点击「访客体验」进入");
        }
        return ControllerUtils.messageHandle(
                () -> accountService.registerEmailVerifyCode(type, email, request.getRemoteAddr()));
    }

    @PostMapping("/register")
    public RestBean<Void> register(@RequestBody @Valid EmailRegisterVo vo) {
        if (mailEntryClosed()) {
            return RestBean.failure(403, "访客演示模式已开启，注册暂不可用；请直接点击「访客体验」进入");
        }
        return ControllerUtils.messageHandle(() -> accountService.registerEmailAccount(vo));
    }

    @PostMapping("/reset-confirm")
    public RestBean<Void> resetConfirm(@RequestBody @Valid ConfirmResetVo vo) {
        return ControllerUtils.messageHandle(vo, accountService::resetConfirm);
    }

    @PostMapping("/reset-password")
    public RestBean<Void> resetPassword(@RequestBody @Valid EmailResetVo vo) {
        return ControllerUtils.messageHandle(vo, accountService::resetEmailAccountPassword);
    }

    /**
     * 公开的演示模式状态查询，供前端决定登录页 / 注册页显示什么。
     * <p>
     * <b>为什么需要这个接口，而不是让前端读环境变量：</b>
     * 前端的 {@code VITE_DEMO_ENABLED} 是**构建期**打进 bundle 的常量，
     * 后端的 {@code app.demo.*} 是**运行期**读的配置，两者各自独立。
     * 如果前端靠构建期变量去猜"注册关没关"，那么只在 .env 里改了后端配置、
     * 重启后端之后，前端不会跟着变 —— 于是又会出现
     * "界面给了「立即注册」入口、点进去后端 403"的漂移。
     * 让前端向后端要状态，就只有**一个真相来源**。
     * <p>
     * 返回两个字段而不是一个布尔：注册页只关心 registrationEnabled，
     * 而登录页或首页可能还想知道"这是不是一个演示站"（例如显示一条提示）。
     * 语义明确的字段比一个 boolean 更不容易在下一次改动中被误用。
     */
    @GetMapping("/demo-status")
    public RestBean<Map<String, Boolean>> demoStatus() {
        return RestBean.success(Map.of(
                "demoMode", demoEnabled,
                "registrationEnabled", !mailEntryClosed()
        ));
    }
}
