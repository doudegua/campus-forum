package net.doudegua.utils;

import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class FlowUtils {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    public boolean limitOnceCheck(String key, int blockTime) {
        Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(key, "",  blockTime, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(ok);
    }
}
