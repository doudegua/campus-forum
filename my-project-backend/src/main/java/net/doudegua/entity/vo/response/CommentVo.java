package net.doudegua.entity.vo.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.doudegua.entity.dto.Comment;

import java.util.Date;

/**
 * 一条评论。
 * <p>
 * 和 {@link TopicPreviewVo} 是同一个思路：实体里有 {@code uid}（作者 id），
 * 但前端要显示的是**名字**，所以名字由外面批量查好了传进来，VO 自己不查库。
 * 同时保留 {@code authorId}，点作者进主页要用。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CommentVo {
    Integer id;
    String content;
    Integer authorId;
    String authorName;
    Date time;

    public CommentVo(Comment comment, String authorName) {
        this.id = comment.getId();
        this.content = comment.getContent();
        this.authorId = comment.getUid();
        this.authorName = authorName;
        this.time = comment.getTime();
    }
}
