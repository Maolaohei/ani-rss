package ani.rss.util.other;

import ani.rss.entity.Ani;
import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 订阅运维健康分（非 BGM 评分）。0-100，越高越健康。
 */
public final class SubscriptionHealth {
    private SubscriptionHealth() {
    }

    public record Score(int score, String level, List<String> reasons) {
    }

    public static Score compute(Ani ani, int omitCount, long nowMs) {
        if (ani == null) {
            return new Score(0, "unknown", List.of("无订阅数据"));
        }
        List<String> reasons = new ArrayList<>();
        int score = 100;

        if (!Boolean.TRUE.equals(ani.getEnable())) {
            return new Score(40, "paused", List.of("订阅未启用"));
        }

        Integer current = ani.getCurrentEpisodeNumber();
        Integer total = ani.getTotalEpisodeNumber();
        if (total != null && total > 0 && current != null && current >= total) {
            return new Score(100, "completed", List.of("已追完"));
        }

        int effectiveOmit = Math.max(0, omitCount);
        if (effectiveOmit > 0) {
            int pen = Math.min(40, effectiveOmit * 10);
            score -= pen;
            reasons.add("疑似漏集 " + effectiveOmit + " 处");
        }

        // 列表阶段用缓存漏集：过期提示不改分数，避免再打 RSS
        Long checkedAt = ani.getOmitCheckedAt();
        if (effectiveOmit > 0 && checkedAt != null && checkedAt > 0) {
            long ageDays = TimeUnit.MILLISECONDS.toDays(Math.max(0, nowMs - checkedAt));
            if (ageDays >= 3) {
                reasons.add("漏集信息可能过期（" + ageDays + " 天前）");
            }
        }

        Long last = ani.getLastDownloadTime();
        if (last == null || last <= 0) {
            score -= 15;
            reasons.add("尚无下载记录");
        } else {
            long days = TimeUnit.MILLISECONDS.toDays(Math.max(0, nowMs - last));
            if (days >= 21) {
                score -= 30;
                reasons.add("超过 " + days + " 天未下载");
            } else if (days >= 14) {
                score -= 20;
                reasons.add("超过 " + days + " 天未下载");
            } else if (days >= 7) {
                score -= 10;
                reasons.add("超过 " + days + " 天未下载");
            }
        }

        if (Boolean.TRUE.equals(ani.getProcrastinating())) {
            score -= 5;
            reasons.add("摸鱼中");
        }

        if (ani.getStandbyRssList() != null && !ani.getStandbyRssList().isEmpty()
                && (ani.getUrl() == null || ani.getUrl().isBlank())) {
            score -= 10;
            reasons.add("仅备用 RSS、主源为空");
        }

        score = Math.max(0, Math.min(100, score));
        String level;
        if (score >= 85) {
            level = "good";
        } else if (score >= 60) {
            level = "warn";
        } else {
            level = "bad";
        }
        if (reasons.isEmpty()) {
            reasons.add("运行正常");
        }
        // 保证可序列化不可变
        return new Score(score, level, List.copyOf(reasons));
    }

    public static String levelLabel(String level) {
        if ("good".equals(level)) return "健康";
        if ("warn".equals(level)) return "注意";
        if ("bad".equals(level)) return "异常";
        if ("paused".equals(level)) return "停用";
        if ("completed".equals(level)) return "完结";
        return StrUtil.blankToDefault(level, "未知");
    }

    /**
     * 列表页用：读取 Ani 上缓存的漏集数量，不访问网络。
     * 全局/订阅关闭漏集或 OVA 时返回 0。
     */
    public static int cachedOmitCount(Ani ani, boolean globalOmitEnabled) {
        if (ani == null || !globalOmitEnabled) {
            return 0;
        }
        if (!Boolean.TRUE.equals(ani.getOmit()) || Boolean.TRUE.equals(ani.getOva())) {
            return 0;
        }
        Integer n = ani.getOmitCount();
        return n == null || n < 0 ? 0 : n;
    }

    /**
     * 将 omitList 结果写回 Ani（下载/预览等已付出 RSS 成本的路径调用）。
     */
    public static void rememberOmit(Ani ani, int omitCount, long nowMs) {
        if (ani == null) {
            return;
        }
        ani.setOmitCount(Math.max(0, omitCount));
        ani.setOmitCheckedAt(nowMs);
    }
}
