package ani.rss.mcp;

import ani.rss.commons.GsonStatic;
import ani.rss.controller.AniController;
import ani.rss.controller.RssJobController;
import ani.rss.entity.*;
import ani.rss.entity.dto.AniBTQueryDTO;
import ani.rss.entity.dto.RssToAniDTO;
import ani.rss.entity.vo.RssJobStatus;
import ani.rss.exception.ResultException;
import ani.rss.mcp.dto.DeleteSubscriptionDTO;
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
import java.util.Map;
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

    /**
     * 把 RSS 参数解析成完整订阅对象（#744）。
     * <p>
     * 拆成单独一步的理由：{@link RssToAniDTO} 只描述"从哪个 RSS 建订阅"，
     * 而后续预览/添加需要的是解析后的完整 {@link Ani}。若直接让模型拼 {@code Ani}，
     * 实体的空字段会被 MCP Schema 当成必填，调用几乎必然失败。
     * 由服务端解析一次、再由模型带着完整对象走后续工具，是最稳的链路。
     *
     * @param dto RSS 订阅参数
     * @return 已补全番剧信息的订阅
     */
    @McpTool(
            name = "rss_to_subscription",
            description = "将 RSS 地址解析并转换为完整的 ANI-RSS 订阅对象。请先调用此工具，再将返回的订阅对象传给 preview_subscription_items 或 add_subscription",
            annotations = @McpTool.McpAnnotations(
                    title = "RSS 转订阅",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true
            )
    )
    public Ani rssToSubscription(@McpToolParam RssToAniDTO dto) {
        try {
            return AniUtil.getAni(dto);
        } catch (Exception e) {
            throw mcpException("RSS转换失败", e);
        }
    }

    @McpTool(
            name = "preview_subscription_items",
            description = "预览完整订阅对象最终会命中的原始剧集条目。请先调用 rss_to_subscription",
            annotations = @McpTool.McpAnnotations(
                    title = "预览订阅条目",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true
            )
    )
    public SubscriptionItemsPreviewVO previewSubscriptionItems(
            @McpToolParam(description = "由 rss_to_subscription 返回的完整订阅对象")
            Map<String, Object> subscription
    ) {
        try {
            Ani ani = mapToAni(subscription);
            List<Item> items = ItemsUtil.getItems(ani);
            return new SubscriptionItemsPreviewVO(ani, items);
        } catch (Exception e) {
            throw mcpException("RSS解析失败", e);
        }
    }

    @McpTool(
            name = "add_subscription",
            description = "添加一个完整的 ANI-RSS 订阅对象。请先调用 rss_to_subscription，可选调用 preview_subscription_items",
            annotations = @McpTool.McpAnnotations(
                    title = "添加订阅",
                    readOnlyHint = false,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = false
            )
    )
    public Ani addSubscription(
            @McpToolParam(description = "由 rss_to_subscription 返回的完整订阅对象")
            Map<String, Object> subscription
    ) {
        try {
            Ani ani = mapToAni(subscription);
            // MCP 侧不替用户做"覆盖已有订阅"的决定，冲突时直接报错由调用方确认
            aniController.addAni(ani, false);
            return ani;
        } catch (Exception e) {
            throw mcpException("创建订阅失败", e);
        }
    }

    private ResultException mcpException(String action, Exception e) {
        log.error(e.getMessage(), e);
        return ResultException.exception(action);
    }

    /**
     * MCP 入参（{@code Map<String, Object>}）转实体。
     * <p>
     * 走 Gson 而不是 BeanUtil：字段名与 {@code @SerializedName} 别名、
     * 日期适配器（{@code DateAdapter}）等语义才能和落盘/接口完全一致。
     */
    private Ani mapToAni(Map<String, Object> map) {
        return GsonStatic.fromJson(GsonStatic.toJson(map), Ani.class);
    }

    // ==================== 写操作（补齐"助手只长了一只手"的缺口） ====================

    @McpTool(
            name = "edit_subscription",
            description = "编辑一个 ANI-RSS 订阅。请先调用 list_subscriptions 获取完整订阅对象，保留 id 并修改需要调整的字段后传入",
            annotations = @McpTool.McpAnnotations(
                    title = "编辑订阅",
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public Ani editSubscription(
            @McpToolParam(description = "修改后的完整订阅对象，id 必须对应现有订阅")
            Map<String, Object> subscription
    ) {
        try {
            // 用 Map 接收，避免实体空字段被 MCP Schema 声明为必填
            Ani ani = mapToAni(subscription);
            aniController.setAni(ani);
            log.info("MCP 编辑订阅 {} {}", ani.getId(), ani.getTitle());
            return ani;
        } catch (Exception e) {
            throw mcpException("编辑订阅失败", e);
        }
    }

    @McpTool(
            name = "delete_subscription",
            description = "删除一个或多个 ANI-RSS 订阅。deleteFiles 为 true 时还会删除下载器任务和本地视频文件",
            annotations = @McpTool.McpAnnotations(
                    title = "删除订阅",
                    openWorldHint = false
            )
    )
    public List<String> deleteSubscription(@McpToolParam DeleteSubscriptionDTO dto) {
        try {
            List<String> ids = dto.getIds();
            boolean deleteFiles = Boolean.TRUE.equals(dto.getDeleteFiles());
            aniController.deleteAni(ids, deleteFiles);
            log.info("MCP 删除订阅 {} deleteFiles={}", ids, deleteFiles);
            return ids;
        } catch (Exception e) {
            throw mcpException("删除订阅失败", e);
        }
    }

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
