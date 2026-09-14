package ani.rss.task;

import ani.rss.commons.ExceptionUtils;
import ani.rss.entity.Config;
import ani.rss.entity.TorrentsInfo;
import ani.rss.service.DownloadService;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 重命名
 */
@Slf4j
@Component
public class RenameTask implements BaseTask {
    @Resource
    private DownloadService downloadService;

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
                    // 字幕不再于下载完成后自动抓取：统一由「字幕匹配」工具手动选择
                    // 「上传本地字幕」或「获取射手网字幕」，写入前二次确认，避免自动匹配到错误字幕。
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
