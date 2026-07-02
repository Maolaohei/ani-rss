package ani.rss.task;

import ani.rss.commons.ExceptionUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.service.DownloadService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.extra.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RSS
 */
@Slf4j
@Component
public class RssTask implements BaseTask {
    public static final AtomicBoolean download = new AtomicBoolean(false);
    private static final AtomicLong downloadStartTime = new AtomicLong(0);
    private static final long MAX_DOWNLOAD_DURATION_MS = TimeUnit.MINUTES.toMillis(30);

    public static void download(AtomicBoolean loop) {
        DownloadService downloadService = SpringUtil.getBean(DownloadService.class);

        try {
            if (!TorrentUtil.login()) {
                return;
            }
            for (Ani ani : AniUtil.ANI_LIST) {
                if (!loop.get()) {
                    return;
                }

                if (!AniUtil.ANI_LIST.contains(ani)) {
                    continue;
                }

                String title = ani.getTitle();
                if (!Boolean.TRUE.equals(ani.getEnable())) {
                    log.debug("{} 未启用", title);
                    continue;
                }
                try {
                    downloadService.downloadAni(ani);
                } catch (Exception e) {
                    String message = ExceptionUtils.getMessage(e);
                    log.error("{} {}", title, message);
                    log.error(message, e);
                }
                // 避免短时间频繁请求导致流控
                ThreadUtil.sleep(500);
            }
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
        } finally {
            download.set(false);
            downloadStartTime.set(0);
        }
    }

    public static void sync() {
        // 如果标记为正在下载，检查是否已超时（可能是上次崩溃残留）
        if (download.get()) {
            long elapsed = System.currentTimeMillis() - downloadStartTime.get();
            if (elapsed > MAX_DOWNLOAD_DURATION_MS) {
                log.warn("检测到残留任务标记（已运行 {} 分钟），自动重置", elapsed / 60000);
                download.set(false);
                downloadStartTime.set(0);
            } else {
                throw new IllegalStateException("存在未完成任务，请等待...");
            }
        }
        // CAS 确保线程安全：从 false 改为 true，失败则说明有并发竞争
        if (!download.compareAndSet(false, true)) {
            throw new IllegalStateException("存在未完成任务，请等待...");
        }
        // CAS 成功后立即记录开始时间，避免其他线程误判超时
        downloadStartTime.set(System.currentTimeMillis());
    }

    @Override
    public void accept(AtomicBoolean loop) {
        Config config = ConfigUtil.CONFIG;
        Integer sleep = config.getRssSleepMinutes();

        if (!config.getRss()) {
            log.debug("rss未启用");
            ThreadUtil.sleep(sleep, TimeUnit.MINUTES);
            return;
        }

        try {
            sync();
            download(loop);
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
        }
        ThreadUtil.sleep(sleep, TimeUnit.MINUTES);
    }
}
