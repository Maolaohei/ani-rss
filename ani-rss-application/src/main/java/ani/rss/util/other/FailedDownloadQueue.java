package ani.rss.util.other;

import ani.rss.commons.GsonStatic;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

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
            log.warn("加载失败队列失败: {}", e.getMessage());
            loaded = true;
        }
    }

    public static synchronized void save() {
        ensureLoaded();
        try {
            FileUtil.writeString(GsonStatic.toJson(new ArrayList<>(ITEMS)), file(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("保存失败队列失败: {}", e.getMessage());
        }
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
        save();
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
    }
}
