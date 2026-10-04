package net.doudegua.entity.vo.response;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 别人的公开资料。
 * <p>
 * <b>为什么不能直接复用 {@link AccountVo}</b>：那个里面有 {@code email}。
 * 拿它去返回"别人的资料"，就是把别人邮箱发给任何一个点进来的人。
 * 这个理由比"字段不一样"硬得多 —— 是它决定了必须新开一个 VO。
 * <p>
 * <b>为什么不能直接返回 {@link net.doudegua.entity.dto.AccountProfile}</b>：
 * 那样等于完全绕过下面的隐私过滤，五个开关全部失效。
 * <p>
 * 这个 VO 的字段一旦被赋值就是"可以给外人看的"。不该给的保持 null ——
 * 所以<b>它只能由 AccountProfileServiceImpl.fetchUserProfile 一处生产</b>，
 * 别在别的地方再拼一次。拼第二遍的人不会记得回头看隐私开关，
 * 而漏一次就是把手机号发出去了。
 */
@Data
@NoArgsConstructor
public class UserProfileVo {
    Integer id;
    String username;
    /** 角色，前端可以据此画个小标签 */
    String role;
    Date registrationDate;
    /** 头像永远公开 —— 它本来就是给人看的，没有"隐藏头像"这种需求 */
    String avatar;

    /*
     * 下面这几个字段为 null 有两种可能：没填，或者设置了不给看。
     * **故意不区分**：区分开就等于告诉访问者"他填了但不想给你看"，
     * 那本身也是一条泄露。前端一律显示成"未填写"就行。
     */

    /**
     * 性别。
     * <p>
     * <b>必须是 Integer，不能是 int。</b>实体里的 {@code AccountProfile.gender}
     * 是 primitive，表达不了"这一项不给看" —— primitive 没有 null。
     * 只有换成包装类型，null 才能承担"隐藏了"这个含义。
     */
    Integer gender;
    String description;
    String phone;
    String qq;

    // 这里没有 email，而且是**故意**没有的。要加字段之前先想清楚它会发给谁。
}
