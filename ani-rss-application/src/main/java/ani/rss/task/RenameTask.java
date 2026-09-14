package ani.rss.task;

import ani.rss.commons.ExceptionUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.TorrentsInfo;
import ani.rss.service.DownloadService;
import ani.rss.service.SubtitleService;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 重命名
 */
@Slf4j
@Component
public class RenameTask implements BaseTask {
    @Resource
    private DownloadService downloadService;

    @Resource
    private SubtitleService subtitleService;

    @Override
    public void accept(AtomicBoolean loop) {
        Config config = ConfigUtil.CONFIG;
        // 防御配置缺字段时的拆箱 NPE
        int renameSleepSeconds = ObjectUtil.defaultIfNull(config.getRenameSleepSeconds(), 10);

        if (!TorrentUtil.login()) {
            ThreadUtil.sleep(renameSleepSeconds * 1000L);
            return;
        }
        try {
            List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();
            for (TorrentsInfo torrentsInfo : torrentsInfos) {
                if (!loop.get()) {
                    return;
                }
                Boolean deleteStandbyRSSOnly = config.getDeleteStandbyRSSOnly();
                try {
                    TorrentUtil.rename(torrentsInfo);
                    // 下载改名完成后、OpenList 上传前：自动匹配并补全字幕（ASSRT）。
                    // 本地模型就地写盘随上传一并上云；OpenList 离线模型直接写入云端视频同目录。
                    try {
                        Optional<Ani> aniOpt = downloadService.findAniByDownloadPath(torrentsInfo);
                        if (aniOpt.isPresent()) {
                            subtitleService.fetchAndAttach(aniOpt.get(), torrentsInfo);
                        }
                    } catch (Exception e) {
                        log.warn("字幕自动获取失败 {}: {}", torrentsInfo.getName(), ExceptionUtils.getMessage(e));
                    }
                    downloadService.notification(torrentsInfo);
                    if (Boolean.TRUE.equals(deleteStandbyRSSOnly)) {
                        continue;
                    }
                    TorrentUtil.delete(torrentsInfo);
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
        }
        ThreadUtil.sleep(renameSleepSeconds * 1000L);
    }
}
