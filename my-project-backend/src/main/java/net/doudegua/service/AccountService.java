package net.doudegua.service;

import com.baomidou.mybatisplus.extension.service.IService;
import net.doudegua.entity.dto.Account;
import net.doudegua.entity.dto.AccountProfile;
import net.doudegua.entity.vo.request.ConfirmResetVo;
import net.doudegua.entity.vo.request.EmailRegisterVo;
import net.doudegua.entity.vo.request.EmailResetVo;
import net.doudegua.entity.vo.request.UpdateProfileVo;
import org.springframework.security.core.userdetails.UserDetailsService;

public interface AccountService extends IService<Account>, UserDetailsService {
    Account findAccountByNameOrEmail(String text);
    Account findAccountById(int id);
//    Account findAccountByEmail(String email);
    String registerEmailVerifyCode(String type, String email, String ip);
    String registerEmailAccount(EmailRegisterVo emailRegisterVo);
    String resetConfirm(ConfirmResetVo confirmResetVo);
    String resetEmailAccountPassword(EmailResetVo emailResetVo);
}
