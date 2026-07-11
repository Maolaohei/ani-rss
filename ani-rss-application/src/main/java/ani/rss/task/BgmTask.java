package ani.rss.task;

import ani.rss.entity.Ani;
import ani.rss.entity.BgmInfo;
import ani.rss.entity.Config;
import ani.rss.service.AniService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.BgmUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.thread.ThreadUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用于更新BGM评分
 */
@Slf4j
@Component
public class BgmTask implements BaseTask {

    /**
     * BGM API 请求间隔，降低限流风险
     */
    private static final long REQUEST_INTERVAL_MS = 300L;

    @Resource
    private AniService aniService;

    @Override
    public void accept(AtomicBoolean loop) {
        try {
            BgmUtil.refreshToken();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        boolean changed = false;
        List<Ani> aniList = AniUtil.getAniList();
        for (Ani ani : aniList) {
            if (!loop.get()) {
                return;
            }
            Boolean enable = ani.getEnable();
            if (!enable) {
                continue;
            }
            BgmInfo bgmInfo;
            try {
                bgmInfo = BgmUtil.getBgmInfo(ani);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                ThreadUtil.sleep(REQUEST_INTERVAL_MS);
                continue;
            }

            double score = Optional.ofNullable(bgmInfo.getRating())
                    .map(BgmInfo.Rating::getScore)
                    .orElse(0.0);
            Double oldScore = ani.getScore();
            if (!Objects.equals(oldScore, score)) {
                ani.setScore(score);
                changed = true;
            }

            Config config = ConfigUtil.CONFIG;
            Boolean updateTotalEpisodeNumber = config.getUpdateTotalEpisodeNumber();
            Boolean forceUpdateTotalEpisodeNumber = config.getForceUpdateTotalEpisodeNumber();

            if (Boolean.TRUE.equals(updateTotalEpisodeNumber)) {
                Boolean updated = aniService.updateTotalEpisodeNumber(ani, bgmInfo, forceUpdateTotalEpisodeNumber);
                if (Boolean.TRUE.equals(updated)) {
                    changed = true;
                }
            }

            // 请求限流：避免订阅多时打爆 BGM API
            ThreadUtil.sleep(REQUEST_INTERVAL_MS);
        }

        if (changed) {
            AniUtil.sync();
        } else {
            log.debug("BGM 评分/总集数无变化，跳过同步");
        }

        ThreadUtil.sleep(12, TimeUnit.HOURS);
    }
}
