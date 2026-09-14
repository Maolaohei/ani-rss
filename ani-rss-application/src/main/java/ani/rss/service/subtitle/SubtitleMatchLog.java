package ani.rss.service.subtitle;

import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 字幕匹配日志（内存环形缓冲 + 磁盘持久化）。
 * <p>
 * 记录每次字幕匹配的结果（原文件名 / 重命名后文件名 / 对应视频 / 季 / 状态），
 * 供前端「字幕匹配」面板展示，便于核对季数是否正确、是否存在跨季误匹配。
 * </p>
 */
@Slf4j
public class SubtitleMatchLog {

    private static final int MAX = 200;
    private static final String FILE_NAME = "subtitle_match_log.json";
    private static final List<SubtitleMatchLogEntry> RING =
            Collections.synchronizedList(new ArrayList<>());
    private static final ReentrantLock IO_LOCK = new ReentrantLock();
    private static volatile boolean loaded = false;
    private static final Type TYPE = new TypeToken<List<SubtitleMatchLogEntry>>() {
    }.getType();

    private static final Gson GSON = new Gson();

    /**
     * 记录一条匹配结果。
     */
    public static void record(SubtitleMatchLogEntry entry) {
        if (entry == null) {
            return;
        }
        ensureLoaded();
        synchronized (RING) {
            RING.add(entry);
            if (RING.size() > MAX) {
                RING.subList(0, RING.size() - MAX).clear();
            }
        }
        persist();
    }

    /**
     * 取最近 limit 条（按时间倒序，最新在前）。
     */
    public static List<SubtitleMatchLogEntry> list(int limit) {
        ensureLoaded();
        synchronized (RING) {
            List<SubtitleMatchLogEntry> snapshot = new ArrayList<>(RING);
            Collections.reverse(snapshot);
            if (limit > 0 && snapshot.size() > limit) {
                return snapshot.subList(0, limit);
            }
            return snapshot;
        }
    }

    /**
     * 清空日志（内存 + 磁盘）。
     */
    public static void clear() {
        synchronized (RING) {
            RING.clear();
        }
        IO_LOCK.lock();
        try {
            FileUtil.del(cacheFile());
        } catch (Exception e) {
            log.warn("清空字幕匹配日志失败: {}", e.getMessage());
        } finally {
            IO_LOCK.unlock();
        }
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        IO_LOCK.lock();
        try {
            if (loaded) {
                return;
            }
            File file = cacheFile();
            if (file.exists()) {
                try {
                    String text = FileUtil.readUtf8String(file);
                    if (StrUtil.isNotBlank(text)) {
                        List<SubtitleMatchLogEntry> list = GSON.fromJson(text, TYPE);
                        if (list != null) {
                            synchronized (RING) {
                                RING.clear();
                                RING.addAll(list);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("读取字幕匹配日志失败, 从头开始: {}", e.getMessage());
                }
            }
            loaded = true;
        } finally {
            IO_LOCK.unlock();
        }
    }

    private static void persist() {
        IO_LOCK.lock();
        try {
            List<SubtitleMatchLogEntry> snapshot;
            synchronized (RING) {
                snapshot = new ArrayList<>(RING);
            }
            FileUtil.writeUtf8String(GSON.toJson(snapshot), cacheFile());
        } catch (Exception e) {
            log.warn("写入字幕匹配日志失败: {}", e.getMessage());
        } finally {
            IO_LOCK.unlock();
        }
    }

    private static File cacheFile() {
        File dir = ConfigUtil.getConfigDir();
        FileUtil.mkdir(dir);
        return new File(dir, FILE_NAME);
    }
}
