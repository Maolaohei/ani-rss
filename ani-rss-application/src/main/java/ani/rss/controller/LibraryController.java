package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.download.OfflineDownloader;
import ani.rss.download.OpenListApi;
import ani.rss.entity.Ani;
import ani.rss.entity.OpenListFileInfo;
import ani.rss.entity.PlayItem;
import ani.rss.entity.web.Result;
import ani.rss.enums.StringEnum;
import ani.rss.service.AniLocks;
import ani.rss.service.DownloadService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地媒体库浏览。
 * <p>
 * 此前只能"进某个订阅看它的选集"，没有全局视角回答"我库里到底有哪些番、占了多少空间"。
 * 本控制器扫描各订阅的下载目录，聚合出库视图。
 * <p>
 * OpenList/Alist 模式下下载目录是网盘虚拟路径，本地文件系统不可见，此时改走网盘 API
 * （{@code OfflineDownloader#listFilesStrict}）——否则这类用户的媒体库恒为空。
 * <p>
 * 扫描结果做 60 秒缓存（与 {@code DownloadService} 的下载路径索引同一思路）：
 * 大库全量扫描很慢，不能每次请求都扫。
 */
@Slf4j
@RestController
public class LibraryController extends BaseController {

    /**
     * 扫描缓存有效期（毫秒）
     */
    private static final long CACHE_TTL_MS = 60_000L;

    /**
     * 网盘扫描总预算（毫秒）。见 {@link #scan(boolean)}。
     */
    private static final long CLOUD_SCAN_BUDGET_MS = 10_000L;

    @Resource
    private DownloadService downloadService;

    @Resource
    private PlayController playController;

    /**
     * 库条目
     */
    public static class LibraryItem {
        private String aniId;
        private String title;
        private String image;
        private Integer season;
        private String subgroup;
        private String downloadPath;
        private boolean exists;
        private int videoCount;
        private long totalSize;
        private String formatSize;
        private Long lastModify;
        private boolean completed;
        /**
         * 内容来自网盘（OpenList/Alist）而非本地磁盘
         */
        private boolean cloud;
        /**
         * 本轮<b>未能确认</b>（网盘列举失败 / 超出扫描时间预算）。
         * <p>
         * 与 {@link #exists}=false 的区别：那个是"确认没有"，这个是"不知道"。
         * 网盘 API 全局限流 300ms/次且订阅多时必然有订阅排不进预算，
         * 若一并当成"不存在"，用户会看到媒体库大面积变空。
         */
        private boolean unknown;

        public String getAniId() {
            return aniId;
        }

        public LibraryItem setAniId(String aniId) {
            this.aniId = aniId;
            return this;
        }

        public String getTitle() {
            return title;
        }

        public LibraryItem setTitle(String title) {
            this.title = title;
            return this;
        }

        public String getImage() {
            return image;
        }

        public LibraryItem setImage(String image) {
            this.image = image;
            return this;
        }

        public Integer getSeason() {
            return season;
        }

        public LibraryItem setSeason(Integer season) {
            this.season = season;
            return this;
        }

        public String getSubgroup() {
            return subgroup;
        }

        public LibraryItem setSubgroup(String subgroup) {
            this.subgroup = subgroup;
            return this;
        }

        public String getDownloadPath() {
            return downloadPath;
        }

        public LibraryItem setDownloadPath(String downloadPath) {
            this.downloadPath = downloadPath;
            return this;
        }

        public boolean isExists() {
            return exists;
        }

        public LibraryItem setExists(boolean exists) {
            this.exists = exists;
            return this;
        }

        public int getVideoCount() {
            return videoCount;
        }

        public LibraryItem setVideoCount(int videoCount) {
            this.videoCount = videoCount;
            return this;
        }

        public long getTotalSize() {
            return totalSize;
        }

        public LibraryItem setTotalSize(long totalSize) {
            this.totalSize = totalSize;
            return this;
        }

        public String getFormatSize() {
            return formatSize;
        }

        public LibraryItem setFormatSize(String formatSize) {
            this.formatSize = formatSize;
            return this;
        }

        public Long getLastModify() {
            return lastModify;
        }

        public LibraryItem setLastModify(Long lastModify) {
            this.lastModify = lastModify;
            return this;
        }

        public boolean isCompleted() {
            return completed;
        }

        public LibraryItem setCompleted(boolean completed) {
            this.completed = completed;
            return this;
        }

        public boolean isCloud() {
            return cloud;
        }

        public LibraryItem setCloud(boolean cloud) {
            this.cloud = cloud;
            return this;
        }

        public boolean isUnknown() {
            return unknown;
        }

        public LibraryItem setUnknown(boolean unknown) {
            this.unknown = unknown;
            return this;
        }
    }

    public static class LibraryQuery {
        private String keyword;
        private Boolean onlyExisting;

        public String getKeyword() {
            return keyword;
        }

        public void setKeyword(String keyword) {
            this.keyword = keyword;
        }

        public Boolean getOnlyExisting() {
            return onlyExisting;
        }

        public void setOnlyExisting(Boolean onlyExisting) {
            this.onlyExisting = onlyExisting;
        }
    }

    private static volatile List<LibraryItem> CACHE = new CopyOnWriteArrayList<>();
    private static volatile long CACHE_AT = 0L;

    /**
     * 失效缓存（订阅增删改后调用）
     */
    public static void invalidate() {
        CACHE_AT = 0L;
    }

    @Auth
    @Operation(summary = "媒体库列表")
    @PostMapping("/library")
    public Result<Map<String, Object>> library(@RequestBody(required = false) LibraryQuery query) {
        List<LibraryItem> all = scan(false);
        String keyword = query == null ? null : StrUtil.trimToNull(query.getKeyword());
        boolean onlyExisting = query != null && Boolean.TRUE.equals(query.getOnlyExisting());

        List<LibraryItem> filtered = new ArrayList<>();
        for (LibraryItem item : all) {
            // 未确认的条目一并保留：它的文件可能就在盘上，把它从"只看已有"里摘掉
            // 正是"把未确认悄悄降级成不存在"，与预览的「存疑归入已下载」是同一原则
            if (onlyExisting && !item.isExists() && !item.isUnknown()) {
                continue;
            }
            if (keyword != null && !matches(item, keyword)) {
                continue;
            }
            filtered.add(item);
        }

        long totalSize = filtered.stream().mapToLong(LibraryItem::getTotalSize).sum();
        int totalVideos = filtered.stream().mapToInt(LibraryItem::getVideoCount).sum();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", filtered);
        data.put("total", filtered.size());
        data.put("totalSize", totalSize);
        data.put("formatTotalSize", FileUtils.formatSize(totalSize, true));
        data.put("totalVideos", totalVideos);
        data.put("scannedAt", CACHE_AT);
        data.put("cached", CACHE_AT > 0 && System.currentTimeMillis() - CACHE_AT < CACHE_TTL_MS);
        return Result.success(data);
    }

    @Auth
    @Operation(summary = "媒体库详情（订阅的本地剧集列表）")
    @PostMapping("/libraryDetail")
    public Result<List<PlayItem>> libraryDetail(@RequestBody Map<String, Object> body) {
        String aniId = body == null || body.get("aniId") == null ? null : String.valueOf(body.get("aniId"));
        if (StrUtil.isBlank(aniId)) {
            return Result.error("参数缺失: aniId");
        }
        Optional<Ani> aniOpt = AniUtil.getAniList().stream()
                .filter(a -> Objects.equals(a.getId(), aniId))
                .findFirst();
        if (aniOpt.isEmpty()) {
            return Result.error("订阅不存在");
        }
        Ani ani = aniOpt.get();
        // F6-1：详情同样是读路径，尽力取读锁，取不到就用快照
        return AniLocks.callWithTryRead(ani, () -> libraryDetailLocked(ani));
    }

    private Result<List<PlayItem>> libraryDetailLocked(Ani ani) {
        try {
            String downloadPath = downloadService.getDownloadPath(ani);
            File dir = new File(downloadPath);
            if (dir.exists()) {
                List<PlayItem> items = playController.getPlayItem(dir, new HashSet<>(), 0);
                items.sort(Comparator.comparingDouble(PlayItem::getEpisode));
                return Result.success(items);
            }
            // 网盘虚拟路径：本地文件系统不可见，改列网盘文件
            CloudScan cloud = scanCloud(downloadPath);
            if (cloud == null) {
                return Result.error("读取网盘目录失败，请稍后重试");
            }
            List<PlayItem> items = toPlayItems(cloud);
            items.sort(Comparator.comparingDouble(PlayItem::getEpisode));
            return Result.success(items);
        } catch (Exception e) {
            log.warn("读取媒体库详情失败 {}: {}", ani.getTitle(), ExceptionUtils.getMessage(e));
            return Result.error("读取失败: " + ExceptionUtils.getMessage(e));
        }
    }

    @Auth
    @Operation(summary = "强制刷新媒体库缓存")
    @PostMapping("/libraryRefresh")
    public Result<Void> libraryRefresh() {
        invalidate();
        scan(true);
        return Result.success("已重新扫描媒体库");
    }

    /**
     * 扫描媒体库（带 60 秒缓存）
     */
    private List<LibraryItem> scan(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && CACHE_AT > 0 && now - CACHE_AT < CACHE_TTL_MS) {
            return CACHE;
        }
        synchronized (LibraryController.class) {
            if (!force && CACHE_AT > 0 && System.currentTimeMillis() - CACHE_AT < CACHE_TTL_MS) {
                return CACHE;
            }
            List<LibraryItem> items = new ArrayList<>();
            // 网盘扫描总预算：网盘 API 全局限流 300ms/次且递归列举，订阅多时逐条查
            // 会把首屏拖到几十秒。超预算的订阅保持"未确认"（与改造前一致的"不存在"）。
            // 结果仍走 60 秒缓存，正常刷新不会重复付这个代价。
            long cloudDeadline = System.currentTimeMillis() + CLOUD_SCAN_BUDGET_MS;
            for (Ani ani : AniUtil.getAniList()) {
                try {
                    // F6-5 矩阵：媒体库是<b>批量</b>读路径，取「无订阅锁，仅缓存」。
                    // 原因：批量扫描会遍历全部订阅，若逐个 tryLock(300ms)，多订阅同时下载时
                    // 等待会线性叠加成秒级延迟；而这里读到的只是"目录里有几集"的展示数据，
                    // 半更新状态最多让计数短暂偏旧，不会像预览那样诱发"重复下载"这类写动作。
                    // 一致性由 LocalStateCache 的 TTL 快照兜底（scanOne 内部走同一套快照）。
                    items.add(scanOne(ani, cloudDeadline));
                } catch (Exception e) {
                    log.debug("扫描媒体库条目失败 {}: {}", ani.getTitle(), ExceptionUtils.getMessage(e));
                    // 扫描失败是"未确认"而非"确认没有"：标 exists=false 会让用户以为内容丢了
                    items.add(new LibraryItem()
                            .setAniId(ani.getId())
                            .setTitle(ani.getTitle())
                            .setImage(ani.getImage())
                            .setSeason(ani.getSeason())
                            .setSubgroup(ani.getSubgroup())
                            .setExists(false)
                            .setUnknown(true));
                }
            }
            if (System.currentTimeMillis() > cloudDeadline) {
                log.warn("媒体库网盘扫描超出时间预算 {}ms，部分订阅本轮未确认（下次扫描或点「重新扫描」可继续）",
                        CLOUD_SCAN_BUDGET_MS);
            }
            // 有内容的排前面，其次按最近更新倒序；未确认的排在"确认没有"之前，
            // 因为"不知道"比"确实没有"更值得用户注意（也提示可以点「重新扫描」再试）
            items.sort(Comparator
                    .comparingInt((LibraryItem i) -> i.getVideoCount() > 0 ? 0 : (i.isUnknown() ? 1 : 2))
                    .thenComparing(Comparator.comparingLong(
                            (LibraryItem i) -> i.getLastModify() == null ? 0L : i.getLastModify()).reversed()));
            CACHE = new CopyOnWriteArrayList<>(items);
            CACHE_AT = System.currentTimeMillis();
            return CACHE;
        }
    }

    private LibraryItem scanOne(Ani ani, long cloudDeadline) {
        String downloadPath = downloadService.getDownloadPath(ani);
        LibraryItem item = new LibraryItem()
                .setAniId(ani.getId())
                .setTitle(ani.getTitle())
                .setImage(ani.getImage())
                .setSeason(ani.getSeason())
                .setSubgroup(ani.getSubgroup())
                .setDownloadPath(downloadPath)
                .setCompleted(Boolean.TRUE.equals(ani.getCompleted()));

        File dir = new File(downloadPath);
        if (dir.exists()) {
            // 本地目录存在：沿用原有本地扫描，行为保持不变
            List<PlayItem> playItems = playController.getPlayItem(dir, new HashSet<>(), 0);
            long totalSize = 0L;
            long lastModify = 0L;
            for (PlayItem playItem : playItems) {
                if (playItem.getFormatSize() == null) {
                    continue;
                }
                Long size = sizeOf(playItem.getFilename());
                if (size != null) {
                    totalSize += size;
                }
                if (playItem.getLastModify() != null && playItem.getLastModify() > lastModify) {
                    lastModify = playItem.getLastModify();
                }
            }
            return item.setExists(true)
                    .setVideoCount(playItems.size())
                    .setTotalSize(totalSize)
                    .setFormatSize(FileUtils.formatSize(totalSize, true))
                    .setLastModify(lastModify == 0L ? null : lastModify);
        }

        // 本地目录不存在：离线网盘模式下下载目录是网盘虚拟路径，本地文件系统不可见，
        // 此前一律判为"不存在"，导致 OpenList 用户的媒体库恒空。改用 API 查真实文件。
        return applyCloudScan(item, scanCloud(downloadPath, cloudDeadline));
    }

    /**
     * 把网盘扫描结果落到展示字段上。
     * <p>
     * {@code cloud == null}（列举失败 / 超预算）必须与"目录确实没有视频"区分开：
     * 前者是"不知道"，后者才是"确认没有"。二者混为一谈会让网盘抖动或订阅稍多时
     * 媒体库大面积显示为 0 集。
     */
    static LibraryItem applyCloudScan(LibraryItem item, CloudScan cloud) {
        if (cloud == null) {
            return item.setExists(false).setUnknown(true);
        }
        if (cloud.videos.isEmpty()) {
            return item.setExists(false);
        }
        return item.setExists(true)
                .setCloud(true)
                .setVideoCount(cloud.videos.size())
                .setTotalSize(cloud.totalSize)
                .setFormatSize(FileUtils.formatSize(cloud.totalSize, true))
                .setLastModify(cloud.lastModify == 0L ? null : cloud.lastModify);
    }

    /**
     * 网盘扫描结果
     */
    static final class CloudScan {
        final List<OpenListFileInfo> videos;
        final List<OpenListFileInfo> allFiles;
        final long totalSize;
        final long lastModify;

        CloudScan(List<OpenListFileInfo> videos, List<OpenListFileInfo> allFiles, long totalSize, long lastModify) {
            this.videos = videos;
            this.allFiles = allFiles;
            this.totalSize = totalSize;
            this.lastModify = lastModify;
        }
    }

    /**
     * 与 {@code PlayController.getPlayItem} 保持一致的过小文件阈值（预告/样片不计入集数）
     */
    private static final long MIN_VIDEO_SIZE_BYTES = 20L * 1024 * 1024;

    /**
     * 扫描网盘目录（OpenList/Alist）。
     * <p>
     * 非离线网盘模式返回空结果；查询失败返回 {@code null} —— 调用方据此区分
     * "目录确实为空"（空列表）与"查询失败"（null），不把"查不到"当成"没有"。
     */
    static CloudScan scanCloud(String downloadPath) {
        return scanCloud(downloadPath, Long.MAX_VALUE);
    }

    static CloudScan scanCloud(String downloadPath, long cloudDeadline) {
        if (!(TorrentUtil.DOWNLOAD instanceof OfflineDownloader offline)) {
            return new CloudScan(List.of(), List.of(), 0L, 0L);
        }
        if (System.currentTimeMillis() > cloudDeadline) {
            log.debug("媒体库网盘扫描超预算，跳过 {}", downloadPath);
            return null;
        }
        List<OpenListFileInfo> files;
        try {
            files = offline.listFilesStrict(downloadPath);
        } catch (OpenListApi.OpenListDirNotFoundException e) {
            // 目录不存在 = 确认没有内容（订阅还没下载过 / 目录被清理），
            // 不是"未确认"。当成未确认会让新订阅永远显示「未确认」。
            log.debug("网盘目录不存在，媒体库视为空 {}", downloadPath);
            return new CloudScan(List.of(), List.of(), 0L, 0L);
        } catch (Exception e) {
            log.warn("列出网盘目录失败 {}: {}", downloadPath, ExceptionUtils.getMessage(e));
            return null;
        }

        List<OpenListFileInfo> videos = new ArrayList<>();
        List<OpenListFileInfo> allFiles = new ArrayList<>();
        long totalSize = 0L;
        long lastModify = 0L;
        for (OpenListFileInfo f : files) {
            if (Boolean.TRUE.equals(f.getIsDir())) {
                continue;
            }
            String name = f.getName();
            if (StrUtil.isBlank(name)) {
                continue;
            }
            allFiles.add(f);
            if (!FileUtils.isVideoFormat(name)) {
                continue;
            }
            if (f.getSize() != null && f.getSize() < MIN_VIDEO_SIZE_BYTES) {
                continue;
            }
            videos.add(f);
            if (f.getSize() != null) {
                totalSize += f.getSize();
            }
            if (f.getModified() != null && f.getModified().getTime() > lastModify) {
                lastModify = f.getModified().getTime();
            }
        }
        return new CloudScan(videos, allFiles, totalSize, lastModify);
    }

    /**
     * 网盘文件 → 播放项（仅用于媒体库详情展示：集数/文件名/大小/字幕数/修改时间）。
     * 网盘路径不是本地路径，filename 直接填网盘路径，播放页不消费本字段。
     */
    static List<PlayItem> toPlayItems(CloudScan cloud) {
        List<PlayItem> items = new ArrayList<>();
        for (OpenListFileInfo f : cloud.videos) {
            String name = f.getName();
            String path = StrUtil.isBlank(f.getPath()) ? name : f.getPath() + "/" + name;
            PlayItem playItem = new PlayItem()
                    .setFilename(path)
                    .setName(name)
                    .setTitle(name)
                    .setExtName(FileUtil.extName(name))
                    .setEpisode(1.0)
                    .setFormatSize(f.getSize() == null ? null : FileUtils.formatSize(f.getSize(), true))
                    .setLastModify(f.getModified() == null ? null : f.getModified().getTime())
                    .setSubtitles(cloudSubtitles(cloud.allFiles, name));
            if (ReUtil.contains(StringEnum.SEASON_REG, name)) {
                playItem.setTitle(ReUtil.get(StringEnum.SEASON_REG, name, 0))
                        .setEpisode(Double.parseDouble(ReUtil.get(StringEnum.SEASON_REG, name, 2)));
            }
            items.add(playItem);
        }
        return items;
    }

    /**
     * 网盘同目录下与视频同主名的字幕（命名规则与本地一致：{@code 主名.xxx.ass}）
     */
    private static List<PlayItem.Subtitles> cloudSubtitles(List<OpenListFileInfo> allFiles, String videoName) {
        String mainName = FileUtil.mainName(videoName);
        if (StrUtil.isBlank(mainName)) {
            return List.of();
        }
        String prefix = mainName.toLowerCase() + ".";
        List<PlayItem.Subtitles> subtitles = new ArrayList<>();
        for (OpenListFileInfo f : allFiles) {
            String subName = f.getName();
            if (StrUtil.isBlank(subName)) {
                continue;
            }
            String ext = FileUtil.extName(subName);
            // 浏览器仅支持 ass、srt
            if (!List.of("ass", "srt").contains(ext)) {
                continue;
            }
            if (subName.toLowerCase().startsWith(prefix)) {
                subtitles.add(new PlayItem.Subtitles().setName(subName));
            }
        }
        return subtitles;
    }

    private static Long sizeOf(String filename) {
        try {
            if (StrUtil.isBlank(filename)) {
                return null;
            }
            File file = new File(filename);
            return file.exists() ? file.length() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean matches(LibraryItem item, String keyword) {
        String lower = keyword.toLowerCase();
        return contains(item.getTitle(), lower)
                || contains(item.getSubgroup(), lower)
                || contains(item.getDownloadPath(), lower);
    }

    private static boolean contains(String value, String lowerKeyword) {
        return value != null && value.toLowerCase().contains(lowerKeyword);
    }
}
