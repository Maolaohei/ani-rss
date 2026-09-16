package ani.rss.service;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.PinyinUtils;
import ani.rss.download.OfflineDownloader;
import ani.rss.download.OpenList;
import ani.rss.download.OpenListApi;
import ani.rss.entity.*;
import ani.rss.enums.EventTypeEnum;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 下载的主要逻辑
 */
@Slf4j
@Service
public class DownloadService {
    /** 非 OpenList 下载器推送串行；OpenList 不持此锁，避免长等待全局串行 */
    private static final Object DOWNLOAD_TOOL_LOCK = new Object();

    @Resource
    private ScrapeService scrapeService;

    /**
     * 下载动漫（按 ani.id 取<b>写锁</b>，同一订阅串行、不同订阅并行）
     * <p>
     * F6-1：锁从普通互斥对象升级为读写锁，读路径（预览/媒体库/手动搜索）因此可以
     * 在下载进行中继续读缓存而不必排队。见 {@link AniLocks}。
     *
     * @param ani
     */
    public void downloadAni(Ani ani) {
        AniLocks.runWithWrite(ani, () -> downloadAniLocked(ani));
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
        // 结构化事件: 漏集数量发生变化且确实存在漏集时推送一次。
        // 加 omitChanged 是为了避免每轮 RSS 扫描都重复推送同一批漏集。
        if (omitN > 0 && omitChanged) {
            Map<String, Object> omitEvent = new LinkedHashMap<>();
            omitEvent.put("omitCount", omitN);
            omitEvent.put("omitList", omitGaps);
            EventWebhookUtil.emit(EventTypeEnum.OMIT_DETECTED, ani, omitEvent);
        }
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

            // 「备用RSS占位」待清除任务：只在这里登记, 真正的删除推迟到确认要下载之前。
            // 若在上面任一闸门(新种子等待/同时下载数/离线进行中/失败队列)就 continue,
            // 占位文件会被白删 —— 该集在等待窗口内既无占位文件也没有主RSS文件。
            TorrentsInfo pendingStandbyPlaceholder = null;

            // 已经下载过
            if (torrent.exists()) {
                // v2: 检查版本号，高版本覆盖低版本（洗版）
                if (v2 && item.getVersion() != null && item.getVersion() > 1) {
                    log.info("检测到高版本 {} v{}, 准备洗版", reName, item.getVersion());
                    // 不跳过，继续下载流程
                } else {
                    // 记录有效性校验: 下载器有对应任务 或 本地有对应文件 才视为已下载;
                    // OpenList/Alist: 下载目录是网盘虚拟路径, 本地文件不可见且任务列表恒空,
                    // 先归位对账, 对账未确认再兜底检查, 兜底也没找到才清理记录触发重新下载
                    boolean recordValid;
                    if (!Boolean.TRUE.equals(config.getRename())) {
                        recordValid = true;
                    } else if (isOpenListTool() && TorrentUtil.DOWNLOAD instanceof OfflineDownloader offline) {
                        String failKey = FailedDownloadQueue.keyOf(ani.getId(), item.getInfoHash(), reName);
                        boolean alreadyQueued = FailedDownloadQueue.list().stream()
                                .anyMatch(f -> Objects.equals(f.getId(), failKey));
                        if (alreadyQueued) {
                            // 失败队列已有本集记录: 跳过二次对账, 直接兜底校验, 避免每轮重复对账
                            recordValid = itemDownloaded(ani, item, true, localEpisodeIndex);
                            if (recordValid) {
                                // 文件已实际就位(如手动归位/上轮重下成功): 清掉过期失败记录
                                FailedDownloadQueue.remove(failKey);
                            }
                        } else {
                            // 周期对账（仅离线工具）：记录存在但文件滞留子目录/云下载目录时自动归位到顶层，
                            // 修复「显示已存在但文件不在预期位置」且无任何自动纠正机制的问题。
                            OfflineDownloader.RelocateResult relocated = null;
                            try {
                                relocated = offline.relocateEpisodeFiles(ani, item, savePath);
                            } catch (Exception e) {
                                log.warn("离线归位对账失败 {}: {}", reName, ExceptionUtils.getMessage(e));
                                recordRelocateFailure(ani, item, e);
                            }
                            if (relocated == OfflineDownloader.RelocateResult.RELOCATED
                                    || relocated == OfflineDownloader.RelocateResult.ALREADY_AT_TOP) {
                                recordValid = true;
                            } else {
                                // 对账没找到/失败：兜底检查（下载器任务 + 网盘视频文件按需检查）
                                recordValid = itemDownloaded(ani, item, true, localEpisodeIndex);
                                if (!recordValid) {
                                    log.warn("归位对账未找到且兜底检查无文件，清理过期种子记录并重新下载 {}", reName);
                                    if (relocated != null) {
                                        recordRelocateFailure(ani, item, new IllegalStateException(
                                                "归位对账未发现本集文件(" + relocated + ")"));
                                    }
                                }
                            }
                        }
                    } else {
                        recordValid = itemDownloaded(ani, item, true, localEpisodeIndex);
                    }
                    if (recordValid) {
                        // 主RSS记录被"备用RSS占位"证据命中: 登记待清除, 按未下载继续走流程,
                        // 实现主RSS替换。否则备用RSS先下载过的集会被"种子记录已存在/本地文件已存在"永久锁死。
                        // 注意这里只登记不删除(见 pendingStandbyPlaceholder 声明处)。
                        TorrentsInfo standbyPlaceholder = findRemovableStandbyPlaceholder(ani, item, torrentsInfos);
                        if (standbyPlaceholder != null) {
                            pendingStandbyPlaceholder = standbyPlaceholder;
                            // fall through 继续正常下载流程(主RSS自己的种子记录保留, 下载时幂等重写)
                        } else {
                            log.debug("种子记录已存在 {}", reName);
                            if (master && !is5) {
                                currentDownloadCount++;
                            }
                            if (v2 && downloadedEpisodes != null) {
                                downloadedEpisodes.add(episode);
                            }
                            continue;
                        }
                    } else {
                        log.warn("清理过期种子记录(无对应任务/文件) {}", reName);
                        FileUtil.del(torrent);
                    }
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

            // 新种子下载等待：发布时间距今不足 N 小时的种子暂缓下载（离线场景云端无人做种，
            // 立即提交只会超时失败并反复重提）。到期由下一轮 RSS 自然重查，无需新定时器。
            // pubDate 为 null 放行（部分 RSS 源无该字段，不能永远卡住）；
            // 洗版条目（v2 高版本）不拦截——修正版通常来自已有种子的重新发布。
            Integer newTorrentWaitHours = ObjectUtil.defaultIfNull(config.getNewTorrentWaitHours(), 2);
            boolean washingItem = v2 && item.getVersion() != null && item.getVersion() > 1;
            if (Objects.nonNull(pubDate) && newTorrentWaitHours > 0 && !washingItem) {
                long earliest = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(newTorrentWaitHours);
                if (pubDate.getTime() > earliest) {
                    // 站点时钟偏移导致 pubDate 在未来：按已过期处理，避免长期卡住
                    if (pubDate.getTime() <= System.currentTimeMillis()) {
                        long waitMinutes = Math.max(1,
                                (pubDate.getTime() - earliest) / TimeUnit.MINUTES.toMillis(1));
                        log.info("新种子等待: {} 发布不足 {} 小时，约 {} 分钟后到期待重试",
                                reName, newTorrentWaitHours, waitMinutes);
                        continue;
                    }
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
                    if (!TorrentUtil.delete(standbyRSS, false, true)) {
                        log.debug("备用RSS可能还未做种完成 {}", standbyRSS.getName());
                        // 删除失败或者不允许删除
                        continue;
                    }
                    torrentsInfos.remove(standbyRSS);
                    // 「仅在主RSS更新后删除备用RSS」的语义是删除任务与文件:
                    // 文件同步清除并移除本轮集数索引, 否则下方 itemDownloaded 仍会
                    // 因本地文件存在拦截主RSS下载, 替换永远无法发生
                    stripLocalEpisodeIndex(localEpisodeIndex, ani, item);
                }
            }

            // 已经下载过
            if (torrentsInfos
                    .stream()
                    .anyMatch(torrentsInfo ->
                            // hash 相同
                            torrentsInfo.getHash().equals(hash))) {
                log.info("已有下载任务 hash:{} name:{}", hash, reName);
                RssTask.countRoundLocalState(LocalState.EXISTS);
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
                // 主RSS更新时该集被"备用RSS占位": 登记待清除, 由主RSS替换(洗版)。
                // 同样只登记不删除, 见 pendingStandbyPlaceholder 声明处。
                TorrentsInfo standbyPlaceholder = findRemovableStandbyPlaceholder(ani, item, torrentsInfos);
                if (standbyPlaceholder != null) {
                    pendingStandbyPlaceholder = standbyPlaceholder;
                    log.info("备用RSS占位待清除, 由主RSS替换下载 {}", reName);
                } else {
                    log.info("本地文件已存在 {}", reName);
                    RssTask.countRoundLocalState(LocalState.EXISTS);
                    if (master && !is5) {
                        currentDownloadCount++;
                    }
                    continue;
                }
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
                RssTask.countRoundLocalState(LocalState.UNKNOWN);
                continue;
            }
            // (E9) 失败队列闸门: 仅非 OpenList 路径(qB/TR/Aria2)。近 24h 内失败过的本集
            // 不再每轮自动重推, 避免坏种/下载器故障时无限重试风暴; 条目保留在失败队列,
            // 由用户手动重试。OpenList 路径不加此闸门(其内部已有 10008/adopt 去重)。
            if (!openListTool) {
                String failKey = FailedDownloadQueue.keyOf(ani.getId(), item.getInfoHash(), reName);
                boolean recentFailed = FailedDownloadQueue.list().stream()
                        .anyMatch(f -> Objects.equals(f.getId(), failKey)
                                && f.getFailedAt() != null
                                && System.currentTimeMillis() - f.getFailedAt() < TimeUnit.HOURS.toMillis(24));
                if (recentFailed) {
                    log.warn("本集 24h 内下载失败(已保留在失败队列), 跳过本轮推送 {} hash={}", reName, hash);
                    RssTask.countRoundLocalState(LocalState.UNKNOWN);
                    continue;
                }
            }
            File saveTorrent = openListTool
                    ? TorrentUtil.saveTorrentPending(ani, item)
                    : TorrentUtil.saveTorrent(ani, item);

            if (!saveTorrent.exists()) {
                // 种子下载失败
                continue;
            }

            // 到这里才真正要下载了, 此时清除「备用RSS占位」才是安全的:
            // 上面任一闸门 continue 都不会走到这里, 占位文件得以保留。
            if (pendingStandbyPlaceholder != null) {
                removeStandbyPlaceholderTorrent(ani, item, torrentsInfos, localEpisodeIndex, pendingStandbyPlaceholder);
            }

            deleteStandbyRss(ani, item);

            if (!AniUtil.getAniList().contains(ani)) {
                return;
            }

            sync = true;

            // 结构化事件: 开始下载(与 DOWNLOAD_END 成对, 便于外部程序对账)
            Map<String, Object> startEvent = new LinkedHashMap<>();
            startEvent.put("reName", reName);
            startEvent.put("infoHash", hash);
            startEvent.put("episode", episode);
            startEvent.put("size", item.getLength());
            startEvent.put("source", config.getDownloadToolType());
            startEvent.put("subgroup", item.getSubgroup());
            startEvent.put("master", master);
            EventWebhookUtil.emit(EventTypeEnum.DOWNLOAD_START, ani, startEvent);

            download(ani, item, savePath, saveTorrent);
            RssTask.countRoundLocalState(LocalState.ABSENT);

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
        // 走 syncStateOnly：只回写运行时状态，不该顺带清空下载路径索引与本地状态快照——
        // 那是"订阅增删改"才需要的动作，而这里每下完一集都会触发。
        //
        // F6-3：每轮每订阅最多落盘一次。原先"进度回写"与"自动停用"各写一次，
        // 下完最后一集的那一轮会连写两次 ani.v2.json（整表序列化 + fsync）。
        // 改为打标记、方法末尾统一 flush，语义不变而写盘次数收敛为 1。
        boolean stateDirty = false;
        if (sync || omitChanged) {
            if (sync) {
                int size = ItemsUtil.currentEpisodeNumber(ani, items);
                ani.setCurrentEpisodeNumber(size);
                ani.setLastDownloadTime(System.currentTimeMillis());
            }
            stateDirty = true;
        }

        if (autoDisabled) {
            Integer totalEpisodeNumber = ani.getTotalEpisodeNumber();
            if (totalEpisodeNumber != null && totalEpisodeNumber >= 1
                    && currentDownloadCount >= totalEpisodeNumber) {
                log.info("{} 第 {} 季 共 {} 集 已全部下载完成, 自动停止订阅", title, season, totalEpisodeNumber);
                NotificationUtil.send(config, ani, StrFormatter.format("{} 订阅已完结", title), NotificationStatusEnum.COMPLETED);
                ani.setEnable(false);
                stateDirty = true;
            }
        }

        if (stateDirty) {
            AniUtil.syncStateOnly();
        }
    }

    /**
     * 查找占用该集的「备用RSS占位」任务。
     * 判定: 同一下载目录 + 带「备用RSS」标签 + 任务名等于 reName 或含相同 SxxExx。
     * 仅在 洗版(delete) + 备用RSS + 未开启多字幕组共存 时才视为可替换占位;
     * OpenList 等离线工具无任务列表恒返回 empty(其洗版在离线提交路径内处理)。
     */
    Optional<TorrentsInfo> findStandbyPlaceholderTorrent(Ani ani, Item item, List<TorrentsInfo> torrentsInfos) {
        Config config = ConfigUtil.CONFIG;
        if (!Boolean.TRUE.equals(config.getDelete())
                || !Boolean.TRUE.equals(config.getStandbyRss())
                || Boolean.TRUE.equals(config.getCoexist())) {
            return Optional.empty();
        }
        if (!Boolean.TRUE.equals(item.getMaster())) {
            return Optional.empty();
        }
        String reName = item.getReName();
        if (StrUtil.isBlank(reName) || !ReUtil.contains(StringEnum.SEASON_REG, reName)) {
            return Optional.empty();
        }
        String episode = ReUtil.get(StringEnum.SEASON_REG, reName, 0);
        String downloadPath = getDownloadPath(ani);
        return torrentsInfos.stream()
                .filter(Objects::nonNull)
                .filter(t -> StrUtil.isNotBlank(t.getDownloadDir()) && t.getDownloadDir().equals(downloadPath))
                .filter(t -> t.getTags() != null
                        && t.getTags().contains(TorrentsTags.BACK_RSS.getValue()))
                .filter(t -> {
                    String name = t.getName();
                    if (StrUtil.isBlank(name)) {
                        return false;
                    }
                    if (name.equalsIgnoreCase(reName)) {
                        return true;
                    }
                    if (!ReUtil.contains(StringEnum.SEASON_REG, name)) {
                        return false;
                    }
                    return ReUtil.get(StringEnum.SEASON_REG, name, 0).equalsIgnoreCase(episode);
                })
                .findFirst();
    }

    /**
     * 检测是否存在「可清除的备用RSS占位」——<b>只检测, 不删除</b>。
     * <p>
     * 与 {@link #removeStandbyPlaceholderTorrent} 拆开是因为删除时机很关键：
     * 调用方在"判定为占位"后还要经过新种子等待/同时下载数/离线进行中/失败队列等闸门,
     * 任何一条 continue 都不该把占位文件删掉(否则该集在等待窗口内既无占位文件也无主RSS文件)。
     *
     * @return 可清除的占位任务; {@code null} 表示不存在或尚不可清除
     */
    TorrentsInfo findRemovableStandbyPlaceholder(Ani ani, Item item,
                                                 List<TorrentsInfo> torrentsInfos) {
        TorrentsInfo standbyTorrent = findStandbyPlaceholderTorrent(ani, item, torrentsInfos).orElse(null);
        if (standbyTorrent == null) {
            return null;
        }
        List<String> tags = standbyTorrent.getTags();
        if (tags == null || !tags.contains(TorrentsTags.RENAME.getValue())) {
            // 备用任务未完成重命名(可能仍在下载/尚未就绪), 等待下轮
            log.debug("备用RSS占位未完成重命名, 等待下轮 {}", standbyTorrent.getName());
            return null;
        }
        return standbyTorrent;
    }

    /**
     * 实际清除「备用RSS占位」: 删除备用任务与其文件, 让主RSS版本替换(洗版)。
     * <p>
     * 修复: 备用RSS先下载某集后, 主RSS出种会被 itemDownloaded 的"本地文件已存在/
     * 已存在下载任务"判定(按集数/重命名匹配, 不区分来源)永久拦截,
     * 而负责替换的 deleteStandbyRss 在该判定之后才执行, 替换永远无法发生。
     *
     * @param standbyTorrent {@link #findRemovableStandbyPlaceholder} 的结果
     * @return 是否成功清除
     */
    boolean removeStandbyPlaceholderTorrent(Ani ani, Item item,
                                            List<TorrentsInfo> torrentsInfos,
                                            Set<String> localEpisodeIndex,
                                            TorrentsInfo standbyTorrent) {
        if (standbyTorrent == null) {
            return false;
        }
        // 非强制删除: 保持"任务已完成才允许删"的安全语义; 删除文件以实现替换
        if (!TorrentUtil.delete(standbyTorrent, false, true)) {
            log.debug("备用RSS占位删除失败, 等待下轮 {}", standbyTorrent.getName());
            return false;
        }
        torrentsInfos.remove(standbyTorrent);
        stripLocalEpisodeIndex(localEpisodeIndex, ani, item);
        log.info("主RSS更新, 已删除备用RSS占位任务与文件, 由主RSS替换 {}", item.getReName());
        return true;
    }

    /**
     * 检测并立即清除「备用RSS占位」。
     *
     * @return 是否成功清除; false 时调用方保持原"本地文件已存在"跳过行为
     */
    boolean removeStandbyPlaceholder(Ani ani, Item item,
                                     List<TorrentsInfo> torrentsInfos,
                                     Set<String> localEpisodeIndex) {
        return removeStandbyPlaceholderTorrent(ani, item, torrentsInfos, localEpisodeIndex,
                findRemovableStandbyPlaceholder(ani, item, torrentsInfos));
    }

    /**
     * 从本地集数索引移除该 item 对应的键(占位文件删除后防止本轮 stale 索引继续误判)。
     * 键的构造与 buildLocalEpisodeIndex/addFileToIndex 及 itemDownloaded 的查询保持一致。
     */
    private static void stripLocalEpisodeIndex(Set<String> localEpisodeIndex, Ani ani, Item item) {
        if (localEpisodeIndex == null) {
            return;
        }
        String reName = item.getReName();
        boolean ovaLegacy = Boolean.TRUE.equals(ani.getOva()) && !RenameUtil.isNamingV2(ani);
        boolean movieStyle = RenameUtil.isMovie(ani) || ovaLegacy;
        if (movieStyle) {
            if (StrUtil.isNotBlank(reName)) {
                localEpisodeIndex.remove("M:" + reName.trim().toUpperCase());
            }
            return;
        }
        Double episode = item.getEpisode();
        if (episode == null || StrUtil.isBlank(reName)) {
            return;
        }
        int querySeason = ani.getSeason();
        Matcher sm = Pattern.compile(StringEnum.SEASON_REG).matcher(reName.trim());
        if (sm.find()) {
            try {
                querySeason = Integer.parseInt(sm.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        localEpisodeIndex.remove(querySeason + ":" + episode);
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
                // message 已包含番剧名（OfflineTimeoutException 构造时拼接），不再重复
                log.error("离线超时失败: {}", message);
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
            // 失败同样进历史，保证"时间线"完整（失败侧同时保留在失败队列供精确重下）
            String humanized = TaskFailureHumanizer.humanize(rawMessage).title();
            DownloadHistory.record(ani == null ? null : ani.getId(),
                    ani == null ? null : ani.getTitle(),
                    item == null ? null : item.getReName(),
                    hash,
                    item == null ? null : item.getEpisode(),
                    null,
                    ConfigUtil.CONFIG.getDownloadToolType(),
                    ani == null ? null : ani.getSubgroup(),
                    DownloadHistory.Result.FAILED,
                    humanized);
            // 结构化事件（对外 Webhook）
            Map<String, Object> eventData = new LinkedHashMap<>();
            eventData.put("reName", item == null ? null : item.getReName());
            eventData.put("infoHash", hash);
            eventData.put("reason", humanized);
            eventData.put("rawMessage", rawMessage);
            EventWebhookUtil.emit(EventTypeEnum.DOWNLOAD_FAILED, ani, eventData);
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

        // F6-1：与 downloadAni 抢同一把订阅写锁，避免"重下"和"整订刷新"并发改同一份状态
        return AniLocks.callWithWrite(ani, () -> {
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
        });
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
        // F6-1：强制下载要删文件 + 重建种子记录，属结构性变更，必须独占该订阅
        return AniLocks.callWithWrite(ani, () -> {
            // 1. 清种子记录(正式 + pending)
            File marker = TorrentUtil.getTorrent(ani, item);
            if (marker != null && marker.exists()) {
                FileUtil.del(marker);
            }
            TorrentUtil.deletePendingTorrent(ani, item);

            // 2. 删除已有文件: 离线网盘用 API 删文件, 本地直接删文件
            String downloadPath = getDownloadPath(ani);
            if (TorrentUtil.DOWNLOAD instanceof OfflineDownloader offline) {
                // 预检归位：子目录/云下载目录已有本集文件时直接归位并恢复记录，
                // 免去「重提交被 115 去重挡住 → 等满离线超时 → 终检救回」的漫长自愈路径。
                // 注意：顶层已有文件（用户确实想重新下载）不在此短路，走正常强下流程。
                try {
                    if (offline.relocateEpisodeFiles(ani, item, downloadPath)
                            == OfflineDownloader.RelocateResult.RELOCATED) {
                        TorrentUtil.saveTorrent(ani, item);
                        NotificationUtil.send(ConfigUtil.CONFIG, ani,
                                StrFormatter.format("{} 已归位（发现已有文件，无需重新下载）", item.getReName()),
                                NotificationStatusEnum.DOWNLOAD_END);
                        return "已归位（发现已有文件）：" + item.getReName();
                    }
                } catch (Exception e) {
                    log.debug("强制下载预检归位失败 {}: {}", item.getReName(), ExceptionUtils.getMessage(e));
                }
                offline.forceDeleteFiles(downloadPath, item.getReName());
            } else {
                deleteLocalFilesByReName(downloadPath, item.getReName());
            }
            // 刚删掉了文件：目录内容已变，快照立即失效（F2-6）
            LocalStateCache.invalidateByDownloadPath(downloadPath);

            // 3. 走正常下载流程(提交/离线等待/重命名等)
            File saved = isOpenListTool()
                    ? TorrentUtil.saveTorrentPending(ani, item)
                    : TorrentUtil.saveTorrent(ani, item);
            if (saved == null || !saved.exists()) {
                throw new IllegalStateException(item.getReName() + " 种子下载失败，无法强制下载");
            }
            download(ani, item, downloadPath, saved);
            return "已强制下载：" + item.getReName();
        });
    }

    /**
     * 归位对账失败/未找到 → 同步到失败队列（任务管理器可见），便于用户感知与手动处理。
     */
    private void recordRelocateFailure(Ani ani, Item item, Exception cause) {
        try {
            FailedDownloadQueue.record(
                    ani.getId(), ani.getTitle(), item.getReName(), item.getInfoHash(),
                    "归位对账失败 " + item.getReName() + ": "
                            + ExceptionUtils.getMessage(cause));
        } catch (Exception e) {
            log.debug("记录归位对账失败到失败队列异常: {}", e.getMessage());
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
        boolean standby = tags.contains(TorrentsTags.BACK_RSS.getValue());
        if (standby) {
            text = StrFormatter.format("(备用RSS) {}", text);
        }
        NotificationUtil.send(ConfigUtil.CONFIG, ani, text, NotificationStatusEnum.DOWNLOAD_END);

        // 下载历史（成功侧）：失败侧已由 FailedDownloadQueue 记录，这里补齐成功记录
        try {
            DownloadHistory.record(ani.getId(), ani.getTitle(), name, torrentsInfo.getHash(),
                    episodeOf(name),
                    torrentsInfo.getSize(),
                    ConfigUtil.CONFIG.getDownloadToolType(),
                    subgroup,
                    standby ? DownloadHistory.Result.WASH : DownloadHistory.Result.SUCCESS,
                    "下载完成");
            // 结构化事件（对外 Webhook）
            Map<String, Object> eventData = new LinkedHashMap<>();
            eventData.put("reName", name);
            eventData.put("infoHash", torrentsInfo.getHash());
            eventData.put("episode", episodeOf(name));
            eventData.put("size", torrentsInfo.getSize());
            eventData.put("source", ConfigUtil.CONFIG.getDownloadToolType());
            eventData.put("subgroup", subgroup);
            eventData.put("standby", standby);
            EventWebhookUtil.emit(EventTypeEnum.DOWNLOAD_END, ani, eventData);
        } catch (Exception e) {
            log.debug("记录下载历史失败: {}", e.getMessage());
        }

        String title = ani.getTitle();

        try {
            AniUtil.completed(ani);
        } catch (Exception e) {
            log.error("番剧完结迁移失败 {}", title);
            log.error(e.getMessage(), e);
        }
    }

    /**
     * 从种子名中提取集数（提取不到返回 null，不抛异常）。
     * 与播放页取集数同一套正则，保证历史记录里的集数与界面上看到的一致。
     */
    private static Double episodeOf(String name) {
        if (StrUtil.isBlank(name)) {
            return null;
        }
        try {
            if (!ReUtil.contains(StringEnum.SEASON_REG, name)) {
                return null;
            }
            String episode = ReUtil.get(StringEnum.SEASON_REG, name, 2);
            return StrUtil.isBlank(episode) ? null : Double.parseDouble(episode);
        } catch (Exception e) {
            return null;
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
        // (E11) 标题为空/纯符号时 getPinyin 可能返回空串, substring(0,1) 会越界, 回落 "#"
        String letter = pinyin.isEmpty() ? "#" : pinyin.substring(0, 1).toUpperCase();
        if (ReUtil.isMatch("^\\d$", letter)) {
            letter = "0";
        } else if (!ReUtil.isMatch("^[a-zA-Z]$", letter)) {
            letter = "#";
        }

        downloadPathTemplate = downloadPathTemplate.replace("${letter}", letter);

        Date releaseDate = ani.getReleaseDate();
        Tmdb tmdb = ani.getTmdb();

        Date tmdbDate = Optional.ofNullable(tmdb)
                .map(Tmdb::getDate)
                .orElse(releaseDate);

        int tmdbYear = DateUtil.year(tmdbDate);
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

        downloadPathTemplate = downloadPathTemplate.replace("${tmdbYear}", String.valueOf(tmdbYear));
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
        return buildEpisodeIndexResult(ani, downloadPath, false).index();
    }

    /**
     * 严格版集数索引：网盘列举失败时抛出，而非静默返回空集。
     * <p>
     * 供预览区分"目录确实为空"（→ 确实不存在）与"查询失败"（→ 只能标"存疑"）。
     * 非严格版会把网盘抖动当成"目录无文件"，用于下载去重是安全的（保守重下），
     * 用于展示则会谎报"本地不存在"，故展示场景必须走本方法。
     */
    public Set<String> buildEpisodeIndexStrict(Ani ani, String downloadPath) {
        return buildEpisodeIndexResult(ani, downloadPath, true).index();
    }

    /**
     * 集数索引 + 完整性标记。
     * <p>
     * 为什么需要 {@code complete}：网盘目录文件数超过 {@code cloudListMaxFiles} 时会截断。
     * 截断后的索引仍然<b>能确认"存在"</b>（找到了就是找到了），但<b>不能断言"不存在"</b>
     * （要找的那一集可能正好在被截掉的部分里）。把这两种能力区分开，
     * 才能既守住性能上限、又不谎报"本地没有"。
     */
    public static final class EpisodeIndex {
        private final Set<String> index;
        private final boolean complete;

        private EpisodeIndex(Set<String> index, boolean complete) {
            this.index = index == null ? Set.of() : index;
            this.complete = complete;
        }

        public Set<String> index() {
            return index;
        }

        public boolean complete() {
            return complete;
        }
    }

    private EpisodeIndex buildEpisodeIndexResult(Ani ani, String downloadPath, boolean strict) {
        Set<String> index = new HashSet<>();
        boolean complete = true;
        boolean ovaLegacy = Boolean.TRUE.equals(ani.getOva()) && !RenameUtil.isNamingV2(ani);
        // 剧场版(电影式)/旧版 OVA: 文件名不含 SxxExx, 按文件名主名索引
        boolean movieStyle = RenameUtil.isMovie(ani) || ovaLegacy;

        if (TorrentUtil.DOWNLOAD instanceof OfflineDownloader offline) {
            // 网盘虚拟路径, 本地文件系统不可见, 用离线网盘 API 列出文件。
            // 只认视频文件: 空目录/临时目录(如「标题 SxxExx」文件夹壳)/字幕或其它杂文件
            // 不能证明本集已下载, 否则种子太新长时间无视频时会误判已存在而永远不重下
            List<String> names = strict
                    ? offline.listFileNamesStrict(downloadPath)
                    : offline.listFileNames(downloadPath);
            int maxFiles = resolveCloudListMaxFiles();
            if (names.size() > maxFiles) {
                log.warn("网盘目录文件数 {} 超过上限 {}，已截断；本次仅能确认「存在」，不能断言「不存在」: {}",
                        names.size(), maxFiles, downloadPath);
                names = names.subList(0, maxFiles);
                complete = false;
            }
            for (String name : names) {
                String extName = FileUtil.extName(name);
                if (StrUtil.isBlank(extName) || !FileUtils.isVideoFormat(extName)) {
                    continue;
                }
                addFileToIndex(index, name, movieStyle);
            }
            return new EpisodeIndex(index, complete);
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
        return new EpisodeIndex(index, complete);
    }

    /**
     * 网盘列举文件数上限（默认 5000，范围 100–50000）
     */
    static int resolveCloudListMaxFiles() {
        Integer configured = ConfigUtil.CONFIG == null ? null : ConfigUtil.CONFIG.getCloudListMaxFiles();
        int value = configured == null ? DEFAULT_CLOUD_LIST_MAX_FILES : configured;
        return Math.max(100, Math.min(value, 50_000));
    }

    static final int DEFAULT_CLOUD_LIST_MAX_FILES = 5000;

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

        String reName = item.getReName();

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
            // F2-7：索引改走订阅级快照缓存，与预览/媒体库/手动搜索共用同一份结果。
            // OpenList 模式下本方法会为"没有种子记录"的条目逐个调用，不共享缓存时
            // 同一订阅在一轮里会被重复列举 N 次。
            // 注意（F5-1）：判定逻辑本身<b>不改</b>——查询失败时仍按旧的非严格版语义
            // 返回"未下载"（保守重下），绝不因为"查不到"就跳过下载。
            try {
                localEpisodeIndex = cachedEpisodeIndex(ani, downloadPath).index();
            } catch (Exception e) {
                log.warn("构建集数索引失败，按未下载处理（保守重下） {}: {}",
                        reName, ExceptionUtils.getMessage(e));
                localEpisodeIndex = Set.of();
            }
        }

        boolean exists = matchesEpisodeIndex(ani, item, localEpisodeIndex);

        if (exists) {
            // 保存 torrent 下次只校验 torrent 是否存在，可以把config设置到固态硬盘，防止一直硬盘机机械硬盘
            TorrentUtil.saveTorrent(ani, item);
            log.info("本地已存在 {}", reName);
            return true;
        }
        return false;
    }

    /**
     * 预览用：目标路径下是否<b>确实存在</b>这一集的视频文件（本地磁盘 / OpenList 网盘）。
     * <p>
     * 与 {@link #itemDownloaded} 的区别：不受「重命名」「文件存在时不下载」两个<b>策略开关</b>影响。
     * 那两个开关回答的是"要不要跳过下载"，而预览的「本地存在」列表达的是"文件到底在不在"这一事实，
     * 不该被策略开关改写。
     * <p>
     * 前提：调用方需确认已开启重命名——未开启时文件名不含 SxxExx，按集数匹配无从谈起。
     *
     * @param episodeIndex 预构建的集数索引（{@link #buildEpisodeIndexStrict}），避免逐条重建；
     *                     传 null 时按需构建（非严格版）
     */
    public boolean itemFileExists(Ani ani, Item item, Set<String> episodeIndex) {
        if (ani == null || item == null) {
            return false;
        }
        Set<String> index = episodeIndex == null
                ? buildLocalEpisodeIndex(ani, getDownloadPath(ani))
                : episodeIndex;
        return matchesEpisodeIndex(ani, item, index);
    }

    /**
     * 文件确实存在但种子记录缺失时补回记录（预览的既有行为）。
     * 记录缺失会让 RSS 主流程把这集当成未下载而重下，故展示时顺手补回；失败只告警，不影响展示。
     */
    public void restoreTorrentRecord(Ani ani, Item item) {
        if (ani == null || item == null) {
            return;
        }
        try {
            TorrentUtil.saveTorrent(ani, item);
            // 记录补回不改变目录内容，但"记录存在"会影响下一次判定，保守失效
            LocalStateCache.invalidate(ani);
        } catch (Exception e) {
            log.warn("补回种子记录失败 {}: {}", item.getReName(), ExceptionUtils.getMessage(e));
        }
    }

    /**
     * 「本地存在」三态。
     * <p>
     * 与 {@link #itemDownloaded} 的布尔语义分开：那个回答的是"要不要跳过下载"（受
     * {@code rename} / {@code fileExist} 两个策略开关影响），本枚举回答的是"文件到底在不在"。
     */
    public enum LocalState {
        /**
         * 目标路径下确实有这一集的视频文件（本地磁盘 / OpenList 网盘）
         */
        EXISTS,
        /**
         * 无法确认，本地有种子记录但不敢断言文件在。两个成因：
         * <ul>
         *   <li>网盘列举失败（查询失败 != 目录为空）；</li>
         *   <li>已提交下载但文件尚未改名落地（下载器里有同名任务，文件名还不含 SxxExx）。</li>
         * </ul>
         * 注意：未开启重命名时<b>不产生</b> UNKNOWN —— 那种情况下直接按种子记录判定。
         */
        UNKNOWN,
        /**
         * 确认没有
         */
        ABSENT;

        /**
         * 是否算作「本地已有」（<b>含存疑</b>）。
         * <p>
         * 存疑归入"已有"：无法校验时不能断言用户没有，否则「只看未下载」会把一批
         * 很可能已存在的条目推出来，用户一不留神就重复下单（强制下载会先删已有文件）。
         * 前端 {@code hasDownloaded || hasDownloadedUnknown} 是本策略的 JS 版本。
         */
        public boolean present() {
            return this != ABSENT;
        }
    }

    /**
     * 「无法确认」的成因。
     * <p>
     * 分开记不是为了好看：三种成因的对策完全不同——列举失败要等网盘恢复、
     * 超预算要调大预算或减少订阅、索引不完整要调大 {@code cloudListMaxFiles}。
     * 混成一句"存疑"，用户只能猜。
     */
    public enum UnknownReason {
        /**
         * 不是存疑（判定出了确定结果）
         */
        NONE,
        /**
         * 网盘列举失败 / 熔断冷却中（查询失败 ≠ 目录为空）
         */
        VERIFY_FAILED,
        /**
         * 本轮网盘 API 预算已耗尽，主动放弃校验
         */
        BUDGET_EXHAUSTED,
        /**
         * 索引被截断，无法断言"不存在"
         */
        INDEX_INCOMPLETE,
        /**
         * 下载器里已有同名任务，文件尚未改名落地
         */
        DOWNLOADING
    }

    /**
     * 「本地存在」判定上下文：一次构建、整批复用。
     * <p>
     * 存在的意义是<b>把索引构建从"每条 item 一次"降到"每订阅一次"</b>——旧写法逐条调用
     * {@code itemDownloaded(ani, item, false)}，内部 {@code localEpisodeIndex == null}
     * 会为每条 item 重建索引，本地模式是 N 次目录遍历，网盘模式下就是 N 次 API 调用
     * （300 条结果 × 300ms 限流 ≈ 90 秒）。
     * <p>
     * 预览与手动搜索共用本上下文，避免两处口径再次漂移。
     */
    public static final class LocalStateContext {
        /**
         * 是否具备真实文件校验能力（需开启重命名：文件名含 SxxExx 才能按季/集匹配）
         */
        private final boolean canVerify;
        /**
         * 校验过程本身失败（网盘列举异常）——与"目录确实为空"必须区分开
         */
        private final boolean verifyFailed;
        private final Set<String> episodeIndex;
        /**
         * 本订阅的下载目录（真实文件校验用）
         */
        private final String downloadPath;
        /**
         * 下载器中已存在的任务键（{@code downloadDir|任务名小写}）。
         * <p>
         * 用于识别"<b>已提交下载但文件尚未改名落地</b>"的窗口：这段时间里文件名还不含
         * {@code SxxExx}，真实文件校验必然失败。若不额外判断就会被判成"否"，
         * 用户在预览里会以为没在下而点强制下载——而强制下载会先删掉正在下的文件。
         */
        private final Set<String> activeTaskKeys;
        /**
         * 索引是否完整。false（列举被截断 / 校验失败 / 超预算）时只能确认"存在"，
         * <b>不得</b>据此断言"不存在"。
         */
        private final boolean indexComplete;
        /**
         * 本上下文为何无法给出确定结论（供展示与诊断）
         */
        private final UnknownReason reason;

        private LocalStateContext(boolean canVerify, boolean verifyFailed, Set<String> episodeIndex,
                                 String downloadPath, Set<String> activeTaskKeys,
                                 boolean indexComplete, UnknownReason reason) {
            this.canVerify = canVerify;
            this.verifyFailed = verifyFailed;
            this.episodeIndex = episodeIndex;
            this.downloadPath = downloadPath;
            this.activeTaskKeys = activeTaskKeys;
            this.indexComplete = indexComplete;
            this.reason = reason == null ? UnknownReason.NONE : reason;
        }

        /**
         * 无法做真实文件校验（未开启重命名）——<b>不是</b>校验失败，按记录判定且不产生存疑
         */
        static LocalStateContext unverifiable() {
            return new LocalStateContext(false, false, null, null, Set.of(),
                    false, UnknownReason.NONE);
        }

        /**
         * 校验不可靠（列举失败 / 超预算 / 索引不完整）
         */
        static LocalStateContext unreliable(String downloadPath, Set<String> activeTaskKeys,
                                           UnknownReason reason) {
            return new LocalStateContext(true, true, null, downloadPath, activeTaskKeys,
                    false, reason);
        }

        static LocalStateContext verified(Set<String> episodeIndex, String downloadPath,
                                          Set<String> activeTaskKeys, boolean indexComplete) {
            return new LocalStateContext(true, false, episodeIndex, downloadPath, activeTaskKeys,
                    indexComplete,
                    indexComplete ? UnknownReason.NONE : UnknownReason.INDEX_INCOMPLETE);
        }

        public boolean canVerify() {
            return canVerify;
        }

        public boolean verifyFailed() {
            return verifyFailed;
        }

        public Set<String> episodeIndex() {
            return episodeIndex;
        }

        public String downloadPath() {
            return downloadPath;
        }

        public Set<String> activeTaskKeys() {
            return activeTaskKeys;
        }

        public boolean indexComplete() {
            return indexComplete;
        }

        public UnknownReason reason() {
            return reason;
        }
    }

    /**
     * 下载器中已有任务的任务键集合（{@code downloadDir|任务名小写}）。
     * <p>
     * 离线网盘（OpenList）不适用：它的在途任务用 {@code .pending} 标记，{@code getTorrentsInfos()} 返回空。
     * 本地下载器的任务列表有 5 秒缓存，故整批只调用一次的成本可忽略。
     */
    private static Set<String> buildActiveTaskKeys() {
        Set<String> keys = new HashSet<>();
        try {
            for (TorrentsInfo ti : TorrentUtil.getTorrentsInfos()) {
                if (ti == null || StrUtil.isBlank(ti.getName())) {
                    continue;
                }
                keys.add(taskKey(ti.getDownloadDir(), ti.getName()));
            }
        } catch (Exception e) {
            // 下载器未登录/未配置等：拿不到任务列表就退化成"没有在途任务"，不阻断展示
            log.debug("读取下载器任务列表失败: {}", ExceptionUtils.getMessage(e));
        }
        return keys;
    }

    private static String taskKey(String downloadDir, String name) {
        return (downloadDir == null ? "" : downloadDir) + "|" + (name == null ? "" : name.toLowerCase());
    }

    /**
     * 构建「本地存在」判定上下文（每订阅只建一次集数索引）。
     * <p>
     * 三种情形：
     * <ul>
     *   <li>未开启重命名 → 文件名不可预测，按集数匹配无从谈起，退回种子记录判定（即旧逻辑）</li>
     *   <li>开启重命名但列举失败 / 超预算 → 回退记录判定，但结果只能标"存疑"</li>
     *   <li>开启重命名且列举成功 → 可按真实文件判定</li>
     * </ul>
     * <p>
     * F2：索引构建走 {@link LocalStateCache}（订阅级快照 + 单飞）。预览、媒体库、
     * RSS 主流程、手动搜索因此共用同一份结果，同一轮内同一订阅的网盘列举次数 ≤ 1。
     */
    public LocalStateContext prepareLocalState(Ani ani) {
        if (ani == null) {
            return LocalStateContext.unverifiable();
        }
        if (!Boolean.TRUE.equals(ConfigUtil.CONFIG.getRename())) {
            return LocalStateContext.unverifiable();
        }
        String downloadPath = getDownloadPath(ani);
        Set<String> activeTaskKeys = buildActiveTaskKeys();
        boolean cloud = TorrentUtil.DOWNLOAD instanceof OfflineDownloader;

        // F7-5：本轮预算已耗尽 → 停止 Phase B，本订阅一律"存疑"（不发任何请求）。
        // 这是主动放弃，不是查询失败：继续打只会更快撞上限流，代价比"显示存疑"大得多。
        // 预算口径是"目录列举次数"（不是所有 API 调用），所以这里必须打印列举计数——
        // 用 apiCallCountRound 会普遍大于预算，日志读起来自相矛盾。
        if (cloud && OpenListApi.isRoundBudgetExhausted()) {
            OpenListApi.markBudgetExhausted();
            log.warn("本轮网盘列举预算已耗尽（{}/{}），停止真实文件校验，剩余条目保持「存疑」: {}",
                    OpenListApi.getListingCallCountRound(), OpenListApi.getRoundBudget(), ani.getTitle());
            return LocalStateContext.unreliable(downloadPath, activeTaskKeys,
                    UnknownReason.BUDGET_EXHAUSTED);
        }

        try {
            EpisodeIndex built = cachedEpisodeIndex(ani, downloadPath);
            return LocalStateContext.verified(built.index(), downloadPath,
                    activeTaskKeys, built.complete());
        } catch (Exception e) {
            // 网盘列举失败：与"目录确实为空"区分开，本轮回退记录判定并标"存疑"
            log.warn("构建集数索引失败，回退种子记录判定 {}: {}",
                    ani.getTitle(), ExceptionUtils.getMessage(e));
            return LocalStateContext.unreliable(downloadPath, activeTaskKeys,
                    UnknownReason.VERIFY_FAILED);
        }
    }

    /**
     * 走订阅级快照缓存构建集数索引（F2）。
     * <p>
     * 严格版列举：失败<b>抛出</b>而不是返回空集。缓存只存成功结果，
     * 一次网盘抖动不会被固化成"目录里什么都没有"。
     */
    private EpisodeIndex cachedEpisodeIndex(Ani ani, String downloadPath) throws Exception {
        boolean cloud = TorrentUtil.DOWNLOAD instanceof OfflineDownloader;
        LocalStateCache.Snapshot snapshot = LocalStateCache.getOrBuild(ani, downloadPath,
                cloud ? LocalStateCache.Source.CLOUD_API : LocalStateCache.Source.LOCAL_DISK,
                () -> {
                    EpisodeIndex built = buildEpisodeIndexResult(ani, downloadPath, true);
                    return LocalStateCache.Loaded.of(built.index(), built.complete());
                });
        return new EpisodeIndex(snapshot.episodeIndex(), snapshot.complete());
    }

    /**
     * 判定单条 item 的「本地存在」状态，不产生任何写动作。
     *
     * @see #applyLocalStates(Ani, List)
     */
    public LocalState resolveLocalState(Ani ani, Item item, LocalStateContext ctx) {
        if (ani == null || item == null) {
            return LocalState.ABSENT;
        }
        LocalStateContext context = ctx == null ? prepareLocalState(ani) : ctx;
        if (!context.canVerify()) {
            // 未开启重命名：文件名不含 SxxExx，无法按集数核对真实文件 → 沿用旧逻辑
            return TorrentUtil.getTorrent(ani, item).exists() ? LocalState.EXISTS : LocalState.ABSENT;
        }
        if (context.verifyFailed()) {
            // 查询失败 ≠ 目录为空：有记录则标"存疑"，不谎报"是"也不降级成"否"
            return TorrentUtil.getTorrent(ani, item).exists() ? LocalState.UNKNOWN : LocalState.ABSENT;
        }
        if (itemFileExists(ani, item, context.episodeIndex())) {
            return LocalState.EXISTS;
        }
        // 索引被截断：找到了才算"存在"，没找到不能算"不存在"（要找的那集可能在被截掉的部分里）
        if (!context.indexComplete()) {
            return LocalState.UNKNOWN;
        }
        // 真实文件里没有这一集，但下载器里已有同名任务 → 只是"已提交、还没改名落地"。
        // 这段窗口内文件名不含 SxxExx，文件校验必然失败；判「否」会让用户以为没在下
        // 而点强制下载，而强制下载会先删掉正在下的文件。故只能标"存疑"。
        if (context.activeTaskKeys().contains(taskKey(context.downloadPath(), item.getReName()))) {
            return LocalState.UNKNOWN;
        }
        return LocalState.ABSENT;
    }

    /**
     * 单条 item 的「存疑」成因（F5-6 可观测）。确定结果返回 {@link UnknownReason#NONE}。
     */
    public UnknownReason resolveUnknownReason(Ani ani, Item item, LocalStateContext ctx) {
        if (ani == null || item == null || ctx == null) {
            return UnknownReason.NONE;
        }
        if (!ctx.canVerify() || ctx.verifyFailed()) {
            return ctx.reason();
        }
        if (itemFileExists(ani, item, ctx.episodeIndex())) {
            return UnknownReason.NONE;
        }
        if (!ctx.indexComplete()) {
            return UnknownReason.INDEX_INCOMPLETE;
        }
        if (ctx.activeTaskKeys().contains(taskKey(ctx.downloadPath(), item.getReName()))) {
            return UnknownReason.DOWNLOADING;
        }
        return UnknownReason.NONE;
    }

    /**
     * 解析整批 item 的「本地存在」状态并写回展示字段（预览 / 手动搜索共用）。
     * <p>
     * 写回：{@code hasDownloaded} / {@code hasDownloadedUnknown} / {@code hasTorrentRecord} /
     * {@code downloading} / {@code downloadingState}。
     * <p>
     * 副作用：文件确实存在但种子记录缺失时补回记录（与旧预览行为一致，避免 RSS 主流程重下）。
     *
     * @return 与 items 一一对应的状态列表，供调用方统计
     */
    public List<LocalState> applyLocalStates(Ani ani, List<? extends Item> items) {
        List<LocalState> states = new ArrayList<>();
        if (ani == null || items == null || items.isEmpty()) {
            return states;
        }
        LocalStateContext ctx = prepareLocalState(ani);
        for (Item item : items) {
            if (item == null) {
                states.add(LocalState.ABSENT);
                continue;
            }
            item.setHasDownloaded(false);
            item.setHasDownloadedUnknown(false);
            item.setDownloading(false);
            item.setDownloadingState(null);
            boolean recordExists = TorrentUtil.getTorrent(ani, item).exists();
            // 「删除种子」按钮要的是"有没有缓存可删"，与"文件在不在"是两件事
            item.setHasTorrentRecord(recordExists);

            LocalState state = resolveLocalState(ani, item, ctx);
            states.add(state);

            if (state == LocalState.EXISTS) {
                item.setHasDownloaded(true);
                if (!recordExists) {
                    restoreTorrentRecord(ani, item);
                }
                continue;
            }
            if (state == LocalState.UNKNOWN) {
                item.setHasDownloadedUnknown(true);
                // 记下"为什么存疑"：三种成因的对策完全不同，混成一句用户只能猜
                RssTask.countRoundUnknownReason(resolveUnknownReason(ani, item, ctx));
            }
            // 未能确认"确实存在"的条目都可能已提交离线任务但尚未落地，
            // 需标"下载中"：否则同一时刻任务管理器显示"离线处理中 45%"、
            // 列表却显示"本地存在：否"，用户会误判为没在下而重复点强制下载
            markPending(ani, item);
        }
        return states;
    }

    /**
     * 统计"有种子记录、但目标路径已确认没有对应文件"的条目数（F5-2）。
     * <p>
     * 这类条目是<b>记录与文件不一致</b>：用户曾经下过、后来把文件删了（或迁移走了）。
     * 处理方式是<b>只提示、不删除</b>——种子记录是"这集曾经下过"的唯一线索，
     * 自动清掉会让用户彻底失去判断依据；界面给出"可清理"提示，把决定权留给用户。
     * <p>
     * 正在下载中（有 pending 标记）的条目不算不一致：那只是"还没落地"。
     */
    public static int countStaleTorrentRecords(List<? extends Item> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Item item : items) {
            if (item == null) {
                continue;
            }
            if (Boolean.TRUE.equals(item.getHasTorrentRecord())
                    && !Boolean.TRUE.equals(item.getHasDownloaded())
                    && !Boolean.TRUE.equals(item.getHasDownloadedUnknown())
                    && !Boolean.TRUE.equals(item.getDownloading())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 已提交离线任务但尚未落地 → 标记"下载中"。
     * 只读本地 {@code .pending} 标记，不触发网盘 API。
     */
    private void markPending(Ani ani, Item item) {
        try {
            File pending = TorrentUtil.getPendingTorrent(ani, item);
            if (pending != null && pending.exists()) {
                item.setDownloading(true);
                item.setDownloadingState("已提交离线任务，等待完成");
            }
        } catch (Exception e) {
            log.debug("检查 pending 记录失败: {}", e.getMessage());
        }
    }

    /**
     * 集数索引匹配：索引里是否有这一集。
     * 剧场版/旧版 OVA 按 M:主名 匹配，OVA 特典式按 0:集数，普通番剧按 season:episode。
     */
    private boolean matchesEpisodeIndex(Ani ani, Item item, Set<String> localEpisodeIndex) {
        Integer season = ani.getSeason();
        Boolean ova = ani.getOva();
        String reName = item.getReName();
        Double episode = item.getEpisode();

        boolean ovaLegacy = Boolean.TRUE.equals(ova) && !RenameUtil.isNamingV2(ani);
        // 剧场版(电影式)/旧版 OVA: 按文件名主名匹配(M: 前缀)
        boolean movieStyle = RenameUtil.isMovie(ani) || ovaLegacy;
        // OVA 特典式(v2): 落盘为 S00Exx(season=0), 用 0 参与匹配
        boolean ovaSpecial = Boolean.TRUE.equals(ova) && RenameUtil.isNamingV2(ani) && !RenameUtil.isMovie(ani);
        if (movieStyle) {
            boolean exists = StrUtil.isNotBlank(reName)
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
            return exists;
        }
        if (ovaSpecial) {
            return episode != null && localEpisodeIndex.contains("0:" + episode);
        }
        if (episode == null) {
            return false;
        }
        // 季号优先取 reName 中的 Sxx(编号特典落 S00, 与正片集数不碰撞);
        // reName 无 SxxExx 时退回订阅季号(旧行为)
        int querySeason = season == null ? 1 : season;
        if (StrUtil.isNotBlank(reName)) {
            Matcher sm = Pattern.compile(StringEnum.SEASON_REG).matcher(reName.trim());
            if (sm.find()) {
                try {
                    querySeason = Integer.parseInt(sm.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return localEpisodeIndex.contains(querySeason + ":" + episode);
    }

    /**
     * 下载路径 → 订阅 反向索引。
     * getDownloadPath 含 Pinyin/季度/占位符等重计算，避免 RenameTask 每轮对每个任务全量重算。
     * 订阅或配置变更（AniUtil.sync / ConfigUtil.sync）时置空失效，miss 时自动重建自愈。
     */
    private static volatile Map<String, Ani> DOWNLOAD_PATH_INDEX;
    private static volatile long DOWNLOAD_PATH_INDEX_BUILT_AT = 0L;

    /**
     * miss 重建的最小间隔：下载器中存在非本程序任务时，RenameTask 每轮对每个 miss 任务
     * 全量重建索引（O(任务×订阅) 重算风暴）。间隔内的 miss 直接返回空，等下一周期再重建。
     */
    private static final long DOWNLOAD_PATH_INDEX_MIN_REBUILD_MS = 60_000L;

    private static final Object INDEX_LOCK = new Object();

    public static void invalidateDownloadPathIndex() {
        DOWNLOAD_PATH_INDEX = null;
        DOWNLOAD_PATH_INDEX_BUILT_AT = 0L;
        // 订阅增删 / 下载路径模板变更会同时影响所有订阅的快照：整体失效
        LocalStateCache.invalidateAll();
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
        DOWNLOAD_PATH_INDEX_BUILT_AT = System.currentTimeMillis();
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
        if (ani == null
                && System.currentTimeMillis() - DOWNLOAD_PATH_INDEX_BUILT_AT >= DOWNLOAD_PATH_INDEX_MIN_REBUILD_MS) {
            // 缓存可能过期（BGM/JP 标题等外部数据变化）：重建后重查一次，避免误判未下载；
            // 负缓存限频：间隔内不重建，防止下载器中无关任务触发 O(任务×订阅) 重算风暴
            synchronized (INDEX_LOCK) {
                index = buildDownloadPathIndex();
                DOWNLOAD_PATH_INDEX = index;
            }
            ani = index.get(downloadDir);
        }

        return ani == null ? Optional.empty() : Optional.of(ObjectUtil.clone(ani));
    }

}
