package ani.rss.task;

import ani.rss.commons.ExceptionUtils;
import ani.rss.download.OpenList;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.vo.RssJobItem;
import ani.rss.entity.vo.RssJobStatus;
import ani.rss.service.DownloadService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.FailedDownloadQueue;
import ani.rss.util.other.TaskFailureHumanizer;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RSS
 */
@Slf4j
@Component
public class RssTask implements BaseTask {
    /**
     * ===== RSS 任务状态机（显式状态对象）=====
     * 生命周期/当前轮/上一轮状态与轮次生命周期方法收拢于此。
     * 引用方式：RssJobState.xxx；状态变更必须经其方法或直接操作对应 Atomic 字段。
     */
    static final class RssJobState {
        // ---- 生命周期状态（跨轮次）----
        /** 全局下载任务锁 */
        static final AtomicBoolean download = new AtomicBoolean(false);
        static final AtomicLong downloadStartTime = new AtomicLong(0);
        /** 轮次世代号（取消/抢占判定） */
        static final AtomicLong generationSequence = new AtomicLong(0);
        static final AtomicLong activeGeneration = new AtomicLong(0);
        /** 生命周期互斥 */
        static final Object LIFECYCLE_LOCK = new Object();

        // ---- 当前轮状态（resetRoundState 初始化 / clearRoundState 清理）----
        static final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        static final AtomicReference<CancelReason> cancelReason = new AtomicReference<>(CancelReason.NONE);
        static final AtomicReference<String> jobScope = new AtomicReference<>("idle");
        static final AtomicReference<String> jobTitle = new AtomicReference<>("");
        static final AtomicReference<String> jobAniId = new AtomicReference<>("");
        static final AtomicReference<String> jobMessage = new AtomicReference<>("空闲");
        static final AtomicReference<JobSource> jobSource = new AtomicReference<>(null);
        static final AtomicReference<ExecutorService> activePool = new AtomicReference<>();
        static final AtomicReference<Thread> activeRunner = new AtomicReference<>();
        static final AtomicReference<PendingManual> pendingManual = new AtomicReference<>(null);
        static final AtomicInteger subscriptionTotal = new AtomicInteger(0);
        static final AtomicInteger subscriptionActive = new AtomicInteger(0);
        static final AtomicInteger subscriptionCompleted = new AtomicInteger(0);
        static final AtomicInteger subscriptionFailed = new AtomicInteger(0);

        // ---- 上一轮状态（recordRoundFinished 写入，任务管理器"最近一次"展示）----
        static final AtomicLong lastFinishedAt = new AtomicLong(0);
        static final AtomicLong lastDurationMs = new AtomicLong(0);
        static final AtomicReference<String> lastResultMessage = new AtomicReference<>("");
        static final AtomicReference<String> lastTitle = new AtomicReference<>("");
        static final AtomicReference<String> lastSource = new AtomicReference<>(null);
        static final AtomicReference<String> lastScope = new AtomicReference<>(null);

        // ---- 轮次生命周期 ----

        /**
         * 记录上一轮完成信息（任务管理器"最近一次"展示）
         */
        static void recordRoundFinished(long finishedAt, long duration, String title, String scope,
                                        JobSource source, String message) {
            RssJobState.lastFinishedAt.set(finishedAt);
            RssJobState.lastDurationMs.set(duration);
            RssJobState.lastTitle.set(title);
            RssJobState.lastScope.set(scope);
            RssJobState.lastSource.set(source == null ? null : source.name().toLowerCase());
            RssJobState.lastResultMessage.set(message);
        }

        /**
         * 初始化当前轮状态（acquireLock 持锁后调用）
         */
        static void resetRoundState(JobSource source, String message) {
            long generation = RssJobState.generationSequence.incrementAndGet();
            RssJobState.activeGeneration.set(generation);
            RssJobState.downloadStartTime.set(System.currentTimeMillis());
            RssJobState.cancelRequested.set(false);
            RssJobState.cancelReason.set(CancelReason.NONE);
            RssJobState.activeRunner.set(null);
            RssJobState.activePool.set(null);
            RssJobState.subscriptionTotal.set(0);
            RssJobState.subscriptionActive.set(0);
            RssJobState.subscriptionCompleted.set(0);
            RssJobState.subscriptionFailed.set(0);
            RssJobState.jobScope.set("starting");
            RssJobState.jobTitle.set("");
            RssJobState.jobAniId.set("");
            RssJobState.jobSource.set(source == null ? JobSource.MANUAL : source);
            RssJobState.jobMessage.set(message == null ? "任务启动中..." : message);
        }

        /**
         * 清理当前轮状态（finishGeneration 持锁后调用），回到空闲
         */
        static void clearRoundState(String idleMessage) {
            RssJobState.jobScope.set("idle");
            RssJobState.jobTitle.set("");
            RssJobState.jobAniId.set("");
            RssJobState.jobSource.set(null);
            RssJobState.downloadStartTime.set(0);
            RssJobState.activeGeneration.set(0);
            RssJobState.activeRunner.compareAndSet(Thread.currentThread(), null);
            RssJobState.download.set(false);
            RssJobState.cancelRequested.set(false);
            RssJobState.cancelReason.set(CancelReason.NONE);
            RssJobState.subscriptionTotal.set(0);
            RssJobState.subscriptionActive.set(0);
            RssJobState.subscriptionCompleted.set(0);
            RssJobState.subscriptionFailed.set(0);
            RssJobState.jobMessage.set(idleMessage);
        }

        private RssJobState() {
        }
    }

    /** 兜底上限：若配置读取失败时使用 */
    private static final long FALLBACK_MAX_DOWNLOAD_DURATION_MS = TimeUnit.MINUTES.toMillis(90);
    /** 在离线超时之上留一点收尾缓冲（分钟） */
    private static final long DOWNLOAD_LOCK_BUFFER_MINUTES = 10L;
    /**
     * 订阅间并行度：同一订阅由 DownloadService 按 id 串行，这里限制整体并发
     */
    private static final int ANI_PARALLELISM = 3;

    /** 任务来源：周期扫描 / 手动刷新 */
    public enum JobSource {
        PERIODIC,
        MANUAL
    }

    private enum CancelReason {
        NONE,
        USER,
        PREEMPT
    }

    /** 最多 1 个待执行的手动刷新（后提交的替换先前的） */
    static final class PendingManual {
        final List<Ani> targetList; // null = 全部
        final String title;
        final String scope;

        PendingManual(List<Ani> targetList, String title, String scope) {
            this.targetList = targetList;
            this.title = title;
            this.scope = scope;
        }
    }

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
        download(new AtomicBoolean(true), aniList, RssJobState.activeGeneration.get());
    }

    public static void download(AtomicBoolean loop) {
        download(loop, null);
    }

    public static void download(AtomicBoolean loop, List<Ani> targetList) {
        download(loop, targetList, RssJobState.activeGeneration.get());
    }

    private static void download(AtomicBoolean loop, List<Ani> targetList, long generation) {
        if (generation <= 0 || generation != RssJobState.activeGeneration.get() || !RssJobState.download.get()) {
            log.debug("忽略已失效的 RSS 执行 generation={} activeGeneration={}", generation, RssJobState.activeGeneration.get());
            return;
        }
        Thread runner = Thread.currentThread();
        if (!RssJobState.activeRunner.compareAndSet(null, runner)) {
            log.warn("RSS 执行线程重复进入 generation={}", generation);
            return;
        }
        DownloadService downloadService = SpringUtil.getBean(DownloadService.class);

        ExecutorService pool = null;
        try {
            if (!TorrentUtil.login()) {
                RssJobState.jobMessage.set("下载器登录失败");
                return;
            }

            List<Ani> candidates = targetList == null
                    ? new ArrayList<>(AniUtil.getAniList())
                    : new ArrayList<>(targetList);

            if (targetList == null) {
                RssJobState.jobScope.set("all");
                RssJobState.jobTitle.set("全部启用订阅");
                RssJobState.jobAniId.set("");
            } else if (candidates.size() == 1 && candidates.get(0) != null) {
                RssJobState.jobScope.set("single");
                RssJobState.jobTitle.set(StrUtil.blankToDefault(candidates.get(0).getTitle(), ""));
                RssJobState.jobAniId.set(StrUtil.blankToDefault(candidates.get(0).getId(), ""));
            } else {
                RssJobState.jobScope.set("partial");
                RssJobState.jobTitle.set("部分订阅 (" + candidates.size() + ")");
                RssJobState.jobAniId.set("");
            }
            RssJobState.jobMessage.set("扫描订阅中...");

            List<Ani> enabled = new ArrayList<>();
            for (Ani ani : candidates) {
                if (!isActive(loop)) {
                    RssJobState.jobMessage.set("已取消");
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
                RssJobState.jobMessage.set("无可下载订阅");
                return;
            }

            RssJobState.subscriptionTotal.set(enabled.size());
            RssJobState.subscriptionActive.set(0);
            RssJobState.subscriptionCompleted.set(0);
            RssJobState.subscriptionFailed.set(0);
            updateProgressMessage(generation);

            int poolSize = Math.min(ANI_PARALLELISM, enabled.size());
            pool = Executors.newFixedThreadPool(poolSize);
            RssJobState.activePool.set(pool);
            List<Future<?>> futures = new ArrayList<>(enabled.size());

            for (Ani ani : enabled) {
                if (!isActive(loop)) {
                    RssJobState.jobMessage.set("已取消");
                    break;
                }
                futures.add(pool.submit(() -> {
                    if (!isActive(loop)) {
                        return;
                    }
                    // 提交时再确认一次订阅仍存在
                    String aniId = ani.getId();
                    boolean stillExists = AniUtil.getAniList().stream()
                            .anyMatch(it -> Objects.equals(it.getId(), aniId));
                    if (!stillExists) {
                        RssJobState.subscriptionCompleted.incrementAndGet();
                        updateProgressMessage(generation);
                        return;
                    }
                    String title = ani.getTitle();
                    RssJobState.subscriptionActive.incrementAndGet();
                    updateProgressMessage(generation);
                    try {
                        downloadService.downloadAni(ani);
                    } catch (Exception e) {
                        RssJobState.subscriptionFailed.incrementAndGet();
                        String message = ExceptionUtils.getMessage(e);
                        log.error("{} {}", title, message);
                        log.error(message, e);
                    } finally {
                        RssJobState.subscriptionActive.decrementAndGet();
                        RssJobState.subscriptionCompleted.incrementAndGet();
                        updateProgressMessage(generation);
                    }
                }));
                // 轻抖动错峰，避免同时打爆 RSS/下载器
                ThreadUtil.sleep(50);
            }

            for (Future<?> future : futures) {
                if (!isActive(loop)) {
                    RssJobState.jobMessage.set("取消中...");
                    break;
                }
                try {
                    future.get();
                } catch (CancellationException e) {
                    if (!RssJobState.cancelRequested.get()) {
                        log.warn("RSS 子任务被取消: {}", e.getMessage());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    RssJobState.cancelRequested.set(true);
                    RssJobState.cancelReason.compareAndSet(CancelReason.NONE, CancelReason.USER);
                    break;
                } catch (ExecutionException e) {
                    RssJobState.subscriptionFailed.incrementAndGet();
                    log.error(ExceptionUtils.getMessage(e), e);
                }
            }
            if (RssJobState.cancelRequested.get()) {
                RssJobState.jobMessage.set(RssJobState.cancelReason.get() == CancelReason.PREEMPT ? "已为手动刷新让路" : "已取消");
            } else {
                int failed = RssJobState.subscriptionFailed.get();
                int completed = RssJobState.subscriptionCompleted.get();
                int total = RssJobState.subscriptionTotal.get();
                RssJobState.jobMessage.set(failed > 0
                        ? ("部分失败: " + failed + "/" + total + "，已处理 " + completed + "/" + total)
                        : ("已完成: " + completed + "/" + total));
            }
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
            RssJobState.jobMessage.set("异常: " + message);
        } finally {
            shutdownAndAwaitPool(pool, generation);
            RssJobState.activePool.compareAndSet(pool, null);

            PendingManual next = finishGeneration(generation);
            if (next != null) {
                startManualAsync(next.targetList, "执行排队的手动刷新");
            }
        }
    }

    /**
     * 手动刷新入口：空闲直接跑；周期任务让路；手动任务最多排队 1 个（后写覆盖）。
     *
     * @param aniList null=全部启用订阅
     * @return 给前端的提示文案
     */
    public static String submitManualRefresh(List<Ani> aniList) {
        PendingManual job = buildPending(aniList);

        // 快速路径：空闲则抢锁启动
        if (!RssJobState.download.get()) {
            try {
                acquireLock(JobSource.MANUAL, "手动刷新启动中...");
                startDownloadAsync(aniList);
                return "已开始刷新RSS";
            } catch (IllegalStateException race) {
                // 并发抢锁失败，走下方排队/抢先逻辑
            }
        }

        JobSource source = RssJobState.jobSource.get();
        if (!RssJobState.download.get()) {
            // 双检：刚才还在跑，现在已空
            try {
                acquireLock(JobSource.MANUAL, "手动刷新启动中...");
                startDownloadAsync(aniList);
                return "已开始刷新RSS";
            } catch (IllegalStateException ignored) {
                // 继续
            }
        }

        if (RssJobState.download.get() && source == JobSource.PERIODIC) {
            // 抢先：请求取消周期任务，并挂上待执行手动刷新
            PendingManual prev = RssJobState.pendingManual.getAndSet(job);
            RssJobState.cancelReason.set(CancelReason.PREEMPT);
            RssJobState.cancelRequested.set(true);
            RssJobState.jobMessage.set("手动刷新抢先中，等待周期任务退出...");
            log.warn("手动刷新抢先周期任务: {}", job.title);
            ExecutorService pool = RssJobState.activePool.get();
            if (pool != null) {
                pool.shutdownNow();
            }
            // 兼容：OpenList/Alist 才清理远端离线任务；其它工具只停 RSS 推进
            cleanupDownloadToolOnCancel("手动抢先");
            // 不强制立刻 release：等 download finally 释放后 drain pending
            if (prev != null) {
                return "已请求周期任务让路，并将替换先前的待执行刷新";
            }
            return "已请求周期任务让路，手动刷新将随后执行";
        }

        if (RssJobState.download.get() && source == JobSource.MANUAL) {
            PendingManual prev = RssJobState.pendingManual.getAndSet(job);
            RssJobState.jobMessage.set("已排队待执行: " + job.title);
            if (prev != null) {
                return "当前已有手动刷新，新的请求已替换待执行队列";
            }
            return "当前手动刷新进行中，新的请求已排队（最多 1 个）";
        }

        // 未知来源或锁异常：尽量排队，避免硬抛导致前端无路可走
        if (RssJobState.download.get()) {
            RssJobState.pendingManual.set(job);
            RssJobState.cancelReason.set(CancelReason.PREEMPT);
            RssJobState.cancelRequested.set(true);
            cleanupDownloadToolOnCancel("未知来源抢先");
            return "存在运行中任务，已请求让路并排队手动刷新";
        }

        try {
            acquireLock(JobSource.MANUAL, "手动刷新启动中...");
            startDownloadAsync(aniList);
            return "已开始刷新RSS";
        } catch (IllegalStateException e) {
            RssJobState.pendingManual.set(job);
            return "任务繁忙，已排队待执行";
        }
    }

    /**
     * 周期任务入口：若已有任务在跑则跳过本轮（不抢手动刷新）。
     *
     * @return true 表示已拿到锁并应执行 syncDownload
     */
    public static boolean tryStartPeriodic() {
        if (RssJobState.download.get()) {
            log.debug("周期 RSS 跳过：已有任务 source={} msg={}", RssJobState.jobSource.get(), RssJobState.jobMessage.get());
            return false;
        }
        try {
            acquireLock(JobSource.PERIODIC, "周期扫描启动中...");
            return true;
        } catch (IllegalStateException e) {
            log.debug("周期 RSS 跳过：{}", e.getMessage());
            return false;
        }
    }

    private static void updateProgressMessage(long generation) {
        if (generation != RssJobState.activeGeneration.get() || RssJobState.cancelRequested.get()) {
            return;
        }
        int total = RssJobState.subscriptionTotal.get();
        int completed = RssJobState.subscriptionCompleted.get();
        int active = RssJobState.subscriptionActive.get();
        int failed = RssJobState.subscriptionFailed.get();
        RssJobState.jobMessage.set("订阅进度 " + completed + "/" + total
                + "，运行中 " + active
                + (failed > 0 ? "，失败 " + failed : ""));
    }

    private static void shutdownAndAwaitPool(ExecutorService pool, long generation) {
        if (pool == null) {
            return;
        }
        pool.shutdownNow();
        boolean interrupted = false;
        while (!pool.isTerminated()) {
            try {
                if (!pool.awaitTermination(1, TimeUnit.SECONDS)
                        && generation == RssJobState.activeGeneration.get()
                        && RssJobState.cancelRequested.get()) {
                    RssJobState.jobMessage.set(RssJobState.cancelReason.get() == CancelReason.PREEMPT
                            ? "手动刷新抢先中，等待当前订阅退出..."
                            : "取消中，等待当前订阅退出...");
                }
            } catch (InterruptedException e) {
                interrupted = true;
                pool.shutdownNow();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static PendingManual finishGeneration(long generation) {
        synchronized (RssJobState.LIFECYCLE_LOCK) {
            if (generation != RssJobState.activeGeneration.get()) {
                log.debug("忽略旧 RSS 任务收尾 generation={} activeGeneration={}", generation, RssJobState.activeGeneration.get());
                return null;
            }

            long startedAt = RssJobState.downloadStartTime.get();
            long finishedAt = System.currentTimeMillis();
            long duration = startedAt > 0 ? Math.max(0L, finishedAt - startedAt) : 0L;
            RssJobState.recordRoundFinished(
                    finishedAt,
                    duration,
                    StrUtil.blankToDefault(RssJobState.jobTitle.get(), ""),
                    StrUtil.blankToDefault(RssJobState.jobScope.get(), "idle"),
                    RssJobState.jobSource.get(),
                    StrUtil.blankToDefault(RssJobState.jobMessage.get(), "已完成"));

            CancelReason reason = RssJobState.cancelReason.get();
            PendingManual next = null;
            if (reason == CancelReason.USER) {
                RssJobState.pendingManual.set(null);
            } else {
                next = RssJobState.pendingManual.getAndSet(null);
            }

            RssJobState.clearRoundState(next == null ? "空闲" : "准备执行排队任务...");
            return next;
        }
    }

    private static PendingManual buildPending(List<Ani> aniList) {
        if (aniList == null) {
            return new PendingManual(null, "全部启用订阅", "all");
        }
        List<Ani> copy = new ArrayList<>(aniList);
        if (copy.size() == 1 && copy.get(0) != null) {
            Ani one = copy.get(0);
            return new PendingManual(copy,
                    StrUtil.blankToDefault(one.getTitle(), "单个订阅"),
                    "single");
        }
        return new PendingManual(copy, "部分订阅 (" + copy.size() + ")", "partial");
    }

    private static void startManualAsync(List<Ani> aniList, String bootMessage) {
        try {
            acquireLock(JobSource.MANUAL, bootMessage);
            startDownloadAsync(aniList);
        } catch (Exception e) {
            log.error("启动排队手动刷新失败: {}", ExceptionUtils.getMessage(e), e);
            // 放回队列，避免丢失（仅当仍空闲失败时）
            if (!RssJobState.download.get()) {
                RssJobState.pendingManual.compareAndSet(null, buildPending(aniList));
            }
        }
    }

    private static void startDownloadAsync(List<Ani> aniList) {
        long generation = RssJobState.activeGeneration.get();
        ThreadUtil.execute(() -> {
            try {
                download(new AtomicBoolean(true), aniList, generation);
            } catch (Exception e) {
                String message = ExceptionUtils.getMessage(e);
                log.error(message, e);
                if (generation == RssJobState.activeGeneration.get()) {
                    RssJobState.jobMessage.set("异常: " + message);
                }
            }
        });
    }

    /**
     * 全局下载锁允许的最长持有时间。
     * 对齐 OpenList「离线超时」，避免默认 60 分钟离线等待被 30 分钟锁误判为残留并卡住。
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
        acquireLock(null, "任务启动中...");
    }

    /**
     * 获取全局锁。source 可为 null（兼容旧 syncLock 调用，视为手动侧入口）。
     */
    private static void acquireLock(JobSource source, String message) {
        synchronized (RssJobState.LIFECYCLE_LOCK) {
            long maxDurationMs = resolveMaxDownloadDurationMs(ConfigUtil.CONFIG);
            if (RssJobState.download.get()) {
                long elapsed = System.currentTimeMillis() - RssJobState.downloadStartTime.get();
                if (elapsed > maxDurationMs) {
                    log.warn("检测到超时任务（已运行 {} 分钟，上限 {} 分钟），尝试安全恢复",
                            elapsed / 60000, maxDurationMs / 60000);
                    if (!forceReleaseLock("残留任务超时安全恢复")) {
                        throw new IllegalStateException("任务已超时但执行线程仍在退出，请等待...");
                    }
                } else {
                    throw new IllegalStateException("存在未完成任务，请等待...");
                }
            }
            if (!RssJobState.download.compareAndSet(false, true)) {
                throw new IllegalStateException("存在未完成任务，请等待...");
            }
            RssJobState.resetRoundState(source, message);
        }
    }

    /**
     * 任务管理器：当前全局 RSS 任务快照
     */
    public static RssJobStatus getJobStatus() {
        boolean running;
        boolean canceling;
        long startedAt;
        String scope;
        String title;
        String aniId;
        String message;
        PendingManual pending;
        JobSource source;
        int total;
        int active;
        int completed;
        int failed;
        long finishedAt;
        long lastDuration;
        String lastMsg;
        String lastJobTitle;
        String lastJobSource;
        String lastJobScope;
        synchronized (RssJobState.LIFECYCLE_LOCK) {
            running = RssJobState.download.get();
            canceling = RssJobState.cancelRequested.get();
            startedAt = RssJobState.downloadStartTime.get();
            scope = running ? RssJobState.jobScope.get() : "idle";
            title = RssJobState.jobTitle.get();
            aniId = RssJobState.jobAniId.get();
            pending = RssJobState.pendingManual.get();
            source = RssJobState.jobSource.get();
            message = running ? RssJobState.jobMessage.get() : (pending != null ? "空闲（有待执行）" : "空闲");
            total = RssJobState.subscriptionTotal.get();
            active = RssJobState.subscriptionActive.get();
            completed = RssJobState.subscriptionCompleted.get();
            failed = RssJobState.subscriptionFailed.get();
            finishedAt = RssJobState.lastFinishedAt.get();
            lastDuration = RssJobState.lastDurationMs.get();
            lastMsg = RssJobState.lastResultMessage.get();
            lastJobTitle = RssJobState.lastTitle.get();
            lastJobSource = RssJobState.lastSource.get();
            lastJobScope = RssJobState.lastScope.get();
        }

        long elapsed = (running && startedAt > 0) ? Math.max(0L, System.currentTimeMillis() - startedAt) : 0L;
        String currentHash = null;
        String offlineTitle = null;
        Integer offlineProgress = null;
        String offlineState = null;
        Long offlineDeadlineMs = null;
        Long offlineEtaMs = null;
        if (isOpenListTool()) {
            try {
                OpenList openList = SpringUtil.getBean(OpenList.class);
                currentHash = openList.getCurrentInfoHash();
                OpenList.OfflineWaitSnapshot wait = openList.getOfflineWaitSnapshot();
                if (wait != null) {
                    offlineTitle = wait.getTitle();
                    offlineProgress = wait.getProgress();
                    offlineState = wait.getState();
                    offlineDeadlineMs = wait.getDeadlineMs();
                    if (offlineDeadlineMs != null && offlineDeadlineMs > 0) {
                        offlineEtaMs = Math.max(0L, offlineDeadlineMs - System.currentTimeMillis());
                    }
                }
            } catch (Exception ignored) {
                // OpenList bean 暂不可用
            }
        }
        boolean openListBusy = StrUtil.isNotBlank(currentHash);
        // 兜底文案：旁路直接 downloadAni 时，RSS 锁空闲但 OpenList 仍在跑
        if (!running && openListBusy && pending == null) {
            message = "OpenList 离线处理中";
            if (offlineProgress != null) {
                message = "OpenList 离线处理中 " + offlineProgress + "%";
            }
        }
        int failedQueueCount = 0;
        try {
            failedQueueCount = FailedDownloadQueue.list().size();
        } catch (Exception ignored) {
        }
        boolean residualSupported = isOpenListTool();
        Integer residualActive = null;
        Integer residualTerminal = null;
        Integer residualTotal = null;
        Long residualScannedAt = null;
        Boolean residualCleaning = null;
        String residualMessage = null;
        java.util.List<String> residualSamples = null;
        java.util.List<ani.rss.entity.vo.ResidualPreviewItem> residualItems = null;
        Integer tempDirTotal = null;
        Integer tempDirCleanable = null;
        Integer tempDirProtected = null;
        Integer tempDirKeep = null;
        Long tempDirScannedAt = null;
        Boolean tempDirCleaning = null;
        String tempDirMessage = null;
        java.util.List<ani.rss.entity.vo.ResidualPreviewItem> tempDirItems = null;
        if (residualSupported) {
            try {
                OpenList openListBean = SpringUtil.getBean(OpenList.class);
                OpenList.ResidualSnapshot snap = openListBean.getResidualSnapshot();
                if (snap != null) {
                    residualActive = snap.getActiveCount();
                    residualTerminal = snap.getTerminalCount();
                    residualTotal = snap.getTotalCount();
                    residualScannedAt = snap.getScannedAt();
                    residualCleaning = snap.getCleaning();
                    residualMessage = snap.getMessage();
                    residualSamples = snap.getSamples();
                    residualItems = toResidualPreviewItems(snap.getItems());
                }
                OpenList.TempDirResidualSnapshot tempSnap = openListBean.getTempDirResidualSnapshot();
                if (tempSnap != null) {
                    tempDirTotal = tempSnap.getTotalCount();
                    tempDirCleanable = tempSnap.getCleanableCount();
                    tempDirProtected = tempSnap.getProtectedCount();
                    tempDirKeep = tempSnap.getKeepCount();
                    tempDirScannedAt = tempSnap.getScannedAt();
                    tempDirCleaning = tempSnap.getCleaning();
                    tempDirMessage = tempSnap.getMessage();
                    tempDirItems = toTempDirPreviewItems(tempSnap.getItems());
                }
            } catch (Exception ignored) {
                residualMessage = "OpenList 残留快照不可用";
            }
        }

        List<RssJobItem> tasks = buildTaskItems(
                running,
                canceling,
                scope,
                title,
                message,
                source,
                startedAt,
                elapsed,
                pending,
                openListBusy,
                currentHash,
                offlineTitle,
                offlineProgress,
                offlineState,
                offlineEtaMs,
                residualSupported,
                residualActive,
                residualTerminal,
                residualTotal,
                residualScannedAt,
                residualCleaning,
                residualMessage,
                residualSamples,
                tempDirTotal,
                tempDirCleanable,
                tempDirCleaning,
                tempDirMessage,
                finishedAt,
                lastDuration,
                lastMsg,
                lastJobTitle,
                lastJobSource,
                lastJobScope
        );
        boolean canCancel = tasks.stream().anyMatch(t -> Boolean.TRUE.equals(t.getCancellable()));

        return new RssJobStatus()
                .setRunning(running)
                .setCancelRequested(canceling)
                .setCanCancel(canCancel)
                .setScope(scope)
                .setTitle(title)
                .setAniId(aniId)
                .setStartedAt(startedAt > 0 ? startedAt : null)
                .setElapsedMs(elapsed)
                .setSubscriptionTotal(total)
                .setSubscriptionActive(active)
                .setSubscriptionCompleted(completed)
                .setSubscriptionFailed(failed)
                .setLastFinishedAt(finishedAt > 0 ? finishedAt : null)
                .setLastDurationMs(finishedAt > 0 ? lastDuration : null)
                .setLastResultMessage(StrUtil.blankToDefault(lastMsg, null))
                .setLastTitle(StrUtil.blankToDefault(lastJobTitle, null))
                .setLastSource(lastJobSource)
                .setLastScope(lastJobScope)
                .setMessage(message)
                .setCurrentHash(currentHash)
                .setOfflineTitle(offlineTitle)
                .setOfflineProgress(offlineProgress)
                .setOfflineState(offlineState)
                .setOfflineDeadlineMs(offlineDeadlineMs)
                .setOfflineEtaMs(offlineEtaMs)
                .setFailedQueueCount(failedQueueCount)
                .setOpenListBusy(openListBusy)
                .setSource(source == null ? null : source.name().toLowerCase())
                .setPending(pending != null)
                .setPendingTitle(pending == null ? null : pending.title)
                .setPendingScope(pending == null ? null : pending.scope)
                .setResidualSupported(residualSupported)
                .setResidualActiveCount(residualActive)
                .setResidualTerminalCount(residualTerminal)
                .setResidualTotalCount(residualTotal)
                .setResidualScannedAt(residualScannedAt)
                .setResidualCleaning(residualCleaning)
                .setResidualMessage(residualMessage)
                .setResidualSamples(residualSamples)
                .setResidualItems(residualItems)
                .setTempDirResidualTotalCount(tempDirTotal)
                .setTempDirResidualCleanableCount(tempDirCleanable)
                .setTempDirResidualProtectedCount(tempDirProtected)
                .setTempDirResidualKeepCount(tempDirKeep)
                .setTempDirResidualScannedAt(tempDirScannedAt)
                .setTempDirResidualCleaning(tempDirCleaning)
                .setTempDirResidualMessage(tempDirMessage)
                .setTempDirResidualItems(tempDirItems)
                .setTasks(tasks);
    }
    private static List<RssJobItem> buildTaskItems(
            boolean running,
            boolean canceling,
            String scope,
            String title,
            String message,
            JobSource source,
            long startedAt,
            long elapsed,
            PendingManual pending,
            boolean openListBusy,
            String currentHash,
            String offlineTitle,
            Integer offlineProgress,
            String offlineState,
            Long offlineEtaMs,
            boolean residualSupported,
            Integer residualActive,
            Integer residualTerminal,
            Integer residualTotal,
            Long residualScannedAt,
            Boolean residualCleaning,
            String residualMessage,
            List<String> residualSamples,
            Integer tempDirTotal,
            Integer tempDirCleanable,
            Boolean tempDirCleaning,
            String tempDirMessage,
            long finishedAt,
            long lastDuration,
            String lastMsg,
            String lastJobTitle,
            String lastJobSource,
            String lastJobScope
    ) {
        List<RssJobItem> tasks = new ArrayList<>();

        if (running) {
            tasks.add(new RssJobItem()
                    .setId("rss-running")
                    .setKind("rss_running")
                    .setStatus(canceling ? "canceling" : "running")
                    .setTitle(StrUtil.blankToDefault(title, scopeText(scope)))
                    .setMessage(StrUtil.blankToDefault(message, "处理中"))
                    .setSource(source == null ? null : source.name().toLowerCase())
                    .setScope(scope)
                    // 便于单条观察：RSS 运行中若正占用 OpenList，一并带上 hash
                    .setHash(openListBusy ? currentHash : null)
                    .setStartedAt(startedAt > 0 ? startedAt : null)
                    .setElapsedMs(elapsed)
                    .setProcessedAt(null)
                    .setDurationMs(null)
                    .setCancellable(true)
                    .setProgress(openListBusy ? offlineProgress : null)
                    .setEtaMs(openListBusy ? offlineEtaMs : null));
        }

        if (pending != null) {
            tasks.add(new RssJobItem()
                    .setId("rss-pending")
                    .setKind("rss_pending")
                    .setStatus("pending")
                    .setTitle(StrUtil.blankToDefault(pending.title, "待执行手动刷新"))
                    .setMessage("等待当前任务结束后执行")
                    .setSource("manual")
                    .setScope(pending.scope)
                    .setHash(null)
                    .setStartedAt(null)
                    .setElapsedMs(null)
                    .setProcessedAt(null)
                    .setDurationMs(null)
                    .setCancellable(true));
        }

        if (openListBusy) {
            StringBuilder offlineMsg = new StringBuilder();
            if (StrUtil.isNotBlank(offlineTitle)) {
                offlineMsg.append(offlineTitle);
            } else {
                offlineMsg.append(running ? "当前 RSS 关联的离线下载进行中" : "OpenList 离线处理中");
            }
            if (StrUtil.isNotBlank(offlineState)) {
                offlineMsg.append(" · ").append(offlineState);
            }
            if (offlineProgress != null) {
                offlineMsg.append(" · ").append(offlineProgress).append('%');
            }
            if (offlineEtaMs != null) {
                long min = Math.max(0L, offlineEtaMs / 60_000L);
                offlineMsg.append(" · 剩余约 ").append(min).append(" 分钟");
            }
            tasks.add(new RssJobItem()
                    .setId("openlist-current")
                    .setKind("openlist_current")
                    .setStatus("busy")
                    .setTitle(StrUtil.blankToDefault(offlineTitle, "OpenList 离线任务"))
                    .setMessage(offlineMsg.toString())
                    .setSource("openlist")
                    .setScope("offline")
                    .setHash(currentHash)
                    .setStartedAt(running && startedAt > 0 ? startedAt : null)
                    .setElapsedMs(running ? elapsed : null)
                    .setProcessedAt(null)
                    .setDurationMs(null)
                    .setCancellable(true)
                    .setProgress(offlineProgress)
                    .setEtaMs(offlineEtaMs));
        }

        if (residualSupported) {
            int active = residualActive == null ? 0 : residualActive;
            int terminal = residualTerminal == null ? 0 : residualTerminal;
            int total = residualTotal == null ? (active + terminal) : residualTotal;
            boolean cleaning = Boolean.TRUE.equals(residualCleaning);
            // 空闲且无残留时不占任务列表；仅有残留/清理中/扫描异常才展示
            boolean hasResidualError = StrUtil.isNotBlank(residualMessage)
                    && !residualMessage.contains("无离线残留")
                    && total == 0
                    && !cleaning;
            if (total > 0 || cleaning || hasResidualError) {
                String sampleText = (residualSamples == null || residualSamples.isEmpty())
                        ? ""
                        : ("；样例: " + String.join(" | ", residualSamples));
                String residualMsg = StrUtil.blankToDefault(residualMessage,
                        "进行中 " + active + " / 终态 " + terminal);
                // 仅进行中/清理中视为 busy；纯终态残留不伪装成待执行，避免前端误高频轮询
                String residualStatus = cleaning || active > 0 ? "busy" : "idle";
                tasks.add(new RssJobItem()
                        .setId("residual-summary")
                        .setKind("residual")
                        .setStatus(residualStatus)
                        .setTitle("OpenList 离线残留")
                        .setMessage(residualMsg + sampleText)
                        .setSource("residual")
                        .setScope("residual")
                        .setHash(null)
                        .setStartedAt(null)
                        .setElapsedMs(null)
                        .setProcessedAt(residualScannedAt)
                        .setDurationMs(null)
                        .setCancellable(false));
            }

            int tdTotal = tempDirTotal == null ? 0 : tempDirTotal;
            int tdCleanable = tempDirCleanable == null ? 0 : tempDirCleanable;
            boolean tdCleaning = Boolean.TRUE.equals(tempDirCleaning);
            boolean hasTempDirError = StrUtil.isNotBlank(tempDirMessage)
                    && !tempDirMessage.contains("无临时目录残留")
                    && tdTotal == 0
                    && !tdCleaning;
            if (tdTotal > 0 || tdCleaning || hasTempDirError) {
                String tdMsg = StrUtil.blankToDefault(tempDirMessage,
                        "可清理 " + tdCleanable + " / 合计 " + tdTotal);
                tasks.add(new RssJobItem()
                        .setId("tempdir-residual-summary")
                        .setKind("tempdir_residual")
                        .setStatus(tdCleaning ? "busy" : "idle")
                        .setTitle("OpenList 临时目录残留")
                        .setMessage(tdMsg)
                        .setSource("residual")
                        .setScope("residual")
                        .setHash(null)
                        .setStartedAt(null)
                        .setElapsedMs(null)
                        .setProcessedAt(null)
                        .setDurationMs(null)
                        .setCancellable(false));
            }
        }

        if (finishedAt > 0) {
            String rawLast = StrUtil.blankToDefault(lastMsg, "已完成");
            String lastDisplay = rawLast;
            // 仅对明确失败类文案做人话化，避免污染正常完成文案
            if (rawLast.contains("失败") || rawLast.contains("异常") || rawLast.contains("超时")
                    || rawLast.contains("坏种") || rawLast.contains("10008")) {
                var h = TaskFailureHumanizer.humanize(rawLast);
                lastDisplay = h.title() + " — " + h.suggestion();
            }
            tasks.add(new RssJobItem()
                    .setId("last-finished")
                    .setKind("last_finished")
                    .setStatus("done")
                    .setTitle(StrUtil.blankToDefault(lastJobTitle, "上一轮任务"))
                    .setMessage(lastDisplay)
                    .setSource(lastJobSource)
                    .setScope(lastJobScope)
                    .setHash(null)
                    .setStartedAt(lastDuration > 0 ? Math.max(0L, finishedAt - lastDuration) : null)
                    .setElapsedMs(null)
                    .setProcessedAt(finishedAt)
                    .setDurationMs(lastDuration)
                    .setCancellable(false));
        }

        return tasks;
    }

    private static String scopeText(String scope) {
        if ("all".equals(scope)) {
            return "全部启用订阅";
        }
        if ("single".equals(scope)) {
            return "单个订阅";
        }
        if ("partial".equals(scope)) {
            return "部分订阅";
        }
        if ("starting".equals(scope)) {
            return "启动中";
        }
        return "空闲";
    }

    private static List<ani.rss.entity.vo.ResidualPreviewItem> toResidualPreviewItems(List<OpenList.ResidualItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<ani.rss.entity.vo.ResidualPreviewItem> out = new ArrayList<>(items.size());
        for (OpenList.ResidualItem item : items) {
            if (item == null) {
                continue;
            }
            out.add(new ani.rss.entity.vo.ResidualPreviewItem()
                    .setId(item.getId())
                    .setName(item.getName())
                    .setState(item.getState())
                    .setKind(item.getKind())
                    .setProgress(item.getProgress())
                    .setTotalBytes(item.getTotalBytes())
                    .setError(item.getError())
                    .setProtectedCurrent(item.getProtectedCurrent())
                    .setAction(item.getAction()));
        }
        return out;
    }

    private static List<ani.rss.entity.vo.ResidualPreviewItem> toTempDirPreviewItems(List<OpenList.TempDirResidualItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<ani.rss.entity.vo.ResidualPreviewItem> out = new ArrayList<>(items.size());
        for (OpenList.TempDirResidualItem item : items) {
            if (item == null) {
                continue;
            }
            out.add(new ani.rss.entity.vo.ResidualPreviewItem()
                    .setId(item.getId())
                    .setName(item.getName())
                    .setState(item.getState())
                    .setKind(item.getKind())
                    .setProgress(null)
                    .setTotalBytes(null)
                    .setError(item.getError())
                    .setProtectedCurrent(item.getProtectedCurrent())
                    .setAction(item.getAction()));
        }
        return out;
    }

    /**
     * 请求取消当前 RSS 任务：停止后续订阅推进，并按下载工具做对应清理。
     * OpenList/Alist：取消进行中离线种子并删除记录（已成功仅删记录）。
     * qB/Aria2/Transmission：只停 RSS 推进，不误删远端种子。
     * 同时清空待执行手动刷新。
     */
    public static boolean requestCancel() {
        PendingManual dropped = RssJobState.pendingManual.getAndSet(null);
        if (!RssJobState.download.get()) {
            if (dropped != null) {
                RssJobState.jobMessage.set("已清除待执行刷新");
                cleanupDownloadToolOnCancel("清除待执行时附带清理");
                return true;
            }
            // RSS 调度空闲，但 OpenList 可能仍占用在等 hash（currentInfoHashes）
            if (isOpenListTool()) {
                String hash = null;
                try {
                    hash = SpringUtil.getBean(OpenList.class).getCurrentInfoHash();
                } catch (Exception ignored) {
                }
                if (StrUtil.isNotBlank(hash)) {
                    RssJobState.jobMessage.set("取消 OpenList 离线任务...");
                    cleanupDownloadToolOnCancel("用户取消 OpenList 占用");
                    RssJobState.jobMessage.set("已请求取消 OpenList 离线任务");
                    return true;
                }
            }
            return false;
        }
        RssJobState.cancelReason.set(CancelReason.USER);
        RssJobState.cancelRequested.set(true);
        RssJobState.jobMessage.set("取消中...");
        log.warn("用户请求取消 RSS 任务 scope={} title={} source={} droppedPending={}",
                RssJobState.jobScope.get(), RssJobState.jobTitle.get(), RssJobState.jobSource.get(), dropped != null);
        ExecutorService pool = RssJobState.activePool.get();
        if (pool != null) {
            pool.shutdownNow();
        }
        cleanupDownloadToolOnCancel("用户取消");
        // 不立刻 forceRelease：等 download() finally 正常释放。
        // 仅当 15s 后仍占用且线程池已结束时兜底（残留锁），避免与在跑任务竞态。
        ThreadUtil.execute(() -> {
            ThreadUtil.sleep(15, TimeUnit.SECONDS);
            if (!RssJobState.download.get() || !RssJobState.cancelRequested.get()) {
                return;
            }
            ExecutorService p = RssJobState.activePool.get();
            boolean poolGone = p == null || p.isTerminated();
            Thread runner = RssJobState.activeRunner.get();
            boolean runnerGone = runner == null || !runner.isAlive();
            if (poolGone && runnerGone) {
                forceReleaseLock("取消后残留锁兜底释放");
            } else {
                log.warn("取消已请求但下载线程仍在运行，继续等待自然退出");
            }
        });
        return true;
    }

    /**
     * 按任务条目取消：
     * - rss-running: 取消当前 RSS 全局任务（并清 pending + OpenList current）
     * - rss-pending: 仅清除排队手动刷新
     * - openlist-current: 仅清理 OpenList 当前占用
     * 其他条目不可取消。
     */
    public static boolean cancelItem(String itemId) {
        if (StrUtil.isBlank(itemId)) {
            return requestCancel();
        }
        if ("rss-running".equals(itemId)) {
            // 全局取消：running + pending + openlist current
            return requestCancel();
        }
        if ("rss-pending".equals(itemId)) {
            PendingManual dropped = RssJobState.pendingManual.getAndSet(null);
            if (dropped == null) {
                return false;
            }
            // 仅空闲时更新全局文案；运行中避免覆盖“处理中: xxx”
            if (!RssJobState.download.get()) {
                RssJobState.jobMessage.set("已清除待执行刷新");
            }
            log.warn("任务管理器清除待执行刷新: {}", dropped.title);
            return true;
        }
        if ("openlist-current".equals(itemId)) {
            if (!isOpenListTool()) {
                return false;
            }
            String hash = null;
            try {
                hash = SpringUtil.getBean(OpenList.class).getCurrentInfoHash();
            } catch (Exception ignored) {
            }
            if (StrUtil.isBlank(hash)) {
                return false;
            }
            // 运行中只清 OpenList 占用，不改 RSS 主文案
            if (!RssJobState.download.get()) {
                RssJobState.jobMessage.set("取消 OpenList 离线任务...");
            }
            cleanupDownloadToolOnCancel("任务条目取消 OpenList");
            if (!RssJobState.download.get()) {
                RssJobState.jobMessage.set("已请求取消 OpenList 离线任务");
            }
            return true;
        }
        return false;
    }

    /**
     * 按当前下载工具做取消侧清理。仅 OpenList/Alist 需要清理远端离线任务。
     */
    private static void cleanupDownloadToolOnCancel(String reason) {
        if (!isOpenListTool()) {
            log.info("{}：当前下载工具非 OpenList/Alist，仅停止 RSS 推进", reason);
            return;
        }
        try {
            SpringUtil.getBean(OpenList.class).cancelCurrentOffline();
        } catch (Exception e) {
            log.debug("{} 时清理 OpenList 失败: {}", reason, e.getMessage());
        }
    }

    /**
     * 当前配置是否为 OpenList/Alist（离线长等待工具）
     */
    public static boolean isOpenListTool() {
        try {
            return TorrentUtil.isOfflineTool();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isOpenListTool(Config config) {
        if (config == null) {
            return false;
        }
        String tool = config.getDownloadToolType();
        return tool != null && ("OpenList".equalsIgnoreCase(tool) || "Alist".equalsIgnoreCase(tool));
    }

    /**
     * loop 仍有效且用户未点取消
     */
    public static boolean isActive(AtomicBoolean loop) {
        if (loop != null && !loop.get()) {
            return false;
        }
        return !RssJobState.cancelRequested.get();
    }

    public static boolean isCancelRequested() {
        return RssJobState.cancelRequested.get();
    }

    /** 测试/诊断：当前是否有待执行手动刷新 */
    static boolean hasPendingManual() {
        return RssJobState.pendingManual.get() != null;
    }

    /** 测试辅助：读取来源 */
    static JobSource currentSource() {
        return RssJobState.jobSource.get();
    }

    private static void clearJobState() {
        RssJobState.cancelRequested.set(false);
        RssJobState.cancelReason.set(CancelReason.NONE);
        RssJobState.jobScope.set("idle");
        RssJobState.jobTitle.set("");
        RssJobState.jobAniId.set("");
        RssJobState.jobMessage.set("空闲");
        RssJobState.jobSource.set(null);
        RssJobState.subscriptionTotal.set(0);
        RssJobState.subscriptionActive.set(0);
        RssJobState.subscriptionCompleted.set(0);
        RssJobState.subscriptionFailed.set(0);
    }

    private static boolean forceReleaseLock(String reason) {
        synchronized (RssJobState.LIFECYCLE_LOCK) {
            ExecutorService pool = RssJobState.activePool.get();
            Thread runner = RssJobState.activeRunner.get();
            boolean poolAlive = pool != null && !pool.isTerminated();
            boolean runnerAlive = runner != null && runner.isAlive();
            if (poolAlive || runnerAlive) {
                log.warn("拒绝强制释放 RSS 全局锁，执行线程仍存活: {}", reason);
                RssJobState.cancelReason.compareAndSet(CancelReason.NONE, CancelReason.USER);
                RssJobState.cancelRequested.set(true);
                RssJobState.jobMessage.set("任务超时，等待执行线程退出...");
                if (pool != null) {
                    pool.shutdownNow();
                }
                return false;
            }

            log.warn("安全释放无活动线程的 RSS 残留锁: {}", reason);
            RssJobState.activePool.set(null);
            RssJobState.activeRunner.set(null);
            RssJobState.activeGeneration.set(0);
            RssJobState.download.set(false);
            RssJobState.downloadStartTime.set(0);
            clearJobState();
            return true;
        }
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
            if (tryStartPeriodic()) {
                syncDownload();
            }
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
        }
        ThreadUtil.sleep(sleep, TimeUnit.MINUTES);
    }
}
