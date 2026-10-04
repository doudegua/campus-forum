package net.doudegua.service;

import com.baomidou.mybatisplus.extension.service.IService;
import net.doudegua.entity.dto.TopicType;

import java.util.List;

/** 帖子类型。 */
public interface TopicTypeService extends IService<TopicType> {

    /** 全部类型，按 id 升序 */
    List<TopicType> listAll();
}
