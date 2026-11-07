package ani.rss.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 合集分段：一份种子里的一个「独立归属」——某一季、剧场版、OVA/特典。
 * <p>
 * 一个混合种子（如 S1 + S2 + 剧场版）会被识别成多个分段；每个分段最终对应
 * <b>一条订阅 + 一个落盘目录</b>。分段的判定全部基于种子内路径文本，
 * 不联网、不依赖 TMDB，允许用户在预览阶段改名/改季/改偏移后再执行。
 */
@Data
@Accessors(chain = true)
@Schema(description = "合集分段")
public class CollectionSegment implements Serializable {
    /**
     * 分段 id（仅本次请求内稳定，用于计划条目归属与前端编辑）
     */
    @Schema(description = "分段 id")
    private String id;

    /**
     * 展示名：第1季 / 剧场版 / OVA 等
     */
    @Schema(description = "展示名")
    private String name;

    /**
     * 类型：TV / MOVIE / SPECIAL
     */
    @Schema(description = "类型")
    private String kind;

    /**
     * 季（剧场版可为空）
     */
    @Schema(description = "季")
    private Integer season;

    /**
     * 集数偏移（默认 0，由用户按字幕组编号习惯自行调整）
     */
    @Schema(description = "集数偏移")
    private Integer offset;

    /**
     * 该分段使用的标题（剧场版默认取种子内目录名，用户可改）
     */
    @Schema(description = "标题")
    private String title;

    /**
     * 该分段在种子内的归属根目录，多个用换行分隔；空表示整份种子
     */
    @Schema(description = "归属根目录")
    private String scope;

    /**
     * 是否剧场版（电影式命名）
     */
    @Schema(description = "剧场版")
    private Boolean ova;

    /**
     * 下载位置（由服务端按模板解析后回填，也可由用户显式指定）
     */
    @Schema(description = "下载位置")
    private String downloadPath;

    /**
     * 预览结果
     */
    @Schema(description = "预览结果")
    private List<Item> items;

    /**
     * 本段集数集合（去重升序）
     */
    @Schema(description = "集数集合")
    private List<Double> episodeRange;

    /**
     * 视频文件数
     */
    @Schema(description = "视频数")
    private Integer videoCount;

    /**
     * 文件总字节数
     */
    @Schema(description = "总大小")
    private Long totalLength;
}
