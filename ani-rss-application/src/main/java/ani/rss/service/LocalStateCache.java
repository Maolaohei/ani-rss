package ani.rss.service;

import ani.rss.entity.Ani;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订阅级「本地状态快照」缓存（F2）。
 * <p>
 * 解决的问题：预览、媒体库、RSS 主流程、手动搜索四个入口各自去问"这个订阅的下载目录里
 * 到底有哪些集"，网盘模式下每次问都是若干次真实 API 调用。同一份数据在一轮里被重复列举
 * 是纯粹的浪费，还会把网盘推到限流边缘。
 * <p>
 * 三条设计原则：
 * <ul>
 *   <li><b>不落盘</b>：纯内存。重启后从空开始，首轮全部走"存疑"，再逐步收敛。
 *       缓存脏了比缓存空更糟——空只是慢一点，脏会让用户看到"本地不存在"而重复下载。</li>
 *   <li><b>单飞</b>：同一 key 并发构建只跑一次，其余线程等同一份结果。没有单飞的话，
 *       预览与 RSS 主流程同时打开同一订阅就会把同一份列举做两遍，缓存反而失去意义。</li>
 *   <li><b>失效优先于过期</b>：TTL 只是兜底。真正保证正确性的是失效钩子——下载完成、
 *       改名完成、强制下载、删除种子、订阅/模板变更都必须立刻失效，否则用户刚下完
 *       刷新预览却还是旧结果。</li>
 * </ul>
 * <p>
 * 失效与构建的竞态：构建开始时记下版本号，构建结束时若版本已变则<b>丢弃结果不写回</b>。
 * 少了这一步，"失效"会被一个更早开始、更晚结束的旧构建覆盖掉。
 */
@Slf4j
public final class LocalStateCache {

    private LocalStateCache() {
    }

    /**
     * 快照来源。决定 TTL，也解释"这份数据有多贵"。
     */
    public enum Source {
        /**
         * 本地磁盘目录遍历，便宜
         */
        LOCAL_DISK,
        /**
         * 网盘 API 列举，贵（受限流与预算约束）
         */
        CLOUD_API
    }

    /**
     * 索引加载结果：索引本身 + 是否完整。
     * <p>
     * 为什么不完整也要缓存：截断只影响"断言不存在"的能力，不影响"确认存在"的能力。
     * 丢掉整份结果反而会让所有条目都变成"存疑"，代价更大。
     */
    public static final class Loaded {
        private final Set<String> episodeIndex;
        private final boolean complete;

        private Loaded(Set<String> episodeIndex, boolean complete) {
            this.episodeIndex = episodeIndex == null ? Set.of() : episodeIndex;
            this.complete = complete;
        }

        public static Loaded of(Set<String> episodeIndex) {
            return new Loaded(episodeIndex, true);
        }

        public static Loaded of(Set<String> episodeIndex, boolean complete) {
            return new Loaded(episodeIndex, complete);
        }

        public Set<String> episodeIndex() {
            return episodeIndex;
        }

        public boolean complete() {
            return complete;
        }
    }

    /**
     * 索引加载器。构建失败<b>必须</b>抛异常：失败结果绝不入缓存，
     * 否则一次网盘抖动会在整个 TTL 内被固化成"目录里什么都没有"。
     */
    @FunctionalInterface
    public interface Loader {
        Loaded load() throws Exception;
    }

    /**
     * 一份订阅级快照
     */
    public static final class Snapshot {
        private final Set<String> episodeIndex;
        private final Source source;
        private final long builtAt;
        private final boolean complete;

        Snapshot(Set<String> episodeIndex, Source source, long builtAt, boolean complete) {
            this.episodeIndex = episodeIndex == null ? Set.of() : episodeIndex;
            this.source = source;
            this.builtAt = builtAt;
            this.complete = complete;
        }

        /**
         * 集数索引，形如 {@code "1:5"}（季:集）或 {@code "M:主名"}（剧场版）
         */
        public Set<String> episodeIndex() {
            return episodeIndex;
        }

        public Source source() {
            return source;
        }

        /**
         * 构建完成时刻（毫秒），供界面显示"数据有多旧"
         */
        public long builtAt() {
            return builtAt;
        }

        /**
         * 索引是否完整。false 表示列举被截断，只能确认"存在"、不能断言"不存在"。
         */
        public boolean complete() {
            return complete;
        }

        long ageMs() {
            return System.currentTimeMillis() - builtAt;
        }
    }

    // ---- 配置常量 ----
    static final int DEFAULT_LOCAL_TTL_SECONDS = 60;
    static final int MIN_LOCAL_TTL_SECONDS = 10;
    static final int MAX_LOCAL_TTL_SECONDS = 3600;
    static final int DEFAULT_CLOUD_TTL_SECONDS = 300;
    static final int MIN_CLOUD_TTL_SECONDS = 30;
    static final int MAX_CLOUD_TTL_SECONDS = 3600;
    /**
     * LRU 容量下限。订阅很少时也要留够，避免"订阅 1 个却每 2 次就淘汰"。
     */
    static final int MIN_CAPACITY = 64;
    /**
     * 单飞等待上限。等待方绝不无限期挂起——超时就当"本次校验失败"（→ 存疑），
     * 让调用方继续往下走，而不是把整轮 RSS 拖死。
     */
    static final long MAX_SINGLE_FLIGHT_WAIT_MS = 30_000L;

    private static final Map<String, Snapshot> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Snapshot>> IN_FLIGHT = new ConcurrentHashMap<>();
    /**
     * 按订阅 id 计的版本号。失效时 +1；构建完成时校验未变才写回。
     */
    private static final Map<String, AtomicLong> VERSIONS = new ConcurrentHashMap<>();
    /**
     * 按下载路径摘要计的版本号。
     * <p>
     * 为什么需要它：改名完成 / 离线落地这类事件只知道"下载目录"，不知道订阅 id
     * （下载器的任务上不带订阅信息）。若只按订阅失效，就只能退化成"整体失效"，
     * 而一轮里每下完一集就整体清空一次缓存，缓存等于没有。
     */
    private static final Map<String, AtomicLong> PATH_VERSIONS = new ConcurrentHashMap<>();
    /**
     * 全局世代号。整体失效（模板变更 / 订阅增删）时 +1。
     */
    private static final AtomicLong EPOCH = new AtomicLong(0L);

    // ---- 可观测计数（F2 验收用）----
    private static final AtomicLong hit = new AtomicLong(0L);
    private static final AtomicLong miss = new AtomicLong(0L);
    private static final AtomicLong coalesced = new AtomicLong(0L);
    private static final AtomicLong buildCount = new AtomicLong(0L);
    private static final AtomicLong expiredCount = new AtomicLong(0L);
    private static final AtomicLong invalidatedDropCount = new AtomicLong(0L);

    /**
     * 缓存键：{@code aniId|sha1(downloadPath)}。
     * <p>
     * 带下载路径是为了让"模板改了"这件事自然落到另一个 key 上——
     * 旧 key 会被 TTL/LRU 自然淘汰，不需要精确追踪哪些订阅受影响。
     */
    public static String key(String aniId, String downloadPath) {
        return StrUtil.blankToDefault(aniId, "unknown") + pathSuffix(downloadPath);
    }

    static String key(Ani ani, String downloadPath) {
        return key(ani == null ? null : ani.getId(), downloadPath);
    }

    /**
     * 取快照，未命中/已过期则构建（含单飞）。
     *
     * @throws Exception 构建失败时原样抛出，调用方据此回退"记录判定 + 存疑"
     */
    public static Snapshot getOrBuild(Ani ani, String downloadPath, Source source, Loader loader)
            throws Exception {
        String aniId = ani == null ? null : ani.getId();
        String cacheKey = key(aniId, downloadPath);

        Snapshot cached = getFresh(cacheKey);
        if (cached != null) {
            hit.incrementAndGet();
            return cached;
        }
        miss.incrementAndGet();

        long versionAtStart = versionOf(aniId).get();
        long pathVersionAtStart = pathVersionOf(pathSuffix(downloadPath)).get();
        long epochAtStart = EPOCH.get();

        CompletableFuture<Snapshot> mine = new CompletableFuture<>();
        CompletableFuture<Snapshot> existing = IN_FLIGHT.putIfAbsent(cacheKey, mine);
        if (existing != null) {
            // 同一 key 已有构建在跑：等它的结果，不再重复列举
            coalesced.incrementAndGet();
            return await(existing, cacheKey);
        }

        try {
            Loaded loaded = loader.load();
            buildCount.incrementAndGet();
            Snapshot snapshot = new Snapshot(loaded.episodeIndex(), source,
                    System.currentTimeMillis(), loaded.complete());
            if (versionOf(aniId).get() == versionAtStart
                    && pathVersionOf(pathSuffix(downloadPath)).get() == pathVersionAtStart
                    && EPOCH.get() == epochAtStart) {
                CACHE.put(cacheKey, snapshot);
                evictIfNeeded();
            } else {
                // 构建期间发生过失效：结果已过时，丢弃不写回（下次重新构建）
                invalidatedDropCount.incrementAndGet();
                log.debug("快照构建期间缓存已失效，丢弃本次结果 {}", cacheKey);
            }
            mine.complete(snapshot);
            return snapshot;
        } catch (Exception e) {
            // 失败不入缓存：一次网盘抖动不该被固化成"目录里什么都没有"
            mine.completeExceptionally(e);
            throw e;
        } finally {
            IN_FLIGHT.remove(cacheKey, mine);
        }
    }

    /**
     * 等待单飞结果。超时/失败一律抛异常（→ 调用方标「存疑」），绝不返回空索引冒充"目录为空"。
     */
    private static Snapshot await(CompletableFuture<Snapshot> future, String cacheKey) throws Exception {
        try {
            return future.get(MAX_SINGLE_FLIGHT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new IllegalStateException("等待同一目录列举结果失败: " + cacheKey, cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException("等待同一目录列举结果超时（" + MAX_SINGLE_FLIGHT_WAIT_MS
                    + "ms）: " + cacheKey, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待同一目录列举结果被中断: " + cacheKey, e);
        }
    }

    private static Snapshot getFresh(String cacheKey) {
        Snapshot snapshot = CACHE.get(cacheKey);
        if (snapshot == null) {
            return null;
        }
        if (snapshot.ageMs() > resolveTtlMs(snapshot.source())) {
            CACHE.remove(cacheKey, snapshot);
            expiredCount.incrementAndGet();
            return null;
        }
        return snapshot;
    }

    /**
     * 失效某订阅的全部快照（含下载路径变化前的旧 key）。
     * <p>
     * 必须同时递增版本号：否则一个"失效前开始、失效后才结束"的构建会把旧结果写回来，
     * 用户刚下完却看到"本地不存在"。
     */
    public static void invalidate(String aniId) {
        if (StrUtil.isBlank(aniId)) {
            return;
        }
        String prefix = aniId + "|";
        List<String> stale = new ArrayList<>();
        for (String k : CACHE.keySet()) {
            if (k.startsWith(prefix)) {
                stale.add(k);
            }
        }
        for (String k : stale) {
            CACHE.remove(k);
        }
        versionOf(aniId).incrementAndGet();
    }

    public static void invalidate(Ani ani) {
        if (ani == null) {
            return;
        }
        invalidate(ani.getId());
    }

    /**
     * 按下载目录失效（改名完成 / 离线落地 / 强制下载删文件后目录内容已变）。
     * <p>
     * 不需要反查订阅：缓存键里带了路径摘要，按后缀精确命中即可。
     * 同时递增该路径的版本号，拦住"失效前开始、失效后才结束"的在途构建。
     */
    public static void invalidateByDownloadPath(String downloadPath) {
        if (StrUtil.isBlank(downloadPath)) {
            // 拿不到路径（下载器未配置等）：宁可整体失效，也不能给错数据
            invalidateAll();
            return;
        }
        String suffix = pathSuffix(downloadPath);
        List<String> stale = new ArrayList<>();
        for (String k : CACHE.keySet()) {
            if (k.endsWith(suffix)) {
                stale.add(k);
            }
        }
        for (String k : stale) {
            CACHE.remove(k);
            int idx = k.lastIndexOf('|');
            if (idx > 0) {
                versionOf(k.substring(0, idx)).incrementAndGet();
            }
        }
        pathVersionOf(suffix).incrementAndGet();
    }

    /**
     * 整体失效：订阅列表增删、下载路径模板/重命名开关变更时使用。
     * <p>
     * 这类变更会同时影响所有订阅的下载路径，逐订阅失效既慢又容易漏，直接换代更可靠。
     */
    public static void invalidateAll() {
        CACHE.clear();
        EPOCH.incrementAndGet();
    }

    /**
     * 仅供测试：清空缓存与计数
     */
    public static void clear() {
        CACHE.clear();
        IN_FLIGHT.clear();
        VERSIONS.clear();
        PATH_VERSIONS.clear();
        EPOCH.set(0L);
        resetStats();
    }

    public static void resetStats() {
        hit.set(0L);
        miss.set(0L);
        coalesced.set(0L);
        buildCount.set(0L);
        expiredCount.set(0L);
        invalidatedDropCount.set(0L);
    }

    public static int size() {
        return CACHE.size();
    }

    /**
     * 仅供测试：直接写入一份快照（绕过构建流程），用于确定性地验证 TTL 过期与 LRU 淘汰。
     * <p>
     * 之所以需要它：TTL 下限是 10 秒，靠 sleep 验证过期会让测试慢到不可接受。
     * 生产代码不得调用。
     */
    static void putForTest(String aniId, String downloadPath, Set<String> index,
                           Source source, long builtAt) {
        CACHE.put(key(aniId, downloadPath), new Snapshot(index, source, builtAt, true));
    }

    public static long getHit() {
        return hit.get();
    }

    public static long getMiss() {
        return miss.get();
    }

    /**
     * 单飞命中次数（本可重复列举、被合并掉的次数）
     */
    public static long getCoalesced() {
        return coalesced.get();
    }

    /**
     * 真正执行过的索引构建次数（= 真实列举次数）
     */
    public static long getBuildCount() {
        return buildCount.get();
    }

    public static long getExpiredCount() {
        return expiredCount.get();
    }

    /**
     * 构建期间遭遇失效而被丢弃的次数（>0 说明失效钩子与构建有交叠，属正常但值得观察）
     */
    public static long getInvalidatedDropCount() {
        return invalidatedDropCount.get();
    }

    /**
     * 缓存命中率。无样本时返回 0 而非 NaN，避免前端显示 {@code NaN%}
     */
    public static double getHitRate() {
        long h = hit.get();
        long total = h + miss.get();
        return total == 0L ? 0.0 : (double) h / total;
    }

    /**
     * 诊断用摘要
     */
    public static Map<String, Object> stats() {
        return Map.of(
                "size", size(),
                "hit", getHit(),
                "miss", getMiss(),
                "coalesced", getCoalesced(),
                "build", getBuildCount(),
                "expired", getExpiredCount(),
                "invalidatedDrop", getInvalidatedDropCount(),
                "hitRate", getHitRate()
        );
    }

    private static AtomicLong versionOf(String aniId) {
        return VERSIONS.computeIfAbsent(StrUtil.blankToDefault(aniId, "unknown"), k -> new AtomicLong(0L));
    }

    private static AtomicLong pathVersionOf(String suffix) {
        return PATH_VERSIONS.computeIfAbsent(suffix, k -> new AtomicLong(0L));
    }

    /**
     * 路径摘要后缀（与 {@link #key(String, String)} 的后半段一致）
     */
    static String pathSuffix(String downloadPath) {
        return "|" + sha1(downloadPath);
    }

    /**
     * LRU 容量：{@code max(64, 订阅数 × 2)}。
     * <p>
     * ×2 是因为同一订阅在模板变更前后可能同时存在两个 key；给足余量避免刚构建完就被淘汰。
     */
    public static int resolveCapacity() {
        int aniCount = 0;
        try {
            List<Ani> list = AniUtil.getAniList();
            aniCount = list == null ? 0 : list.size();
        } catch (Exception e) {
            // 启动早期 ani 列表尚未初始化：退回下限即可
            log.debug("读取订阅数失败，缓存容量退回下限: {}", e.getMessage());
        }
        return Math.max(MIN_CAPACITY, aniCount * 2);
    }

    private static void evictIfNeeded() {
        int capacity = resolveCapacity();
        if (CACHE.size() <= capacity) {
            return;
        }
        List<Map.Entry<String, Snapshot>> entries = new ArrayList<>(CACHE.entrySet());
        entries.sort(Comparator.comparingLong(e -> e.getValue().builtAt));
        int overflow = CACHE.size() - capacity;
        for (int i = 0; i < overflow && i < entries.size(); i++) {
            CACHE.remove(entries.get(i).getKey(), entries.get(i).getValue());
        }
    }

    /**
     * 分级 TTL：网盘比本地磁盘贵得多，缓存也更久
     */
    public static long resolveTtlMs(Source source) {
        Integer configured = ConfigUtil.CONFIG == null
                ? null
                : (source == Source.CLOUD_API
                ? ConfigUtil.CONFIG.getCloudStateCacheTtlSeconds()
                : ConfigUtil.CONFIG.getLocalStateCacheTtlSeconds());
        if (source == Source.CLOUD_API) {
            return intConfig(configured, DEFAULT_CLOUD_TTL_SECONDS,
                    MIN_CLOUD_TTL_SECONDS, MAX_CLOUD_TTL_SECONDS) * 1000L;
        }
        return intConfig(configured, DEFAULT_LOCAL_TTL_SECONDS,
                MIN_LOCAL_TTL_SECONDS, MAX_LOCAL_TTL_SECONDS) * 1000L;
    }

    static int intConfig(Integer value, int fallback, int min, int max) {
        int v = value == null ? fallback : value;
        return Math.max(min, Math.min(v, max));
    }

    /**
     * 不引第三方摘要工具：路径本身没有敏感信息，摘要只是为了把 key 压短。
     * 摘要不可用时退回原串，功能不受影响。
     */
    static String sha1(String text) {
        String value = text == null ? "" : text;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return value;
        }
    }
}
