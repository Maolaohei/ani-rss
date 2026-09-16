package ani.rss.commons;

import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内通用缓存。
 *
 * <h2>为什么不再用 {@code @Synchronized} + {@code FIFOCache}</h2>
 * (P2-11) 原实现给每个方法都挂了 {@code @Synchronized("CACHE")}——也就是<b>全应用一把锁</b>。
 * 而 {@code HttpReq.isProxy(url)} 对<b>每一个 HTTP 请求</b>都要先 {@code CacheUtils.get(key)}，
 * 于是"每次出网都先抢一把全局锁"。在 RSS 并发 + 前端轮询的场景下，这把锁把所有出网点串在了一起，
 * 而它保护的内容其实只是"读一个 Map"。
 * <p>
 * 另外 hutool 的 {@code FIFOCache} <b>本身不是线程安全的</b>（连 {@code get} 都可能改内部结构），
 * 所以那把锁并不能简单去掉——只能换掉底层结构。现在改为 {@link ConcurrentHashMap}：
 * 读路径完全无锁，写路径按 key 分段竞争。
 *
 * <h2>语义与原来保持一致</h2>
 * <ul>
 *   <li>{@code put(k, v)}：不过期。{@code put(k, v, timeout)}：{@code timeout <= 0} 视为不过期
 *       （与原 {@code FIFOCache} 的 {@code CacheObj} 判定一致）。</li>
 *   <li>过期项在 {@code get} 时惰性剔除，对调用方表现为"不存在"。</li>
 *   <li>容量上限仍是 {@value #MAX_ENTRIES}（与原 {@code newFIFOCache(1024 * 8)} 一致）：
 *       超限时先清过期项，仍超限则按<b>插入顺序</b>淘汰最旧的（原 FIFO 语义）。</li>
 * </ul>
 *
 * <h2>与原来的唯一行为差异</h2>
 * 本类<b>不接受 null 值</b>：{@code put(k, null)} 等价于 {@code remove(k)}。
 * 原实现（基于 HashMap）能存 null，但 {@code get} 回来也是 null，与"不存在"无法区分，
 * 所以这个差异对调用方不可观测；显式处理是为了避免 {@link ConcurrentHashMap} 直接抛 NPE。
 */
@Slf4j
public class CacheUtils {

    /**
     * 容量上限，超出后清理过期项、再按插入顺序淘汰最旧的
     */
    private static final int MAX_ENTRIES = 1024 * 8;

    private static final ConcurrentHashMap<Object, Entry> CACHE = new ConcurrentHashMap<>();

    /**
     * 插入序号，用于"按插入顺序淘汰"。单调递增，只在 {@code put} 时取号。
     */
    private static final AtomicLong SEQUENCE = new AtomicLong(0L);

    private CacheUtils() {
    }

    /**
     * 缓存项。
     *
     * @param value        值（非 null）
     * @param expireAtNanos 过期时刻（{@link System#nanoTime()} 基准）；{@link Long#MAX_VALUE} 表示不过期
     * @param seq          插入序号
     */
    private record Entry(Object value, long expireAtNanos, long seq) {
        /**
         * 用 {@code now - expireAt >= 0} 而不是 {@code now > expireAt}：
         * {@link System#nanoTime()} 的绝对值无意义且会回绕，相减才安全。
         */
        boolean expired(long now) {
            return now - expireAtNanos >= 0;
        }
    }

    @SuppressWarnings("unchecked")
    public static <V> V get(Object key) {
        if (key == null) {
            return null;
        }
        Entry entry = CACHE.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expired(System.nanoTime())) {
            CACHE.remove(key, entry);
            return null;
        }
        log.debug("get key [{}]", key);
        return (V) entry.value();
    }

    public static void put(Object key, Object object) {
        put(key, object, 0L);
    }

    public static void put(Object key, Object object, long timeout) {
        if (key == null) {
            return;
        }
        if (object == null) {
            // 见类注释：null 等价于删除
            remove(key);
            return;
        }
        log.debug("put key [{}] timeout [{}]", key, timeout);
        long expireAt = timeout > 0L ? System.nanoTime() + timeout * 1_000_000L : Long.MAX_VALUE;
        CACHE.put(key, new Entry(object, expireAt, SEQUENCE.incrementAndGet()));
        evictIfNeeded();
    }

    public static boolean containsKey(Object key) {
        return get(key) != null;
    }

    public static void remove(Object key) {
        if (key == null) {
            return;
        }
        if (CACHE.remove(key) != null) {
            log.debug("remove key [{}]", key);
        }
    }

    /**
     * 仅供测试：清空全部条目。
     * <p>
     * 这是 JVM 级静态状态，用例之间会互相串数据（表现为"单独跑通过、全量跑失败"），
     * 所以测试的 {@code @BeforeEach} / {@code @AfterEach} 里要调它。
     * 之所以是 {@code public}：用它的测试类分散在多个包下。
     */
    public static void clearForTest() {
        CACHE.clear();
    }

    /**
     * 超限时清理。先清过期项；仍超限则按插入顺序淘汰最旧的。
     * <p>
     * 不做互斥：并发触发是幂等的（重复删除无害），而给它加锁等于把刚去掉的全局锁装回来。
     */
    private static void evictIfNeeded() {
        int size = CACHE.size();
        if (size <= MAX_ENTRIES) {
            return;
        }
        long now = System.nanoTime();
        CACHE.entrySet().removeIf(e -> e.getValue().expired(now));

        int overflow = CACHE.size() - MAX_ENTRIES;
        if (overflow <= 0) {
            return;
        }
        List<Map.Entry<Object, Entry>> victims = CACHE.entrySet()
                .stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().seq()))
                .limit(overflow)
                .toList();
        for (Map.Entry<Object, Entry> victim : victims) {
            CACHE.remove(victim.getKey(), victim.getValue());
        }
    }
}
