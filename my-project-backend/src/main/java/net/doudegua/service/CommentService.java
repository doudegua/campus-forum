package net.doudegua.service;

import com.baomidou.mybatisplus.extension.service.IService;
import net.doudegua.entity.dto.Comment;
import net.doudegua.entity.vo.request.CommentListQueryVo;
import net.doudegua.entity.vo.request.CreateCommentVo;
import net.doudegua.entity.vo.response.CommentListVo;

/**
 * 评论。
 * <p>
 * 返回值约定和 {@link TopicService} 一样：{@code createComment} 返回 null 表示成功，
 * 非空字符串是给用户看的失败原因；{@code fetchComments} 直接返回数据。
 */
public interface CommentService extends IService<Comment> {

    /** 发评论。成功返回 null */
    String createComment(int uid, CreateCommentVo vo);

    String deleteComment(int uid, int id);

    /** 某条帖子的评论，游标分页 */
    CommentListVo fetchComments(CommentListQueryVo vo);
}
