package net.doudegua.entity.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 一门课。
 * <p>
 * 和论坛表一样没有外键；字段名和列名靠 MyBatis-Plus 默认的下划线转驼峰对应，
 * 所以 {@code selected_count} 会映射到 {@code selectedCount}，不需要额外配置。
 * <p>
 * {@link #selectedCount} 和 {@code Topic.likeCount} 长得像，但语义完全不同：
 * 点赞数是派生值，丢几个可以重算；<b>选课人数是账本，错了就是超卖或超收</b>。
 * 它只允许被 {@code CourseMapper} 里那条带条件的 UPDATE 修改 ——
 * 任何"读出来 +1 再写回"的写法在并发下都会丢更新。
 */
@Data
@NoArgsConstructor
@TableName("db_course")
public class Course {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String name;

    private String teacher;

    /** 学分。数据库是 DECIMAL(3,1)，所以用 BigDecimal 接，别用 double */
    private BigDecimal credit;

    /** 容量（选课名额）。这是不变量里的分母，运行期间不允许改 */
    private Integer capacity;

    /**
     * 已选人数，冗余计数列。
     * <p>
     * 它存在的意义是让"还剩几个"能用一条主键查询算出来，
     * 而不必每次 {@code COUNT(db_course_selection)}。
     * 但它<b>不是</b>用来异步回写的缓存 —— 见 {@code CourseMapper} 里的注释。
     */
    private Integer selectedCount;

    /** 选课开始时间。这一轮骨架不校验时间窗，字段先留着 */
    private Date selectStart;

    /** 选课结束时间 */
    private Date selectEnd;
}
