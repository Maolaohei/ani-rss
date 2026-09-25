package ani.rss.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
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
     * 做种数（RSS 源提供时透出；未提供为 null）
     */
    @Schema(description = "做种数")
    private Integer seeders;

    /**
     * 本地已存在
     * <p>
     * 语义：<b>目标路径下确实存在这一集的视频文件</b>（本地磁盘或 OpenList 网盘）。
     * 不再以"本地 .torrent 记录是否存在"为判据——记录只代表曾经推过种子，
     * 网盘文件被删/移动/改名或种子从未落地时记录仍在，会把"不存在"谎报成"存在"。
     * <p>
     * 例外：<b>未开启重命名</b>时文件名不含 {@code SxxExx}，无法按季/集核对真实文件，
     * 此时退回旧逻辑——本地有种子记录即视为"存在"（与改造前行为一致）。
     * 需要区分"真的校验过"与"回退判定"的场景请改用
     * {@link #hasDownloadedUnknown} 或 {@code DownloadService.LocalState}。
     */
    @Schema(description = "本地已存在")
    private Boolean hasDownloaded;

    /**
     * 本地存在存疑：<b>开启</b>了重命名、但真实文件校验本身失败（网盘列举异常）时，
     * 回退种子记录判定得到的结果。
     * <p>
     * "查询失败"与"目录确实为空"必须区分开：前者不能断言文件不存在，故只标"存疑"——
     * 既不谎报"是"，也不把原本判为存在的条目直接降级成"否"（降级会触发重复下载）。
     */
    @Schema(description = "本地存在存疑")
    private Boolean hasDownloadedUnknown;

    /**
     * 本地种子记录存在（{@code configDir/torrents} 下的 .torrent/.txt 缓存）。
     * <p>
     * 与 {@link #hasDownloaded} 的区别：记录只代表"曾经推过这个种子"，不代表文件已落地。
     * 「删除种子」按钮要的是本字段（有没有缓存可删），而不是"文件在不在"。
     */
    @Schema(description = "本地种子记录存在")
    private Boolean hasTorrentRecord;

    /**
     * 正在下载中（离线提交后存在 .pending 记录 / 下载器队列中存在）
     * <p>
     * 此前只有 hasDownloaded 一个布尔，导致"正在离线下载的那一集"在预览里
     * 显示为"本地存在：否"，与任务管理器的"离线处理中 45%"直接矛盾，
     * 用户会误判为没在下而重复点强制下载。
     */
    @Schema(description = "正在下载中")
    private Boolean downloading;

    /**
     * 下载中状态的补充说明（如 "OpenList 离线处理中 45%"）
     */
    @Schema(description = "下载中状态说明")
    private String downloadingState;

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
    @Nullable
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
     * 本条目的「来源 RSS 集数偏移」。
     * <p>
     * 一个订阅可以挂多条备用 RSS，<b>每条有自己独立的集数偏移</b>：同一部番，Baha 源按累计
     * 集数编号（96），主源按季内集数编号（24）。{@code episode}/{@code reName} 是用
     * <b>产生本条目的那条 RSS</b> 的偏移算出来的，而订阅对象上只有一个 {@code ani.offset}，
     * 两者可以不同（用户给备用源单独配偏移时必然不同）。
     * <p>
     * 下游凡是要「从文件名提集数 → 换算成最终集数」的地方（期望文件计划、归位重命名）
     * 都必须用本字段的偏移，否则目标名会与 {@code reName} 差出偏移量——实测：
     * 添加下载是 {@code S04E24}，离线完成后被重命名成 {@code S04E96}。
     * 为空（老数据 / 非 RSS 入口）时回退订阅的 {@code ani.offset}。
     */
    @Schema(description = "来源 RSS 集数偏移")
    private Integer rssOffset;

    /**
     * 子集列表 (预览时合集折叠用)
     */
    @Schema(description = "子集列表")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private List<Item> children;
}
