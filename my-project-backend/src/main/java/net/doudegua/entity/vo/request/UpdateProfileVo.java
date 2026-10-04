package net.doudegua.entity.vo.request;

import lombok.Data;
import lombok.Getter;

import java.util.Date;

@Data
@Getter
public class UpdateProfileVo {
    String username;
    int gender;
    String phone;
    String qq;
    String description;
}
