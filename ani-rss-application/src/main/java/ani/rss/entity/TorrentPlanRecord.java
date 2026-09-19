package ani.rss.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 离线下载的「期望文件计划」快照（P4：重启恢复用）。
 * <p>
 * 为什么需要落盘：{@code TorrentUtil.cleanupOrphanPending()} 在启动时会把待完成标记
 * （{@code torrents/.pending/<hash>.torrent}）全部清掉，随后由下一轮 RSS 重新提交。
 * 但如果文件其实<b>早已下完</b>（离线任务在重启前就完成了、或重启期间自行完成），
 * 没有计划就只能等下一轮 RSS 扫目录反推——而这恰恰是"要的那几个文件齐没齐"判不准的地方。
 * 把计划连同落点信息存下来，启动后做一次"只读比对"，齐了就立刻归位并标记完成。
 * <p>
 * 不存任何凭据；只在有计划的离线任务存在期间存在，任务收尾即删除。
 */
@Data
@Accessors(chain = true)
@Schema(description = "离线下载期望文件计划快照")
public class TorrentPlanRecord implements Serializable {
    /**
     * 离线任务 infoHash（小写），也是快照文件名
     */
    @Schema(description = "infoHash")
    private String infoHash;

    /**
     * 订阅 id（用于找回订阅；订阅已删除则丢弃本快照）
     */
    @Schema(description = "订阅 id")
    private String aniId;

    /**
     * 最终下载目录（网盘路径）
     */
    @Schema(description = "下载目录")
    private String downloadPath;

    /**
     * 本次任务的临时目录名（下载目录下的一级子目录）
     */
    @Schema(description = "临时目录名")
    private String tempDirName;

    /**
     * 模板基名（日志/兜底命名用）
     */
    @Schema(description = "模板基名")
    private String finalRenameBase;

    /**
     * 计划条目：{@code title}=种子内相对路径，{@code reName}=最终文件名，{@code length}=字节数
     */
    @Schema(description = "计划条目")
    private List<Item> items;

    /**
     * 落盘时刻（用于过期清理，避免历史快照无限堆积）
     */
    @Schema(description = "落盘时刻")
    private Long createdAt;
}
