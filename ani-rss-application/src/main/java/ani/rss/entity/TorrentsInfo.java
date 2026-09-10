package ani.rss.entity;

import ani.rss.commons.FileUtils;
import cn.hutool.core.util.NumberUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;
import java.util.function.Supplier;

/**
 * 种子信息
 */
@Data
@Accessors(chain = true)
@Schema(description = "种子信息")
public class TorrentsInfo implements Serializable {
    @Schema(description = "id")
    private String id;

    /**
     * hash
     */
    @Schema(description = "hash")
    private String hash;

    /**
     * 名称
     */
    @Schema(description = "名称")
    private String name;

    /**
     * 状态
     */
    @Schema(description = "状态")
    private State state;

    /**
     * 标签
     */
    @Schema(description = "标签")
    private List<String> tags;

    /**
     * 磁链
     */
    @Schema(description = "磁链")
    private String magnet;

    /**
     * 已下载的大小
     */
    @Schema(description = "已下载的大小")
    private Long completed;

    /**
     * 大小
     */
    @Schema(description = "大小")
    private Long size;

    /**
     * 进度
     */
    @Schema(description = "进度")
    private Double progress;

    /**
     * 大小
     */
    @Schema(description = "大小(字符串)")
    private String formatSize;

    /**
     * 时间
     */
    @Schema(description = "时间")
    private String dateStr;

    /**
     * 下载位置
     */
    @Schema(description = "下载位置")
    private String downloadDir;

    /**
     * 种子地址
     */
    @Schema(description = "种子地址")
    private String torrent;

    /**
     * 下载速度（字节/秒）
     * <p>
     * 用户判断“是在慢慢下还是已经卡死”的关键数字，此前未从下载器映射出来。
     */
    @Schema(description = "下载速度（字节/秒）")
    private Long downloadSpeed;

    /**
     * 预计剩余时间（毫秒；未知为 null）
     */
    @Schema(description = "预计剩余时间（毫秒）")
    private Long eta;

    /**
     * 下载速度（字符串，如 1.2 MB/s）
     */
    @Schema(description = "下载速度（字符串）")
    private String formatDownloadSpeed;

    /**
     * 剩余时间（字符串，如 3分20秒；未知为 "-"）
     */
    @Schema(description = "剩余时间（字符串）")
    private String formatEta;

    /**
     * 已下载大小（字符串）
     */
    @Schema(description = "已下载大小（字符串）")
    private String formatCompleted;

    /**
     * 连接数/做种数等附加计数（下载器支持时填充）
     */
    @Schema(description = "做种数")
    private Integer numSeeds;

    /**
     * 文件列表
     */
    @Schema(description = "文件列表")
    private Supplier<List<String>> files;

    public TorrentsInfo progress(long completed, long size) {
        if (size < 1) {
            size = 1;
            this.setProgress(0.0);
        } else {
            this.setProgress(
                    NumberUtil.round((completed * 1.0 / size) * 100, 2).doubleValue()
            );
        }

        String formatSize = FileUtils.formatSize(size, true);

        this.setCompleted(completed);
        this.setSize(size);
        this.setFormatSize(formatSize);
        this.setFormatCompleted(FileUtils.formatSize(Math.max(0, completed), true));
        return this;
    }

    /**
     * 填充速度与剩余时间；下载器不支持/未知时保持"-"，前端据此隐藏该行。
     */
    public TorrentsInfo speed(long downloadSpeed, Long etaMs) {
        this.setDownloadSpeed(downloadSpeed);
        this.setFormatDownloadSpeed(downloadSpeed > 0 ? FileUtils.formatSize(downloadSpeed, true) + "/s" : "-");
        this.setEta(etaMs);
        this.setFormatEta(etaMs != null && etaMs > 0 ? formatEtaText(etaMs) : "-");
        return this;
    }

    private static String formatEtaText(long etaMs) {
        long totalSeconds = etaMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return hours + "小时" + minutes + "分";
        }
        if (minutes > 0) {
            return minutes + "分" + seconds + "秒";
        }
        return seconds + "秒";
    }

    public enum State {
        /**
         * 校验恢复数据
         */
        checkingResumeData,
        /**
         * 正在检验磁盘文件
         */
        checkingDisk,
        /**
         * [F] 下载中
         */
        forcedDL,
        /**
         * 停滞中
         */
        stalledDL,
        /**
         * 已暂停
         */
        stoppedDL,
        pausedDL,
        /**
         * 队列中
         */
        queuedDL,
        /**
         * 下载中
         */
        downloading,
        /**
         * 做种中
         */
        stalledUP,
        /**
         * 错误
         */
        error,
        /**
         * 上传中
         */
        uploading,
        /**
         * 排队中(上传)
         */
        queuedUP,
        /**
         * 已完成
         */
        pausedUP,
        stoppedUP,
        /**
         * [F]元数据
         */
        forcedMetaDownload,
        /**
         * 元数据
         */
        metaDownload,
        /**
         * 缺失文件
         */
        missingFiles,
        /**
         * 未知
         */
        unknown
    }
}


