package ani.rss.task;

import ani.rss.commons.ExceptionUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.DownloadHistory;
import ani.rss.util.other.FailedDownloadQueue;
import ani.rss.util.other.NotificationUtil;
import ani.rss.util.other.SubscriptionHealth;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 追番周报。
 * <p>
 * 复用已有的 DownloadHistory（本周新增/失败/体积）、FailedDownloadQueue（待处理失败）
 * 与 SubscriptionHealth（漏集缓存），周期生成一份结构化汇总推送，
 * 并可选择自动触发一次补种（走统一的手动刷新入口，因此同样可在任务管理器里观察）。
 */
@Slf4j
@Component
public class WeeklyReportTask implements BaseTask {

    @Override
    public void accept(AtomicBoolean loop) {
        Config config = ConfigUtil.CONFIG;
        int intervalHours = Math.max(1, ObjectUtil.defaultIfNull(config.getWeeklyReportIntervalHours(), 168));

        try {
            if (Boolean.TRUE.equals(config.getWeeklyReport())) {
                report(config);
            }
        } catch (Exception e) {
            log.error("追番周报生成失败: {}", ExceptionUtils.getMessage(e));
        }

        ThreadUtil.sleep(intervalHours * 3600_000L);
    }

    private void report(Config config) {
        int hours = Math.max(1, ObjectUtil.defaultIfNull(config.getWeeklyReportIntervalHours(), 168));
        // DownloadHistory 的统计按天聚合，这里换算成等价天数（至少 1 天）
        int days = Math.max(1, (int) Math.ceil(hours / 24.0));

        DownloadHistory.Summary summary = DownloadHistory.summary(days);
        List<FailedDownloadQueue.FailedItem> failed = FailedDownloadQueue.list();

        List<Ani> aniList = AniUtil.getAniList();
        boolean omitOn = Boolean.TRUE.equals(config.getOmit());
        List<String> omitLines = new ArrayList<>();
        int omitTotal = 0;
        for (Ani ani : aniList) {
            if (!Boolean.TRUE.equals(ani.getEnable())) {
                continue;
            }
            try {
                int omit = SubscriptionHealth.cachedOmitCount(ani, omitOn);
                if (omit > 0) {
                    omitTotal += omit;
                    omitLines.add(ani.getTitle() + " 缺 " + omit + " 集");
                }
            } catch (Exception e) {
                log.debug("读取漏集缓存失败 {}: {}", ani.getTitle(), e.getMessage());
            }
        }

        StringBuilder text = new StringBuilder();
        text.append("追番周报（近 ").append(days).append(" 天）\n");
        text.append("新增完成 ").append(summary.success()).append(" 集");
        if (summary.size() > 0) {
            text.append("，共 ").append(cn.hutool.core.io.FileUtil.readableFileSize(summary.size()));
        }
        text.append("\n");
        text.append("失败 ").append(summary.failed()).append(" 集");
        if (summary.success() + summary.failed() > 0) {
            text.append("，成功率 ").append(String.format("%.1f%%", summary.successRate() * 100));
        }
        text.append("\n");
        text.append("漏集 ").append(omitTotal).append(" 集\n");
        text.append("待处理失败队列 ").append(failed.size()).append(" 条");

        if (!omitLines.isEmpty()) {
            text.append("\n漏集明细：");
            int limit = Math.min(omitLines.size(), 10);
            for (int i = 0; i < limit; i++) {
                text.append("\n· ").append(omitLines.get(i));
            }
            if (omitLines.size() > limit) {
                text.append("\n· 其余 ").append(omitLines.size() - limit).append(" 部见订阅列表");
            }
        }

        NotificationUtil.sendSystem(config, text.toString(), NotificationStatusEnum.SYSTEM);
        log.info("追番周报已生成: 新增 {} / 失败 {} / 漏集 {}",
                summary.success(), summary.failed(), omitTotal);

        if (Boolean.TRUE.equals(config.getWeeklyReportAutoRetry()) && omitTotal > 0) {
            // 自动补种：走统一手动刷新入口，可在任务管理器观察与取消
            try {
                String msg = RssTask.submitManualRefresh(null);
                log.info("周报自动补种已触发: {}", msg);
            } catch (Exception e) {
                log.warn("周报自动补种触发失败: {}", ExceptionUtils.getMessage(e));
            }
        }
    }
}
