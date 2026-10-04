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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/auth")
public class AuthorizeController {

    @Resource
    AccountService accountService;

    @GetMapping("/ask-code")
    public RestBean<Void> askVerifyCode(@RequestParam @Email String email,
                                        @RequestParam @Pattern(regexp = "(register|reset)") String type,
                                        HttpServletRequest request) {
        return ControllerUtils.messageHandle(
                () -> accountService.registerEmailVerifyCode(type, email, request.getRemoteAddr()));
    }

    @PostMapping("/register")
    public RestBean<Void> register(@RequestBody @Valid EmailRegisterVo vo) {
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
