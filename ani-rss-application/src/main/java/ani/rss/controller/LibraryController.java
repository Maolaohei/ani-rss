package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.PlayItem;
import ani.rss.entity.web.Result;
import ani.rss.service.DownloadService;
import ani.rss.util.other.AniUtil;
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
            if (onlyExisting && !item.isExists()) {
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
        try {
            String downloadPath = downloadService.getDownloadPath(ani);
            List<PlayItem> items = playController.getPlayItem(new File(downloadPath), new HashSet<>(), 0);
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
            for (Ani ani : AniUtil.getAniList()) {
                try {
                    items.add(scanOne(ani));
                } catch (Exception e) {
                    log.debug("扫描媒体库条目失败 {}: {}", ani.getTitle(), ExceptionUtils.getMessage(e));
                    items.add(new LibraryItem()
                            .setAniId(ani.getId())
                            .setTitle(ani.getTitle())
                            .setImage(ani.getImage())
                            .setSeason(ani.getSeason())
                            .setSubgroup(ani.getSubgroup())
                            .setExists(false));
                }
            }
            // 有内容的排前面，其次按最近更新倒序
            items.sort(Comparator
                    .comparingInt((LibraryItem i) -> i.getVideoCount() > 0 ? 0 : 1)
                    .thenComparing(Comparator.comparingLong(
                            (LibraryItem i) -> i.getLastModify() == null ? 0L : i.getLastModify()).reversed()));
            CACHE = new CopyOnWriteArrayList<>(items);
            CACHE_AT = System.currentTimeMillis();
            return CACHE;
        }
    }

    private LibraryItem scanOne(Ani ani) {
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
        if (!dir.exists()) {
            return item.setExists(false);
        }
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
