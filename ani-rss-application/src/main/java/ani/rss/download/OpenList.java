package ani.rss.download;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.*;
import ani.rss.entity.web.Header;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.StringEnum;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.NotificationUtil;
import ani.rss.util.other.RenameUtil;
import ani.rss.util.other.TempDirResidualPolicy;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.collection.ListUtil;
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
import cn.hutool.http.HttpResponse;
import cn.hutool.http.Method;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenList implements BaseDownload {
    private Config config;

    // 提交中去重：防止同一 infoHash 被重复提交到 OpenList
    private static final Set<String> inFlightTasks = ConcurrentHashMap.newKeySet();
    // 按 infoHash 串行，不同 hash 可并行
    private static final ConcurrentHashMap<String, Object> DOWNLOAD_LOCKS = new ConcurrentHashMap<>();
    /** 当前正在等待的离线 hash（任务管理器展示 / 取消清理） */
    private static final AtomicReference<String> currentInfoHash = new AtomicReference<>();
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

    // API 最小间隔限流（替代每次固定 sleep 2s）
    private static final long API_MIN_INTERVAL_MS = 300L;
    private static final Object API_RATE_LOCK = new Object();
    private static volatile long lastApiCallAt = 0L;

    // findFiles 短缓存，轮询期间减少递归 list
    private static final long FIND_FILES_TTL_MS = 3000L;
    private static final Map<String, CachedFileList> findFilesCache = new ConcurrentHashMap<>();

    private static final int IDEMPOTENT_API_MAX_ATTEMPTS = 3;
    private static final long[] IDEMPOTENT_API_RETRY_DELAYS_MS = {500L, 1500L};
    private static final long TIMEOUT_FILE_STABILITY_WAIT_MS = 2000L;

    /**
     * 115/OpenList 返回「任务已存在(10008)」后，短时间内禁止对同一 magnet 再 add。
     * 避免 DownloadService 外层 10 次重试把 115 打爆并误报坏种。
     */
    private static final long DUPLICATE_MAGNET_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(30);
    private static final ConcurrentHashMap<String, Long> DUPLICATE_MAGNET_UNTIL = new ConcurrentHashMap<>();

    private static final class CachedFileList {
        final long expireAt;
        final List<OpenListFileInfo> files;

        CachedFileList(List<OpenListFileInfo> files, long ttlMs) {
            this.files = files;
            this.expireAt = System.currentTimeMillis() + ttlMs;
        }
    }

    @Override
    public Boolean login(Boolean test, Config config) {
        this.config = config;
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
            return downloadLocked(ani, item, savePath, torrentFile, magnet, hashKey);
        }
    }

    private Boolean downloadLocked(Ani ani, Item item, String savePath, File torrentFile,
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
                        return false;
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
                            return false;
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
                return true;
            }
            claimedInFlight = true;
            currentInfoHash.set(infoHash);
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
                log.warn("磁力近期已报 10008/任务已存在，跳过重复提交，仅等待文件 {}", reName);
                tid = findExistingTaskIdPreferActive(infoHash);
            }

            // 洗版：仅在即将新提交离线时做一次；复用/10008 等待路径禁止洗，避免重试风暴删掉目标
            if (!skipNewSubmit && StrUtil.isBlank(tid) && standbyRss && delete && !coexist) {
                String s = ReUtil.get(StringEnum.SEASON_REG, finalRenameBase, 0);
                if (StrUtil.isNotBlank(s)) {
                    String finalSavePath = savePath;
                    String seasonKey = s;
                    fsList(savePath, true)
                            .stream()
                            .map(OpenListFileInfo::getName)
                            .filter(name -> name != null && name.contains(seasonKey))
                            .forEach(name -> {
                                fsRemove(finalSavePath, List.of(name));
                                log.info("已开启备用RSS, 自动删除 {}/{}", finalSavePath, name);
                            });
                }
            }

            // 无进行中任务时才提交离线
            if (StrUtil.isBlank(tid) && !skipNewSubmit) {
                tid = fsAddOfflineDownload(magnet, path);
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
            long retry = 0;
            // 唯一截止：用户配置的【离线超时】
            int waitMinutes = ObjectUtil.defaultIfNull(alistDownloadTimeout, 30);
            waitMinutes = Math.max(waitMinutes, 1);
            long deadlineMs = DateUtil.offsetMinute(startTime, waitMinutes).getTime();
            int pollIndex = 0;
            if (skipNewSubmit) {
                log.info("10008 冷却期内不重复提交，按离线超时 {} 分钟等待文件/任务 {}", waitMinutes, reName);
            }

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

                    // Pending/Running：只等待，不触发重试、不扫目录
                    if (state == OpenListTaskInfo.State.Pending
                            || state == OpenListTaskInfo.State.Running
                            || state == OpenListTaskInfo.State.Waiting_for_Retry
                            || state == OpenListTaskInfo.State.Preparing_to_Retry) {
                        sleepUntilNextPoll(deadlineMs, pollIndex++);
                        continue;
                    }

                    // 10008 优先于文件兜底：否则会误把同季其它集视频当成成功，又因临时目录无文件 return false
                    if (isDuplicateOfflineError(taskInfo.getError())) {
                        log.warn("离线任务报告任务已存在(10008) tid={} state={}，转为等待已有任务/文件 {}",
                                tid, state, reName);
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
                            tid = otherTid;
                            log.info("切换到已存在离线任务 tid={} {}", tid, reName);
                        } else {
                            tid = null; // 进入无 tid 文件轮询
                        }
                        sleepUntilNextPoll(deadlineMs, pollIndex++);
                        continue;
                    }
                    // Error/Failed：仅当本集临时目录或最终目录命中本集文件时才当完成
                    if (hasEpisodeVideos(path, tempDirName, item.getEpisodeRange())
                        || hasEpisodeVideos(savePath, finalRenameBase, item.getEpisodeRange())) {
                        log.info("本集资源已就绪，OpenList 任务状态异常但文件可用，继续后处理 {}", reName);
                        clearDuplicateMagnet(infoHash);
                        break;
                    }
                    // 终态任务（Failed/Error/Canceled）：无文件才算失败。Failed 不等于坏种。
                    if (state == OpenListTaskInfo.State.Failed
                            || state == OpenListTaskInfo.State.Error
                            || state == OpenListTaskInfo.State.Canceled) {
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
                        || hasEpisodeVideos(savePath, finalRenameBase, item.getEpisodeRange())) {
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
            List<OpenListFileInfo> videoList = openListFileInfos.stream()
                    .filter(f -> FileUtils.isVideoFormat(f.getName()))
                    .sorted(Comparator.comparingLong(OpenListFileInfo::getSize).reversed())
                    .toList();
            List<OpenListFileInfo> subtitleList = openListFileInfos.stream()
                    .filter(f -> FileUtils.isSubtitleFormat(f.getName()))
                    .toList();

            if (videoList.isEmpty()) {
                // 可能已在 savePath 落盘（历史完成/被其它路径移动）
                // 必须排除临时目录内的文件，否则「还在临时目录」会被误判为已完成并跳过移动
                List<OpenListFileInfo> saveFiles = findEpisodeFiles(savePath, finalRenameBase).stream()
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
                if (!videoList.isEmpty()) {
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
                // 找到原始文件所在目录
                Optional<OpenListFileInfo> fileInfo = openListFileInfos.stream()
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
            } else if (tempDownloadDir != null) {
                // 需要移动的视频/字幕已确认在最终目录顶层 → 强制删除临时目录
                cleanupTempDownloadDir(savePath, tempDirName, true);
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
            // 仅清理本线程占用的 inFlight，避免误删其它线程标记
            if (claimedInFlight) {
                inFlightTasks.remove(infoHash);
                currentInfoHash.compareAndSet(infoHash, null);
                clearOfflineWait(infoHash);
            }
            // 配置要求删除时清理离线任务记录（超时 purge 已处理同 hash）
            if (tid != null && delete) {
                try {
                    taskDelete(tid);
                } catch (Exception e) {
                    log.warn("删除离线任务失败 {}: {}", tid, e.getMessage());
                }
            }
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
        invalidateFindFilesCache();
        retryIdempotent("fs/mkdir " + path, () -> {
            postApi("fs/mkdir")
                    .body(GsonStatic.toJson(Map.of(
                            "path", path
                    )))
                    .then(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        int code = jsonObject.get("code").getAsInt();
                        String message = jsonObject.has("message") ? jsonObject.get("message").getAsString() : "";
                        if (code == 200) {
                            log.info("创建文件夹: {}", path);
                            return;
                        }

                        if (!message.startsWith("failed to check if dir exists")) {
                            throw new IllegalStateException("fs/mkdir 失败 code=" + code + " " + message);
                        }

                        Path pathObj = Path.of(path);
                        if (pathObj.getNameCount() <= 1) {
                            throw new IllegalStateException("fs/mkdir 失败 code=" + code + " " + message);
                        }

                        String parentPath = pathObj
                                .getParent()
                                .toString()
                                .replace('\\', '/');
                        mkdir(parentPath);
                        mkdir(path);
                    });
            return null;
        });
    }

    /**
     * 移动文件
     *
     * @param srcDir 原目录
     * @param dstDir 目标目录
     * @param names  文件名
     */
    public void fsMove(String srcDir, String dstDir, List<String> names) {
        invalidateFindFilesCache();
        postApi("fs/move")
                .body(GsonStatic.toJson(Map.of(
                        "src_dir", srcDir,
                        "dst_dir", dstDir,
                        "names", names
                ))).then(res -> {
                    log.info(res.body());
                    assertOpenListOk(res, "fs/move " + srcDir + " -> " + dstDir);
                });
    }

    /**
     * 删除文件
     *
     * @param dir   目录
     * @param names 文件名
     */
    public void fsRemove(String dir, List<String> names) {
        invalidateFindFilesCache();
        postApi("fs/remove")
                .body(GsonStatic.toJson(Map.of(
                        "dir", dir,
                        "names", names
                ))).then(res -> assertOpenListOk(res, "fs/remove " + dir));
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
        invalidateFindFilesCache();
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
            invalidateFindFilesCache();
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
        invalidateFindFilesCache();
        postApi("fs/batch_rename")
                .body(GsonStatic.toJson(Map.of(
                        "src_dir", srcDir,
                        "rename_objects", mapList
                ))).then(res -> {
                    log.info(res.body());
                    assertOpenListOk(res, "fs/batch_rename " + srcDir);
                });
    }

    /**
     * 添加离线下载
     *
     * @param magnet 磁力链接
     * @param path   离线位置
     * @return tid
     */
    public String fsAddOfflineDownload(String magnet, String path) {
        invalidateFindFilesCache();
        return postApi("fs/add_offline_download")
                .body(GsonStatic.toJson(Map.of(
                        "path", path,
                        "urls", List.of(magnet),
                        "tool", config.getProvider(),
                        "delete_policy", "delete_on_upload_succeed"
                )))
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    log.debug(jsonObject.toString());
                    int code = jsonObject.get("code").getAsInt();
                    // 10008: 任务已存在，视为成功（幂等）
                    if (code == 10008) {
                        log.info("离线任务已存在，跳过重复提交 {}", path);
                        return null;
                    }
                    Assert.isTrue(code == 200);
                    return jsonObject.getAsJsonObject("data")
                            .getAsJsonArray("tasks")
                            .get(0).getAsJsonObject()
                            .get("id").getAsString();
                });
    }

    /**
     * 文件列表
     *
     * @param path 目录
     * @return 文件列表
     */
    public List<OpenListFileInfo> fsList(String path, Boolean refresh) {
        try {
            return retryIdempotent("fs/list " + path, () -> postApi("fs/list")
                    .body(GsonStatic.toJson(Map.of(
                            "path", path,
                            "page", 1,
                            "per_page", 0,
                            "refresh", refresh
                    )))
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        int code = jsonObject.get("code").getAsInt();
                        if (code != 200) {
                            return List.of();
                        }
                        JsonElement data = jsonObject.get("data");
                        if (Objects.isNull(data) || data.isJsonNull()) {
                            return List.of();
                        }
                        JsonElement content = data.getAsJsonObject().get("content");
                        if (Objects.isNull(content) || content.isJsonNull()) {
                            return List.of();
                        }
                        List<OpenListFileInfo> infos = GsonStatic.fromJsonList(content.getAsJsonArray(), OpenListFileInfo.class);
                        for (OpenListFileInfo info : infos) {
                            info.setPath(path);
                        }
                        return ListUtil.sort(new ArrayList<>(infos), Comparator.comparing(fileInfo -> {
                            Long size = fileInfo.getSize();
                            return Long.MAX_VALUE - ObjectUtil.defaultIfNull(size, 0L);
                        }));
                    }));
        } catch (Exception e) {
            log.warn("OpenList fs/list 调用失败 path={}: {}", path, ExceptionUtils.getMessage(e));
            return List.of();
        }
    }

    /**
     * 查看任务
     *
     * @param tid 任务id
     * @return 任务信息
     */
    public Optional<OpenListTaskInfo> taskInfo(String tid) {
        try {
            OpenListTaskInfo taskInfo = retryIdempotent("task/info " + tid,
                    () -> postApi("task/offline_download/info?tid=" + tid)
                            .thenFunction(res -> {
                                HttpReq.assertStatus(res);
                                JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                                int code = jsonObject.get("code").getAsInt();
                                if (code != 200 || !jsonObject.has("data") || jsonObject.get("data").isJsonNull()) {
                                    throw new IllegalStateException("task/info 失败 code=" + code);
                                }
                                return GsonStatic.fromJson(jsonObject.get("data").getAsJsonObject(), OpenListTaskInfo.class);
                            }));
            return Optional.ofNullable(taskInfo);
        } catch (Exception e) {
            log.warn("OpenList task/info 调用失败 tid={}: {}", tid, ExceptionUtils.getMessage(e));
            return Optional.empty();
        }
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
        return isDuplicateMagnetCooling(infoHash, now, DUPLICATE_MAGNET_UNTIL);
    }

    private void markDuplicateMagnet(String infoHash) {
        if (StrUtil.isBlank(infoHash)) {
            return;
        }
        DUPLICATE_MAGNET_UNTIL.put(infoHash.toLowerCase(Locale.ROOT),
                System.currentTimeMillis() + DUPLICATE_MAGNET_COOLDOWN_MS);
    }

    private void clearDuplicateMagnet(String infoHash) {
        if (StrUtil.isBlank(infoHash)) {
            return;
        }
        DUPLICATE_MAGNET_UNTIL.remove(infoHash.toLowerCase(Locale.ROOT));
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
                    taskDelete(id);
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
                    taskDelete(id);
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
     * 校验 OpenList API 返回 code==200
     */
    private void assertOpenListOk(HttpResponse res, String action) {
        HttpReq.assertStatus(res);
        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
        int code = jsonObject.get("code").getAsInt();
        if (code != 200) {
            String message = jsonObject.has("message") ? jsonObject.get("message").getAsString() : "";
            throw new IllegalStateException(action + " 失败 code=" + code + " " + message);
        }
    }

    /**
     * 未完成的离线任务
     *
     * @return 任务列表
     */
    public List<OpenListTaskInfo> taskUnDoneList() {
        return getApi("task/offline_download/undone")
                .thenFunction(res -> {
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonArray jsonArray = jsonObject.get("data").getAsJsonArray();
                    return GsonStatic.fromJsonList(jsonArray, OpenListTaskInfo.class);
                });
    }

    /**
     * 已完成的离线任务
     *
     * @return 任务列表
     */
    public List<OpenListTaskInfo> taskDoneList() {
        return getApi("task/offline_download/done")
                .thenFunction(res -> {
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonArray jsonArray = jsonObject.get("data").getAsJsonArray();
                    return GsonStatic.fromJsonList(jsonArray, OpenListTaskInfo.class);
                });
    }

    /**
     * 重试任务
     *
     * @param tid 任务id
     */
    public void taskRetry(String tid) {
        if (StrUtil.isBlank(tid)) {
            return;
        }
        postApi("task/offline_download/retry")
                .form("tid", tid)
                .thenFunction(HttpResponse::isOk);
    }

    /**
     * 取消任务（运行中任务应先 cancel 再 delete）
     *
     * @param tid 任务id
     */
    public void taskCancel(String tid) {
        if (StrUtil.isBlank(tid)) {
            return;
        }
        // 现网 OpenList/AList：query tid 有效；form 常返回 HTTP 200 + body code=404 且任务仍在
        if (tryTaskAction("task/offline_download/cancel?tid=" + tid, null, "cancel/query", tid)) {
            return;
        }
        if (tryTaskAction("task/offline_download/cancel", tid, "cancel/form", tid)) {
            return;
        }
        log.debug("cancel 任务失败 {}", tid);
    }

    /**
     * 删除任务
     *
     * @param tid 任务id
     */
    public void taskDelete(String tid) {
        if (StrUtil.isBlank(tid)) {
            return;
        }
        // 现网有效路径：POST delete?tid=
        // form delete_some 常返回 code=200 data={} 但 running 任务仍残留，不能优先也不能只信 HTTP 200
        if (tryTaskAction("task/offline_download/delete?tid=" + tid, null, "delete/query", tid)) {
            return;
        }
        if (tryTaskAction("task/offline_download/delete", tid, "delete/form", tid)) {
            return;
        }
        if (tryTaskAction("task/offline_download/delete_some", tid, "delete_some/form", tid)) {
            return;
        }
        // 兼容旧版：JSON 数组 body（部分服务器会 400 invalid request format）
        try {
            HttpResponse res = postApi("task/offline_download/delete_some")
                    .body(GsonStatic.toJson(List.of(tid)))
                    .execute();
            if (isOpenListCodeOk(res)) {
                return;
            }
            log.debug("delete_some/json 失败 {}: {}", tid, res.body());
        } catch (Exception e) {
            log.debug("delete_some/json 异常 {}: {}", tid, e.getMessage());
        }
        log.warn("删除离线任务失败 {}", tid);
    }

    /**
     * 执行 cancel/delete 类动作，并校验 body.code==200（不能只看 HTTP 200）。
     *
     * @param action  API path（可含 query）
     * @param formTid 非空时用 form tid；空则只发 path
     */
    private boolean tryTaskAction(String action, String formTid, String label, String tid) {
        try {
            HttpRequest req = postApi(action);
            if (StrUtil.isNotBlank(formTid)) {
                req.form("tid", formTid);
            }
            HttpResponse res = req.execute();
            if (isOpenListCodeOk(res)) {
                return true;
            }
            log.debug("{} 未生效 tid={} body={}", label, tid, res.body());
        } catch (Exception e) {
            log.debug("{} 异常 tid={}: {}", label, tid, e.getMessage());
        }
        return false;
    }

    /**
     * HTTP 层 ok 且 JSON code==200 才算 OpenList 业务成功。
     * form cancel/delete 常返回 HTTP 200 + code 404/空成功，必须拆开判断。
     */
    static boolean isOpenListCodeOk(HttpResponse res) {
        if (res == null) {
            return false;
        }
        return isOpenListBusinessOk(res.isOk(), res.body());
    }

    /**
     * 纯函数：便于单测。HTTP 非 2xx 直接失败；body 有 code 时必须 200。
     */
    static boolean isOpenListBusinessOk(boolean httpOk, String body) {
        if (!httpOk) {
            return false;
        }
        try {
            if (StrUtil.isBlank(body)) {
                // 少数实现只回 HTTP 200
                return true;
            }
            JsonObject jsonObject = GsonStatic.fromJson(body, JsonObject.class);
            if (jsonObject == null || !jsonObject.has("code")) {
                return true;
            }
            return jsonObject.get("code").getAsInt() == 200;
        } catch (Exception e) {
            return false;
        }
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
            if (offlineCancelRequested.get() || ani.rss.task.RssTask.isCancelRequested()) {
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
        ResidualSnapshot snap = buildResidualSnapshot(tasks, currentInfoHash.get(), residualCleaning.get(), System.currentTimeMillis());
        residualSnapshot.set(snap);
        return snap;
    }

    /**
     * 从离线任务列表构建残留快照（纯函数，便于单测）。
     * 预览最多保留 {@link #RESIDUAL_PREVIEW_LIMIT} 条明细；samples 仍截断前 5 条名称。
     */
    static ResidualSnapshot buildResidualSnapshot(List<OpenListTaskInfo> tasks,
                                                  String protectHash,
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
                ResidualItem item = toResidualItem(task, protectHash);
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

    static ResidualItem toResidualItem(OpenListTaskInfo task, String protectHash) {
        ResidualKind kind = classifyResidual(task == null ? null : task.getState());
        String id = task == null ? "" : StrUtil.blankToDefault(task.getId(), "");
        String name = task == null ? "" : StrUtil.blankToDefault(task.getName(), id);
        boolean protectedCurrent = task != null && taskNameContainsHash(task.getName(), protectHash);
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
            String protectHash = currentInfoHash.get();
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
                // 保护当前 RSS 正在等待的 hash，避免误杀在跑任务
                if (StrUtil.isNotBlank(protectHash) && taskNameContainsHash(task.getName(), protectHash)) {
                    skipped++;
                    log.info("清理残留跳过当前任务 tid={} hash={}", task.getId(), protectHash);
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
        return currentInfoHash.get();
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
        String hash = currentInfoHash.get();
        if (StrUtil.isBlank(hash)) {
            return;
        }
        // 未 login 时 config 为空：只打断本地等待，不打远程 API
        if (config == null) {
            log.warn("任务管理器取消，OpenList 未登录，仅释放本地占用 hash={}", hash);
            clearDuplicateMagnet(hash);
            inFlightTasks.remove(hash);
            currentInfoHash.compareAndSet(hash, null);
            return;
        }
        log.warn("任务管理器取消，清理 OpenList 离线任务 hash={}（进行中 cancel+delete；终态仅 delete 记录）", hash);
        try {
            // purgeHashTasks：进行中先 cancel 再 delete；Succeeded/Failed 等终态只 delete 记录
            purgeHashTasks(hash);
        } catch (Exception e) {
            log.warn("取消清理 OpenList 失败 {}: {}", hash, e.getMessage());
        }
        clearDuplicateMagnet(hash);
        inFlightTasks.remove(hash);
        currentInfoHash.compareAndSet(hash, null);
    }

    /**
     * 轮询/等待是否应中止（超时仍由 deadline 负责）
     */
    private boolean shouldAbortWait() {
        return offlineCancelRequested.get() || ani.rss.task.RssTask.isCancelRequested();
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
        invalidateFindFilesCache();
        List<OpenListFileInfo> videos = expectedEpisodeVideos(findEpisodeFiles(tempPath, reName), expectedEpisodes);
        if (videos.isEmpty() && !Objects.equals(tempPath, savePath)) {
            invalidateFindFilesCache();
            // 最终目录通常是模板命名；reName 可能是合集临时目录名，回退扫最终目录视频
            videos = expectedEpisodeVideos(findEpisodeFiles(savePath, reName), expectedEpisodes);
            if (videos.isEmpty()) {
                videos = expectedEpisodeVideos(findFiles(savePath), expectedEpisodes);
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
        CachedFileList cached = findFilesCache.get(path);
        if (cached != null && cached.expireAt > System.currentTimeMillis()) {
            return cached.files;
        }

        List<OpenListFileInfo> openListFileInfos = fsList(path, true);
        List<OpenListFileInfo> list = openListFileInfos.stream()
                .flatMap(openListFileInfo -> {
                    if (openListFileInfo.getIsDir()) {
                        return findFiles(path + "/" + openListFileInfo.getName()).stream();
                    }
                    return Stream.of(openListFileInfo);
                }).toList();

        List<OpenListFileInfo> sorted = ListUtil.sort(new ArrayList<>(list), Comparator.comparing(fileInfo -> {
            Long size = fileInfo.getSize();
            return Long.MAX_VALUE - ObjectUtil.defaultIfNull(size, 0L);
        }));
        findFilesCache.put(path, new CachedFileList(sorted, FIND_FILES_TTL_MS));
        return sorted;
    }

    private <T> T retryIdempotent(String action, Supplier<T> supplier) {
        return retryIdempotent(action, supplier, IDEMPOTENT_API_RETRY_DELAYS_MS);
    }

    static <T> T retryIdempotent(String action, Supplier<T> supplier, long[] retryDelaysMs) {
        int attempts = Math.max(1, Math.min(IDEMPOTENT_API_MAX_ATTEMPTS,
                (retryDelaysMs == null ? 0 : retryDelaysMs.length) + 1));
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return supplier.get();
            } catch (RuntimeException e) {
                last = e;
                if (!isTransientOpenListFailure(e) || attempt >= attempts) {
                    throw e;
                }
                long delay = Math.max(0L, retryDelaysMs[attempt - 1]);
                log.warn("OpenList 临时故障，准备重试 action={} attempt={}/{} delayMs={} error={}",
                        action, attempt, attempts, delay, ExceptionUtils.getMessage(e));
                if (delay > 0) {
                    ThreadUtil.sleep(delay);
                }
            }
        }
        throw last == null ? new IllegalStateException(action + " failed") : last;
    }

    static boolean isTransientOpenListFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String message = StrUtil.blankToDefault(current.getMessage(), "").toLowerCase(Locale.ROOT);
            if (className.contains("sockettimeout")
                    || className.contains("connectexception")
                    || className.contains("noroutetohost")
                    || message.contains("read timed out")
                    || message.contains("connect timed out")
                    || message.contains("connection reset")
                    || message.contains("connection refused")
                    || message.contains("status: 502")
                    || message.contains("status: 503")
                    || message.contains("status: 504")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * API 最小间隔限流，避免固定 sleep 2s 拖慢轮询
     */
    private static void throttleApi() {
        synchronized (API_RATE_LOCK) {
            long now = System.currentTimeMillis();
            long wait = API_MIN_INTERVAL_MS - (now - lastApiCallAt);
            if (wait > 0) {
                ThreadUtil.sleep(wait);
            }
            lastApiCallAt = System.currentTimeMillis();
        }
    }

    /**
     * 目录变更后清理 findFiles 缓存
     */
    private static void invalidateFindFilesCache() {
        findFilesCache.clear();
    }

    /**
     * get api
     *
     * @param action
     * @return
     */
    public synchronized HttpRequest getApi(String action) {
        throttleApi();
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        return HttpReq.get(host + "/api/" + action)
                .header(Header.AUTHORIZATION, password);
    }

    /**
     * post api
     *
     * @param action
     * @return
     */
    public synchronized HttpRequest postApi(String action) {
        throttleApi();
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        return HttpReq.post(host + "/api/" + action)
                .header(Header.AUTHORIZATION, password);
    }

}
