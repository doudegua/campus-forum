package net.doudegua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import net.doudegua.entity.dto.Comment;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
}
