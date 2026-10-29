package ani.rss.download.openlist.files;

import ani.rss.commons.FileUtils;
import ani.rss.commons.ExceptionUtils;
import ani.rss.download.BaseDownload;
import ani.rss.download.OfflineDownloader.RelocateResult;
import ani.rss.download.openlist.policy.OpenListPolicy;
import ani.rss.download.openlist.residual.TempDirDeleteResult;
import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.entity.OpenListFileInfo;
import ani.rss.entity.TorrentPlanRecord;
import ani.rss.util.other.OfflinePlanStore;
import ani.rss.util.other.TempDirCleanupStore;
import ani.rss.util.other.TorrentPlanMatcher;
import ani.rss.util.other.TorrentPlanUtil;
import ani.rss.util.other.TempDirResidualPolicy;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * OpenList 文件扫描、归位、完成校验与启动恢复逻辑。
 * <p>
 * 本类不持有登录态；网盘读写、通知与历史记录统一经 {@link OpenListFilePort} 交给宿主执行。
 */
@Slf4j
public final class OpenListFileService {
    private static final Comparator<OpenListFileInfo> FILE_SIZE_DESC =
            Comparator.comparingLong(OpenListFileInfo::getSize).reversed();
    private static final int MOVE_VERIFY_MAX_ATTEMPTS = 3;
    private static final long MOVE_VERIFY_RETRY_DELAY_MS = 5_000L;

    /**
     * 离线收尾所需的文件上下文。只包含 files 模块真正读取的字段，不向本包暴露 OpenList 的提交状态。
     */
    public record FinalizeContext(String reName,
                                  String finalRenameBase,
                                  String tempDirName,
                                  boolean isCollection,
                                  boolean manualCollection,
                                  int sourceOffset,
                                  List<Item> plan) {
    }

    /**
     * 收尾结果：成功时由调用方 promote；存疑时保留 pending，下轮重做归位。
     */
    public record FinalizeResult(boolean success,
                                 List<Item> planMissingVideos,
                                 List<Item> finalizedItems) {
    }

    private record ResolveResult(List<ResolvedFile> files, List<Item> planMissingVideos) {
    }

    private final OpenListFilePort host;

    public OpenListFileService(OpenListFilePort host) {
        this.host = Objects.requireNonNull(host);
    }

    /**
     * 构建视频/字幕 → 目标名 的重命名映射（离线完成后归位用）。
     * 单视频：以模板基名（合集按文件集数替换）命名；字幕继承视频名+语言后缀。
     * 多视频：按文件名提取集数替换模板集数；字幕优先按主名配对，其次按集数配对；
     * 仍未匹配的字幕以「首个视频的重命名后主名」为基名（而非原始文件名），
     * 避免视频已改模板名、字幕仍是原始名导致播放器配对失败。
     */
    public Map<String, String> buildEpisodeRenameMap(List<OpenListFileInfo> videoList,
                                                     List<OpenListFileInfo> subtitleList,
                                                     String finalRenameBase,
                                                     boolean isCollection,
                                                     Integer season,
                                                     int episodeOffset) {
        Map<String, String> renameMap = new HashMap<>();
        if (videoList.isEmpty()) {
            return renameMap;
        }
        if (videoList.size() == 1) {
            OpenListFileInfo videoFile = videoList.get(0);
            String videoReName = isCollection
                    ? host.collectionEpisodeReName(videoFile.getName(), finalRenameBase, season, episodeOffset)
                    : finalRenameBase;
            renameMap.put(videoFile.getName(), videoReName + "." + FileUtil.extName(videoFile.getName()));
            for (OpenListFileInfo sub : subtitleList) {
                String name = sub.getName();
                String ext = FileUtil.extName(name);
                String newName = videoReName;
                String lang = host.extractSubtitleLangSuffix(name);
                if (StrUtil.isNotBlank(lang)) {
                    newName = newName + "." + lang;
                }
                renameMap.put(name, newName + "." + ext);
            }
            return renameMap;
        }
        for (OpenListFileInfo video : videoList) {
            String videoName = video.getName();
            String videoBase = FileUtil.mainName(videoName);
            String videoExt = FileUtil.extName(videoName);
            String videoReName;
            if (isCollection) {
                videoReName = host.collectionEpisodeReName(videoName, finalRenameBase, season, episodeOffset);
            } else {
                // 文件名集数 → 最终集数必须加「来源 RSS 的偏移」，否则目标名会与 reName 差出偏移量
                String episode = BaseDownload.shiftEpisode(
                        host.extractEpisodeFromFileName(videoName), episodeOffset);
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

            String videoEpisode = host.extractEpisodeFromFileName(videoName);
            for (OpenListFileInfo sub : subtitleList) {
                String subName = sub.getName();
                String subBase = FileUtil.mainName(subName);
                String subExt = FileUtil.extName(subName);
                String subBaseClean = subBase;
                String lang = host.extractSubtitleLangSuffix(subName);
                if (StrUtil.isNotBlank(lang)) {
                    // 剥离已知语言段后再比较主名
                    for (String part : lang.split("\\.")) {
                        subBaseClean = FileUtil.mainName(subBaseClean);
                    }
                }
                boolean matched = videoBase.equals(subBase) || videoBase.equals(subBaseClean);
                // 主名不一致时按集数配对（如 "Show - 03.ass" 对 "Show - 03.mkv"，或语言后缀导致主名错位）
                if (!matched && videoEpisode != null
                        && videoEpisode.equals(host.extractEpisodeFromFileName(subName))) {
                    matched = true;
                }
                if (matched) {
                    String subReName = videoReName;
                    if (StrUtil.isNotBlank(lang)) {
                        subReName = subReName + "." + lang;
                    }
                    renameMap.put(subName, subReName + "." + subExt);
                }
            }
        }
        // 处理未匹配的字幕文件 - 确保它们也被移动。
        // 基名用「首个视频重命名后的主名」而非原始视频主名，保证与视频最终名一致。
        for (OpenListFileInfo sub : subtitleList) {
            if (renameMap.containsKey(sub.getName())) continue;
            String name = sub.getName();
            String ext = FileUtil.extName(name);
            String firstVideoTarget = renameMap.get(videoList.get(0).getName());
            String videoBase = StrUtil.isNotBlank(firstVideoTarget)
                    ? FileUtil.mainName(firstVideoTarget)
                    : FileUtil.mainName(videoList.get(0).getName());
            String newName = videoBase;
            String lang = host.extractSubtitleLangSuffix(name);
            if (StrUtil.isNotBlank(lang)) {
                newName = newName + "." + lang;
            }
            renameMap.put(name, newName + "." + ext);
            log.info("未匹配字幕文件: {} -> {}", name, newName + "." + ext);
        }
        return renameMap;
    }

    /**
     * 扫描一次本集文件：临时目录 → savePath（模板名）→ savePath 递归（按集数）→ 云下载目录（按集数+标题守卫）。
     * 只读不移动；alreadyFinal 判定要求视频全部位于 savePath 顶层（递归子目录命中不算，继续走重命名/移动）。
     */
    public EpisodeScanResult scanEpisodeFilesOnce(String path, String savePath, String tempDownloadDir,
                                                  String finalRenameBase, List<Double> episodeRange,
                                                  List<String> titleTokens, int sourceOffset) {
        Integer expectedSeason = OpenListPolicy.expectedSeasonOf(finalRenameBase);
        List<OpenListFileInfo> openListFileInfos = host.findFiles(path);
        Set<String> cloudSourceDirs = new HashSet<>();
        List<OpenListFileInfo> videoList = openListFileInfos.stream()
                // 防御：findFiles 缓存异常/实现差异下目录可能混入，目录名带扩展名会误判为视频
                .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                .filter(f -> FileUtils.isVideoFormat(f.getName()))
                .sorted(FILE_SIZE_DESC)
                .toList();
        List<OpenListFileInfo> subtitleList = openListFileInfos.stream()
                .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                .filter(f -> FileUtils.isSubtitleFormat(f.getName()))
                .toList();

        if (videoList.isEmpty()) {
            // 可能已在 savePath 落盘（历史完成/被其它路径移动）
            // 必须排除临时目录内的文件，否则「还在临时目录」会被误判为已完成并跳过移动
            List<OpenListFileInfo> saveFiles = host.findEpisodeFiles(savePath, finalRenameBase).stream()
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                    .filter(f -> !OpenListPolicy.isUnderPath(f, tempDownloadDir != null ? tempDownloadDir : null))
                    .toList();
            videoList = saveFiles.stream()
                    .filter(f -> FileUtils.isVideoFormat(f.getName()))
                    .sorted(FILE_SIZE_DESC)
                    .toList();
            subtitleList = saveFiles.stream()
                    .filter(f -> FileUtils.isSubtitleFormat(f.getName()))
                    .toList();
            openListFileInfos = saveFiles;
            if (videoList.isEmpty()) {
                // 与超时终检 inspectTimeoutFiles 的兜底一致：savePath 递归下可能已存在本集视频
                // 但未按模板命名（合集原始标题目录/非模板命名），按集数匹配而非模板名，
                // 避免「终检通过 → 后处理判失败 → 清标记 → 下轮重提交」死循环。
                // 源命名文件的集数用来源偏移平移（模板名文件在上面 findEpisodeFiles 已按 0 口径匹配过）
                List<OpenListFileInfo> fallback = host.findFiles(savePath).stream()
                        .filter(f -> !OpenListPolicy.isUnderPath(
                                f, tempDownloadDir != null ? tempDownloadDir : null))
                        .toList();
                List<OpenListFileInfo> fallbackVideos = host.expectedEpisodeVideos(
                        fallback, episodeRange, expectedSeason, sourceOffset);
                if (!fallbackVideos.isEmpty()) {
                    log.info("savePath 兜底扫描发现本集文件，进入后处理 videos={} 详情={}",
                            fallbackVideos.size(), host.describeVideos(fallbackVideos));
                    videoList = fallbackVideos.stream()
                            .sorted(FILE_SIZE_DESC)
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
                // 兜底扫描（按集数匹配 + 目录链标题守卫，与其它救援路径同一口径），自动归位到 savePath
                List<OpenListFileInfo> cloudFiles = host.findCloudDownloadFiles();
                List<OpenListFileInfo> cloudVideos = host.findCloudDownloadEpisodeVideos(
                        cloudFiles, host.resolveCloudDownloadDir(), episodeRange,
                        expectedSeason, titleTokens, sourceOffset);
                if (!cloudVideos.isEmpty()) {
                    log.info("115 云下载目录兜底扫描发现本集文件，进入后处理 videos={} 详情={}",
                            cloudVideos.size(), host.describeVideos(cloudVideos));
                    videoList = cloudVideos.stream()
                            .sorted(FILE_SIZE_DESC)
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
                // 快速路径收紧：仅当视频「全部」位于 savePath 顶层才视为已归位。
                // 此前用 !saveFiles.isEmpty() 短路，递归扫描会把其它子目录中的本集文件误判为已归位。
                boolean allAtTopLevel = videoList.stream()
                        .allMatch(f -> Objects.equals(
                                OpenListPolicy.trimTrailingSlash(f.getPath()),
                                OpenListPolicy.trimTrailingSlash(savePath)));
                if (allAtTopLevel) {
                    return new EpisodeScanResult(videoList, subtitleList, openListFileInfos,
                            cloudSourceDirs, true);
                }
                // 兜底文件位于 savePath 子目录（如合集原始标题目录）或顶层非模板命名：继续走重命名/移动
                log.info("本集文件未全部位于最终目录顶层，继续重命名/移动");
            }
        }
        return new EpisodeScanResult(videoList, subtitleList, openListFileInfos, cloudSourceDirs, false);
    }

    /**
     * 归位对账：扫描临时目录/云下载残留，重命名后移动到最终目录顶层。
     * 查询失败必须返回 UNVERIFIABLE，不能伪装成确认不存在。
     */
    public RelocateResult relocateEpisodeFiles(Ani ani, Item item, String downloadPath) {
        if (!host.loggedIn() || ani == null || item == null || StrUtil.isBlank(downloadPath)) {
            return RelocateResult.NOT_FOUND;
        }
        String savePath = OpenListPolicy.normalizePath(downloadPath);
        String finalRenameBase = item.getReName();
        if (StrUtil.isBlank(finalRenameBase)) {
            return RelocateResult.NOT_FOUND;
        }
        if (host.listingCoolingDown()) {
            host.logListingCooldownSkip();
            log.debug("归位对账跳过: 网盘接口冷却中（剩余 {}ms） {}",
                    host.listingCooldownRemainingMs(), item.getReName());
            return RelocateResult.UNVERIFIABLE;
        }
        List<Double> episodeRange = item.getEpisodeRange() != null && !item.getEpisodeRange().isEmpty()
                ? item.getEpisodeRange()
                : (item.getEpisode() != null ? List.of(item.getEpisode()) : List.of());
        Integer expectedSeason = ani.getSeason() != null ? ani.getSeason() : OpenListPolicy.expectedSeasonOf(finalRenameBase);
        try {
            java.util.Optional<List<OpenListFileInfo>> topLevelResult = host.listDirectChildrenCached(savePath);
            if (topLevelResult.isEmpty()) {
                log.warn("归位对账: 网盘下载目录不存在，按「确认没有」处理 path={} {}",
                        savePath, item.getReName());
                return RelocateResult.NOT_FOUND;
            }
            List<OpenListFileInfo> topLevel = topLevelResult.get();
            String saveNorm = OpenListPolicy.trimTrailingSlash(savePath);
            List<OpenListFileInfo> topFiles = topLevel.stream()
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                    .toList();
            List<OpenListFileInfo> topVideos = topFiles.stream()
                    .filter(f -> FileUtils.isVideoFormat(f.getName()))
                    .filter(f -> Objects.equals(OpenListPolicy.trimTrailingSlash(f.getPath()), saveNorm))
                    .toList();
            if (!host.expectedEpisodeVideos(topVideos, episodeRange, expectedSeason, 0).isEmpty()) {
                return RelocateResult.ALREADY_AT_TOP;
            }
            List<OpenListFileInfo> source = new ArrayList<>(
                    listTempDirFiles(topLevel, savePath, OpenListPolicy.resolveTempDirName(item)));
            List<OpenListFileInfo> cloudFiles = host.findCloudDownloadFilesStrict();
            String cloudDirForGuard = host.resolveCloudDownloadDir();
            List<String> cloudTitleTokens = OpenListPolicy.titleTokensOf(ani, item);
            Set<String> cloudSourceDirs = new HashSet<>();
            int sourceOffset = BaseDownload.rssOffsetOf(item);
            List<OpenListFileInfo> videoList = host.expectedEpisodeVideos(
                            source, episodeRange, expectedSeason, sourceOffset).stream()
                    .sorted(FILE_SIZE_DESC)
                    .toList();
            if (videoList.isEmpty()) {
                videoList = host.expectedEpisodeVideos(
                                cloudFiles, episodeRange, expectedSeason, sourceOffset).stream()
                        .filter(f -> !OpenListPolicy.cloudEntryLacksTitleToken(
                                f.getName(), f.getPath(), cloudDirForGuard, cloudTitleTokens))
                        .sorted(FILE_SIZE_DESC)
                        .toList();
                if (videoList.isEmpty()) {
                    return RelocateResult.NOT_FOUND;
                }
                source = cloudFiles.stream().filter(f -> !Boolean.TRUE.equals(f.getIsDir())).toList();
                videoList.stream().map(OpenListFileInfo::getPath)
                        .filter(Objects::nonNull).forEach(cloudSourceDirs::add);
                log.info("归位对账: 云下载目录发现本集文件 {}", item.getReName());
            } else {
                String seasonKey = ani.getSeason() != null
                        ? String.format(Locale.ROOT, "S%02d", ani.getSeason()) : null;
                videoList = videoList.stream()
                        .filter(f -> {
                            String parent = OpenListPolicy.firstPathSegmentUnder(f, savePath);
                            return StrUtil.isBlank(parent)
                                    || TempDirResidualPolicy.looksLikeTempEpisodeDir(parent, seasonKey);
                        })
                        .toList();
                if (videoList.isEmpty()) {
                    return RelocateResult.NOT_FOUND;
                }
                log.info("归位对账: 子目录发现本集文件 {} videos={}", item.getReName(), videoList.size());
            }

            Set<String> videoDirs = videoList.stream()
                    .map(OpenListFileInfo::getPath)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            List<OpenListFileInfo> subtitleList = new ArrayList<>(source.stream()
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                    .filter(f -> FileUtils.isSubtitleFormat(f.getName()))
                    .filter(f -> f.getPath() == null || videoDirs.contains(f.getPath()))
                    .toList());
            cloudFiles.stream()
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                    .filter(f -> FileUtils.isSubtitleFormat(f.getName()))
                    .filter(f -> videoDirs.contains(f.getPath()))
                    .filter(f -> !OpenListPolicy.cloudEntryLacksTitleToken(
                            f.getName(), f.getPath(), cloudDirForGuard, cloudTitleTokens))
                    .filter(f -> subtitleList.stream().noneMatch(s -> Objects.equals(s.getPath(), f.getPath())
                            && Objects.equals(s.getName(), f.getName())))
                    .forEach(subtitleList::add);

            boolean isCollection = item.getEpisodeRange() != null && !item.getEpisodeRange().isEmpty();
            Map<String, String> renameMap = buildEpisodeRenameMap(
                    videoList, subtitleList, finalRenameBase, isCollection, ani.getSeason(),
                    BaseDownload.rssOffsetOf(item));
            dedupeRenameTargets(renameMap);

            Map<String, List<String>> pathToNames = new LinkedHashMap<>();
            Map<String, List<String>> resolvedNamesByDir = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : renameMap.entrySet()) {
                String srcName = entry.getKey();
                String newName = Boolean.TRUE.equals(host.renameEnabled()) ? entry.getValue() : srcName;
                String dirPath = topFiles.stream()
                        .filter(f -> srcName.equals(f.getName()))
                        .findFirst()
                        .map(OpenListFileInfo::getPath)
                        .orElse(null);
                if (StrUtil.isBlank(dirPath)) {
                    dirPath = source.stream()
                            .filter(f -> srcName.equals(f.getName()))
                            .findFirst()
                            .map(OpenListFileInfo::getPath)
                            .orElse(null);
                }
                if (StrUtil.isBlank(dirPath)) {
                    continue;
                }
                pathToNames.computeIfAbsent(dirPath, k -> new ArrayList<>()).add(newName);
            }
            if (pathToNames.isEmpty()) {
                return RelocateResult.NOT_FOUND;
            }
            for (Map.Entry<String, List<String>> entry : pathToNames.entrySet()) {
                String dirPath = entry.getKey();
                List<String> names = entry.getValue();
                if (Boolean.TRUE.equals(host.renameEnabled()) && !names.isEmpty()) {
                    List<Map<String, String>> renameObjects = new ArrayList<>();
                    for (Map.Entry<String, String> re : renameMap.entrySet()) {
                        if (names.contains(re.getValue())) {
                            renameObjects.add(Map.of("src_name", re.getKey(), "new_name", re.getValue()));
                        }
                    }
                    if (!renameObjects.isEmpty()) {
                        host.fsBatchRename(renameObjects, dirPath);
                        names = names.stream()
                                .map(n -> resolveRenamedNames(dirPath, List.of(n)).getOrDefault(n, n))
                                .collect(Collectors.toCollection(ArrayList::new));
                    }
                }
                resolvedNamesByDir.put(dirPath, names);
                if (!Objects.equals(OpenListPolicy.trimTrailingSlash(dirPath),
                        OpenListPolicy.trimTrailingSlash(savePath))) {
                    host.fsMove(dirPath, savePath, names);
                }
            }

            Set<String> allNames = resolvedNamesByDir.values().stream()
                    .flatMap(List::stream).collect(Collectors.toSet());
            List<String> missing = verifyTopLevelNames(savePath, allNames);
            if (!missing.isEmpty()) {
                log.warn("归位对账: 部分文件未出现在顶层 {}", missing);
                return RelocateResult.NOT_FOUND;
            }
            if (!cloudSourceDirs.isEmpty()) {
                host.cleanupCloudDownloadEmptyDirs(cloudSourceDirs);
            }
            log.info("归位对账完成: {} 已移动到顶层", item.getReName());
            return RelocateResult.RELOCATED;
        } catch (Exception e) {
            log.warn("归位对账无法完成（网盘列举失败或异常，非「确认没有」），本轮保留记录 path={} {}: {}",
                    savePath, item.getReName(), ExceptionUtils.getMessage(e));
            return RelocateResult.UNVERIFIABLE;
        }
    }

    /**
     * 后处理：对一次扫描命中的本集文件执行重命名 → 移动 → 顶层校验（失败重试）→ 清理临时/云下载
     * 空壳 → 合集缺集校验 → 完成通知。等待循环与 10008 死锁快速路径共用。
     */
    public FinalizeResult finalizeFromScan(FinalizeContext ctx, Ani ani, Item item, String savePath,
                                           EpisodeScanResult scan) {
        String reName = ctx.reName();
        Set<String> cloudSourceDirs = new HashSet<>(scan.cloudSourceDirs());

        ResolveResult resolveResult = resolveFiles(ctx, ani, scan);
        List<ResolvedFile> resolved = resolveResult.files();
        List<Item> planMissingVideos = resolveResult.planMissingVideos();
        if (resolved.isEmpty()) {
            // 无任何可归位文件：存疑并保留临时目录，避免"空归位→清临时目录→误报完成"把产物一并清掉。
            // 不能判失败：这里说不清"是没下完"还是"下完了但认不出来"（计划缺失 / 115 改名），
            // 判失败会清 pending 重下，而文件可能已经在网盘上。
            log.warn("归位未匹配到任何可移动文件（存疑：保留 pending / 计划快照 / 临时目录） {} "
                            + "tempDir={} videos={} plan={}",
                    reName, ctx.tempDirName(), scan.videoList().size(),
                    ctx.plan() == null ? -1 : ctx.plan().size());
            host.recordRelocateUncertain();
            return new FinalizeResult(false, planMissingVideos, null);
        }

        // 目标名去重：撞名时退回原名避让，绝不抛异常
        // （抛异常会让"文件其实已落盘"的集被判离线失败 → 清 pending → 下轮重下 → 死循环）
        dedupeResolvedTargets(resolved);

        // 按原始路径分组，处理文件可能分布在多个子目录的情况
        Map<String, List<ResolvedFile>> pathToFiles = new LinkedHashMap<>();
        for (ResolvedFile file : resolved) {
            String dirPath = StrUtil.isNotBlank(file.srcPath)
                    ? file.srcPath
                    : (scan.videoList().isEmpty() ? savePath : scan.videoList().get(0).getPath());
            pathToFiles.computeIfAbsent(dirPath, k -> new ArrayList<>()).add(file);
        }

        // 重命名 + 移动 + 顶层校验 + 清理临时/云下载空壳（启动恢复流程共用同一实现）
        String fallbackDir = scan.videoList().isEmpty() ? savePath : scan.videoList().get(0).getPath();
        if (!relocateResolved(resolved, pathToFiles, savePath, ctx.tempDirName(), fallbackDir, cloudSourceDirs)) {
            // §3.2 / N1：rename / move 没搬成（含冷却、限流、超时、990009 假成功、确认缺名）
            // 只说明"这次没搬成"，不说明文件没下完 → 存疑，下轮重做归位，绝不重新提交离线任务。
            log.warn("归位/顶层确认未通过（存疑：保留 pending / 计划快照 / 临时目录，下轮重做归位） {}",
                    reName);
            host.recordRelocateUncertain();
            return new FinalizeResult(false, planMissingVideos, null);
        }

        // 缺集校验：只看最终目录顶层——刚刚被 verifyTopLevelNames 确认过的那一层。
        // 本段只产出日志，读失败时宁可跳过，也不要报一个假的"全部缺失"。
        if (item.getEpisodeRange() != null && !item.getEpisodeRange().isEmpty()) {
            List<OpenListFileInfo> topLevel = null;
            try {
                topLevel = host.listDirectChildrenStrict(savePath);
            } catch (Exception e) {
                log.warn("缺集校验列举失败，跳过本次校验（不影响已确认的成功判定） {}: {}",
                        reName, ExceptionUtils.getMessage(e));
            }
            if (topLevel != null) {
                List<OpenListFileInfo> actualVideos = topLevel.stream()
                        // 115 会按文件名建同名目录（可能带 .mkv），必须排除目录
                        .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                        .filter(f -> FileUtils.isVideoFormat(f.getName()))
                        .toList();
                OpenListFilePort.EpisodeValidation validation =
                        host.validateCollectionEpisodes(item.getEpisodeRange(), actualVideos);
                if (validation.missing().isEmpty()) {
                    log.info("合集集数校验通过: {} 本次期望 {}, 已命中 {}",
                            reName, validation.expected(), validation.matched());
                } else {
                    log.warn("合集缺集: {} 本次期望 {}, 已命中 {}, 缺失 {}",
                            reName, validation.expected(), validation.matched(), validation.missing());
                }
            }
        }

        // P3: 计划内还有视频没到位 → 明确记账（失败队列 + 日志），
        // 让"下载器报成功/部分成功但其实缺文件"在界面上可见、可重试。
        reportPlanGaps(ctx, planMissingVideos, ani, item);
        // 供调用方按"真正落位"的条目精确标记完成（不把整个 episodeRange 都标成已下载）
        List<Item> finalizedItems = resolved.stream()
                .map(file -> file.plan)
                .filter(Objects::nonNull)
                .toList();

        // 手动合集入口(collectionPlan 非空)且确为多集时才用合集措辞；总集数为 1 时按单集收尾
        boolean collectionDownload = ctx.manualCollection() && ctx.isCollection();
        String message = collectionDownload
                ? StrFormatter.format("{} 合集下载完成, 共归位 {} 个文件", item.getReName(), resolved.size())
                : StrFormatter.format("{} 下载完成", item.getReName());
        host.notifyDownloadCompleted(ani, message);
        host.recordHistory(ani, item, collectionDownload ? "离线合集完成" : "离线下载完成");
        host.recordSuccess();
        return new FinalizeResult(true, planMissingVideos, finalizedItems);
    }

    /**
     * 归位文件：统一成「源文件 → 目标名」的列表。
     * 有期望计划时按计划条目认领；计划缺失或对不上时回退旧的启发式。
     */
    private ResolveResult resolveFiles(FinalizeContext ctx, Ani ani, EpisodeScanResult scan) {
        List<OpenListFileInfo> openListFileInfos = scan.openListFileInfos();
        List<Item> planMissingVideos = List.of();
        if (ctx.plan() != null && !ctx.plan().isEmpty()) {
            TorrentPlanMatcher.Result result = TorrentPlanMatcher.match(ctx.plan(), openListFileInfos);
            planMissingVideos = result.missingVideos();
            if (!result.matches().isEmpty()) {
                if (!result.missing().isEmpty()) {
                    log.info("计划归位命中 {} 项, 未到位 {} 项 {}",
                            result.matches().size(), result.missing().size(), ctx.reName());
                }
                List<ResolvedFile> resolved = new ArrayList<>();
                for (TorrentPlanMatcher.Match match : result.matches()) {
                    String target = match.plan().getReName();
                    if (StrUtil.isBlank(target)) {
                        continue;
                    }
                    resolved.add(new ResolvedFile(match.file().getName(), match.file().getPath(), target, match.plan()));
                }
                if (!resolved.isEmpty()) {
                    return new ResolveResult(resolved, planMissingVideos);
                }
            }
            log.warn("期望计划({} 项)与离线产物({} 项)无任何匹配, 回退启发式归位 {}",
                    ctx.plan().size(), openListFileInfos.size(), ctx.reName());
        }

        Map<String, String> renameMap = buildEpisodeRenameMap(scan.videoList(), scan.subtitleList(),
                ctx.finalRenameBase(), ctx.isCollection(), ani == null ? null : ani.getSeason(),
                ctx.sourceOffset());
        if (renameMap.isEmpty()) {
            return new ResolveResult(List.of(), planMissingVideos);
        }
        String fallbackDir = scan.videoList().isEmpty() ? null : scan.videoList().get(0).getPath();
        List<ResolvedFile> resolved = new ArrayList<>();
        for (Map.Entry<String, String> entry : renameMap.entrySet()) {
            String srcName = entry.getKey();
            String srcPath = openListFileInfos.stream()
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                    .filter(f -> Objects.equals(srcName, f.getName()))
                    .map(OpenListFileInfo::getPath)
                    .findFirst()
                    .orElse(fallbackDir);
            resolved.add(new ResolvedFile(srcName, srcPath, entry.getValue(), null));
        }
        return new ResolveResult(resolved, planMissingVideos);
    }

    /**
     * 计划扫描：只看计划内文件到了没，与集数解析/模板命名无关。
     */
    public EpisodeScanResult planScan(List<Item> plan, String path, String savePath,
                                      String tempDownloadDir, List<Double> expectedEpisodes,
                                      List<String> titleTokens, boolean requireAllVideos, String reName) {
        if (plan == null || plan.isEmpty()) {
            return null;
        }
        java.util.function.Predicate<TorrentPlanMatcher.Result> hit = requireAllVideos
                ? TorrentPlanMatcher.Result::videosComplete
                : r -> !r.matches().isEmpty();
        try {
            Map<String, OpenListFileInfo> candidates = new LinkedHashMap<>();
            addCandidates(candidates, host.findFiles(path));
            TorrentPlanMatcher.Result result =
                    TorrentPlanMatcher.match(plan, new ArrayList<>(candidates.values()));
            if (!hit.test(result)) {
                addCandidates(candidates, host.findFiles(savePath).stream()
                        .filter(f -> !OpenListPolicy.isUnderPath(f, tempDownloadDir)).toList());
                result = TorrentPlanMatcher.match(plan, new ArrayList<>(candidates.values()));
            }
            if (!hit.test(result)) {
                String cloudDir = host.resolveCloudDownloadDir();
                addCandidates(candidates, host.findCloudDownloadFiles().stream()
                        .filter(f -> !OpenListPolicy.cloudEntryLacksTitleToken(
                                f.getName(), f.getPath(), cloudDir, titleTokens))
                        .toList());
                result = TorrentPlanMatcher.match(plan, new ArrayList<>(candidates.values()));
            }
            if (!hit.test(result)) {
                return null;
            }
            // 云下载兜底命中的源目录：归位后要清理空壳
            Set<String> cloudSourceDirs = new HashSet<>();
            String cloudDir = host.resolveCloudDownloadDir();
            if (StrUtil.isNotBlank(cloudDir)) {
                String prefix = OpenListPolicy.trimTrailingSlash(cloudDir);
                result.matches().stream()
                        .map(m -> m.file().getPath())
                        .filter(Objects::nonNull)
                        .filter(p -> OpenListPolicy.trimTrailingSlash(p).startsWith(prefix))
                        .forEach(cloudSourceDirs::add);
            }
            return new EpisodeScanResult(
                    result.videoMatches().stream().map(TorrentPlanMatcher.Match::file).toList(),
                    result.subtitleMatches().stream().map(TorrentPlanMatcher.Match::file).toList(),
                    new ArrayList<>(candidates.values()), cloudSourceDirs, false);
        } catch (Exception e) {
            log.debug("计划比对失败（不影响等待） {}: {}", reName, ExceptionUtils.getMessage(e));
            return null;
        }
    }

    /**
     * 并入候选文件（按 path+name 去重，目录条目一律不要）
     */
    private static void addCandidates(Map<String, OpenListFileInfo> candidates, List<OpenListFileInfo> files) {
        if (files == null) {
            return;
        }
        for (OpenListFileInfo file : files) {
            if (file == null || Boolean.TRUE.equals(file.getIsDir())) {
                continue;
            }
            candidates.putIfAbsent(StrUtil.nullToEmpty(file.getPath()) + "/" + StrUtil.nullToEmpty(file.getName()), file);
        }
    }

    private void reportPlanGaps(FinalizeContext ctx, List<Item> missing, Ani ani, Item item) {
        if (missing == null || missing.isEmpty()) {
            return;
        }
        String names = missing.stream()
                .map(Item::getTitle)
                .limit(10)
                .collect(Collectors.joining(", "));
        String raw = StrFormatter.format("{} 计划内仍有 {} 个视频未到位: {}",
                ctx.reName(), missing.size(), names);
        log.warn(raw);
        host.recordPlanGap(ani, item, raw);
    }

    /**
     * 重命名（可选）+ 移动到最终目录顶层 + 顶层校验（失败重试移动）+ 清理临时目录与云下载空壳。
     */
    private boolean relocateResolved(List<ResolvedFile> resolved, Map<String, List<ResolvedFile>> pathToFiles,
                                     String savePath, String tempDirName, String fallbackDir,
                                     Set<String> cloudSourceDirs) {
        Boolean rename = host.renameEnabled();
        // 重命名后必须回读"盘上真实叫什么"：115（经 AList）的 fs/rename 只改主名、保留原扩展名。
        Map<ResolvedFile, String> actualNames = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(rename)) {
            for (Map.Entry<String, List<ResolvedFile>> entry : pathToFiles.entrySet()) {
                List<Map<String, String>> renameObjects = new ArrayList<>();
                List<String> requested = new ArrayList<>();
                for (ResolvedFile file : entry.getValue()) {
                    if (file.targetName.equals(file.srcName)) {
                        // 同名重命名是 no-op: 部分实现对 src==new 会报错, 直接跳过
                        continue;
                    }
                    log.info("重命名 {} ==> {}", file.srcName, file.targetName);
                    renameObjects.add(Map.of("src_name", file.srcName, "new_name", file.targetName));
                    requested.add(file.targetName);
                }
                if (renameObjects.isEmpty()) {
                    continue;
                }
                host.fsBatchRename(renameObjects, entry.getKey());
                Map<String, String> resolvedNames = resolveRenamedNames(entry.getKey(), requested);
                for (ResolvedFile file : entry.getValue()) {
                    if (!file.targetName.equals(file.srcName)) {
                        actualNames.put(file, resolvedNames.getOrDefault(file.targetName, file.targetName));
                    }
                }
            }
        }
        // 移动/校验一律用"盘上真实名"
        Function<ResolvedFile, String> movedName = file ->
                Boolean.TRUE.equals(rename)
                        ? actualNames.getOrDefault(file, file.targetName)
                        : file.srcName;

        // 移动：从每个子目录分别移动；已在 savePath 顶层的（兜底扫描发现的原始命名文件）仅就地重命名，无需移动
        Set<String> allMovedNames = new HashSet<>();
        String savePathNorm = OpenListPolicy.trimTrailingSlash(savePath);
        for (Map.Entry<String, List<ResolvedFile>> entry : pathToFiles.entrySet()) {
            String dirPath = entry.getKey();
            List<String> names = entry.getValue().stream()
                    .map(movedName)
                    .toList();
            if (Objects.equals(OpenListPolicy.trimTrailingSlash(dirPath), savePathNorm)) {
                allMovedNames.addAll(names);
                continue;
            }
            host.fsMove(dirPath, savePath, names);
            allMovedNames.addAll(names);
        }

        // 验证必须落在最终目录顶层；990009 异步移动/并发冲突下 fsMove 可能假成功：
        // 校验失败先重试移动，仍失败则判失败，禁止「warn 后照常发完成通知」。
        List<String> missingNames = verifyTopLevelNames(savePath, allMovedNames);
        int verifyAttempt = 0;
        while (!missingNames.isEmpty() && verifyAttempt < MOVE_VERIFY_MAX_ATTEMPTS) {
            verifyAttempt++;
            log.warn("第{}次校验：文件未出现在最终目录顶层，重试移动 {}", verifyAttempt, missingNames);
            ThreadUtil.sleep(MOVE_VERIFY_RETRY_DELAY_MS);
            // 只失效本次归位涉及的目录：最终目录 + 各源目录（pathToFiles 的键）
            List<String> changedDirs = new ArrayList<>(pathToFiles.keySet());
            changedDirs.add(savePath);
            host.invalidateListCache(changedDirs.toArray(String[]::new));
            for (Map.Entry<String, List<ResolvedFile>> entry : pathToFiles.entrySet()) {
                String dirPath = entry.getKey();
                if (Objects.equals(OpenListPolicy.trimTrailingSlash(dirPath), savePathNorm)) {
                    continue;
                }
                List<String> retryNames = entry.getValue().stream()
                        .map(movedName)
                        .filter(missingNames::contains)
                        .toList();
                if (!retryNames.isEmpty()) {
                    host.fsMove(dirPath, savePath, retryNames);
                }
            }
            missingNames = verifyTopLevelNames(savePath, allMovedNames);
        }
        if (!missingNames.isEmpty()) {
            log.error("归位失败：文件未出现在最终目录顶层，判定本次下载失败（不发完成通知）: {}", missingNames);
            return false;
        }

        // 需要移动的视频/字幕已确认在最终目录顶层 → 强制删除临时目录。
        // 顺序是「先记账再删」：删除是否真落地要靠回读确认，失败只留下"清理债务"交给巡检，
        // <b>不</b>改判本次下载结果——把清理失败升级成 UNCERTAIN 会让下一轮重复 rename/move，
        // 代价远大于一个残留目录。
        settleTempDirCleanup(savePath, tempDirName, allMovedNames);
        // 云下载兜底：文件已全部移动归位，清理源目录残留的空壳（115 任务目录）
        if (!cloudSourceDirs.isEmpty()) {
            host.cleanupCloudDownloadEmptyDirs(cloudSourceDirs);
        }
        return true;
    }

    /**
     * 收尾清理临时目录：登记清理债务 → 删除 → 按回读确认结果销账/延期/转人工。
     * <p>
     * {@code finalizedNames} 是"已经确认落在最终目录顶层"的文件名，作为巡检时删除前的安全门：
     * 少了任何一件都不许自动删临时目录（那可能是唯一一份产物）。
     */
    private void settleTempDirCleanup(String savePath, String tempDirName, Set<String> finalizedNames) {
        if (StrUtil.isBlank(savePath) || StrUtil.isBlank(tempDirName)) {
            return;
        }
        String id = TempDirCleanupStore.idOf(savePath, tempDirName);
        TempDirCleanupStore.markDeletePending(savePath, tempDirName, finalizedNames);
        TempDirDeleteResult result;
        try {
            result = host.cleanupTempDownloadDir(savePath, tempDirName);
        } catch (Exception e) {
            // 连删除入口都炸：同样是"没删成"，留给巡检，不影响下载结论
            result = TempDirDeleteResult.FAILED_RETRYABLE;
            log.warn("删除临时目录异常（保留清理债务） {}/{}: {}", savePath, tempDirName,
                    ExceptionUtils.getMessage(e));
        }
        if (result == null) {
            result = TempDirDeleteResult.FAILED_RETRYABLE;
        }
        if (result.confirmed()) {
            TempDirCleanupStore.confirm(id);
            return;
        }
        if (result == TempDirDeleteResult.MISMATCH_HUMAN) {
            TempDirCleanupStore.needsHuman(id, "删除临时目录时现场与预期不符");
        } else {
            TempDirCleanupStore.defer(id, "删除未被确认（限流/冷却/异步未落地）");
            log.warn("临时目录删除未被确认，已登记清理债务等待巡检补做 {}/{}", savePath, tempDirName);
        }
    }

    /**
     * 恢复单个计划：候选目录只读比对 → 齐了才归位 → 标记完成 → 删除快照。
     */
    public void recoverOnePlan(TorrentPlanRecord record, Ani ani) {
        String infoHash = record.getInfoHash();
        List<Item> plan = record.getItems();
        String savePath = record.getDownloadPath();
        String tempDirName = record.getTempDirName();
        if (ani == null) {
            log.info("启动恢复：订阅已不存在, 丢弃计划快照 {}", infoHash);
            OfflinePlanStore.delete(infoHash);
            return;
        }
        if (plan == null || plan.isEmpty() || StrUtil.isBlank(savePath)) {
            OfflinePlanStore.delete(infoHash);
            return;
        }
        // 候选 = 最终目录「顶层」文件 + 本次「临时目录」里的文件。
        // 定点列举，不递归整个 savePath：递归代价随残留临时目录数放大。
        Map<String, OpenListFileInfo> candidates = new LinkedHashMap<>();
        List<OpenListFileInfo> topLevel = host.listDirectChildrenCached(savePath).orElse(List.of());
        addCandidates(candidates, topLevel);
        addCandidates(candidates, listTempDirFiles(topLevel, savePath, tempDirName));
        TorrentPlanMatcher.Result result =
                TorrentPlanMatcher.match(plan, new ArrayList<>(candidates.values()));
        Set<String> cloudSourceDirs = new HashSet<>();
        if (!result.videosComplete()) {
            String cloudDir = host.resolveCloudDownloadDir();
            List<String> titleTokens = OpenListPolicy.titleTokensOf(ani, null);
            // 必须用严格版：宽容版把"查询失败"吞成空列表，随后删快照会永久放弃补救。
            addCandidates(candidates, host.findCloudDownloadFilesStrict().stream()
                    .filter(f -> !OpenListPolicy.cloudEntryLacksTitleToken(
                            f.getName(), f.getPath(), cloudDir, titleTokens))
                    .toList());
            result = TorrentPlanMatcher.match(plan, new ArrayList<>(candidates.values()));
            if (result.videosComplete() && StrUtil.isNotBlank(cloudDir)) {
                String prefix = OpenListPolicy.trimTrailingSlash(cloudDir);
                result.matches().stream()
                        .map(m -> m.file().getPath())
                        .filter(Objects::nonNull)
                        .filter(p -> OpenListPolicy.trimTrailingSlash(p).startsWith(prefix))
                        .forEach(cloudSourceDirs::add);
            }
        }
        if (!result.videosComplete()) {
            log.info("启动恢复：计划内视频未齐, 交回 RSS 重新提交 {} ({}/{})",
                    record.getFinalRenameBase(), result.matches().size(), plan.size());
            OfflinePlanStore.delete(infoHash);
            return;
        }

        List<ResolvedFile> resolved = new ArrayList<>();
        for (TorrentPlanMatcher.Match match : result.matches()) {
            String target = match.plan().getReName();
            if (StrUtil.isBlank(target)) {
                continue;
            }
            resolved.add(new ResolvedFile(match.file().getName(), match.file().getPath(), target, match.plan()));
        }
        if (resolved.isEmpty()) {
            OfflinePlanStore.delete(infoHash);
            return;
        }
        dedupeResolvedTargets(resolved);
        Map<String, List<ResolvedFile>> pathToFiles = new LinkedHashMap<>();
        for (ResolvedFile file : resolved) {
            String dirPath = StrUtil.isNotBlank(file.srcPath) ? file.srcPath : savePath;
            pathToFiles.computeIfAbsent(dirPath, k -> new ArrayList<>()).add(file);
        }
        if (!relocateResolved(resolved, pathToFiles, savePath, tempDirName, savePath, cloudSourceDirs)) {
            // 归位校验没过（网盘异步移动失败等）：保留快照，下次启动再试
            log.warn("启动恢复：归位校验未通过, 保留快照 {}", infoHash);
            return;
        }

        // 标记"真正归位"的集（不用 episodeRange，避免把没到的集也标成已下载）
        Set<String> keys = TorrentPlanUtil.episodeIndexKeys(ani, result.matches().stream()
                .map(TorrentPlanMatcher.Match::plan).toList());
        if (!keys.isEmpty()) {
            host.appendEpisodes(ani.getId(), savePath, keys);
        }
        Item item = new Item()
                .setTitle(record.getFinalRenameBase())
                .setReName(record.getFinalRenameBase())
                .setInfoHash(infoHash);
        host.notifyDownloadCompleted(ani,
                StrFormatter.format("{} 下载完成（启动恢复归位 {} 个文件）", record.getFinalRenameBase(), resolved.size()));
        host.recordHistory(ani, item, "离线下载完成(启动恢复)");
        OfflinePlanStore.delete(infoHash);
        log.info("启动恢复完成: {} 已归位 {} 个文件", record.getFinalRenameBase(), resolved.size());
    }

    private List<OpenListFileInfo> listTempDirFiles(List<OpenListFileInfo> topLevel, String savePath,
                                                    String tempDirName) {
        if (StrUtil.isBlank(tempDirName)) {
            return List.of();
        }
        boolean present = topLevel.stream().anyMatch(f -> Boolean.TRUE.equals(f.getIsDir())
                && tempDirName.equalsIgnoreCase(StrUtil.trim(f.getName())));
        if (!present) {
            return List.of();
        }
        String tempPath = OpenListPolicy.trimTrailingSlash(savePath) + "/" + tempDirName;
        java.util.Optional<List<OpenListFileInfo>> insideResult = host.listDirectChildrenCached(tempPath);
        if (insideResult.isEmpty()) {
            log.debug("归位对账: 临时目录已不存在 {}", tempPath);
            return List.of();
        }
        List<OpenListFileInfo> files = new ArrayList<>();
        for (OpenListFileInfo f : insideResult.get()) {
            if (!Boolean.TRUE.equals(f.getIsDir())) {
                files.add(f);
                continue;
            }
            String childName = StrUtil.trim(f.getName());
            if (StrUtil.isBlank(childName)) {
                continue;
            }
            java.util.Optional<List<OpenListFileInfo>> nestedResult =
                    host.listDirectChildrenCached(tempPath + "/" + childName);
            if (nestedResult.isEmpty()) {
                log.debug("归位对账: 任务目录已不存在 {}/{}", tempPath, childName);
                continue;
            }
            nestedResult.get().stream()
                    .filter(nested -> !Boolean.TRUE.equals(nested.getIsDir()))
                    .forEach(files::add);
        }
        return files;
    }

    /**
     * 重命名后回读真实文件名；115 可能只改主名并保留原扩展名。
     */
    private Map<String, String> resolveRenamedNames(String dir, List<String> requested) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String want : requested) {
            resolved.put(want, want);
        }
        if (requested.isEmpty()) {
            return resolved;
        }
        List<String> actualNames;
        try {
            host.invalidateListCache(dir);
            actualNames = host.listStrict(dir).stream()
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                    .map(OpenListFileInfo::getName)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("重命名后回读目录失败，按请求名继续 {}: {}", dir, ExceptionUtils.getMessage(e));
            return resolved;
        }
        for (String want : requested) {
            if (actualNames.contains(want)) {
                continue;
            }
            String main = FileUtil.mainName(want);
            List<String> sameMain = actualNames.stream()
                    .filter(n -> FileUtil.mainName(n).equals(main))
                    .toList();
            if (sameMain.size() == 1) {
                log.warn("网盘保留原扩展名：请求 {} 实际 {}", want, sameMain.get(0));
                resolved.put(want, sameMain.get(0));
            }
        }
        return resolved;
    }

    /**
     * 校验文件是否出现在 savePath 顶层，返回缺失名单。
     */
    private List<String> verifyTopLevelNames(String savePath, Set<String> expectedNames) {
        Set<String> topLevelNames = host.listStrict(savePath).stream()
                .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                .map(OpenListFileInfo::getName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return expectedNames.stream()
                .filter(name -> !topLevelNames.contains(name))
                .toList();
    }

    /**
     * 一个待归位文件：源文件名 + 源所在目录 + 目标名（+ 对应计划条目）。
     */
    private static final class ResolvedFile {
        final String srcName;
        final String srcPath;
        String targetName;
        final Item plan;

        ResolvedFile(String srcName, String srcPath, String targetName, Item plan) {
            this.srcName = srcName;
            this.srcPath = srcPath;
            this.targetName = targetName;
            this.plan = plan;
        }
    }

    /**
     * 目标名去重（列表版）：保证互不相同，撞名时后来者退回自己的原始文件名。
     */
    private static void dedupeResolvedTargets(List<ResolvedFile> files) {
        if (files == null || files.size() < 2) {
            return;
        }
        Set<String> taken = new HashSet<>();
        for (ResolvedFile file : files) {
            if (Objects.equals(file.srcName, file.targetName)) {
                taken.add(file.targetName);
            }
        }
        List<ResolvedFile> rest = files.stream()
                .filter(f -> !Objects.equals(f.srcName, f.targetName))
                .sorted(Comparator.comparing(f -> f.srcName))
                .toList();
        for (ResolvedFile file : rest) {
            if (taken.add(file.targetName)) {
                continue;
            }
            String fallback = uniqueRenameName(file.srcName, taken);
            log.warn("重命名目标冲突, 退回原名避免互相覆盖: {} -> {} (原目标 {})",
                    file.srcName, fallback, file.targetName);
            file.targetName = fallback;
        }
    }

    /**
     * 目标名去重：保证 renameMap 的 value 互不相同，撞名时让后来者避让。
     */
    public static void dedupeRenameTargets(Map<String, String> renameMap) {
        if (renameMap.size() < 2) {
            return;
        }
        Set<String> taken = new HashSet<>();
        for (Map.Entry<String, String> entry : renameMap.entrySet()) {
            if (Objects.equals(entry.getKey(), entry.getValue())) {
                taken.add(entry.getValue());
            }
        }
        List<String> sources = new ArrayList<>(renameMap.keySet());
        sources.sort(Comparator.naturalOrder());
        for (String src : sources) {
            String target = renameMap.get(src);
            if (Objects.equals(src, target)) {
                continue;
            }
            if (taken.add(target)) {
                continue;
            }
            String fallback = uniqueRenameName(src, taken);
            log.warn("重命名目标冲突, 退回原名避免互相覆盖: {} -> {} (原目标 {})", src, fallback, target);
            renameMap.put(src, fallback);
        }
    }

    private static String uniqueRenameName(String base, Set<String> taken) {
        if (taken.add(base)) {
            return base;
        }
        String main = FileUtil.mainName(base);
        String ext = FileUtil.extName(base);
        for (int i = 2; i <= 99; i++) {
            String candidate = StrUtil.isBlank(ext) ? main + "." + i : main + "." + i + "." + ext;
            if (taken.add(candidate)) {
                return candidate;
            }
        }
        return base;
    }
}
