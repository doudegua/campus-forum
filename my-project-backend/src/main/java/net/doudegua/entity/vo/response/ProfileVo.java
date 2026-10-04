package net.doudegua.entity.vo.response;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.doudegua.entity.dto.AccountProfile;
import net.doudegua.entity.vo.BaseData;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileVo {
    int gender;
    String phone;
    String qq;
    @TableField
    String description;
    String avatar;

    public ProfileVo(AccountProfile accountProfile) {
        this.gender = accountProfile.getGender();
        this.phone = accountProfile.getPhone();
        this.qq = accountProfile.getQq();
        this.description = accountProfile.getDescription();
        this.avatar = accountProfile.getAvatar();
    }
}
