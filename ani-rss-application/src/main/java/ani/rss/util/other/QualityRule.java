package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.QualityProfile;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 质量择优规则求值。
 * <p>
 * 与既有硬编码逻辑的关系：{@code ItemsUtil.sortByQualityAndSize} 原本按
 * "分辨率优先级 + 体积降序"排序。本类在其之上提供可配置版本，
 * <b>未启用时行为与原来完全一致</b>。
 * <p>
 * 安全底线：硬过滤（排除编码/字幕组、体积区间）若会把某集的候选<b>全部</b>滤掉，
 * 则放弃过滤并记日志——规则配错不该导致"这一集永远不下"。
 */
@Slf4j
public final class QualityRule {

    private static final long MB = 1024L * 1024L;

    private QualityRule() {
    }

    /**
     * 单条候选的评估结果
     *
     * @param allowed 是否通过硬过滤
     * @param score   排序得分，越高越好
     * @param reason  命中的规则说明（用于日志与预览展示）
     */
    public record Decision(boolean allowed, int score, String reason) {
    }

    /**
     * 解析生效的质量规则：订阅级自定义优先，否则用全局配置
     */
    public static QualityProfile effective(Ani ani, Config config) {
        if (ani != null && Boolean.TRUE.equals(ani.getCustomQualityProfileEnable())
                && ani.getCustomQualityProfile() != null) {
            // 订阅级开关就是该订阅规则的总开关；旧版本曾生成 enable=false 的
            // 嵌套默认对象，但没有订阅级质量表单可关闭它。显式开启覆盖时强制启用，
            // 避免 UI 显示已开启、实际 profile.enabled() 却返回 false。
            QualityProfile profile = ani.getCustomQualityProfile();
            if (!profile.enabled()) {
                profile.setEnable(true);
            }
            return profile;
        }
        if (config != null && config.getQualityProfile() != null) {
            return config.getQualityProfile();
        }
        return QualityProfile.disabled();
    }

    /**
     * 识别分辨率（与既有硬编码口径保持一致，另补 1440p）
     */
    public static String detectResolution(String title) {
        if (StrUtil.isBlank(title)) {
            return null;
        }
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("2160p") || t.contains("2160") || t.contains("4k")) {
            return "2160p";
        }
        if (t.contains("1440p")) {
            return "1440p";
        }
        if (t.contains("1080p") || t.contains("1080")) {
            return "1080p";
        }
        if (t.contains("720p") || t.contains("720")) {
            return "720p";
        }
        if (t.contains("480p") || t.contains("480")) {
            return "480p";
        }
        return null;
    }

    /**
     * 分辨率等级，数值越大越高。
     * 未识别值为 0；调用方对未知分辨率采取保留策略。
     */
    public static int resolutionRank(String resolution) {
        if (resolution == null) {
            return 0;
        }
        return switch (resolution.trim().toLowerCase(Locale.ROOT)) {
            case "480", "480p" -> 1;
            case "720", "720p" -> 2;
            case "1080", "1080p" -> 3;
            case "1440", "1440p" -> 4;
            case "2160", "2160p", "4k" -> 5;
            default -> 0;
        };
    }

    /**
     * 识别编码
     */
    public static String detectCodec(String title) {
        if (StrUtil.isBlank(title)) {
            return null;
        }
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("hevc") || t.contains("h.265") || t.contains("h265") || t.contains("x265")) {
            return "hevc";
        }
        if (t.contains("av1")) {
            return "av1";
        }
        if (t.contains("avc") || t.contains("h.264") || t.contains("h264") || t.contains("x264")) {
            return "avc";
        }
        return null;
    }

    /**
     * 评估单条候选
     */
    public static Decision evaluate(Item item, QualityProfile profile) {
        if (item == null) {
            return new Decision(false, Integer.MIN_VALUE, "空条目");
        }
        if (profile == null || !profile.enabled()) {
            return new Decision(true, legacyScore(item), "未启用规则");
        }

        String title = StrUtil.blankToDefault(item.getTitle(), "");
        String resolution = detectResolution(title);
        String codec = detectCodec(title);
        String subgroup = StrUtil.blankToDefault(item.getSubgroup(), "");

        // 分辨率上下限是硬过滤；未识别分辨率时保留候选，避免源标题缺质量词导致误杀。
        if (resolution != null) {
            int resolutionRank = resolutionRank(resolution);
            String minResolution = profile.normalizedMinResolution();
            String maxResolution = profile.normalizedMaxResolution();
            if (minResolution != null && resolutionRank < resolutionRank(minResolution)) {
                return new Decision(false, 0, "分辨率低于下限 " + minResolution);
            }
            if (maxResolution != null && resolutionRank > resolutionRank(maxResolution)) {
                return new Decision(false, 0, "分辨率高于上限 " + maxResolution);
            }
        }

        // ---- 硬过滤 ----
        List<String> excludeCodecs = profile.safeExcludeCodecs();
        if (codec != null && excludeCodecs.contains(codec)) {
            return new Decision(false, 0, "排除编码 " + codec);
        }
        List<String> excludeSubgroups = profile.safeExcludeSubgroups();
        if (!excludeSubgroups.isEmpty() && containsAny(subgroup, excludeSubgroups)) {
            return new Decision(false, 0, "排除字幕组 " + subgroup);
        }
        Long length = item.getLength();
        if (length != null && length > 0) {
            Integer minMb = profile.getMinSizeMb();
            if (minMb != null && minMb > 0 && length < minMb * MB) {
                return new Decision(false, 0, "体积小于下限 " + minMb + "MB");
            }
            Integer maxMb = profile.getMaxSizeMb();
            if (maxMb != null && maxMb > 0 && length > maxMb * MB) {
                return new Decision(false, 0, "体积超过上限 " + maxMb + "MB");
            }
        }

        Integer minSeeders = profile.getMinSeeders();
        if (minSeeders != null && minSeeders > 0 && item.getSeeders() != null
                && item.getSeeders() < minSeeders) {
            return new Decision(false, 0, "做种数低于下限 " + minSeeders);
        }

        // ---- 打分 ----
        int score = 0;
        StringBuilder reason = new StringBuilder();

        List<String> order = profile.effectiveResolutionOrder();
        int resIndex = resolution == null ? -1 : order.indexOf(resolution);
        if (resIndex >= 0) {
            score += (order.size() - resIndex) * 1000;
            reason.append(resolution);
        }

        List<String> codecs = profile.safePreferCodecs();
        if (codec != null) {
            int codecIndex = codecs.indexOf(codec);
            if (codecIndex >= 0) {
                score += (codecs.size() - codecIndex) * 100;
                appendReason(reason, codec);
            }
        }

        List<String> preferSubgroups = profile.safePreferSubgroups();
        if (!preferSubgroups.isEmpty() && containsAny(subgroup, preferSubgroups)) {
            score += 50;
            appendReason(reason, "优先字幕组 " + subgroup);
        }

        return new Decision(true, score, reason.length() == 0 ? "默认" : reason.toString());
    }

    /**
     * 未启用规则时的兼容得分（复刻既有 getQualityPriority 口径，仅用于排序稳定性）
     */
    private static int legacyScore(Item item) {
        String title = StrUtil.blankToDefault(item.getTitle(), "").toLowerCase(Locale.ROOT);
        if (title.contains("2160p") || title.contains("4k")) {
            return 40;
        }
        if (title.contains("1080p")) {
            return 30;
        }
        if (title.contains("720p")) {
            return 20;
        }
        if (title.contains("480p")) {
            return 10;
        }
        return 0;
    }

    /**
     * 对同集候选做硬过滤 + 排序，返回最优候选排在最前的列表。
     * <p>
     * 过滤后为空时回退为不过滤（并记日志），保证规则不会让某一集彻底失去候选。
     *
     * @param candidates 同集候选（调用方已按"合集优先 / 主源优先"收窄）
     * @param profile    生效规则
     */
    public static List<Item> rank(List<Item> candidates, QualityProfile profile) {
        if (CollUtil.isEmpty(candidates)) {
            return candidates;
        }
        List<Item> pool = candidates;
        if (profile != null && profile.enabled()) {
            List<Item> allowed = new ArrayList<>();
            for (Item item : candidates) {
                if (evaluate(item, profile).allowed()) {
                    allowed.add(item);
                }
            }
            if (allowed.isEmpty()) {
                // 规则过严会把这一集彻底滤掉——宁可放弃过滤，也不能让这集不下
                List<String> reasons = candidates.stream()
                        .map(it -> evaluate(it, profile).reason())
                        .distinct()
                        .limit(3)
                        .toList();
                log.warn("质量规则过滤后无候选, 已回退为不过滤（候选 {} 条, 原因样例: {}）",
                        candidates.size(), String.join(" / ", reasons));
            } else {
                pool = allowed;
            }
        }

        Comparator<Item> comparator = Comparator
                .comparingInt((Item it) -> evaluate(it, profile).score()).reversed()
                .thenComparing(Comparator.comparingLong((Item it) ->
                        it.getLength() != null ? it.getLength() : 0L).reversed());
        return pool.stream().sorted(comparator).toList();
    }

    private static void appendReason(StringBuilder sb, String part) {
        if (sb.length() > 0) {
            sb.append(" + ");
        }
        sb.append(part);
    }

    private static boolean containsAny(String value, List<String> needles) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (lower.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
