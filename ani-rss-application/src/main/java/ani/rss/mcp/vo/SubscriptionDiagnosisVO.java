package ani.rss.mcp.vo;

import ani.rss.util.other.DownloadHistory;
import ani.rss.util.other.FailedDownloadQueue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 订阅诊断结果（MCP / AI 助手用）
 * <p>
 * 把散落在健康分、漏集缓存、下载历史、失败队列里的线索聚合成一份"可读结论 + 可执行建议"，
 * 让 AI 能直接回答"这部为什么停更了"而不是让用户自己翻四个页面。
 */
@Data
@Accessors(chain = true)
@Schema(description = "订阅诊断结果")
public class SubscriptionDiagnosisVO implements Serializable {

    @Schema(description = "订阅 id")
    private String aniId;

    @Schema(description = "订阅标题")
    private String title;

    @Schema(description = "是否启用")
    private Boolean enable;

    @Schema(description = "健康分 0-100")
    private Integer healthScore;

    @Schema(description = "健康等级 good/warn/bad/paused/completed")
    private String healthLevel;

    @Schema(description = "健康原因")
    private List<String> healthReasons = new ArrayList<>();

    @Schema(description = "漏集数量")
    private Integer omitCount;

    @Schema(description = "当前集数")
    private Integer currentEpisodeNumber;

    @Schema(description = "总集数")
    private Integer totalEpisodeNumber;

    @Schema(description = "最近下载完成时间戳 ms")
    private Long lastDownloadTime;

    @Schema(description = "关联的失败队列条目")
    private List<FailedDownloadQueue.FailedItem> failedItems = new ArrayList<>();

    @Schema(description = "最近下载记录（最多 10 条）")
    private List<DownloadHistory.DownloadRecord> recentHistory = new ArrayList<>();

    @Schema(description = "可执行的下一步建议")
    private List<String> advice = new ArrayList<>();
}
