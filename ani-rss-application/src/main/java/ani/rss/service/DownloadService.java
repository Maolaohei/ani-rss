package ani.rss.service;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.PinyinUtils;
import ani.rss.download.OfflineDownloader;
import ani.rss.download.OpenList;
import ani.rss.entity.*;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.StringEnum;
import ani.rss.enums.TorrentsTags;
import ani.rss.task.RssTask;
import ani.rss.util.other.*;
import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.lang.func.Func1;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import wushuo.tmdb.api.entity.Tmdb;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 下载的主要逻辑
 */
@Slf4j
@Service
public class DownloadService {
    /**
     * 按订阅 id 细粒度锁：同一订阅串行，不同订阅可并行
     */
    private static final ConcurrentHashMap<String, Object> ANI_LOCKS = new ConcurrentHashMap<>();
    /** 非 OpenList 下载器推送串行；OpenList 不持此锁，避免长等待全局串行 */
    private static final Object DOWNLOAD_TOOL_LOCK = new Object();

    @Resource
    private ScrapeService scrapeService;

    private static Object lockForAni(Ani ani) {
        String id = Optional.ofNullable(ani)
                .map(Ani::getId)
                .filter(StrUtil::isNotBlank)
                .orElse("unknown");
        return ANI_LOCKS.computeIfAbsent(id, k -> new Object());
    }

    /**
     * 下载动漫（按 ani.id 加锁）
     *
     * @param ani
     */
    public void downloadAni(Ani ani) {
        Object lock = lockForAni(ani);
        synchronized (lock) {
            downloadAniLocked(ani);
        }
    }

    /**
     * 下载动漫（调用方需已持有对应 ani 锁）
     */
    private void downloadAniLocked(Ani ani) {
        Config config = ConfigUtil.CONFIG;
        Boolean delete = config.getDelete();
        Boolean autoDisabled = config.getAutoDisabled();
        Integer downloadCount = config.getDownloadCount();
        Integer delayedDownload = config.getDelayedDownload();
        Boolean deleteStandbyRSSOnly = config.getDeleteStandbyRSSOnly();

        String title = ani.getTitle();
        Integer season = ani.getSeason();
        Boolean downloadNew = ani.getDownloadNew();
        List<Double> notDownload = ani.getNotDownload();

        List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();

        int currentDownloadCount = 0;
        List<Item> items = ItemsUtil.getItems(ani);

        List<Integer> omitGaps = ItemsUtil.omitList(ani, items);
        int omitN = omitGaps == null ? 0 : omitGaps.size();
        Integer prevOmit = ani.getOmitCount();
        boolean omitChanged = prevOmit == null || prevOmit != omitN;
        SubscriptionHealth.rememberOmit(ani, omitN, System.currentTimeMillis());
        ItemsUtil.omit(ani, items);
        log.debug("{} 共 {} 个", title, items.size());

        long count = torrentsInfos
                .stream()
                .filter(it -> {
                    TorrentsInfo.State state = it.getState();
                    if (Objects.isNull(state)) {
                        return true;
                    }
                    // 未下载完成
                    return !List.of(
                            TorrentsInfo.State.queuedUP.name(),
                            TorrentsInfo.State.uploading.name(),
                            TorrentsInfo.State.stalledUP.name(),
                            TorrentsInfo.State.pausedUP.name(),
                            TorrentsInfo.State.stoppedUP.name()
                    ).contains(state.name());
                })
                .count();

        String savePath = getDownloadPath(ani);

        ItemsUtil.procrastinating(ani, items);

        // 实时保存文件
        boolean sync = false;

        // v2: episode 级去重，跟踪已下载集数（含合集范围内所有集数）
        boolean v2 = RenameUtil.isNamingV2(ani);
        Set<Double> downloadedEpisodes = v2 ? new HashSet<>() : null;

        // 本地 infoHash 去重：合集展开后的 clone 仍会进入循环，但同 infoHash 的第二个及后续 clone 会被此 Set 过滤跳过
        Set<String> pushedHashes = new HashSet<>();

        // 每个订阅只扫一次本地下载目录; OpenList 为网盘虚拟路径, 不预构建
        // (151 行信任记录, itemDownloaded 内部按需用 OpenList API 检查, 避免每轮 API 调用)
        Set<String> localEpisodeIndex = isOpenListTool()
                ? null
                : buildLocalEpisodeIndex(ani, savePath);

        for (Item item : items) {
            if (RssTask.isCancelRequested()) {
                log.warn("{} 检测到任务取消，停止本订阅后续下载", title);
                return;
            }
            log.debug(JSONUtil.formatJsonStr(GsonStatic.toJson(item)));
            String reName = item.getReName();
            File torrent = TorrentUtil.getTorrent(ani, item);
            Boolean master = item.getMaster();
            String hash = FileUtil.mainName(torrent)
                    .trim().toLowerCase();

            Double episode = item.getEpisode();
            // .5 集
            boolean is5 = ItemsUtil.is5(episode);

            // 已经下载过
            if (torrent.exists()) {
                // v2: 检查版本号，高版本覆盖低版本（洗版）
                if (v2 && item.getVersion() != null && item.getVersion() > 1) {
                    log.info("检测到高版本 {} v{}, 准备洗版", reName, item.getVersion());
                    // 不跳过，继续下载流程
                } else {
                    // 记录有效性校验: 下载器有对应任务 或 本地有对应文件 才视为已下载;
                    // OpenList/Alist: 下载目录是网盘虚拟路径, 本地文件不可见且任务列表恒空,
                    // 无法可靠校验, 信任种子记录, 避免误删有效记录导致无限重下
                    boolean recordValid;
                    if (isOpenListTool() || !Boolean.TRUE.equals(config.getRename())) {
                        recordValid = true;
                    } else {
                        recordValid = itemDownloaded(ani, item, true, localEpisodeIndex);
                    }
                    if (recordValid) {
                        log.debug("种子记录已存在 {}", reName);
                        if (master && !is5) {
                            currentDownloadCount++;
                        }
                        if (v2 && downloadedEpisodes != null) {
                            downloadedEpisodes.add(episode);
                        }
                        continue;
                    }
                    log.warn("清理过期种子记录(无对应任务/文件) {}", reName);
                    FileUtil.del(torrent);
                }
            }

            // v2: episode 级去重，同集数已被其他种子覆盖则跳过
            if (v2 && downloadedEpisodes != null && downloadedEpisodes.contains(episode)) {
                log.debug("集数已被覆盖 {} ep{}", reName, episode);
                if (master && !is5) {
                    currentDownloadCount++;
                }
                continue;
            }

            // v2: 合集范围去重，范围内所有集数均已覆盖则跳过
            if (v2 && item.getEpisodeRange() != null && !item.getEpisodeRange().isEmpty()) {
                boolean allCovered = item.getEpisodeRange().stream().allMatch(downloadedEpisodes::contains);
                if (allCovered) {
                    log.debug("合集范围已全部覆盖 {} {}", reName, item.getEpisodeRange());
                    if (master && !is5) {
                        currentDownloadCount++;
                    }
                    continue;
                }
            }

            if (notDownload.contains(episode)) {
                if (master && !is5) {
                    currentDownloadCount++;
                }
                log.debug("已被禁止下载: {}", reName);
                continue;
            }

            // 只下载最新集
            if (Boolean.TRUE.equals(downloadNew)) {
                Item newItem = items.get(items.size() - 1);

                // 日期一致也可下载, 防止字幕组同时发多集
                Date pubDate = item.getPubDate();
                Date newPubDate = newItem.getPubDate();
                if (Objects.nonNull(pubDate) && Objects.nonNull(newPubDate)) {
                    String pubDateFormat = DateUtil.format(pubDate, "yyyy-MM-dd");
                    String newPubDateFormat = DateUtil.format(newPubDate, "yyyy-MM-dd");
                    // 日期不一致则跳过
                    if (!pubDateFormat.equals(newPubDateFormat)) {
                        if (master && !is5) {
                            currentDownloadCount++;
                        }
                        continue;
                    }
                } else if (item != newItem) {
                    if (master && !is5) {
                        currentDownloadCount++;
                    }
                    continue;
                }
            }

            Date pubDate = item.getPubDate();
            if (Objects.nonNull(pubDate) && delayedDownload > 0) {
                Date now = DateUtil.offset(new Date(), DateField.MINUTE, -delayedDownload);
                if (now.getTime() < pubDate.getTime()) {
                    log.info("延迟下载 {}", reName);
                    continue;
                }
            }

            // 仅在主RSS更新后删除备用RSS
            if (delete && master && deleteStandbyRSSOnly) {
                TorrentsInfo standbyRSS = torrentsInfos
                        .stream()
                        .filter(torrentsInfo -> {
                            if (!torrentsInfo.getDownloadDir().equals(savePath)) {
                                return false;
                            }
                            if (!ReUtil.contains(StringEnum.SEASON_REG, torrentsInfo.getName())) {
                                return false;
                            }
                            String s = ReUtil.get(StringEnum.SEASON_REG, torrentsInfo.getName(), 0);
                            String ep = ReUtil.get(StringEnum.SEASON_REG, reName, 0);
                            if (s == null || ep == null || !s.equalsIgnoreCase(ep)) {
                                return false;
                            }
                            List<String> tags = torrentsInfo.getTags();
                            // 包含 备用RSS 标签或者 无主RSS字幕组信息
                            return tags.contains(TorrentsTags.BACK_RSS.getValue()) ||
                                    !tags.contains(ani.getSubgroup());
                        })
                        .findFirst()
                        .orElse(null);

                if (Objects.nonNull(standbyRSS)) {
                    List<String> tags = standbyRSS.getTags();
                    if (!tags.contains(TorrentsTags.RENAME.getValue())) {
                        // 未完成重命名
                        continue;
                    }
                    if (!TorrentUtil.delete(standbyRSS)) {
                        log.debug("备用RSS可能还未做种完成 {}", standbyRSS.getName());
                        // 删除失败或者不允许删除
                        continue;
                    }
                    torrentsInfos.remove(standbyRSS);
                }
            }

            // 已经下载过
            if (torrentsInfos
                    .stream()
                    .anyMatch(torrentsInfo ->
                            // hash 相同
                            torrentsInfo.getHash().equals(hash))) {
                log.info("已有下载任务 hash:{} name:{}", hash, reName);
                if (master && !is5) {
                    currentDownloadCount++;
                }
                continue;
            }

            // infoHash 去重：合集展开后多个 clone 共享同一 infoHash，第一个通过后后续 clone 被跳过
            if (!pushedHashes.add(hash)) {
                log.debug("infoHash 已推送过，跳过 {} {}", hash, reName);
                if (master && !is5) {
                    currentDownloadCount++;
                }
                continue;
            }

            // 未开启rename不进行检测; 洗版(高版本)不受本地已下载判断拦截
            boolean washing = v2 && item.getVersion() != null && item.getVersion() > 1;
            if (!washing && itemDownloaded(ani, item, true, localEpisodeIndex)) {
                log.info("本地文件已存在 {}", reName);
                if (master && !is5) {
                    currentDownloadCount++;
                }
                continue;
            }

            // 同时下载数量限制
            if (downloadCount > 0) {
                if (count >= downloadCount) {
                    log.debug("达到同时下载数量限制 {}", downloadCount);
                    continue;
                }
            }

            // OpenList 提交 != 完成：先写待完成标记，离线完成后才提升为正式种子记录
            // 其余下载方式保持原行为：提交即写正式记录（预览视为已下载）
            boolean openListTool = isOpenListTool();
            // OpenList: pending 标记存在表示离线进行中, 本轮跳过, 避免重复提交与通知轰炸
            if (openListTool && TorrentUtil.getPendingTorrent(ani, item).exists()) {
                log.debug("离线任务进行中, 跳过本轮 {}", reName);
                continue;
            }
            File saveTorrent = openListTool
                    ? TorrentUtil.saveTorrentPending(ani, item)
                    : TorrentUtil.saveTorrent(ani, item);

            if (!saveTorrent.exists()) {
                // 种子下载失败
                continue;
            }

            deleteStandbyRss(ani, item);

            if (!AniUtil.getAniList().contains(ani)) {
                return;
            }

            sync = true;

            download(ani, item, savePath, saveTorrent);

            if (master && !is5) {
                currentDownloadCount++;
            }
            // v2: 记录已下载集数（合集记录所有范围内的集数）
            if (v2 && downloadedEpisodes != null) {
                if (item.getEpisodeRange() != null && !item.getEpisodeRange().isEmpty()) {
                    downloadedEpisodes.addAll(item.getEpisodeRange());
                } else {
                    downloadedEpisodes.add(episode);
                }
            }
            count++;
        }

        // 有新下载，或漏集数量变化时落盘（避免每轮无意义写 ani.v2.json）
        if (sync || omitChanged) {
            if (sync) {
                int size = ItemsUtil.currentEpisodeNumber(ani, items);
                ani.setCurrentEpisodeNumber(size);
                ani.setLastDownloadTime(System.currentTimeMillis());
            }
            AniUtil.sync();
        }

        if (!autoDisabled) {
            return;
        }
        Integer totalEpisodeNumber = ani.getTotalEpisodeNumber();
        if (totalEpisodeNumber < 1) {
            return;
        }
        if (currentDownloadCount >= totalEpisodeNumber) {
            log.info("{} 第 {} 季 共 {} 集 已全部下载完成, 自动停止订阅", title, season, totalEpisodeNumber);
            NotificationUtil.send(config, ani, StrFormatter.format("{} 订阅已完结", title), NotificationStatusEnum.COMPLETED);
            ani.setEnable(false);
            AniUtil.sync();
        }
    }

    /**
     * 预览洗版将删除的种子/文件（不实际删除）。
     */
    public List<WashPreview.Candidate> previewStandbyDeletes(Ani ani, Item item) {
        Config config = ConfigUtil.CONFIG;
        Boolean standbyRss = config.getStandbyRss();
        Boolean coexist = config.getCoexist();
        Boolean delete = config.getDelete();
        String reName = item == null ? null : item.getReName();
        String downloadPath = getDownloadPath(ani);

        List<String> torrentNames = new ArrayList<>();
        try {
            for (TorrentsInfo t : TorrentUtil.getTorrentsInfos()) {
                if (t == null) continue;
                if (downloadPath != null && downloadPath.equals(t.getDownloadDir())) {
                    torrentNames.add(t.getName());
                }
            }
        } catch (Exception e) {
            log.debug("预览洗版读取种子失败: {}", e.getMessage());
        }
        List<String> fileNames = new ArrayList<>();
        try {
            File[] files = FileUtils.listFiles(downloadPath);
            if (files != null) {
                for (File f : files) {
                    if (f != null) fileNames.add(f.getName());
                }
            }
        } catch (Exception e) {
            log.debug("预览洗版读取文件失败: {}", e.getMessage());
        }
        return WashPreview.preview(reName, torrentNames, fileNames,
                Boolean.TRUE.equals(standbyRss), Boolean.TRUE.equals(delete), Boolean.TRUE.equals(coexist));
    }

    public void deleteStandbyRss(Ani ani, Item item) {
        Config config = ConfigUtil.CONFIG;
        Boolean standbyRss = config.getStandbyRss();
        Boolean coexist = config.getCoexist();
        Boolean delete = config.getDelete();
        String reName = item.getReName();

        if (!delete) {
            return;
        }

        if (!standbyRss) {
            return;
        }

        if (coexist) {
            // 开启多字幕组共存将不会进行洗版
            return;
        }

        if (!ReUtil.contains(StringEnum.SEASON_REG, reName)) {
            return;
        }

        // 只取 SxxExx 片段做洗版匹配；忽略大小写避免 s01e03 / S01E03 漏删
        String episode = ReUtil.get(StringEnum.SEASON_REG, reName, 0);

        String downloadPath = getDownloadPath(ani);

        List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();

        torrentsInfos
                .stream()
                .filter(torrentsInfo -> {
                    String name = torrentsInfo.getName();
                    String downloadDir = torrentsInfo.getDownloadDir();
                    if (!downloadDir.equals(downloadPath)) {
                        return false;
                    }
                    if (!ReUtil.contains(StringEnum.SEASON_REG, name)) {
                        return false;
                    }
                    String s = ReUtil.get(StringEnum.SEASON_REG, name, 0);
                    return s.equalsIgnoreCase(episode);
                })
                .findFirst()
                .ifPresent(standbyRSS ->
                        TorrentUtil.delete(standbyRSS, true, true)
                );

        File[] files = FileUtils.listFiles(downloadPath);
        for (File file : files) {
            String fileMainName = FileUtil.mainName(file);
            if (StrUtil.isBlank(fileMainName)) {
                continue;
            }
            if (!ReUtil.contains(StringEnum.SEASON_REG, fileMainName)) {
                continue;
            }
            fileMainName = ReUtil.get(StringEnum.SEASON_REG, fileMainName, 0);
            if (!fileMainName.equalsIgnoreCase(episode)) {
                continue;
            }
            boolean isDel = false;
            // 文件在删除前先判断其格式
            if (file.isFile()) {
                String extName = FileUtil.extName(file);
                // 没有后缀 跳过
                if (StrUtil.isBlank(extName)) {
                    continue;
                }
                if (FileUtils.isVideoFormat(extName)) {
                    isDel = true;
                }
                if (List.of("nfo", "bif").contains(extName)) {
                    isDel = true;
                }
                if (file.getName().endsWith("-thumb.jpg")) {
                    isDel = true;
                }
            }
            if (file.isDirectory()) {
                isDel = true;
            }
            if (isDel) {
                log.info("已开启备用RSS, 自动删除 {}", FileUtils.getAbsolutePath(file));
                try {
                    FileUtil.del(file);
                    log.info("删除成功 {}", FileUtils.getAbsolutePath(file));
                } catch (Exception e) {
                    log.error("删除失败 {}", FileUtils.getAbsolutePath(file));
                    log.error(e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 下载
     *
     * @param ani
     * @param item
     * @param savePath
     * @param torrentFile
     */
    /**
     * 是否离线下载方式(提交 != 完成, 需 pending 标记)：基于当前下载器实例能力判断，
     * 新增离线型下载器无需再改此处
     */
    private static boolean isOpenListTool() {
        return TorrentUtil.isOfflineTool();
    }

    public void download(Ani ani, Item item, String savePath, File torrentFile) {
        ani = ObjectUtil.clone(ani);

        String name = item.getReName();
        Boolean master = item.getMaster();
        String subgroup = item.getSubgroup();
        subgroup = StrUtil.blankToDefault(subgroup, "未知字幕组");
        ani.setSubgroup(subgroup);

        log.info("添加下载 {}", name);

        if (!torrentFile.exists()) {
            log.error("种子下载出现问题 {} {}", name, FileUtils.getAbsolutePath(torrentFile));
            return;
        }
        // 不再固定 sleep；仅在推送失败重试时退避
        savePath = FileUtils.getAbsolutePath(savePath);

        String notifyText = StrFormatter.format("{} 已更新", name);
        if (!master) {
            notifyText = StrFormatter.format("(备用RSS) {}", notifyText);
        }
        NotificationUtil.send(ConfigUtil.CONFIG, ani, notifyText, NotificationStatusEnum.DOWNLOAD_START);

        Config config = ConfigUtil.CONFIG;

        Integer downloadRetry = config.getDownloadRetry();
        // OpenList/Alist 内部会长时间等待离线完成，不能持有全局下载器锁，否则会把 3 路并行订阅串成 1 路
        boolean openListTool = isOpenListTool();
        boolean holdToolLock = !openListTool;
        // OpenList 已在内部按【离线超时】硬等待，外层再乘 downloadRetry 会把总等待放大成 N 倍
        int maxAttempts = openListTool ? 1 : ObjectUtil.defaultIfNull(downloadRetry, 1);
        maxAttempts = Math.max(maxAttempts, 1);
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                boolean ok;
                if (holdToolLock) {
                    // qB/Transmission 等：短推送串行，避免多订阅同时打下载器 API
                    synchronized (DOWNLOAD_TOOL_LOCK) {
                        ok = TorrentUtil.DOWNLOAD.download(ani, item, savePath, torrentFile);
                    }
                } else {
                    // OpenList 自行按 infoHash 串行 + API 限流
                    ok = TorrentUtil.DOWNLOAD.download(ani, item, savePath, torrentFile);
                }
                if (ok) {
                    // OpenList: 提交即受理——等待/提升/失败处理已移交 OpenList 独立长任务池，
                    // pending 标记保持到离线真正完成，预览不会误判"已下载"
                    if (openListTool) {
                        return;
                    }
                    TorrentUtil.refreshTorrentsCache();
                    return;
                }
                // OpenList/Alist 返回 false：多为 10008 等待、任务 Failed/取消、离线工具侧失败
                // 不等于坏种；占用已在 OpenList 内部按 hash 清理/释放
                if (openListTool) {
                    String raw = name + " 离线下载未完成（OpenList 返回失败，非坏种）";
                    log.error(raw);
                    recordDownloadFailure(ani, item, raw);
                    NotificationUtil.send(ConfigUtil.CONFIG, ani,
                            TaskFailureHumanizer.formatNotify(name, raw),
                            NotificationStatusEnum.ERROR);
                    TorrentUtil.deletePendingTorrent(ani, item);
                    return;
                }
            } catch (ani.rss.download.OfflineTimeoutException e) {
                // 超时 != 坏种：不删种子、不报疑似坏种；占用已在 OpenList 内清理
                String message = ExceptionUtils.getMessage(e);
                log.error("{} 离线超时失败: {}", name, message);
                recordDownloadFailure(ani, item, message);
                NotificationUtil.send(ConfigUtil.CONFIG, ani,
                        TaskFailureHumanizer.formatNotify(name, message),
                        NotificationStatusEnum.ERROR);
                TorrentUtil.deletePendingTorrent(ani, item);
                return;
            } catch (Exception e) {
                String message = ExceptionUtils.getMessage(e);
                log.error(message, e);
            }
            if (i < maxAttempts) {
                log.error("{} 下载失败将进行重试, 当前重试次数为{}次", name, i);
                // 失败退避：1s、2s、3s...
                ThreadUtil.sleep(Math.min(1000L * i, 3000L));
            }
        }

        if (openListTool) {
            // OpenList 普通异常:按离线未完成处理, 清 pending, 不报坏种
            String raw = name + " 离线下载未完成（OpenList 异常，非坏种）";
            log.error(raw);
            recordDownloadFailure(ani, item, raw);
            NotificationUtil.send(ConfigUtil.CONFIG, ani,
                    TaskFailureHumanizer.formatNotify(name, raw),
                    NotificationStatusEnum.ERROR);
            TorrentUtil.deletePendingTorrent(ani, item);
            return;
        }

        // 删除下载失败的种子, 下次轮询仍会重试
        FileUtil.del(torrentFile);

        String raw = name + " 添加失败，疑似为坏种";
        log.error(raw);
        recordDownloadFailure(ani, item, raw);
        NotificationUtil.send(ConfigUtil.CONFIG, ani,
                TaskFailureHumanizer.formatNotify(name, raw),
                NotificationStatusEnum.ERROR);
    }

    private void recordDownloadFailure(Ani ani, Item item, String rawMessage) {
        try {
            String hash = item == null ? null : item.getInfoHash();
            FailedDownloadQueue.record(
                    ani == null ? null : ani.getId(),
                    ani == null ? null : ani.getTitle(),
                    item == null ? null : item.getReName(),
                    hash,
                    rawMessage);
        } catch (Exception e) {
            log.debug("记录失败队列失败: {}", e.getMessage());
        }
    }

    /**
     * 失败队列：精确重下单条（不触发整订 RSS 刷新）。
     *
     * @return 给人看的结果文案
     */
    public String retryFailedItem(FailedDownloadQueue.FailedItem failed) {
        if (failed == null || StrUtil.isBlank(failed.getAniId())) {
            throw new IllegalArgumentException("失败条目无效");
        }
        Ani ani = AniUtil.getAniList().stream()
                .filter(a -> Objects.equals(a.getId(), failed.getAniId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("关联订阅不存在"));

        Object lock = lockForAni(ani);
        synchronized (lock) {
            List<Item> items = ItemsUtil.getItems(ani);
            Item match = matchFailedItem(items, failed);
            if (match == null) {
                throw new IllegalStateException("RSS 中已找不到该条目（可能已过期），无法精确重下");
            }

            // 清掉成功标记（种子缓存），否则 saveTorrent/download 会被跳过
            File marker = TorrentUtil.getTorrent(ani, match);
            if (marker != null && marker.exists()) {
                FileUtil.del(marker);
            }
            // 兼容旧逻辑：目录内按 hash 子串再扫一遍
            if (StrUtil.isNotBlank(failed.getInfoHash())) {
                File torrentDir = TorrentUtil.getTorrentDir(ani);
                File[] files = torrentDir.listFiles();
                if (files != null) {
                    String h = failed.getInfoHash().toLowerCase(Locale.ROOT);
                    for (File f : files) {
                        if (f != null && f.getName().toLowerCase(Locale.ROOT).contains(h)) {
                            FileUtil.del(f);
                        }
                    }
                }
            }

            File saved = isOpenListTool()
                    ? TorrentUtil.saveTorrentPending(ani, match)
                    : TorrentUtil.saveTorrent(ani, match);
            if (saved == null || !saved.exists()) {
                String raw = StrUtil.blankToDefault(match.getReName(), failed.getReName()) + " 种子下载失败，无法重试";
                recordDownloadFailure(ani, match, raw);
                throw new IllegalStateException(raw);
            }

            long failedAtBefore = failed.getFailedAt() == null ? 0L : failed.getFailedAt();
            String savePath = getDownloadPath(ani);
            // 失败路径会 record 并刷新 failedAt；成功则队列条目时间戳不变
            download(ani, match, savePath, saved);

            String key = FailedDownloadQueue.keyOf(failed.getAniId(), match.getInfoHash(), match.getReName());
            Optional<FailedDownloadQueue.FailedItem> after = FailedDownloadQueue.list().stream()
                    .filter(i -> Objects.equals(i.getId(), failed.getId()) || Objects.equals(i.getId(), key))
                    .findFirst();
            if (after.isPresent()) {
                Long at = after.get().getFailedAt();
                if (at != null && at > failedAtBefore) {
                    throw new IllegalStateException(StrUtil.blankToDefault(after.get().getMessage(),
                            "推送下载未成功，条目仍在失败队列"));
                }
            }
            FailedDownloadQueue.remove(failed.getId());
            FailedDownloadQueue.remove(key);
            return "已精确重下：" + StrUtil.blankToDefault(match.getReName(), failed.getReName());
        }
    }

    /**
     * 强制下载: 删除已有文件(本地/网盘)与种子记录后, 走正常下载流程重新下载。
     * 重命名/等待/离线提交等处理与正常下载完全一致。
     *
     * @return 结果文案
     */
    public String forceDownloadItem(Ani ani, Item item) {
        if (ani == null || item == null) {
            throw new IllegalArgumentException("参数无效");
        }
        Object lock = lockForAni(ani);
        synchronized (lock) {
            // 1. 清种子记录(正式 + pending)
            File marker = TorrentUtil.getTorrent(ani, item);
            if (marker != null && marker.exists()) {
                FileUtil.del(marker);
            }
            TorrentUtil.deletePendingTorrent(ani, item);

            // 2. 删除已有文件: 离线网盘用 API 删文件, 本地直接删文件
            String downloadPath = getDownloadPath(ani);
            if (TorrentUtil.DOWNLOAD instanceof OfflineDownloader offline) {
                offline.forceDeleteFiles(downloadPath, item.getReName());
            } else {
                deleteLocalFilesByReName(downloadPath, item.getReName());
            }

            // 3. 走正常下载流程(提交/离线等待/重命名等)
            File saved = isOpenListTool()
                    ? TorrentUtil.saveTorrentPending(ani, item)
                    : TorrentUtil.saveTorrent(ani, item);
            if (saved == null || !saved.exists()) {
                throw new IllegalStateException(item.getReName() + " 种子下载失败，无法强制下载");
            }
            download(ani, item, downloadPath, saved);
            return "已强制下载：" + item.getReName();
        }
    }

    /**
     * 删除本地下载目录下与 reName 匹配的文件(主名相等或包含)
     */
    private void deleteLocalFilesByReName(String downloadPath, String reName) {
        if (StrUtil.isBlank(downloadPath) || StrUtil.isBlank(reName)) {
            return;
        }
        String target = reName.trim().toUpperCase();
        List<File> files = FileUtils.listFileList(downloadPath);
        for (File file : files) {
            String main = FileUtil.mainName(file).trim().toUpperCase();
            if (main.equals(target) || main.contains(target)) {
                try {
                    FileUtil.del(file);
                    log.info("强制下载: 删除本地已有文件 {}", file.getPath());
                } catch (Exception e) {
                    log.warn("强制下载: 删除本地文件失败 {}: {}", file.getPath(), ExceptionUtils.getMessage(e));
                }
            }
        }
    }

    private static Item matchFailedItem(List<Item> items, FailedDownloadQueue.FailedItem failed) {
        if (items == null || items.isEmpty() || failed == null) {
            return null;
        }
        String hash = StrUtil.blankToDefault(failed.getInfoHash(), "").trim().toLowerCase(Locale.ROOT);
        if (StrUtil.isNotBlank(hash)) {
            for (Item it : items) {
                if (it == null) continue;
                String ih = StrUtil.blankToDefault(it.getInfoHash(), "").trim().toLowerCase(Locale.ROOT);
                if (hash.equals(ih)) {
                    return it;
                }
            }
        }
        String reName = StrUtil.blankToDefault(failed.getReName(), "");
        if (StrUtil.isNotBlank(reName)) {
            for (Item it : items) {
                if (it != null && reName.equals(it.getReName())) {
                    return it;
                }
            }
        }
        return null;
    }

    /**
     * 下载完成通知
     *
     * @param torrentsInfo
     */
    public synchronized void notification(TorrentsInfo torrentsInfo) {
        TorrentsInfo.State state = torrentsInfo.getState();
        String name = torrentsInfo.getName();

        if (Objects.isNull(state)) {
            return;
        }
        if (!List.of(
                TorrentsInfo.State.queuedUP.name(),
                TorrentsInfo.State.uploading.name(),
                TorrentsInfo.State.stalledUP.name(),
                TorrentsInfo.State.pausedUP.name(),
                TorrentsInfo.State.stoppedUP.name()
        ).contains(state.name())) {
            return;
        }
        // 添加下载完成标签，防止重复通知
        List<String> tags = torrentsInfo.getTags();
        if (tags.contains(TorrentsTags.DOWNLOAD_COMPLETE.getValue())) {
            return;
        }
        Boolean b = TorrentUtil.addTags(torrentsInfo, TorrentsTags.DOWNLOAD_COMPLETE.getValue());
        if (!b) {
            return;
        }
        Optional<Ani> aniOpt = findAniByDownloadPath(torrentsInfo);

        if (aniOpt.isEmpty()) {
            log.debug("未能获取番剧对象: {}", torrentsInfo.getName());
            return;
        }

        Ani ani = aniOpt.get();

        // 根据标签反向判断出字幕组
        String subgroup = ani.getSubgroup();
        Set<String> collect = ani.getStandbyRssList()
                .stream()
                .map(StandbyRss::getLabel)
                .collect(Collectors.toSet());

        subgroup = tags
                .stream()
                .filter(collect::contains)
                .findFirst()
                .orElse(subgroup);
        subgroup = StrUtil.blankToDefault(subgroup, "未知字幕组");
        ani.setSubgroup(subgroup);

        Config config = ConfigUtil.CONFIG;
        Boolean scrape = config.getScrape();
        if (scrape) {
            try {
                // 刮削
                scrapeService.scrape(ani, false);
            } catch (Exception e) {
                log.error("刮削失败: {}", ani.getTitle());
                log.error(e.getMessage(), e);
            }
        }
        String text = StrFormatter.format("{} 下载完成", name);
        if (tags.contains(TorrentsTags.BACK_RSS.getValue())) {
            text = StrFormatter.format("(备用RSS) {}", text);
        }
        NotificationUtil.send(ConfigUtil.CONFIG, ani, text, NotificationStatusEnum.DOWNLOAD_END);

        String title = ani.getTitle();

        try {
            AniUtil.completed(ani);
        } catch (Exception e) {
            log.error("番剧完结迁移失败 {}", title);
            log.error(e.getMessage(), e);
        }
    }

    /**
     * 获取下载位置
     *
     * @param ani
     * @return
     */
    public String getDownloadPath(Ani ani) {
        return getDownloadPath(ani, ConfigUtil.CONFIG);
    }

    /**
     * 获取下载位置
     *
     * @param ani
     * @return
     */
    public String getDownloadPath(Ani ani, Config config) {
        Boolean customDownloadPath = ani.getCustomDownloadPath();
        String aniDownloadPath = ani.getDownloadPath();
        Boolean ova = ani.getOva();

        String downloadPathTemplate = config.getDownloadPathTemplate();
        String ovaDownloadPathTemplate = config.getOvaDownloadPathTemplate();
        if (Boolean.TRUE.equals(ova) && StrUtil.isNotBlank(ovaDownloadPathTemplate)) {
            // 剧场版位置
            downloadPathTemplate = ovaDownloadPathTemplate;
        }

        if (Boolean.TRUE.equals(customDownloadPath) && StrUtil.isNotBlank(aniDownloadPath)) {
            // 自定义下载位置
            downloadPathTemplate = StrUtil.split(aniDownloadPath, "\n", true, true)
                    .stream()
                    .map(FileUtils::getAbsolutePath)
                    .findFirst()
                    .orElse(downloadPathTemplate);
        }

        String title = ani.getTitle().trim();

        String pinyin = PinyinUtils.getPinyin(title);
        String letter = pinyin.substring(0, 1).toUpperCase();
        if (ReUtil.isMatch("^\\d$", letter)) {
            letter = "0";
        } else if (!ReUtil.isMatch("^[a-zA-Z]$", letter)) {
            letter = "#";
        }

        downloadPathTemplate = downloadPathTemplate.replace("${letter}", letter);

        Date releaseDate = ani.getReleaseDate();

        int year = DateUtil.year(releaseDate);
        int month = DateUtil.month(releaseDate) + 1;
        String monthFormat = String.format("%02d", month);

        // 季度
        if (
                downloadPathTemplate.contains("${quarter}") ||
                        downloadPathTemplate.contains("${quarterFormat}") ||
                        downloadPathTemplate.contains("${quarterName}")
        ) {
            int quarter;
            String quarterName;
            /*
            https://github.com/wushuo894/ani-rss/pull/451
            优化季度判断规则，避免将月底先行播放的番归类到上个季度
            */
            if (List.of(12, 1, 2).contains(month)) {
                if (month == 12) {
                    // 当使用季度信息, 并且月份等于12时, 年份自动 +1。避免年份与月份不一致
                    year++;
                }
                quarter = 1;
                quarterName = "冬";
            } else if (List.of(3, 4, 5).contains(month)) {
                quarter = 4;
                quarterName = "春";
            } else if (List.of(6, 7, 8).contains(month)) {
                quarter = 7;
                quarterName = "夏";
            } else {
                quarter = 10;
                quarterName = "秋";
            }
            String quarterFormat = String.format("%02d", quarter);
            downloadPathTemplate = downloadPathTemplate.replace("${quarter}", String.valueOf(quarter));
            downloadPathTemplate = downloadPathTemplate.replace("${quarterFormat}", quarterFormat);
            downloadPathTemplate = downloadPathTemplate.replace("${quarterName}", quarterName);
        }

        downloadPathTemplate = downloadPathTemplate.replace("${year}", String.valueOf(year));
        downloadPathTemplate = downloadPathTemplate.replace("${month}", String.valueOf(month));
        downloadPathTemplate = downloadPathTemplate.replace("${monthFormat}", monthFormat);

        int season = ani.getSeason();
        String seasonFormat = String.format("%02d", season);

        downloadPathTemplate = downloadPathTemplate.replace("${season}", String.valueOf(season));
        downloadPathTemplate = downloadPathTemplate.replace("${seasonFormat}", seasonFormat);

        String bgmId = BgmUtil.getSubjectId(ani);
        downloadPathTemplate = downloadPathTemplate.replace("${bgmId}", bgmId);

        List<Func1<Ani, Object>> list = List.of(
                Ani::getTitle,
                Ani::getThemoviedbName,
                Ani::getSubgroup
        );

        downloadPathTemplate = RenameUtil.replaceField(downloadPathTemplate, ani, list);

        String tmdbId = Opt.ofNullable(ani.getTmdb())
                .map(Tmdb::getId)
                .filter(StrUtil::isNotBlank)
                .orElse("");

        downloadPathTemplate = downloadPathTemplate.replace("${tmdbid}", tmdbId);

        if (downloadPathTemplate.contains("${jpTitle}")) {
            String jpTitle = RenameUtil.getJpTitle(ani);
            downloadPathTemplate = downloadPathTemplate.replace("${jpTitle}", jpTitle);
        }

        return FileUtils.getAbsolutePath(downloadPathTemplate);
    }


    /**
     * 构建本地下载目录中的集数索引，避免每个 item 重复 listFiles。
     * OpenList/Alist: 下载目录为网盘虚拟路径, 通过 OpenList API 列出文件构建索引。
     */
    private Set<String> buildLocalEpisodeIndex(Ani ani, String downloadPath) {
        Set<String> index = new HashSet<>();
        boolean ovaLegacy = Boolean.TRUE.equals(ani.getOva()) && !RenameUtil.isNamingV2(ani);
        // 剧场版(电影式)/旧版 OVA: 文件名不含 SxxExx, 按文件名主名索引
        boolean movieStyle = RenameUtil.isMovie(ani) || ovaLegacy;

        if (TorrentUtil.DOWNLOAD instanceof OfflineDownloader offline) {
            // 网盘虚拟路径, 本地文件系统不可见, 用离线网盘 API 列出文件
            for (String name : offline.listFileNames(downloadPath)) {
                addFileToIndex(index, name, movieStyle);
            }
            return index;
        }

        List<File> files = FileUtils.listFileList(downloadPath);
        for (File file : files) {
            if (file.isDirectory()) {
                // 目录不参与索引, 避免同名目录误判已下载
                continue;
            }
            if (file.isFile()) {
                String extName = FileUtil.extName(file);
                if (StrUtil.isBlank(extName) || !FileUtils.isVideoFormat(extName)) {
                    continue;
                }
            }
            addFileToIndex(index, file.getPath(), movieStyle);
        }
        return index;
    }

    /**
     * 将文件名加入本地索引: movieStyle 用 M: 主名, 普通番剧用 season:episode
     */
    private static void addFileToIndex(Set<String> index, String filePath, boolean movieStyle) {
        String mainName = FileUtil.mainName(new File(filePath));
        if (StrUtil.isBlank(mainName)) {
            return;
        }
        mainName = mainName.trim().toUpperCase();
        if (movieStyle) {
            index.add("M:" + mainName);
            return;
        }
        if (!ReUtil.contains(StringEnum.SEASON_REG, mainName)) {
            return;
        }
        String seasonStr = ReUtil.get(StringEnum.SEASON_REG, mainName, 1);
        String episodeStr = ReUtil.get(StringEnum.SEASON_REG, mainName, 2);
        if (StrUtil.isBlank(seasonStr) || StrUtil.isBlank(episodeStr)) {
            return;
        }
        try {
            int s = Integer.parseInt(seasonStr);
            double e = Double.parseDouble(episodeStr);
            // 统一规范化，匹配时 O(1) 查找
            index.add(s + ":" + e);
        } catch (Exception ignored) {
        }
    }

    /**
     * 判断是否已经下载过
     *
     * @param ani
     * @param item
     * @param downloadList
     * @return
     */
    public Boolean itemDownloaded(Ani ani, Item item, Boolean downloadList) {
        return itemDownloaded(ani, item, downloadList, null);
    }

    /**
     * 判断是否已经下载过
     *
     * @param ani
     * @param item
     * @param downloadList
     * @param localEpisodeIndex 预构建的本地集数索引，null 时按需构建
     * @return
     */
    public Boolean itemDownloaded(Ani ani, Item item, Boolean downloadList, Set<String> localEpisodeIndex) {
        Config config = ConfigUtil.CONFIG;
        Boolean rename = config.getRename();
        if (!rename) {
            return false;
        }

        String downloadPathTemplate = config.getDownloadPathTemplate();

        if (StrUtil.isBlank(downloadPathTemplate)) {
            return false;
        }

        Boolean fileExist = config.getFileExist();
        if (!fileExist) {
            return false;
        }

        Integer season = ani.getSeason();
        Boolean ova = ani.getOva();
        String reName = item.getReName();
        Double episode = item.getEpisode();

        String downloadPath = getDownloadPath(ani);

        if (downloadList) {
            List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();
            for (TorrentsInfo torrentsInfo : torrentsInfos) {
                String name = torrentsInfo.getName();
                if (!name.equalsIgnoreCase(reName)) {
                    continue;
                }
                String downloadDir = torrentsInfo.getDownloadDir();
                if (!downloadDir.equals(downloadPath)) {
                    continue;
                }
                log.info("已存在下载任务 {}", reName);
                TorrentUtil.saveTorrent(ani, item);
                return true;
            }
        }

        if (localEpisodeIndex == null) {
            localEpisodeIndex = buildLocalEpisodeIndex(ani, downloadPath);
        }

        boolean ovaLegacy = Boolean.TRUE.equals(ova) && !RenameUtil.isNamingV2(ani);
        // 剧场版(电影式)/旧版 OVA: 按文件名主名匹配(M: 前缀)
        boolean movieStyle = RenameUtil.isMovie(ani) || ovaLegacy;
        // OVA 特典式(v2): 落盘为 S00Exx(season=0), 用 0 参与匹配
        boolean ovaSpecial = Boolean.TRUE.equals(ova) && RenameUtil.isNamingV2(ani) && !RenameUtil.isMovie(ani);
        boolean exists;
        if (movieStyle) {
            exists = StrUtil.isNotBlank(reName)
                    && localEpisodeIndex.contains("M:" + reName.trim().toUpperCase());
            if (!exists && StrUtil.isNotBlank(ani.getTitle())) {
                // qB 等对无 SxxExx 的任务不重命名文件, 文件名可能是种子原始名,
                // 精确 reName 匹配失败时按订阅标题主名放宽匹配, 避免已下载被误判未下载导致重下循环
                String titleUp = RenameUtil.getName(ani.getTitle()).trim().toUpperCase();
                if (StrUtil.isNotBlank(titleUp) && titleUp.length() >= 2) {
                    final String t = titleUp;
                    exists = localEpisodeIndex.stream()
                            .anyMatch(k -> k.startsWith("M:") && k.contains(t));
                }
            }
        } else if (ovaSpecial) {
            exists = episode != null && localEpisodeIndex.contains("0:" + episode);
        } else if (episode == null) {
            exists = false;
        } else {
            exists = localEpisodeIndex.contains(season + ":" + episode);
        }

        if (exists) {
            // 保存 torrent 下次只校验 torrent 是否存在，可以把config设置到固态硬盘，防止一直硬盘机机械硬盘
            TorrentUtil.saveTorrent(ani, item);
            log.info("本地已存在 {}", reName);
            return true;
        }
        return false;
    }

    /**
     * 下载路径 → 订阅 反向索引。
     * getDownloadPath 含 Pinyin/季度/占位符等重计算，避免 RenameTask 每轮对每个任务全量重算。
     * 订阅或配置变更（AniUtil.sync / ConfigUtil.sync）时置空失效，miss 时自动重建自愈。
     */
    private static volatile Map<String, Ani> DOWNLOAD_PATH_INDEX;

    private static final Object INDEX_LOCK = new Object();

    public static void invalidateDownloadPathIndex() {
        DOWNLOAD_PATH_INDEX = null;
    }

    private Map<String, Ani> buildDownloadPathIndex() {
        Map<String, Ani> index = new HashMap<>();
        for (Ani ani : AniUtil.getAniList()) {
            if (ani == null) {
                continue;
            }
            try {
                index.put(getDownloadPath(ani), ani);
            } catch (Exception e) {
                log.debug("构建下载路径索引失败: {} {}", ani.getTitle(), ExceptionUtils.getMessage(e));
            }
        }
        return index;
    }

    /**
     * 根据任务反查订阅
     *
     * @param torrentsInfo
     * @return
     */
    public Optional<Ani> findAniByDownloadPath(TorrentsInfo torrentsInfo) {
        String downloadDir = torrentsInfo.getDownloadDir();
        if (StrUtil.isBlank(downloadDir)) {
            return Optional.empty();
        }

        Map<String, Ani> index = DOWNLOAD_PATH_INDEX;
        if (index == null) {
            synchronized (INDEX_LOCK) {
                index = DOWNLOAD_PATH_INDEX;
                if (index == null) {
                    index = buildDownloadPathIndex();
                    DOWNLOAD_PATH_INDEX = index;
                }
            }
        }

        Ani ani = index.get(downloadDir);
        if (ani == null) {
            // 缓存可能过期（BGM/JP 标题等外部数据变化）：重建后重查一次，避免误判未下载
            synchronized (INDEX_LOCK) {
                index = buildDownloadPathIndex();
                DOWNLOAD_PATH_INDEX = index;
            }
            ani = index.get(downloadDir);
        }

        return ani == null ? Optional.empty() : Optional.of(ObjectUtil.clone(ani));
    }

}
