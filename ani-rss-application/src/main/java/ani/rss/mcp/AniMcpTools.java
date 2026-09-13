package ani.rss.mcp;

import ani.rss.controller.AniController;
import ani.rss.controller.RssJobController;
import ani.rss.entity.*;
import ani.rss.entity.dto.AniBTQueryDTO;
import ani.rss.entity.dto.RssToAniDTO;
import ani.rss.entity.vo.RssJobStatus;
import ani.rss.exception.ResultException;
import ani.rss.mcp.dto.ListSubscriptionDTO;
import ani.rss.mcp.dto.SearchMikanDTO;
import ani.rss.mcp.vo.SubscriptionDiagnosisVO;
import ani.rss.mcp.vo.SubscriptionItemsPreviewVO;
import ani.rss.service.AniBTService;
import ani.rss.service.AnimeGardenService;
import ani.rss.service.DownloadService;
import ani.rss.service.MikanService;
import ani.rss.task.RssTask;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.DownloadHistory;
import ani.rss.util.other.FailedDownloadQueue;
import ani.rss.util.other.ItemsUtil;
import ani.rss.util.other.SubscriptionHealth;
import cn.hutool.core.util.ObjectUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Component
public class AniMcpTools {

    @Resource
    private AniController aniController;

    @Resource
    private RssJobController rssJobController;

    @Resource
    private DownloadService downloadService;

    @Resource
    private MikanService mikanService;

    @Resource
    private AniBTService aniBTService;

    @Resource
    private AnimeGardenService animeGardenService;

    @McpTool(
            name = "list_subscriptions",
            description = "列出现有 ANI-RSS 订阅，可按启用状态过滤",
            annotations = @McpTool.McpAnnotations(
                    title = "订阅列表",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public List<Ani> listSubscriptions(@McpToolParam ListSubscriptionDTO dto) {
        Boolean enabled = dto.getEnabled();
        return AniUtil.getAniList().stream()
                .filter(ani -> Objects.isNull(enabled) || enabled.equals(ani.getEnable()))
                .toList();
    }

    @McpTool(
            name = "search_mikan",
            description = "按关键词搜索 Mikan 番剧，可传入季度条件",
            annotations = @McpTool.McpAnnotations(
                    title = "搜索 Mikan",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true
            )
    )
    public Mikan searchMikan(@McpToolParam SearchMikanDTO dto) {
        String text = dto.getText();
        Mikan.Season season = dto.getSeason();
        text = ObjectUtil.defaultIfNull(text, "");
        season = ObjectUtil.defaultIfNull(season, new Mikan.Season());
        return mikanService.list(text, season);
    }

    @McpTool(
            name = "search_anibt",
            description = "搜索 AniBT 番剧",
            annotations = @McpTool.McpAnnotations(
                    title = "搜索 AniBT",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true
            )
    )
    public AniBT searchAniBT() {
        return aniBTService.list(new AniBTQueryDTO());
    }

    @McpTool(
            name = "search_anime_garden",
            description = "搜索 AnimeGarden 番剧列表",
            annotations = @McpTool.McpAnnotations(
                    title = "搜索 AnimeGarden",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true
            )
    )
    public List<AnimeGarden.Week> searchAnimeGarden() {
        return animeGardenService.list("");
    }

    @McpTool(
            name = "get_mikan_groups",
            description = "根据 Mikan 番剧页面 URL 获取字幕组 RSS",
            annotations = @McpTool.McpAnnotations(
                    title = "获取 Mikan 字幕组",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true
            )
    )
    public List<Mikan.Group> getMikanGroups(
            @McpToolParam(description = "蜜柑（Mikan）番剧页面 URL", required = true)
            String url
    ) {
        return mikanService.getGroups(url);
    }

    @McpTool(
            name = "get_anibt_groups",
            description = "根据 AniBT BGM ID 获取字幕组 RSS",
            annotations = @McpTool.McpAnnotations(
                    title = "获取 AniBT 字幕组",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true
            )
    )
    public List<AniBT.Group> getAniBTGroups(
            @McpToolParam(description = "BGM 番剧 ID", required = true)
            String bgmId
    ) {
        return aniBTService.getGroups(bgmId);
    }

    @McpTool(
            name = "get_anime_garden_groups",
            description = "根据 AnimeGarden BGM ID 获取字幕组 RSS",
            annotations = @McpTool.McpAnnotations(
                    title = "获取 AnimeGarden 字幕组",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true
            )
    )
    public List<AnimeGarden.Group> getAnimeGardenGroups(
            @McpToolParam(description = "BGM 番剧 ID")
            String bgmId
    ) {
        return animeGardenService.group(bgmId);
    }

    @McpTool(
            name = "preview_subscription_items",
            description = "预览某个 RSS 订阅最终会命中的原始剧集条目",
            annotations = @McpTool.McpAnnotations(
                    title = "预览订阅条目",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true
            )
    )
    public SubscriptionItemsPreviewVO previewSubscriptionItems(@McpToolParam RssToAniDTO dto) {
        try {
            Ani ani = AniUtil.getAni(dto);
            List<Item> items = ItemsUtil.getItems(ani);
            return new SubscriptionItemsPreviewVO(ani, items);
        } catch (Exception e) {
            throw mcpException("RSS解析失败", e);
        }
    }

    @McpTool(
            name = "add_subscription",
            description = "添加一个 ANI-RSS 订阅。需要预览命中条目时请先调用 preview_subscription_items",
            annotations = @McpTool.McpAnnotations(
                    title = "添加订阅",
                    readOnlyHint = false,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = false
            )
    )
    public Ani addSubscription(@McpToolParam RssToAniDTO dto) {
        try {
            Ani ani = AniUtil.getAni(dto);
            aniController.addAni(ani);
            return ani;
        } catch (Exception e) {
            throw mcpException("创建订阅失败", e);
        }
    }

    private ResultException mcpException(String action, Exception e) {
        log.error(e.getMessage(), e);
        return ResultException.exception(action);
    }

    // ==================== 写操作（补齐"助手只长了一只手"的缺口） ====================

    @McpTool(
            name = "set_subscription_enabled",
            description = "启用或禁用某个订阅。禁用后不再扫描与下载，但保留订阅配置与已下载文件。",
            annotations = @McpTool.McpAnnotations(
                    title = "启用/禁用订阅",
                    readOnlyHint = false,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public Ani setSubscriptionEnabled(
            @McpToolParam(description = "订阅 id", required = true) String aniId,
            @McpToolParam(description = "true 启用，false 禁用", required = true) Boolean enable
    ) {
        Ani ani = requireAni(aniId);
        ani.setEnable(enable);
        AniUtil.sync();
        log.info("MCP 修改订阅启用状态 {} => {}", ani.getTitle(), enable);
        return ani;
    }

    @McpTool(
            name = "refresh_subscription",
            description = "立即刷新单个订阅（温和模式，不会中断正在进行的整轮扫描），用于检查是否有新集。",
            annotations = @McpTool.McpAnnotations(
                    title = "刷新订阅",
                    readOnlyHint = false,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = false
            )
    )
    public String refreshSubscription(
            @McpToolParam(description = "订阅 id", required = true) String aniId
    ) {
        Ani ani = requireAni(aniId);
        return RssTask.submitManualRefresh(java.util.List.of(ani), false);
    }

    @McpTool(
            name = "get_task_status",
            description = "获取当前 RSS 调度与离线任务的实时状态（运行中/排队中/失败队列/上一轮结果）。",
            annotations = @McpTool.McpAnnotations(
                    title = "任务状态",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public RssJobStatus getTaskStatus() {
        return RssTask.getJobStatus();
    }

    @McpTool(
            name = "list_failed_items",
            description = "列出失败下载队列（含人话化原因与建议），用于判断哪些集需要补种。",
            annotations = @McpTool.McpAnnotations(
                    title = "失败队列",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public java.util.List<FailedDownloadQueue.FailedItem> listFailedItems() {
        return FailedDownloadQueue.list();
    }

    @McpTool(
            name = "retry_failed_item",
            description = "对失败队列中的某一条执行精确重下（不触发整订刷新）。",
            annotations = @McpTool.McpAnnotations(
                    title = "重试失败条目",
                    readOnlyHint = false,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = false
            )
    )
    public String retryFailedItem(
            @McpToolParam(description = "失败队列条目 id（可由 list_failed_items 获取）", required = true) String id
    ) {
        FailedDownloadQueue.FailedItem failed = FailedDownloadQueue.list().stream()
                .filter(i -> Objects.equals(i.getId(), id))
                .findFirst()
                .orElseThrow(() -> ResultException.exception("失败条目不存在"));
        return downloadService.retryFailedItem(failed);
    }

    @McpTool(
            name = "cancel_rss_job",
            description = "取消当前正在进行的 RSS 任务。注意：OpenList/AList 会同时清理远端离线占用；qBittorrent/Transmission/Aria2 只停止 RSS 推进，不会删除远端种子。",
            annotations = @McpTool.McpAnnotations(
                    title = "取消当前任务",
                    readOnlyHint = false,
                    destructiveHint = true,
                    idempotentHint = false,
                    openWorldHint = false
            )
    )
    public RssJobStatus cancelRssJob() {
        return rssJobController.rssJobCancel().getData();
    }

    @McpTool(
            name = "cancel_rss_item",
            description = "取消当前 RSS 任务中的单个条目（按任务条目 id）。",
            annotations = @McpTool.McpAnnotations(
                    title = "取消单条任务",
                    readOnlyHint = false,
                    destructiveHint = true,
                    idempotentHint = false,
                    openWorldHint = false
            )
    )
    public RssJobStatus cancelRssItem(
            @McpToolParam(description = "任务条目 id（可由 get_task_status 的 tasks[].id 获取）", required = true) String itemId
    ) {
        return rssJobController.rssJobCancelItem(java.util.Map.of("id", itemId)).getData();
    }

    // ==================== AI 诊断 ====================

    @McpTool(
            name = "diagnose_subscription",
            description = "诊断单个订阅的健康状况：健康分与原因、漏集数、最近下载记录、关联的失败条目，并给出可执行的下一步建议。用于回答“这部为什么停更了/为什么没下到”这类问题。",
            annotations = @McpTool.McpAnnotations(
                    title = "诊断订阅",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public ani.rss.mcp.vo.SubscriptionDiagnosisVO diagnoseSubscription(
            @McpToolParam(description = "订阅 id", required = true) String aniId
    ) {
        Ani ani = requireAni(aniId);
        boolean omitOn = Boolean.TRUE.equals(ConfigUtil.CONFIG.getOmit());
        int omitCount = SubscriptionHealth.cachedOmitCount(ani, omitOn);
        SubscriptionHealth.Score score = SubscriptionHealth.compute(ani, omitCount, System.currentTimeMillis());

        List<FailedDownloadQueue.FailedItem> failed = FailedDownloadQueue.list().stream()
                .filter(i -> Objects.equals(i.getAniId(), aniId))
                .toList();

        List<DownloadHistory.DownloadRecord> history = DownloadHistory.query(aniId, null, null, 10);

        List<String> advice = new ArrayList<>();
        if (Boolean.FALSE.equals(ani.getEnable())) {
            advice.add("订阅当前处于禁用状态，不会自动扫描；如需继续追更请启用它。");
        }
        if (omitCount > 0) {
            advice.add("存在 " + omitCount + " 集缺失，可用 refresh_subscription 触发一次补扫。");
        }
        if (!failed.isEmpty()) {
            advice.add("有 " + failed.size() + " 条失败记录，可用 list_failed_items 查看原因，再 retry_failed_item 精确重下。");
        }
        if (history.isEmpty()) {
            advice.add("最近没有下载记录；若刚添加订阅，首次扫描可能尚未完成。");
        }
        Long lastDownloadTime = ani.getLastDownloadTime();
        if (lastDownloadTime != null && lastDownloadTime > 0) {
            long days = (System.currentTimeMillis() - lastDownloadTime) / (24 * 60 * 60 * 1000L);
            if (days >= 7) {
                advice.add("已 " + days + " 天没有新下载，可能是番剧停更、RSS 源失效或字幕组更换。");
            }
        }
        if (advice.isEmpty()) {
            advice.add("各项指标正常，暂无需干预。");
        }

        return new ani.rss.mcp.vo.SubscriptionDiagnosisVO()
                .setAniId(aniId)
                .setTitle(ani.getTitle())
                .setEnable(ani.getEnable())
                .setHealthScore(score.score())
                .setHealthLevel(score.level())
                .setHealthReasons(score.reasons())
                .setOmitCount(omitCount)
                .setCurrentEpisodeNumber(ani.getCurrentEpisodeNumber())
                .setTotalEpisodeNumber(ani.getTotalEpisodeNumber())
                .setLastDownloadTime(lastDownloadTime)
                .setFailedItems(failed)
                .setRecentHistory(history)
                .setAdvice(advice);
    }

    /**
     * 取订阅，不存在直接抛出可读错误
     */
    private Ani requireAni(String aniId) {
        if (aniId == null || aniId.isBlank()) {
            throw ResultException.exception("订阅 id 不能为空");
        }
        return AniUtil.getAniList().stream()
                .filter(a -> Objects.equals(a.getId(), aniId))
                .findFirst()
                .orElseThrow(() -> ResultException.exception("订阅不存在"));
    }
}
