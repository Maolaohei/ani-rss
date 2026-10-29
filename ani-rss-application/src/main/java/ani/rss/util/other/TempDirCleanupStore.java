package ani.rss.util.other;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.TempDirCleanupRecord;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 临时目录清理债务的落盘仓库（清理 outbox）。
 * <p>
 * 位置：{@code {config}/torrents/.pending-cleanup/<id>.cleanup.json}。
 * <b>刻意与 {@link OfflinePlanStore} 分开</b>：计划快照是"归位"的依据，
 * 任务收尾时会被 {@code OpenList.releaseOfflinePlaceholder} 删掉，而清理债务恰恰要在
 * 那之后还活着——否则"移动成功、删除失败"就再也没有任何落盘线索，只能靠人工全量扫盘。
 * <p>
 * 写入走「临时文件 + 原子移动」；读到半截 JSON 只会被当成"没有债务"，不会影响下载。
 * 所有变更都同步到磁盘后才更新内存缓存，进程被杀也不会丢账。
 */
@Slf4j
public final class TempDirCleanupStore {

    public static final String SUFFIX = ".cleanup.json";

    /**
     * 仅对 {@link TempDirCleanupRecord.Phase#LEASED} 生效：超过这个时间还没归位的租约
     * 说明该次下载早已放弃，痕迹可以丢掉（真正要清的残留由临时目录扫描兜底）。
     */
    private static final long LEASE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * 人工态保留时间：别让"现场异常"的记录无限堆积。
     */
    private static final long NEEDS_HUMAN_KEEP_MS = 30L * 24 * 60 * 60 * 1000;

    /**
     * 重试上限：超过就转人工，不再自动打网盘（避免限流期被自己的巡检继续加压）。
     */
    public static final int MAX_ATTEMPTS = 8;

    /**
     * 退避序列：10s → 30s → 2m → 10m → 1h → 6h → 24h（之后按 24h 封顶）。
     * <p>
     * 头两档很短，是因为 115 的异步删除通常几秒内落地；后面拉长，是为了在
     * 网盘限流窗口（常见几分钟到几小时）里不再制造请求。
     */
    private static final long[] BACKOFF_MS = {
            10_000L, 30_000L, 120_000L, 600_000L, 3_600_000L, 21_600_000L, 86_400_000L
    };

    private static final long CACHE_TTL_MS = 30_000L;

    private static volatile List<TempDirCleanupRecord> cache;
    private static volatile long cacheAt;
    /**
     * 临时目录完整路径缓存（供媒体库列举时屏蔽"盘上还在、账已欠着"的目录）。
     * key=tempPath，value=phase。用 ConcurrentHashMap 便于高频只读。
     */
    private static final Map<String, TempDirCleanupRecord.Phase> PATH_CACHE = new ConcurrentHashMap<>();

    private TempDirCleanupStore() {
    }

    public static File dir() {
        File dir = new File(ConfigUtil.getConfigDir(), "torrents" + File.separator + ".pending-cleanup");
        FileUtil.mkdir(dir);
        return dir;
    }

    public static String idOf(String savePath, String tempDirName) {
        String raw = StrUtil.blankToDefault(savePath, "") + "\u0000" + StrUtil.blankToDefault(tempDirName, "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 理论上不会发生；退化成 hashCode 也不能让收尾流程炸掉
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static File fileOf(String id) {
        return new File(dir(), id + SUFFIX);
    }

    private static String tempPathOf(String savePath, String tempDirName) {
        return StrUtil.removeSuffix(StrUtil.blankToDefault(savePath, ""), "/") + "/" + tempDirName;
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 登记"临时目录已创建"（写前落盘）。
     * <p>
     * 已有同键记录时不覆盖阶段：重复提交同一集（洗版/重试）很常见，
     * 覆盖会把已经走到 DELETE_PENDING 的债务退回 LEASED，白等一轮。
     */
    public static synchronized void lease(String savePath, String tempDirName, String reName,
                                          String infoHash, String aniId, String finalRenameBase) {
        if (StrUtil.isBlank(savePath) || StrUtil.isBlank(tempDirName)) {
            return;
        }
        String id = idOf(savePath, tempDirName);
        long now = System.currentTimeMillis();
        TempDirCleanupRecord record = read(id);
        if (record == null) {
            record = new TempDirCleanupRecord()
                    .setId(id)
                    .setSavePath(savePath)
                    .setTempDirName(tempDirName)
                    .setTempPath(tempPathOf(savePath, tempDirName))
                    .setCreatedAt(now);
        }
        record.setSource(StrUtil.blankToDefault(record.getSource(), TempDirCleanupRecord.SOURCE_FINALIZE))
                .setReName(StrUtil.blankToDefault(reName, record.getReName()))
                .setInfoHash(StrUtil.blankToDefault(infoHash, record.getInfoHash()))
                .setAniId(StrUtil.blankToDefault(aniId, record.getAniId()))
                .setFinalRenameBase(StrUtil.blankToDefault(finalRenameBase, record.getFinalRenameBase()));
        // 阶段只允许前进：LEASED 可以被 markDeletePending 推进，回退一律忽略
        if (record.getPhase() == null) {
            record.setPhase(TempDirCleanupRecord.Phase.LEASED);
        }
        write(record.setUpdatedAt(now));
    }

    /**
     * 媒体已确认归位 → 债务转为待删除，并记下"哪些文件已经确认在顶层"作为删除前的安全门。
     */
    public static synchronized void markDeletePending(String savePath, String tempDirName,
                                                      java.util.Collection<String> finalizedNames) {
        if (StrUtil.isBlank(savePath) || StrUtil.isBlank(tempDirName)) {
            return;
        }
        String id = idOf(savePath, tempDirName);
        long now = System.currentTimeMillis();
        TempDirCleanupRecord record = read(id);
        if (record == null) {
            record = new TempDirCleanupRecord()
                    .setId(id)
                    .setSource(TempDirCleanupRecord.SOURCE_FINALIZE)
                    .setSavePath(savePath)
                    .setTempDirName(tempDirName)
                    .setTempPath(tempPathOf(savePath, tempDirName))
                    .setCreatedAt(now);
        }
        if (record.getPhase() == TempDirCleanupRecord.Phase.NEEDS_HUMAN) {
            // 人工态不被自动流程推翻；只补全信息
            write(record.setUpdatedAt(now));
            return;
        }
        record.setPhase(TempDirCleanupRecord.Phase.DELETE_PENDING)
                .setFinalizedNames(finalizedNames == null
                        ? new ArrayList<>()
                        : new ArrayList<>(new HashSet<>(finalizedNames)))
                .setAttempts(0)
                .setNextAttemptAt(now)
                .setLastError(null)
                .setUpdatedAt(now);
        write(record);
    }

    /**
     * 用户手工清理时登记债务：即使这次没删成，也要在账上留一条，交给巡检继续。
     */
    public static synchronized void requestOperator(String savePath, String tempDirName, String reason) {
        if (StrUtil.isBlank(savePath) || StrUtil.isBlank(tempDirName)) {
            return;
        }
        String id = idOf(savePath, tempDirName);
        long now = System.currentTimeMillis();
        TempDirCleanupRecord record = read(id);
        if (record == null) {
            record = new TempDirCleanupRecord()
                    .setId(id)
                    .setSavePath(savePath)
                    .setTempDirName(tempDirName)
                    .setTempPath(tempPathOf(savePath, tempDirName))
                    .setCreatedAt(now);
        }
        record.setSource(TempDirCleanupRecord.SOURCE_OPERATOR)
                .setPhase(TempDirCleanupRecord.Phase.DELETE_PENDING)
                .setNextAttemptAt(now)
                .setLastError(StrUtil.maxLength(StrUtil.blankToDefault(reason, ""), 200))
                .setUpdatedAt(now);
        write(record);
    }

    /**
     * 回读确认目录已不存在 → 销账。
     */
    public static synchronized void confirm(String id) {
        if (StrUtil.isBlank(id)) {
            return;
        }
        FileUtil.del(fileOf(id));
        invalidate();
    }

    /**
     * 这次没删成：次数 +1，按退避安排下次；超过上限转人工。
     */
    public static synchronized void defer(String id, String reason) {
        if (StrUtil.isBlank(id)) {
            return;
        }
        TempDirCleanupRecord record = read(id);
        if (record == null) {
            return;
        }
        int attempts = record.getAttempts() + 1;
        long now = System.currentTimeMillis();
        record.setAttempts(attempts)
                .setLastError(StrUtil.maxLength(StrUtil.blankToDefault(reason, ""), 200))
                .setUpdatedAt(now);
        if (attempts >= MAX_ATTEMPTS) {
            record.setPhase(TempDirCleanupRecord.Phase.NEEDS_HUMAN)
                    .setNextAttemptAt(0L);
            log.warn("临时目录清理重试 {} 次仍未确认，转人工处理 {}", attempts, record.getTempPath());
        } else {
            record.setNextAttemptAt(now + backoff(attempts));
        }
        write(record);
    }

    /**
     * 现场与预期不符：停止自动重试，等人处理（例如顶层同名项是文件、成片缺失）。
     */
    public static synchronized void needsHuman(String id, String reason) {
        if (StrUtil.isBlank(id)) {
            return;
        }
        TempDirCleanupRecord record = read(id);
        if (record == null) {
            return;
        }
        record.setPhase(TempDirCleanupRecord.Phase.NEEDS_HUMAN)
                .setNextAttemptAt(0L)
                .setLastError(StrUtil.maxLength(StrUtil.blankToDefault(reason, ""), 200))
                .setUpdatedAt(System.currentTimeMillis());
        write(record);
        log.warn("临时目录清理转人工 {}: {}", record.getTempPath(), reason);
    }

    private static long backoff(int attempts) {
        int idx = Math.max(0, Math.min(attempts - 1, BACKOFF_MS.length - 1));
        return BACKOFF_MS[idx];
    }

    // ------------------------------------------------------------------ 读取

    /**
     * 全部债务（按创建时间升序）。带 30s 缓存：媒体库列举路径会高频调用，
     * 但不能让一次盘读把每次列举都拖慢。
     */
    public static List<TempDirCleanupRecord> all() {
        List<TempDirCleanupRecord> snapshot = cache;
        if (snapshot != null && System.currentTimeMillis() - cacheAt < CACHE_TTL_MS) {
            return snapshot;
        }
        return reload();
    }

    private static synchronized List<TempDirCleanupRecord> reload() {
        long now = System.currentTimeMillis();
        File[] files = dir().listFiles((d, name) -> name.endsWith(SUFFIX));
        List<TempDirCleanupRecord> records = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                TempDirCleanupRecord record = readFile(file);
                if (record == null) {
                    FileUtil.del(file);
                    continue;
                }
                if (expired(record, now)) {
                    log.debug("清理债务记录已过期，丢弃 {}", file.getName());
                    FileUtil.del(file);
                    continue;
                }
                records.add(record);
            }
        }
        records.sort(Comparator.comparing(r -> r.getCreatedAt() == null ? 0L : r.getCreatedAt()));
        cache = records;
        cacheAt = now;
        refreshPathCache(records);
        return records;
    }

    private static boolean expired(TempDirCleanupRecord record, long now) {
        long createdAt = record.getCreatedAt() == null ? 0L : record.getCreatedAt();
        if (createdAt <= 0) {
            return false;
        }
        long age = now - createdAt;
        if (record.getPhase() == TempDirCleanupRecord.Phase.LEASED) {
            return age > LEASE_MAX_AGE_MS;
        }
        if (record.getPhase() == TempDirCleanupRecord.Phase.NEEDS_HUMAN) {
            return age > NEEDS_HUMAN_KEEP_MS;
        }
        // DELETE_PENDING 不按时间丢：它就是要还的账，直到确认或转人工
        return false;
    }

    /**
     * 到期待处理的债务（只取 DELETE_PENDING）。
     * <p>
     * LEASED 一律不取：那时媒体还没归位，删掉会毁掉正在下载或待重试归位的产物。
     */
    public static List<TempDirCleanupRecord> due(long now, int limit) {
        List<TempDirCleanupRecord> result = new ArrayList<>();
        for (TempDirCleanupRecord record : all()) {
            if (record.getPhase() != TempDirCleanupRecord.Phase.DELETE_PENDING) {
                continue;
            }
            if (record.getNextAttemptAt() > now) {
                continue;
            }
            result.add(record);
            if (result.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return result;
    }

    public static TempDirCleanupRecord find(String savePath, String tempDirName) {
        if (StrUtil.isBlank(savePath) || StrUtil.isBlank(tempDirName)) {
            return null;
        }
        return read(idOf(savePath, tempDirName));
    }

    public static TempDirCleanupRecord findById(String id) {
        return StrUtil.isBlank(id) ? null : read(id);
    }

    /**
     * 仍欠着删除的临时目录完整路径（含 NEEDS_HUMAN）。
     * <p>
     * 用途：媒体库列举时把"账上还欠着清理"的目录下的文件屏蔽掉——即使盘上还没删，
     * 也不会出现 {@code S1/S1E01.MKV} 与 {@code S1/S1E01/S1E01.MKV} 两份重复集。
     */
    public static Set<String> pendingTempPaths() {
        all();
        return new HashSet<>(PATH_CACHE.keySet());
    }

    public static int pendingCount() {
        int count = 0;
        for (TempDirCleanupRecord record : all()) {
            if (record.getPhase() == TempDirCleanupRecord.Phase.DELETE_PENDING) {
                count++;
            }
        }
        return count;
    }

    public static int needsHumanCount() {
        int count = 0;
        for (TempDirCleanupRecord record : all()) {
            if (record.getPhase() == TempDirCleanupRecord.Phase.NEEDS_HUMAN) {
                count++;
            }
        }
        return count;
    }

    public static synchronized void invalidate() {
        cache = null;
        cacheAt = 0L;
        PATH_CACHE.clear();
    }

    private static void refreshPathCache(List<TempDirCleanupRecord> records) {
        PATH_CACHE.clear();
        for (TempDirCleanupRecord record : records) {
            if (record.getPhase() == null) {
                continue;
            }
            String path = StrUtil.blankToDefault(record.getTempPath(),
                    tempPathOf(record.getSavePath(), record.getTempDirName()));
            if (StrUtil.isNotBlank(path)) {
                PATH_CACHE.put(path, record.getPhase());
            }
        }
    }

    // ------------------------------------------------------------------ 文件读写

    private static TempDirCleanupRecord read(String id) {
        return readFile(fileOf(id));
    }

    private static TempDirCleanupRecord readFile(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            TempDirCleanupRecord record = GsonStatic.fromJson(FileUtil.readUtf8String(file),
                    TempDirCleanupRecord.class);
            if (record == null || StrUtil.isBlank(record.getSavePath()) || StrUtil.isBlank(record.getTempDirName())) {
                return null;
            }
            if (StrUtil.isBlank(record.getId())) {
                record.setId(idOf(record.getSavePath(), record.getTempDirName()));
            }
            return record;
        } catch (Exception e) {
            log.debug("读取清理债务失败，按无债务处理 {}: {}", file.getName(), e.getMessage());
            return null;
        }
    }

    private static void write(TempDirCleanupRecord record) {
        if (record == null || StrUtil.isBlank(record.getId())) {
            return;
        }
        File target = fileOf(record.getId());
        File temp = new File(target.getParentFile(), target.getName() + ".temp");
        try {
            FileUtil.writeUtf8String(GsonStatic.toJson(record), temp);
            FileUtils.move(temp.toPath(), target.toPath());
        } catch (Exception e) {
            log.debug("保存清理债务失败 {}: {}", record.getId(), e.getMessage());
        } finally {
            FileUtil.del(temp);
        }
        invalidate();
    }
}
