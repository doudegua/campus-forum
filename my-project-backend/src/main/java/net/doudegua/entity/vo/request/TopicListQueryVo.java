package net.doudegua.entity.vo.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class TopicListQueryVo {
    @Min(1) @Max(50) @NotNull
    Integer size;
    /** 游标：上一页最后一条的 id。不传 = 从头开始 */
    Integer cursor;
    List<Integer> types;
    Integer uid;
}
