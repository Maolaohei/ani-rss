package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.entity.StandbyRss;
import ani.rss.entity.web.Result;
import ani.rss.service.DownloadService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ItemsUtil;
import cn.hutool.core.util.StrUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 手动搜索补种。
 * <p>
 * 场景：某集漏了 / 画质不满意 / 想换字幕组，但不想改订阅配置。
 * 本控制器把「主 RSS + 全部备用 RSS + 用户临时粘贴的 RSS」聚合成一次搜索，
 * 返回可下单的条目列表，下单直接复用 {@link DownloadService#forceDownloadItem}。
 * <p>
 * 底层件全部是既有的（{@code ItemsUtil.getItems} 解析、{@code itemDownloaded} 判重、
 * {@code forceDownloadItem} 下单），这里只做聚合与透出。
 */
@Slf4j
@RestController
public class ManualSearchController extends BaseController {

    /**
     * 单次搜索返回条目上限
     */
    private static final int MAX_ITEMS = 300;

    @Resource
    private DownloadService downloadService;

    /**
     * 搜索请求
     */
    public static class ManualSearchDTO {
        private String aniId;
        private String rssUrl;
        private String rssLabel;
        private String keyword;
        private Integer limit;
        private Boolean onlyMissing;

        public String getAniId() {
            return aniId;
        }

        public void setAniId(String aniId) {
            this.aniId = aniId;
        }

        public String getRssUrl() {
            return rssUrl;
        }

        public void setRssUrl(String rssUrl) {
            this.rssUrl = rssUrl;
        }

        public String getRssLabel() {
            return rssLabel;
        }

        public void setRssLabel(String rssLabel) {
            this.rssLabel = rssLabel;
        }

        public String getKeyword() {
            return keyword;
        }

        public void setKeyword(String keyword) {
            this.keyword = keyword;
        }

        public Integer getLimit() {
            return limit;
        }

        public void setLimit(Integer limit) {
            this.limit = limit;
        }

        public Boolean getOnlyMissing() {
            return onlyMissing;
        }

        public void setOnlyMissing(Boolean onlyMissing) {
            this.onlyMissing = onlyMissing;
        }
    }

    /**
     * 搜索条目（带来源标注与本地状态）
     */
    public static class SearchItem extends Item {
        private String sourceLabel;
        private String sourceUrl;

        public String getSourceLabel() {
            return sourceLabel;
        }

        public SearchItem setSourceLabel(String sourceLabel) {
            this.sourceLabel = sourceLabel;
            return this;
        }

        public String getSourceUrl() {
            return sourceUrl;
        }

        public SearchItem setSourceUrl(String sourceUrl) {
            this.sourceUrl = sourceUrl;
            return this;
        }
    }

    @Auth
    @Operation(summary = "手动搜索补种")
    @PostMapping("/manualSearch")
    public Result<Map<String, Object>> manualSearch(@RequestBody ManualSearchDTO dto) {
        Optional<Ani> aniOpt = resolveAni(dto == null ? null : dto.getAniId());

        // 解析条目需要一个"解析上下文"订阅（提供标题/偏移等）；只贴 RSS 时用临时上下文
        Ani context = aniOpt.orElseGet(() -> buildContext(dto == null ? null : dto.getRssUrl()));
        if (StrUtil.isBlank(context.getUrl()) && StrUtil.isBlank(dto == null ? null : dto.getRssUrl())) {
            return Result.error("请选择订阅或填写 RSS 地址");
        }

        String keyword = dto == null ? null : StrUtil.trimToNull(dto.getKeyword());
        int limit = dto == null || dto.getLimit() == null
                ? MAX_ITEMS
                : Math.max(1, Math.min(dto.getLimit(), MAX_ITEMS));
        boolean onlyMissing = dto != null && Boolean.TRUE.equals(dto.getOnlyMissing());

        List<Map<String, Object>> sources = new ArrayList<>();
        List<SearchItem> items = new ArrayList<>();

        for (Source source : collectSources(aniOpt.orElse(null), dto)) {
            Map<String, Object> sourceStat = new LinkedHashMap<>();
            sourceStat.put("label", source.label());
            sourceStat.put("url", source.url());
            try {
                List<Item> parsed = ItemsUtil.getItems(context, source.url(), source.label());
                int hit = 0;
                for (Item item : parsed) {
                    if (item == null) {
                        continue;
                    }
                    if (keyword != null && !matches(item, keyword)) {
                        continue;
                    }
                    SearchItem si = toSearchItem(item, source);
                    if (onlyMissing) {
                        boolean downloaded = isDownloaded(aniOpt.orElse(null), si);
                        if (downloaded) {
                            continue;
                        }
                    }
                    items.add(si);
                    hit++;
                    if (items.size() >= limit) {
                        break;
                    }
                }
                sourceStat.put("count", hit);
                sourceStat.put("error", null);
            } catch (Exception e) {
                String message = ExceptionUtils.getMessage(e);
                log.warn("手动搜索源失败 {} {}: {}", source.label(), source.url(), message);
                sourceStat.put("count", 0);
                sourceStat.put("error", message);
            }
            sources.add(sourceStat);
            if (items.size() >= limit) {
                break;
            }
        }

        // 标注本地状态（已下载/下载中），与预览页口径一致
        for (SearchItem item : items) {
            markLocalState(aniOpt.orElse(null), item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sources", sources);
        data.put("items", items);
        data.put("keyword", keyword);
        data.put("total", items.size());
        return Result.success(data);
    }

    /**
     * 手动下单：直接复用强制下载链路（会重新拉取 RSS 匹配最新条目）
     */
    @Auth
    @Operation(summary = "手动补种下单")
    @PostMapping("/manualDownload")
    public Result<String> manualDownload(@RequestBody Map<String, Object> body) {
        if (body == null || body.get("item") == null) {
            return Result.error("参数缺失: item");
        }
        Optional<Ani> aniOpt = resolveAni(body.get("aniId") == null ? null : String.valueOf(body.get("aniId")));
        if (aniOpt.isEmpty()) {
            return Result.error("订阅不存在");
        }
        Item item;
        try {
            item = GsonStatic.fromJson(GsonStatic.toJson(body.get("item")), Item.class);
        } catch (Exception e) {
            return Result.error("条目解析失败: " + ExceptionUtils.getMessage(e));
        }
        if (item == null || StrUtil.isBlank(item.getInfoHash())) {
            return Result.error("条目缺少 infoHash");
        }
        try {
            String message = downloadService.forceDownloadItem(aniOpt.get(), item);
            return Result.success(StrUtil.blankToDefault(message, "已提交下载"));
        } catch (Exception e) {
            log.error("手动补种失败 {}: {}", item.getReName(), ExceptionUtils.getMessage(e));
            return Result.error("下载失败: " + ExceptionUtils.getMessage(e));
        }
    }

    private Optional<Ani> resolveAni(String aniId) {
        if (StrUtil.isBlank(aniId)) {
            return Optional.empty();
        }
        return AniUtil.getAniList().stream()
                .filter(a -> Objects.equals(a.getId(), aniId))
                .findFirst();
    }

    /**
     * 只贴了 RSS 地址时的临时解析上下文（不落库、不参与调度）
     */
    private static Ani buildContext(String rssUrl) {
        return new Ani()
                .setId("__manual__")
                .setTitle("手动搜索")
                .setUrl(StrUtil.blankToDefault(rssUrl, ""))
                .setSeason(1)
                .setOffset(0)
                .setEnable(false)
                .setStandbyRssList(new ArrayList<>());
    }

    private record Source(String label, String url) {
    }

    /**
     * 聚合搜索源：主 RSS → 全部备用 RSS → 用户临时粘贴的 RSS
     */
    private static List<Source> collectSources(Ani ani, ManualSearchDTO dto) {
        List<Source> sources = new ArrayList<>();
        if (ani != null && StrUtil.isNotBlank(ani.getUrl())) {
            sources.add(new Source(StrUtil.blankToDefault(ani.getSubgroup(), "主 RSS"), ani.getUrl()));
        }
        if (ani != null && ani.getStandbyRssList() != null) {
            for (StandbyRss standby : ani.getStandbyRssList()) {
                if (standby == null || StrUtil.isBlank(standby.getUrl())) {
                    continue;
                }
                String label = StrUtil.blankToDefault(standby.getLabel(), "备用 RSS");
                boolean duplicated = sources.stream().anyMatch(s -> s.url().equals(standby.getUrl()));
                if (!duplicated) {
                    sources.add(new Source(label, standby.getUrl()));
                }
            }
        }
        if (dto != null && StrUtil.isNotBlank(dto.getRssUrl())) {
            String url = dto.getRssUrl().trim();
            boolean duplicated = sources.stream().anyMatch(s -> s.url().equals(url));
            if (!duplicated) {
                sources.add(new Source(StrUtil.blankToDefault(dto.getRssLabel(), "自定义 RSS"), url));
            }
        }
        return sources;
    }

    private static boolean matches(Item item, String keyword) {
        String lower = keyword.toLowerCase();
        return contains(item.getReName(), lower)
                || contains(item.getTitle(), lower)
                || contains(item.getSubgroup(), lower);
    }

    private static boolean contains(String value, String lowerKeyword) {
        return value != null && value.toLowerCase().contains(lowerKeyword);
    }

    private static SearchItem toSearchItem(Item item, Source source) {
        SearchItem si = new SearchItem();
        si.setTitle(item.getTitle());
        si.setReName(item.getReName());
        si.setTorrent(item.getTorrent());
        si.setInfoHash(item.getInfoHash());
        si.setEpisode(item.getEpisode());
        si.setFormatSize(item.getFormatSize());
        si.setLength(item.getLength());
        si.setSeeders(item.getSeeders());
        si.setPubDate(item.getPubDate());
        si.setMaster(item.getMaster());
        si.setSubgroup(StrUtil.blankToDefault(item.getSubgroup(), source.label()));
        si.setVersion(item.getVersion());
        si.setEpisodeRange(item.getEpisodeRange());
        si.setChildren(item.getChildren());
        si.setSourceLabel(source.label());
        si.setSourceUrl(source.url());
        return si;
    }

    private void markLocalState(Ani ani, SearchItem item) {
        if (ani == null) {
            return;
        }
        try {
            if (downloadService.itemDownloaded(ani, item, false)) {
                item.setHasDownloaded(true);
                return;
            }
            item.setHasDownloaded(false);
            item.setDownloading(false);
        } catch (Exception e) {
            log.debug("标记本地状态失败 {}: {}", item.getReName(), e.getMessage());
        }
    }

    private boolean isDownloaded(Ani ani, SearchItem item) {
        if (ani == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(downloadService.itemDownloaded(ani, item, false));
        } catch (Exception e) {
            return false;
        }
    }
}
