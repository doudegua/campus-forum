package net.doudegua.entity.vo.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 保存隐私设置的入参。
 * <p>
 * 五个字段全部 {@code @NotNull}，不是懒得写"部分更新" —— 这是刻意的契约：
 * 设置页每次把五个开关整体提交，服务端就整体覆盖。
 * <p>
 * 如果允许缺字段（null 表示"这一项不改"），会踩到一个很难查的坑：
 * MyBatis-Plus 的 {@code .set(列, null)} 是**无条件**写进 SET 子句的，
 * 所以少传一个字段就会把那列写成 NULL。而这几列是 NOT NULL，
 * 于是报的是 SQL 约束错误 —— 一个"设置页忘了传某个开关"会表现成 500，
 * 排查方向完全指不到前端。
 * <p>
 * 想真做部分更新得用条件重载 {@code .set(vo.getX() != null, 列, 值)}，
 * 那是另一套语义，等真的有这个需求再说。
 */
@Data
public class UpdatePrivacyVo {
    @NotNull(message = "参数不完整")
    Boolean showGender;
    @NotNull(message = "参数不完整")
    Boolean showPhone;
    @NotNull(message = "参数不完整")
    Boolean showQq;
    @NotNull(message = "参数不完整")
    Boolean showDescription;
    @NotNull(message = "参数不完整")
    Boolean showTopics;
}
