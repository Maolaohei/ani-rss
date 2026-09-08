package ani.rss.service;

import ani.rss.entity.Ani;
import ani.rss.entity.BgmInfo;
import ani.rss.util.other.BgmUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AniService {

    /**
     * 更新订阅总集数
     *
     * @param ani     订阅
     * @param bgmInfo BGM
     * @param force   强制
     * @return 是否已发生更新
     */
    public Boolean updateTotalEpisodeNumber(Ani ani, BgmInfo bgmInfo, Boolean force) {
        Integer totalEpisodeNumber = ani.getTotalEpisodeNumber();
        if (totalEpisodeNumber == null) {
            // 旧订阅数据可能缺失 totalEpisodeNumber，跳过更新避免拆箱 NPE
            log.warn("{} 总集数为空，跳过总集数更新", ani.getTitle());
            return false;
        }
        if (!Boolean.TRUE.equals(force)) {
            // 未开启强制更新（force 为空视为未开启）
            if (totalEpisodeNumber > 0) {
                // 总集数不为 0
                return false;
            }
        }

        String title = ani.getTitle();

        // 自动更新总集数信息
        int bgmEp = BgmUtil.getEps(bgmInfo);
        if (bgmEp == totalEpisodeNumber) {
            // 集数未发生改变
            return false;
        }

        ani.setTotalEpisodeNumber(bgmEp);

        log.info("{} 总集数发生更新: {}", title, bgmEp);
        return true;
    }
}
