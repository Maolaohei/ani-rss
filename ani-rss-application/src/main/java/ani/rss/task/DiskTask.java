package ani.rss.task;

import ani.rss.commons.ExceptionUtils;
import ani.rss.entity.Config;
import ani.rss.enums.EventTypeEnum;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.DiskMonitorUtil;
import ani.rss.util.other.EventWebhookUtil;
import ani.rss.util.other.NotificationUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 磁盘空间监控。
 * <p>
 * 自托管场景里"下载盘写满"会让整条下载链路一起失败，而此前只有事后日志。
 * 本任务周期探测下载根 / 剧场版根 / 完结迁移根 / 配置目录的可用空间，
 * 越过阈值时推送系统通知。
 * <p>
 * 去重策略：同一路径在同一"告警档位"（阈值/阈值+5/阈值+10）内只推一次，
 * 且两次推送之间至少有冷却时间，避免每小时刷屏。
 */
@Slf4j
@Component
public class DiskTask implements BaseTask {

    /**
     * 同一路径的告警冷却时间（6 小时）
     */
    private static final long ALERT_COOLDOWN_MS = 6 * 60 * 60 * 1000L;

    private static final Map<String, AlertState> LAST_ALERT = new ConcurrentHashMap<>();

    private record AlertState(int tier, long at) {
    }

    @Override
    public void accept(AtomicBoolean loop) {
        Config config = ConfigUtil.CONFIG;
        // 探测间隔下限 5 分钟，防止用户填 0 导致空转打满 CPU
        int intervalMinutes = Math.max(5, ObjectUtil.defaultIfNull(config.getDiskCheckIntervalMinutes(), 60));

        try {
            if (Boolean.TRUE.equals(config.getDiskMonitor())) {
                check(config);
            }
        } catch (Exception e) {
            log.error("磁盘空间检查失败: {}", ExceptionUtils.getMessage(e));
        }

        ThreadUtil.sleep(intervalMinutes * 60_000L);
    }

    private void check(Config config) {
        int warnPercent = clampPercent(ObjectUtil.defaultIfNull(config.getDiskWarnPercent(), 85));
        long now = System.currentTimeMillis();
        StringBuilder alert = new StringBuilder();

        for (DiskMonitorUtil.Mount mount : DiskMonitorUtil.probe()) {
            if (!mount.measurable()) {
                // 网络盘 / 未挂载：getTotalSpace 常返回 0，不参与告警
                continue;
            }
            if (mount.usedPercent() < warnPercent) {
                // 回落：清掉该路径的告警态，下次再越线能重新提醒
                LAST_ALERT.remove(key(mount));
                continue;
            }
            int tier = tierOf(mount.usedPercent(), warnPercent);
            String key = key(mount);
            AlertState last = LAST_ALERT.get(key);
            boolean escalated = last == null || tier > last.tier();
            boolean cooled = last == null || now - last.at() >= ALERT_COOLDOWN_MS;
            if (!escalated && !cooled) {
                continue;
            }
            LAST_ALERT.put(key, new AlertState(tier, now));
            if (alert.length() > 0) {
                alert.append("\n");
            }
            alert.append(StrUtil.format("{} 已用 {}%（剩余 {}，路径 {}）",
                    mount.label(),
                    String.format("%.1f", mount.usedPercent()),
                    DiskMonitorUtil.format(mount.usable()),
                    mount.path()));
            log.warn("磁盘空间预警 {} 已用 {}% 剩余 {}",
                    mount.label(), String.format("%.1f", mount.usedPercent()),
                    DiskMonitorUtil.format(mount.usable()));
        }

        if (alert.length() == 0) {
            return;
        }
        NotificationUtil.sendSystem(config,
                "磁盘空间预警\n" + alert + "\n建议及时清理或扩容，避免下载失败",
                NotificationStatusEnum.SYSTEM);
        try {
            EventWebhookUtil.emit(EventTypeEnum.DISK_WARNING, null, Map.of(
                    "detail", alert.toString(),
                    "threshold", warnPercent));
        } catch (Exception e) {
            log.debug("派发磁盘预警事件失败: {}", e.getMessage());
        }
    }

    private static String key(DiskMonitorUtil.Mount mount) {
        return StrUtil.blankToDefault(mount.path(), mount.label());
    }

    /**
     * 档位：阈值 / 阈值+5 / 阈值+10，档位越高越紧急
     */
    static int tierOf(double usedPercent, int warnPercent) {
        if (usedPercent >= warnPercent + 10) {
            return 2;
        }
        if (usedPercent >= warnPercent + 5) {
            return 1;
        }
        return 0;
    }

    static int clampPercent(int percent) {
        return Math.max(50, Math.min(99, percent));
    }

    /**
     * 测试用：清空告警去重态
     */
    static void resetForTest() {
        LAST_ALERT.clear();
    }
}
