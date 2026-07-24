package ani.rss.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 任务管理器中的单条可观察任务槽。
 * <p>
 * 当前系统仍是全局单 RSS 调度，tasks 只是把 running / pending / OpenList 占用等槽位拆成列表展示。
 */
@Data
@Accessors(chain = true)
@Schema(description = "RSS 任务条目")
public class RssJobItem implements Serializable {
    @Schema(description = "条目 id: rss-running / rss-pending / openlist-current / residual-summary / last-finished")
    private String id;

    @Schema(description = "条目类型: rss_running / rss_pending / openlist_current / residual / last_finished")
    private String kind;

    @Schema(description = "状态: running / pending / busy / idle / done / canceling")
    private String status;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "消息")
    private String message;

    @Schema(description = "来源: periodic / manual / openlist / residual")
    private String source;

    @Schema(description = "范围: all / single / partial / starting / residual / offline")
    private String scope;

    @Schema(description = "关联 hash（若有）")
    private String hash;

    @Schema(description = "开始时间戳 ms")
    private Long startedAt;

    @Schema(description = "已运行毫秒")
    private Long elapsedMs;

    @Schema(description = "已处理/完成时间戳 ms")
    private Long processedAt;

    @Schema(description = "最近一次耗时毫秒")
    private Long durationMs;

    @Schema(description = "该条目是否可取消")
    private Boolean cancellable;

    @Schema(description = "进度 0-100")
    private Integer progress;

    @Schema(description = "预计剩余毫秒")
    private Long etaMs;
}
