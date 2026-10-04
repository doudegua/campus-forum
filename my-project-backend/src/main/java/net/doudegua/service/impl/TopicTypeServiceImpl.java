package net.doudegua.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import net.doudegua.entity.dto.TopicType;
import net.doudegua.mapper.TopicTypeMapper;
import net.doudegua.service.TopicTypeService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TopicTypeServiceImpl extends ServiceImpl<TopicTypeMapper, TopicType> implements TopicTypeService {

    @Override
    public List<TopicType> listAll() {
        // 显式带上排序。不写的话返回顺序由存储引擎决定，
        // 数据一多就会变成看起来随机的顺序，而下拉框里顺序变了很奇怪。
        // lambdaQuery() 是 ServiceImpl 白送的链式写法，不用自己 new Wrapper
        return this.lambdaQuery().orderByAsc(TopicType::getId).list();
    }
}
