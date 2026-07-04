package ani.rss.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 下载项
 */
@Data
@Accessors(chain = true)
@Schema(description = "下载项")
public class Item implements Serializable {
    /**
     * 标题
     */
    @Schema(description = "标题")
    private String title;

    /**
     * 重命名
     */
    @Schema(description = "重命名")
    private String reName;

    /**
     * 种子
     */
    @Schema(description = "种子")
    private String torrent;

    /**
     * infoHash
     */
    @Schema(description = "infoHash")
    private String infoHash;

    /**
     * 集数
     */
    @Schema(description = "集数")
    private Double episode;

    /**
     * 大小
     */
    @Schema(description = "大小")
    private String formatSize;

    /**
     * 大小
     */
    @Schema(description = "大小")
    private Long length;

    /**
     * 本地已存在
     */
    @Schema(description = "本地已存在")
    private Boolean local;

    /**
     * 主 rss
     */
    @Schema(description = "主 rss")
    private Boolean master;

    /**
     * 字幕组
     */
    @Schema(description = "字幕组")
    private String subgroup;

    /**
     * 发布时间
     */
    @Schema(description = "发布时间")
    private Date pubDate;

    /**
     * 集数范围 (合集展开后的完整集数列表)
     */
    @Schema(description = "集数范围")
    private List<Double> episodeRange;

    /**
     * 版本号 (v2, v3 等，用于洗版判断)
     */
    @Schema(description = "版本号")
    private Integer version;

    /**
     * 子集列表 (预览时合集折叠用)
     */
    @Schema(description = "子集列表")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private List<Item> children;
}
