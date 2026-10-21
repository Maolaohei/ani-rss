package ani.rss.service;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.PinyinUtils;
import ani.rss.download.OfflineDownloader;
import ani.rss.download.OpenList;
import ani.rss.download.OpenListApi;
import ani.rss.download.openlist.runtime.OpenListDownloadPathResolver;
import ani.rss.entity.*;
import ani.rss.enums.EventTypeEnum;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.StringEnum;
import ani.rss.enums.TorrentsTags;
import ani.rss.task.RssTask;
import ani.rss.task.RssDownloadPort;
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
public class DownloadService implements OpenListDownloadPathResolver, RssDownloadPort {
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
     * <p>
     * 编排只做三件事：准备本轮上下文 → 逐条目过闸门并提交 → 收尾落盘。
     * 具体判定见 {@link #passGates}，提交与清理见 {@link #submitItem}。
     */
    private void downloadAniLocked(Ani ani) {
        Config config = ConfigUtil.CONFIG;
        DownloadRun run = prepareDownloadRun(ani);
        for (Item item : run.items) {
            if (RssTask.isCancelRequested()) {
                log.warn("{} 检测到任务取消，停止本订阅后续下载", run.title);
                return;
            }
            // false = 订阅已被移除, 整轮中止; true = 跳过/完成本条目继续下一个
            if (!downloadItem(ani, item, run, config)) {
                return;
            }
        }
        flushRunState(ani, config, run);
    }

    /**
     * 处理单个条目：先过闸门，通过后再提交。
     *
     * @return false 表示整轮下载需要中止（订阅已被移除）
     */
    private boolean downloadItem(Ani ani, Item item, DownloadRun run, Config config) {
        log.debug(JSONUtil.formatJsonStr(GsonStatic.toJson(item)));
        ItemPlan plan = new ItemPlan();
        if (!passGates(ani, item, run, config, plan)) {
            return true;
        }
        return submitItem(ani, item, run, config, plan);
    }

    /**
     * 逐层闸门判定：任一闸门拦下，本条目本轮跳过（返回 false）。
     * <p>
     * 判定顺序是语义的一部分：备用RSS占位的删除必须推迟到主RSS提交成功之后，
     * 这里只登记待删任务（{@link ItemPlan#pendingStandbyPlaceholder}），绝不提前删除。
     *
     * @return true 表示通过全部闸门，可以进入提交
     */
    private boolean passGates(Ani ani, Item item, DownloadRun run, Config config, ItemPlan plan) {
        prepareGateState(ani, item, run, plan);
        if (versionGate(ani, item, run, plan).stops()) {
            return false;
        }
        if (existingRecordGate(ani, item, run, config, plan).stops()) {
            return false;
        }
        if (episodeDuplicateGate(run, plan).stops()) {
            return false;
        }
        if (rangeDuplicateGate(item, run, plan).stops()) {
            return false;
        }
        if (notDownloadGate(run, plan).stops()) {
            return false;
        }
        if (latestOnlyGate(item, run, plan).stops()) {
            return false;
        }
        if (delayGate(run, plan).stops()) {
            return false;
        }
        if (newTorrentWaitGate(run, config, plan).stops()) {
            return false;
        }
        if (standbyCleanupGate(ani, item, run, plan).stops()) {
            return false;
        }
        if (existingTaskGate(run, plan).stops()) {
            return false;
        }
        if (pushedHashGate(run, plan).stops()) {
            return false;
        }
        if (localPresentGate(ani, item, run, plan).stops()) {
            return false;
        }
        if (concurrencyGate(run).stops()) {
            return false;
        }
        return true;
    }

    private enum GateResult {
        CONTINUE,
        SKIP_ITEM,
        ABORT_ITEM;

        private boolean stops() {
            return this != CONTINUE;
        }
    }

    private void prepareGateState(Ani ani, Item item, DownloadRun run, ItemPlan plan) {
        plan.reName = item.getReName();
        plan.torrent = TorrentUtil.getTorrent(ani, item);
        plan.master = item.getMaster();
        plan.hash = FileUtil.mainName(plan.torrent)
                .trim().toLowerCase();

        plan.episode = item.getEpisode();
        // .5 集
        plan.is5 = ItemsUtil.is5(plan.episode);

        // 「备用RSS占位」待清除任务：只在这里登记, 真正的删除推迟到确认要下载之前。
        // 若在上面任一闸门(新种子等待/同时下载数/离线进行中/失败队列)就 continue,
        // 占位文件会被白删 —— 该集在等待窗口内既无占位文件也没有主RSS文件。
        plan.pendingStandbyPlaceholder = null;
    }

    /**
     * 洗版判定只计算状态并记录日志，不拦截条目。
     */
    private GateResult versionGate(Ani ani, Item item, DownloadRun run, ItemPlan plan) {
        // 高版本标记：RSS 标题里带 v2+。这是"标题声称的版本"，不是"盘上那份的版本"。
        plan.higherVersion = run.v2 && item.getVersion() != null && item.getVersion() > 1;
        // 洗版 = 本版<b>尚未落地</b>（没有自己的正式种子记录）且版本<b>高于</b>已落地版本（见 needsWash）。
        // 原先只看 `version > 1`：那是无状态的，而重命名后的文件名不含版本号 ——
        // 盘上那份是不是 v2 无处可知，于是同一 v2 每轮都被判成"待洗版"重新下载，
        // 哪怕它自己的种子记录就摆在那里（离线正式记录只在产物落地后才提升）。
        plan.wash = needsWash(ani, item, plan.torrent.exists());
        if (plan.wash) {
            log.info("检测到高版本 {} v{}, 准备洗版（本地已落地 v{}）",
                    plan.reName, item.getVersion(), WashState.versionOf(ani, item));
        }
        return GateResult.CONTINUE;
    }

    /**
     * 本地种子记录与备用RSS占位判定。
     */
    private GateResult existingRecordGate(
            Ani ani, Item item, DownloadRun run, Config config, ItemPlan plan) {
        ExistingRecordResult existing = handleExistingRecord(
                ani, item, config, plan.torrent, plan.reName,
                run.savePath, run.localEpisodeIndex, run.torrentsInfos);
        if (existing.countsAsExisting()) {
            if (plan.master && !plan.is5) {
                run.currentDownloadCount++;
            }
            if (run.v2 && run.downloadedEpisodes != null) {
                run.downloadedEpisodes.add(plan.episode);
            }
        }
        if (existing.stopsCurrentItem()) {
            return GateResult.SKIP_ITEM;
        }
        if (existing == ExistingRecordResult.STANDBY) {
            plan.pendingStandbyPlaceholder = findRemovableStandbyPlaceholder(ani, item, run.torrentsInfos);
        }
        return GateResult.CONTINUE;
    }

    private GateResult episodeDuplicateGate(DownloadRun run, ItemPlan plan) {
        // v2: episode 级去重，同集数已被其他种子覆盖则跳过
        if (run.v2 && run.downloadedEpisodes != null && run.downloadedEpisodes.contains(plan.episode)) {
            log.debug("集数已被覆盖 {} ep{}", plan.reName, plan.episode);
            if (plan.master && !plan.is5) {
                run.currentDownloadCount++;
            }
            return GateResult.SKIP_ITEM;
        }
        return GateResult.CONTINUE;
    }

    private GateResult rangeDuplicateGate(Item item, DownloadRun run, ItemPlan plan) {
        // v2: 合集范围去重，范围内所有集数均已覆盖则跳过
        if (run.v2 && item.getEpisodeRange() != null && !item.getEpisodeRange().isEmpty()) {
            boolean allCovered = item.getEpisodeRange().stream().allMatch(run.downloadedEpisodes::contains);
            if (allCovered) {
                log.debug("合集范围已全部覆盖 {} {}", plan.reName, item.getEpisodeRange());
                if (plan.master && !plan.is5) {
                    run.currentDownloadCount++;
                }
                return GateResult.SKIP_ITEM;
            }
        }
        return GateResult.CONTINUE;
    }

    private GateResult notDownloadGate(DownloadRun run, ItemPlan plan) {
        if (run.notDownload.contains(plan.episode)) {
            if (plan.master && !plan.is5) {
                run.currentDownloadCount++;
            }
            log.debug("已被禁止下载: {}", plan.reName);
            return GateResult.SKIP_ITEM;
        }
        return GateResult.CONTINUE;
    }

    private GateResult latestOnlyGate(Item item, DownloadRun run, ItemPlan plan) {
        // 只下载最新集
        if (Boolean.TRUE.equals(run.downloadNew)) {
            Item newItem = run.items.get(run.items.size() - 1);

            // 日期一致也可下载, 防止字幕组同时发多集
            Date pubDate = item.getPubDate();
            Date newPubDate = newItem.getPubDate();
            if (Objects.nonNull(pubDate) && Objects.nonNull(newPubDate)) {
                String pubDateFormat = DateUtil.format(pubDate, "yyyy-MM-dd");
                String newPubDateFormat = DateUtil.format(newPubDate, "yyyy-MM-dd");
                // 日期不一致则跳过
                if (!pubDateFormat.equals(newPubDateFormat)) {
                    if (plan.master && !plan.is5) {
                        run.currentDownloadCount++;
                    }
                    return GateResult.SKIP_ITEM;
                }
            } else if (item != newItem) {
                if (plan.master && !plan.is5) {
                    run.currentDownloadCount++;
                }
                return GateResult.SKIP_ITEM;
            }
        }
        plan.pubDate = item.getPubDate();
        return GateResult.CONTINUE;
    }

    private GateResult delayGate(DownloadRun run, ItemPlan plan) {
        if (Objects.nonNull(plan.pubDate) && run.delayedDownload > 0) {
            Date now = DateUtil.offset(new Date(), DateField.MINUTE, -run.delayedDownload);
            if (now.getTime() < plan.pubDate.getTime()) {
                log.info("延迟下载 {}", plan.reName);
                return GateResult.SKIP_ITEM;
            }
        }
        return GateResult.CONTINUE;
    }

    private GateResult newTorrentWaitGate(DownloadRun run, Config config, ItemPlan plan) {
        // 新种子下载等待：发布时间距今不足 N 小时的种子暂缓下载（离线场景云端无人做种，
        // 立即提交只会超时失败并反复重提）。到期由下一轮 RSS 自然重查，无需新定时器。
        // pubDate 为 null 放行（部分 RSS 源无该字段，不能永远卡住）；
        // 高版本（v2+）条目不拦截——修正版通常来自已有种子的重新发布。
        // 用 higherVersion 而不是 wash：本版记录已存在的高版本条目会在这里被拦下，
        // 而它下一轮本来也不会重下（记录终态），拦住只是白等 2 小时。
        Integer newTorrentWaitHours = ObjectUtil.defaultIfNull(config.getNewTorrentWaitHours(), 2);
        if (Objects.nonNull(plan.pubDate) && newTorrentWaitHours > 0 && !plan.higherVersion) {
            long earliest = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(newTorrentWaitHours);
            if (plan.pubDate.getTime() > earliest) {
                // 站点时钟偏移导致 pubDate 在未来：按已过期处理，避免长期卡住
                if (plan.pubDate.getTime() <= System.currentTimeMillis()) {
                    long waitMinutes = Math.max(1,
                            (plan.pubDate.getTime() - earliest) / TimeUnit.MINUTES.toMillis(1));
                    log.info("新种子等待: {} 发布不足 {} 小时，约 {} 分钟后到期待重试",
                            plan.reName, newTorrentWaitHours, waitMinutes);
                    return GateResult.SKIP_ITEM;
                }
            }
        }
        return GateResult.CONTINUE;
    }

    /**
     * 仅在主RSS更新后删除备用RSS。
     * <p>
     * 本轮只做两件可回退的事：登记待删任务 + 剥掉本地集数索引，真正的删除推迟到
     * 主RSS提交成功之后。主提交失败时备用任务与文件原样保留。
     */
    private GateResult standbyCleanupGate(Ani ani, Item item, DownloadRun run, ItemPlan plan) {
        plan.legacyStandbyRss = null;
        if (run.delete && plan.master && run.deleteStandbyRSSOnly) {
            TorrentsInfo standbyRSS = run.torrentsInfos
                    .stream()
                    .filter(torrentsInfo -> {
                        if (!torrentsInfo.getDownloadDir().equals(run.savePath)) {
                            return false;
                        }
                        if (!ReUtil.contains(StringEnum.SEASON_REG, torrentsInfo.getName())) {
                            return false;
                        }
                        String s = ReUtil.get(StringEnum.SEASON_REG, torrentsInfo.getName(), 0);
                        String ep = ReUtil.get(StringEnum.SEASON_REG, plan.reName, 0);
                        if (s == null || ep == null || !s.equalsIgnoreCase(ep)) {
                            return false;
                        }
                        List<String> tags = torrentsInfo.getTags();
                        // 包含 备用RSS 标签或者 无主RSS字幕组信息
                        return tags.contains(TorrentsTags.BACK_RSS.getValue()) ||
                                !tags.contains(ani.getSubgroup());
                    })
                    // 不能命中刚提交的主RSS版本（同集数、同目录，靠名字无法区分）
                    .filter(torrentsInfo -> !isSameHash(torrentsInfo, plan.hash))
                    .findFirst()
                    .orElse(null);
            if (Objects.nonNull(standbyRSS)) {
                List<String> tags = standbyRSS.getTags();
                if (!tags.contains(TorrentsTags.RENAME.getValue())) {
                    // 未完成重命名
                    return GateResult.SKIP_ITEM;
                }
                // 只登记不删除：删除推迟到主RSS提交成功之后。
                // 索引仍在本轮剥掉：否则 itemDownloaded 会因备用文件存在拦下主RSS。
                plan.legacyStandbyRss = standbyRSS;
                stripLocalEpisodeIndex(run.localEpisodeIndex, ani, item);
            }
        }
        return GateResult.CONTINUE;
    }

    private GateResult existingTaskGate(DownloadRun run, ItemPlan plan) {
        // 已经下载过
        if (run.torrentsInfos
                .stream()
                .anyMatch(torrentsInfo ->
                        // hash 相同（忽略大小写：下载器回的 hash 大小写不保证，本地 hash 恒小写）
                        isSameHash(torrentsInfo, plan.hash))) {
            log.info("已有下载任务 hash:{} name:{}", plan.hash, plan.reName);
            RssTask.countRoundLocalState(LocalState.EXISTS);
            if (plan.master && !plan.is5) {
                run.currentDownloadCount++;
            }
            return GateResult.SKIP_ITEM;
        }
        return GateResult.CONTINUE;
    }

    private GateResult pushedHashGate(DownloadRun run, ItemPlan plan) {
        // infoHash 去重：合集展开后多个 clone 共享同一 infoHash，第一个通过后后续 clone 被跳过
        if (!run.pushedHashes.add(plan.hash)) {
            log.debug("infoHash 已推送过，跳过 {} {}", plan.hash, plan.reName);
            if (plan.master && !plan.is5) {
                run.currentDownloadCount++;
            }
            return GateResult.SKIP_ITEM;
        }
        return GateResult.CONTINUE;
    }

    private GateResult localPresentGate(Ani ani, Item item, DownloadRun run, ItemPlan plan) {
        // 未开启rename不进行检测; 洗版(高版本)不受本地已下载判断拦截
        if (!plan.wash && itemDownloaded(ani, item, true, run.localEpisodeIndex)) {
            // 主RSS更新时该集被"备用RSS占位": 登记待清除, 由主RSS替换(洗版)。
            // 同样只登记不删除, 见 pendingStandbyPlaceholder 声明处。
            TorrentsInfo standbyPlaceholder = findRemovableStandbyPlaceholder(ani, item, run.torrentsInfos);
            if (standbyPlaceholder != null) {
                plan.pendingStandbyPlaceholder = standbyPlaceholder;
                log.info("备用RSS占位待清除, 由主RSS替换下载 {}", plan.reName);
            } else {
                log.info("本地文件已存在 {}", plan.reName);
                RssTask.countRoundLocalState(LocalState.EXISTS);
                if (plan.master && !plan.is5) {
                    run.currentDownloadCount++;
                }
                return GateResult.SKIP_ITEM;
            }
        }
        return GateResult.CONTINUE;
    }

    private GateResult concurrencyGate(DownloadRun run) {
        // 同时下载数量限制
        if (run.downloadCount > 0) {
            if (run.activeDownloadCount >= run.downloadCount) {
                log.debug("达到同时下载数量限制 {}", run.downloadCount);
                return GateResult.SKIP_ITEM;
            }
        }
        return GateResult.CONTINUE;
    }

    /**
     * 提交下载；成功后清理备用RSS占位与旧任务，并更新本轮计数。
     *
     * @return false 表示整轮中止（订阅已被移除）
     */
    private boolean submitItem(Ani ani, Item item, DownloadRun run, Config config, ItemPlan plan) {
        // OpenList 提交 != 完成：先写待完成标记，离线完成后才提升为正式种子记录
        // 其余下载方式保持原行为：提交即写正式记录（预览视为已下载）
        boolean openListTool = isOpenListTool();
        // OpenList: pending 标记存在表示离线进行中, 本轮跳过, 避免重复提交与通知轰炸
        if (openListTool && TorrentUtil.getPendingTorrent(ani, item).exists()) {
            if (!TorrentUtil.isPendingExpired(ani, item)) {
                log.debug("离线任务进行中, 跳过本轮 {}", plan.reName);
                RssTask.countRoundLocalState(LocalState.UNKNOWN);
                return true;
            }
            // §6.3 在途有效期：超过 alistDownloadTimeout × 2（默认 60 分钟）后不再"只等"，
            // 放行进入归位/判定链路。
            // 注意「过期 ≠ 重下」：下面的提交会被 OpenList.adoptOrCleanResidualTasks 复用远端
            // 进行中的任务（10008 也兜底），这里只是把"重做归位 + 1 次单层确认"这条恢复路径打开，
            // 让存疑条目有机会自动收敛为成功。没有这一步，"存疑保留 pending"会让该集永远卡住。
            log.info("离线 pending 已超过在途有效期（{} 分钟），放行重做归位与确认 {}",
                    TorrentUtil.pendingTtlMs(ConfigUtil.CONFIG) / 60_000L, plan.reName);
        }
        // (E9) 失败队列闸门: 仅非 OpenList 路径(qB/TR/Aria2)。近 24h 内失败过的本集
        // 不再每轮自动重推, 避免坏种/下载器故障时无限重试风暴; 条目保留在失败队列,
        // 由用户手动重试。OpenList 路径不加此闸门(其内部已有 10008/adopt 去重)。
        if (!openListTool) {
            String failKey = FailedDownloadQueue.keyOf(ani.getId(), item.getInfoHash(), plan.reName);
            boolean recentFailed = FailedDownloadQueue.list().stream()
                    .anyMatch(f -> Objects.equals(f.getId(), failKey)
                            && f.getFailedAt() != null
                            && System.currentTimeMillis() - f.getFailedAt() < TimeUnit.HOURS.toMillis(24));
            if (recentFailed) {
                log.warn("本集 24h 内下载失败(已保留在失败队列), 跳过本轮推送 {} hash={}", plan.reName, plan.hash);
                RssTask.countRoundLocalState(LocalState.UNKNOWN);
                return true;
            }
        }
        File saveTorrent = openListTool
                ? TorrentUtil.saveTorrentPending(ani, item)
                : TorrentUtil.saveTorrent(ani, item);

        if (!saveTorrent.exists()) {
            // 种子下载失败
            return true;
        }

        // 洗版清扫必须按"提交前目录里本来有什么"来清：主/备用同一模板重命名后可能完全同名，
        // 提交之后就再也分不出"刚下好的主版本"与"待清理的备版本"，只能按快照清理。
        Set<String> preExistingFiles = needsStandbySweep(item)
                ? snapshotExistingFiles(run.savePath)
                : null;

        // 占位清除推迟到主RSS提交成功之后: 上面任一闸门 continue / 提交失败都不删占位, 主失败时备用文件得以保留。
        if (!AniUtil.getAniList().contains(ani)) {
            return false;
        }

        // 结构化事件: 开始下载(与 DOWNLOAD_END 成对, 便于外部程序对账)
        Map<String, Object> startEvent = new LinkedHashMap<>();
        startEvent.put("reName", plan.reName);
        startEvent.put("infoHash", plan.hash);
        startEvent.put("episode", plan.episode);
        startEvent.put("size", item.getLength());
        startEvent.put("source", config.getDownloadToolType());
        startEvent.put("subgroup", item.getSubgroup());
        startEvent.put("master", plan.master);
        EventWebhookUtil.emit(EventTypeEnum.DOWNLOAD_START, ani, startEvent);

        boolean downloadAccepted = download(ani, item, run.savePath, saveTorrent);
        if (!downloadAccepted) {
            // 提交/离线失败：本轮不算下载成功——不占并发配额、不记已下载集数、
            // 不推进 lastDownloadTime、也绝不清理备用。失败信息与 DOWNLOAD_FAILED
            // 事件已由 download() 内部落库，这里只把本地状态记成"存疑"。
            log.warn("下载未提交成功, 本轮保留备用RSS与种子记录, 不做洗版清理 {}", plan.reName);
            RssTask.countRoundLocalState(LocalState.UNKNOWN);
            return true;
        }

        // 主RSS已提交成功, 此时清理备用才是安全的; 清理异常不得中断本轮剩余条目
        try {
            if (plan.pendingStandbyPlaceholder != null) {
                removeStandbyPlaceholderTorrent(ani, item, run.torrentsInfos, run.localEpisodeIndex, plan.pendingStandbyPlaceholder);
            }
        } catch (Exception e) {
            log.warn("清除备用RSS占位失败(不影响主RSS下载) {}: {}", plan.reName, ExceptionUtils.getMessage(e));
        }
        try {
            // 同一任务可能已被上面的占位清除删掉：避免重复下发删除（第二次必然失败并刷日志）
            if (plan.legacyStandbyRss != null && plan.legacyStandbyRss != plan.pendingStandbyPlaceholder
                    && TorrentUtil.delete(plan.legacyStandbyRss, false, true)) {
                run.torrentsInfos.remove(plan.legacyStandbyRss);
            }
        } catch (Exception e) {
            log.warn("删除备用RSS任务失败(不影响主RSS下载) {}: {}", plan.reName, ExceptionUtils.getMessage(e));
        }
        try {
            deleteStandbyRss(ani, item, plan.hash, preExistingFiles);
        } catch (Exception e) {
            log.warn("备用RSS洗版清理失败(不影响主RSS下载) {}: {}", plan.reName, ExceptionUtils.getMessage(e));
        }

        run.sync = true;

        RssTask.countRoundLocalState(LocalState.ABSENT);

        if (plan.master && !plan.is5) {
            run.currentDownloadCount++;
        }
        // v2: 记录已下载集数（合集记录所有范围内的集数）
        if (run.v2 && run.downloadedEpisodes != null) {
            if (item.getEpisodeRange() != null && !item.getEpisodeRange().isEmpty()) {
                run.downloadedEpisodes.addAll(item.getEpisodeRange());
            } else {
                run.downloadedEpisodes.add(plan.episode);
            }
        }
        run.activeDownloadCount++;
        return true;
    }

    /**
     * 一轮结束后的收尾：有新下载或漏集变化才落盘（避免每轮无意义写 ani.v2.json），
     * 自动停用判定同样在这里收敛。
     */
    private void flushRunState(Ani ani, Config config, DownloadRun run) {
        // 有新下载，或漏集数量变化时落盘（避免每轮无意义写 ani.v2.json）
        // 走 syncStateOnly：只回写运行时状态，不该顺带清空下载路径索引与本地状态快照——
        // 那是"订阅增删改"才需要的动作，而这里每下完一集都会触发。
        //
        // F6-3：每轮每订阅最多落盘一次。原先"进度回写"与"自动停用"各写一次，
        // 下完最后一集的那一轮会连写两次 ani.v2.json（整表序列化 + fsync）。
        // 改为打标记、方法末尾统一 flush，语义不变而写盘次数收敛为 1。
        boolean stateDirty = false;
        if (run.sync || run.omitChanged) {
            if (run.sync) {
                int size = ItemsUtil.currentEpisodeNumber(ani, run.items);
                ani.setCurrentEpisodeNumber(size);
                ani.setLastDownloadTime(System.currentTimeMillis());
            }
            stateDirty = true;
        }

        // 摸鱼检测放在集数回写之后：此时 currentEpisodeNumber 才是本轮最新值，
        // 已完结（当前集数 >= 总集数）的订阅不再提醒摸鱼。
        ItemsUtil.procrastinating(ani, run.items);

        if (run.autoDisabled) {
            Integer totalEpisodeNumber = ani.getTotalEpisodeNumber();
            if (totalEpisodeNumber != null && totalEpisodeNumber >= 1
                    && run.currentDownloadCount >= totalEpisodeNumber) {
                log.info("{} 第 {} 季 共 {} 集 已全部下载完成, 自动停止订阅", run.title, run.season, totalEpisodeNumber);
                NotificationUtil.send(config, ani, StrFormatter.format("{} 订阅已完结", run.title), NotificationStatusEnum.COMPLETED);
                ani.setEnable(false);
                stateDirty = true;
            }
        }

        if (stateDirty) {
            AniUtil.syncStateOnly();
        }
    }

    /**
     * 单个条目的下载计划：闸门阶段写入，提交阶段复用。
     */
    private static final class ItemPlan {
        private String reName;
        private File torrent;
        private String hash;
        private Double episode;
        private boolean is5;
        private Boolean master;
        private Date pubDate;
        private boolean higherVersion;
        private boolean wash;
        private TorrentsInfo pendingStandbyPlaceholder;
        private TorrentsInfo legacyStandbyRss;
    }

    /**
     * 准备一轮订阅下载所需的配置与运行上下文。
     */
    private DownloadRun prepareDownloadRun(Ani ani) {
        DownloadRun run = new DownloadRun();
        Config config = ConfigUtil.CONFIG;
        run.delete = config.getDelete();
        run.autoDisabled = config.getAutoDisabled();
        run.downloadCount = config.getDownloadCount();
        run.delayedDownload = config.getDelayedDownload();
        run.deleteStandbyRSSOnly = config.getDeleteStandbyRSSOnly();
        run.title = ani.getTitle();
        run.season = ani.getSeason();
        run.downloadNew = ani.getDownloadNew();
        run.notDownload = ani.getNotDownload();
        run.torrentsInfos = TorrentUtil.getTorrentsInfos();
        run.items = ItemsUtil.getItems(ani);

        List<Integer> omitGaps = ItemsUtil.omitList(ani, run.items);
        int omitN = omitGaps == null ? 0 : omitGaps.size();
        Integer prevOmit = ani.getOmitCount();
        run.omitChanged = prevOmit == null || prevOmit != omitN;
        if (omitN > 0 && run.omitChanged) {
            Map<String, Object> omitEvent = new LinkedHashMap<>();
            omitEvent.put("omitCount", omitN);
            omitEvent.put("omitList", omitGaps);
            EventWebhookUtil.emit(EventTypeEnum.OMIT_DETECTED, ani, omitEvent);
        }
        SubscriptionHealth.rememberOmit(ani, omitN, System.currentTimeMillis());
        ItemsUtil.omit(ani, run.items);
        log.debug("{} 共 {} 个", run.title, run.items.size());

        run.activeDownloadCount = run.torrentsInfos.stream()
                .filter(it -> {
                    TorrentsInfo.State state = it.getState();
                    if (Objects.isNull(state)) {
                        return true;
                    }
                    return !List.of(
                            TorrentsInfo.State.queuedUP.name(),
                            TorrentsInfo.State.uploading.name(),
                            TorrentsInfo.State.stalledUP.name(),
                            TorrentsInfo.State.pausedUP.name(),
                            TorrentsInfo.State.stoppedUP.name()
                    ).contains(state.name());
                })
                .count();
        run.savePath = getDownloadPath(ani);
        run.sync = false;
        run.v2 = RenameUtil.isNamingV2(ani);
        run.downloadedEpisodes = run.v2 ? new HashSet<>() : null;
        run.pushedHashes = new HashSet<>();
        run.localEpisodeIndex = isOpenListTool()
                ? null
                : buildLocalEpisodeIndex(ani, run.savePath);
        return run;
    }

    enum ExistingRecordResult {
        CONTINUE,
        EXISTS,
        SKIP,
        STANDBY;

        /**
         * 是否应计入本轮「已存在」统计。
         * <p>
         * SKIP 包含 UNVERIFIABLE / RETRY_EXHAUSTED，不能当作已下载计数；
         * STANDBY 仍要继续走下载流程。
         */
        boolean countsAsExisting() {
            return this == EXISTS;
        }

        boolean stopsCurrentItem() {
            return this == EXISTS || this == SKIP;
        }
    }

    /**
     * 校验已有种子记录。
     * <p>
     * 三态语义保持不变：只有明确 ABSENT 才允许进入重下流程；OpenList 的
     * UNVERIFIABLE 一律保留记录。
     */
    private ExistingRecordResult handleExistingRecord(
            Ani ani, Item item, Config config, File torrent, String reName,
            String savePath, Set<String> localEpisodeIndex, List<TorrentsInfo> torrentsInfos) {
        if (!torrent.exists()) {
            return ExistingRecordResult.CONTINUE;
        }
        Presence presence;
        UnknownReason unknownReason = UnknownReason.NONE;
        if (!Boolean.TRUE.equals(config.getRename())) {
            presence = Presence.VALID;
        } else if (isOpenListTool() && TorrentUtil.DOWNLOAD instanceof OfflineDownloader offline) {
            PresenceDecision decision = resolveOpenListPresence(
                    ani, item, reName, savePath, offline, localEpisodeIndex);
            presence = decision.presence();
            unknownReason = decision.reason();
        } else {
            presence = itemDownloaded(ani, item, true, localEpisodeIndex)
                    ? Presence.VALID : Presence.ABSENT;
        }
        if (presence == Presence.UNVERIFIABLE) {
            log.info("本地状态无法确认（{}），本轮保留记录不重下 {}{}",
                    unknownReasonText(unknownReason), reName, cooldownRemainText(unknownReason));
            RssTask.countRoundLocalState(LocalState.UNKNOWN);
            RssTask.countRoundUnknownReason(unknownReason);
            return ExistingRecordResult.SKIP;
        }
        if (presence == Presence.RETRY_EXHAUSTED) {
            log.warn("本集连续下载失败已达 {} 次，停止自动重推（条目保留在失败队列，可手动重试） {}",
                    MAX_OPENLIST_AUTO_RETRY_ATTEMPTS, reName);
            RssTask.countRoundLocalState(LocalState.UNKNOWN);
            return ExistingRecordResult.SKIP;
        }
        if (!presence.valid()) {
            if (isOpenListTool()) {
                log.warn("种子记录存在但未确认网盘产物，按 v3 保留记录（不删、不重下），"
                        + "等下一轮复核 {}", reName);
                RssTask.countRoundLocalState(LocalState.UNKNOWN);
                return ExistingRecordResult.SKIP;
            }
            log.warn("清理过期种子记录(无对应任务/文件) {}", reName);
            FileUtil.del(torrent);
            return ExistingRecordResult.CONTINUE;
        }
        TorrentsInfo standbyPlaceholder = findRemovableStandbyPlaceholder(ani, item, torrentsInfos);
        if (standbyPlaceholder != null) {
            return ExistingRecordResult.STANDBY;
        }
        log.debug("种子记录已存在 {}", reName);
        backfillWashState(ani, item);
        return ExistingRecordResult.EXISTS;
    }

    private static final class DownloadRun {
        private Boolean delete;
        private Boolean autoDisabled;
        private Integer downloadCount;
        private Integer delayedDownload;
        private Boolean deleteStandbyRSSOnly;
        private String title;
        private Integer season;
        private Boolean downloadNew;
        private List<Double> notDownload;
        private List<TorrentsInfo> torrentsInfos;
        private List<Item> items;
        private boolean omitChanged;
        private long activeDownloadCount;
        private String savePath;
        private boolean sync;
        private boolean v2;
        private Set<Double> downloadedEpisodes;
        private Set<String> pushedHashes;
        private Set<String> localEpisodeIndex;
        private int currentDownloadCount;
    }

    /**
     * 是否需要洗版：本版<b>尚未落地</b>（没有自己的正式种子记录）、且版本<b>高于</b>已落地版本。
     * <p>
     * 判定分两层，缺一层就会无限洗版：
     * <ol>
     *   <li><b>记录终态（本地事实）</b>：本版自己的正式种子记录已存在 → 这一版已经下载过了，
     *       不是洗版目标。离线下载（OpenList）的正式记录只在离线完成、归位与重命名成功之后，
     *       才从 {@code .pending} 提升而来（{@code OpenList#promoteAfterConfirmed}）——
     *       它存在就是"产物已在盘上"的正证据。记录与文件确实不一致时，会走上面的存在性校验：
     *       把它当"记录失效"走<b>普通</b>重下，而不是每轮一次的洗版循环。</li>
     *   <li><b>版本状态（跨版本事实）</b>：{@code item.version} 必须严格高于
     *       {@link WashState} 里已落地的版本。升级前的存量数据没有 wash-state 记录，
     *       只看这一条会永远算 {@code 0}（{@code v2 > 0} 恒真）而每轮重下——
     *       这正是线上"V2 明明已下载却被一直当成旧版"的成因。</li>
     * </ol>
     *
     * @param recordExists 本版自己的正式种子记录是否存在（调用方传入，避免重复读缓存文件）
     */
    static boolean needsWash(Ani ani, Item item, boolean recordExists) {
        if (!RenameUtil.isNamingV2(ani) || item == null || recordExists) {
            return false;
        }
        Integer version = item.getVersion();
        if (version == null || version <= 1) {
            return false;
        }
        return version > WashState.versionOf(ani, item);
    }

    /**
     * 观察到「本版自己的正式种子记录有效」时，补记已落地版本。
     * <p>
     * 为什么必须补记：{@link WashState} 是修复后新增的文件，升级前已经下载完的
     * 存量数据一条记录都没有。不补记则"已落地版本"永远停在 0，条目一旦走到需要版本比较
     * 的路径（记录被清理、同版本重新发布换了 infoHash）就又会当成"从没洗过"。
     * <p>
     * 只在<b>自己确认过</b>的分支调用（走完归位对账/兜底校验，记录与产物都对上了），
     * 且只对 v2 命名生效（见 {@link WashState#markDownloaded}）。
     */
    private void backfillWashState(Ani ani, Item item) {
        try {
            if (WashState.markDownloaded(ani, item)) {
                log.debug("补记已落地版本 {} v{}", item.getReName(), item.getVersion());
            }
        } catch (Exception e) {
            log.debug("补记已落地版本失败 {}: {}", item.getReName(), ExceptionUtils.getMessage(e));
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

    /**
     * 仅在主RSS更新后：清理同集的备用RSS任务与文件（洗版替换的收尾）。
     * <p>
     * 相对旧实现的两处收紧，都是为了适配"先提交、成功才清理"的新时序：
     * <ol>
     *   <li>只删带「备用RSS」标签的任务，并用 {@code excludeHash} 排除刚提交成功的主RSS版本——
     *       主/备同集数、同目录，重命名后连文件名都可能一致，靠名字无法区分；</li>
     *   <li>文件只清 {@code preExistingFiles}（主RSS提交前拍下的目录快照）里的那些——
     *       提交之后新落地的文件属于主版本，绝不能按 SxxExx 一扫了之。
     *       传 {@code null} 表示不清理文件（宁可不删，也不能误删主版本）。</li>
     * </ol>
     *
     * @param excludeHash      刚提交成功的主RSS infoHash，可为空
     * @param preExistingFiles 主RSS提交前该下载目录的文件名快照，可为空（为空则不清理文件）
     */
    public void deleteStandbyRss(Ani ani, Item item, String excludeHash, Set<String> preExistingFiles) {
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

        // 「仅在主RSS更新后删除备用RSS」的前提是"正在下载的确实是主RSS版本"：
        // 备用条目下载时不得反过来清掉主RSS的任务与文件。
        if (!Boolean.TRUE.equals(item.getMaster())) {
            return;
        }

        if (!ReUtil.contains(StringEnum.SEASON_REG, reName)) {
            return;
        }

        // 只取 SxxExx 片段做洗版匹配；忽略大小写避免 s01e03 / S01E03 漏删
        String episode = ReUtil.get(StringEnum.SEASON_REG, reName, 0);

        String downloadPath = getDownloadPath(ani);

        List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();

        List<TorrentsInfo> standbyTorrents = torrentsInfos
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
                    // 刚提交成功的主RSS版本不能删：与备用同集数、同目录，靠名字无法区分
                    if (isSameHash(torrentsInfo, excludeHash)) {
                        return false;
                    }
                    // 本方法的语义是"删除备用RSS"：只认带备用标签的任务。
                    // 无标签的历史任务不再被顺手删掉——它很可能就是主RSS自己的任务。
                    List<String> tags = torrentsInfo.getTags();
                    if (tags == null || !tags.contains(TorrentsTags.BACK_RSS.getValue())) {
                        return false;
                    }
                    String s = ReUtil.get(StringEnum.SEASON_REG, name, 0);
                    return s != null && s.equalsIgnoreCase(episode);
                })
                .toList();
        if (standbyTorrents.size() > 1) {
            // 同集存在多条备用任务（历史残留）：全部删掉。只删第一条会让其余继续占位/占空间，
            // 而文件侧是"全清"，两边口径必须一致。
            log.warn("同集数存在 {} 条备用RSS任务，本次全部删除以避免残留占位 {} {}",
                    standbyTorrents.size(), episode, downloadPath);
        }
        standbyTorrents.forEach(standbyRSS -> TorrentUtil.delete(standbyRSS, true, true));

        if (preExistingFiles == null) {
            // 没有提交前快照就不碰文件：避免把刚落地的主RSS版本误删
            return;
        }
        sweepStandbyFiles(new File(downloadPath), "", episode, preExistingFiles, 0);
    }

    /**
     * 洗版递归深度上限（BDMV 等目录结构可能很深，防失控）
     */
    private static final int SWEEP_MAX_DEPTH = 8;

    /**
     * 递归清扫本集旧版本：只删「提交前快照里就有」且「名字里的 SxxExx 与本次整体相等」的条目。
     * <p>
     * 递归是必要的：旧版本可能落在子目录里，只扫一层会漏清。
     * 目录若自身就命中本集则整棵删除（与旧行为一致），否则递归进去继续找。
     *
     * @param prefix 当前目录相对下载根的前缀——快照键就是相对路径，避免子目录重名互相干扰
     */
    private void sweepStandbyFiles(File dir, String prefix, String episode,
                                   Set<String> preExistingFiles, int depth) {
        if (depth > SWEEP_MAX_DEPTH) {
            return;
        }
        for (File file : FileUtils.listFiles(dir)) {
            if (file == null || !file.exists()) {
                continue;
            }
            String relative = prefix.isEmpty() ? file.getName() : prefix + "/" + file.getName();
            // 只清提交前就已存在的条目（主版本可能是同名的新文件）
            if (!preExistingFiles.contains(relative)) {
                continue;
            }
            if (isWashableEntry(file, episode)) {
                log.info("已开启备用RSS, 自动删除 {}", FileUtils.getAbsolutePath(file));
                try {
                    FileUtil.del(file);
                    log.info("删除成功 {}", FileUtils.getAbsolutePath(file));
                } catch (Exception e) {
                    log.error("删除失败 {}", FileUtils.getAbsolutePath(file));
                    log.error(e.getMessage(), e);
                }
                // 目录整棵已删 / 文件已处理，都不需要再往下走
                continue;
            }
            if (file.isDirectory()) {
                sweepStandbyFiles(file, relative, episode, preExistingFiles, depth + 1);
            }
        }
    }

    /**
     * 该条目是否属于「本集的旧版本」：名字里的 SxxExx 必须与本次<b>整体相等</b>（忽略大小写）。
     * <p>
     * 不能退化成 {@code contains}：那会让 {@code S01E01} 命中 {@code S01E010} / {@code S01E01.5}，
     * 把别的集删掉。目录一律算（旧版本可能是目录结构）；文件只认
     * <b>视频 / 字幕 / nfo / bif / -thumb.jpg</b>。
     * <p>
     * 字幕必须一起删：旧版本的字幕是按旧片源的时间轴做的，留着会与新视频错配
     * （与 OpenList 侧同一口径）。
     */
    static boolean isWashableEntry(File file, String episode) {
        if (file == null || StrUtil.isBlank(episode)) {
            return false;
        }
        String mainName = FileUtil.mainName(file);
        if (StrUtil.isBlank(mainName) || !ReUtil.contains(StringEnum.SEASON_REG, mainName)) {
            return false;
        }
        String found = ReUtil.get(StringEnum.SEASON_REG, mainName, 0);
        if (found == null || !found.equalsIgnoreCase(episode)) {
            return false;
        }
        if (file.isDirectory()) {
            return true;
        }
        if (!file.isFile()) {
            return false;
        }
        String extName = FileUtil.extName(file);
        if (StrUtil.isBlank(extName)) {
            return false;
        }
        return FileUtils.isVideoFormat(extName)
                || FileUtils.isSubtitleFormat(extName)
                || List.of("nfo", "bif").contains(extName)
                || file.getName().endsWith("-thumb.jpg");
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

    /**
     * 是否同一 infoHash（忽略大小写）。
     * <p>
     * 本地 {@code hash} 来自 {@code FileUtil.mainName(torrent).toLowerCase()} 恒为小写，
     * 而下载器回的 {@code getHash()} 大小写不保证（Transmission 常回大写），
     * 直接 {@code equals} 会失配 —— 表现为"已有下载任务"认不出、重复推送。
     */
    static boolean isSameHash(TorrentsInfo torrentsInfo, String hash) {
        if (torrentsInfo == null || StrUtil.isBlank(hash)) {
            return false;
        }
        String h = torrentsInfo.getHash();
        return h != null && h.equalsIgnoreCase(hash);
    }

    /**
     * 是否需要做「备用RSS洗版清扫」。闸门与 {@link #deleteStandbyRss} 保持一致，
     * 用于决定要不要在主RSS提交前拍目录快照（快照本身很便宜，但没必要时不做 IO）。
     */
    private boolean needsStandbySweep(Item item) {
        Config config = ConfigUtil.CONFIG;
        return Boolean.TRUE.equals(config.getDelete())
                && Boolean.TRUE.equals(config.getStandbyRss())
                && !Boolean.TRUE.equals(config.getCoexist())
                && Boolean.TRUE.equals(item.getMaster())
                && ReUtil.contains(StringEnum.SEASON_REG, item.getReName());
    }

    /**
     * 拍下目录内现有文件名快照：洗版清扫只清这些文件（见 {@link #deleteStandbyRss}）。
     * 读取失败时返回空集，等价于"本次不清理文件"，绝不误删。
     */
    Set<String> snapshotExistingFiles(String downloadPath) {
        Set<String> paths = new HashSet<>();
        try {
            collectRelativePaths(new File(downloadPath), "", paths, 0);
        } catch (Exception e) {
            log.warn("读取下载目录失败, 本次不清理备用RSS文件 {}: {}",
                    downloadPath, ExceptionUtils.getMessage(e));
        }
        return paths;
    }

    /**
     * 递归收集相对路径：键与 {@link #sweepStandbyFiles} 完全一致（相对路径，避免子目录重名干扰）。
     */
    private static void collectRelativePaths(File dir, String prefix, Set<String> out, int depth) {
        if (dir == null || depth > SWEEP_MAX_DEPTH) {
            return;
        }
        for (File file : FileUtils.listFiles(dir)) {
            if (file == null) {
                continue;
            }
            String relative = prefix.isEmpty() ? file.getName() : prefix + "/" + file.getName();
            out.add(relative);
            if (file.isDirectory()) {
                collectRelativePaths(file, relative, out, depth + 1);
            }
        }
    }

    public boolean download(Ani ani, Item item, String savePath, File torrentFile) {
        ani = ObjectUtil.clone(ani);

        String name = item.getReName();
        Boolean master = item.getMaster();
        String subgroup = item.getSubgroup();
        subgroup = StrUtil.blankToDefault(subgroup, "未知字幕组");
        ani.setSubgroup(subgroup);

        log.info("添加下载 {}", name);

        if (!torrentFile.exists()) {
            log.error("种子下载出现问题 {} {}", name, FileUtils.getAbsolutePath(torrentFile));
            // 这条早期返回原先不落库，会让 DOWNLOAD_START 悬空；补一条失败记录
            recordDownloadFailure(ani, item, name + " 种子记录缺失，无法提交下载");
            return false;
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
                    // 受理即记账：下一轮同一版本不再洗版（见 WashState）。
                    // 记账点是"受理"而不是"完成"——这是所有下载器、所有入口
                    // （RSS 主流程 / 失败重试 / 强制下载）唯一的公共落点；
                    // "受理但最终没完成"由既有本地存在判定接管（文件不在 → 照常重下）。
                    // 另有一处补记：存量数据在观察到"本版记录有效"时由 backfillWashState 收敛。
                    WashState.markDownloaded(ani, item);
                    // OpenList: 提交即受理——等待/提升/失败处理已移交 OpenList 独立长任务池，
                    // pending 标记保持到离线真正完成，预览不会误判"已下载"
                    if (openListTool) {
                        return true;
                    }
                    TorrentUtil.refreshTorrentsCache();
                    return true;
                }
                // OpenList/Alist 返回 false：只可能来自提交阶段（infoHash 解析失败 / 离线等待池拒绝 /
                // 10008 长冷却 / 同 hash 等待被取消）；提交成功之后的收尾由 OpenList 内部
                // finalizeOfflineDownload 按三态异步处理，不走这里。
                // v3（§3.2 / D1 / D2）：这些都属于「存疑」或纯本地失败，**不能**删 pending、不能写失败队列、
                // 不能发失败通知——删了 pending 下一轮就会当成"没下过"重新提交，在网盘堆出第二份产物。
                if (openListTool) {
                    log.warn("OpenList 未受理本次提交（存疑：保留 pending / 计划快照，下一轮复核；"
                            + "不记失败、不发通知） {}", name);
                    RssTask.countRoundLocalState(LocalState.UNKNOWN);
                    return false;
                }
            } catch (ani.rss.download.OfflineTimeoutException e) {
                // 超时 != 坏种，也 != "网盘上没有文件"：存疑，保留 pending 与种子记录
                String message = ExceptionUtils.getMessage(e);
                if (openListTool) {
                    log.warn("离线等待超时（存疑：保留 pending / 计划快照，下一轮重做归位）: {}", message);
                    RssTask.countRoundLocalState(LocalState.UNKNOWN);
                    return false;
                }
                // message 已包含番剧名（OfflineTimeoutException 构造时拼接），不再重复
                log.error("离线超时失败: {}", message);
                recordDownloadFailure(ani, item, message);
                NotificationUtil.send(ConfigUtil.CONFIG, ani,
                        TaskFailureHumanizer.formatNotify(name, message),
                        NotificationStatusEnum.ERROR);
                TorrentUtil.deletePendingTorrent(ani, item);
                return false;
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
            // OpenList 普通异常：同样按「存疑」处理——异常最可能发生在"文件其实已经在网盘上"的时候
            // （提交超时 / 冷却 / 限流），旧实现会清 pending + 记失败 + 下轮重下。
            log.warn("OpenList 提交过程异常（存疑：保留 pending / 计划快照，下一轮复核）: {}", name);
            RssTask.countRoundLocalState(LocalState.UNKNOWN);
            return false;
        }

        // 删除下载失败的种子, 下次轮询仍会重试
        FileUtil.del(torrentFile);

        String raw = name + " 添加失败，疑似为坏种";
        log.error(raw);
        recordDownloadFailure(ani, item, raw);
        NotificationUtil.send(ConfigUtil.CONFIG, ani,
                TaskFailureHumanizer.formatNotify(name, raw),
                NotificationStatusEnum.ERROR);
        return false;
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
     * <p>
     * P1-4：去 synchronized。用 DOWNLOAD_COMPLETE 标签去重保证幂等，
     * 并发重复进入时只有一个能成功打标签，未抢到者直接返回。
     *
     * @param torrentsInfo
     */
    public void notification(TorrentsInfo torrentsInfo) {
        TorrentsInfo.State state = torrentsInfo.getState();
        String name = torrentsInfo.getName();

        if (!isDownloadCompleteState(state)) {
            return;
        }
        List<String> tags = torrentsInfo.getTags();
        if (!claimDownloadCompleteTag(torrentsInfo, tags)) {
            return;
        }
        Optional<Ani> aniOpt = findAniByDownloadPath(torrentsInfo);

        if (aniOpt.isEmpty()) {
            log.debug("未能获取番剧对象: {}", torrentsInfo.getName());
            return;
        }

        Ani ani = aniOpt.get();
        String subgroup = resolveNotificationSubgroup(ani, tags);

        Config config = ConfigUtil.CONFIG;
        scrapeCompletedAni(ani, config);
        boolean standby = notifyDownloadComplete(ani, name, tags);
        recordDownloadCompletion(ani, torrentsInfo, name, subgroup, standby);
        completeAniQuietly(ani);
    }

    private static boolean isDownloadCompleteState(TorrentsInfo.State state) {
        if (Objects.isNull(state)) {
            return false;
        }
        return List.of(
                TorrentsInfo.State.queuedUP.name(),
                TorrentsInfo.State.uploading.name(),
                TorrentsInfo.State.stalledUP.name(),
                TorrentsInfo.State.pausedUP.name(),
                TorrentsInfo.State.stoppedUP.name()
        ).contains(state.name());
    }

    private static boolean claimDownloadCompleteTag(TorrentsInfo torrentsInfo, List<String> tags) {
        // 添加下载完成标签，防止重复通知
        if (tags.contains(TorrentsTags.DOWNLOAD_COMPLETE.getValue())) {
            return false;
        }
        Boolean b = TorrentUtil.addTags(torrentsInfo, TorrentsTags.DOWNLOAD_COMPLETE.getValue());
        if (!b) {
            return false;
        }
        return true;
    }

    /**
     * 根据标签反向判断出字幕组，并同步到番剧对象。
     */
    private static String resolveNotificationSubgroup(Ani ani, List<String> tags) {
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
        return subgroup;
    }

    private void scrapeCompletedAni(Ani ani, Config config) {
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
    }

    /**
     * 发送下载完成通知，返回是否来自备用RSS。
     */
    private static boolean notifyDownloadComplete(Ani ani, String name, List<String> tags) {
        String text = StrFormatter.format("{} 下载完成", name);
        boolean standby = tags.contains(TorrentsTags.BACK_RSS.getValue());
        if (standby) {
            text = StrFormatter.format("(备用RSS) {}", text);
        }
        NotificationUtil.send(ConfigUtil.CONFIG, ani, text, NotificationStatusEnum.DOWNLOAD_END);
        return standby;
    }

    /**
     * 下载历史（成功侧）：失败侧已由 FailedDownloadQueue 记录，这里补齐成功记录。
     */
    private void recordDownloadCompletion(
            Ani ani, TorrentsInfo torrentsInfo, String name, String subgroup, boolean standby) {
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
    }

    private void completeAniQuietly(Ani ani) {
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

    @Override
    public String resolve(Ani ani) {
        return getDownloadPath(ani);
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
     * OpenList 路径同一集自动重推次数上限。
     * <p>
     * 与网盘 API 熔断阈值（{@code openListFailThreshold=3}）取同一口径：连续失败 3 次
     * 就停止自动重推，条目保留在失败队列由用户手动重试。少了这道闸门，一个持久失败
     * （坏种/超时/重命名冲突）会被 RSS 每轮重推一次，每次都往网盘堆一份新产物。
     */
    static final int MAX_OPENLIST_AUTO_RETRY_ATTEMPTS = 3;

    /**
     * 将文件名加入本地索引: movieStyle 用 M: 主名, 普通番剧用 season:episode
     * <p>
     * 实现已收敛到 {@link RenameUtil#addFileToEpisodeIndex} —— 离线归位成功时的增量追加
     * 必须与这里产出完全一致的键，两处各写一份必然漂移。
     */
    private static void addFileToIndex(Set<String> index, String filePath, boolean movieStyle) {
        RenameUtil.addFileToEpisodeIndex(index, filePath, movieStyle);
    }

    /**
     * 「本地存在」三态判定。
     * <p>
     * 为什么必须与 {@link #itemDownloaded} 的布尔语义分开：那个回答的是"要不要跳过下载"，
     * 把"查不到"与"确实没有"一起压成 false（保守重下）；而本枚举用于回答
     * "要不要删掉种子记录"，把两者合并就会让一次网盘抖动演变成
     * "删记录 + 整季重新下单"——查不到只能说明没查成，不能说明没有。
     */
    enum Presence {
        /** 文件确实在（含已在下载器任务列表中） */
        EXISTS,
        /** 查过了，确认没有 */
        ABSENT,
        /** 无法判断：网盘列举失败 / 熔断冷却中 / 索引不完整 */
        UNVERIFIABLE,
        /** 连续失败次数已达上限，停止自动重推（保留在失败队列等手动重试） */
        RETRY_EXHAUSTED,
        /** 记录有效但无需下载动作（未开启重命名时的信任记录语义） */
        VALID;

        /**
         * 是否算作「记录有效」（跳过下载）。{@link #EXISTS} 与 {@link #VALID} 同义，
         * 分开只是为了让日志读起来知道"为什么有效"。
         */
        boolean valid() {
            return this == VALID || this == EXISTS;
        }
    }

    /**
     * OpenList 路径的「记录是否有效」判定（三态 + 存疑成因）。
     * <p>
     * 判定顺序：熔断冷却 → <b>本地快照（v3 新增，命中即零请求）</b> → 失败队列 → 归位对账 → 兜底校验。
     * 任一环节"没能查成"都返回 {@link Presence#UNVERIFIABLE}，并带上
     * {@link UnknownReason} 让调用方把"为什么没查成"说清楚，由调用方保留记录。
     * <p>
     * package-private：判定表本身要能被单测直接固化（尤其"快照命中 → 零网盘请求"这条不变量）。
     */
    PresenceDecision resolveOpenListPresence(Ani ani, Item item, String reName, String savePath,
                                            OfflineDownloader offline, Set<String> localEpisodeIndex) {
        // 熔断冷却期：本轮不发网盘请求，直接"无法判断"（不删记录、不重下）。
        // 放在失败队列之前：冷却期连兜底校验都发不出请求，先判冷却才能给出准确成因——
        // 否则一律报成"列举失败"，把"等 60s 就好"说成"网盘坏了"。
        if (OpenListApi.isListingCoolingDown()) {
            OpenListApi.logCooldownSkipOnce("本地状态校验");
            return PresenceDecision.of(Presence.UNVERIFIABLE, UnknownReason.COOLDOWN);
        }
        // v3（D4 / 落地清单 #10）：快照已确认本集存在 → 零请求直接采信，不再每轮对账。
        // 这是"已确认条目零请求"的落点：一份新鲜快照（含主动失效与增量追加）足以回答"在不在"，
        // 而每轮对每条已确认的集都重做一次归位对账 = 每条一次网盘往返，订阅一多就是整批放大
        // （用户实测的 fs/list 500 + TLS handshake timeout 正是从这条链路上被放大的）。
        // 只信"有"不信"没有"：快照过期只会让我们少下一次，代价可接受；
        // 而用它断言"不存在"会删记录重下，那是绝不能有的方向。
        // 有意接受的代价：子目录里的历史残留在这份快照存活期内不会被自动归位；
        // 快照过期后 peek 返回 null，relocateEpisodeFiles 会重新接管——是"延迟"，不是"丢失"。
        LocalStateCache.Snapshot snapshot = LocalStateCache.peek(ani, getDownloadPath(ani));
        if (snapshot != null && matchesEpisodeIndex(ani, item, snapshot.episodeIndex())) {
            return PresenceDecision.of(Presence.EXISTS, UnknownReason.NONE);
        }
        String failKey = FailedDownloadQueue.keyOf(ani.getId(), item.getInfoHash(), reName);
        FailedDownloadQueue.FailedItem queued = FailedDownloadQueue.list().stream()
                .filter(f -> Objects.equals(f.getId(), failKey))
                .findFirst()
                .orElse(null);
        if (queued != null) {
            // 失败队列已有本集记录: 跳过二次对账, 直接兜底校验, 避免每轮重复对账
            PresenceDecision verified = verifyPresence(ani, item, localEpisodeIndex);
            if (verified.presence() == Presence.EXISTS) {
                // 文件已实际就位(如手动归位/上轮重下成功): 清掉过期失败记录
                FailedDownloadQueue.remove(failKey);
                clearAbsence(failKey);
                return verified;
            }
            // 连续失败上限：避免真失败(坏种/超时/持久冲突)时每轮无限重推，
            // 每次都往网盘堆一份新产物。条目保留在失败队列，可由用户手动重试。
            int attempts = queued.getAttempts() == null ? 0 : queued.getAttempts();
            if (verified.presence() == Presence.ABSENT && attempts >= MAX_OPENLIST_AUTO_RETRY_ATTEMPTS) {
                return PresenceDecision.of(Presence.RETRY_EXHAUSTED, UnknownReason.NONE);
            }
            if (verified.presence() == Presence.ABSENT) {
                return absenceOrUnconfirmed(failKey, reName, verified);
            }
            return verified;
        }
        // 周期对账（仅离线工具）：记录存在但文件滞留子目录/云下载目录时自动归位到顶层，
        // 修复「显示已存在但文件不在预期位置」且无任何自动纠正机制的问题。
        OfflineDownloader.RelocateResult relocated = null;
        try {
            relocated = offline.relocateEpisodeFiles(ani, item, savePath);
        } catch (Exception e) {
            log.warn("离线归位对账失败 {}: {}", reName, ExceptionUtils.getMessage(e));
            recordRelocateFailure(ani, item, e);
        }
        // 对账已确认归位成功 / 本就位于顶层：直接采信，不必再跑一次兜底列举
        if (relocated == OfflineDownloader.RelocateResult.RELOCATED
                || relocated == OfflineDownloader.RelocateResult.ALREADY_AT_TOP) {
            clearAbsence(failKey);
            return PresenceDecision.of(Presence.EXISTS, UnknownReason.NONE);
        }
        // 对账没能跑完：既不能说文件在，也不能说文件不在。保留记录，本轮不重下。
        if (relocated == OfflineDownloader.RelocateResult.UNVERIFIABLE) {
            return unverifiableOrSnapshot(ani, item, UnknownReason.VERIFY_FAILED);
        }
        // 对账确认没找到：兜底检查（网盘视频文件按需检查；离线工具的任务列表恒空）
        PresenceDecision fallback = verifyPresence(ani, item, localEpisodeIndex);
        Presence combined = combineFallback(relocated, fallback.presence());
        if (combined == Presence.ABSENT) {
            // 「确认没有」是唯一允许删记录重下的判定，因此必须跨时间确认：
            // Alist/OpenList 的目录列举自带缓存（存储驱动"缓存过期时间"默认可达 1 小时），
            // 刚下完 / 刚移动完去列目录经常列不到，一次就删记录会换来重复下载 + 与已落盘文件撞名。
            PresenceDecision absence = absenceOrUnconfirmed(failKey, reName,
                    PresenceDecision.of(Presence.ABSENT, UnknownReason.NONE));
            if (absence.presence() == Presence.ABSENT) {
                log.warn("归位对账未找到且兜底检查确认无文件，清理过期种子记录并重新下载 {}", reName);
                if (relocated != null) {
                    recordRelocateFailure(ani, item, new IllegalStateException(
                            "归位对账未发现本集文件(" + relocated + ")"));
                }
            }
            return absence;
        }
        if (combined == Presence.UNVERIFIABLE) {
            log.info("归位对账未找到，但兜底检查未能完成（{}），本轮保留记录不重下 {}",
                    unknownReasonText(fallback.reason()), reName);
            return unverifiableOrSnapshot(ani, item, fallback.reason());
        }
        clearAbsence(failKey);
        return PresenceDecision.of(combined, UnknownReason.NONE);
    }

    /**
     * 「无法确认」前的最后一层兜底：新鲜快照已确认本集存在 → 直接改判 {@link Presence#EXISTS}。
     * <p>
     * 为什么值得这么做：网盘抖一下（上游 TLS 握手超时之类）就会让<b>整批已下载过的集</b>
     * 变成"存疑"并跳过下载——而它们其实都在快照里写着"存在"。事实已经在手，
     * 就不该因为一次列举失败改口（那也正是用户看到的"全都是网盘不可用"）。
     * <p>
     * <b>只信"有"，不信"没有"</b>：快照可能带外过期（用户在网盘手动删了文件），
     * 用它断言"不存在"会删记录重下；而"存在"这一半即使过期也只会让我们少下一次，
     * 代价可接受（TTL 与主动失效会兜底回收）。
     * <p>
     * package-private：判定表本身要能被单测直接固化。
     */
    PresenceDecision unverifiableOrSnapshot(Ani ani, Item item, UnknownReason reason) {
        LocalStateCache.Snapshot snapshot = LocalStateCache.peek(ani, getDownloadPath(ani));
        // 截断的快照仍然能确认"存在"（找到了就是找到了），所以不要求 complete()
        if (snapshot != null && matchesEpisodeIndex(ani, item, snapshot.episodeIndex())) {
            log.info("网盘列举未能完成，改用本地状态快照判定为「已存在」（快照 {} 分钟前构建） {}",
                    (System.currentTimeMillis() - snapshot.builtAt()) / 60_000L, item.getReName());
            return PresenceDecision.of(Presence.EXISTS, UnknownReason.NONE);
        }
        return PresenceDecision.of(Presence.UNVERIFIABLE, reason);
    }

    /**
     * 「记录是否有效」判定结果 + 无法确认时的成因。
     * <p>
     * 为什么要把成因一起带出来：{@code UNVERIFIABLE} 只回答"没查成"，
     * 而"为什么没查成"决定用户下一步做什么（等冷却结束 / 查网盘 / 调大列举上限）。
     * 只回一个三态，调用方就只能打出一句"网盘不可用"——那句话把三种完全不同的
     * 故障说成同一件事，日志里再没有第二条线索可用。
     */
    record PresenceDecision(Presence presence, UnknownReason reason) {
        static PresenceDecision of(Presence presence, UnknownReason reason) {
            return new PresenceDecision(presence, reason == null ? UnknownReason.NONE : reason);
        }

        boolean unverifiable() {
            return presence == Presence.UNVERIFIABLE;
        }
    }

    /**
     * 「确认没有」需要跨时间确认的间隔。
     * <p>
     * <b>为什么不能一次就删记录</b>：Alist/OpenList 侧的目录列举<b>自带缓存</b>
     * （存储驱动的"缓存过期时间"默认可达 1 小时），刚下完 / 刚移动完去列目录经常列不到；
     * 而"一次判 ABSENT 就删记录重下"会同时付出两份代价——重复下载 + 与已落盘文件撞名。
     * 所以改成"两次都判没有、且相隔 ≥ 本间隔"才允许删记录，其余情况报「存疑」等下一轮。
     * <p>
     * 取 <b>62 分钟</b>：<b>刻意比 Alist/OpenList 默认的 1 小时目录缓存更长一点</b>——
     * 否则两次观察可能都在同一份过期缓存里，确认等于没确认。默认 15 分钟轮询下
     * 相当于第 5 轮才可能真正清理记录；代价是"文件确实被删"要多等一会儿，
     * 换来的是绝不重复下载。
     */
    static final long ABSENT_CONFIRM_INTERVAL_MS = TimeUnit.MINUTES.toMillis(62);

    /** 同一集首次被判「确认没有」的时刻（key = aniId|infoHash|reName） */
    private static final Map<String, Long> ABSENT_FIRST_SEEN = new ConcurrentHashMap<>();

    /**
     * 缺席是否已被跨时间确认：第一次看到只登记并返回 {@code false}。
     * <p>
     * package-private 且带 {@code nowMs} 重载：判定本身要能被单测用合成时间固化。
     */
    static boolean absenceConfirmed(String key, long nowMs) {
        if (StrUtil.isBlank(key)) {
            return false;
        }
        if (ABSENT_FIRST_SEEN.size() > 4096) {
            // 兜底：长期运行不该无限增长（正常路径会 remove）。清空只会让确认重新计时，方向安全。
            ABSENT_FIRST_SEEN.clear();
        }
        Long first = ABSENT_FIRST_SEEN.putIfAbsent(key, nowMs);
        return first != null && nowMs - first >= ABSENT_CONFIRM_INTERVAL_MS;
    }

    static boolean absenceConfirmed(String key) {
        return absenceConfirmed(key, System.currentTimeMillis());
    }

    /** 文件又被找到了 → 清掉缺席登记，下次从头计时。 */
    private static void clearAbsence(String key) {
        if (StrUtil.isNotBlank(key)) {
            ABSENT_FIRST_SEEN.remove(key);
        }
    }

    /**
     * 「确认没有」的跨时间闸门：首次只登记，跨过间隔后才真的允许删记录。
     *
     * @param decision 原本的 ABSENT 判定（确认后原样返回，由调用方执行删记录重下）
     */
    private static PresenceDecision absenceOrUnconfirmed(String key, String reName, PresenceDecision decision) {
        if (absenceConfirmed(key)) {
            clearAbsence(key);
            return decision;
        }
        log.warn("判为「没有文件」但尚未跨时间确认（网盘目录列举自带缓存、可达 1 小时），"
                + "本轮保留记录等下一轮复核 {}", reName);
        return PresenceDecision.of(Presence.UNVERIFIABLE, UnknownReason.ABSENCE_UNCONFIRMED);
    }

    /**
     * 存疑成因的展示文案（日志与自检共用）。
     * <p>
     * 抽成静态纯函数是为了能把"文案本身"固化下来：这些字串就是可排查性，
     * 一旦有人把它们又合并成一句"网盘不可用"，用例会立刻失败。
     */
    static String unknownReasonText(UnknownReason reason) {
        if (reason == null) {
            return "原因未知";
        }
        return switch (reason) {
            case COOLDOWN -> "网盘接口熔断冷却中";
            case VERIFY_FAILED -> "网盘列举失败";
            case BUDGET_EXHAUSTED -> "本轮网盘列举预算已耗尽";
            case INDEX_INCOMPLETE -> "集数索引不完整（列举被截断）";
            case DOWNLOADING -> "等待改名落地";
            case ABSENCE_UNCONFIRMED -> "缺席未确认（网盘目录列举可能有缓存）";
            case NONE -> "原因未知";
        };
    }

    /**
     * 冷却剩余时长的展示后缀（仅冷却成因有），空串表示无需追加。
     */
    private static String cooldownRemainText(UnknownReason reason) {
        if (reason != UnknownReason.COOLDOWN) {
            return "";
        }
        long remain = OpenListApi.listingCooldownRemainingMs();
        return remain > 0L
                ? "，剩余 " + ((remain + 999L) / 1000L) + "s（冷却期内不发任何网盘请求）"
                : "";
    }

    /**
     * 「归位对账结果 + 兜底校验结果」→ 最终判定的完整决策表。
     * <p>
     * 抽成纯函数是为了能直接固化这张表。表里任何一格把"没查成"错判成"确认没有"，
     * 代价都是整季种子记录被删并重新下单——2026-09-18 线上事故的直接成因。
     * 关键一格：{@code NOT_FOUND + UNVERIFIABLE → UNVERIFIABLE}，<b>不是</b> ABSENT。
     */
    static Presence combineFallback(OfflineDownloader.RelocateResult relocated, Presence fallback) {
        if (relocated == OfflineDownloader.RelocateResult.RELOCATED
                || relocated == OfflineDownloader.RelocateResult.ALREADY_AT_TOP) {
            return Presence.EXISTS;
        }
        if (relocated == OfflineDownloader.RelocateResult.UNVERIFIABLE) {
            return Presence.UNVERIFIABLE;
        }
        return fallback == null ? Presence.UNVERIFIABLE : fallback;
    }

    /**
     * 「本地存在」三态校验：区分"确实没有"与"查不到"。
     * <p>
     * 与 {@link #itemDownloaded} 的区别是失败不降级：列举抛异常、索引被截断时
     * 返回 {@link Presence#UNVERIFIABLE} 而非 ABSENT，并带上成因，调用方据此避免破坏性动作。
     *
     * @param localEpisodeIndex 预构建索引；为 null 时按需走订阅级快照缓存
     */
    private PresenceDecision verifyPresence(Ani ani, Item item, Set<String> localEpisodeIndex) {
        if (localEpisodeIndex != null) {
            return PresenceDecision.of(matchesEpisodeIndex(ani, item, localEpisodeIndex)
                    ? Presence.EXISTS : Presence.ABSENT, UnknownReason.NONE);
        }
        EpisodeIndex index;
        try {
            index = cachedEpisodeIndex(ani, getDownloadPath(ani));
        } catch (Exception e) {
            log.warn("构建集数索引失败（本轮不删记录、不重下） {}: {}",
                    item.getReName(), ExceptionUtils.getMessage(e));
            return unverifiableOrSnapshot(ani, item, UnknownReason.VERIFY_FAILED);
        }
        if (matchesEpisodeIndex(ani, item, index.index())) {
            return PresenceDecision.of(Presence.EXISTS, UnknownReason.NONE);
        }
        if (!index.complete()) {
            // 列举被截断：只能确认"存在"，不能断言"不存在"
            log.info("集数索引不完整（列举被截断），无法断言本集不存在 {}", item.getReName());
            return PresenceDecision.of(Presence.UNVERIFIABLE, UnknownReason.INDEX_INCOMPLETE);
        }
        return PresenceDecision.of(Presence.ABSENT, UnknownReason.NONE);
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
         * 网盘列举失败 / 查询失败（查询失败 ≠ 目录为空）
         * <p>
         * <b>不含</b>熔断冷却——冷却是一个"等一会儿会自愈"的状态，
         * 与"网盘坏了"的对策完全不同，故单列为 {@link #COOLDOWN}。
         */
        VERIFY_FAILED,
        /**
         * 网盘接口处于熔断冷却期：本轮一个请求都没发出去
         */
        COOLDOWN,
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
        DOWNLOADING,
        /**
         * 判为「没有文件」但尚未跨时间确认（网盘目录列举自带缓存，刚下完可能列不到）
         */
        ABSENCE_UNCONFIRMED
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
