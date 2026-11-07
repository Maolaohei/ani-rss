package ani.rss.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 合集
 */
@Data
@Accessors(chain = true)
@Schema(description = "合集")
public class CollectionInfo implements Serializable {
    /**
     * 种子文件 Base64，或磁力链接（{@code magnet:?xt=urn:btih:...}）
     */
    @Schema(description = "种子文件 base64 或磁力链接")
    private String torrent;

    /**
     * 订阅
     */
    @Schema(description = "订阅")
    private Ani ani;

    /**
     * bgm
     */
    @Schema(description = "bgm")
    private BgmInfo bgmInfo;

    /**
     * 合集模式：{@code SINGLE}（默认，整份种子一个订阅/一个目录）
     * 或 {@code SPLIT}（按 {@link #segments} 拆成多季/剧场版，一段一个订阅）
     */
    @Schema(description = "合集模式")
    private String mode;

    /**
     * 拆分方案（仅 {@code mode=SPLIT} 时生效）
     */
    @Schema(description = "分段方案")
    private List<CollectionSegment> segments;

    /**
     * 结构分析提示（自动识别的依据/风险点，供前端展示）
     */
    @Schema(description = "提示")
    private List<String> warnings;
}
