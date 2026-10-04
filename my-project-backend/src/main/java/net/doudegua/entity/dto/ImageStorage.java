package net.doudegua.entity.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;

@Data
@TableName("db_image_storage")
@AllArgsConstructor
public class ImageStorage {
    @TableId(type = IdType.AUTO)
    Integer id;
    /** 上传者 db_account.id */
    Integer uid;
    /** UUID，同时是取图 URL 里的标识。MinIO 里的完整路径由 ImageServiceImpl 加前缀拼出来 */
    String imageKey;
    /** 用户上传时的原始文件名，仅用于展示/下载，不参与寻址 */
    String originalName;
    /** 服务端按文件头判定的类型。特意不用客户端声明的那份 —— 那个可以伪造 */
    String contentType;
    Integer size;
    Date createdAt;
}
