package net.doudegua.entity.vo.response;

import lombok.Data;
import lombok.NoArgsConstructor;
import net.doudegua.entity.dto.Account;

import java.util.Date;

@Data
@NoArgsConstructor
public class AccountVo {
    /**
     * 用户 id。
     * <p>
     * 加它是因为前端要拼"我的帖子"的地址（{@code /index/user/<id>}），
     * 而在此之前前端**根本不知道自己是谁** —— store 里只有 username/email/role。
     * <p>
     * 这个接口只返回当前登录用户（id 从 JWT 取），所以多一个 id 不涉及任何隐私问题。
     */
    Integer id;
    String username;
    String email;
    String role;
    Date registrationDate;

    public AccountVo(Account account) {
        this.id = account.getId();
        this.username = account.getUsername();
        this.email = account.getEmail();
        this.role = account.getRole();
        this.registrationDate = account.getRegistrationDate();
    }
}
