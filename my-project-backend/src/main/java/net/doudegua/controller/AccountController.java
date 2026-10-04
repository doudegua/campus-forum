package net.doudegua.controller;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.entity.RestBean;
import net.doudegua.entity.dto.Account;
import net.doudegua.entity.dto.AccountProfile;
import net.doudegua.entity.vo.request.UpdatePrivacyVo;
import net.doudegua.entity.vo.request.UpdateProfileVo;
import net.doudegua.entity.vo.response.AccountVo;
import net.doudegua.entity.vo.response.PrivacyVo;
import net.doudegua.entity.vo.response.ProfileVo;
import net.doudegua.entity.vo.response.UserProfileVo;
import net.doudegua.service.AccountProfileService;
import net.doudegua.service.AccountService;
import net.doudegua.utils.ControllerUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/user")
public class AccountController {

    @Resource
    AccountService accountService;

    @Resource
    AccountProfileService accountProfileService;

    @GetMapping("/info")
    public RestBean<AccountVo> info(@RequestAttribute("id") int id) {
        Account account =  accountService.findAccountById(id);
        AccountVo accountVo = new AccountVo(account);
        return RestBean.success(accountVo);
    }

    @GetMapping("/profile")
    public RestBean<ProfileVo> profile(@RequestAttribute("id") int id) {
        AccountProfile accountProfile = accountProfileService.findAccountProfileById(id);
        ProfileVo profileVo = new ProfileVo(accountProfile);
        return RestBean.success(profileVo);
    }

    @PostMapping("/profile")
    public RestBean<Void> profile(@RequestAttribute("id") int id,
                                  @RequestBody UpdateProfileVo vo) {
        return ControllerUtils.messageHandle(() -> accountProfileService.updateProfile(id, vo));
    }

    /**
     * 上传头像。
     * <p>
     * 大小上限挪进 service 了（那里是唯一的出处），这里只负责收文件和转发。
     * <p>
     * 注意只有 POST，没有对应的 GET —— 取头像走 {@code /api/image/{key}}，
     * 和帖子配图同一条路。以前那个 {@code GET /api/user/avatar} 是"只取自己的 + 要鉴权"，
     * 结果是别人的头像取不到、而且进不了 {@code <img src>}。
     */
    @PostMapping("/avatar")
    public RestBean<Void> upLoadAvatar(@RequestAttribute("id") int id,
                                       @RequestParam("file") MultipartFile avatar) {
        log.info("Uploading avatar for user {}", id);
        RestBean<Void> result = ControllerUtils.messageHandle(() -> accountProfileService.uploadAvatar(id, avatar));
        if (result.code() == 200) {
            log.info("Avatar uploaded for user {}", id);
        }
        return result;
    }

    /** 读自己的隐私设置。用 @RequestAttribute 取 id，路径里没有别人的 id —— 这个接口就没有"看别人"的形态 */
    @GetMapping("/privacy")
    public RestBean<PrivacyVo> privacy(@RequestAttribute("id") int id) {
        return RestBean.success(accountProfileService.fetchPrivacy(id));
    }

    @PostMapping("/privacy")
    public RestBean<Void> privacy(@RequestAttribute("id") int id,
                                  @RequestBody @Valid UpdatePrivacyVo vo) {
        return ControllerUtils.messageHandle(() -> accountProfileService.updatePrivacy(id, vo));
    }

    /**
     * 某个用户的公开资料。看自己时隐私开关不生效 —— 见 service 的注释。
     * <p>
     * 路径和同层的 {@code /info}、{@code /profile}、{@code /privacy} 形状一样。
     * Spring 的路径匹配**优先选字面量**，所以 {@code /api/user/privacy} 会稳稳落到上面那两个方法上，
     * 不会被这里当成 targetId 抢走 —— 这是有明确定义的优先级，不是运气。
     * <p>
     * 参数名特意叫 {@code targetId} 而不是 {@code id}：路径里那个 id 和当前登录用户的 id
     * 都是 int，名字起一样了，调用时传反编译器一个字都不会说
     * （ForumController 里刚栽过一次，查了半天）。
     */
    @GetMapping("/{targetId}")
    public RestBean<UserProfileVo> userProfile(@PathVariable("targetId") int targetId,
                                               @RequestAttribute("id") int viewerId) {
        UserProfileVo vo = accountProfileService.fetchUserProfile(targetId, viewerId);
        if (vo == null) {
            return RestBean.failure(404, "用户不存在");
        }
        return RestBean.success(vo);
    }
}
