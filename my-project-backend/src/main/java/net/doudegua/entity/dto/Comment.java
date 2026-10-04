package net.doudegua.entity.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;

/**
 * 帖子评论。<b>平铺</b>，没有楼中楼 —— 没有 parent_id。
 * <p>
 * 等真有人要"回复某条评论"再加。加 parent_id 是一次纯增量的 ALTER，
 * 提前设计反而会把层级结构设计错。
 */
@Data
@TableName("db_comment")
@AllArgsConstructor
public class Comment {
    @TableId(type = IdType.AUTO)
    Integer id;
    /** 所属帖子 db_topic.id */
    Integer topicId;
    /** 作者 db_account.id */
    Integer uid;
    /**
     * 正文。和帖子正文一样是 HTML，前端用 v-html 渲染，
     * 所以入库前必须过 HtmlSanitizer —— 漏了就是一个存储型 XSS。
     */
    String content;
    Date time;
}
