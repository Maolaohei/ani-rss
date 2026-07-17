package ani.rss.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * RSS 全局任务快照（任务管理器）
 */
@Data
@Accessors(chain = true)
@Schema(description = "RSS 任务状态")
public class RssJobStatus implements Serializable {
    @Schema(description = "是否占用全局锁/正在执行")
    private Boolean running;

    @Schema(description = "是否已请求取消")
    private Boolean cancelRequested;

    @Schema(description = "是否存在可取消项（全局取消按钮）")
    private Boolean canCancel;

    @Schema(description = "任务范围: idle/all/single/partial/starting")
    private String scope;

    @Schema(description = "当前订阅标题（单订时）")
    private String title;

    @Schema(description = "当前订阅 id（单订时）")
    private String aniId;

    @Schema(description = "开始时间戳 ms")
    private Long startedAt;

    @Schema(description = "已运行毫秒")
    private Long elapsedMs;

    @Schema(description = "最近一次任务完成时间戳 ms")
    private Long lastFinishedAt;

    @Schema(description = "最近一次任务耗时毫秒")
    private Long lastDurationMs;

    @Schema(description = "最近一次任务结果文案")
    private String lastResultMessage;

    @Schema(description = "最近一次任务标题")
    private String lastTitle;

    @Schema(description = "最近一次任务来源")
    private String lastSource;

    @Schema(description = "最近一次任务范围")
    private String lastScope;

    @Schema(description = "状态文案")
    private String message;

    @Schema(description = "当前 OpenList infoHash（若有）")
    private String currentHash;

    @Schema(description = "任务来源: periodic/manual")
    private String source;

    @Schema(description = "是否有待执行的手动刷新")
    private Boolean pending;

    @Schema(description = "待执行任务标题")
    private String pendingTitle;

    @Schema(description = "待执行任务范围")
    private String pendingScope;

    @Schema(description = "OpenList 是否正占用当前 hash（即使 RSS 调度空闲）")
    private Boolean openListBusy;

    @Schema(description = "是否支持 OpenList 残留扫描(OpenList/Alist)")
    private Boolean residualSupported;

    @Schema(description = "离线残留进行中数量")
    private Integer residualActiveCount;

    @Schema(description = "离线残留终态数量")
    private Integer residualTerminalCount;

    @Schema(description = "离线残留总数")
    private Integer residualTotalCount;

    @Schema(description = "残留快照扫描时间戳 ms")
    private Long residualScannedAt;

    @Schema(description = "是否正在清理残留")
    private Boolean residualCleaning;

    @Schema(description = "残留扫描/清理消息")
    private String residualMessage;

    @Schema(description = "残留任务样例(最多5条)")
    private List<String> residualSamples;

    @Schema(description = "可观察任务列表（running/pending/openlist/residual/last-finished）")
    private List<RssJobItem> tasks;
}
