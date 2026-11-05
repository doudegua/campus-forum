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
}
