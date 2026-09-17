package ani.rss.util.other;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 失败下载条目队列（本地 JSON，手动重试）。
 * 不自动无限重试，避免与 RSS 周期扫重复风暴。
 */
@Slf4j
public final class FailedDownloadQueue {
    public static final String FILE_NAME = "failed-download-queue.json";
    public static final int MAX_SIZE = 200;

    private static final CopyOnWriteArrayList<FailedItem> ITEMS = new CopyOnWriteArrayList<>();
    private static volatile boolean loaded = false;

    /**
     * 落盘节流（同 DownloadHistory）：record 高频时合并写盘，list 只读内存保持同步可见。
     */
    private static final long SAVE_THROTTLE_MS = 2000L;
    private static final AtomicLong LAST_SAVE_MS = new AtomicLong(0L);
    private static final AtomicBoolean SAVE_SCHEDULED = new AtomicBoolean(false);
    private static final ScheduledExecutorService SAVE_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "failed-queue-save");
        t.setDaemon(true);
        return t;
    });
    private static volatile String LAST_SAVED_JSON;

    private FailedDownloadQueue() {
    }

    @Data
    @Accessors(chain = true)
    public static class FailedItem implements Serializable {
        private String id;
        private String aniId;
        private String title;
        private String reName;
        private String infoHash;
        private String errorCode;
        private String message;
        private String suggestion;
        private Long failedAt;
        private Integer attempts;
    }

    public static synchronized void load() {
        File file = file();
        if (!file.exists()) {
            ITEMS.clear();
            loaded = true;
            return;
        }
        try {
            String json = FileUtil.readString(file, StandardCharsets.UTF_8);
            List<FailedItem> list = GsonStatic.fromJsonList(json, FailedItem.class);
            ITEMS.clear();
            if (list != null) {
                ITEMS.addAll(list);
            }
            loaded = true;
        } catch (Exception e) {
            // 解析失败: 先把坏文件改名保留现场, 再以空列表继续, 避免下一次 save() 用空列表把原始记录覆盖丢失
            String ts = DateUtil.format(new Date(), "yyyyMMddHHmmss");
            File badFile = new File(file.getParentFile(), FILE_NAME + ".bad-" + ts);
            try {
                FileUtils.move(file.toPath(), badFile.toPath());
                log.error("失败队列文件解析失败, 已改名为 [{}] 保留现场; 本次以空失败列表继续", badFile.getName());
            } catch (Exception moveException) {
                log.error("失败队列文件解析失败, 且改名保留失败(可能被占用): {}", file, moveException);
            }
            log.warn("加载失败队列失败: {}", e.getMessage());
            ITEMS.clear();
            loaded = true;
        }
    }

    public static synchronized void save() {
        ensureLoaded();
        try {
            // 原子写：temp + rename，避免写盘瞬间崩溃/磁盘满留下截断的 json
            File file = file();
            String json = GsonStatic.toJson(new ArrayList<>(ITEMS));
            if (json.equals(LAST_SAVED_JSON) && file.exists()) {
                return;
            }
            File temp = new File(file.getPath() + ".temp");
            FileUtil.writeString(json, temp, StandardCharsets.UTF_8);
            FileUtils.move(temp.toPath(), file.toPath());
            LAST_SAVED_JSON = json;
            LAST_SAVE_MS.set(System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("保存失败队列失败: {}", e.getMessage());
        }
    }

    /**
     * 节流落盘：距上次不足 2s 则延迟 2s 异步写一次，异步时读当前全量，不丢数据。
     */
    private static void saveThrottled() {
        long now = System.currentTimeMillis();
        // 文件不存在时立即落盘（首写/换目录/测试隔离），避免跨用例节流窗口吞掉写入
        if (now - LAST_SAVE_MS.get() < SAVE_THROTTLE_MS && file().exists()) {
            if (SAVE_SCHEDULED.compareAndSet(false, true)) {
                SAVE_EXECUTOR.schedule(() -> {
                    SAVE_SCHEDULED.set(false);
                    try {
                        save();
                    } catch (Exception e) {
                        log.debug("异步保存失败队列失败: {}", e.getMessage());
                    }
                }, SAVE_THROTTLE_MS, TimeUnit.MILLISECONDS);
            }
            return;
        }
        save();
    }

    public static List<FailedItem> list() {
        ensureLoaded();
        return List.copyOf(ITEMS);
    }

    public static synchronized FailedItem record(String aniId, String title, String reName, String infoHash,
                                                 String rawMessage) {
        ensureLoaded();
        TaskFailureHumanizer.HumanizedFailure h = TaskFailureHumanizer.humanize(rawMessage);
        String key = keyOf(aniId, infoHash, reName);
        for (FailedItem existing : ITEMS) {
            if (Objects.equals(existing.getId(), key)) {
                existing.setMessage(h.title())
                        .setSuggestion(h.suggestion())
                        .setErrorCode(h.code().name())
                        .setFailedAt(System.currentTimeMillis())
                        .setAttempts((existing.getAttempts() == null ? 0 : existing.getAttempts()) + 1);
                trimAndSave();
                return existing;
            }
        }
        FailedItem item = new FailedItem()
                .setId(key)
                .setAniId(aniId)
                .setTitle(title)
                .setReName(reName)
                .setInfoHash(infoHash)
                .setErrorCode(h.code().name())
                .setMessage(h.title())
                .setSuggestion(h.suggestion())
                .setFailedAt(System.currentTimeMillis())
                .setAttempts(1);
        ITEMS.add(0, item);
        trimAndSave();
        return item;
    }

    public static synchronized boolean remove(String id) {
        ensureLoaded();
        boolean removed = ITEMS.removeIf(i -> Objects.equals(i.getId(), id));
        if (removed) {
            save();
        }
        return removed;
    }

    public static synchronized int clear() {
        ensureLoaded();
        int n = ITEMS.size();
        ITEMS.clear();
        save();
        return n;
    }

    public static String keyOf(String aniId, String infoHash, String reName) {
        if (StrUtil.isNotBlank(infoHash)) {
            return StrUtil.blankToDefault(aniId, "") + ":" + infoHash.toLowerCase();
        }
        return StrUtil.blankToDefault(aniId, "") + ":" + StrUtil.blankToDefault(reName, "unknown");
    }

    private static void trimAndSave() {
        while (ITEMS.size() > MAX_SIZE) {
            ITEMS.remove(ITEMS.size() - 1);
        }
        saveThrottled();
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static File file() {
        return new File(ConfigUtil.getConfigDir(), FILE_NAME);
    }

    /** 测试用：重置内存态 */
    static synchronized void resetForTest() {
        ITEMS.clear();
        loaded = true;
        LAST_SAVED_JSON = null;
        LAST_SAVE_MS.set(0L);
        SAVE_SCHEDULED.set(false);
    }
}
