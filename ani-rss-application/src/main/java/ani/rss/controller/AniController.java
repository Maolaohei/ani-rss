package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.CacheUtils;
import ani.rss.commons.DeleteGuard;
import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.PinyinUtils;
import ani.rss.commons.WeekComparator;
import ani.rss.entity.*;
import ani.rss.entity.dto.IdDTO;
import ani.rss.entity.dto.ImportAniDataDTO;
import ani.rss.entity.dto.RssToAniDTO;
import ani.rss.entity.web.Result;
import ani.rss.enums.EventTypeEnum;
import ani.rss.enums.SortTypeEnum;
import ani.rss.service.AniLocks;
import ani.rss.service.AniService;
import ani.rss.service.ClearService;
import ani.rss.service.DownloadService;
import ani.rss.task.RssTask;
import ani.rss.util.other.*;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.comparator.PinyinComparator;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.*;
import java.util.function.ToLongFunction;

@Slf4j
@RestController
public class AniController extends BaseController {
    // 订阅增删操作的锁(与添加合集订阅共用 AniUtil.SUBSCRIPTION_LOCK)，防止 TOCTOU 竞态
    private static final Object SUBSCRIPTION_LOCK = AniUtil.SUBSCRIPTION_LOCK;

    /**
     * 预览回写「漏集检查水位」的最小落盘间隔。
     * <p>
     * 预览是最常用入口，若每次都落盘，光是翻订阅列表就能写出大量全量订阅文件（P2-1）。
     * 健康分的「漏集信息可能过期」提示以 <b>3 天</b>为界，因此水位只要保留到小时级就足够准确。
     */
    private static final long OMIT_PERSIST_INTERVAL_MS = 60 * 60 * 1000L;

    @Resource
    private AniService aniService;

    @Resource
    private ClearService clearService;

    @Resource
    private DownloadService downloadService;

    /**
     * 订阅列表已变更：落盘并让媒体库缓存失效。
     * <p>
     * 媒体库列表有 60 秒缓存（{@code LibraryController.CACHE_TTL_MS}），
     * 订阅的增删改若不让缓存失效，媒体库最多 60 秒内仍会显示已删除/已改名的订阅。
     */
    private static void syncAniList() {
        AniUtil.sync();
        LibraryController.invalidate();
    }

    /**
     * 列表派生字段缓存时长。拼音/首字母只由标题决定，改标题就是换 key，
     * 因此不需要任何失效逻辑，TTL 只是防止长期不访问的标题常驻。
     */
    private static final long PINYIN_CACHE_TTL_MS = 10 * 60 * 1000L;

    /**
     * 列表派生字段：拼音（P1-10）。
     * <p>
     * 列表每次请求都会对全部订阅重算拼音，200 订阅下是可观的无谓 CPU。
     * 键就是标题本身，因此不存在失效错误的问题。
     */
    private static String cachedPinyin(String title) {
        if (StrUtil.isBlank(title)) {
            return title;
        }
        String key = "pinyin:" + title;
        String cached = CacheUtils.get(key);
        if (cached != null) {
            return cached;
        }
        String value = PinyinUtils.getPinyin(title, "");
        CacheUtils.put(key, value, PINYIN_CACHE_TTL_MS);
        return value;
    }

    /**
     * 列表派生字段：拼音首字母（P1-10），与 {@link #cachedPinyin(String)} 同源同策
     */
    private static String cachedPinyinInitials(String title) {
        if (StrUtil.isBlank(title)) {
            return title;
        }
        String key = "pinyinInitials:" + title;
        String cached = CacheUtils.get(key);
        if (cached != null) {
            return cached;
        }
        String value = PinyinUtils.getFirstLetter(title, "");
        CacheUtils.put(key, value, PINYIN_CACHE_TTL_MS);
        return value;
    }

    @Auth
    @Operation(summary = "添加订阅")
    @PostMapping("/addAni")
    public Result<Void> addAni(@RequestBody Ani ani) {
        ani.setTitle(ani.getTitle().trim())
                .setUrl(ani.getUrl().trim());
        AniUtil.verify(ani);

        synchronized (SUBSCRIPTION_LOCK) {
            Optional<Ani> first = AniUtil.getAniList().stream()
                    .filter(it -> it.getId().equals(ani.getId()))
                    .findFirst();

            if (first.isPresent()) {
                throw new IllegalArgumentException("此订阅已存在");
            }

            first = AniUtil.getAniList().stream()
                    .filter(it -> it.getTitle().equals(ani.getTitle()) && it.getSeason().equals(ani.getSeason()))
                    .findFirst();

            String title = ani.getTitle();
            Integer season = ani.getSeason();

            if (first.isPresent()) {
                Config config = ConfigUtil.CONFIG;
                Boolean replace = config.getReplace();
                if (replace) {
                    AniUtil.getAniList().remove(first.get());
                    // 被替换掉的订阅不再存在，尽力回收它的锁对象（P2-4）
                    AniLocks.release(first.get());
                    // 立刻失效 id 索引，避免"改完列表到 sync() 之间"查到已被替换掉的订阅（P2-14）
                    AniUtil.invalidateIdIndex();
                    log.info("自动替换 {} 第{}季", title, season);
                } else {
                    throw new IllegalArgumentException("订阅标题重复");
                }
            }

            AniUtil.getAniList().add(ani);
            AniUtil.invalidateIdIndex();
        }
        syncAniList();
        Boolean enable = ani.getEnable();
        if (enable) {
            // 必须走任务管理器：才能展示运行中状态/取消/排队，避免只剩 Hash、状态空闲
            String downloadMsg = RssTask.submitManualRefresh(List.of(ani));
            log.info("添加订阅后触发下载: {} => {}", ani.getTitle(), downloadMsg);
        } else {
            // 如果未开启订阅则只获取一下集数
            ThreadUtil.execute(() -> {
                try {
                    List<Item> items = ItemsUtil.getItems(ani);
                    int currentEpisodeNumber = ItemsUtil.currentEpisodeNumber(ani, items);
                    ani.setCurrentEpisodeNumber(currentEpisodeNumber);
                } catch (Exception e) {
                    log.error(ExceptionUtils.getMessage(e), e);
                }
            });
        }
        log.info("添加订阅 {} {} {}", ani.getTitle(), ani.getUrl(), ani.getId());

        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("url", ani.getUrl());
        eventData.put("season", ani.getSeason());
        eventData.put("subgroup", ani.getSubgroup());
        EventWebhookUtil.emit(EventTypeEnum.SUBSCRIPTION_ADDED, ani, eventData);

        return Result.success("添加订阅成功");
    }

    @Auth
    @Operation(summary = "修改订阅")
    @PostMapping("/setAni")
    public Result<Void> setAni(@RequestBody Ani ani) {
        ani.setTitle(ani.getTitle().trim())
                .setUrl(ani.getUrl().trim());
        AniUtil.verify(ani);
        Optional<Ani> first = AniUtil.getAniList().stream()
                .filter(it -> !it.getId().equals(ani.getId()))
                .filter(it -> it.getTitle().equals(ani.getTitle()) && it.getSeason().equals(ani.getSeason()))
                .findFirst();
        if (first.isPresent()) {
            return Result.error("订阅标题重复");
        }

        first = AniUtil.getAniList().stream()
                .filter(it -> it.getId().equals(ani.getId()))
                .findFirst();
        if (first.isEmpty()) {
            return Result.error("修改失败");
        }
        HttpServletRequest request = Global.REQUEST.get();
        String move = request.getParameter("move");
        if (Boolean.parseBoolean(move)) {
            Ani get = ObjectUtil.clone(first.get());
            ThreadUtil.execute(() -> {
                String downloadPath = downloadService.getDownloadPath(get);
                String newDownloadPath = downloadService.getDownloadPath(ani);
                Boolean login = TorrentUtil.login();
                List<TorrentsInfo> torrentsInfos = new ArrayList<>();
                if (login) {
                    torrentsInfos = TorrentUtil.getTorrentsInfos();
                }
                if (downloadPath.equals(newDownloadPath)) {
                    // 位置未发生改变
                    return;
                }

                File downloadPathFile = new File(downloadPath);

                for (TorrentsInfo torrentsInfo : torrentsInfos) {
                    String downloadDir = torrentsInfo.getDownloadDir();
                    if (!downloadDir.equals(downloadPath)) {
                        // 旧位置不相同
                        continue;
                    }
                    // 修改保存位置
                    TorrentUtil.setSavePath(torrentsInfo, newDownloadPath);
                }
                if (!downloadPathFile.exists()) {
                    return;
                }
                if (downloadPathFile.isFile()) {
                    return;
                }
                if (!torrentsInfos.isEmpty()) {
                    ThreadUtil.sleep(3000);
                }
                try {
                    FileUtil.mkdir(newDownloadPath);
                    File[] files = FileUtils.listFiles(downloadPath);
                    for (File oldFile : files) {
                        log.info("移动文件 {} ==> {}", oldFile, newDownloadPath);
                        FileUtil.move(oldFile, new File(newDownloadPath), true);
                    }
                    FileUtil.del(downloadPath);
                    clearService.clearParentFile(downloadPath);
                } catch (Exception e) {
                    log.error(ExceptionUtils.getMessage(e), e);
                }
            });
        }
        File torrentDir = TorrentUtil.getTorrentDir(first.get());

        String[] ignoreProperties = new String[]{"currentEpisodeNumber", "lastDownloadTime"};
        BeanUtil.copyProperties(ani, first.get(), ignoreProperties);

        File newTorrentDir = TorrentUtil.getTorrentDir(first.get());
        if (!torrentDir.toString().equals(newTorrentDir.toString())) {
            FileUtil.move(torrentDir, newTorrentDir.getParentFile(), true);
        }
        syncAniList();

        log.info("修改订阅 {} {} {}", ani.getTitle(), ani.getUrl(), ani.getId());
        return Result.success("修改成功");
    }

    @Auth
    @Operation(summary = "删除订阅")
    @PostMapping("/deleteAni")
    public Result<Void> deleteAni(@RequestBody List<String> ids, @RequestParam("deleteFiles") Boolean deleteFiles) {
        Assert.notEmpty(ids, "未选择订阅");
        List<Ani> anis;
        synchronized (SUBSCRIPTION_LOCK) {
            anis = AniUtil.getAniList().stream()
                    .filter(it -> ids.contains(it.getId()))
                    .toList();
            if (anis.isEmpty()) {
                return Result.error("删除失败");
            }
            for (Ani ani : anis) {
                AniUtil.getAniList().remove(ani);
                // 订阅已删除，尽力回收它的锁对象，避免反复增删订阅时锁对象常驻（P2-4）
                AniLocks.release(ani);
            }
            // 立刻失效 id 索引，避免"改完列表到 sync() 之间"查到已删除的订阅（P2-14）
            AniUtil.invalidateIdIndex();
        }

        syncAniList();
        ThreadUtil.execute(() -> {
            for (Ani ani : anis) {
                File torrentDir = TorrentUtil.getTorrentDir(ani);
                FileUtil.del(torrentDir);
                clearService.clearParentFile(torrentDir);
                log.info("删除订阅 {} {} {}", ani.getTitle(), ani.getUrl(), ani.getId());
                EventWebhookUtil.emit(EventTypeEnum.SUBSCRIPTION_DELETED, ani, Map.of("deleteFiles", deleteFiles));
            }
            if (!deleteFiles) {
                // 不删除本地文件
                return;
            }

            List<File> files = anis
                    .stream()
                    .map(ani -> downloadService.getDownloadPath(ani))
                    .map(File::new)
                    .toList();

            Boolean login = TorrentUtil.login();
            List<TorrentsInfo> torrentsInfos = new ArrayList<>();
            if (login) {
                torrentsInfos = TorrentUtil.getTorrentsInfos();
            }
            for (File file : files) {
                // 安全闸门：下载路径来自订阅自定义位置或全局模板，模板为空时会退化为进程工作目录
                // (程序目录, 内含 config/、logs/)，也可能被填成 D:/ 这类根路径。
                // 前端虽有二次确认，但直接调接口同样能触发，必须在后端兜底。
                if (!DeleteGuard.isSafeToDeleteRecursively(file)) {
                    log.warn("下载路径过于宽泛, 跳过删除本地文件: {}", FileUtils.getAbsolutePath(file));
                    continue;
                }
                String path = FileUtils.getAbsolutePath(file);
                for (TorrentsInfo torrentsInfo : torrentsInfos) {
                    String downloadDir = torrentsInfo.getDownloadDir();
                    if (downloadDir.equals(path)) {
                        TorrentUtil.delete(torrentsInfo, true, true);
                    }
                }
                if (!file.exists()) {
                    continue;
                }
                ThreadUtil.sleep(3000);
                log.info("删除 {}", file);
                FileUtil.del(file);
                clearService.clearParentFile(file);
            }
        });
        return Result.success("删除订阅成功");
    }

    @Auth
    @Operation(summary = "订阅列表")
    @PostMapping("/listAni")
    public Result<ListAni> listAni() {
        Config config = ConfigUtil.CONFIG;

        SortTypeEnum sortType = config.getSortType();

        ListAni listAni = new ListAni();
        List<ListAni.WeekAni> weekAniList = new ArrayList<>();

        List<String> weeks = List.of("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六");
        Map<String, List<Ani>> weekItemsMap = new HashMap<>();

        for (String week : weeks) {
            List<Ani> items = new ArrayList<>();
            weekAniList.add(new ListAni.WeekAni(week, items));
            weekItemsMap.put(week, items);
        }

        WeekComparator weekComparator = new WeekComparator();
        weekAniList = weekAniList.stream()
                .sorted((a, b) ->
                        weekComparator.compare(a.getWeekLabel(), b.getWeekLabel())
                ).toList();
        listAni.setWeekList(weekAniList);

        // 按拼音排序。刻意复制一份：排序与下面的派生字段计算都只在副本上进行，
        // 不触碰 AniUtil.ANI_LIST 里那些被 RSS 轮次/落盘同时使用的活对象（P1-10）
        List<Ani> aniList = new ArrayList<>(AniUtil.getAniList());

        List<String> releaseDateList = aniList.stream()
                .map(Ani::getReleaseDate)
                .map(DateUtil::beginOfMonth)
                .sorted(Comparator.comparingLong(Date::getTime).reversed())
                .map(it -> DateUtil.format(it, DatePattern.NORM_MONTH_PATTERN))
                .distinct()
                .toList();
        listAni.setReleaseDateList(releaseDateList)
                .setTotal(aniList.size());

        // 分组清单：供前端筛选下拉直接使用（未分组的订阅不产生条目）
        List<String> groupList = aniList.stream()
                .map(Ani::getGroup)
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
        listAni.setGroupList(groupList);

        if (sortType == SortTypeEnum.SCORE) {
            aniList = CollUtil.sort(aniList, Comparator.comparingDouble(Ani::getScore).reversed());
        }

        if (sortType == SortTypeEnum.PINYIN) {
            PinyinComparator pinyinComparator = new PinyinComparator();
            aniList = CollUtil.sort(aniList, (a, b) -> pinyinComparator.compare(a.getTitle(), b.getTitle()));
        }

        if (sortType == SortTypeEnum.DOWNLOAD_TIME) {
            aniList = CollUtil.sort(aniList, Comparator.comparingLong((ToLongFunction<Ani>) ani -> {
                Long lastDownloadTime = ani.getLastDownloadTime();
                if (lastDownloadTime == 0) {
                    return Long.MAX_VALUE;
                }
                return lastDownloadTime;
            }).reversed());
        }

        int index = 0;
        long now = System.currentTimeMillis();
        for (Ani ani : aniList) {
            /*
            P1-10：列表是纯展示接口，绝不能往共享的 Ani 上写字段。
            同一条 Ani 此刻可能正被 AniUtil.doSync()（把 healthScore/Level/Reasons 置 null 后序列化落盘）
            与 RssTask（写 sort / lastDownloadTime）同时改动——那是无锁并发写同一批对象，
            轻则列表里"健康分忽有忽无"，重则 ani.v2.json 里落进半写状态的字段。
            这里浅拷贝一份，所有派生字段只写在副本上；JSON 结构完全不变，前端无感知。
            */
            Ani view = new Ani();
            BeanUtil.copyProperties(ani, view);
            view.setSort(index++);
            String title = view.getTitle();
            String pinyin = cachedPinyin(title);
            String pinyinInitials = cachedPinyinInitials(title);

            Date releaseDate = view.getReleaseDate();
            int week = DateUtil.dayOfWeek(releaseDate) - 1;
            String weekLabel = weeks.get(week);

            // 运维健康分（不落盘）；列表用 RSS 周期缓存的漏集，避免 N 次拉源
            try {
                boolean omitOn = Boolean.TRUE.equals(config.getOmit());
                int omitCount = SubscriptionHealth.cachedOmitCount(view, omitOn);
                SubscriptionHealth.Score health = SubscriptionHealth.compute(view, omitCount, now);
                view.setHealthScore(health.score())
                        .setHealthLevel(health.level())
                        .setHealthReasons(health.reasons());
            } catch (Exception ignored) {
            }

            view
                    .setPinyin(pinyin)
                    .setPinyinInitials(pinyinInitials)
                    .setWeekLabel(weekLabel);

            List<Ani> anis = weekItemsMap.get(weekLabel);
            anis.add(view);
        }

        return Result.success(listAni);
    }

    @Auth
    @Operation(summary = "更新总集数")
    @PostMapping("/updateTotalEpisodeNumber")
    public Result<Void> updateTotalEpisodeNumber(@RequestParam("force") Boolean force, @RequestBody List<String> ids) {
        Assert.notEmpty(ids, "未选择订阅");
        ThreadUtil.execute(() -> {
            log.info("开始手动更新总集数");
            int count = 0;
            for (Ani ani : AniUtil.getAniList()) {
                String id = ani.getId();
                if (!ids.contains(id)) {
                    continue;
                }
                BgmInfo bgmInfo;
                try {
                    bgmInfo = BgmUtil.getBgmInfo(ani);
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                    continue;
                }
                Boolean b = aniService.updateTotalEpisodeNumber(ani, bgmInfo, force);
                if (b) {
                    count++;
                }
            }
            syncAniList();
            log.info("手动更新总集数完成 共更新{}条订阅", count);
        });
        return Result.success("已开始更新总集数");
    }

    @Auth
    @Operation(summary = "批量 启用/禁用 订阅")
    @PostMapping("/batchEnable")
    public Result<Void> batchEnable(@RequestParam("value") Boolean value, @RequestBody List<String> ids) {
        Assert.notEmpty(ids, "未选择订阅");

        for (Ani ani : AniUtil.getAniList()) {
            String id = ani.getId();
            if (!ids.contains(id)) {
                continue;
            }
            ani.setEnable(value);
            EventWebhookUtil.emit(EventTypeEnum.SUBSCRIPTION_ENABLED_CHANGED, ani, Map.of("enable", value));
        }
        syncAniList();
        return Result.success("修改完成");
    }

    @Auth
    @Operation(summary = "批量设置订阅分组")
    @PostMapping("/batchGroup")
    public Result<Void> batchGroup(@RequestParam("group") String group, @RequestBody List<String> ids) {
        Assert.notEmpty(ids, "未选择订阅");
        // 传空串表示移出分组
        String normalized = StrUtil.trimToNull(group);

        int count = 0;
        for (Ani ani : AniUtil.getAniList()) {
            if (!ids.contains(ani.getId())) {
                continue;
            }
            ani.setGroup(normalized);
            count++;
        }
        if (count == 0) {
            return Result.error("未找到可修改的订阅");
        }
        syncAniList();
        return Result.success(normalized == null
                ? StrUtil.format("已将 {} 个订阅移出分组", count)
                : StrUtil.format("已将 {} 个订阅设为分组「{}」", count, normalized));
    }

    @Auth
    @Operation(summary = "刷新全部订阅")
    @PostMapping("/refreshAll")
    public Result<Void> refreshAll() {
        // 未传Body, 刷新所有订阅；支持抢先周期任务 / 1 槽手动排队
        String msg = RssTask.submitManualRefresh(null);
        return Result.success(msg);
    }

    @Auth
    @Operation(summary = "刷新订阅")
    @PostMapping("/refreshAni")
    public Result<Void> refreshAni(@RequestBody IdDTO dto) {
        Optional<Ani> first = AniUtil.getAniList().stream()
                .filter(it -> it.getId().equals(dto.getId()))
                .findFirst();
        if (first.isEmpty()) {
            return Result.error("修改失败");
        }
        Ani downloadAni = first.get();
        // 单订阅刷新用「温和模式」：用户只想看这一部有没有新集，
        // 不应因此中断正在跑的整轮周期扫描（此前与"刷新全部"共用抢先路径）。
        String msg = RssTask.submitManualRefresh(List.of(downloadAni), false);
        return Result.success(msg);
    }

    @Auth
    @Operation(summary = "将RSS转换为订阅")
    @PostMapping("/rssToAni")
    public Result<Ani> rssToAni(@RequestBody RssToAniDTO dto) {
        try {
            Ani newAni = AniUtil.getAni(dto);
            return Result.success(newAni);
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
            return Result.error("RSS解析失败 {}", message);
        }
    }

    @Auth
    @Operation(summary = "强制下载(删除已有文件后重新下载)")
    @PostMapping("/forceDownload")
    public Result<String> forceDownload(@RequestBody Map<String, Object> body) {
        // body: { ani: {...}, infoHashes: ["hash1", ...] }
        Object aniObj = body == null ? null : body.get("ani");
        if (aniObj == null) {
            return Result.error("参数缺失: ani");
        }
        Ani ani = GsonStatic.fromJson(GsonStatic.toJson(aniObj), Ani.class);
        List<String> hashes = new ArrayList<>();
        Object hashObj = body == null ? null : body.get("infoHashes");
        if (hashObj instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    hashes.add(String.valueOf(o));
                }
            }
        }
        if (hashes.isEmpty()) {
            return Result.error("参数缺失: infoHashes");
        }
        // 重新拉取 RSS 匹配最新 item, 避免使用过期的预览数据
        List<Item> items = ItemsUtil.getItems(ani);
        int ok = 0;
        List<String> failedNames = new ArrayList<>();
        for (String hash : hashes) {
            Item match = items.stream()
                    .filter(it -> hash.equalsIgnoreCase(it.getInfoHash()))
                    .findFirst()
                    .orElse(null);
            if (match == null) {
                failedNames.add(hash);
                continue;
            }
            try {
                downloadService.forceDownloadItem(ani, match);
                ok++;
            } catch (Exception e) {
                log.error("强制下载失败 {}: {}", match.getReName(), ExceptionUtils.getMessage(e));
                failedNames.add(match.getReName());
            }
        }
        Result<String> result = Result.success("");
        if (failedNames.isEmpty()) {
            result.setMessage("已强制下载 " + ok + " 项, 将按正常流程处理(离线下载/重命名等)");
        } else {
            result.setMessage("已强制下载 " + ok + " 项, 失败 " + failedNames.size() + " 项");
        }
        return result;
    }

    @Auth
    @Operation(summary = "预览订阅")
    @PostMapping("/previewAni")
    public Result<Map<String, Object>> previewAni(@RequestBody Ani ani) {
        // F6-1：预览是读路径，走"尽力取读锁"。下载正持有写锁（分钟级）时不等它，
        // 直接读 LocalStateCache 的快照——点开预览被下载阻塞，比读到稍旧的数据糟糕得多。
        return AniLocks.callWithTryRead(ani, () -> previewAniLocked(ani));
    }

    /**
     * 预览订阅（调用方已持有该订阅读锁，或已明确接受"不持锁读快照"）
     */
    private Result<Map<String, Object>> previewAniLocked(Ani ani) {
        List<Item> items = ItemsUtil.getItems(ani);

        String downloadPath = downloadService.getDownloadPath(ani);

        // 「本地存在」的判定统一收敛到 DownloadService.applyLocalStates：
        // 目标路径下确实有这一集的视频文件（本地磁盘 / OpenList 网盘）才算"是"，
        // 而不是"本地有 .torrent 记录"——记录只代表曾经推过种子，网盘文件被删/移动/改名
        // 或种子从未落地时记录仍在，旧实现会把"不存在"谎报成"存在"。
        //
        // 判定分三种情形（详见 LocalState）：
        //   开启重命名 + 列举成功 → 按真实文件判定（是 / 否）
        //   开启重命名 + 列举失败 → 回退记录判定，标"存疑"（查询失败 ≠ 目录为空）
        //   未开启重命名         → 文件名不含 SxxExx 无从匹配，沿用旧逻辑（有记录即"是"）
        List<DownloadService.LocalState> states = downloadService.applyLocalStates(ani, items);

        List<Integer> omitList = ItemsUtil.omitList(ani, items);
        // 预览已拉 RSS：回写漏集缓存，供列表健康分使用。
        // 只落盘、不失效缓存：这里每预览一次就会走到，走 syncAniList() 会把下载路径索引、
        // 本地状态快照、媒体库缓存全部清空——预览恰恰是最需要缓存命中的入口。
        try {
            // 按 id 反查走 AniUtil 的 id 索引（P2-14），不再全表 stream 过滤
            Optional<Ani> live = AniUtil.findById(ani.getId());
            Ani target = live.orElse(ani);
            int omitCount = omitList == null ? 0 : omitList.size();
            Integer oldOmitCount = target.getOmitCount();
            Long oldCheckedAt = target.getOmitCheckedAt();
            long now = System.currentTimeMillis();
            SubscriptionHealth.rememberOmit(target, omitCount, now);
            /*
            只有「漏集数真的变了」或「落盘水位过期」才写盘。
            预览每次都改 omitCheckedAt，若不加这道闸门，内容比对永远认为"变了"，
            等于预览几次就写几次全量订阅文件（P2-1）。
            */
            boolean omitChanged = !Objects.equals(oldOmitCount, omitCount);
            boolean stale = oldCheckedAt == null || now - oldCheckedAt >= OMIT_PERSIST_INTERVAL_MS;
            if (live.isPresent() && (omitChanged || stale)) {
                AniUtil.syncStateOnly();
            }
        } catch (Exception e) {
            log.debug("回写漏集缓存失败: {}", e.getMessage());
        }

        // 预览专用：合集折叠聚合
        items = ItemsUtil.groupCollectionForPreview(items);

        // 洗版预览：取首个"确认未下载"的主 RSS 条目估算将删除内容。
        // 存疑（无法校验）与下载中的条目都必须排除——它们的文件很可能已经在盘上，
        // 拿它们去估算"将删除内容"会误导用户。
        List<Map<String, String>> washPreview = new ArrayList<>();
        try {
            Optional<Item> washItem = items.stream()
                    .filter(AniController::definitelyAbsent)
                    .filter(it -> Boolean.TRUE.equals(it.getMaster()) || it.getMaster() == null)
                    .findFirst();
            if (washItem.isEmpty()) {
                washItem = items.stream().filter(AniController::definitelyAbsent).findFirst();
            }
            if (washItem.isPresent()) {
                for (WashPreview.Candidate c : downloadService.previewStandbyDeletes(ani, washItem.get())) {
                    washPreview.add(Map.of(
                            "name", StrUtil.blankToDefault(c.name(), ""),
                            "kind", StrUtil.blankToDefault(c.kind(), ""),
                            "reason", StrUtil.blankToDefault(c.reason(), "")
                    ));
                }
            }
        } catch (Exception e) {
            log.debug("洗版预览失败: {}", e.getMessage());
        }

        // 健康分（非 BGM 评分）
        SubscriptionHealth.Score health = SubscriptionHealth.compute(ani, omitList == null ? 0 : omitList.size(), System.currentTimeMillis());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("downloadPath", downloadPath);
        map.put("items", items);
        map.put("omitList", omitList);
        map.put("localStateSummary", summarizeLocalStates(items, states));
        map.put("washPreview", washPreview);
        map.put("healthScore", health.score());
        map.put("healthLevel", health.level());
        map.put("healthReasons", health.reasons());
        return Result.success(map);
    }

    /**
     * 是否"确认不存在"：既没有真实文件，也没有存疑，且不在下载中。
     * <p>
     * 预览的「洗版估算」只应拿确认缺失的条目去算，存疑/下载中的条目文件很可能已在盘上。
     */
    private static boolean definitelyAbsent(Item item) {
        if (item == null) {
            return false;
        }
        return !Boolean.TRUE.equals(item.getHasDownloaded())
                && !Boolean.TRUE.equals(item.getHasDownloadedUnknown())
                && !Boolean.TRUE.equals(item.getDownloading());
    }

    /**
     * 「本地存在」三态计数，供前端诊断与验收断言（EXISTS + UNKNOWN + ABSENT = 条目总数）。
     * <p>
     * 额外给出 {@code inconsistent}：有种子记录但目标路径已确认没有文件。
     * 这类条目<b>只提示不自动清理</b>（F5-2）——记录是"曾经下过"的唯一线索。
     */
    private static Map<String, Integer> summarizeLocalStates(List<? extends Item> items,
                                                             List<DownloadService.LocalState> states) {
        int exists = 0;
        int unknown = 0;
        int absent = 0;
        if (states != null) {
            for (DownloadService.LocalState state : states) {
                if (state == DownloadService.LocalState.EXISTS) {
                    exists++;
                } else if (state == DownloadService.LocalState.UNKNOWN) {
                    unknown++;
                } else {
                    absent++;
                }
            }
        }
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("exists", exists);
        summary.put("unknown", unknown);
        summary.put("absent", absent);
        summary.put("inconsistent", DownloadService.countStaleTorrentRecords(items));
        return summary;
    }

    @Auth
    @Operation(summary = "失败下载队列")
    @PostMapping("/failedDownloadQueue")
    public Result<List<FailedDownloadQueue.FailedItem>> failedDownloadQueue() {
        return Result.success(FailedDownloadQueue.list());
    }

    @Auth
    @Operation(summary = "移除失败队列条目")
    @PostMapping("/failedDownloadQueueRemove")
    public Result<Void> failedDownloadQueueRemove(@RequestBody Map<String, Object> body) {
        String id = body == null || body.get("id") == null ? null : String.valueOf(body.get("id"));
        if (StrUtil.isBlank(id)) {
            return Result.error("id 不能为空");
        }
        boolean ok = FailedDownloadQueue.remove(id);
        return ok ? Result.success("已移除") : Result.error("条目不存在");
    }

    @Auth
    @Operation(summary = "清空失败队列")
    @PostMapping("/failedDownloadQueueClear")
    public Result<Void> failedDownloadQueueClear() {
        int n = FailedDownloadQueue.clear();
        return Result.success("已清空 " + n + " 条");
    }

    @Auth
    @Operation(summary = "重试失败队列条目（精确重下单条，不整订刷新）")
    @PostMapping("/failedDownloadQueueRetry")
    public Result<Void> failedDownloadQueueRetry(@RequestBody Map<String, Object> body) {
        String id = body == null || body.get("id") == null ? null : String.valueOf(body.get("id"));
        if (StrUtil.isBlank(id)) {
            return Result.error("id 不能为空");
        }
        Optional<FailedDownloadQueue.FailedItem> hit = FailedDownloadQueue.list().stream()
                .filter(i -> id.equals(i.getId()))
                .findFirst();
        if (hit.isEmpty()) {
            return Result.error("条目不存在");
        }
        FailedDownloadQueue.FailedItem failed = hit.get();
        try {
            // OpenList 可能长时间等待；后台执行，成功才 remove；失败保留/刷新队列
            FailedDownloadQueue.FailedItem snapshot = failed;
            ThreadUtil.execute(() -> {
                try {
                    downloadService.retryFailedItem(snapshot);
                } catch (Exception e) {
                    log.warn("精确重下失败 {}: {}", snapshot.getReName(), ExceptionUtils.getMessage(e));
                }
            });
            return Result.success("已提交精确重下");
        } catch (Exception e) {
            return Result.error("重试失败: " + ExceptionUtils.getMessage(e));
        }
    }

    @Auth
    @Operation(summary = "获取订阅的下载位置")
    @PostMapping("/downloadPath")
    public Result<Map<String, Object>> downloadPath(@RequestBody Ani ani) {
        String downloadPath = downloadService.getDownloadPath(ani);

        boolean change = false;
        Optional<Ani> first = AniUtil.getAniList().stream()
                .filter(it -> it.getId().equals(ani.getId()))
                .findFirst();
        if (first.isPresent()) {
            Ani oldAni = ObjectUtil.clone(first.get());
            // 只在名称改变时移动
            oldAni.setSeason(ani.getSeason());
            String oldDownloadPath = downloadService.getDownloadPath(oldAni);
            change = !downloadPath.equals(oldDownloadPath);
        }

        Map<String, Object> map = Map.of(
                "change", change,
                "downloadPath", downloadPath
        );
        return Result.success(map);
    }

    @Auth
    @Operation(summary = "导入订阅")
    @PostMapping("/importAni")
    public Result<Void> importAni(@RequestBody ImportAniDataDTO dto) {
        List<Ani> aniList = dto.getAniList();
        if (aniList.isEmpty()) {
            return Result.error("导入列表为空");
        }

        ImportAniDataDTO.Conflict conflict = dto.getConflict();

        for (Ani ani : aniList) {
            AniUtil.verify(ani);

            String title = ani.getTitle();
            int season = ani.getSeason();
            Optional<Ani> first = AniUtil.getAniList().stream()
                    .filter(it -> it.getTitle().equals(title) && it.getSeason() == season)
                    .findFirst();

            if (first.isEmpty()) {
                String image = ani.getImage();
                String cover = AniUtil.saveCover(image);
                ani.setCover(cover)
                        .setId(UUID.fastUUID().toString());
                AniUtil.getAniList().add(ani);
                AniUtil.invalidateIdIndex();
                continue;
            }

            if (conflict == ImportAniDataDTO.Conflict.SKIP) {
                log.info("存在冲突，已跳过 {} 第{}季", title, season);
                continue;
            }

            log.info("存在冲突，已替换 {} 第{}季", title, season);
            String image = ani.getImage();
            String cover = AniUtil.saveCover(image);
            ani.setCover(cover);

            String[] ignoreProperties = new String[]{"id", "currentEpisodeNumber", "lastDownloadTime"};
            BeanUtil.copyProperties(ani, first.get(), ignoreProperties);
        }

        syncAniList();
        return Result.success("导入成功");
    }

    @Auth
    @Operation(summary = "刷新封面")
    @PostMapping("/refreshCover")
    public Result<String> refreshCover(@RequestBody Ani ani) {
        String s = AniUtil.saveCover(ani.getImage(), true);
        return Result.success(r -> r.setData(s));
    }

}
