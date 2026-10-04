package net.doudegua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import net.doudegua.entity.dto.Topic;

@Mapper
public interface TopicMapper extends BaseMapper<Topic> {
}
