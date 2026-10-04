package net.doudegua.entity.vo.request;

import jakarta.validation.constraints.Email;
import org.hibernate.validator.constraints.Length;

public class EmailChangeVo {
    @Email
    String email;
    @Length(max = 6, min = 6)
    String code;
}
