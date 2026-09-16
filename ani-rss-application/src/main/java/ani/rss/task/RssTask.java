package ani.rss.task;

import ani.rss.commons.ExceptionUtils;
import ani.rss.download.OpenList;
import ani.rss.download.OpenListApi;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.TorrentsInfo;
import ani.rss.entity.vo.RssJobItem;
import ani.rss.entity.vo.RssJobStatus;
import ani.rss.enums.EventTypeEnum;
import ani.rss.enums.TorrentsTags;
import ani.rss.service.DownloadService;
import ani.rss.service.LocalStateCache;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.EventWebhookUtil;
import ani.rss.util.other.FailedDownloadQueue;
import ani.rss.util.other.RssJobStateStore;
import ani.rss.util.other.TaskFailureHumanizer;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
        // ---- 错峰更新进度（提交阶段分批推进）----
        /** 已进入的批次号（从 1 开始；0 表示尚未开始分批） */
        static final AtomicInteger currentBatch = new AtomicInteger(0);
        /** 本轮总批次数（未启用错峰时为 1） */
        static final AtomicInteger totalBatch = new AtomicInteger(0);
        /** 下一批的预计提交时间戳 ms（0 表示当前不在等待） */
        static final AtomicLong nextBatchAt = new AtomicLong(0L);
        // ---- F4 静默窗口状态 ----
        /** 本轮是否为"等待静默超时后强制开启"（true 时结果可信度降低，需在 UI 上明示） */
        static final AtomicBoolean quiescentForced = new AtomicBoolean(false);
        /** 强制开轮时，因"后处理未收尾"而被跳过的订阅 id */
        static final AtomicReference<Set<String>> quiescentBusyAniIds =
                new AtomicReference<>(Set.of());
        /** 本轮因未静默而跳过的订阅数 */
        static final AtomicInteger quiescentSkipped = new AtomicInteger(0);
        // ---- F5-6 本轮本地状态分布（跨订阅累计）----
        /** 判定为"已存在"（跳过下载）的条目数 */
        static final AtomicInteger localExists = new AtomicInteger(0);
        /** 判定为"无法确认"（跳过下载）的条目数 */
        static final AtomicInteger localUnknown = new AtomicInteger(0);
        /** 判定为"确认不存在"（已下发下载）的条目数 */
        static final AtomicInteger localAbsent = new AtomicInteger(0);
        // ---- F5-6 存疑成因分布（"为什么无法确认"比"有多少条无法确认"更有用）----
        /** 网盘列举失败 / 熔断冷却中 */
        static final AtomicInteger unknownVerifyFailed = new AtomicInteger(0);
        /** 本轮 API 预算耗尽，主动放弃校验 */
        static final AtomicInteger unknownBudgetExhausted = new AtomicInteger(0);
        /** 索引被截断，无法断言"不存在" */
        static final AtomicInteger unknownIncomplete = new AtomicInteger(0);
        /** 下载器里已有同名任务，文件尚未改名落地 */
        static final AtomicInteger unknownDownloading = new AtomicInteger(0);
        /**
         * 本轮失败的订阅明细（含归因与建议）。
         * <p>
         * 只有计数时用户无法知道"是哪几个订阅、失败在哪一步"，
         * 而日志面板又不支持关键词检索，诊断链会断掉。
         */
        static final List<RssJobStatus.FailedSubscription> failedSubscriptions =
                Collections.synchronizedList(new ArrayList<>());
        /** 明细条数上限，避免长时间运行累积 */
        static final int FAILED_SUBSCRIPTION_MAX = 50;

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
            persistState();
            try {
                EventWebhookUtil.emit(EventTypeEnum.RSS_ROUND_FINISHED, null, Map.of(
                        "title", StrUtil.blankToDefault(title, ""),
                        "scope", StrUtil.blankToDefault(scope, ""),
                        "source", source == null ? "" : source.name().toLowerCase(),
                        "durationMs", duration,
                        "message", StrUtil.blankToDefault(message, "")));
            } catch (Exception e) {
                log.debug("派发 RSS 轮次事件失败: {}", e.getMessage());
            }
        }

        /**
         * 把"上一轮结果 + 订阅级失败明细"落盘，供重启后恢复展示。
         * 只落已完成轮次的结果，不落运行中/排队中的活动态（重启后那些任务客观上已不存在）。
         */
        static void persistState() {
            try {
                RssJobStateStore.save(new RssJobStateStore.Snapshot()
                        .setLastFinishedAt(RssJobState.lastFinishedAt.get())
                        .setLastDurationMs(RssJobState.lastDurationMs.get())
                        .setLastResultMessage(RssJobState.lastResultMessage.get())
                        .setLastTitle(RssJobState.lastTitle.get())
                        .setLastSource(RssJobState.lastSource.get())
                        .setLastScope(RssJobState.lastScope.get())
                        .setFailedSubscriptions(new ArrayList<>(RssJobState.failedSubscriptions)));
            } catch (Exception e) {
                log.debug("持久化 RSS 调度状态失败: {}", e.getMessage());
            }
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
            RssJobState.currentBatch.set(0);
            RssJobState.totalBatch.set(0);
            RssJobState.nextBatchAt.set(0L);
            RssJobState.quiescentForced.set(false);
            RssJobState.quiescentBusyAniIds.set(Set.of());
            RssJobState.quiescentSkipped.set(0);
            RssJobState.localExists.set(0);
            RssJobState.localUnknown.set(0);
            RssJobState.localAbsent.set(0);
            RssJobState.unknownVerifyFailed.set(0);
            RssJobState.unknownBudgetExhausted.set(0);
            RssJobState.unknownIncomplete.set(0);
            RssJobState.unknownDownloading.set(0);
            RssJobState.failedSubscriptions.clear();
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
            RssJobState.currentBatch.set(0);
            RssJobState.totalBatch.set(0);
            RssJobState.nextBatchAt.set(0L);
            RssJobState.quiescentForced.set(false);
            RssJobState.quiescentBusyAniIds.set(Set.of());
            RssJobState.quiescentSkipped.set(0);
            RssJobState.localExists.set(0);
            RssJobState.localUnknown.set(0);
            RssJobState.localAbsent.set(0);
            RssJobState.unknownVerifyFailed.set(0);
            RssJobState.unknownBudgetExhausted.set(0);
            RssJobState.unknownIncomplete.set(0);
            RssJobState.unknownDownloading.set(0);
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
     * 订阅间并行度：同一订阅由 DownloadService 按 id 串行，这里限制整体并发。
     * <p>
     * 默认值保持不变（3），可通过 {@code Config.rssConcurrency} 覆盖。
     * 上限 8：再高会让下载器与源站同时承压，得不偿失。
     */
    private static final int ANI_PARALLELISM = 3;

    /**
     * 可配置并行度上限
     */
    static final int MAX_ANI_PARALLELISM = 8;

    /** 错峰批间隔默认值（毫秒） */
    static final int DEFAULT_STAGGER_BATCH_INTERVAL_MS = 2000;
    /** 错峰批间隔上限（毫秒） */
    static final int MAX_STAGGER_BATCH_INTERVAL_MS = 60_000;
    /** 错峰等待总时长下限（毫秒）：轮询周期很短时也至少留一点削峰余地 */
    static final long MIN_STAGGER_BUDGET_MS = 10_000L;
    /** 轮询周期缺失时的兜底分钟数 */
    static final int DEFAULT_RSS_SLEEP_MINUTES = 15;

    /**
     * 优先级默认值（普通）
     */
    static final int DEFAULT_PRIORITY = 1;

    /**
     * 按订阅优先级稳定排序：0=高 → 1=普通 → 2=低。
     * <p>
     * 线程池按提交顺序取任务，因此排序后高优先级订阅会先被扫描。
     * 同级保持原有顺序（stable），避免打乱用户习惯的排列。
     * 非法/缺失的优先级一律按"普通"处理。
     */
    static List<Ani> sortByPriority(List<Ani> anis) {
        List<Ani> copy = new ArrayList<>(anis);
        copy.sort(Comparator.comparingInt(RssTask::priorityOf));
        return copy;
    }

    static int priorityOf(Ani ani) {
        if (ani == null) {
            return DEFAULT_PRIORITY;
        }
        Integer p = ani.getPriority();
        if (p == null) {
            return DEFAULT_PRIORITY;
        }
        return Math.max(0, Math.min(2, p));
    }

    /**
     * 解析生效的并行度（配置缺失/非法时回落默认值）
     */
    static int resolveParallelism(Config config, int subscriptionCount) {
        int configured = config == null || config.getRssConcurrency() == null
                ? ANI_PARALLELISM
                : config.getRssConcurrency();
        int parallelism = Math.max(1, Math.min(configured, MAX_ANI_PARALLELISM));
        return Math.min(parallelism, Math.max(1, subscriptionCount));
    }

    /**
     * 错峰更新是否启用。默认启用；配置缺失（旧配置文件）时也按启用处理。
     */
    static boolean isStaggeredUpdateEnabled(Config config) {
        return config == null || !Boolean.FALSE.equals(config.getStaggeredUpdateEnable());
    }

    /**
     * 批间隔毫秒数（缺失/非法回落 2000，上限 60s）
     */
    static long resolveStaggerBatchIntervalMs(Config config) {
        Integer configured = config == null ? null : config.getStaggerBatchIntervalMs();
        long value = configured == null ? DEFAULT_STAGGER_BATCH_INTERVAL_MS : configured;
        return Math.max(0L, Math.min(value, MAX_STAGGER_BATCH_INTERVAL_MS));
    }

    /**
     * 错峰等待的<b>总时长上限</b>。
     * <p>
     * 错峰的意义是削峰，不是把整轮拉长：订阅多时若照 批数 × 间隔 一路等下去，
     * 一轮可能被拖到几十分钟，反而挤掉下一轮。故取"轮询周期的 1/4"与 10s 中的较大者封顶，
     * 超出后剩余批次改为连续提交（会记一条 WARN）。
     */
    static long resolveStaggerBudgetMs(Config config, int batchIntervalMs, int totalBatch) {
        int sleepMinutes = config == null || config.getRssSleepMinutes() == null
                ? DEFAULT_RSS_SLEEP_MINUTES
                : Math.max(1, config.getRssSleepMinutes());
        long byCycle = TimeUnit.MINUTES.toMillis(sleepMinutes) / 4;
        long budget = Math.max(byCycle, MIN_STAGGER_BUDGET_MS);
        long planned = (long) batchIntervalMs * Math.max(0, totalBatch - 1);
        return Math.min(planned, budget);
    }

    /**
     * 计算批次数。未启用错峰时为 1（= 一次性全量提交，与改造前一致）。
     */
    static int resolveTotalBatch(Config config, int subscriptionCount, int batchSize) {
        if (!isStaggeredUpdateEnabled(config) || subscriptionCount <= 0) {
            return 1;
        }
        int size = Math.max(1, batchSize);
        return (subscriptionCount + size - 1) / size;
    }

    /**
     * 单轮网盘 API 预算（F7-5）。
     * <p>
     * 默认 = 本轮启用订阅数 × 1：每个订阅至少要列举一次才能确认本地文件。
     * 配了就用配的值，但一律受 {@link OpenListApi#MAX_API_BUDGET_PER_ROUND} 硬顶约束——
     * 订阅 500 个就允许打 500 次，等于没有预算。
     */
    static int resolveApiBudgetPerRound(Config config, int enabledCount) {
        Integer configured = config == null ? null : config.getOpenListApiBudgetPerRound();
        int value = configured == null ? Math.max(1, enabledCount) : configured;
        return Math.max(1, Math.min(value, OpenListApi.MAX_API_BUDGET_PER_ROUND));
    }

    /**
     * F4-6：强制开轮时剔除"后处理尚未收尾"的订阅，返回被剔除的数量（就地过滤 {@code enabled}）。
     * <p>
     * 这些订阅此刻的本地状态<b>不可信</b>——文件可能已经下载完，但改名 / 离线归位还没落地，
     * 于是按文件名匹配集数会匹配不上，把"已下载"判成"未下载"，进而<b>重复下载</b>。
     * 这正是验收项 A5（下载完成但未改名 → 不触发重复下载）与 A13（静默超时强制开轮 → 该轮无
     * {@code ABSENT}）要守住的场景。
     * <p>
     * 只剔除"忙碌"的那些，其余订阅照常扫描：不因为个别订阅卡住就让整轮停摆。
     * 注意<b>只有强制开轮才剔除</b>——正常轮次本来就等到了静默，剔除会漏扫。
     * <p>
     * 抽成静态纯函数是为了能被单测直接覆盖：轮次主体依赖线程池、静默闸门与下载器，
     * 端到端跑一轮的成本远高于收益，而这段过滤逻辑恰恰是"重复下载"的最后一道闸门。
     *
     * @param enabled    已通过"存在 + 启用"过滤的订阅（会被就地修改）
     * @param forced     本轮是否为"静默超时强制开轮"
     * @param busyAniIds 后处理未收尾的订阅 id
     * @return 被剔除的订阅数量
     */
    static int dropBusySubscriptionsOnForcedRound(List<Ani> enabled, boolean forced, Set<String> busyAniIds) {
        if (!forced || enabled == null || enabled.isEmpty()
                || busyAniIds == null || busyAniIds.isEmpty()) {
            return 0;
        }
        int before = enabled.size();
        // 显式判 null id：不可变 Set（Set.of(...)）的 contains(null) 会抛 NPE，
        // 而 id 缺失的条目本来也不可能出现在 busyAniIds 里，保留它走正常路径即可。
        enabled.removeIf(ani -> ani != null && ani.getId() != null && busyAniIds.contains(ani.getId()));
        return before - enabled.size();
    }

    /**
     * 订阅是否仍然启用（F3-2）。
     * <p>
     * 轮次进行中用户随时可能关掉某个订阅。用的是 {@code AniUtil.getAniList()} 里的
     * <b>实时对象</b>，不是轮次开始时的副本——否则"关掉了却还在扫"会一直存在。
     */
    static boolean isStillEnabled(String aniId) {
        if (StrUtil.isBlank(aniId)) {
            return false;
        }
        try {
            List<Ani> list = AniUtil.getAniList();
            if (list == null) {
                return false;
            }
            for (Ani ani : list) {
                if (ani != null && aniId.equals(ani.getId())) {
                    return Boolean.TRUE.equals(ani.getEnable());
                }
            }
        } catch (Exception e) {
            log.debug("读取实时订阅状态失败 {}: {}", aniId, ExceptionUtils.getMessage(e));
        }
        return false;
    }

    /**
     * 取实时订阅对象（F6-2）。
     * <p>
     * 轮次开始时的副本会把 {@code enable} / {@code notDownload} / {@code season} 的改动全部吃掉：
     * 用户在扫描过程中改了这些值，本轮仍然按旧值执行。每个子任务开头都重新取一次，
     * 让改动最迟在下一个订阅上生效。
     *
     * @return 实时对象；订阅已被删除时返回 null
     */
    static Ani resolveLiveAni(String aniId) {
        if (StrUtil.isBlank(aniId)) {
            return null;
        }
        try {
            List<Ani> list = AniUtil.getAniList();
            if (list == null) {
                return null;
            }
            for (Ani ani : list) {
                if (ani != null && aniId.equals(ani.getId())) {
                    return ani;
                }
            }
        } catch (Exception e) {
            log.debug("读取实时订阅失败 {}: {}", aniId, ExceptionUtils.getMessage(e));
        }
        return null;
    }

    /**
     * 可中断的错峰等待：分段 sleep，保证用户点"取消"后能在 {@code SLICE} 级别内响应，
     * 而不是傻等完整个批间隔（批间隔上限 60s）。
     *
     * @return 实际等待的毫秒数（被取消时小于请求值）
     */
    static long sleepInterruptible(AtomicBoolean loop, long totalMs) {
        long remaining = Math.max(0L, totalMs);
        if (remaining <= 0L) {
            return 0L;
        }
        long startedAt = System.currentTimeMillis();
        long slice = 500L;
        while (remaining > 0L) {
            if (!isActive(loop)) {
                break;
            }
            long step = Math.min(remaining, slice);
            ThreadUtil.sleep(step);
            remaining -= step;
        }
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    // ==================== F4 静默窗口 ====================
    //
    // 问题：周期轮次每 rssSleepMinutes 无条件开轮，改名/上传/离线归位是独立任务，
    // 两者零协调。于是会出现"下载已完成、但文件尚未改名落地"的窗口——此刻按真实文件
    // 做"本地是否存在"判定会漏掉它（未改名文件不含 SxxExx，集数索引匹配不上），
    // 进而重复下发下载。
    //
    // 解法：开轮前先等后处理收尾（静默），并连续确认 N 次防瞬时空窗误判；
    // 等太久则强制开轮——一次卡住的改名不该让 RSS 永久停摆。
    //
    // 判定本身只读内存/本地文件，**绝不触发网盘 API**（F4-8）。

    /** 静默确认次数默认值 */
    static final int DEFAULT_QUIESCENT_CONFIRM_TIMES = 2;
    static final int MAX_QUIESCENT_CONFIRM_TIMES = 10;
    static final int MAX_QUIESCENT_TIMEOUT_MINUTES = 24 * 60;
    /** 静默判定的最小轮询间隔，避免确认次数大时打满 CPU */
    static final long MIN_QUIESCENT_INTERVAL_MS = 500L;

    /**
     * 「已下载完成、进入做种/上传」的状态集合。
     * <p>
     * 这些状态下文件已经落盘，若还缺 {@code RENAME} 标签，说明改名尚未完成——
     * 正是需要等待的窗口。其余状态（下载中/校验中/暂停下载/出错/元数据）属于"还没下完"。
     */
    private static final Set<String> SEEDING_STATES = Set.of(
            TorrentsInfo.State.queuedUP.name(),
            TorrentsInfo.State.uploading.name(),
            TorrentsInfo.State.stalledUP.name(),
            TorrentsInfo.State.pausedUP.name(),
            TorrentsInfo.State.stoppedUP.name());

    /**
     * 是否处于"已完成下载"状态。{@code null} 状态按"未完成"处理（保守）。
     */
    static boolean isSeedingState(TorrentsInfo.State state) {
        return state != null && SEEDING_STATES.contains(state.name());
    }

    /**
     * 静默判定结果。
     */
    public static final class QuiescentState {
        private final boolean quiescent;
        private final String reason;
        private final Set<String> busyAniIds;

        private QuiescentState(boolean quiescent, String reason, Set<String> busyAniIds) {
            this.quiescent = quiescent;
            this.reason = reason;
            this.busyAniIds = busyAniIds == null ? Set.of() : busyAniIds;
        }

        static QuiescentState quiet() {
            return new QuiescentState(true, "", Set.of());
        }

        static QuiescentState busy(String reason, Set<String> busyAniIds) {
            return new QuiescentState(false, reason, busyAniIds);
        }

        public boolean quiescent() {
            return quiescent;
        }

        /** 不静默的原因（已人话化），静默时为空串 */
        public String reason() {
            return reason;
        }

        /** 能归属到订阅的"未静默"订阅 id（F4-6 单订阅粒度）；无法归属时为空 */
        public Set<String> busyAniIds() {
            return busyAniIds;
        }
    }

    /**
     * 评估当前是否处于静默窗口（F4-1 ~ F4-4、F4-8）。
     * <p>
     * 三条件全部满足才算静默：无在跑轮次、离线 pending 已清空、下载器里没有未完成任务
     * 且已完成任务都已改名。
     * <p>
     * 任一项<b>无法判定</b>（如下载器查询失败）一律按"不静默"处理：宁可等，不可在状态未知时开轮。
     */
    public static QuiescentState evaluateQuiescent() {
        // F4-4 全局条件：没有正在跑的轮次
        if (RssJobState.download.get()) {
            return QuiescentState.busy("有 RSS 轮次正在运行", Set.of());
        }
        // F4-3 离线网盘：.pending 下不能还有未落地的标记文件
        int pending = countPendingMarkers();
        if (pending > 0) {
            return QuiescentState.busy("有 " + pending + " 个离线任务尚未落地", Set.of());
        }
        // F4-2 本地下载器：无未完成任务；已完成任务必须都已改名
        List<TorrentsInfo> torrentsInfos;
        try {
            torrentsInfos = TorrentUtil.getTorrentsInfos();
        } catch (Exception e) {
            return QuiescentState.busy("读取下载器任务失败: " + ExceptionUtils.getMessage(e), Set.of());
        }
        int inFlight = 0;
        Set<String> notRenamed = new LinkedHashSet<>();
        for (TorrentsInfo ti : torrentsInfos) {
            if (ti == null) {
                continue;
            }
            if (!isSeedingState(ti.getState())) {
                inFlight++;
                continue;
            }
            List<String> tags = ti.getTags();
            if (tags == null || !tags.contains(TorrentsTags.RENAME.getValue())) {
                notRenamed.add(StrUtil.blankToDefault(ti.getName(), ""));
            }
        }
        if (inFlight > 0) {
            return QuiescentState.busy("有 " + inFlight + " 个下载任务尚未完成", Set.of());
        }
        if (!notRenamed.isEmpty()) {
            return QuiescentState.busy("有 " + notRenamed.size() + " 个任务已完成下载但尚未改名",
                    resolveAniIds(notRenamed));
        }
        return QuiescentState.quiet();
    }

    /**
     * 把"未改名任务名"反查为订阅 id（F4-6）。
     * <p>
     * 反查依赖 {@code downloadDir → Ani} 索引，拿不到就跳过——**不因此阻塞整轮**，
     * 只是失去"按订阅精确跳过"的能力。
     */
    private static Set<String> resolveAniIds(Set<String> torrentNames) {
        Set<String> ids = new LinkedHashSet<>();
        try {
            DownloadService downloadService = SpringUtil.getBean(DownloadService.class);
            List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();
            for (TorrentsInfo ti : torrentsInfos) {
                if (ti == null || !torrentNames.contains(StrUtil.blankToDefault(ti.getName(), ""))) {
                    continue;
                }
                Optional<Ani> ani = downloadService.findAniByDownloadPath(ti);
                ani.map(Ani::getId).filter(StrUtil::isNotBlank).ifPresent(ids::add);
            }
        } catch (Exception e) {
            log.debug("反查未静默订阅失败: {}", ExceptionUtils.getMessage(e));
        }
        return ids;
    }

    /**
     * 统计 {@code {configDir}/torrents/.pending/} 下的标记文件数（F4-3、F4-8：只读本地文件）。
     */
    static int countPendingMarkers() {
        try {
            File pendingRoot = new File(ConfigUtil.getConfigDir(), "torrents/.pending");
            if (!pendingRoot.isDirectory()) {
                return 0;
            }
            int[] count = {0};
            FileUtil.walkFiles(pendingRoot, f -> {
                if (f.isFile()) {
                    count[0]++;
                }
            });
            return count[0];
        } catch (Exception e) {
            log.debug("统计 pending 标记失败: {}", ExceptionUtils.getMessage(e));
            return 0;
        }
    }

    /**
     * 等待进入静默窗口（F4-5）。连续 {@code quiescentConfirmTimes} 次判定静默才放行。
     *
     * @return 最终判定结果；{@code quiescent()} 为 false 表示已超时（调用方据此强制开轮）
     */
    static QuiescentState awaitQuiescent(AtomicBoolean loop, Config config) {
        return awaitQuiescent(loop,
                resolveQuiescentConfirmTimes(config),
                resolveQuiescentIntervalMs(config),
                resolveQuiescentTimeoutMs(config));
    }

    /**
     * 等待静默（参数显式传入，便于单测用毫秒级超时覆盖超时分支）。
     *
     * @param confirmTimes 需要连续判定静默的次数
     * @param intervalMs   两次判定之间的间隔
     * @param timeoutMs    总等待上限
     * @return 最终判定结果；{@code quiescent()} 为 false 表示已超时
     */
    static QuiescentState awaitQuiescent(AtomicBoolean loop, int confirmTimes, long intervalMs, long timeoutMs) {
        int need = Math.max(1, confirmTimes);
        long interval = Math.max(1L, intervalMs);
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);

        int consecutive = 0;
        QuiescentState last = null;
        while (true) {
            if (!isActive(loop)) {
                // 已请求取消：不再拦着，交给调用方收尾
                return QuiescentState.quiet();
            }
            last = evaluateQuiescent();
            if (last.quiescent()) {
                if (++consecutive >= need) {
                    return last;
                }
            } else {
                consecutive = 0;
            }
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0L) {
                return last;
            }
            ThreadUtil.sleep(Math.min(interval, remain));
        }
    }

    static int resolveQuiescentConfirmTimes(Config config) {
        Integer configured = config == null ? null : config.getQuiescentConfirmTimes();
        int value = configured == null ? DEFAULT_QUIESCENT_CONFIRM_TIMES : configured;
        return Math.max(1, Math.min(value, MAX_QUIESCENT_CONFIRM_TIMES));
    }

    /**
     * 静默确认的轮询间隔：与改名任务同频（{@code renameSleepSeconds}），至少 {@code MIN_QUIESCENT_INTERVAL_MS}。
     */
    static long resolveQuiescentIntervalMs(Config config) {
        Integer seconds = config == null ? null : config.getRenameSleepSeconds();
        long value = seconds == null ? 10L : Math.max(1L, seconds);
        return Math.max(MIN_QUIESCENT_INTERVAL_MS, TimeUnit.SECONDS.toMillis(value));
    }

    /**
     * 静默等待上限（毫秒）。配置为 {@code null} 时回落 {@code 2 × rssSleepMinutes}。
     */
    static long resolveQuiescentTimeoutMs(Config config) {
        Integer minutes = config == null ? null : config.getQuiescentTimeoutMinutes();
        int value;
        if (minutes == null) {
            int sleepMinutes = config == null || config.getRssSleepMinutes() == null
                    ? DEFAULT_RSS_SLEEP_MINUTES
                    : Math.max(1, config.getRssSleepMinutes());
            value = Math.max(5, sleepMinutes * 2);
        } else {
            value = Math.max(1, Math.min(minutes, MAX_QUIESCENT_TIMEOUT_MINUTES));
        }
        return TimeUnit.MINUTES.toMillis(value);
    }

    /**
     * 本轮结束后的休眠时长：从轮询周期里**扣掉**等待静默已消耗的时间。
     * <p>
     * 不扣的话"等待 + 间隔"会把周期悄悄拉长一倍（15 分钟周期 → 30 分钟等待 + 15 分钟间隔），
     * 用户只会觉得"RSS 变慢了"却找不到原因。
     * 保底 {@code max(1 分钟, 周期的 1/4)}，避免退化成忙等。
     */
    static long resolvePostRoundSleepMs(Config config, long waitedMs) {
        int sleepMinutes = config == null || config.getRssSleepMinutes() == null
                ? DEFAULT_RSS_SLEEP_MINUTES
                : Math.max(1, config.getRssSleepMinutes());
        long periodMs = TimeUnit.MINUTES.toMillis(sleepMinutes);
        long floorMs = Math.max(TimeUnit.MINUTES.toMillis(1), periodMs / 4);
        return Math.max(floorMs, periodMs - Math.max(0L, waitedMs));
    }

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

        ExecutorService pool = null;
        try {
            // getBean 放入 try：bean 获取失败也要走 finally 释放全局锁，避免幽灵锁
            DownloadService downloadService = SpringUtil.getBean(DownloadService.class);

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

            // F4-6：强制开轮时，跳过"后处理尚未收尾"的订阅（详见 dropBusySubscriptionsOnForcedRound）
            int skippedBusy = dropBusySubscriptionsOnForcedRound(
                    enabled, RssJobState.quiescentForced.get(), RssJobState.quiescentBusyAniIds.get());
            if (skippedBusy > 0) {
                RssJobState.quiescentSkipped.set(skippedBusy);
                log.warn("强制开轮：跳过 {} 个尚未完成后处理的订阅，其余 {} 个照常扫描",
                        skippedBusy, enabled.size());
            }

            if (enabled.isEmpty()) {
                RssJobState.jobMessage.set("无可下载订阅");
                return;
            }

            RssJobState.subscriptionTotal.set(enabled.size());
            RssJobState.subscriptionActive.set(0);
            RssJobState.subscriptionCompleted.set(0);
            RssJobState.subscriptionFailed.set(0);
            RssJobState.failedSubscriptions.clear();
            updateProgressMessage(generation);

            // 按订阅优先级排序后再提交：线程池按提交顺序取任务，
            // 因此"本季在追"的高优先级订阅会先被扫描，订阅量大时不必等全量扫完。
            List<Ani> ordered = sortByPriority(enabled);
            int poolSize = resolveParallelism(ConfigUtil.CONFIG, ordered.size());
            pool = Executors.newFixedThreadPool(poolSize);
            RssJobState.activePool.set(pool);
            List<Future<?>> futures = new ArrayList<>(ordered.size());

            // 本轮 API 调用计数从这里开始算，供自检/诊断观察"这一轮到底打了多少次网盘"
            OpenListApi.resetRoundApiStats();
            // F7-5：同时给本轮装上预算。预算耗尽即停止 Phase B，剩余条目保持「存疑」。
            OpenListApi.startRoundBudget(resolveApiBudgetPerRound(ConfigUtil.CONFIG, ordered.size()));

            // ---- F1 错峰更新 ----
            // 线程池容量即"一批"，批与批之间留出间隔，避免同一瞬间把 RSS 源 / 下载器 / 网盘打爆。
            // 注意 sleep 只发生在提交线程（本线程），子任务内部不做任何等待 —— 否则会占用
            // 工作线程、把并行度白白吃掉（F1-4）。
            // 总等待时长受 resolveStaggerBudgetMs 封顶：错峰的目的是削峰，不是把整轮拉长。
            boolean staggered = isStaggeredUpdateEnabled(ConfigUtil.CONFIG);
            long batchIntervalMs = staggered ? resolveStaggerBatchIntervalMs(ConfigUtil.CONFIG) : 0L;
            int totalBatch = resolveTotalBatch(ConfigUtil.CONFIG, ordered.size(), poolSize);
            long staggerBudgetMs = staggered
                    ? resolveStaggerBudgetMs(ConfigUtil.CONFIG, (int) batchIntervalMs, totalBatch)
                    : 0L;
            RssJobState.totalBatch.set(totalBatch);
            RssJobState.currentBatch.set(0);
            RssJobState.nextBatchAt.set(0L);
            long staggerUsedMs = 0L;
            boolean budgetWarned = false;

            for (int index = 0; index < ordered.size(); index++) {
                if (!isActive(loop)) {
                    RssJobState.jobMessage.set("已取消");
                    break;
                }
                int batchNo = index / poolSize + 1;
                if (staggered && batchNo > RssJobState.currentBatch.get()) {
                    // 进入新的一批：先错峰等待，再提交
                    if (batchNo > 1 && batchIntervalMs > 0L) {
                        if (staggerUsedMs < staggerBudgetMs) {
                            long wait = Math.min(batchIntervalMs, staggerBudgetMs - staggerUsedMs);
                            RssJobState.nextBatchAt.set(System.currentTimeMillis() + wait);
                            updateProgressMessage(generation);
                            staggerUsedMs += sleepInterruptible(loop, wait);
                            RssJobState.nextBatchAt.set(0L);
                        } else if (!budgetWarned) {
                            budgetWarned = true;
                            log.warn("错峰等待已达总时长上限 {}ms，剩余批次改为连续提交（共 {} 批 / {} 个订阅）",
                                    staggerBudgetMs, totalBatch, ordered.size());
                        }
                    }
                    RssJobState.currentBatch.set(batchNo);
                }
                Ani ani = ordered.get(index);
                // F3-2 检查点①：提交前重新确认订阅仍启用。
                // 用户扫到一半关掉某个订阅时，剩余批次不该再把它提交进线程池。
                if (!isStillEnabled(ani.getId())) {
                    log.debug("{} 已禁用，跳过提交", ani.getTitle());
                    LocalStateCache.invalidate(ani.getId());
                    RssJobState.subscriptionCompleted.incrementAndGet();
                    updateProgressMessage(generation);
                    continue;
                }
                futures.add(pool.submit(() -> {
                    if (!isActive(loop)) {
                        return;
                    }
                    String aniId = ani.getId();
                    // F6-2 检查点②：子任务开头取<b>实时</b>订阅对象。
                    // 轮次开始时的副本会把 enable / notDownload / season 的改动全部吃掉。
                    Ani live = resolveLiveAni(aniId);
                    if (live == null) {
                        // 订阅已被删除
                        RssJobState.subscriptionCompleted.incrementAndGet();
                        updateProgressMessage(generation);
                        return;
                    }
                    // F3-2/F3-3 检查点③：已提交但尚未开始的子任务，若期间被禁用则直接退出
                    if (!Boolean.TRUE.equals(live.getEnable())) {
                        log.debug("{} 已禁用，跳过执行", live.getTitle());
                        LocalStateCache.invalidate(aniId);
                        RssJobState.subscriptionCompleted.incrementAndGet();
                        updateProgressMessage(generation);
                        return;
                    }
                    String title = live.getTitle();
                    RssJobState.subscriptionActive.incrementAndGet();
                    updateProgressMessage(generation);
                    try {
                        downloadService.downloadAni(live);
                    } catch (Exception e) {
                        RssJobState.subscriptionFailed.incrementAndGet();
                        String message = ExceptionUtils.getMessage(e);
                        log.error("{} {}", title, message);
                        log.error(message, e);
                        recordSubscriptionFailure(live, message);
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
                // 强制开轮 / 有订阅被跳过时必须说清楚，否则用户会以为"漏扫了"是 bug
                String forcedNote = RssJobState.quiescentForced.get() ? "（静默超时强制开轮）" : "";
                int skipped = RssJobState.quiescentSkipped.get();
                String skippedNote = skipped > 0 ? "，跳过未收尾订阅 " + skipped : "";
                RssJobState.jobMessage.set(failed > 0
                        ? ("部分失败: " + failed + "/" + total + "，已处理 " + completed + "/" + total
                        + skippedNote + forcedNote)
                        : ("已完成: " + completed + "/" + total + skippedNote + forcedNote));
            }
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
            RssJobState.jobMessage.set(humanizeRunningError(message));
        } finally {
            // 轮次结束必须撤掉预算，否则用户随后手动预览会被上一轮的预算卡住
            OpenListApi.clearRoundBudget();
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
        return submitManualRefresh(aniList, true);
    }

    /**
     * 手动刷新入口（可控是否抢占周期任务）。
     * <p>
     * 单订阅刷新（卡片上的“立即检查新集”）用 {@code allowPreempt=false}：
     * 用户只是想看这一部有没有新集，不应该因此中断正在跑的整轮周期扫描
     * （周期扫描被打断会让本轮剩余订阅不再处理）。此时改为排队，
     * 由周期任务收尾后自动接管。
     *
     * @param aniList     null=全部启用订阅
     * @param allowPreempt 是否允许抢占（取消）正在运行的周期任务
     * @return 给前端的提示文案
     */
    public static String submitManualRefresh(List<Ani> aniList, boolean allowPreempt) {
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
        long seenGeneration = RssJobState.activeGeneration.get();
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

        if (!allowPreempt && RssJobState.download.get()) {
            // 温和模式：只排队，绝不取消正在运行的任务
            PendingManual prev = RssJobState.pendingManual.getAndSet(job);
            RssJobState.jobMessage.set("已排队待执行: " + job.title);
            if (prev != null) {
                return "当前任务进行中，已替换先前的待执行刷新";
            }
            return "当前任务进行中，已排队，稍后自动执行";
        }

        if (RssJobState.download.get() && source == JobSource.PERIODIC) {
            synchronized (RssJobState.LIFECYCLE_LOCK) {
                // 锁内重验：仅当仍是同一个周期任务（世代未切换）才允许抢先取消，避免误杀新一代任务
                if (RssJobState.download.get()
                        && RssJobState.jobSource.get() == JobSource.PERIODIC
                        && RssJobState.activeGeneration.get() == seenGeneration) {
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
            }
            // 锁内校验失败：状态已切换（周期任务已收尾或已被新一代任务接替）
            if (!RssJobState.download.get()) {
                // 已空闲：抢锁直接启动
                try {
                    acquireLock(JobSource.MANUAL, "手动刷新启动中...");
                    startDownloadAsync(aniList);
                    return "已开始刷新RSS";
                } catch (IllegalStateException ignored) {
                    // 继续走排队逻辑
                }
            }
            if (RssJobState.download.get() && RssJobState.jobSource.get() == JobSource.MANUAL) {
                // 已是手动任务：仅排队，不取消
                PendingManual prev = RssJobState.pendingManual.getAndSet(job);
                RssJobState.jobMessage.set("已排队待执行: " + job.title);
                if (prev != null) {
                    return "当前已有手动刷新，新的请求已替换待执行队列";
                }
                return "当前手动刷新进行中，新的请求已排队（最多 1 个）";
            }
            // 其它状态（如新一代周期任务）：保守排队，绝不取消新一代任务
            RssJobState.pendingManual.compareAndSet(null, job);
            return "任务繁忙，已排队待执行";
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
            synchronized (RssJobState.LIFECYCLE_LOCK) {
                // 锁内重验，避免对刚收尾的任务发取消信号
                if (RssJobState.download.get()) {
                    RssJobState.pendingManual.set(job);
                    RssJobState.cancelReason.set(CancelReason.PREEMPT);
                    RssJobState.cancelRequested.set(true);
                }
            }
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
                + (failed > 0 ? "，失败 " + failed : "")
                + staggerSuffix());
    }

    /**
     * 错峰批次后缀。
     * <p>
     * 只有本轮确实分了多批（{@code totalBatch > 1}）时才追加，
     * 未启用错峰 / 订阅数不足一批时返回空串 —— 保证关闭错峰后的文案与改造前逐字节一致。
     */
    static String staggerSuffix() {
        int totalBatch = RssJobState.totalBatch.get();
        if (totalBatch <= 1) {
            return "";
        }
        int currentBatch = Math.min(Math.max(1, RssJobState.currentBatch.get()), totalBatch);
        StringBuilder sb = new StringBuilder("，批次 ")
                .append(currentBatch).append("/").append(totalBatch);
        long nextAt = RssJobState.nextBatchAt.get();
        if (nextAt > 0L) {
            long remain = Math.max(0L, nextAt - System.currentTimeMillis());
            sb.append("（").append(Math.max(1L, remain / 1000L)).append("s 后下一批）");
        }
        return sb.toString();
    }

    private static void shutdownAndAwaitPool(ExecutorService pool, long generation) {
        if (pool == null) {
            return;
        }
        pool.shutdownNow();
        boolean interrupted = false;
        // 整体 deadline：worker 卡在不可中断 IO 时也能收尾释放锁，避免全局 RSS 锁被永久挂死
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        while (!pool.isTerminated()) {
            if (System.currentTimeMillis() >= deadline) {
                log.warn("等待 RSS 线程池退出超时（累计 5 分钟），放弃等待并继续收尾 generation={}", generation);
                break;
            }
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
        try {
            startDownloadAsyncInner(aniList);
        } catch (Throwable e) {
            // 提交失败（如线程池拒绝/异步初始化异常）也要释放锁，避免 download=true 幽灵锁
            log.error("提交 RSS 异步下载任务失败: {}", e.getMessage(), e);
            if (generation == RssJobState.activeGeneration.get()) {
                PendingManual next = finishGeneration(generation);
                if (next != null) {
                    // 锁已释放，把排队任务放回队列，由下一次手动刷新/任务收尾调度触发，避免递归重试
                    RssJobState.pendingManual.compareAndSet(null, next);
                }
            }
        }
    }

    /**
     * 裸提交异步下载任务（不含失败兜底），供 startDownloadAsync 复用
     */
    private static void startDownloadAsyncInner(List<Ani> aniList) {
        long generation = RssJobState.activeGeneration.get();
        ThreadUtil.execute(() -> {
            try {
                download(new AtomicBoolean(true), aniList, generation);
            } catch (Exception e) {
                String message = ExceptionUtils.getMessage(e);
                log.error(message, e);
                if (generation == RssJobState.activeGeneration.get()) {
                    RssJobState.jobMessage.set(humanizeRunningError(message));
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
     * 启动时恢复上一轮调度快照（任务管理器"上一轮已处理"与订阅级失败明细）。
     * <p>
     * 只恢复已完成轮次的结果性信息，不恢复运行中/排队中的活动态——
     * 重启后那些任务客观上已不存在，恢复出"运行中"只会制造幽灵状态。
     * 需要恢复的运行态由启动回扫 / OpenList 残留扫描负责。
     *
     * @return 是否恢复成功
     */
    public static boolean restorePersistedState() {
        try {
            RssJobStateStore.Snapshot snapshot = RssJobStateStore.load();
            if (snapshot == null) {
                return false;
            }
            if (snapshot.getLastFinishedAt() != null) {
                RssJobState.lastFinishedAt.set(snapshot.getLastFinishedAt());
            }
            if (snapshot.getLastDurationMs() != null) {
                RssJobState.lastDurationMs.set(snapshot.getLastDurationMs());
            }
            if (StrUtil.isNotBlank(snapshot.getLastResultMessage())) {
                RssJobState.lastResultMessage.set(snapshot.getLastResultMessage());
            }
            if (StrUtil.isNotBlank(snapshot.getLastTitle())) {
                RssJobState.lastTitle.set(snapshot.getLastTitle());
            }
            if (StrUtil.isNotBlank(snapshot.getLastSource())) {
                RssJobState.lastSource.set(snapshot.getLastSource());
            }
            if (StrUtil.isNotBlank(snapshot.getLastScope())) {
                RssJobState.lastScope.set(snapshot.getLastScope());
            }
            List<RssJobStatus.FailedSubscription> failed = snapshot.getFailedSubscriptions();
            if (failed != null && !failed.isEmpty()) {
                RssJobState.failedSubscriptions.clear();
                RssJobState.failedSubscriptions.addAll(failed);
            }
            log.info("已恢复上次 RSS 调度快照: 最近完成 {} 条失败明细",
                    failed == null ? 0 : failed.size());
            return true;
        } catch (Exception e) {
            log.warn("恢复 RSS 调度快照失败: {}", e.getMessage());
            return false;
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
        int currentBatch;
        int totalBatch;
        long nextBatchAt;
        boolean quiescentForced;
        int quiescentSkipped;
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
            currentBatch = RssJobState.currentBatch.get();
            totalBatch = RssJobState.totalBatch.get();
            nextBatchAt = RssJobState.nextBatchAt.get();
            quiescentForced = RssJobState.quiescentForced.get();
            quiescentSkipped = RssJobState.quiescentSkipped.get();
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
                .setCurrentBatch(totalBatch > 1 ? currentBatch : null)
                .setTotalBatch(totalBatch > 0 ? totalBatch : null)
                .setNextBatchAt(nextBatchAt > 0L ? nextBatchAt : null)
                .setQuiescentForced(running ? quiescentForced : null)
                .setQuiescentSkipped(running && quiescentSkipped > 0 ? quiescentSkipped : null)
                .setRoundLocalState(running ? getRoundLocalStateSummary() : null)
                // 存疑成因：只说"有 N 条无法确认"用户不知道该做什么，分开计数才有行动价值
                .setUnknownReasons(running ? getRoundUnknownReasonSummary() : null)
                // 失败明细：让用户直接看到"是哪几个订阅、失败在哪一步、怎么办"
                .setFailedSubscriptions(List.copyOf(RssJobState.failedSubscriptions))
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
        // 防御旧配置缺字段时的拆箱 NPE
        int sleepMinutes = ObjectUtil.defaultIfNull(config.getRssSleepMinutes(), 15);

        if (!Boolean.TRUE.equals(config.getRss())) {
            log.debug("rss未启用");
            ThreadUtil.sleep(sleepMinutes, TimeUnit.MINUTES);
            return;
        }

        try {
            // F4 静默窗口：等后处理（改名 / 上传 / 离线归位）收尾后再开新一轮。
            // 等待发生在 tryStartPeriodic 之前 —— 期间不持全局锁，用户的手动刷新仍可正常抢占。
            long waitStartedAt = System.currentTimeMillis();
            QuiescentState quiescent = awaitQuiescent(loop, config);
            long waitedMs = System.currentTimeMillis() - waitStartedAt;
            boolean forced = !quiescent.quiescent();
            if (forced) {
                log.warn("等待静默超时（{} 分钟），强制开启本轮。未静默原因: {}。"
                                + "本轮将跳过尚未完成收尾的订阅，其余照常扫描",
                        TimeUnit.MILLISECONDS.toMinutes(resolveQuiescentTimeoutMs(config)),
                        quiescent.reason());
            }
            if (tryStartPeriodic()) {
                // 归属本轮：必须在拿到轮次锁之后设置，否则可能被别的轮次误读
                RssJobState.quiescentForced.set(forced);
                RssJobState.quiescentBusyAniIds.set(quiescent.busyAniIds());
                syncDownload();
            }
            // 把等待静默的时间从本轮间隔里扣掉。
            // 不扣的话"等待 + 间隔"会把周期悄悄拉长一倍（15 分钟周期变成 30 分钟等待 + 15 分钟间隔），
            // 用户只会觉得"RSS 变慢了"却找不到原因。保底留一小段，避免退化成忙等。
            // 用可中断等待：停止请求最长 500ms 内生效，不必等满整个轮询间隔或依赖 interrupt
            sleepInterruptibly(loop, resolvePostRoundSleepMs(config, waitedMs));
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
            sleepInterruptibly(loop, TimeUnit.MINUTES.toMillis(sleepMinutes));
        }
    }

    /**
     * F5-6：累计本轮的本地状态分布（跨订阅汇总），供验收与诊断。
     * <p>
     * 语义是<b>本轮的处置结果</b>，不是展示层的三态：
     * {@code EXISTS} = 判定已有、跳过下载；{@code UNKNOWN} = 无法确认、跳过下载；
     * {@code ABSENT} = 确认没有、已下发下载。
     * <p>
     * 由下载主流程调用，故收拢在此（{@code RssJobState} 不对外暴露）。
     */
    public static void countRoundLocalState(DownloadService.LocalState state) {
        if (state == null) {
            return;
        }
        switch (state) {
            case EXISTS -> RssJobState.localExists.incrementAndGet();
            case UNKNOWN -> RssJobState.localUnknown.incrementAndGet();
            default -> RssJobState.localAbsent.incrementAndGet();
        }
    }

    /**
     * 本轮本地状态分布快照：{@code {exists, unknown, absent}}。
     */
    public static Map<String, Integer> getRoundLocalStateSummary() {
        return Map.of(
                "exists", RssJobState.localExists.get(),
                "unknown", RssJobState.localUnknown.get(),
                "absent", RssJobState.localAbsent.get());
    }

    /**
     * F5-6：累计本轮「存疑」的成因。
     * <p>
     * 只说"有 12 条无法确认"帮不上忙——列举失败要等网盘恢复、超预算要调预算或减订阅、
     * 索引不完整要调 {@code cloudListMaxFiles}、正在改名只要等一会儿。
     * 分开计数，用户才知道该做什么。
     */
    public static void countRoundUnknownReason(DownloadService.UnknownReason reason) {
        if (reason == null) {
            return;
        }
        switch (reason) {
            case VERIFY_FAILED -> RssJobState.unknownVerifyFailed.incrementAndGet();
            case BUDGET_EXHAUSTED -> RssJobState.unknownBudgetExhausted.incrementAndGet();
            case INDEX_INCOMPLETE -> RssJobState.unknownIncomplete.incrementAndGet();
            case DOWNLOADING -> RssJobState.unknownDownloading.incrementAndGet();
            default -> {
            }
        }
    }

    /**
     * 本轮存疑成因分布快照
     */
    public static Map<String, Integer> getRoundUnknownReasonSummary() {
        return Map.of(
                "verifyFailed", RssJobState.unknownVerifyFailed.get(),
                "budgetExhausted", RssJobState.unknownBudgetExhausted.get(),
                "indexIncomplete", RssJobState.unknownIncomplete.get(),
                "downloading", RssJobState.unknownDownloading.get());
    }

    /**
     * 记录一条订阅级失败明细（供任务管理器展示，最多保留 {@code FAILED_SUBSCRIPTION_MAX} 条）。
     */
    private static void recordSubscriptionFailure(Ani ani, String message) {
        try {
            String raw = StrUtil.blankToDefault(message, "未知错误");
            String title = "任务异常";
            String suggestion = "";
            try {
                var h = TaskFailureHumanizer.humanize(raw);
                title = StrUtil.blankToDefault(h.title(), title);
                suggestion = StrUtil.blankToDefault(h.suggestion(), "");
            } catch (Exception ignored) {
                // 归因失败不影响主流程
            }

            List<RssJobStatus.FailedSubscription> list = RssJobState.failedSubscriptions;
            if (list.size() >= RssJobState.FAILED_SUBSCRIPTION_MAX) {
                list.remove(0);
            }
            list.add(new RssJobStatus.FailedSubscription()
                    .setAniId(ani == null ? null : ani.getId())
                    .setTitle(ani == null ? null : ani.getTitle())
                    .setStage("rss")
                    .setHumanizedMessage(title)
                    .setSuggestion(suggestion)
                    .setRawMessage(raw)
                    .setAt(System.currentTimeMillis()));
        } catch (Exception e) {
            log.debug("记录订阅失败明细失败: {}", e.getMessage());
        }
    }

    /**
     * 正在发生的异常也做人话化，而不是把英文堆栈原文丢给用户。
     * <p>
     * 此前只有「上一轮结果」与失败队列走了 {@link TaskFailureHumanizer}，
     * 运行中的 jobMessage 是 {@code "异常: " + rawMessage}，用户看到的是
     * Connection reset / 各类英文异常；这里统一归因 + 给出下一步建议，
     * 原始信息仍完整写进日志（上面已 log.error）便于排查。
     */
    private static String humanizeRunningError(String message) {
        String raw = StrUtil.blankToDefault(message, "未知错误");
        try {
            var h = TaskFailureHumanizer.humanize(raw);
            String title = StrUtil.blankToDefault(h.title(), "任务异常");
            String suggestion = StrUtil.blankToDefault(h.suggestion(), "");
            return suggestion.isEmpty() ? ("异常：" + title) : ("异常：" + title + " — " + suggestion);
        } catch (Exception e) {
            // 人话化失败不能影响主流程
            log.debug("任务异常人话化失败: {}", e.getMessage());
            return "异常：" + raw;
        }
    }
}
