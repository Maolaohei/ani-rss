package ani.rss.task;

import ani.rss.commons.ExceptionUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.TorrentsInfo;
import ani.rss.service.AniLocks;
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
                    // F6-1：改名会写回本地状态（文件名变化 → 集数索引变化），属写路径。
                    // 按订阅维度取写锁：同一订阅的「下载 / 改名 / 删除」必须串行，
                    // 不同订阅互不阻塞。解析不到订阅时锁键退化为 "unknown"，
                    // 等价于让这类异常任务全局串行——仍比并发改同一份状态安全。
                    Ani ani = downloadService.findAniByDownloadPath(torrentsInfo).orElse(null);
                    AniLocks.runWithWrite(ani, () -> {
                        TorrentUtil.rename(torrentsInfo);
                        // 字幕不再于下载完成后自动抓取：统一由「字幕匹配」工具手动选择
                        // 「上传本地字幕」或「获取射手网字幕」，写入前二次确认，避免自动匹配到错误字幕。
                        downloadService.notification(torrentsInfo);
                        if (!Boolean.TRUE.equals(deleteStandbyRSSOnly)) {
                            TorrentUtil.delete(torrentsInfo);
                        }
                    });
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
