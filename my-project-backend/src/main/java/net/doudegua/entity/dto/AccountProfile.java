package net.doudegua.entity.dto;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import net.doudegua.entity.vo.BaseData;

@Data
@TableName("db_account_details")
@AllArgsConstructor
public class AccountProfile implements BaseData {
    @TableId
    Integer id;
    int gender;
    String phone;
    String qq;
    @TableField
    String description;
    /**
     * 头像。存的是 {@code db_image_storage.image_key}，也就是一个裸的 32 位十六进制串。
     * <p>
     * 以前存的是 {@code /avatar/<uuid>} —— 那是 MinIO 里的对象名，头像当时走
     * {@code /api/user/avatar} 自己那套读法。现在并到图片那条线上：
     * MinIO 对象是 {@code /image/<key>}，对外 URL 是 {@code /api/image/<key>}，
     * 于是头像白拿了缓存头、404 语义和流量统计，也就不用再维护第二套了。
     */
    String avatar;

    /*
     * 隐私开关。用 Boolean 而不是 boolean —— 包装类型才有"没设置"这个状态，
     * 而且 MyBatis-Plus 把 TINYINT(1) 映射成 Boolean 是稳的。
     * 注意 gender 还是 primitive int，所以"不想公开性别"只靠 show_gender 表达，
     * 不能靠 gender=null（那做不到）。
     */
    Boolean showGender;
    Boolean showPhone;
    Boolean showQq;
    Boolean showDescription;
    /** 是否公开"我发的帖子"列表 */
    Boolean showTopics;
}
