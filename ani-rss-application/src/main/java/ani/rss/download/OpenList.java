package ani.rss.download;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.*;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.StringEnum;
import ani.rss.task.RssTask;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.FailedDownloadQueue;
import ani.rss.util.other.NotificationUtil;
import ani.rss.util.other.RenameUtil;
import ani.rss.util.other.TaskFailureHumanizer;
import ani.rss.util.other.TempDirResidualPolicy;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.Method;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenList implements BaseDownload, OfflineDownloader {
    private Config config;

    /**
     * 网盘 API 客户端（认证/限流/fs/task 接口），从本类拆出
     */
    private final OpenListApi api = new OpenListApi();

    /**
     * 独立长任务池：OpenList 离线等待（最长 60 分钟）在此执行，不再占用 RSS 主线程池。
     * daemon 线程：JVM 退出不阻塞；并发离线任务数 2~4。
     */
    private static final ExecutorService OFFLINE_WAIT_POOL = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)),
            runnable -> {
                Thread thread = new Thread(runnable, "openlist-offline-wait");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 提交中去重：防止同一 infoHash 被重复提交到 OpenList
     */
    private static final Set<String> inFlightTasks = ConcurrentHashMap.newKeySet();
    // 按 infoHash 串行，不同 hash 可并行
    private static final ConcurrentHashMap<String, Object> DOWNLOAD_LOCKS = new ConcurrentHashMap<>();
    /** 当前正在等待的离线 hash 集合（任务管理器展示 / 取消清理 / 残留保护）；多 hash 并行时需记录全部 */
    private static final Set<String> currentInfoHashes = ConcurrentHashMap.newKeySet();
    /** 当前离线等待进度（任务管理器） */
    private static final AtomicReference<OfflineWaitSnapshot> offlineWaitSnapshot = new AtomicReference<>();
    /** 用户取消时置位，打断 sleep 与轮询 */
    private static final AtomicBoolean offlineCancelRequested = new AtomicBoolean(false);
    /** 进程内仅启动回扫一次（login 成功后异步） */
    private static final AtomicBoolean startupResidualScanned = new AtomicBoolean(false);
    /** 最近一次残留快照（任务管理器展示） */
    private static final AtomicReference<ResidualSnapshot> residualSnapshot = new AtomicReference<>(ResidualSnapshot.empty());
    /** 清理中互斥，避免并发 purge 打爆 API */
    private static final AtomicBoolean residualCleaning = new AtomicBoolean(false);
    /** 文件系统临时目录残留（与离线任务残留分离） */
    private static final AtomicReference<TempDirResidualSnapshot> tempDirResidualSnapshot =
            new AtomicReference<>(TempDirResidualSnapshot.empty());
    private static final AtomicBoolean tempDirResidualCleaning = new AtomicBoolean(false);
    private static final int TEMP_DIR_PREVIEW_LIMIT = 30;
    /** 单次扫描最多深度检查的候选临时目录数，避免订阅极多时打爆 OpenList */
    private static final int TEMP_DIR_INSPECT_BUDGET = 120;

    /**
     * 提交成功后的分级轮询间隔：20s -> 1min -> 5min -> 10min
     * 避免 2s 打爆 OpenList API。
     */
    private static final long POLL_INTERVAL_20S_MS = TimeUnit.SECONDS.toMillis(20);
    private static final long POLL_INTERVAL_1M_MS = TimeUnit.MINUTES.toMillis(1);
    private static final long POLL_INTERVAL_5M_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long POLL_INTERVAL_10M_MS = TimeUnit.MINUTES.toMillis(10);

    /**
     * 列出网盘目录下文件路径(递归, 60s 缓存), 供"本地已下载"判断使用。
     * 下载目录是网盘虚拟路径(本地文件系统不可见), 需通过 API 检查文件真实存在。
     */
    public List<String> listFileNames(String dirPath) {
        return api.listFileNames(dirPath);
    }

    private static final long TIMEOUT_FILE_STABILITY_WAIT_MS = 2000L;

    /**
     * 115/OpenList 返回「任务已存在(10008)」后，短时间内禁止对同一 magnet 再 add。
     * 避免 DownloadService 外层 10 次重试把 115 打爆并误报坏种。
     */
    private static final long DUPLICATE_MAGNET_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(30);
    private static final ConcurrentHashMap<String, Long> DUPLICATE_MAGNET_UNTIL = new ConcurrentHashMap<>();

    /**
     * 10008 重提达上限后进入长冷却：115 云端对该 hash 存在去重记录，
     * 且 AList 侧无任务可删（task/info 404 / 任务列表无记录），删除重提无效。
     * 冷却期间跳过提交与等待，提示用户手动清理。
     */
    private static final long DUPLICATE_MAGNET_LONG_COOLDOWN_MS = TimeUnit.HOURS.toMillis(24);
    private static final ConcurrentHashMap<String, Long> DUPLICATE_MAGNET_LONG_UNTIL = new ConcurrentHashMap<>();

    /**
     * 卡住任务（115 中存在但不下载）单轮流程内最多删除并重新提交的次数。
     * 超过上限后按原有失败逻辑收尾，避免无限循环。
     */
    private static final int MAX_STUCK_RESUBMIT = 2;

    /**
     * 本次新提交任务无任何进度变化达到该时长，判定为卡住，触发删除+重新提交。
     */
    private static final long STALL_DETECT_MS = TimeUnit.MINUTES.toMillis(10);

    @Override
    public boolean isOffline() {
        return true;
    }

    @Override
    public Boolean login(Boolean test, Config config) {
        this.config = config;
        api.setConfig(config);
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        if (StrUtil.isBlank(host) || StrUtil.isBlank(password)) {
            log.warn("OpenList 未配置完成");
            return false;
        }
        String downloadPath = config.getDownloadPathTemplate();
        Assert.notBlank(downloadPath, "未设置下载位置");
        String provider = config.getProvider();
        Assert.notBlank(provider, "请选择 Driver");
        try {
            return postApi("me")
                    .setMethod(Method.GET)
                    .thenFunction(res -> {
                        if (!res.isOk()) {
                            log.error("登录 OpenList 失败");
                            return false;
                        }
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        if (jsonObject.get("code").getAsInt() != 200) {
                            log.error("登录 OpenList 失败");
                            return false;
                        }
                        // 非测试登录：异步启动回扫。失败不影响登录成功。
                        if (!Boolean.TRUE.equals(test)) {
                            scheduleStartupResidualScan();
                        }
                        return true;
                    });
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(e.getMessage(), e);
            log.error("登录 OpenList 失败 {}", message);
        }
        return false;
    }


    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        return List.of();
    }

    @Override
    public Boolean download(Ani ani, Item item, String savePath, File torrentFile) {
        savePath = ReUtil.replaceAll(savePath, "^[A-z]:", "");

        String magnet = TorrentUtil.getMagnet(torrentFile);
        String infoHash = ReUtil.get(StringEnum.MAGNET_REG, magnet, 1);
        if (StrUtil.isBlank(infoHash) && ReUtil.contains(StringEnum.ED2K_REG, magnet)) {
            infoHash = ReUtil.get(StringEnum.ED2K_REG, magnet, 3);
        }
        // P0: hash 为空直接失败，避免 null 撞 inFlight 假成功
        if (StrUtil.isBlank(infoHash)) {
            log.error("无法解析 infoHash，拒绝提交: {}", item.getReName());
            return false;
        }
        infoHash = infoHash.toLowerCase();
        final String hashKey = infoHash;

        Object lock = DOWNLOAD_LOCKS.computeIfAbsent(hashKey, k -> new Object());
        // 不回收锁对象：避免等待线程与新线程拿到不同 lock 导致同 hash 并行
        synchronized (lock) {
            OfflineDownloadContext ctx = submitOffline(ani, item, savePath, magnet, hashKey);
            if (ctx.shortCircuit) {
                return ctx.shortCircuitResult;
            }
            // 提交即受理：等待+提升+失败处理移交独立长任务池，不再占用 RSS 主线程池；
            // pending 标记保持到离线真正完成，预览不会误判"已下载"；
            // 取消经 offlineCancelRequested/RssTask.isCancelRequested 由后台任务自行响应
            final String finalSavePath = savePath;
            try {
                OFFLINE_WAIT_POOL.submit(() -> finalizeOfflineDownload(ctx, ani, item, finalSavePath));
            } catch (Exception e) {
                // 池关闭/拒绝（极低概率）：释放占位，避免同 hash 死等
                log.error("提交离线等待任务失败 {}: {}", item.getReName(), ExceptionUtils.getMessage(e));
                releaseOfflinePlaceholder(hashKey, ctx.tid, ctx.claimedInFlight,
                        config.getDelete(), ctx.newlySubmittedTid);
                return false;
            }
            return true;
        }
    }

    /**
     * 后台完成离线下载：等待结束后提升 pending / 记录失败（原 DownloadService 同步流程迁移）。
     * 在独立长任务池执行，最长等待至离线超时。
     */
    private void finalizeOfflineDownload(OfflineDownloadContext ctx, Ani ani, Item item, String savePath) {
        try {
            Boolean ok = awaitAndFinalize(ctx, ani, item, savePath);
            if (Boolean.TRUE.equals(ok)) {
                try {
                    TorrentUtil.promoteTorrent(ani, item);
                } catch (Exception e) {
                    // 文件可能已落盘, 下轮 itemDownloaded 会恢复记录
                    log.error("提升种子记录失败(文件可能已落盘) {}: {}", ctx.reName, ExceptionUtils.getMessage(e));
                    try {
                        TorrentUtil.deletePendingTorrent(ani, item);
                    } catch (Exception ignored) {
                    }
                }
                TorrentUtil.refreshTorrentsCache();
                return;
            }
            if (offlineCancelRequested.get() || RssTask.isCancelRequested()) {
                // 用户取消：仅清 pending，不误发"下载失败"通知/不记失败队列
                log.info("离线下载被用户取消，清理 pending {}", ctx.reName);
                try {
                    TorrentUtil.deletePendingTorrent(ani, item);
                } catch (Exception ignored) {
                }
                return;
            }
            handleOfflineFailure(ani, item, ctx.reName, "离线下载未完成（OpenList 返回失败，非坏种）");
        } catch (ani.rss.download.OfflineTimeoutException e) {
            handleOfflineFailure(ani, item, ctx.reName, ExceptionUtils.getMessage(e));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            handleOfflineFailure(ani, item, ctx.reName, "离线下载异常: " + ExceptionUtils.getMessage(e));
        }
    }

    /**
     * 离线失败收尾：失败队列 + 错误通知 + 清除 pending（与 DownloadService 同步流程一致）
     */
    private void handleOfflineFailure(Ani ani, Item item, String name, String reason) {
        // OfflineTimeoutException 的 message 已包含 reName，避免重复拼接（历史日志曾出现番剧名 ×2）
        String raw = StrUtil.isNotBlank(reason) && reason.startsWith(name + " ")
                ? reason
                : name + " " + StrUtil.blankToDefault(reason, "离线下载失败");
        log.error(raw);
        try {
            FailedDownloadQueue.record(
                    ani == null ? null : ani.getId(),
                    ani == null ? null : ani.getTitle(),
                    item == null ? null : item.getReName(),
                    item == null ? null : item.getInfoHash(),
                    raw);
        } catch (Exception e) {
            log.debug("记录失败队列失败: {}", e.getMessage());
        }
        try {
            NotificationUtil.send(ConfigUtil.CONFIG, ani,
                    TaskFailureHumanizer.formatNotify(name, raw),
                    NotificationStatusEnum.ERROR);
        } catch (Exception e) {
            log.debug("发送失败通知异常: {}", e.getMessage());
        }
        try {
            TorrentUtil.deletePendingTorrent(ani, item);
        } catch (Exception ignored) {
        }
    }

    /**
     * 离线下载上下文：submitOffline 与 awaitAndFinalize 之间的共享状态。
     * 拆分的目的是为「提交即返回 + 独立长任务池等待」提供接缝（离线等待不应占满 RSS 主线程池）。
     */
    private static final class OfflineDownloadContext {
        final String infoHash;
        final String reName;
        final String finalRenameBase;
        final String tempDirName;
        final String path;
        final String tempDownloadDir;
        final boolean isCollection;
        final boolean skipNewSubmit;
        final int waitMinutes;
        final long deadlineMs;
        /** 原始磁力链接：卡住任务删除后重新提交使用 */
        final String magnet;

        String tid;            // 可变：10008 时切换/清空
        long retry;
        boolean claimedInFlight;
        /** 本次是否新提交的离线任务：delete=true 收尾时仅删除新提交任务，复用任务不远程删除（避免中断他人/历史任务） */
        boolean newlySubmittedTid;
        boolean shortCircuit;          // 提交阶段已决定结果（无需进入等待）
        Boolean shortCircuitResult;
        /** 本流程内卡住重提次数（上限 MAX_STUCK_RESUBMIT） */
        int resubmitCount;
        /** 无进度卡住检测：首次观测时间（0=未开始）与上次进度 */
        long stallStartMs;
        int lastProgress;

        OfflineDownloadContext(String infoHash, String reName, String finalRenameBase, String tempDirName,
                               String path, String tempDownloadDir, boolean isCollection, boolean skipNewSubmit,
                               int waitMinutes, long deadlineMs, String magnet) {
            this.infoHash = infoHash;
            this.reName = reName;
            this.finalRenameBase = finalRenameBase;
            this.tempDirName = tempDirName;
            this.path = path;
            this.tempDownloadDir = tempDownloadDir;
            this.isCollection = isCollection;
            this.skipNewSubmit = skipNewSubmit;
            this.waitMinutes = waitMinutes;
            this.deadlineMs = deadlineMs;
            this.magnet = magnet;
        }
    }

    /**
     * 提交阶段：目录准备、残留复用、离线任务提交（不等待）。
     * 返回上下文；若提交阶段已能确定结果（同 hash 等待/失败），置 shortCircuit。
     */
    private OfflineDownloadContext submitOffline(Ani ani, Item item, String savePath,
                                                 String magnet, String infoHash) {
        // 最终重命名始终使用订阅模板结果；合集临时目录可单独使用源标题
        String finalRenameBase = item.getReName();
        String reName = finalRenameBase;
        String tempDirName = finalRenameBase;

        boolean isCollection = item.getEpisodeRange() != null && !item.getEpisodeRange().isEmpty();
        if (isCollection) {
            String collectionName = item.getTitle();
            // 清理标题中的路径分隔符，取第一层文件夹名
            collectionName = collectionName.replace("\\", "/");
            if (collectionName.contains("/")) {
                collectionName = collectionName.substring(0, collectionName.indexOf("/"));
            }
            collectionName = RenameUtil.getName(collectionName);
            if (StrUtil.isNotBlank(collectionName)) {
                tempDirName = collectionName;
            }
            log.info("合集下载，使用原始标题作为临时目录: {}，最终命名仍使用模板: {}", tempDirName, finalRenameBase);
        }

        // 下载位置：与 savePath 不同则为临时目录，移动后需清理
        String path = savePath + "/" + tempDirName;
        String tempDownloadDir = path.equals(savePath) ? null : path;
        Boolean standbyRss = config.getStandbyRss();
        Boolean delete = config.getDelete();
        Boolean coexist = config.getCoexist();

        String tid = null;
        boolean claimedInFlight = false;
        boolean newlySubmittedTid = false;
        try {
            // 提交中去重：防止同一 infoHash 被重复提交
            if (!inFlightTasks.add(infoHash)) {
                // 等待持有方结束，返回 true 避免 DownloadService 删种子
                log.info("infoHash 正在处理中，等待其完成 {}", reName);
                Integer waitMinutes = ObjectUtil.defaultIfNull(config.getAlistDownloadTimeout(), 30);
                waitMinutes = Math.max(waitMinutes, 1);
                long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(waitMinutes);
                int waitPoll = 0;
                while (inFlightTasks.contains(infoHash)
                        && System.currentTimeMillis() < deadline) {
                    if (shouldAbortWait()) {
                        log.warn("等待同 hash 任务被用户取消 {}", reName);
                        return shortCircuitResult(false);
                    }
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) {
                        break;
                    }
                    long sleepMs = Math.min(nextPollIntervalMs(waitPoll++), remain);
                    long end = System.currentTimeMillis() + sleepMs;
                    while (System.currentTimeMillis() < end) {
                        if (shouldAbortWait()) {
                            log.warn("等待同 hash 任务被用户取消 {}", reName);
                            return shortCircuitResult(false);
                        }
                        long slice = Math.min(1000L, end - System.currentTimeMillis());
                        if (slice <= 0) break;
                        ThreadUtil.sleep(slice);
                    }
                }
                if (inFlightTasks.contains(infoHash)) {
                    log.error("等待同 hash 任务超过离线超时 {} 分钟，放弃 {}", waitMinutes, reName);
                    throw new OfflineTimeoutException(reName + " 等待同 hash 任务超过离线超时 " + waitMinutes + " 分钟");
                } else {
                    log.info("同 hash 任务已结束 {}", reName);
                    // 等待方未实际提交下载: 清除本订阅的 pending 标记,
                    // 避免 DownloadService 收到 true 后将其提升为正式记录(假完成, 导致永久漏下)
                    TorrentUtil.deletePendingTorrent(ani, item);
                }
                return shortCircuitResult(true);
            }
            claimedInFlight = true;
            currentInfoHashes.add(infoHash);
            offlineCancelRequested.set(false);
            int waitMinutesInit = Math.max(ObjectUtil.defaultIfNull(config.getAlistDownloadTimeout(), 30), 1);
            updateOfflineWait(infoHash, reName, tempDirName, null, "Pending",
                    System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(waitMinutesInit));

            mkdir(path);

            // P0: 只清理失败/取消/成功残留；Running/Pending 复用 tid，不重提
            String existingTid = adoptOrCleanResidualTasks(infoHash);
            if (StrUtil.isNotBlank(existingTid)) {
                tid = existingTid;
                log.info("复用进行中的离线任务 tid={} {}", tid, reName);
            }

            boolean skipNewSubmit = isDuplicateMagnetCooling(infoHash);
            if (skipNewSubmit && StrUtil.isBlank(tid)) {
                if (isDuplicateMagnetLongCooling(infoHash)) {
                    // 115 云端 hash 去重残留（10008 重提仍失败）：24h 长冷却，快速失败不再等待
                    String activeTid = findExistingTaskIdPreferActive(infoHash);
                    if (StrUtil.isNotBlank(activeTid)
                            && taskInfo(activeTid).map(t -> isActiveState(t.getState())).orElse(false)) {
                        // 用户已手动处理（出现进行中任务）：解除长冷却，正常等待
                        clearDuplicateMagnetLong(infoHash);
                        tid = activeTid;
                        log.info("检测到进行中任务，解除 10008 长冷却，等待完成 {}", reName);
                    } else {
                        log.warn("磁力处于 10008 长冷却（24h），跳过提交与等待；"
                                + "请到 115/AList 手动清理该 hash 的历史离线任务后重试 {} hash={}",
                                reName, infoHash);
                        return shortCircuitResult(false);
                    }
                } else {
                    log.warn("磁力近期已报 10008/任务已存在，跳过重复提交，仅等待文件 {}", reName);
                    tid = findExistingTaskIdPreferActive(infoHash);
                }
            }

            // 洗版：仅在即将新提交离线时做一次；复用/10008 等待路径禁止洗，避免重试风暴删掉目标
            if (!skipNewSubmit && StrUtil.isBlank(tid) && standbyRss && delete && !coexist) {
                String s = ReUtil.get(StringEnum.SEASON_REG, finalRenameBase, 0);
                if (StrUtil.isNotBlank(s)) {
                    String finalSavePath = savePath;
                    String seasonKey = s;
                    // 本次任务目录（刚 mkdir 创建）：必须排除，否则洗版会把刚创建的任务目录当旧文件删掉，
                    // 导致 115 离线任务落点目录被删 → 任务 Failed → 下轮提交被 10008 挡住 → 死循环
                    String taskDirPath = trimTrailingSlash(path);
                    String currentDirName = taskDirPath.substring(taskDirPath.lastIndexOf('/') + 1);
                    try {
                        fsList(savePath, true)
                                .stream()
                                .map(OpenListFileInfo::getName)
                                .filter(name -> isWashTarget(name, seasonKey, currentDirName))
                                .forEach(name -> {
                                    fsRemove(finalSavePath, List.of(name));
                                    log.info("已开启备用RSS, 自动删除 {}/{}", finalSavePath, name);
                                });
                    } catch (Exception e) {
                        // 洗版是辅助操作：删除失败（115 异步 990009/超时等）仅告警，不得中断本次下载
                        log.warn("洗版删除旧文件失败(不影响本次下载) {}/{}: {}",
                                finalSavePath, seasonKey, ExceptionUtils.getMessage(e));
                    }
                }
            }

            // 无进行中任务时才提交离线
            if (StrUtil.isBlank(tid) && !skipNewSubmit) {
                tid = fsAddOfflineDownload(magnet, path);
                if (StrUtil.isNotBlank(tid)) {
                    newlySubmittedTid = true;
                }
            }

            // API 级 10008：tid==null；任务级 10008 在轮询中识别
            if (tid == null) {
                markDuplicateMagnet(infoHash);
                Optional<OpenListFileInfo> existing = findFiles(path).stream()
                        .filter(f -> FileUtils.isVideoFormat(f.getName()))
                        .findFirst();
                if (existing.isPresent()) {
                    log.info("离线任务已存在且文件已下载，跳过 {}", reName);
                    clearDuplicateMagnet(infoHash);
                } else {
                    log.warn("离线任务已存在但文件未就绪，等待完成 {}", reName);
                    tid = findExistingTaskIdPreferActive(infoHash);
                }
            } else {
                log.info("添加/复用离线下载成功 tid={} {}", tid, reName);
            }

            Integer alistDownloadTimeout = config.getAlistDownloadTimeout();
            Long alistDownloadRetryNumber = config.getAlistDownloadRetryNumber();
            DateTime startTime = DateTime.now();
            // 唯一截止：用户配置的【离线超时】
            int waitMinutes = ObjectUtil.defaultIfNull(alistDownloadTimeout, 30);
            waitMinutes = Math.max(waitMinutes, 1);
            long deadlineMs = DateUtil.offsetMinute(startTime, waitMinutes).getTime();
            if (skipNewSubmit) {
                log.info("10008 冷却期内不重复提交，按离线超时 {} 分钟等待文件/任务 {}", waitMinutes, reName);
            }

            OfflineDownloadContext ctx = new OfflineDownloadContext(
                    infoHash, reName, finalRenameBase, tempDirName,
                    path, tempDownloadDir, isCollection, skipNewSubmit,
                    waitMinutes, deadlineMs, magnet);
            ctx.tid = tid;
            ctx.claimedInFlight = claimedInFlight;
            ctx.newlySubmittedTid = newlySubmittedTid;
            ctx.retry = 0;
            return ctx;
        } catch (OfflineTimeoutException e) {
            // 超时必须向上抛给 finalizeOfflineDownload（后台任务），避免被当成普通 false/坏种
            throw e;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            releaseOfflinePlaceholder(infoHash, tid, claimedInFlight, delete, newlySubmittedTid);
            return shortCircuitResult(false);
        }
    }

    /**
     * 收尾时是否远程删除离线任务（纯函数，便于单测）：
     * 仅当 delete=true 且 tid 是本次新提交时才删除；复用/10008 切换的任务保留（可能属他人/历史轮次）。
     */
    static boolean shouldDeleteTidOnRelease(String tid, boolean newlySubmittedTid, Boolean delete) {
        return tid != null && newlySubmittedTid && Boolean.TRUE.equals(delete);
    }

    /**
     * 释放 inFlight 占位与离线等待快照（提交阶段异常 / 等待阶段收尾共用）
     */
    private void releaseOfflinePlaceholder(String infoHash, String tid, boolean claimedInFlight,
                                           Boolean delete, boolean newlySubmittedTid) {
        // 仅清理本线程占用的 inFlight，避免误删其它线程标记
        if (claimedInFlight) {
            inFlightTasks.remove(infoHash);
            currentInfoHashes.remove(infoHash);
            clearOfflineWait(infoHash);
        }
        // 配置要求删除时清理离线任务记录（超时 purge 已处理同 hash）；
        // 仅删除本次新提交的任务；复用的既有任务保留（可能属其它会话/历史轮次，避免中断对方下载）
        if (shouldDeleteTidOnRelease(tid, newlySubmittedTid, delete)) {
            try {
                taskDelete(tid);
            } catch (Exception e) {
                log.warn("删除离线任务失败 {}: {}", tid, e.getMessage());
            }
        }
    }

    private OfflineDownloadContext shortCircuitResult(Boolean result) {
        OfflineDownloadContext ctx = new OfflineDownloadContext("", "", "", "", "", "", false, false, 1, 0, null);
        ctx.shortCircuit = true;
        ctx.shortCircuitResult = result;
        return ctx;
    }

    /**
     * 任务状态是否进行中（Pending/Running/Waiting_for_Retry/Preparing_to_Retry）
     */
    static boolean isActiveState(OpenListTaskInfo.State state) {
        return state == OpenListTaskInfo.State.Pending
                || state == OpenListTaskInfo.State.Running
                || state == OpenListTaskInfo.State.Waiting_for_Retry
                || state == OpenListTaskInfo.State.Preparing_to_Retry;
    }

    /**
     * 无进度卡住检测：本次新提交任务进度长时间无变化 → 删除并重新提交。
     * 记录首次观测时间与进度，进度变化则重置观测窗口。
     *
     * @return true 表示已删除并重新提交（调用方应 continue 重新轮询）
     */
    private boolean detectStallAndResubmit(OfflineDownloadContext ctx, OpenListTaskInfo taskInfo) {
        Integer progress = taskInfo.getProgress();
        StallProbe probe = probeStall(ctx.lastProgress, progress, ctx.stallStartMs,
                System.currentTimeMillis(), STALL_DETECT_MS);
        ctx.stallStartMs = probe.stallStartMs();
        ctx.lastProgress = probe.lastProgress();
        if (!probe.shouldResubmit()) {
            return false;
        }
        log.warn("离线任务无进度疑似卡住 state={} progress={}，删除并重新提交 {}",
                taskInfo.getState(), progress, ctx.reName);
        return resubmitStuckTask(ctx, "无进度卡住");
    }

    /**
     * 卡住任务处理：删除当前任务并重新提交同一磁力，重置卡住观测窗口。
     * 受 MAX_STUCK_RESUBMIT 上限约束，超限返回 false。
     *
     * @return true 表示已成功重新提交（ctx.tid 已更新为新任务）
     */
    /**
     * 无进度卡住观测结果（probeStall 纯函数输出）
     */
    record StallProbe(long stallStartMs, int lastProgress, boolean shouldResubmit) {
    }

    /**
     * 无进度卡住判定（纯函数，便于单测）：根据观测窗口与进度变化决定是否触发重提。
     * 规则与 detectStallAndResubmit 原实现完全一致：
     * - progress 为 null：115 未返回进度（排队中/API 未支持），重置窗口不重提
     * - 窗口未开始（stallStartMs==0）：开始观测不重提
     * - 进度有变化：有进展，重置窗口不重提
     * - 无变化且未超过窗口：保持观测不重提
     * - 无变化且已超过窗口：触发重提
     */
    static StallProbe probeStall(int lastProgress, Integer progress, long stallStartMs,
                                 long now, long stallDetectMs) {
        if (progress == null) {
            return new StallProbe(now, -1, false);
        }
        if (stallStartMs == 0L) {
            return new StallProbe(now, progress, false);
        }
        if (progress != lastProgress) {
            return new StallProbe(now, progress, false);
        }
        if (now - stallStartMs < stallDetectMs) {
            return new StallProbe(stallStartMs, lastProgress, false);
        }
        return new StallProbe(stallStartMs, lastProgress, true);
    }

    /**
     * 卡住任务是否允许重新提交：未达重提上限且有可用磁力（纯函数，便于单测）
     */
    static boolean canResubmitStuckTask(int resubmitCount, String magnet) {
        return resubmitCount < MAX_STUCK_RESUBMIT && StrUtil.isNotBlank(magnet);
    }

    /**
     * 卡住重提已达上限且为 10008 语义：判定是否进入长冷却（纯函数，便于单测）。
     * 仅 10008（115 云端 hash 去重残留、AList 侧无任务可删）需要长冷却；
     * 终态失败/无进度卡住等场景重提失败是种子/网络问题，不应长冷却。
     */
    static boolean isStuckResubmitExhaustedAndDuplicate(int resubmitCount, String reason) {
        return resubmitCount >= MAX_STUCK_RESUBMIT && reason != null && reason.contains("10008");
    }

    private boolean resubmitStuckTask(OfflineDownloadContext ctx, String reason) {
        if (!canResubmitStuckTask(ctx.resubmitCount, ctx.magnet)) {
            if (ctx.resubmitCount >= MAX_STUCK_RESUBMIT) {
                log.warn("卡住重提次数已达上限 {}，放弃重提 {}", ctx.resubmitCount, ctx.reName);
                if (isStuckResubmitExhaustedAndDuplicate(ctx.resubmitCount, reason)) {
                    // 10008 重提仍失败：115 云端对该 hash 有去重记录且 AList 侧无任务可删，
                    // 进入 24h 长冷却，避免每轮 RSS 反复提交消耗 115 配额
                    markDuplicateMagnetLong(ctx.infoHash);
                    log.warn("115 云端存在该 hash 重复记录且重提仍 10008，进入 24h 长冷却；"
                            + "请到 115/AList 手动清理该 hash 的历史离线任务后重试 hash={}", ctx.infoHash);
                }
            } else {
                log.warn("无法重提（无磁力） {}", ctx.reName);
            }
            return false;
        }
        ctx.resubmitCount++;
        // 删除卡住的旧任务（终态直接删；进行中先 cancel 再 delete）
        String oldTid = ctx.tid;
        if (StrUtil.isNotBlank(oldTid)) {
            OpenListTaskInfo.State oldState = taskInfo(oldTid)
                    .map(OpenListTaskInfo::getState).orElse(null);
            if (isActiveState(oldState)) {
                try {
                    taskCancel(oldTid);
                } catch (Exception cancelEx) {
                    log.debug("取消卡住任务失败 {}: {}", oldTid, cancelEx.getMessage());
                }
            }
            try {
                taskDelete(oldTid);
            } catch (Exception deleteEx) {
                // 删除失败可能导致同 hash 双任务：告警，靠重提后再次轮询兜底
                log.warn("删除卡住任务失败 {}: {}", oldTid, deleteEx.getMessage());
            }
        }
        // 重提前清除 10008 冷却，允许立即重新提交
        clearDuplicateMagnet(ctx.infoHash);
        try {
            String newTid = fsAddOfflineDownload(ctx.magnet, ctx.path);
            if (StrUtil.isNotBlank(newTid)) {
                ctx.tid = newTid;
                ctx.newlySubmittedTid = true;
                // 重置卡住观测，给新任务完整窗口
                ctx.stallStartMs = 0L;
                ctx.lastProgress = -1;
                log.info("卡住任务已删除并重新提交 tid={} (第{}/{}) {} reason={}",
                        newTid, ctx.resubmitCount, MAX_STUCK_RESUBMIT, ctx.reName, reason);
                return true;
            }
            // 重新提交仍 10008/空 tid：恢复冷却防打爆，进入无 tid 文件轮询等待
            markDuplicateMagnet(ctx.infoHash);
            ctx.tid = null;
            ctx.newlySubmittedTid = false;
            ctx.stallStartMs = 0L;
            ctx.lastProgress = -1;
            log.warn("卡住任务重新提交未返回 tid，转为等待文件 {} reason={}", ctx.reName, reason);
            return false;
        } catch (Exception e) {
            markDuplicateMagnet(ctx.infoHash);
            ctx.tid = null;
            ctx.newlySubmittedTid = false;
            ctx.stallStartMs = 0L;
            ctx.lastProgress = -1;
            log.warn("卡住任务重新提交异常 {}: {}", ctx.reName, ExceptionUtils.getMessage(e));
            return false;
        }
    }

    /**
     * 等待阶段 + 后处理：等待离线完成（可取消/超时/重试），完成后重命名/移动/校验。
     * 与 submitOffline 分离，为「提交即返回 + 独立长任务池等待」提供接缝。
     */
    private Boolean awaitAndFinalize(OfflineDownloadContext ctx, Ani ani, Item item, String savePath) {
        String reName = ctx.reName;
        String finalRenameBase = ctx.finalRenameBase;
        String tempDirName = ctx.tempDirName;
        String path = ctx.path;
        String tempDownloadDir = ctx.tempDownloadDir;
        boolean isCollection = ctx.isCollection;
        String infoHash = ctx.infoHash;
        long deadlineMs = ctx.deadlineMs;
        int waitMinutes = ctx.waitMinutes;
        int pollIndex = 0;
        // tid 会随 10008 切换，需写回 ctx 供 finally 清理使用
        String tid = ctx.tid;
        long retry = ctx.retry;
        Long alistDownloadRetryNumber = config.getAlistDownloadRetryNumber();
        Boolean delete = config.getDelete();
        boolean claimedInFlight = ctx.claimedInFlight;
        try {
            while (DateTime.now().getTime() < deadlineMs) {
                if (shouldAbortWait()) {
                    log.warn("离线等待被用户取消 {}", reName);
                    try {
                        purgeHashTasks(infoHash);
                    } catch (Exception purgeEx) {
                        log.warn("取消清理 OpenList 失败 {}: {}", infoHash, purgeEx.getMessage());
                    }
                    clearDuplicateMagnet(infoHash);
                    return false;
                }
                // 卡住重提会更新 ctx.tid，循环顶部同步局部 tid
                tid = ctx.tid;
                if (tid != null) {
                    Optional<OpenListTaskInfo> taskInfoOpt = taskInfo(tid);
                    if (taskInfoOpt.isEmpty()) {
                        // 避免 taskInfo 空响应时 tight loop
                        sleepUntilNextPoll(deadlineMs, pollIndex++);
                        continue;
                    }

                    OpenListTaskInfo taskInfo = taskInfoOpt.get();
                    OpenListTaskInfo.State state = taskInfo.getState();
                    OpenListTaskInfo.RetryPolicy policy = state.getRetryPolicy();
                    updateOfflineWait(infoHash, reName, tempDirName, taskInfo.getProgress(),
                            state == null ? null : state.name(), deadlineMs);

                    if (policy == OpenListTaskInfo.RetryPolicy.SUCCESS) {
                        clearDuplicateMagnet(infoHash);
                        break;
                    }
                    if (policy == OpenListTaskInfo.RetryPolicy.NO_RETRY) {
                        log.error("离线任务不可重试 {} state={} error={}", reName, state, taskInfo.getError());
                        return false;
                    }

                    // Pending/Running：等待；本次新提交任务做无进度卡住检测
                    if (state == OpenListTaskInfo.State.Pending
                            || state == OpenListTaskInfo.State.Running
                            || state == OpenListTaskInfo.State.Waiting_for_Retry
                            || state == OpenListTaskInfo.State.Preparing_to_Retry) {
                        if (ctx.newlySubmittedTid
                                && ctx.resubmitCount < MAX_STUCK_RESUBMIT
                                && detectStallAndResubmit(ctx, taskInfo)) {
                            continue; // 已删除并重新提交，重新轮询新 tid
                        }
                        sleepUntilNextPoll(deadlineMs, pollIndex++);
                        continue;
                    }

                    // 10008 优先于文件兜底：否则会误把同季其它集视频当成成功，又因临时目录无文件 return false
                    if (isDuplicateOfflineError(taskInfo.getError())) {
                        log.warn("离线任务报告任务已存在(10008) tid={} state={} {}", tid, state, reName);
                        markDuplicateMagnet(infoHash);
                        String failedTid = tid;
                        // 清理本次 add 产生的失败壳任务，避免列表堆积
                        try {
                            if (StrUtil.isNotBlank(failedTid)) {
                                taskDelete(failedTid);
                            }
                        } catch (Exception ex) {
                            log.debug("删除 10008 失败壳任务 {}: {}", failedTid, ex.getMessage());
                        }
                        String otherTid = findExistingTaskIdPreferActive(infoHash);
                        if (StrUtil.isNotBlank(otherTid) && !otherTid.equals(failedTid)) {
                            OpenListTaskInfo.State otherState = taskInfo(otherTid)
                                    .map(OpenListTaskInfo::getState).orElse(null);
                            if (isActiveState(otherState) || otherState == null) {
                                // 进行中或状态未知（查询失败）：保守复用，不误删可能仍在进行的任务
                                // 双写：循环内读取本地 tid，finally 清理读取 ctx.tid
                                tid = ctx.tid = otherTid;
                                // 切换为复用任务：不再是本次新提交，delete=true 收尾不得远程删除它
                                ctx.newlySubmittedTid = false;
                                log.info("切换到已存在离线任务 tid={} state={} {}", tid, otherState, reName);
                            } else {
                                // 已有任务是终态失败/未知（卡住）：删除并重新提交，而非死等
                                log.warn("已有任务卡住 state={} tid={}，删除并重新提交 {}",
                                        otherState, otherTid, reName);
                                tid = ctx.tid = otherTid;
                                if (resubmitStuckTask(ctx, "10008 已有任务卡住")) {
                                    continue;
                                }
                                tid = ctx.tid = null; // 进入无 tid 文件轮询
                                ctx.newlySubmittedTid = false;
                            }
                        } else {
                            // 无其他任务：尝试删除本次壳任务并重新提交一次（可能残留已清理）
                            if (ctx.resubmitCount < MAX_STUCK_RESUBMIT
                                    && resubmitStuckTask(ctx, "10008 无已有任务，重新提交")) {
                                continue;
                            }
                            tid = ctx.tid = null; // 进入无 tid 文件轮询
                            ctx.newlySubmittedTid = false;
                        }
                        sleepUntilNextPoll(deadlineMs, pollIndex++);
                        continue;
                    }
                    // Error/Failed：仅当本集临时目录或最终目录命中本集文件时才当完成
                    if (hasEpisodeVideos(path, tempDirName, item.getEpisodeRange())
                        || hasEpisodeVideos(savePath, finalRenameBase, item.getEpisodeRange())
                        || !findCloudDownloadEpisodeVideos(item.getEpisodeRange()).isEmpty()) {
                        log.info("本集资源已就绪，OpenList 任务状态异常但文件可用，继续后处理 {}", reName);
                        clearDuplicateMagnet(infoHash);
                        break;
                    }
                    // 终态任务（Failed/Error/Canceled）：无文件才算失败。Failed 不等于坏种。
                    if (state == OpenListTaskInfo.State.Failed
                            || state == OpenListTaskInfo.State.Error
                            || state == OpenListTaskInfo.State.Canceled) {
                        // 卡住任务（Failed/Error 且本次新提交）：删除并重新提交，上限内。
                        // 复用/历史任务不在此重提：交由下一轮 RSS 的 adoptOrCleanResidualTasks 清理，
                        // 避免状态查询竞态下误删他人仍可能进行的下载。
                        if (state != OpenListTaskInfo.State.Canceled
                                && ctx.newlySubmittedTid
                                && ctx.resubmitCount < MAX_STUCK_RESUBMIT) {
                            log.warn("离线任务终态失败疑似卡住 state={} error={}，删除并重新提交 {}",
                                    state, taskInfo.getError(), reName);
                            if (resubmitStuckTask(ctx, "终态失败卡住")) {
                                continue;
                            }
                        }
                        log.error("离线任务已终结 state={} error={}，放弃重试（非坏种判定）", state, taskInfo.getError());
                        return false;
                    }
                    // 非终态异常（Failing 等）：按次数重试
                    if (alistDownloadRetryNumber > -1 && retry >= alistDownloadRetryNumber) {
                        log.error("离线下载失败 {} (已重试{}次)", taskInfo.getError(), retry);
                        return false;
                    }
                    retry++;
                    log.info("离线任务重试 {}/{} state={}", retry, alistDownloadRetryNumber, state);
                    taskRetry(tid);
                    sleepUntilNextPoll(deadlineMs, pollIndex++);
                    continue;
                }

                // 无 tid（10008）：低频轮询本集文件是否出现（勿扫整季其它集）
                if (hasEpisodeVideos(path, tempDirName, item.getEpisodeRange())
                        || hasEpisodeVideos(savePath, finalRenameBase, item.getEpisodeRange())
                        || !findCloudDownloadEpisodeVideos(item.getEpisodeRange()).isEmpty()) {
                    log.info("10008 本集任务文件已就绪 {}", reName);
                    clearDuplicateMagnet(infoHash);
                    break;
                }
                // 与有 tid 一致：20s -> 1min -> 5min -> 10min
                sleepUntilNextPoll(deadlineMs, pollIndex++);
            }

            if (DateTime.now().getTime() >= deadlineMs) {
                // 最终兜底：绕过缓存并确认文件大小稳定，避免状态更新滞后导致误判失败。
                TimeoutFileInspection inspection = inspectTimeoutFiles(
                        path, savePath, tempDirName, item.getEpisodeRange());
                String finalState = tid == null
                        ? "no-tid"
                        : taskInfo(tid).map(info -> String.valueOf(info.getState())).orElse("unknown");
                if (inspection.ready()) {
                    log.info("离线超时终检通过，继续后处理 {} tid={} state={} videos={} totalBytes={} stable={}",
                            reName, tid, finalState, inspection.videoCount(), inspection.totalBytes(), inspection.stable());
                    clearDuplicateMagnet(infoHash);
                } else {
                    log.error("{} 超过离线超时 {} 分钟，终检未发现稳定的本集视频，强制失败并清理 "
                                    + "OpenList/本地占用 tid={} state={} videos={} totalBytes={} stable={}",
                            reName, waitMinutes, tid, finalState, inspection.videoCount(),
                            inspection.totalBytes(), inspection.stable());
                    try {
                        purgeHashTasks(infoHash);
                    } catch (Exception purgeEx) {
                        log.warn("超时清理 OpenList 任务失败 {}: {}", infoHash, purgeEx.getMessage());
                    }
                    clearDuplicateMagnet(infoHash);
                    throw new OfflineTimeoutException(StrFormatter.format(
                            "{} 超过离线超时 {} 分钟", reName, waitMinutes));
                }
            }
            // ① finally 前先扫描文件：临时目录优先；否则最终目录中本集相关文件
            List<OpenListFileInfo> openListFileInfos = findFiles(path);
            // 云下载兜底命中时记录文件源目录：移动成功后清理这些源目录残留的空壳（115 任务目录）
            Set<String> cloudSourceDirs = new HashSet<>();
            List<OpenListFileInfo> videoList = openListFileInfos.stream()
                    // 防御：findFiles 缓存异常/实现差异下目录可能混入，目录名带扩展名会误判为视频
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                    .filter(f -> FileUtils.isVideoFormat(f.getName()))
                    .sorted(Comparator.comparingLong(OpenListFileInfo::getSize).reversed())
                    .toList();
            List<OpenListFileInfo> subtitleList = openListFileInfos.stream()
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                    .filter(f -> FileUtils.isSubtitleFormat(f.getName()))
                    .toList();

            if (videoList.isEmpty()) {
                // 可能已在 savePath 落盘（历史完成/被其它路径移动）
                // 必须排除临时目录内的文件，否则「还在临时目录」会被误判为已完成并跳过移动
                List<OpenListFileInfo> saveFiles = findEpisodeFiles(savePath, finalRenameBase).stream()
                        .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                        .filter(f -> !isUnderPath(f, tempDownloadDir != null ? tempDownloadDir : null))
                        .toList();
                videoList = saveFiles.stream()
                        .filter(f -> FileUtils.isVideoFormat(f.getName()))
                        .sorted(Comparator.comparingLong(OpenListFileInfo::getSize).reversed())
                        .toList();
                subtitleList = saveFiles.stream()
                        .filter(f -> FileUtils.isSubtitleFormat(f.getName()))
                        .toList();
                openListFileInfos = saveFiles;
                if (videoList.isEmpty()) {
                    // 与超时终检 inspectTimeoutFiles 的兜底一致：savePath 递归下可能已存在本集视频
                    // 但未按模板命名（合集原始标题目录/非模板命名），按集数匹配而非模板名，
                    // 避免「终检通过 → 后处理判失败 → 清标记 → 下轮重提交」死循环
                    List<OpenListFileInfo> fallback = findFiles(savePath).stream()
                            .filter(f -> !isUnderPath(f, tempDownloadDir != null ? tempDownloadDir : null))
                            .toList();
                    List<OpenListFileInfo> fallbackVideos = expectedEpisodeVideos(fallback, item.getEpisodeRange());
                    if (!fallbackVideos.isEmpty()) {
                        log.info("savePath 兜底扫描发现本集文件，进入后处理 {} videos={}",
                                reName, fallbackVideos.size());
                        videoList = fallbackVideos.stream()
                                .sorted(Comparator.comparingLong(OpenListFileInfo::getSize).reversed())
                                .toList();
                        subtitleList = fallback.stream()
                                .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                                .filter(f -> FileUtils.isSubtitleFormat(f.getName()))
                                .toList();
                        openListFileInfos = fallback;
                    }
                }
                if (videoList.isEmpty()) {
                    // 115 离线完成后的文件可能落在根目录「云下载」而非目标路径：
                    // 兜底扫描（原始标题命名，按集数匹配），走重命名/移动流程自动归位到 savePath
                    List<OpenListFileInfo> cloudFiles = findCloudDownloadFiles();
                    List<OpenListFileInfo> cloudVideos = expectedEpisodeVideos(cloudFiles, item.getEpisodeRange());
                    if (!cloudVideos.isEmpty()) {
                        log.info("115 云下载目录兜底扫描发现本集文件，进入后处理 {} videos={}",
                                reName, cloudVideos.size());
                        videoList = cloudVideos.stream()
                                .sorted(Comparator.comparingLong(OpenListFileInfo::getSize).reversed())
                                .toList();
                        subtitleList = cloudFiles.stream()
                                .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                                .filter(f -> FileUtils.isSubtitleFormat(f.getName()))
                                .toList();
                        openListFileInfos = cloudFiles;
                        // 记录源目录（云下载下的原始目录/根），移动成功后用于清理空壳
                        videoList.stream()
                                .map(OpenListFileInfo::getPath)
                                .filter(Objects::nonNull)
                                .forEach(cloudSourceDirs::add);
                        subtitleList.stream()
                                .map(OpenListFileInfo::getPath)
                                .filter(Objects::nonNull)
                                .forEach(cloudSourceDirs::add);
                    }
                }
                if (!videoList.isEmpty()) {
                    if (!saveFiles.isEmpty() || videoList.stream()
                            .allMatch(f -> Objects.equals(trimTrailingSlash(f.getPath()), trimTrailingSlash(savePath)))) {
                        // 已在最终目录：不要再 rename/move 到自己，直接成功；
                        // 视频/字幕已确认落盘，强制清掉临时目录（含嵌套残留）
                        log.info("本集文件已在最终目录，视为下载完成 {}", reName);
                        if (tempDownloadDir != null) {
                            cleanupTempDownloadDir(savePath, tempDirName, true);
                        }
                        clearDuplicateMagnet(infoHash);
                        NotificationUtil.send(config, ani,
                                StrFormatter.format("{} 下载完成", item.getReName()),
                                NotificationStatusEnum.DOWNLOAD_END);
                        return true;
                    }
                    // 兜底文件位于 savePath 子目录（如合集原始标题目录）：继续走下方重命名/移动流程
                    log.info("本集文件位于 savePath 子目录，继续重命名/移动 {}", reName);
                }
            }

            if (videoList.isEmpty()) {
                return false;
            }

            Boolean rename = config.getRename();
            Map<String, String> renameMap = new HashMap<>();

            if (videoList.size() == 1) {
                OpenListFileInfo videoFile = videoList.get(0);
                String videoReName = isCollection
                        ? collectionEpisodeReName(videoFile.getName(), finalRenameBase, ani.getSeason())
                        : finalRenameBase;
                renameMap.put(videoFile.getName(), videoReName + "." + FileUtil.extName(videoFile.getName()));
                for (OpenListFileInfo sub : subtitleList) {
                    String name = sub.getName();
                    String ext = FileUtil.extName(name);
                    String newName = videoReName;
                    String lang = FileUtil.extName(FileUtil.mainName(name));
                    if (StrUtil.isNotBlank(lang)) {
                        newName = newName + "." + lang;
                    }
                    renameMap.put(name, newName + "." + ext);
                }
            } else {
                for (OpenListFileInfo video : videoList) {
                    String videoName = video.getName();
                    String videoBase = FileUtil.mainName(videoName);
                    String videoExt = FileUtil.extName(videoName);
                    String videoReName;
                    if (isCollection) {
                        videoReName = collectionEpisodeReName(videoName, finalRenameBase, ani.getSeason());
                    } else {
                        String episode = extractEpisodeFromFileName(videoName);
                        if (episode == null) {
                            // 特典/无集数文件([Character PV 01]、[CM]、[Menu]、SPs 等): 保留原名, 避免与正片重命名冲突
                            videoReName = videoBase;
                        } else if (finalRenameBase.contains(".E")) {
                            videoReName = finalRenameBase.replaceAll("\\.E\\d+(\\.5)?", ".E" + episode);
                        } else if (finalRenameBase.matches(".*[Ss]\\d+.*E\\d+.*")) {
                            videoReName = finalRenameBase.replaceAll("E\\d+(\\.5)?", "E" + episode);
                        } else {
                            videoReName = finalRenameBase;
                        }
                    }
                    String videoTarget = videoReName + "." + videoExt;
                    // 同集多版本/多语言(如柯南同集 CHT/CHS/MKV): 目标名冲突时保留原名, 避免互相覆盖
                    if (!renameMap.containsValue(videoTarget)) {
                        renameMap.put(videoName, videoTarget);
                    } else {
                        log.info("同集多版本, 保留原名: {}", videoName);
                        renameMap.put(videoName, videoBase + "." + videoExt);
                    }

                    for (OpenListFileInfo sub : subtitleList) {
                        String subName = sub.getName();
                        String subBase = FileUtil.mainName(subName);
                        String subExt = FileUtil.extName(subName);
                        String subBaseClean = subBase;
                        String lang = FileUtil.extName(subBase);
                        if (StrUtil.isNotBlank(lang) && !FileUtils.isVideoFormat(lang)) {
                            subBaseClean = FileUtil.mainName(subBase);
                        }
                        if (videoBase.equals(subBase) || videoBase.equals(subBaseClean)) {
                            String subReName = videoReName;
                            if (StrUtil.isNotBlank(lang) && !FileUtils.isVideoFormat(lang)) {
                                subReName = subReName + "." + lang;
                            }
                            renameMap.put(subName, subReName + "." + subExt);
                        }
                    }
                }
                // 处理未匹配的字幕文件 - 确保它们也被移动
                for (OpenListFileInfo sub : subtitleList) {
                    if (renameMap.containsKey(sub.getName())) continue;
                    String name = sub.getName();
                    String ext = FileUtil.extName(name);
                    // 使用视频文件名作为基础名，而不是统一的 reName
                    String videoBase = videoList.isEmpty() ? finalRenameBase : FileUtil.mainName(videoList.get(0).getName());
                    String newName = videoBase;
                    String lang = FileUtil.extName(FileUtil.mainName(name));
                    if (StrUtil.isNotBlank(lang)) {
                        newName = newName + "." + lang;
                    }
                    renameMap.put(name, newName + "." + ext);
                    log.info("未匹配字幕文件: {} -> {}", name, newName + "." + ext);
                }
            }

            // ② renameMap 目标名冲突检测
            Set<String> targetNames = new HashSet<>();
            for (Map.Entry<String, String> entry : renameMap.entrySet()) {
                if (!targetNames.add(entry.getValue())) {
                    log.error("重命名目标冲突: {} -> {} (已存在)", entry.getKey(), entry.getValue());
                    throw new IllegalStateException("重命名目标文件名冲突: " + entry.getValue());
                }
            }

            // 按原始路径分组，处理文件可能分布在多个子目录的情况
            Map<String, List<String>> pathToNames = new HashMap<>();
            for (Map.Entry<String, String> entry : renameMap.entrySet()) {
                String srcName = entry.getKey();
                String newName = rename ? entry.getValue() : srcName;
                // 找到原始文件所在目录（排除目录条目，避免把 115 任务目录当文件处理）
                Optional<OpenListFileInfo> fileInfo = openListFileInfos.stream()
                        .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                        .filter(f -> f.getName().equals(srcName))
                        .findFirst();
                String dirPath = fileInfo.map(OpenListFileInfo::getPath).orElse(videoList.get(0).getPath());
                pathToNames.computeIfAbsent(dirPath, k -> new ArrayList<>()).add(newName);
            }

            // 重命名
            if (rename) {
                for (Map.Entry<String, List<String>> entry : pathToNames.entrySet()) {
                    String dirPath = entry.getKey();
                    List<Map<String, String>> renameObjects = new ArrayList<>();
                    for (String srcName : renameMap.keySet()) {
                        Optional<OpenListFileInfo> fi = openListFileInfos.stream()
                                .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                                .filter(f -> f.getName().equals(srcName)).findFirst();
                        if (fi.isPresent() && fi.get().getPath().equals(dirPath)) {
                            String newName = renameMap.get(srcName);
                            log.info("重命名 {} ==> {}", srcName, newName);
                            renameObjects.add(Map.of("src_name", srcName, "new_name", newName));
                        }
                    }
                    if (!renameObjects.isEmpty()) {
                        fsBatchRename(renameObjects, dirPath);
                    }
                }
            }

            // 移动：从每个子目录分别移动
            Set<String> allMovedNames = new HashSet<>();
            for (Map.Entry<String, List<String>> entry : pathToNames.entrySet()) {
                String dirPath = entry.getKey();
                List<String> names = entry.getValue();
                fsMove(dirPath, savePath, names);
                allMovedNames.addAll(names);
            }

            // 验证必须落在最终目录顶层；findFiles(savePath) 会递归进临时目录，
            // 若仍用它判定，未真正移出的文件也会被当成「已移动」，随后清理失败留下空壳。
            Set<String> topLevelNames = fsList(savePath, true).stream()
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                    .map(OpenListFileInfo::getName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            List<String> missingNames = allMovedNames.stream()
                    .filter(name -> !topLevelNames.contains(name))
                    .toList();

            if (!missingNames.isEmpty()) {
                log.warn("部分文件未出现在最终目录顶层，保留临时目录: {}", missingNames);
            } else {
                if (tempDownloadDir != null) {
                    // 需要移动的视频/字幕已确认在最终目录顶层 → 强制删除临时目录
                    cleanupTempDownloadDir(savePath, tempDirName, true);
                }
                // 云下载兜底：文件已全部移动归位，清理源目录残留的空壳（115 任务目录）
                if (!cloudSourceDirs.isEmpty()) {
                    cleanupCloudDownloadEmptyDirs(cloudSourceDirs);
                }
            }

            // 缺集校验：扫描整季目录，但日志只报告本次声明范围的命中情况。
            if (item.getEpisodeRange() != null && !item.getEpisodeRange().isEmpty()) {
                List<OpenListFileInfo> actualVideos = findFiles(savePath).stream()
                        .filter(f -> FileUtils.isVideoFormat(f.getName()))
                        .toList();
                EpisodeValidation validation = validateCollectionEpisodes(item.getEpisodeRange(), actualVideos);
                if (validation.missing().isEmpty()) {
                    log.info("合集集数校验通过: {} 本次期望 {}, 已命中 {}",
                            reName, validation.expected(), validation.matched());
                } else {
                    log.warn("合集缺集: {} 本次期望 {}, 已命中 {}, 缺失 {}",
                            reName, validation.expected(), validation.matched(), validation.missing());
                }
            }

            NotificationUtil.send(config, ani,
                    StrFormatter.format("{} 下载完成", item.getReName()),
                    NotificationStatusEnum.DOWNLOAD_END);
            return true;
        } catch (OfflineTimeoutException e) {
            // 超时必须向上抛给 DownloadService，避免被当成普通 false/坏种
            throw e;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return false;
        } finally {
            releaseOfflinePlaceholder(ctx.infoHash, ctx.tid, ctx.claimedInFlight, delete, ctx.newlySubmittedTid);
        }
    }

    /**
     * 合集最终命名：以订阅模板命名结果为基名，仅替换/补全集数。
     * 临时目录名使用源标题，不应传入本方法。
     */
    String collectionEpisodeReName(String originalName, String reName, Integer season) {
        if (StrUtil.isBlank(originalName) || StrUtil.isBlank(reName)) {
            return reName;
        }

        String episode = extractEpisodeFromFileName(originalName);
        if (StrUtil.isBlank(episode)) {
            // 特典/无集数文件([Character PV 01]、[CM]、[Menu]、SPs 等): 保留原名, 避免与正片重命名冲突
            return FileUtil.mainName(originalName);
        }
        if (reName.contains(".E")) {
            return reName.replaceAll("\\.E\\d+(\\.5)?", ".E" + episode);
        }
        if (reName.matches(".*[Ss]\\d+.*E\\d+.*")) {
            return reName.replaceAll("E\\d+(\\.5)?", "E" + episode);
        }

        int seasonNumber = season == null || season < 0 ? 1 : season;
        return reName + " S" + String.format("%02d", seasonNumber) + "E" + formatEpisode(episode);
    }

    private static String formatEpisode(String episode) {
        try {
            if (episode.endsWith(".5")) {
                return String.format("%02d.5", Integer.parseInt(episode.substring(0, episode.length() - 2)));
            }
            return String.format("%02d", Integer.parseInt(episode));
        } catch (NumberFormatException ignored) {
            return episode;
        }
    }

    EpisodeValidation validateCollectionEpisodes(List<Double> expected, List<OpenListFileInfo> actualVideos) {
        LinkedHashSet<Double> expectedEpisodes = expected == null
                ? new LinkedHashSet<>()
                : expected.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Double> downloadedEpisodes = actualVideos == null
                ? Set.of()
                : actualVideos.stream()
                .filter(f -> FileUtils.isVideoFormat(f.getName()))
                .map(OpenListFileInfo::getName)
                .map(this::extractEpisodeFromFileName)
                .filter(StrUtil::isNotBlank)
                .map(OpenList::parseEpisodeNumber)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<Double> matched = expectedEpisodes.stream().filter(downloadedEpisodes::contains).toList();
        List<Double> missing = expectedEpisodes.stream().filter(ep -> !downloadedEpisodes.contains(ep)).toList();
        return new EpisodeValidation(List.copyOf(expectedEpisodes), matched, missing);
    }

    private static Double parseEpisodeNumber(String episode) {
        try {
            return Double.parseDouble(episode);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    record EpisodeValidation(List<Double> expected, List<Double> matched, List<Double> missing) {
    }

    @Override
    public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
        return false;
    }

    @Override
    public Boolean rename(TorrentsInfo torrentsInfo) {
        return false;
    }

    @Override
    public Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        return false;
    }

    @Override
    public void updateTrackers(Set<String> trackers) {

    }

    @Override
    public void setSavePath(TorrentsInfo torrentsInfo, String path) {

    }

    /**
     * 创建文件夹
     *
     * @param path 路径
     */
    public void mkdir(String path) {
        api.mkdir(path);
    }

    /**
     * 移动文件
     *
     * @param srcDir 原目录
     * @param dstDir 目标目录
     * @param names  文件名
     */
    public void fsMove(String srcDir, String dstDir, List<String> names) {
        api.fsMove(srcDir, dstDir, names);
    }

    /**
     * 删除文件
     *
     * @param dir   目录
     * @param names 文件名
     */
    public void fsRemove(String dir, List<String> names) {
        api.fsRemove(dir, names);
    }

    /**
     * 强制下载用: 删除网盘目录下与 reName 匹配的已有文件/目录(主名相等或包含)。
     */
    public void forceDeleteFiles(String dirPath, String reName) {
        if (StrUtil.isBlank(dirPath) || StrUtil.isBlank(reName)) {
            return;
        }
        String target = reName.trim().toUpperCase();
        List<String> toRemove = new ArrayList<>();
        try {
            for (OpenListFileInfo entry : fsList(dirPath, true)) {
                String name = entry.getName();
                if (StrUtil.isBlank(name)) {
                    continue;
                }
                String main = FileUtil.mainName(new File(name)).trim().toUpperCase();
                if (main.equals(target) || main.contains(target)) {
                    toRemove.add(name);
                }
            }
        } catch (Exception e) {
            log.warn("强制下载: 列出网盘目录失败 {}: {}", dirPath, ExceptionUtils.getMessage(e));
            return;
        }
        if (!toRemove.isEmpty()) {
            try {
                fsRemove(dirPath, toRemove);
                log.info("强制下载: 删除网盘已有文件 {}/{}", dirPath, toRemove);
            } catch (Exception e) {
                log.warn("强制下载: 删除网盘文件失败 {}/{}: {}", dirPath, toRemove, ExceptionUtils.getMessage(e));
            }
        }
    }

    /**
     * 文件是否位于 prefix 目录下（含其自身路径）。
     */
    static boolean isUnderPath(OpenListFileInfo file, String prefix) {
        if (file == null || StrUtil.isBlank(prefix)) {
            return false;
        }
        String p = StrUtil.blankToDefault(file.getPath(), "").replace('\\', '/');
        String n = StrUtil.blankToDefault(file.getName(), "").replace('\\', '/');
        String full = p.endsWith("/" + n) || p.equals(n) ? p : (p.isEmpty() ? n : p + "/" + n);
        String pref = prefix.replace('\\', '/');
        while (pref.endsWith("/")) {
            pref = pref.substring(0, pref.length() - 1);
        }
        return full.equalsIgnoreCase(pref)
                || full.toLowerCase(Locale.ROOT).startsWith(pref.toLowerCase(Locale.ROOT) + "/");
    }

    /**
     * 去掉路径尾部斜杠，用于路径相等比较（兼容 115/OpenList 返回的目录路径格式差异）
     */
    static String trimTrailingSlash(String path) {
        if (StrUtil.isBlank(path)) {
            return "";
        }
        String p = path.replace('\\', '/');
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * 洗版删除目标判定：名称含 seasonKey 的旧文件/目录，但排除本次任务目录
     * （刚 mkdir 创建的目录，否则洗版会删掉它导致离线任务落点丢失）
     */
    static boolean isWashTarget(String name, String seasonKey, String currentDirName) {
        if (name == null || StrUtil.isBlank(seasonKey)) {
            return false;
        }
        if (name.equals(currentDirName)) {
            return false;
        }
        return name.contains(seasonKey);
    }

    /**
     * 解析 115 云下载兜底目录（纯函数，便于单测）：
     * - 配置非空：直接使用配置路径（归一化斜杠）
     * - 配置为空：在根目录列表里找名字含"云下载"的目录（自动发现）
     * - 找不到：返回 null（不启用兜底）
     */
    static String pickCloudDir(String configured, List<String> rootNames) {
        if (StrUtil.isNotBlank(configured)) {
            return configured.replace('\\', '/');
        }
        if (rootNames == null) {
            return null;
        }
        return rootNames.stream()
                .filter(name -> name != null && name.contains("云下载"))
                .findFirst()
                .map(name -> "/" + name.replace('\\', '/'))
                .orElse(null);
    }

    /**
     * 115 云下载兜底目录（进程内缓存一次自动发现结果；配置优先）
     */
    private String resolvedCloudDir;

    private String resolveCloudDownloadDir() {
        String configured = config == null ? null : config.getAlistCloudDownloadDir();
        if (StrUtil.isNotBlank(configured)) {
            return configured.replace('\\', '/');
        }
        if (resolvedCloudDir != null) {
            return resolvedCloudDir;
        }
        try {
            List<String> rootNames = fsList("/", true).stream()
                    .map(OpenListFileInfo::getName)
                    .toList();
            resolvedCloudDir = pickCloudDir(null, rootNames);
        } catch (Exception e) {
            log.debug("自动发现 115 云下载目录失败: {}", ExceptionUtils.getMessage(e));
            resolvedCloudDir = null;
        }
        return resolvedCloudDir;
    }

    /**
     * 扫描 115 云下载目录全部文件（原始标题命名，供按集数匹配与后处理移动）。
     * 未配置且自动发现失败时返回空列表。
     */
    private List<OpenListFileInfo> findCloudDownloadFiles() {
        String cloudDir = resolveCloudDownloadDir();
        if (StrUtil.isBlank(cloudDir)) {
            return List.of();
        }
        try {
            api.invalidateFindFilesCache();
            return findFiles(cloudDir);
        } catch (Exception e) {
            log.debug("扫描 115 云下载目录失败 {}: {}", cloudDir, ExceptionUtils.getMessage(e));
            return List.of();
        }
    }

    /**
     * 扫描 115 云下载目录，按本集声明范围匹配视频（原始标题命名，不能按 reName 模板过滤）。
     * 找不到或未配置时返回空列表。
     */
    private List<OpenListFileInfo> findCloudDownloadEpisodeVideos(List<Double> expectedEpisodes) {
        return expectedEpisodeVideos(findCloudDownloadFiles(), expectedEpisodes);
    }

    /**
     * 清理云下载兜底移动后的空壳源目录（115 云下载任务目录残留，名字通常=文件名含扩展名）。
     * 只删除「已空」的目录：先检查目录无任何子项才删，避免误删用户其它内容。
     *
     * @param sourceDirs 本次兜底移动涉及的源目录集合（OpenListFileInfo.getPath）
     */
    private void cleanupCloudDownloadEmptyDirs(Set<String> sourceDirs) {
        for (String srcDir : sourceDirs) {
            try {
                String dir = trimTrailingSlash(srcDir);
                if (StrUtil.isBlank(dir) || "/".equals(dir)) {
                    continue;
                }
                List<OpenListFileInfo> children = fsList(dir, true);
                if (!children.isEmpty()) {
                    // 目录仍有内容（可能含其它剧集文件）：不动
                    continue;
                }
                int idx = dir.lastIndexOf('/');
                if (idx <= 0) {
                    continue;
                }
                String parent = dir.substring(0, idx);
                String name = dir.substring(idx + 1);
                log.info("清理 115 云下载空壳目录 {}/{}", parent, name);
                fsRemove(parent, List.of(name));
            } catch (Exception e) {
                log.debug("清理云下载空壳目录失败 {}: {}", srcDir, ExceptionUtils.getMessage(e));
            }
        }
    }

    /**
     * 清理临时下载目录。
     * <p>
     * 前置条件由调用方保证：需要的视频/字幕已在最终目录顶层确认完成。
     * 此时临时目录内无论还有嵌套媒体、垃圾还是空壳，一律删除（115 对非空目录
     * 的 fs/remove 可整树删除）。force=false 仅用于谨慎场景，仍要求无受保护媒体。
     */
    void cleanupTempDownloadDir(String savePath, String tempDirName, boolean force) {
        if (StrUtil.isBlank(savePath) || StrUtil.isBlank(tempDirName)) {
            return;
        }
        String tempPath = savePath + "/" + tempDirName;
        try {
            // 目录不存在则无需清理
            List<OpenListFileInfo> top = fsList(tempPath, true);
            // OpenList 对不存在路径可能返回空列表或抛错；空也继续尝试 remove 幂等

            if (!force) {
                List<OpenListFileInfo> remaining = listFilesWithRetry(tempPath, 2);
                List<OpenListFileInfo> mediaLeft = remaining.stream()
                        .filter(OpenList::isProtectedTempFile)
                        .toList();
                if (!mediaLeft.isEmpty()) {
                    log.warn("未强制清理且临时目录仍有媒体，跳过 {}: {}",
                            tempPath, mediaLeft.stream().map(OpenListFileInfo::getName).toList());
                    return;
                }
                // 非强制：先清垃圾/空壳，再删顶层
                remaining.stream()
                        .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                        .filter(OpenList::isJunkTempFile)
                        .sorted(Comparator.comparingInt((OpenListFileInfo f) ->
                                StrUtil.count(StrUtil.blankToDefault(f.getPath(), ""), "/")).reversed())
                        .forEach(f -> {
                            try {
                                String dir = StrUtil.blankToDefault(f.getPath(), tempPath);
                                log.info("删除临时残留文件 {}/{}", dir, f.getName());
                                fsRemove(dir, List.of(f.getName()));
                            } catch (Exception e) {
                                log.warn("删除临时残留失败 {}/{}: {}", f.getPath(), f.getName(), e.getMessage());
                            }
                        });
                removeEmptyDirsBottomUp(tempPath);
            } else if (!top.isEmpty()) {
                log.info("最终文件已确认，强制删除临时目录 {} (entries={})",
                        tempPath, top.stream().map(OpenListFileInfo::getName).toList());
            }

            try {
                // 115/OpenList：对非空目录直接 remove 顶层即可整树删除
                fsRemove(savePath, List.of(tempDirName));
                log.info("已删除临时目录 {}/{}", savePath, tempDirName);
            } catch (Exception e) {
                // 若顶层 remove 失败（部分实现拒绝非空），自底向上再试一次
                log.warn("直接删除临时目录失败，尝试逐层清理 {}: {}", tempPath, e.getMessage());
                try {
                    forceRemoveTree(tempPath);
                    fsRemove(savePath, List.of(tempDirName));
                    log.info("逐层清理后已删除临时目录 {}/{}", savePath, tempDirName);
                } catch (Exception e2) {
                    log.warn("删除临时目录失败 {}: {}", tempPath, e2.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("清理临时目录失败 {}: {}", tempPath, e.getMessage());
        }
    }

    /**
     * 自底向上强制删除 path 下所有文件与子目录（不含 path 自身）。
     */
    private void forceRemoveTree(String path) {
        if (StrUtil.isBlank(path)) {
            return;
        }
        api.invalidateFindFilesCache();
        List<OpenListFileInfo> entries = fsList(path, true);
        // 先递归子目录
        for (OpenListFileInfo entry : entries) {
            if (Boolean.TRUE.equals(entry.getIsDir())) {
                String childPath = path + "/" + entry.getName();
                forceRemoveTree(childPath);
            }
        }
        // 再删当前层全部条目
        List<String> names = entries.stream()
                .map(OpenListFileInfo::getName)
                .filter(StrUtil::isNotBlank)
                .toList();
        if (!names.isEmpty()) {
            try {
                fsRemove(path, names);
            } catch (Exception e) {
                // 逐个再试
                for (String name : names) {
                    try {
                        fsRemove(path, List.of(name));
                    } catch (Exception e2) {
                        log.debug("强制删除失败 {}/{}: {}", path, name, e2.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 临时目录内应保留的文件：视频/字幕，或非垃圾的正文文件。
     * 仅在 force=false 的谨慎清理路径使用。
     */
    static boolean isProtectedTempFile(OpenListFileInfo f) {
        if (f == null || Boolean.TRUE.equals(f.getIsDir())) {
            return false;
        }
        String name = f.getName();
        if (StrUtil.isBlank(name)) {
            return false;
        }
        if (FileUtils.isVideoFormat(name) || FileUtils.isSubtitleFormat(name)) {
            return true;
        }
        return !isJunkTempFile(f);
    }

    static boolean isJunkTempFile(OpenListFileInfo f) {
        if (f == null || Boolean.TRUE.equals(f.getIsDir())) {
            return false;
        }
        String name = StrUtil.blankToDefault(f.getName(), "").toLowerCase(Locale.ROOT);
        return name.endsWith(".aria2")
                || name.endsWith(".tmp")
                || name.endsWith(".temp")
                || name.endsWith(".!qb")
                || name.endsWith(".part")
                || name.endsWith(".bc!")
                || name.equals("thumbs.db")
                || name.equals(".ds_store");
    }

    private List<OpenListFileInfo> listFilesWithRetry(String path, int attempts) {
        List<OpenListFileInfo> last = List.of();
        int n = Math.max(1, attempts);
        for (int i = 0; i < n; i++) {
            api.invalidateFindFilesCache();
            last = findFiles(path);
            if (!last.isEmpty() || i == n - 1) {
                return last;
            }
            ThreadUtil.sleep(500L * (i + 1));
        }
        return last;
    }

    /**
     * 自底向上删除 path 下的空子目录，并清理其中的垃圾残留。
     * 不删除 path 自身（顶层临时目录由调用方 fsRemove）。
     */
    private void removeEmptyDirsBottomUp(String path) {
        if (StrUtil.isBlank(path)) {
            return;
        }
        List<OpenListFileInfo> entries = fsList(path, true);
        for (OpenListFileInfo entry : entries) {
            if (!Boolean.TRUE.equals(entry.getIsDir())) {
                continue;
            }
            String childPath = path + "/" + entry.getName();
            removeEmptyDirsBottomUp(childPath);

            for (OpenListFileInfo child : fsList(childPath, true)) {
                if (Boolean.TRUE.equals(child.getIsDir())) {
                    continue;
                }
                if (!isJunkTempFile(child)) {
                    continue;
                }
                try {
                    fsRemove(childPath, List.of(child.getName()));
                } catch (Exception e) {
                    log.debug("删除空子目录残留失败 {}/{}: {}", childPath, child.getName(), e.getMessage());
                }
            }

            List<OpenListFileInfo> after = fsList(childPath, true);
            boolean hasProtected = after.stream().anyMatch(f ->
                    Boolean.TRUE.equals(f.getIsDir()) || isProtectedTempFile(f));
            if (!hasProtected && after.stream().allMatch(f -> Boolean.TRUE.equals(f.getIsDir()) || isJunkTempFile(f))) {
                for (OpenListFileInfo junk : after) {
                    if (Boolean.TRUE.equals(junk.getIsDir())) {
                        continue;
                    }
                    try {
                        fsRemove(childPath, List.of(junk.getName()));
                    } catch (Exception e) {
                        log.debug("删除垃圾失败 {}/{}: {}", childPath, junk.getName(), e.getMessage());
                    }
                }
                after = fsList(childPath, true);
            }
            if (after.isEmpty()) {
                try {
                    fsRemove(path, List.of(entry.getName()));
                    log.info("删除空子目录 {}/{}", path, entry.getName());
                } catch (Exception e) {
                    log.debug("删除空子目录失败 {}/{}: {}", path, entry.getName(), e.getMessage());
                }
            }
        }
    }

    /**
     * 批量重命名
     *
     * @param mapList 重命名列表
     * @param srcDir  目录
     */
    public void fsBatchRename(List<Map<String, String>> mapList, String srcDir) {
        api.fsBatchRename(mapList, srcDir);
    }

    /**
     * 添加离线下载
     *
     * @param magnet 磁力链接
     * @param path   离线位置
     * @return tid
     */
    public String fsAddOfflineDownload(String magnet, String path) {
        return api.fsAddOfflineDownload(magnet, path);
    }

    /**
     * 文件列表
     *
     * @param path 目录
     * @return 文件列表
     */
    public List<OpenListFileInfo> fsList(String path, Boolean refresh) {
        return api.fsList(path, refresh);
    }

    /**
     * 查看任务
     *
     * @param tid 任务id
     * @return 任务信息
     */
    public Optional<OpenListTaskInfo> taskInfo(String tid) {
        return api.taskInfo(tid);
    }

    /**
     * 根据 infoHash 查找已存在的离线任务 ID（任意状态）
     */
    private String findExistingTaskId(String infoHash) {
        return findExistingTaskIdPreferActive(infoHash);
    }

    /**
     * 优先返回进行中任务；否则返回任意匹配 tid。
     */
    private String findExistingTaskIdPreferActive(String infoHash) {
        if (StrUtil.isBlank(infoHash)) {
            return null;
        }
        String key = infoHash.toLowerCase(Locale.ROOT);
        List<OpenListTaskInfo> tasks = new ArrayList<>();
        tasks.addAll(taskUnDoneList());
        tasks.addAll(taskDoneList());

        String anyTid = null;
        for (OpenListTaskInfo task : tasks) {
            String name = task.getName();
            if (name == null || !name.toLowerCase(Locale.ROOT).contains(key)) {
                continue;
            }
            OpenListTaskInfo.State state = task.getState();
            if (state == OpenListTaskInfo.State.Pending
                    || state == OpenListTaskInfo.State.Running
                    || state == OpenListTaskInfo.State.Waiting_for_Retry
                    || state == OpenListTaskInfo.State.Preparing_to_Retry) {
                return task.getId();
            }
            if (anyTid == null) {
                anyTid = task.getId();
            }
        }
        return anyTid;
    }

    /**
     * 识别 115/OpenList「任务已存在」类错误（API code 或 task.error 内嵌 JSON/文案）
     */
    static boolean isDuplicateOfflineError(String error) {
        if (StrUtil.isBlank(error)) {
            return false;
        }
        String raw = error.trim();
        if (raw.contains("10008")) {
            return true;
        }
        if (raw.contains("任务已存在") || raw.contains("请勿输入重复")) {
            return true;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("duplicate") && (lower.contains("task") || lower.contains("link") || lower.contains("url"));
    }

    static boolean isDuplicateMagnetCooling(String infoHash, long nowMs, ConcurrentHashMap<String, Long> table) {
        if (StrUtil.isBlank(infoHash) || table == null) {
            return false;
        }
        Long until = table.get(infoHash.toLowerCase(Locale.ROOT));
        return until != null && until > nowMs;
    }

    private boolean isDuplicateMagnetCooling(String infoHash) {
        long now = System.currentTimeMillis();
        DUPLICATE_MAGNET_UNTIL.entrySet().removeIf(e -> e.getValue() == null || e.getValue() <= now);
        if (isDuplicateMagnetCooling(infoHash, now, DUPLICATE_MAGNET_UNTIL)) {
            return true;
        }
        DUPLICATE_MAGNET_LONG_UNTIL.entrySet().removeIf(e -> e.getValue() == null || e.getValue() <= now);
        return isDuplicateMagnetCooling(infoHash, now, DUPLICATE_MAGNET_LONG_UNTIL);
    }

    private void markDuplicateMagnet(String infoHash) {
        if (StrUtil.isBlank(infoHash)) {
            return;
        }
        DUPLICATE_MAGNET_UNTIL.put(infoHash.toLowerCase(Locale.ROOT),
                System.currentTimeMillis() + DUPLICATE_MAGNET_COOLDOWN_MS);
    }

    /**
     * 10008 重提达上限：标记 24h 长冷却（115 云端 hash 去重残留，AList 侧无任务可删）
     */
    private void markDuplicateMagnetLong(String infoHash) {
        if (StrUtil.isBlank(infoHash)) {
            return;
        }
        DUPLICATE_MAGNET_LONG_UNTIL.put(infoHash.toLowerCase(Locale.ROOT),
                System.currentTimeMillis() + DUPLICATE_MAGNET_LONG_COOLDOWN_MS);
    }

    private boolean isDuplicateMagnetLongCooling(String infoHash) {
        long now = System.currentTimeMillis();
        DUPLICATE_MAGNET_LONG_UNTIL.entrySet().removeIf(e -> e.getValue() == null || e.getValue() <= now);
        return isDuplicateMagnetCooling(infoHash, now, DUPLICATE_MAGNET_LONG_UNTIL);
    }

    private void clearDuplicateMagnetLong(String infoHash) {
        if (StrUtil.isBlank(infoHash)) {
            return;
        }
        DUPLICATE_MAGNET_LONG_UNTIL.remove(infoHash.toLowerCase(Locale.ROOT));
    }

    private void clearDuplicateMagnet(String infoHash) {
        if (StrUtil.isBlank(infoHash)) {
            return;
        }
        String key = infoHash.toLowerCase(Locale.ROOT);
        DUPLICATE_MAGNET_UNTIL.remove(key);
        DUPLICATE_MAGNET_LONG_UNTIL.remove(key);
    }

    /**
     * 根据 infoHash 处理残留离线任务。
     * - Pending/Running 等进行中：复用 tid，不删除不重提
     * - Failed/Error/Canceled/Succeeded：删除后允许重提
     *
     * @return 可复用的进行中 tid；无则 null
     */
    private String adoptOrCleanResidualTasks(String infoHash) {
        List<OpenListTaskInfo> tasks = new ArrayList<>();
        tasks.addAll(taskDoneList());
        tasks.addAll(taskUnDoneList());

        String runningTid = null;
        for (OpenListTaskInfo task : tasks) {
            String name = task.getName();
            if (name == null || !name.toLowerCase().contains(infoHash.toLowerCase())) {
                continue;
            }
            String id = task.getId();
            ResidualAction action = decideResidualAction(task.getState(), runningTid != null);
            switch (action) {
                case ADOPT -> {
                    runningTid = id;
                    log.info("发现进行中离线任务，复用: {} {} state={}", id, name, task.getState());
                }
                case DELETE_DUPLICATE_RUNNING -> {
                    log.warn("同 hash 存在多个进行中任务，删除多余: {} {}", id, name);
                    try {
                        taskCancel(id);
                    } catch (Exception ignore) {
                    }
                    try {
                        taskDelete(id);
                    } catch (Exception e) {
                        log.debug("删除多余任务失败 {}: {}", id, e.getMessage());
                    }
                }
                case DELETE -> {
                    log.info("删除可清理残留任务: {} {} state={}", id, name, task.getState());
                    if (task.getState() == OpenListTaskInfo.State.Pending
                            || task.getState() == OpenListTaskInfo.State.Running
                            || task.getState() == OpenListTaskInfo.State.Waiting_for_Retry
                            || task.getState() == OpenListTaskInfo.State.Preparing_to_Retry
                            || task.getState() == OpenListTaskInfo.State.Canceling
                            || task.getState() == OpenListTaskInfo.State.Failing) {
                        try {
                            taskCancel(id);
                        } catch (Exception ignore) {
                        }
                    }
                    try {
                        taskDelete(id);
                    } catch (Exception e) {
                        log.debug("删除残留任务失败 {}: {}", id, e.getMessage());
                    }
                }
            }
        }
        return runningTid;
    }

    /**
     * 兼容旧调用名
     */
    public void deleteResidualTasks(String magnetOrHash) {
        String key = magnetOrHash;
        if (StrUtil.isNotBlank(magnetOrHash)) {
            String hash = ReUtil.get(StringEnum.MAGNET_REG, magnetOrHash, 1);
            if (StrUtil.isNotBlank(hash)) {
                key = hash.toLowerCase();
            } else {
                key = magnetOrHash.toLowerCase();
            }
        }
        adoptOrCleanResidualTasks(key);
    }

    /**
     * 残留任务处理策略（纯函数，便于单测）
     */
    static ResidualAction decideResidualAction(OpenListTaskInfo.State state, boolean hasAdoptedRunning) {
        if (state == null) {
            return ResidualAction.DELETE;
        }
        return switch (state) {
            case Pending, Running, Waiting_for_Retry, Preparing_to_Retry ->
                    hasAdoptedRunning ? ResidualAction.DELETE_DUPLICATE_RUNNING : ResidualAction.ADOPT;
            case Succeeded, Error, Failing, Failed, Canceling, Canceled -> ResidualAction.DELETE;
        };
    }

    enum ResidualAction {
        ADOPT,
        DELETE,
        DELETE_DUPLICATE_RUNNING
    }

    /**
     * 未完成的离线任务
     *
     * @return 任务列表
     */
    public List<OpenListTaskInfo> taskUnDoneList() {
        return api.taskUnDoneList();
    }

    /**
     * 已完成的离线任务
     *
     * @return 任务列表
     */
    public List<OpenListTaskInfo> taskDoneList() {
        return api.taskDoneList();
    }

    /**
     * 重试任务
     *
     * @param tid 任务id
     */
    public void taskRetry(String tid) {
        api.taskRetry(tid);
    }

    /**
     * 取消任务（运行中任务应先 cancel 再 delete）
     *
     * @param tid 任务id
     */
    public void taskCancel(String tid) {
        api.taskCancel(tid);
    }

    /**
     * 删除任务
     *
     * @param tid 任务id
     */
    public void taskDelete(String tid) {
        api.taskDelete(tid);
    }

    /**
     * 超时/强制失败时：按 hash 清理 OpenList 上所有匹配离线任务。
     * 运行中：cancel -> delete；终态：delete。
     * 与 config.delete 无关，避免坏任务卡住后续流程。
     */
    public void purgeHashTasks(String infoHash) {
        if (StrUtil.isBlank(infoHash)) {
            return;
        }
        String key = infoHash.toLowerCase(Locale.ROOT);
        // 清理后 re-list 确认；form 假成功时靠二次确认兜住
        for (int attempt = 1; attempt <= 3; attempt++) {
            List<OpenListTaskInfo> tasks = listTasksMatchingHash(key);
            if (tasks.isEmpty()) {
                if (attempt > 1) {
                    log.info("清理完成 hash={}（第 {} 次确认已清空）", key, attempt);
                }
                return;
            }
            Set<String> seen = new HashSet<>();
            for (OpenListTaskInfo task : tasks) {
                if (task == null || StrUtil.isBlank(task.getId()) || !seen.add(task.getId())) {
                    continue;
                }
                OpenListTaskInfo.State state = task.getState();
                log.info("清理离线任务 attempt={} tid={} state={} name={}",
                        attempt, task.getId(), state, task.getName());
                if (state == null
                        || state == OpenListTaskInfo.State.Pending
                        || state == OpenListTaskInfo.State.Running
                        || state == OpenListTaskInfo.State.Waiting_for_Retry
                        || state == OpenListTaskInfo.State.Preparing_to_Retry
                        || state == OpenListTaskInfo.State.Canceling
                        || state == OpenListTaskInfo.State.Failing) {
                    try {
                        taskCancel(task.getId());
                    } catch (Exception e) {
                        log.debug("cancel {} 失败: {}", task.getId(), e.getMessage());
                    }
                }
                try {
                    taskDelete(task.getId());
                } catch (Exception e) {
                    log.debug("delete {} 失败: {}", task.getId(), e.getMessage());
                }
            }
            // 给 OpenList/115 一点收敛时间再确认
            ThreadUtil.sleep(attempt == 1 ? 500L : 1000L);
        }
        List<OpenListTaskInfo> remain = listTasksMatchingHash(key);
        if (!remain.isEmpty()) {
            log.warn("清理后仍有残留 hash={} count={} tids={}",
                    key,
                    remain.size(),
                    remain.stream().map(OpenListTaskInfo::getId).filter(Objects::nonNull).toList());
        }
    }

    /**
     * 汇总 undone+done 中 name 含 infoHash 的任务。
     */
    private List<OpenListTaskInfo> listTasksMatchingHash(String infoHash) {
        if (StrUtil.isBlank(infoHash)) {
            return List.of();
        }
        String key = infoHash.toLowerCase(Locale.ROOT);
        List<OpenListTaskInfo> all = new ArrayList<>();
        try {
            all.addAll(taskUnDoneList());
        } catch (Exception e) {
            log.debug("读取 undone 失败: {}", e.getMessage());
        }
        try {
            all.addAll(taskDoneList());
        } catch (Exception e) {
            log.debug("读取 done 失败: {}", e.getMessage());
        }
        List<OpenListTaskInfo> matched = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (OpenListTaskInfo task : all) {
            if (task == null || StrUtil.isBlank(task.getId()) || !seen.add(task.getId())) {
                continue;
            }
            String name = task.getName();
            if (name != null && name.toLowerCase(Locale.ROOT).contains(key)) {
                matched.add(task);
            }
        }
        return matched;
    }

    /**
     * 分级轮询间隔：20s -> 1min -> 5min -> 10min
     */
    static long nextPollIntervalMs(int pollIndex) {
        if (pollIndex <= 0) {
            return POLL_INTERVAL_20S_MS;
        }
        if (pollIndex == 1) {
            return POLL_INTERVAL_1M_MS;
        }
        if (pollIndex == 2) {
            return POLL_INTERVAL_5M_MS;
        }
        return POLL_INTERVAL_10M_MS;
    }

    /**
     * 睡眠到下次轮询，但不超过 deadline。
     */
    private static void sleepUntilNextPoll(long deadlineMs, int pollIndex) {
        long remain = deadlineMs - System.currentTimeMillis();
        if (remain <= 0) {
            return;
        }
        long sleepMs = Math.min(nextPollIntervalMs(pollIndex), remain);
        // 分段睡眠，便于任务管理器取消尽快生效
        long end = System.currentTimeMillis() + sleepMs;
        while (System.currentTimeMillis() < end) {
            if (offlineCancelRequested.get() || RssTask.isCancelRequested()) {
                return;
            }
            long slice = Math.min(1000L, end - System.currentTimeMillis());
            if (slice <= 0) {
                break;
            }
            ThreadUtil.sleep(slice);
        }
    }

    /**
     * 任务管理器：当前正在等待的 OpenList infoHash
     */

    /**
     * 启动后异步回扫 OpenList 离线残留：
     * - 仅执行一次
     * - 只删除终态记录（Succeeded/Failed/Error/Canceled 等），不打断进行中下载
     * - 进行中任务只计数展示，留给用户手动「清理残留」
     */
    private void scheduleStartupResidualScan() {
        if (!startupResidualScanned.compareAndSet(false, true)) {
            return;
        }
        ThreadUtil.execute(() -> {
            try {
                // 错开登录瞬时流量
                ThreadUtil.sleep(800L);
                ResidualSnapshot snap = scanOfflineResiduals(true);
                residualSnapshot.set(snap);
                if (snap.getTerminalCount() > 0) {
                    CleanResult cleaned = cleanOfflineResiduals(false);
                    residualSnapshot.set(scanOfflineResiduals(false));
                    log.info("OpenList 启动回扫完成: active={} terminalCleaned={} remainTerminal={} err={}",
                            snap.getActiveCount(), cleaned.getCleaned(), residualSnapshot.get().getTerminalCount(), cleaned.getMessage());
                } else {
                    log.info("OpenList 启动回扫完成: active={} terminal=0", snap.getActiveCount());
                }
            } catch (Exception e) {
                log.warn("OpenList 启动回扫失败: {}", ExceptionUtils.getMessage(e));
                // 允许下次 login 再试
                startupResidualScanned.set(false);
            }
        });
    }

    /**
     * 扫描 offline 残留任务（undone + done）。
     * @param allowRemote 是否允许远程 list；false 时仅返回缓存
     */
    public ResidualSnapshot scanOfflineResiduals(boolean allowRemote) {
        if (!allowRemote) {
            ResidualSnapshot cached = residualSnapshot.get();
            return cached == null ? ResidualSnapshot.empty() : cached;
        }
        if (config == null) {
            throw new IllegalStateException("OpenList 未登录");
        }
        List<OpenListTaskInfo> tasks = listAllOfflineTasksStrict();
        ResidualSnapshot snap = buildResidualSnapshot(tasks, Set.copyOf(currentInfoHashes),
                residualCleaning.get(), System.currentTimeMillis());
        residualSnapshot.set(snap);
        return snap;
    }

    /**
     * 从离线任务列表构建残留快照（纯函数，便于单测）。
     * 预览最多保留 {@link #RESIDUAL_PREVIEW_LIMIT} 条明细；samples 仍截断前 5 条名称。
     */
    static ResidualSnapshot buildResidualSnapshot(List<OpenListTaskInfo> tasks,
                                                  Set<String> protectHashes,
                                                  boolean cleaning,
                                                  long scannedAt) {
        int active = 0;
        int terminal = 0;
        List<String> samples = new ArrayList<>();
        List<ResidualItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (tasks != null) {
            for (OpenListTaskInfo task : tasks) {
                if (task == null || StrUtil.isBlank(task.getId()) || !seen.add(task.getId())) {
                    continue;
                }
                ResidualKind kind = classifyResidual(task.getState());
                if (kind == ResidualKind.ACTIVE) {
                    active++;
                } else {
                    terminal++;
                }
                ResidualItem item = toResidualItem(task, protectHashes);
                if (items.size() < RESIDUAL_PREVIEW_LIMIT) {
                    items.add(item);
                }
                if (samples.size() < 5) {
                    samples.add(StrUtil.maxLength(item.getName(), 80));
                }
            }
        }
        int total = active + terminal;
        String message;
        if (total == 0) {
            message = "无离线残留";
        } else {
            message = StrFormatter.format("进行中 {} / 终态 {}（可预览 {} 条）",
                    active, terminal, items.size());
        }
        return new ResidualSnapshot()
                .setActiveCount(active)
                .setTerminalCount(terminal)
                .setTotalCount(total)
                .setScannedAt(scannedAt)
                .setSamples(samples)
                .setItems(items)
                .setCleaning(cleaning)
                .setMessage(message);
    }

    static ResidualItem toResidualItem(OpenListTaskInfo task, Set<String> protectHashes) {
        ResidualKind kind = classifyResidual(task == null ? null : task.getState());
        String id = task == null ? "" : StrUtil.blankToDefault(task.getId(), "");
        String name = task == null ? "" : StrUtil.blankToDefault(task.getName(), id);
        boolean protectedCurrent = task != null && protectHashes != null
                && protectHashes.stream().anyMatch(h -> taskNameContainsHash(task.getName(), h));
        String stateName = task != null && task.getState() != null ? task.getState().name() : "Unknown";
        String error = task == null ? null : StrUtil.blankToDefault(task.getError(), null);
        Integer progress = task == null ? null : task.getProgress();
        String totalBytes = task == null ? null : task.getTotalBytes();
        return new ResidualItem()
                .setId(id)
                .setName(StrUtil.maxLength(name, 160))
                .setState(stateName)
                .setKind(kind.name())
                .setProgress(progress)
                .setTotalBytes(totalBytes)
                .setError(error == null ? null : StrUtil.maxLength(error, 200))
                .setProtectedCurrent(protectedCurrent)
                .setAction(residualActionLabel(kind, protectedCurrent));
    }

    /**
     * 预览用动作文案：清理时会做什么。
     */
    static String residualActionLabel(ResidualKind kind, boolean protectedCurrent) {
        if (protectedCurrent) {
            return "保护中（当前 RSS 等待，清理时跳过）";
        }
        if (kind == ResidualKind.ACTIVE) {
            return "取消并删除记录";
        }
        return "删除记录";
    }

    private static final int RESIDUAL_PREVIEW_LIMIT = 30;

    /**
     * 清理 OpenList 离线残留。
     * @param includeActive true=取消并删除进行中；false=仅删终态记录（启动回扫）
     */
    public CleanResult cleanOfflineResiduals(boolean includeActive) {
        if (config == null) {
            return new CleanResult().setOk(false).setMessage("OpenList 未登录");
        }
        if (!residualCleaning.compareAndSet(false, true)) {
            return new CleanResult().setOk(false).setMessage("清理正在进行中，请稍候");
        }
        try {
            Set<String> protectHashes = Set.copyOf(currentInfoHashes);
            List<OpenListTaskInfo> tasks = listAllOfflineTasksStrict();
            int cleaned = 0;
            int skipped = 0;
            int failed = 0;
            Set<String> seen = new HashSet<>();
            for (OpenListTaskInfo task : tasks) {
                if (task == null || StrUtil.isBlank(task.getId()) || !seen.add(task.getId())) {
                    continue;
                }
                ResidualKind kind = classifyResidual(task.getState());
                if (kind == ResidualKind.ACTIVE && !includeActive) {
                    skipped++;
                    continue;
                }
                // 保护当前 RSS 正在等待的全部 hash，避免误杀在跑任务
                if (protectHashes.stream().anyMatch(h -> taskNameContainsHash(task.getName(), h))) {
                    skipped++;
                    log.info("清理残留跳过当前任务 tid={} hash={}", task.getId(), task.getName());
                    continue;
                }
                try {
                    if (kind == ResidualKind.ACTIVE) {
                        try {
                            taskCancel(task.getId());
                        } catch (Exception cancelEx) {
                            log.debug("清理残留 cancel 失败 {}: {}", task.getId(), cancelEx.getMessage());
                        }
                    }
                    // 终态与成功任务：只能删记录，不能“取消下载”
                    taskDelete(task.getId());
                    cleaned++;
                } catch (Exception e) {
                    failed++;
                    log.warn("清理残留失败 tid={} state={}: {}", task.getId(), task.getState(), e.getMessage());
                }
            }
            ResidualSnapshot after = scanOfflineResiduals(true);
            String msg = StrFormatter.format("清理完成 cleaned={} skipped={} failed={} remain={}",
                    cleaned, skipped, failed, after.getTotalCount());
            log.info("OpenList {}", msg);
            return new CleanResult()
                    .setOk(failed == 0)
                    .setCleaned(cleaned)
                    .setSkipped(skipped)
                    .setFailed(failed)
                    .setMessage(msg)
                    .setSnapshot(after);
        } finally {
            residualCleaning.set(false);
            ResidualSnapshot latest = residualSnapshot.get();
            if (latest != null) {
                residualSnapshot.set(latest.setCleaning(false));
            }
        }
    }

    public ResidualSnapshot getResidualSnapshot() {
        ResidualSnapshot snap = residualSnapshot.get();
        if (snap == null) {
            return ResidualSnapshot.empty();
        }
        return snap.setCleaning(residualCleaning.get());
    }

    public TempDirResidualSnapshot getTempDirResidualSnapshot() {
        TempDirResidualSnapshot snap = tempDirResidualSnapshot.get();
        if (snap == null) {
            return TempDirResidualSnapshot.empty();
        }
        return snap.setCleaning(tempDirResidualCleaning.get());
    }

    /**
     * 扫描各订阅保存路径下的临时目录残留（文件系统，非离线任务记录）。
     * 多订阅优化：路径归一化去重、跳过停用订阅、浅层 list 代替递归 findFiles、候选预算。
     */
    public TempDirResidualSnapshot scanTempDirResiduals(boolean allowRemote) {
        if (!allowRemote) {
            TempDirResidualSnapshot cached = tempDirResidualSnapshot.get();
            return cached == null ? TempDirResidualSnapshot.empty() : cached;
        }
        if (config == null) {
            throw new IllegalStateException("OpenList 未登录");
        }
        Set<String> activeTempDirs = new HashSet<>();
        OfflineWaitSnapshot wait = offlineWaitSnapshot.get();
        if (wait != null) {
            if (StrUtil.isNotBlank(wait.getTempDirName())) {
                activeTempDirs.add(wait.getTempDirName());
            }
            if (StrUtil.isNotBlank(wait.getTitle())) {
                activeTempDirs.add(wait.getTitle());
            }
        }

        List<TempDirResidualItem> items = new ArrayList<>();
        List<String> samples = new ArrayList<>();
        int cleanable = 0;
        int protectedCount = 0;
        int keep = 0;
        int inspectBudget = TEMP_DIR_INSPECT_BUDGET;
        int truncatedCandidates = 0;

        // pathKey -> {savePath, seasonKeys}
        Map<String, PathScanTarget> pathTargets = new LinkedHashMap<>();

        List<Ani> aniList;
        try {
            aniList = ani.rss.util.other.AniUtil.getAniList();
        } catch (Exception e) {
            aniList = List.of();
        }
        ani.rss.service.DownloadService downloadService = null;
        try {
            downloadService = cn.hutool.extra.spring.SpringUtil.getBean(ani.rss.service.DownloadService.class);
        } catch (Exception ignored) {
        }

        for (Ani ani : aniList) {
            if (ani == null || Boolean.FALSE.equals(ani.getEnable())) {
                continue;
            }
            String savePath;
            try {
                // 优先已落盘的自定义路径，避免 getDownloadPath 触发 jpTitle/BGM 网络
                if (StrUtil.isNotBlank(ani.getDownloadPath()) && Boolean.TRUE.equals(ani.getCustomDownloadPath())) {
                    savePath = ani.getDownloadPath();
                } else if (downloadService != null) {
                    savePath = downloadService.getDownloadPath(ani);
                } else {
                    savePath = StrUtil.blankToDefault(ani.getDownloadPath(), "");
                }
            } catch (Exception e) {
                continue;
            }
            if (StrUtil.isBlank(savePath)) {
                continue;
            }
            String normalized = normalizeOpenListPath(savePath);
            if (StrUtil.isBlank(normalized)) {
                continue;
            }
            String pathKey = normalized.toLowerCase(Locale.ROOT);
            PathScanTarget target = pathTargets.get(pathKey);
            if (target == null) {
                target = new PathScanTarget(normalized);
                pathTargets.put(pathKey, target);
            }
            if (ani.getSeason() != null) {
                target.seasonKeys.add(String.format(Locale.ROOT, "S%02d", ani.getSeason()));
            }
        }

        for (PathScanTarget target : pathTargets.values()) {
            String savePath = target.savePath;
            List<OpenListFileInfo> top;
            try {
                // 扫描优先用缓存友好 list，避免对每个路径 force refresh
                top = fsList(savePath, false);
            } catch (Exception e) {
                log.debug("扫描临时目录失败 {}: {}", savePath, e.getMessage());
                continue;
            }
            if (top == null || top.isEmpty()) {
                continue;
            }
            Set<String> topVideoEpisodeKeys = new HashSet<>();
            Set<String> topVideoNames = new HashSet<>();
            for (OpenListFileInfo f : top) {
                if (f == null || Boolean.TRUE.equals(f.getIsDir())) {
                    continue;
                }
                String name = f.getName();
                if (StrUtil.isBlank(name) || !(FileUtils.isVideoFormat(name) || FileUtils.isSubtitleFormat(name))) {
                    continue;
                }
                topVideoNames.add(name);
                if (ReUtil.contains(StringEnum.SEASON_REG, name)) {
                    String ep = ReUtil.get(StringEnum.SEASON_REG, name, 0);
                    if (StrUtil.isNotBlank(ep)) {
                        topVideoEpisodeKeys.add(ep.toLowerCase(Locale.ROOT));
                    }
                }
            }
            for (OpenListFileInfo entry : top) {
                if (entry == null || !Boolean.TRUE.equals(entry.getIsDir()) || StrUtil.isBlank(entry.getName())) {
                    continue;
                }
                String dirName = entry.getName();
                boolean looksTemp = false;
                for (String sk : target.seasonKeys) {
                    if (TempDirResidualPolicy.looksLikeTempEpisodeDir(dirName, sk)) {
                        looksTemp = true;
                        break;
                    }
                }
                if (!looksTemp && TempDirResidualPolicy.looksLikeTempEpisodeDir(dirName, null)) {
                    looksTemp = true;
                }
                if (!looksTemp && !ReUtil.contains(StringEnum.SEASON_REG, dirName)) {
                    if (dirName.contains(".") || dirName.length() < 2) {
                        continue;
                    }
                }

                if (inspectBudget <= 0) {
                    truncatedCandidates++;
                    continue;
                }
                inspectBudget--;

                String tempPath = savePath + "/" + dirName;
                boolean hasProtectedMedia = false;
                boolean junkOnly = true;
                boolean empty = true;
                try {
                    // 浅层 list：残留分类只需一级信号，避免递归 findFiles 打爆 API
                    List<OpenListFileInfo> inside = fsList(tempPath, false);
                    if (inside == null) {
                        inside = List.of();
                    }
                    for (OpenListFileInfo f : inside) {
                        if (f == null) continue;
                        empty = false;
                        if (Boolean.TRUE.equals(f.getIsDir())) {
                            // 有子目录则不是纯垃圾空壳
                            junkOnly = false;
                            continue;
                        }
                        if (isProtectedTempFile(f)) {
                            hasProtectedMedia = true;
                            junkOnly = false;
                        } else if (!isJunkTempFile(f)) {
                            junkOnly = false;
                        }
                    }
                } catch (Exception e) {
                    junkOnly = false;
                }
                if (empty) {
                    junkOnly = true;
                    hasProtectedMedia = false;
                }
                boolean hasFinalSibling = false;
                if (ReUtil.contains(StringEnum.SEASON_REG, dirName)) {
                    String ep = ReUtil.get(StringEnum.SEASON_REG, dirName, 0);
                    if (StrUtil.isNotBlank(ep) && topVideoEpisodeKeys.contains(ep.toLowerCase(Locale.ROOT))) {
                        hasFinalSibling = true;
                    }
                } else if (!topVideoNames.isEmpty()) {
                    hasFinalSibling = topVideoNames.stream()
                            .anyMatch(n -> !n.equalsIgnoreCase(dirName) && FileUtils.isVideoFormat(n));
                }
                TempDirResidualPolicy.Decision decision = TempDirResidualPolicy.decide(
                        dirName, hasFinalSibling, hasProtectedMedia, junkOnly, activeTempDirs);
                String action = switch (decision.action()) {
                    case FORCE_CLEAN -> "强制删除临时目录（最终成片已在顶层）";
                    case JUNK_CLEAN -> "删除空壳/垃圾临时目录";
                    case PROTECT_ACTIVE -> "保护中（当前下载占用）";
                    case KEEP -> "保留（需人工确认）";
                };
                if (decision.action() == TempDirResidualPolicy.Action.FORCE_CLEAN
                        || decision.action() == TempDirResidualPolicy.Action.JUNK_CLEAN) {
                    cleanable++;
                } else if (decision.action() == TempDirResidualPolicy.Action.PROTECT_ACTIVE) {
                    protectedCount++;
                } else {
                    keep++;
                }
                if (items.size() < TEMP_DIR_PREVIEW_LIMIT) {
                    items.add(new TempDirResidualItem()
                            .setId(savePath + "/" + dirName)
                            .setName(StrUtil.maxLength(dirName, 160))
                            .setSavePath(savePath)
                            .setState(decision.action().name())
                            .setKind("TEMP_DIR")
                            .setAction(action)
                            .setError(decision.reason())
                            .setProtectedCurrent(decision.action() == TempDirResidualPolicy.Action.PROTECT_ACTIVE)
                            .setCleanable(decision.action() == TempDirResidualPolicy.Action.FORCE_CLEAN
                                    || decision.action() == TempDirResidualPolicy.Action.JUNK_CLEAN));
                }
                if (samples.size() < 5) {
                    samples.add(StrUtil.maxLength(dirName, 80));
                }
            }
        }

        int total = cleanable + protectedCount + keep;
        String message;
        if (total == 0) {
            message = "无临时目录残留";
        } else {
            message = StrFormatter.format("可清理 {} / 保护 {} / 保留 {}（预览 {} 条，路径 {}）",
                    cleanable, protectedCount, keep, items.size(), pathTargets.size());
            if (truncatedCandidates > 0) {
                message = message + StrFormatter.format("，另有 {} 个候选未深检（预算用尽，请再扫）", truncatedCandidates);
            }
        }
        TempDirResidualSnapshot snap = new TempDirResidualSnapshot()
                .setTotalCount(total)
                .setCleanableCount(cleanable)
                .setProtectedCount(protectedCount)
                .setKeepCount(keep)
                .setScannedAt(System.currentTimeMillis())
                .setCleaning(tempDirResidualCleaning.get())
                .setMessage(message)
                .setSamples(samples)
                .setItems(items);
        tempDirResidualSnapshot.set(snap);
        return snap;
    }

    private static String normalizeOpenListPath(String savePath) {
        if (StrUtil.isBlank(savePath)) {
            return "";
        }
        String p = savePath.replace('\\', '/');
        p = ReUtil.replaceAll(p, "^[A-z]:", "");
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static final class PathScanTarget {
        final String savePath;
        final Set<String> seasonKeys = new HashSet<>();

        PathScanTarget(String savePath) {
            this.savePath = savePath;
        }
    }

    /**
     * 仅清理 FORCE_CLEAN / JUNK_CLEAN 临时目录；保护 PROTECT_ACTIVE；KEEP 不自动删。
     */
    public CleanResult cleanTempDirResiduals() {
        if (config == null) {
            return new CleanResult().setOk(false).setMessage("OpenList 未登录");
        }
        if (!tempDirResidualCleaning.compareAndSet(false, true)) {
            return new CleanResult().setOk(false).setMessage("临时目录清理正在进行中");
        }
        try {
            TempDirResidualSnapshot before = scanTempDirResiduals(true);
            int cleaned = 0;
            int skipped = 0;
            int failed = 0;
            if (before.getItems() != null) {
                for (TempDirResidualItem item : before.getItems()) {
                    if (item == null || !Boolean.TRUE.equals(item.getCleanable())) {
                        skipped++;
                        continue;
                    }
                    try {
                        cleanupTempDownloadDir(item.getSavePath(), item.getName(), true);
                        cleaned++;
                    } catch (Exception e) {
                        failed++;
                        log.warn("清理临时目录失败 {}/{}: {}", item.getSavePath(), item.getName(), e.getMessage());
                    }
                }
            }
            TempDirResidualSnapshot after = scanTempDirResiduals(true);
            String msg = StrFormatter.format("临时目录清理完成 cleaned={} skipped={} failed={} remain={}",
                    cleaned, skipped, failed, after.getTotalCount());
            log.info("OpenList {}", msg);
            return new CleanResult()
                    .setOk(failed == 0)
                    .setCleaned(cleaned)
                    .setSkipped(skipped)
                    .setFailed(failed)
                    .setMessage(msg);
        } finally {
            tempDirResidualCleaning.set(false);
            TempDirResidualSnapshot latest = tempDirResidualSnapshot.get();
            if (latest != null) {
                tempDirResidualSnapshot.set(latest.setCleaning(false));
            }
        }
    }

    private List<OpenListTaskInfo> listAllOfflineTasksStrict() {
        List<OpenListTaskInfo> all = new ArrayList<>();
        List<OpenListTaskInfo> undone = taskUnDoneList();
        if (undone != null) {
            all.addAll(undone);
        }
        List<OpenListTaskInfo> done = taskDoneList();
        if (done != null) {
            all.addAll(done);
        }
        return all;
    }

    static boolean taskNameContainsHash(String name, String hash) {
        if (StrUtil.isBlank(name) || StrUtil.isBlank(hash)) {
            return false;
        }
        return name.toLowerCase(Locale.ROOT).contains(hash.toLowerCase(Locale.ROOT));
    }

    /**
     * ACTIVE=进行中可 cancel；TERMINAL=只能删记录
     */
    static ResidualKind classifyResidual(OpenListTaskInfo.State state) {
        if (state == null) {
            return ResidualKind.TERMINAL;
        }
        return switch (state) {
            case Pending, Running, Waiting_for_Retry, Preparing_to_Retry -> ResidualKind.ACTIVE;
            case Succeeded, Error, Failing, Failed, Canceling, Canceled -> ResidualKind.TERMINAL;
        };
    }

    enum ResidualKind {
        ACTIVE,
        TERMINAL
    }

    @lombok.Data
    @lombok.experimental.Accessors(chain = true)
    public static class ResidualSnapshot implements java.io.Serializable {
        private int activeCount;
        private int terminalCount;
        private int totalCount;
        private Long scannedAt;
        private Boolean cleaning;
        private String message;
        private List<String> samples;
        /** 结构化预览明细（最多 {@link #RESIDUAL_PREVIEW_LIMIT} 条） */
        private List<ResidualItem> items;

        public static ResidualSnapshot empty() {
            return new ResidualSnapshot()
                    .setActiveCount(0)
                    .setTerminalCount(0)
                    .setTotalCount(0)
                    .setCleaning(false)
                    .setSamples(List.of())
                    .setItems(List.of());
        }
    }

    @lombok.Data
    @lombok.experimental.Accessors(chain = true)
    public static class ResidualItem implements java.io.Serializable {
        private String id;
        private String name;
        private String state;
        /** ACTIVE / TERMINAL */
        private String kind;
        private Integer progress;
        private String totalBytes;
        private String error;
        private Boolean protectedCurrent;
        /** 清理时将执行的动作说明 */
        private String action;
    }

    @lombok.Data
    @lombok.experimental.Accessors(chain = true)
    public static class CleanResult implements java.io.Serializable {
        private boolean ok;
        private int cleaned;
        private int skipped;
        private int failed;
        private String message;
        private ResidualSnapshot snapshot;
    }


    public String getCurrentInfoHash() {
        // 兼容单值 getter：多 hash 并行时返回任一在等 hash（任务管理器展示用）
        return currentInfoHashes.stream().findFirst().orElse(null);
    }

    public OfflineWaitSnapshot getOfflineWaitSnapshot() {
        return offlineWaitSnapshot.get();
    }

    private static void updateOfflineWait(String hash, String title, String tempDirName,
                                          Integer progress, String state, long deadlineMs) {
        offlineWaitSnapshot.set(new OfflineWaitSnapshot()
                .setHash(hash)
                .setTitle(title)
                .setTempDirName(StrUtil.blankToDefault(tempDirName, title))
                .setProgress(progress)
                .setState(state)
                .setDeadlineMs(deadlineMs)
                .setUpdatedAt(System.currentTimeMillis()));
    }

    private static void clearOfflineWait(String hash) {
        OfflineWaitSnapshot snap = offlineWaitSnapshot.get();
        if (snap != null && (hash == null || hash.equalsIgnoreCase(snap.getHash()))) {
            offlineWaitSnapshot.compareAndSet(snap, null);
        }
    }

    @lombok.Data
    @lombok.experimental.Accessors(chain = true)
    public static class OfflineWaitSnapshot implements java.io.Serializable {
        private String hash;
        private String title;
        /** 实际临时目录名（合集可能与 reName/title 不同） */
        private String tempDirName;
        private Integer progress;
        private String state;
        private Long deadlineMs;
        private Long updatedAt;
    }

    @lombok.Data
    @lombok.experimental.Accessors(chain = true)
    public static class TempDirResidualSnapshot implements java.io.Serializable {
        private int totalCount;
        private int cleanableCount;
        private int protectedCount;
        private int keepCount;
        private Long scannedAt;
        private Boolean cleaning;
        private String message;
        private List<String> samples;
        private List<TempDirResidualItem> items;

        public static TempDirResidualSnapshot empty() {
            return new TempDirResidualSnapshot()
                    .setTotalCount(0)
                    .setCleanableCount(0)
                    .setProtectedCount(0)
                    .setKeepCount(0)
                    .setCleaning(false)
                    .setSamples(List.of())
                    .setItems(List.of());
        }
    }

    @lombok.Data
    @lombok.experimental.Accessors(chain = true)
    public static class TempDirResidualItem implements java.io.Serializable {
        private String id;
        private String name;
        private String savePath;
        private String state;
        private String kind;
        private String action;
        private String error;
        private Boolean protectedCurrent;
        private Boolean cleanable;
    }


    /**
     * 用户取消 RSS 任务时：打断等待并清理当前 hash 的 OpenList 离线任务。
     * 边界：
     * - Pending/Running 等：先 cancel 再 delete 记录
     * - Failed/Error/Canceled/Succeeded：无法“取消下载”，仅 delete 记录（文件已成功落盘不会回滚）
     * - 未登录：只释放本地占用，不打远程 API
     */
    public void cancelCurrentOffline() {
        offlineCancelRequested.set(true);
        // 多 hash 并行时取消全部在等任务（快照避免并发修改）
        Set<String> hashes = Set.copyOf(currentInfoHashes);
        if (hashes.isEmpty()) {
            return;
        }
        // 未 login 时 config 为空：只打断本地等待，不打远程 API
        if (config == null) {
            log.warn("任务管理器取消，OpenList 未登录，仅释放本地占用 hashes={}", hashes);
            for (String hash : hashes) {
                clearDuplicateMagnet(hash);
                inFlightTasks.remove(hash);
                currentInfoHashes.remove(hash);
            }
            return;
        }
        log.warn("任务管理器取消，清理 OpenList 离线任务 hashes={}（进行中 cancel+delete；终态仅 delete 记录）", hashes);
        for (String hash : hashes) {
            try {
                // purgeHashTasks：进行中先 cancel 再 delete；Succeeded/Failed 等终态只 delete 记录
                purgeHashTasks(hash);
            } catch (Exception e) {
                log.warn("取消清理 OpenList 失败 {}: {}", hash, e.getMessage());
            }
            clearDuplicateMagnet(hash);
            inFlightTasks.remove(hash);
            currentInfoHashes.remove(hash);
        }
    }

    /**
     * 轮询/等待是否应中止（超时仍由 deadline 负责）
     */
    private boolean shouldAbortWait() {
        return offlineCancelRequested.get() || RssTask.isCancelRequested();
    }

    private TimeoutFileInspection inspectTimeoutFiles(String tempPath, String savePath, String reName,
                                                       List<Double> expectedEpisodes) {
        TimeoutFileSnapshot first = freshEpisodeVideoSnapshot(tempPath, savePath, reName, expectedEpisodes);
        if (!snapshotCoversExpectedEpisodes(first, expectedEpisodes) || first.totalBytes() <= 0) {
            return new TimeoutFileInspection(false, first.videoCount(), first.totalBytes(), false);
        }
        ThreadUtil.sleep(TIMEOUT_FILE_STABILITY_WAIT_MS);
        TimeoutFileSnapshot second = freshEpisodeVideoSnapshot(tempPath, savePath, reName, expectedEpisodes);
        boolean stable = first.files().equals(second.files()) && second.totalBytes() > 0;
        boolean ready = stable && snapshotCoversExpectedEpisodes(second, expectedEpisodes);
        return new TimeoutFileInspection(ready, second.videoCount(), second.totalBytes(), stable);
    }

    private TimeoutFileSnapshot freshEpisodeVideoSnapshot(String tempPath, String savePath, String reName,
                                                          List<Double> expectedEpisodes) {
        api.invalidateFindFilesCache();
        List<OpenListFileInfo> videos = expectedEpisodeVideos(findEpisodeFiles(tempPath, reName), expectedEpisodes);
        if (videos.isEmpty() && !Objects.equals(tempPath, savePath)) {
            api.invalidateFindFilesCache();
            // 最终目录通常是模板命名；reName 可能是合集临时目录名，回退扫最终目录视频
            videos = expectedEpisodeVideos(findEpisodeFiles(savePath, reName), expectedEpisodes);
            if (videos.isEmpty()) {
                videos = expectedEpisodeVideos(findFiles(savePath), expectedEpisodes);
            }
            if (videos.isEmpty()) {
                // 115 离线完成后的文件可能落在根目录「云下载」而非目标路径：兜底扫描
                videos = findCloudDownloadEpisodeVideos(expectedEpisodes);
            }
        }
        LinkedHashMap<String, Long> files = new LinkedHashMap<>();
        for (OpenListFileInfo video : videos) {
            String key = StrUtil.blankToDefault(video.getPath(), "") + "/" + video.getName();
            files.put(key, ObjectUtil.defaultIfNull(video.getSize(), 0L));
        }
        return snapshotTimeoutFiles(files);
    }

    private List<OpenListFileInfo> expectedEpisodeVideos(List<OpenListFileInfo> files, List<Double> expectedEpisodes) {
        Set<Double> expected = normalizedEpisodes(expectedEpisodes);
        return files.stream()
                // 目录可能以 .mkv/.mp4 命名（如 115 云下载按文件名建目录）：必须排除，避免把目录当视频
                // 重命名/移动后产生「文件夹.扩展名/同名文件」嵌套
                .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                .filter(f -> FileUtils.isVideoFormat(f.getName()))
                .filter(f -> expected.isEmpty() || expected.contains(parseEpisodeNumber(extractEpisodeFromFileName(f.getName()))))
                .toList();
    }

    boolean snapshotCoversExpectedEpisodes(TimeoutFileSnapshot snapshot, List<Double> expectedEpisodes) {
        if (snapshot == null || snapshot.videoCount() == 0) {
            return false;
        }
        Set<Double> expected = normalizedEpisodes(expectedEpisodes);
        if (expected.isEmpty()) {
            return true;
        }
        Set<Double> actual = snapshot.files().keySet().stream()
                .map(this::extractEpisodeFromFileName)
                .map(OpenList::parseEpisodeNumber)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return actual.containsAll(expected);
    }

    private static Set<Double> normalizedEpisodes(List<Double> episodes) {
        return episodes == null
                ? Set.of()
                : episodes.stream().filter(Objects::nonNull).collect(Collectors.toSet());
    }

    static TimeoutFileSnapshot snapshotTimeoutFiles(Map<String, Long> files) {
        LinkedHashMap<String, Long> normalized = new LinkedHashMap<>();
        if (files != null) {
            files.forEach((name, size) -> normalized.put(name, ObjectUtil.defaultIfNull(size, 0L)));
        }
        long totalBytes = normalized.values().stream().mapToLong(Long::longValue).sum();
        return new TimeoutFileSnapshot(Map.copyOf(normalized), normalized.size(), totalBytes);
    }

    record TimeoutFileSnapshot(Map<String, Long> files, int videoCount, long totalBytes) {
    }

    record TimeoutFileInspection(boolean ready, int videoCount, long totalBytes, boolean stable) {
    }

    /**
     * 快速判断目录下是否已有视频（走 findFiles 缓存）
     */
    private boolean hasVideoFile(String path) {
        return findFiles(path).stream().anyMatch(f -> FileUtils.isVideoFormat(f.getName()));
    }

    /**
     * 是否存在“本集”视频。禁止用整季 savePath 任意视频冒充当前集完成。
     */
    private boolean hasEpisodeVideos(String dir, String reName, List<Double> expectedEpisodes) {
        List<OpenListFileInfo> videos = expectedEpisodeVideos(findEpisodeFiles(dir, reName), expectedEpisodes);
        if (normalizedEpisodes(expectedEpisodes).isEmpty()) {
            return !videos.isEmpty();
        }
        return validateCollectionEpisodes(expectedEpisodes, videos).missing().isEmpty();
    }

    /**
     * 在 dir 下找本集相关文件：
     * 1) 直接子路径名包含 reName 的目录递归（临时目录 path 通常以 reName 结尾）
     * 2) 文件名包含 SEASON 片段（S01E03）或完整 reName
     */
    static boolean isEpisodeFileName(String fileName, String reName) {
        if (StrUtil.isBlank(fileName) || StrUtil.isBlank(reName)) {
            return false;
        }
        String name = fileName;
        String rn = reName;
        if (name.equalsIgnoreCase(rn) || name.toLowerCase(Locale.ROOT).contains(rn.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String season = ReUtil.get(StringEnum.SEASON_REG, rn, 0);
        return StrUtil.isNotBlank(season) && name.toUpperCase(Locale.ROOT).contains(season.toUpperCase(Locale.ROOT));
    }

    private List<OpenListFileInfo> findEpisodeFiles(String dir, String reName) {
        if (StrUtil.isBlank(dir) || StrUtil.isBlank(reName)) {
            return List.of();
        }
        // 临时目录本身就是 .../reName
        String normalizedDir = dir.replace('\\', '/');
        String normalizedRe = reName.replace('\\', '/');
        if (normalizedDir.toLowerCase(Locale.ROOT).endsWith("/" + normalizedRe.toLowerCase(Locale.ROOT))
                || normalizedDir.equalsIgnoreCase(normalizedRe)) {
            return findFiles(dir);
        }
        return findFiles(dir).stream()
                .filter(f -> isEpisodeFileName(f.getName(), reName))
                .toList();
    }

    /**
     * 获取目录下及子目录的文件
     *
     * @param path 目录
     * @return 文件列表
     */
    public synchronized List<OpenListFileInfo> findFiles(String path) {
        return api.findFiles(path);
    }

    private <T> T retryIdempotent(String action, Supplier<T> supplier) {
        return api.retryIdempotent(action, supplier);
    }

    static <T> T retryIdempotent(String action, Supplier<T> supplier, long[] retryDelaysMs) {
        return OpenListApi.retryIdempotent(action, supplier, retryDelaysMs);
    }

    static boolean isTransientOpenListFailure(Throwable throwable) {
        return OpenListApi.isTransientOpenListFailure(throwable);
    }

    /**
     * HTTP 层 ok 且 JSON code==200 才算 OpenList 业务成功（委托 OpenListApi，供测试/兼容）
     */
    static boolean isOpenListBusinessOk(boolean httpOk, String body) {
        return OpenListApi.isOpenListBusinessOk(httpOk, body);
    }

    /**
     * get api
     *
     * @param action
     * @return
     */
    public synchronized HttpRequest getApi(String action) {
        return api.getApi(action);
    }

    /**
     * post api
     *
     * @param action
     * @return
     */
    public synchronized HttpRequest postApi(String action) {
        return api.postApi(action);
    }

}
