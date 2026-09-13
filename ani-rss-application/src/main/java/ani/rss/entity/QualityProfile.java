package ani.rss.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 质量择优规则（Quality Profile）。
 * <p>
 * 此前同集候选的择优逻辑是<b>硬编码</b>的：分辨率按 2160p &gt; 1080p &gt; 720p &gt; 480p 固定排序，
 * 同分辨率下取体积更大者，无法表达"我只要 HEVC""体积别超过 2G""不要某字幕组"这类真实诉求。
 * <p>
 * 本类把这些维度变成可配置规则。三条约束：
 * <ol>
 *   <li><b>默认关闭</b>——{@code enable=false} 时完全走既有逻辑，行为零变化；</li>
 *   <li><b>硬过滤不允许清空候选</b>——若过滤后一个候选都不剩，回退为不过滤，
 *       绝不能因为规则配得太严导致某一集直接不下；</li>
 *   <li><b>不改变既有优先级</b>——多字幕组共存 &gt; 洗版（备用 RSS 主源优先）&gt; 质量规则。
 *       质量规则只在既有逻辑已经选定的候选池内部排序，不会反过来把主源换成备源。</li>
 * </ol>
 */
@Data
@Accessors(chain = true)
@Schema(description = "质量择优规则")
public class QualityProfile implements Serializable {

    /**
     * 是否启用。默认关闭，保证存量用户行为不变。
     */
    @Schema(description = "启用质量择优规则")
    private Boolean enable;

    /**
     * 分辨率偏好顺序，靠前者优先。留空表示沿用默认顺序。
     * 取值建议：2160p / 1080p / 720p / 480p
     */
    @Schema(description = "分辨率偏好顺序（靠前优先）")
    private List<String> resolutionOrder;

    /**
     * 最低分辨率（硬过滤）。留空表示不限制。
     */
    @Schema(description = "最低分辨率")
    private String minResolution;

    /**
     * 最高分辨率（硬过滤）。留空表示不限制。
     */
    @Schema(description = "最高分辨率")
    private String maxResolution;

    /**
     * 规范化后的最低分辨率
     */
    public String normalizedMinResolution() {
        return normalizeResolution(minResolution);
    }

    /**
     * 规范化后的最高分辨率
     */
    public String normalizedMaxResolution() {
        return normalizeResolution(maxResolution);
    }

    private static String normalizeResolution(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "4k", "2160", "2160p" -> "2160p";
            case "1440", "1440p" -> "1440p";
            case "1080", "1080p" -> "1080p";
            case "720", "720p" -> "720p";
            case "480", "480p" -> "480p";
            default -> null;
        };
    }

    /**
     * 编码偏好顺序，靠前者优先。留空表示不参与排序。
     * 取值建议：hevc / av1 / avc
     */
    @Schema(description = "编码偏好顺序（靠前优先）")
    private List<String> preferCodecs;

    /**
     * 排除的编码（命中即过滤）。如只要 HEVC 就填 avc。
     */
    @Schema(description = "排除的编码")
    private List<String> excludeCodecs;

    /**
     * 体积下限（MB）。0 或空表示不限制。
     */
    @Schema(description = "体积下限(MB)")
    private Integer minSizeMb;

    /**
     * 体积上限（MB）。0 或空表示不限制。
     */
    @Schema(description = "体积上限(MB)")
    private Integer maxSizeMb;

    /**
     * 做种数下限。RSS 未提供做种数时不做硬过滤（保持候选可用），
     * 只有源提供该字段且小于下限时才过滤。
     */
    @Schema(description = "做种数下限")
    private Integer minSeeders;

    /**
     * 优先字幕组（命中时提升排序权重）
     */
    @Schema(description = "优先字幕组")
    private List<String> preferSubgroups;

    /**
     * 排除字幕组（命中即过滤）
     */
    @Schema(description = "排除字幕组")
    private List<String> excludeSubgroups;

    /**
     * 优先合集包：同集同时存在合集源与单集源时，优先合集（沿用既有默认行为）
     */
    @Schema(description = "优先合集包")
    private Boolean preferCollection;

    /**
     * 默认分辨率偏好顺序（与既有硬编码逻辑保持一致）
     */
    public static final List<String> DEFAULT_RESOLUTION_ORDER =
            List.of("2160p", "1440p", "1080p", "720p", "480p");

    public static QualityProfile disabled() {
        return new QualityProfile().setEnable(false);
    }

    public boolean enabled() {
        return Boolean.TRUE.equals(enable);
    }

    /**
     * 生效的分辨率顺序（未配置时回落默认）
     */
    public List<String> effectiveResolutionOrder() {
        if (resolutionOrder == null || resolutionOrder.isEmpty()) {
            return DEFAULT_RESOLUTION_ORDER;
        }
        return resolutionOrder.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase())
                .toList();
    }

    /**
     * 优先合集：未显式配置时沿用既有行为（优先合集）
     */
    public boolean preferCollectionOrDefault() {
        return preferCollection == null || Boolean.TRUE.equals(preferCollection);
    }

    public List<String> safePreferCodecs() {
        return lower(resolutionOrEmpty(preferCodecs));
    }

    public List<String> safeExcludeCodecs() {
        return lower(resolutionOrEmpty(excludeCodecs));
    }

    public List<String> safePreferSubgroups() {
        return trimAll(preferSubgroups);
    }

    public List<String> safeExcludeSubgroups() {
        return trimAll(excludeSubgroups);
    }

    private static List<String> resolutionOrEmpty(List<String> list) {
        return list == null ? new ArrayList<>() : list;
    }

    private static List<String> lower(List<String> list) {
        return list.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase())
                .toList();
    }

    private static List<String> trimAll(List<String> list) {
        if (list == null) {
            return new ArrayList<>();
        }
        return list.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .toList();
    }
}
