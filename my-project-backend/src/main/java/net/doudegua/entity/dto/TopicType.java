package net.doudegua.entity.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 帖子类型（版块）。
 * <p>
 * 注意字段名叫 description 而不是 desc：数据库里原本那列叫 {@code desc}，
 * 而 DESC 是 MySQL 的保留字，{@code SELECT id, name, desc FROM ...} 会直接语法错误
 * （MyBatis-Plus 生成的正是这种不带反引号的 SQL）。
 * 已经把列改名为 description，这样不用到处写反引号。
 */
@Data
@TableName("db_topic_type")
@AllArgsConstructor
public class TopicType {
    @TableId(type = IdType.AUTO)
    Integer id;
    /** 类型名，前端下拉框里显示的就是它 */
    String name;
    /** 类型说明，前端暂时用不到，留着给以后做版块简介 */
    String description;
}
