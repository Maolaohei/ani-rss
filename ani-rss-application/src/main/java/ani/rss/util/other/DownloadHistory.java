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
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 下载历史（本地 JSON，成功侧对账）。
 * <p>
 * 失败侧已有 {@link FailedDownloadQueue}，但成功侧此前完全不留痕：
 * 用户无法回答"上周到底下了哪些""这周一共收了多少集""这集为什么没下"。
 * 本类把每次下载结果落盘，供历史页 / 看板趋势 / 周报 / AI 根因分析消费。
 * <p>
 * 持久化策略与 {@link FailedDownloadQueue} 保持一致：temp + rename 原子写、
 * 解析失败改名保留现场、容量上限裁剪。
 */
@Slf4j
public final class DownloadHistory {
    public static final String FILE_NAME = "download-history.json";
    public static final int MAX_SIZE = 1000;

    private static final CopyOnWriteArrayList<DownloadRecord> ITEMS = new CopyOnWriteArrayList<>();
    private static volatile boolean loaded = false;

    /**
     * 落盘节流：record 高频时合并写盘，避免每次下载都全量序列化 + 写临时文件 + move。
     * query/list 只读内存（CopyOnWrite），保持同步可见，不受异步落盘影响。
     */
    private static final long SAVE_THROTTLE_MS = 2000L;
    private static final AtomicLong LAST_SAVE_MS = new AtomicLong(0L);
    private static final AtomicBoolean SAVE_SCHEDULED = new AtomicBoolean(false);
    private static final ScheduledExecutorService SAVE_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "download-history-save");
        t.setDaemon(true);
        return t;
    });
    private static volatile String LAST_SAVED_JSON;

    private DownloadHistory() {
    }

    /**
     * 下载结果
     */
    public enum Result {
        /**
         * 正常下载完成
         */
        SUCCESS,
        /**
         * 备用RSS 洗版完成
         */
        WASH,
        /**
         * 已存在跳过
         */
        SKIP,
        /**
         * 失败
         */
        FAILED
    }

    @Data
    @Accessors(chain = true)
    public static class DownloadRecord implements Serializable {
        private String id;
        private String aniId;
        private String title;
        private String reName;
        private String infoHash;
        private Double episode;
        private Long size;
        private String source;
        private String subgroup;
        private String result;
        private String message;
        private Long at;
        private Long durationMs;
    }

    /**
     * 按天聚合的统计
     */
    public record DayStat(String date, int success, int failed, long size) {
    }

    /**
     * 总览统计
     */
    public record Summary(int total, int success, int failed, int skip, long size,
                          double successRate, long avgDurationMs) {
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
            List<DownloadRecord> list = GsonStatic.fromJsonList(json, DownloadRecord.class);
            ITEMS.clear();
            if (list != null) {
                ITEMS.addAll(list);
            }
            loaded = true;
        } catch (Exception e) {
            // 解析失败先把坏文件改名保留现场，避免下一次 save() 用空列表覆盖原始记录
            String ts = DateUtil.format(new Date(), "yyyyMMddHHmmss");
            File badFile = new File(file.getParentFile(), FILE_NAME + ".bad-" + ts);
            try {
                FileUtils.move(file.toPath(), badFile.toPath());
                log.error("下载历史文件解析失败, 已改名为 [{}] 保留现场; 本次以空历史继续", badFile.getName());
            } catch (Exception moveException) {
                log.error("下载历史文件解析失败, 且改名保留失败(可能被占用): {}", file, moveException);
            }
            ITEMS.clear();
            loaded = true;
        }
    }

    public static synchronized void save() {
        ensureLoaded();
        try {
            File file = file();
            String json = GsonStatic.toJson(new ArrayList<>(ITEMS));
            // 内容未变跳过：record 去重/高频调用时省掉临时文件 + 原子 move
            if (json.equals(LAST_SAVED_JSON) && file.exists()) {
                return;
            }
            File temp = new File(file.getPath() + ".temp");
            FileUtil.writeString(json, temp, StandardCharsets.UTF_8);
            FileUtils.move(temp.toPath(), file.toPath());
            LAST_SAVED_JSON = json;
            LAST_SAVE_MS.set(System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("保存下载历史失败: {}", e.getMessage());
        }
    }

    /**
     * 节流落盘：距上次落盘不足 2s 则延迟 2s 异步写一次（同窗口只排一次），
     * 异步线程执行时读取当前 ITEMS 全量，因此不丢数据、只延后。
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
                        log.debug("异步保存下载历史失败: {}", e.getMessage());
                    }
                }, SAVE_THROTTLE_MS, TimeUnit.MILLISECONDS);
            }
            return;
        }
        save();
    }

    public static List<DownloadRecord> list() {
        ensureLoaded();
        return List.copyOf(ITEMS);
    }

    /**
     * 条件查询（按时间倒序）
     *
     * @param aniId  订阅 id，可空
     * @param result 结果，可空
     * @param since  起始时间（epoch ms），可空
     * @param limit  最大条数，<=0 表示不限制
     */
    public static List<DownloadRecord> query(String aniId, String result, Long since, int limit) {
        ensureLoaded();
        List<DownloadRecord> result1 = new ArrayList<>();
        for (DownloadRecord item : ITEMS) {
            if (StrUtil.isNotBlank(aniId) && !Objects.equals(aniId, item.getAniId())) {
                continue;
            }
            if (StrUtil.isNotBlank(result) && !result.equalsIgnoreCase(item.getResult())) {
                continue;
            }
            if (since != null && (item.getAt() == null || item.getAt() < since)) {
                continue;
            }
            result1.add(item);
        }
        result1.sort(Comparator.comparingLong((DownloadRecord r) ->
                r.getAt() == null ? 0L : r.getAt()).reversed());
        if (limit > 0 && result1.size() > limit) {
            return List.copyOf(result1.subList(0, limit));
        }
        return List.copyOf(result1);
    }

    public static synchronized DownloadRecord record(DownloadRecord record) {
        ensureLoaded();
        if (record == null) {
            return null;
        }
        if (record.getAt() == null) {
            record.setAt(System.currentTimeMillis());
        }
        if (StrUtil.isBlank(record.getId())) {
            String key = StrUtil.blankToDefault(record.getAniId(), "unknown")
                    + ":" + StrUtil.blankToDefault(record.getInfoHash(), "")
                    + ":" + StrUtil.blankToDefault(record.getReName(), "unknown")
                    + ":" + record.getAt();
            record.setId(key);
        }
        ITEMS.add(0, record);
        trimAndSave();
        return record;
    }

    /**
     * 便捷记录
     */
    public static DownloadRecord record(String aniId, String title, String reName, String infoHash,
                                        Double episode, Long size, String source, String subgroup,
                                        Result result, String message) {
        return record(new DownloadRecord()
                .setAniId(aniId)
                .setTitle(title)
                .setReName(reName)
                .setInfoHash(infoHash)
                .setEpisode(episode)
                .setSize(size)
                .setSource(source)
                .setSubgroup(subgroup)
                .setResult(result == null ? Result.SUCCESS.name() : result.name())
                .setMessage(message)
                .setAt(System.currentTimeMillis()));
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

    /**
     * 按天聚合最近 days 天的下载量（含失败），用于首页趋势图
     */
    public static List<DayStat> dayStats(int days) {
        ensureLoaded();
        int d = Math.max(1, Math.min(days, 90));
        Map<String, int[]> counters = new LinkedHashMap<>();
        Map<String, Long> sizes = new LinkedHashMap<>();
        String today = DateUtil.format(new Date(), "yyyy-MM-dd");
        for (int i = d - 1; i >= 0; i--) {
            String key = DateUtil.format(DateUtil.offsetDay(DateUtil.parse(today, "yyyy-MM-dd"), -i), "yyyy-MM-dd");
            counters.put(key, new int[]{0, 0});
            sizes.put(key, 0L);
        }
        for (DownloadRecord item : ITEMS) {
            if (item.getAt() == null) {
                continue;
            }
            String key = DateUtil.format(new Date(item.getAt()), "yyyy-MM-dd");
            int[] counter = counters.get(key);
            if (counter == null) {
                continue;
            }
            if (Result.FAILED.name().equalsIgnoreCase(item.getResult())) {
                counter[1] += 1;
            } else {
                counter[0] += 1;
                sizes.merge(key, item.getSize() == null ? 0L : item.getSize(), Long::sum);
            }
        }
        List<DayStat> stats = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : counters.entrySet()) {
            stats.add(new DayStat(entry.getKey(), entry.getValue()[0], entry.getValue()[1],
                    sizes.getOrDefault(entry.getKey(), 0L)));
        }
        return stats;
    }

    /**
     * 总览统计（近 days 天，days<=0 表示全部）
     */
    public static Summary summary(int days) {
        ensureLoaded();
        Long since = null;
        if (days > 0) {
            since = DateUtil.offsetDay(new Date(), -days).getTime();
        }
        int success = 0;
        int failed = 0;
        int skip = 0;
        long size = 0L;
        long durationSum = 0L;
        int durationCount = 0;
        for (DownloadRecord item : ITEMS) {
            if (since != null && (item.getAt() == null || item.getAt() < since)) {
                continue;
            }
            String r = StrUtil.blankToDefault(item.getResult(), Result.SUCCESS.name());
            if (Result.FAILED.name().equalsIgnoreCase(r)) {
                failed++;
            } else if (Result.SKIP.name().equalsIgnoreCase(r)) {
                skip++;
            } else {
                success++;
                size += item.getSize() == null ? 0L : item.getSize();
            }
            if (item.getDurationMs() != null && item.getDurationMs() > 0) {
                durationSum += item.getDurationMs();
                durationCount++;
            }
        }
        int finished = success + failed;
        double rate = finished == 0 ? 1.0 : (double) success / finished;
        long avg = durationCount == 0 ? 0L : durationSum / durationCount;
        return new Summary(success + failed + skip, success, failed, skip, size, rate, avg);
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

    /**
     * 测试用：重置内存态
     */
    static synchronized void resetForTest() {
        ITEMS.clear();
        loaded = true;
        LAST_SAVED_JSON = null;
        LAST_SAVE_MS.set(0L);
        SAVE_SCHEDULED.set(false);
    }
}
