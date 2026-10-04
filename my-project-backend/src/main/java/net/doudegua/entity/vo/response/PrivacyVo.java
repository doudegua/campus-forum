package net.doudegua.entity.vo.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 隐私开关。<b>只有本人能拿到这个对象</b> —— 它表达的是"设置成什么样"，
 * 不是"别人能看到什么"。
 * <p>
 * 这两个问题必须分开，别图省事合成一个 VO：
 * 如果给别人的资料页返回这个，就等于告诉你"他手机号设置了但不给你看"，
 * 那本身就是一条信息泄露。别人看到的应该是 {@code null}，而不是一个被隐藏的标记。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PrivacyVo {
    Boolean showGender;
    Boolean showPhone;
    Boolean showQq;
    Boolean showDescription;
    /** 是否公开"我发的帖子"列表 */
    Boolean showTopics;
}
