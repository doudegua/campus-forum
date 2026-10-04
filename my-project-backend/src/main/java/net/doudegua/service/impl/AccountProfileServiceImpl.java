package net.doudegua.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.doudegua.entity.RestBean;
import net.doudegua.entity.dto.Account;
import net.doudegua.entity.dto.AccountProfile;
import net.doudegua.entity.vo.request.UpdatePrivacyVo;
import net.doudegua.entity.vo.request.UpdateProfileVo;
import net.doudegua.entity.vo.response.PrivacyVo;
import net.doudegua.entity.vo.response.UserProfileVo;
import net.doudegua.mapper.AccountMapper;
import net.doudegua.mapper.AccountProfileMapper;
import net.doudegua.service.AccountProfileService;
import net.doudegua.service.ImageService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class AccountProfileServiceImpl extends ServiceImpl<AccountProfileMapper, AccountProfile> implements AccountProfileService {

    /** 头像上限。比帖子配图（900KB）严得多，因为它要在列表里反复出现 */
    private static final long MAX_AVATAR_SIZE = 200 * 1024;

    /**
     * 这里注入的是 <b>Mapper</b> 而不是 AccountService，这是有意的。
     * <p>
     * 资料服务确实需要碰 db_account（改用户名、查账号），但**只用到增删改查**，
     * 不需要账号服务里的业务逻辑（注册、验证码、改密码）。
     * <p>
     * 原来注入的是 {@code AccountService}，结果在"注册时同时建资料行"之后
     * 形成了一个环：
     * <pre>
     *   AccountServiceImpl → AccountProfileService → AccountService
     * </pre>
     * Spring Boot 2.6+ 默认禁止环依赖，应用直接起不来。改注入 Mapper 就断开了 ——
     * Mapper 是依赖树的叶子，不会反过来依赖任何 Service，
     * 所以**结构上不可能再成环**。
     * <p>
     * 这类"本来只需要数据访问，却注入了整个业务服务"是很常见的成环原因。
     * 判断标准很简单：需要的只是 CRUD，还是需要别人封装好的业务规则？
     */
    @Resource
    private AccountMapper accountMapper;

    /**
     * 头像不再自己操作 MinIO 了，改走图片那条线。
     * 于是这个类里一行 io.minio 的代码都不剩 —— 存储细节只该有一个出口。
     */
    @Resource
    private ImageService imageService;

    @Override
    public AccountProfile findAccountProfileById(int id) {
        return this.query().eq("id", id).one();
    }

    @Override
    public String fetchProfile(int id) {
        AccountProfile profile = findAccountProfileById(id);
        return profile == null ? null : profile.getAvatar();
    }

    @Override
    public String updateProfile(int id, UpdateProfileVo updateProfileVo) {
        // 改用户名落在 db_account 上，所以走的是 AccountMapper 而不是本类的 this.update()。
        // mapper.update(实体, Wrapper)：第一个参数传 null 表示"要 SET 的列我自己写"，
        // 返回的是**受影响行数**。
        //
        // 这里返回 0 是可能的（id 不存在时），所以必须把它并进 success ——
        // 否则"改了一个不存在的账号"会被当成成功。
        int accountRows = accountMapper.update(null, Wrappers.<Account>lambdaUpdate()
                .eq(Account::getId, id)
                .set(Account::getUsername, updateProfileVo.getUsername()));
        boolean success = accountRows > 0;

        // ⚠️ 这一句曾经是"改资料返回 400 且只报 fail"的根源：
        //   资料行不存在时这里影响 0 行 → success 变 false → 整个方法返回 "fail"。
        //   而资料行不存在的原因是注册时没建 —— 已在 registerEmailAccount 里修掉，
        //   并用 @Transactional 保证账号和资料两行一起建。
        success &= this.update().eq("id", id)
                .set("gender", updateProfileVo.getGender())
                .set("phone", updateProfileVo.getPhone())
                .set("qq", updateProfileVo.getQq())
                .set("description", updateProfileVo.getDescription())
                .update();
        return success ? null : "fail";
    }

    @Override
    public String uploadAvatar(int id, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "没有选择图片";
        }
        if (file.getSize() > MAX_AVATAR_SIZE) {
            return "头像不能超过 200KB，请压缩后再上传";
        }

        // 直接复用图片那条线：按文件头校验类型（挡住伪装成 png 的 SVG）、写 MinIO、
        // 写 db_image_storage、以及入库失败时回滚对象，它全做了。
        //
        // 以前这里自己拼路径、自己 putObject，**没有任何类型校验**，
        // 而且 contentType 用的是 file.getContentType()（客户端说了算）。
        // 那套在头像只走 /api/user/avatar 时勉强还能接受；现在头像也走公开的
        // /api/image/{key}，一个伪装成 png 的 SVG 就成了同源存储型 XSS，
        // 而同源脚本能直接读走 localStorage 里的登录 token。
        // 合并这件事的真正价值在这儿，不在"少写几行"。
        RestBean<String> uploaded = imageService.uploadImage(id, file);
        if (uploaded.code() != 200) {
            return uploaded.message();
        }
        String key = uploaded.data();

        AccountProfile old = findAccountProfileById(id);
        if (!this.update().eq("id", id).set("avatar", key).update()) {
            // 更新没成功，刚传上去的图就没人引用了，立刻清掉别留着当垃圾
            imageService.deleteImage(key);
            return "头像保存失败，请重试";
        }

        // 更新成功之后才删旧图。反过来的话，删完旧图、更新又失败，
        // 用户就两头都空了 —— 头像直接消失
        if (old != null) {
            imageService.deleteImage(old.getAvatar());
        }
        return null;
    }

    @Override
    public PrivacyVo fetchPrivacy(int id) {
        AccountProfile profile = findAccountProfileById(id);
        if (profile == null) {
            // 这个人的资料行还不存在（比如刚注册还没进过设置页）。
            // 返回全开的默认值，而不是报错 —— 默认值本来就该由建行时的 DEFAULT 决定，
            // 这里只是把"还没建行"这种情况表现得和"建了但全默认"一样
            return new PrivacyVo(true, true, true, true, true);
        }
        return new PrivacyVo(profile.getShowGender(), profile.getShowPhone(),
                profile.getShowQq(), profile.getShowDescription(), profile.getShowTopics());
    }

    @Override
    public String updatePrivacy(int id, UpdatePrivacyVo vo) {
        boolean success = this.update().eq("id", id)
                .set("show_gender", vo.getShowGender())
                .set("show_phone", vo.getShowPhone())
                .set("show_qq", vo.getShowQq())
                .set("show_description", vo.getShowDescription())
                .set("show_topics", vo.getShowTopics())
                .update();
        return success ? null : "保存失败，请重试";
    }

    @Override
    public UserProfileVo fetchUserProfile(int targetId, int viewerId) {
        Account account = accountMapper.selectById(targetId);
        if (account == null) {
            return null;
        }
        AccountProfile profile = findAccountProfileById(targetId);

        // 看自己的时候，隐私开关一律不生效。
        // 不这么做的话，你把自己手机号设成不公开之后，你自己打开自己的主页也看不见它 ——
        // 那是个很直观的 bug，而且用户完全想不通为什么。
        // 这跟 TopicServiceImpl 里 show_topics 的判断（uid != viewerId）是同一条规则。
        boolean self = targetId == viewerId;

        // 这里是全项目**唯一**一处把 db_account_details 变成"给外人看的资料"的代码。
        // 以后要加可见字段就在这儿加，别在别处再拼一遍 ——
        // 拼第二遍的人不会记得回头看隐私开关，而漏一次就是把手机号发出去了。
        // 注意 vo 里没有 email：AccountVo 有，所以那个不能拿来当"别人的资料"用。
        UserProfileVo vo = new UserProfileVo();
        vo.setId(account.getId());
        vo.setUsername(account.getUsername());
        vo.setRole(account.getRole());
        vo.setRegistrationDate(account.getRegistrationDate());

        if (profile != null) {
            // 头像不受任何开关管 —— 它本来就是给人看的
            vo.setAvatar(profile.getAvatar());

            // 不赋值就保持 null。"隐藏"和"没填"在响应里长得一模一样，这是故意的：
            // 区分开就等于告诉访问者"他填了但不想给你看"，那本身也是一条泄露
            if (self || allows(profile.getShowGender())) {
                vo.setGender(profile.getGender());
            }
            if (self || allows(profile.getShowDescription())) {
                vo.setDescription(profile.getDescription());
            }
            if (self || allows(profile.getShowPhone())) {
                vo.setPhone(profile.getPhone());
            }
            if (self || allows(profile.getShowQq())) {
                vo.setQq(profile.getQq());
            }
        }
        return vo;
    }

    /**
     * 开关为 true 才放行。
     * <p>
     * 资料行不存在、或者列恰好是 NULL（理论上不会，建表时是 NOT NULL DEFAULT 1），
     * 都按"允许"处理 —— 和建表的默认值保持一致，
     * 别让"这个人还没建过资料行"变成"他的一切都不可见"。
     */
    private static boolean allows(Boolean flag) {
        return !Boolean.FALSE.equals(flag);
    }
}
