package ani.rss.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * OpenList 离线残留预览条目（任务管理器展示）
 */
@Data
@Accessors(chain = true)
@Schema(description = "OpenList 残留预览条目")
public class ResidualPreviewItem implements Serializable {
    @Schema(description = "任务 id")
    private String id;

    @Schema(description = "任务名称")
    private String name;

    @Schema(description = "状态名")
    private String state;

    @Schema(description = "ACTIVE / TERMINAL")
    private String kind;

    @Schema(description = "进度 0-100")
    private Integer progress;

    @Schema(description = "总字节（字符串）")
    private String totalBytes;

    @Schema(description = "错误信息")
    private String error;

    @Schema(description = "是否为当前 RSS 等待中的 hash（清理时保护）")
    private Boolean protectedCurrent;

    @Schema(description = "一键清理时的动作说明")
    private String action;
}
