package ani.rss.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 临时目录清理债务记录。
 * <p>
 * 背景：离线下载的收尾顺序是「创建临时目录 → 提交离线 → 等待完成 → 改名 → 移动媒体 → 删除临时目录」。
 * 最后一步删临时目录曾是"成功路径末尾的一脚删除"：失败只打一条 warn，随后照常判成功并销掉所有持久化痕迹。
 * 一旦这一步撞上网盘限流/冷却（或 115 的 990009 异步删除没落地），临时目录就永久留在盘上，
 * 媒体管理器同时扫到 {@code S1/S1E01.MKV} 与 {@code S1/S1E01/S1E01.MKV}，表现为重复集。
 * <p>
 * 这份记录就是那条"欠着的删除"：临时目录一创建就落盘（写前），媒体归位确认后转
 * {@link Phase#DELETE_PENDING}，<b>回读确认不存在</b>才删除记录。失败走独立重试通道，
 * 不参与下载成功/失败判定（否则一次限流会让整季重复归位）。
 */
@Data
@Accessors(chain = true)
@Schema(description = "临时目录清理债务")
public class TempDirCleanupRecord implements Serializable {

    /**
     * 债务来源：finalize=离线收尾自动登记；operator=用户在残留面板手工清理时登记。
     */
    public static final String SOURCE_FINALIZE = "FINALIZE";
    public static final String SOURCE_OPERATOR = "OPERATOR";

    @Schema(description = "唯一键：sha256(savePath + \\0 + tempDirName)")
    private String id;

    @Schema(description = "来源")
    private String source;

    @Schema(description = "最终目录（网盘路径）")
    private String savePath;

    @Schema(description = "临时目录名")
    private String tempDirName;

    @Schema(description = "临时目录完整路径")
    private String tempPath;

    @Schema(description = "订阅重命名结果")
    private String reName;

    @Schema(description = "种子 hash")
    private String infoHash;

    @Schema(description = "订阅 id")
    private String aniId;

    @Schema(description = "最终命名基名")
    private String finalRenameBase;

    /**
     * 本次已确认归位到最终目录顶层的文件名。
     * <p>
     * 这是自动删除的<b>安全门</b>：巡检时会逐个回读，缺一个就拒绝自动删除（转人工）。
     */
    @Schema(description = "已确认归位的文件名")
    private List<String> finalizedNames = new ArrayList<>();

    @Schema(description = "阶段")
    private Phase phase = Phase.LEASED;

    @Schema(description = "已尝试次数")
    private int attempts;

    @Schema(description = "下次可尝试时间戳")
    private long nextAttemptAt;

    @Schema(description = "最近一次失败原因")
    private String lastError;

    @Schema(description = "创建时间")
    private Long createdAt;

    @Schema(description = "更新时间")
    private Long updatedAt;

    public enum Phase {
        /**
         * 临时目录已创建（下载尚未归位）。只作痕迹保留，巡检不会去删——
         * 此时删掉会毁掉正在下载或待重试归位的产物。
         */
        LEASED,
        /**
         * 媒体已归位并确认，等删临时目录。
         */
        DELETE_PENDING,
        /**
         * 现场不符预期，停止自动重试，等人工处理。
         */
        NEEDS_HUMAN
    }
}
