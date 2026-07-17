package ani.rss.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

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
}
