package ani.rss.util.other;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.vo.RssJobStatus;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * RSS 调度状态持久化（跨重启保留"上一轮"信息）。
 * <p>
 * 此前重启后任务管理器的"上一轮已处理"卡片与订阅级失败明细全部清空，
 * 用户重启一次就丢掉了排查线索。这里把**已完成轮次的结果快照**落盘。
 * <p>
 * 刻意<b>不</b>持久化"运行中/排队中"这类活动态：重启后那些任务客观上已经不存在，
 * 恢复出"运行中"只会制造幽灵状态（这正是 OpenList 残留逻辑踩过的坑）。
 * 活动态仍由启动回扫 / 残留扫描负责重建。
 */
@Slf4j
public final class RssJobStateStore {

    public static final String FILE_NAME = "rss-job-state.json";

    /**
     * 失败明细持久化上限
     */
    private static final int FAILED_MAX = 50;

    private RssJobStateStore() {
    }

    public static class Snapshot implements Serializable {
        private Long lastFinishedAt;
        private Long lastDurationMs;
        private String lastResultMessage;
        private String lastTitle;
        private String lastSource;
        private String lastScope;
        private List<RssJobStatus.FailedSubscription> failedSubscriptions;

        public Long getLastFinishedAt() {
            return lastFinishedAt;
        }

        public Snapshot setLastFinishedAt(Long lastFinishedAt) {
            this.lastFinishedAt = lastFinishedAt;
            return this;
        }

        public Long getLastDurationMs() {
            return lastDurationMs;
        }

        public Snapshot setLastDurationMs(Long lastDurationMs) {
            this.lastDurationMs = lastDurationMs;
            return this;
        }

        public String getLastResultMessage() {
            return lastResultMessage;
        }

        public Snapshot setLastResultMessage(String lastResultMessage) {
            this.lastResultMessage = lastResultMessage;
            return this;
        }

        public String getLastTitle() {
            return lastTitle;
        }

        public Snapshot setLastTitle(String lastTitle) {
            this.lastTitle = lastTitle;
            return this;
        }

        public String getLastSource() {
            return lastSource;
        }

        public Snapshot setLastSource(String lastSource) {
            this.lastSource = lastSource;
            return this;
        }

        public String getLastScope() {
            return lastScope;
        }

        public Snapshot setLastScope(String lastScope) {
            this.lastScope = lastScope;
            return this;
        }

        public List<RssJobStatus.FailedSubscription> getFailedSubscriptions() {
            return failedSubscriptions;
        }

        public Snapshot setFailedSubscriptions(List<RssJobStatus.FailedSubscription> failedSubscriptions) {
            this.failedSubscriptions = failedSubscriptions;
            return this;
        }
    }

    public static synchronized void save(Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            List<RssJobStatus.FailedSubscription> failed = snapshot.getFailedSubscriptions();
            if (failed != null && failed.size() > FAILED_MAX) {
                snapshot.setFailedSubscriptions(new ArrayList<>(failed.subList(0, FAILED_MAX)));
            }
            File file = file();
            File temp = new File(file.getPath() + ".temp");
            FileUtil.writeString(GsonStatic.toJson(snapshot), temp, StandardCharsets.UTF_8);
            FileUtil.move(temp, file, true);
        } catch (Exception e) {
            log.debug("保存 RSS 调度状态失败: {}", e.getMessage());
        }
    }

    /**
     * 读取快照；文件不存在或损坏返回 null（损坏时改名保留现场）
     */
    public static synchronized Snapshot load() {
        File file = file();
        if (!file.exists()) {
            return null;
        }
        try {
            String json = FileUtil.readString(file, StandardCharsets.UTF_8);
            return GsonStatic.fromJson(json, Snapshot.class);
        } catch (Exception e) {
            String ts = DateUtil.format(new Date(), "yyyyMMddHHmmss");
            File bad = new File(file.getParentFile(), FILE_NAME + ".bad-" + ts);
            try {
                FileUtil.move(file, bad, true);
                log.warn("RSS 调度状态解析失败, 已改名为 [{}] 保留现场", bad.getName());
            } catch (Exception ignored) {
                log.warn("RSS 调度状态解析失败且改名保留失败: {}", file);
            }
            return null;
        }
    }

    private static File file() {
        return new File(ConfigUtil.getConfigDir(), FILE_NAME);
    }
}
