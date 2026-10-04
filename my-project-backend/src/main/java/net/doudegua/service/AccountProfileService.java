package net.doudegua.service;

import com.baomidou.mybatisplus.extension.service.IService;
import net.doudegua.entity.dto.AccountProfile;
import net.doudegua.entity.vo.request.UpdatePrivacyVo;
import net.doudegua.entity.vo.request.UpdateProfileVo;
import net.doudegua.entity.vo.response.PrivacyVo;
import net.doudegua.entity.vo.response.UserProfileVo;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户资料（db_account_details）。
 * <p>
 * 隐私开关也在这个 service 里，没单开一个。判据是"管什么数据"：
 * 那几个开关就是这张表上的列，和资料同一行，拆开只会多一层没有内容的转发。
 * <p>
 * 注意这里<b>没有</b> fetchAvatar 了 —— 头像并进了 {@link ImageService}，
 * 对外 URL 是 {@code /api/image/{key}}。
 */
public interface AccountProfileService extends IService<AccountProfile> {
    AccountProfile findAccountProfileById(int id);

    String fetchProfile(int id);
    String updateProfile(int id, UpdateProfileVo updateProfileVo);
    String uploadAvatar(int id, MultipartFile avatar);

    /** 读自己的隐私设置。别人拿不到这个 —— 见 PrivacyVo 的注释 */
    PrivacyVo fetchPrivacy(int id);

    /** 保存隐私设置。成功返回 null */
    String updatePrivacy(int id, UpdatePrivacyVo vo);

    /**
     * 别人的公开资料。按对方的隐私开关过滤后才返回。
     * <p>
     * 返回 null 表示这个人不存在。
     *
     * @param viewerId 谁在看。<b>当 targetId == viewerId（看自己）时，隐私开关一律不生效</b> ——
     *                 否则你把自己的手机号设成不公开之后，你自己打开自己的主页也看不见它。
     *                 这和 {@code TopicServiceImpl.fetchTopicPreviewList} 里对
     *                 {@code show_topics} 的处理是同一条规则，两处保持一致。
     *                 <p>
     *                 注意这个接口的答案对**其他人**仍然不依赖提问者：
     *                 除了"本人"这一个特例，谁来看都一样。
     */
    UserProfileVo fetchUserProfile(int targetId, int viewerId);
}
