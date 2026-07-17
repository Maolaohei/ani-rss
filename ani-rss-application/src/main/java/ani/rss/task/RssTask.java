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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
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
    /** 兜底上限：若配置读取失败时使用 */
    private static final long FALLBACK_MAX_DOWNLOAD_DURATION_MS = TimeUnit.MINUTES.toMillis(90);
    /** 在离线超时之上留一点收尾缓冲（分钟） */
    private static final long DOWNLOAD_LOCK_BUFFER_MINUTES = 10L;
    /**
     * 订阅间并行度：同一订阅由 DownloadService 按 id 串行，这里限制整体并发
     */
    private static final int ANI_PARALLELISM = 3;

    /**
     * 获取全局 RSS 任务锁（别名，兼容上游 API 命名）
     */
    public static void syncLock() {
        sync();
    }

    /**
     * 在已持有全局任务锁的前提下，刷新全部启用订阅
     */
    public static void syncDownload() {
        syncDownload(null);
    }

    /**
     * 在已持有全局任务锁的前提下执行下载。
     * aniList 为 null 时刷全部启用订阅；非空时只刷指定订阅。
     */
    public static void syncDownload(List<Ani> aniList) {
        download(new AtomicBoolean(true), aniList);
    }

    public static void download(AtomicBoolean loop) {
        download(loop, null);
    }

    public static void download(AtomicBoolean loop, List<Ani> targetList) {
        DownloadService downloadService = SpringUtil.getBean(DownloadService.class);

        ExecutorService pool = null;
        try {
            if (!TorrentUtil.login()) {
                return;
            }

            List<Ani> candidates = targetList == null
                    ? new ArrayList<>(AniUtil.getAniList())
                    : new ArrayList<>(targetList);

            List<Ani> enabled = new ArrayList<>();
            for (Ani ani : candidates) {
                if (!loop.get()) {
                    return;
                }
                if (ani == null) {
                    continue;
                }
                String aniId = ani.getId();
                boolean stillExists = AniUtil.getAniList().stream()
                        .anyMatch(it -> Objects.equals(it.getId(), aniId));
                if (!stillExists) {
                    continue;
                }
                if (!Boolean.TRUE.equals(ani.getEnable())) {
                    log.debug("{} 未启用", ani.getTitle());
                    continue;
                }
                enabled.add(ani);
            }

            if (enabled.isEmpty()) {
                return;
            }

            int poolSize = Math.min(ANI_PARALLELISM, enabled.size());
            pool = Executors.newFixedThreadPool(poolSize);
            List<Future<?>> futures = new ArrayList<>(enabled.size());

            for (Ani ani : enabled) {
                if (!loop.get()) {
                    break;
                }
                futures.add(pool.submit(() -> {
                    if (!loop.get()) {
                        return;
                    }
                    // 提交时再确认一次订阅仍存在
                    String aniId = ani.getId();
                    boolean stillExists = AniUtil.getAniList().stream()
                            .anyMatch(it -> Objects.equals(it.getId(), aniId));
                    if (!stillExists) {
                        return;
                    }
                    String title = ani.getTitle();
                    try {
                        downloadService.downloadAni(ani);
                    } catch (Exception e) {
                        String message = ExceptionUtils.getMessage(e);
                        log.error("{} {}", title, message);
                        log.error(message, e);
                    }
                }));
                // 轻度错峰，避免同时打满 RSS/下载器
                ThreadUtil.sleep(50);
            }

            for (Future<?> future : futures) {
                if (!loop.get()) {
                    break;
                }
                try {
                    future.get();
                } catch (Exception e) {
                    log.error(ExceptionUtils.getMessage(e), e);
                }
            }
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
        } finally {
            if (pool != null) {
                pool.shutdownNow();
            }
            download.set(false);
            downloadStartTime.set(0);
        }
    }

    /**
     * 全局下载锁允许的最长持有时间。
     * 对齐 OpenList【离线超时】，避免默认 60 分钟离线等待被 30 分钟锁误判为残留并卡住。
     */
    static long resolveMaxDownloadDurationMs(Config config) {
        long minutes = 60L;
        try {
            if (config != null && config.getAlistDownloadTimeout() != null) {
                minutes = Math.max(1L, config.getAlistDownloadTimeout().longValue());
            }
        } catch (Exception ignored) {
            minutes = 60L;
        }
        long ms = TimeUnit.MINUTES.toMillis(minutes + DOWNLOAD_LOCK_BUFFER_MINUTES);
        return Math.max(ms, FALLBACK_MAX_DOWNLOAD_DURATION_MS);
    }

    public static void sync() {
        long maxDurationMs = resolveMaxDownloadDurationMs(ConfigUtil.CONFIG);
        // 如果标记为正在下载，检查是否已超时（可能是上次崩溃残留）
        if (download.get()) {
            long elapsed = System.currentTimeMillis() - downloadStartTime.get();
            if (elapsed > maxDurationMs) {
                log.warn("检测到残留任务标记（已运行 {} 分钟，上限 {} 分钟），自动重置",
                        elapsed / 60000, maxDurationMs / 60000);
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
            syncLock();
            syncDownload();
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
        }
        ThreadUtil.sleep(sleep, TimeUnit.MINUTES);
    }
}