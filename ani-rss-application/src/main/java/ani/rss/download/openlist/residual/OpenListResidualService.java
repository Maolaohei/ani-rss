package ani.rss.download.openlist.residual;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.download.openlist.policy.OpenListPolicy;
import ani.rss.entity.Ani;
import ani.rss.entity.OpenListFileInfo;
import ani.rss.entity.OpenListTaskInfo;
import ani.rss.enums.StringEnum;
import ani.rss.util.other.TempDirResidualPolicy;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenList / 115 云下载的残留治理：离线任务残留与网盘临时目录残留的扫描、预览与清理。
 * <p>
 * 只通过 {@link OpenListResidualPort} 与宿主交互（列举、删除、任务操作、运行态读取），
 * 不反向引用 OpenList 本体；快照与「清理中」标志都是本类的实例状态，
 * 由 OpenList 组装时持有单例，不再散落在 OpenList 的静态字段里。
 * <p>
 * 安全边界（拆分前后必须一致）：
 * <ul>
 *   <li>扫描只读、清理只写，两边不共用判定分支；</li>
 *   <li>「查不到」不等于「确认没有」：清理前的列举一律走严格版，失败即放弃本次清理；</li>
 *   <li>在途任务的 hash 与临时目录名始终受保护，并行离线时一个都不能漏。</li>
 * </ul>
 */
@Slf4j
public class OpenListResidualService {
    private final OpenListResidualPort host;

    /** 进程内仅启动回扫一次（login 成功后异步） */
    private final AtomicBoolean startupResidualScanned = new AtomicBoolean(false);
    /** 最近一次残留快照（任务管理器展示） */
    private final AtomicReference<ResidualSnapshot> residualSnapshot =
            new AtomicReference<>(ResidualSnapshot.empty());
    /** 清理中互斥，避免并发 purge 打爆 API */
    private final AtomicBoolean residualCleaning = new AtomicBoolean(false);
    /** 文件系统临时目录残留（与离线任务残留分离） */
    private final AtomicReference<TempDirResidualSnapshot> tempDirResidualSnapshot =
            new AtomicReference<>(TempDirResidualSnapshot.empty());
    private final AtomicBoolean tempDirResidualCleaning = new AtomicBoolean(false);
    private static final int RESIDUAL_PREVIEW_LIMIT = 30;
    private static final int TEMP_DIR_PREVIEW_LIMIT = 30;
    /** 单次扫描最多深度检查的候选临时目录数，避免订阅极多时打爆 OpenList */
    private static final int TEMP_DIR_INSPECT_BUDGET = 120;

    public OpenListResidualService(OpenListResidualPort host) {
        this.host = host;
    }

    /**
     * 启动后异步回扫 OpenList 离线残留：
     * - 仅执行一次
     * - 只删除终态记录（Succeeded/Failed/Error/Canceled 等），不打断进行中下载
     * - 进行中任务只计数展示，留给用户手动「清理残留」
     */
    public void scheduleStartupResidualScan() {
        if (!startupResidualScanned.compareAndSet(false, true)) {
            return;
        }
        ThreadUtil.execute(() -> {
            try {
                // 错开登录瞬时流量
                ThreadUtil.sleep(800L);
                ResidualSnapshot snap = scanOfflineResiduals(true);
                residualSnapshot.set(snap);
                // P4: 恢复"重启前/重启期间其实已经下完"的离线任务（只读比对，齐了才归位）
                try {
                    host.recoverOfflinePlans();
                } catch (Exception e) {
                    log.warn("启动恢复离线计划失败: {}", ExceptionUtils.getMessage(e));
                }
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
        if (!host.loggedIn()) {
            throw new IllegalStateException("OpenList 未登录");
        }
        List<OpenListTaskInfo> tasks = listAllOfflineTasksStrict();
        ResidualSnapshot snap = buildResidualSnapshot(tasks, Set.copyOf(host.currentInfoHashes()),
                residualCleaning.get(), System.currentTimeMillis());
        residualSnapshot.set(snap);
        return snap;
    }

    /**
     * 从离线任务列表构建残留快照（纯函数，便于单测）。
     * 预览最多保留 {@link #RESIDUAL_PREVIEW_LIMIT} 条明细；samples 仍截断前 5 条名称。
     */
    public static ResidualSnapshot buildResidualSnapshot(List<OpenListTaskInfo> tasks,
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

    public static ResidualItem toResidualItem(OpenListTaskInfo task, Set<String> protectHashes) {
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
    public static String residualActionLabel(ResidualKind kind, boolean protectedCurrent) {
        if (protectedCurrent) {
            return "保护中（当前 RSS 等待，清理时跳过）";
        }
        if (kind == ResidualKind.ACTIVE) {
            return "取消并删除记录";
        }
        return "删除记录";
    }

    /**
     * 清理 OpenList 离线残留。
     * @param includeActive true=取消并删除进行中；false=仅删终态记录（启动回扫）
     */
    public CleanResult cleanOfflineResiduals(boolean includeActive) {
        if (!host.loggedIn()) {
            return new CleanResult().setOk(false).setMessage("OpenList 未登录");
        }
        if (!residualCleaning.compareAndSet(false, true)) {
            return new CleanResult().setOk(false).setMessage("清理正在进行中，请稍候");
        }
        try {
            Set<String> protectHashes = Set.copyOf(host.currentInfoHashes());
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
                            host.cancelTask(task.getId());
                        } catch (Exception cancelEx) {
                            log.debug("清理残留 cancel 失败 {}: {}", task.getId(), cancelEx.getMessage());
                        }
                    }
                    // 终态与成功任务：只能删记录，不能“取消下载”
                    host.deleteTask(task.getId());
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
        if (!host.loggedIn()) {
            throw new IllegalStateException("OpenList 未登录");
        }
        Set<String> activeTempDirs = collectActiveTempDirs();

        List<TempDirResidualItem> items = new ArrayList<>();
        List<String> samples = new ArrayList<>();
        int cleanable = 0;
        int protectedCount = 0;
        int keep = 0;
        int inspectBudget = TEMP_DIR_INSPECT_BUDGET;
        int truncatedCandidates = 0;

        Map<String, PathScanTarget> pathTargets = collectTempDirScanTargets();

        for (PathScanTarget target : pathTargets.values()) {
            String savePath = target.savePath;
            List<OpenListFileInfo> top;
            try {
                // 扫描优先用缓存友好 list，避免对每个路径 force refresh
                top = host.list(savePath, false);
            } catch (Exception e) {
                log.debug("扫描临时目录失败 {}: {}", savePath, e.getMessage());
                continue;
            }
            if (top == null || top.isEmpty()) {
                continue;
            }
            TopVideoKeys topKeys = collectTopVideoKeys(top);
            Set<String> topVideoEpisodeKeys = topKeys.episodeKeys();
            Set<String> topVideoNames = topKeys.names();
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

                TempDirCandidateProbe probe = probeTempDirCandidate(savePath, dirName,
                        topVideoEpisodeKeys, topVideoNames, activeTempDirs);
                TempDirResidualPolicy.Decision decision = probe.decision();
                String action = probe.action();
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

    /**
     * 收集需要保护的临时目录名：并行离线时每个在途任务一组，
     * 再补上单槽等待快照里的临时目录名与标题（旧状态只记录了最后一个任务）。
     */
    private Set<String> collectActiveTempDirs() {
        // 多槽保护集合：并行离线时所有在途任务的临时目录都受保护（单槽快照只覆盖最后一个任务）
        Set<String> activeTempDirs = new HashSet<>(host.activeTempDirNames());
        for (String name : host.offlineWaitNames()) {
            if (StrUtil.isNotBlank(name)) {
                activeTempDirs.add(name);
            }
        }
        return activeTempDirs;
    }

    /**
     * 收集需要扫描的最终目录：按订阅保存路径去重，并记录该路径下出现过的季号，
     * 供临时目录判断“是否已有同季成片兄弟”。
     *
     * @return pathKey -> {savePath, seasonKeys}
     */
    private Map<String, PathScanTarget> collectTempDirScanTargets() {
        Map<String, PathScanTarget> pathTargets = new LinkedHashMap<>();
        List<Ani> aniList;
        try {
            aniList = ani.rss.util.other.AniUtil.getAniList();
        } catch (Exception e) {
            aniList = List.of();
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
                } else {
                    savePath = host.pathResolver().resolve(ani);
                    if (StrUtil.isBlank(savePath)) {
                        savePath = StrUtil.blankToDefault(ani.getDownloadPath(), "");
                    }
                }
            } catch (Exception e) {
                continue;
            }
            if (StrUtil.isBlank(savePath)) {
                continue;
            }
            String normalized = OpenListPolicy.normalizePath(savePath);
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
        return pathTargets;
    }

    /**
     * 最终目录顶层直接可见的视频/字幕：
     * 用来判断某个临时目录是否已经有同集成片兄弟（有则允许强制清理）。
     */
    private TopVideoKeys collectTopVideoKeys(List<OpenListFileInfo> top) {
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
        return new TopVideoKeys(topVideoEpisodeKeys, topVideoNames);
    }

    /**
     * 最终目录顶层视频的两组索引。
     *
     * @param episodeKeys 小写化的季集串（SxxExx）
     * @param names       视频/字幕文件名
     */
    private record TopVideoKeys(Set<String> episodeKeys, Set<String> names) {
    }

    /**
     * 探测单个候选临时目录：浅层列举 + 空壳/垃圾/媒体判定 + 成片兄弟判定，
     * 最后交给 {@link TempDirResidualPolicy} 出结论。
     * <p>
     * 只读不写：真正的删除动作由 {@link #cleanTempDirResiduals} 执行。
     */
    private TempDirCandidateProbe probeTempDirCandidate(String savePath, String dirName,
                                                        Set<String> topVideoEpisodeKeys, Set<String> topVideoNames,
                                                        Set<String> activeTempDirs) {
        String tempPath = savePath + "/" + dirName;
        boolean hasProtectedMedia = false;
        boolean junkOnly = true;
        boolean empty = true;
        Set<String> innerEpisodeKeys = new HashSet<>();
        try {
            // 浅层 list：残留分类只需一级信号，避免递归 findFiles 打爆 API。
            // 必须用严格版：下面那段 catch 把"查不到"降级为 junkOnly=false（= KEEP，需人工确认），
            // 但宽容版从不抛异常 ⇒ 那段 catch 是死代码，查询失败会被判成"仅垃圾/为空"，
            // 随后 cleanTempDirResiduals 用 force=true 整树删掉仍有媒体的目录。
            List<OpenListFileInfo> inside = host.listForCleanup(tempPath);
            for (OpenListFileInfo f : inside) {
                if (f == null) continue;
                empty = false;
                if (Boolean.TRUE.equals(f.getIsDir())) {
                    // 有子目录则不是纯垃圾空壳
                    junkOnly = false;
                    continue;
                }
                if (TempDirResidualPolicy.isProtectedTempFile(f)) {
                    hasProtectedMedia = true;
                    junkOnly = false;
                } else if (!TempDirResidualPolicy.isJunkTempFile(f)) {
                    junkOnly = false;
                }
                if (FileUtils.isVideoFormat(f.getName())
                        && ReUtil.contains(StringEnum.SEASON_REG, f.getName())) {
                    String ek = ReUtil.get(StringEnum.SEASON_REG, f.getName(), 0);
                    if (StrUtil.isNotBlank(ek)) {
                        innerEpisodeKeys.add(ek.toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (Exception e) {
            // 查不到 ≠ 空：信息不足时按「仍有内容」处理，交给 TempDirResidualPolicy 判 KEEP。
            // empty 也必须置 false —— 否则下面的 `if (empty) { junkOnly = true; }` 会把它翻回来，
            // 失败就又变回「仅垃圾或为空」，一键清理随后整树删除。
            log.debug("临时目录残留分类：列举失败，按需人工确认处理 {}: {}",
                    tempPath, ExceptionUtils.getMessage(e));
            empty = false;
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
            // 收紧：顶层必须存在「同集」最终视频才算有成片兄弟，才能 FORCE_CLEAN。
            // 此前顶层任意视频即判定成立，合集临时目录（目录名不含 SxxExx）内
            // 未完成媒体会被一键清理整树误删。
            hasFinalSibling = !innerEpisodeKeys.isEmpty()
                    && topVideoEpisodeKeys.stream().anyMatch(innerEpisodeKeys::contains);
        }
        TempDirResidualPolicy.Decision decision = TempDirResidualPolicy.decide(
                dirName, hasFinalSibling, hasProtectedMedia, junkOnly, activeTempDirs);
        String action = switch (decision.action()) {
            case FORCE_CLEAN -> "强制删除临时目录（最终成片已在顶层）";
            case JUNK_CLEAN -> "删除空壳/垃圾临时目录";
            case PROTECT_ACTIVE -> "保护中（当前下载占用）";
            case KEEP -> "保留（需人工确认）";
        };
        return new TempDirCandidateProbe(decision, action);
    }

    /**
     * 候选临时目录的探测结论。
     *
     * @param decision 清理策略给出的动作与原因
     * @param action   给界面的中文动作说明
     */
    private record TempDirCandidateProbe(TempDirResidualPolicy.Decision decision, String action) {
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
        if (!host.loggedIn()) {
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
                        host.deleteTempDir(item.getSavePath(), item.getName());
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
        List<OpenListTaskInfo> undone = host.undoneTasks();
        if (undone != null) {
            all.addAll(undone);
        }
        List<OpenListTaskInfo> done = host.doneTasks();
        if (done != null) {
            all.addAll(done);
        }
        return all;
    }

    public static boolean taskNameContainsHash(String name, String hash) {
        if (StrUtil.isBlank(name) || StrUtil.isBlank(hash)) {
            return false;
        }
        return name.toLowerCase(Locale.ROOT).contains(hash.toLowerCase(Locale.ROOT));
    }

    /**
     * ACTIVE=进行中可 cancel；TERMINAL=只能删记录
     */
    public static ResidualKind classifyResidual(OpenListTaskInfo.State state) {
        if (state == null) {
            return ResidualKind.TERMINAL;
        }
        return switch (state) {
            case Pending, Running, Waiting_for_Retry, Preparing_to_Retry -> ResidualKind.ACTIVE;
            case Succeeded, Error, Failing, Failed, Canceling, Canceled -> ResidualKind.TERMINAL;
        };
    }






}
