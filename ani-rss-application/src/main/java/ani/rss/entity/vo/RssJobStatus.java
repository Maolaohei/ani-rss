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

    @Schema(description = "本轮订阅总数")
    private Integer subscriptionTotal;

    @Schema(description = "本轮正在处理的订阅数")
    private Integer subscriptionActive;

    @Schema(description = "本轮已处理的订阅数")
    private Integer subscriptionCompleted;

    @Schema(description = "本轮处理失败的订阅数")
    private Integer subscriptionFailed;

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

    @Schema(description = "当前离线任务标题")
    private String offlineTitle;

    @Schema(description = "当前离线进度 0-100")
    private Integer offlineProgress;

    @Schema(description = "当前离线状态")
    private String offlineState;

    @Schema(description = "离线截止时间戳 ms")
    private Long offlineDeadlineMs;

    @Schema(description = "离线剩余毫秒")
    private Long offlineEtaMs;

    @Schema(description = "失败队列条数")
    private Integer failedQueueCount;

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

    @Schema(description = "残留任务预览明细(最多30条)")
    private List<ResidualPreviewItem> residualItems;

    @Schema(description = "临时目录残留总数")
    private Integer tempDirResidualTotalCount;

    @Schema(description = "临时目录可清理数")
    private Integer tempDirResidualCleanableCount;

    @Schema(description = "临时目录保护数")
    private Integer tempDirResidualProtectedCount;

    @Schema(description = "临时目录保留数")
    private Integer tempDirResidualKeepCount;

    @Schema(description = "临时目录扫描时间戳 ms")
    private Long tempDirResidualScannedAt;

    @Schema(description = "是否正在清理临时目录")
    private Boolean tempDirResidualCleaning;

    @Schema(description = "临时目录残留消息")
    private String tempDirResidualMessage;

    @Schema(description = "临时目录残留预览(最多30条)")
    private List<ResidualPreviewItem> tempDirResidualItems;

    @Schema(description = "可观察任务列表（running/pending/openlist/residual/last-finished）")
    private List<RssJobItem> tasks;

    @Schema(description = "本轮失败的订阅明细（含归因与建议，最多 50 条）")
    private List<FailedSubscription> failedSubscriptions;

    /**
     * 订阅级失败明细。
     * <p>
     * 此前任务管理器只有一个「失败 N」计数，用户不知道是哪几个订阅、
     * 失败在哪一步；真实信息只存在于日志的 log.error 行里，而日志面板
     * 又不支持按关键词检索——诊断链在最关键的一环断开。
     */
    @Data
    @Accessors(chain = true)
    @Schema(description = "订阅级失败明细")
    public static class FailedSubscription implements Serializable {
        @Schema(description = "订阅 id")
        private String aniId;

        @Schema(description = "订阅标题")
        private String title;

        @Schema(description = "失败阶段: rss/download")
        private String stage;

        @Schema(description = "归因标题（已人话化）")
        private String humanizedMessage;

        @Schema(description = "下一步建议")
        private String suggestion;

        @Schema(description = "原始错误信息")
        private String rawMessage;

        @Schema(description = "失败时间戳 ms")
        private Long at;
    }
}
