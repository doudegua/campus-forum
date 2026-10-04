package net.doudegua.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import net.doudegua.entity.dto.Account;
import net.doudegua.entity.dto.AccountProfile;
import net.doudegua.entity.vo.request.ConfirmResetVo;
import net.doudegua.entity.vo.request.EmailRegisterVo;
import net.doudegua.entity.vo.request.EmailResetVo;
import net.doudegua.mapper.AccountMapper;
import net.doudegua.service.AccountProfileService;
import net.doudegua.service.AccountService;
import net.doudegua.utils.Const;
import net.doudegua.utils.FlowUtils;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Service
public class AccountServiceImpl extends ServiceImpl<AccountMapper, Account> implements AccountService {

    @Resource
    FlowUtils flowUtils;

    @Resource
    AmqpTemplate amqpTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    PasswordEncoder passwordEncoder;

    @Resource
    AccountProfileService accountProfileService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Account account = this.findAccountByNameOrEmail(username);
        if(account == null){
            throw new UsernameNotFoundException("用户名或密码错误");
        }
        return User
                .withUsername(username)
                .password(account.getPassword())
                .roles(account.getRole())
                .build();
    }

    @Override
    public String registerEmailVerifyCode(String type, String email, String ip) {
        synchronized (ip.intern()) {
            if(!this.verifyLimit(ip)) {
                return "请求频繁，稍后再试";
            }
            Random random = new Random();
            int code = random.nextInt(899999) + 100000;
            Map<String, Object> data = Map.of("type", type, "email", email, "code", code);
            amqpTemplate.convertAndSend("mail", data);
            stringRedisTemplate.opsForValue()
                    .set(Const.VERIFY_EMAIL_DATA + email, String.valueOf(code), 3, TimeUnit.MINUTES);
            return null;
        }
    }

    @Override
    @Transactional
    public String registerEmailAccount(EmailRegisterVo emailRegisterVo) {
        String email = emailRegisterVo.getEmail();
        String username = emailRegisterVo.getUsername();
        String key = Const.VERIFY_EMAIL_DATA + email;
        String code = stringRedisTemplate.opsForValue().get(key);
        if(code == null) return "Please acquire verification code";
        if(!code.equals(emailRegisterVo.getCode())) return "Wrong verification code";
        if(this.existAccountByEmail(email)) return "Registered email";
        if(this.existAccountByUsername(username)) return "Registered username";
        String password = passwordEncoder.encode(emailRegisterVo.getPassword());
        Account account = new Account(null, username, password, email,"user", new Date());
        if(this.save(account)){
            /*
             * ⚠️ 这里必须同时建一行资料（db_account_details），否则改资料会失败。
             *
             * 踩过的坑：原来只 save 了 db_account，资料表里没有这个人的行。
             * 于是 AccountProfileServiceImpl.updateProfile 里那句
             *     this.update().eq("id", id).set("gender", ...).update()
             * 匹配不到任何行 → 影响 0 行 → 返回 false
             * → 整个方法返回 "fail" → 前端收到 400，
             *   而日志里只有一个没有任何细节的 "fail"，很难查。
             * （更迷惑的是账号名那半边是**更新成功**的，因为它在 db_account 里。）
             *
             * 为什么在注册时建、而不是让 updateProfile 去"没有就插入"：
             *   1. "每个账号都有一行资料"是这个数据模型的基本假设，
             *      应该在建账号时就成立，而不是靠后续每个写入方各自兜底
             *   2. 让三个写入方（改资料/改隐私/传头像）各写一份 upsert，
             *      很容易漏掉一个 —— 而漏掉的那个就会静默坏掉
             *
             * 只设 id 和默认性别，其余留 null。注意 gender 是 primitive int，
             * 不设就是 0（男），和 schema 里该列的默认值一致。
             *
             * @Transactional 保证两行要么都成功、要么都回滚 ——
             * 不能出现"有账号没资料"这种半成品状态。
             */
            accountProfileService.save(new AccountProfile(
                    account.getId(), 0, null, null, null, null,
                    null, null, null, null, null));
            stringRedisTemplate.delete(key);
            return null;
        } else {
            return "Internal error";
        }
    }

    @Override
    public String resetEmailAccountPassword(EmailResetVo vo) {
        String email = vo.getEmail();
        String verify = this.resetConfirm(new ConfirmResetVo(email, vo.getCode()));
        if(verify != null) return verify;
        String password = passwordEncoder.encode(vo.getPassword());
        boolean update = this.update().eq("email", email).set("password", password).update();
        if(update){
            stringRedisTemplate.delete(Const.VERIFY_EMAIL_DATA + email);
        }
        return null;
    }

    @Override
    public String resetConfirm(ConfirmResetVo vo) {
        String email = vo.getEmail();
        String code = stringRedisTemplate.opsForValue().get(Const.VERIFY_EMAIL_DATA + email);
        if(code == null) return "Please acquire verification code";
        if(!code.equals(vo.getCode())) return "Wrong verification code";
        return null;
    }

    @Override
    public Account findAccountByNameOrEmail(String text) {
        return this.query()
                .eq("username", text).or()
                .eq("email", text)
                .one();
    }

    @Override
    public Account findAccountById(int id) {
        return this.query().eq("id", id).one();
    }

    private boolean existAccountByEmail(String email) {
        return this.baseMapper.exists(Wrappers.<Account>query().eq("email", email));
    }

    private boolean existAccountByUsername(String username) {
        return this.baseMapper.exists(Wrappers.<Account>query().eq("username", username));
    }

    private boolean verifyLimit(String address) {
        String key = Const.VERIFY_EMAIL_LIMIT + address;
        return flowUtils.limitOnceCheck(key, 60);
    }

}
