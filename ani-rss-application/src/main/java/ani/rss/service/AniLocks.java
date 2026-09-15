package ani.rss.service;

import ani.rss.entity.Ani;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * 订阅级读写锁注册表（F6-1）。
 * <p>
 * 替代原先的 {@code DownloadService.ANI_LOCKS}（纯互斥对象）。升级为读写锁是为了让
 * "读"与"写"不再互相排队：
 * <ul>
 *   <li><b>写</b>：RSS 更新、{@code downloadAni}、强制下载、重试失败项、改名后回写、删除种子。
 *       同一订阅的写操作必须串行——两个写同时改同一份本地状态正是重复下载与状态错乱的来源。</li>
 *   <li><b>读</b>：预览、媒体库、手动搜索、状态查询。多个读之间互不阻塞。</li>
 * </ul>
 *
 * <h2>为什么读锁用 try 而不是无条件等待</h2>
 * 写锁在下载期间会持有<b>分钟级</b>的时间。如果预览无条件等读锁，用户点开预览就会卡住几分钟——
 * 这比"读到稍旧的数据"糟糕得多。因此读路径统一走
 * {@link #callWithTryRead(Ani, long, Supplier)}：只等很短一段（默认 {@value #DEFAULT_TRY_READ_MS}ms），
 * 等不到就<b>不等了直接读</b>。一致性由 {@link LocalStateCache} 的 TTL 快照兜底——
 * 缓存本身就是"上一次写完成后的结果"，读到它是安全的。
 *
 * <h2>加锁顺序（禁止逆序）</h2>
 * 全局轮次锁 → 静默窗口 → 本订阅写锁 → 令牌桶 → 快照缓存。
 * 任何路径不得持有本锁的同时去等待全局轮次锁。
 *
 * <h2>两个必须遵守的约束</h2>
 * <ol>
 *   <li><b>持读锁的代码不得再取写锁</b>。{@link ReentrantReadWriteLock} 不允许读→写升级，
 *       那样会自锁死。持写锁的代码再取读锁是允许的（降级）。</li>
 *   <li><b>只在外层入口加锁</b>。写锁可重入（同线程），但依赖重入会把锁边界散到各处，
 *       后续维护者很难判断谁才是真正的临界区。</li>
 * </ol>
 */
@Slf4j
public final class AniLocks {

    private AniLocks() {
    }

    /**
     * 读路径的默认尝试时长。取 300ms 的理由：足够覆盖一次"回写运行时状态并落盘"的短临界区，
     * 又短到用户完全感觉不到等待；真正长持锁的下载场景直接放弃等待，改用缓存。
     */
    public static final long DEFAULT_TRY_READ_MS = 300L;

    private static final Map<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<>();

    /**
     * 读锁未能按时获取、退化为"不持锁读"的次数（可观测）。
     * <p>
     * 长期偏高说明写锁被长时间持有（通常是下载），此时界面数据来自缓存，
     * 属预期行为；若同时伴随数据陈旧感，应调大快照 TTL 或缩短下载等待。
     */
    private static final AtomicLong readFallback = new AtomicLong(0L);

    /**
     * 订阅 id → 锁键。id 缺失时退化为同一个 {@code unknown} 键：宁可让这类异常订阅互相排队，
     * 也不能让它们各自拿到不同的锁从而并发改同一份状态。
     */
    public static String idOf(Ani ani) {
        if (ani == null) {
            return "unknown";
        }
        return idOf(ani.getId());
    }

    public static String idOf(String aniId) {
        return StrUtil.blankToDefault(aniId, "unknown");
    }

    public static ReentrantReadWriteLock lockFor(Ani ani) {
        return lockFor(idOf(ani));
    }

    public static ReentrantReadWriteLock lockFor(String aniId) {
        return LOCKS.computeIfAbsent(idOf(aniId), k -> new ReentrantReadWriteLock());
    }

    // ---------------- 写 ----------------

    /**
     * 写锁内执行（同线程可重入）
     */
    public static void runWithWrite(Ani ani, Runnable action) {
        if (action == null) {
            return;
        }
        ReentrantReadWriteLock.WriteLock lock = lockFor(ani).writeLock();
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 写锁内执行并返回结果（同线程可重入）
     */
    public static <T> T callWithWrite(Ani ani, Supplier<T> action) {
        if (action == null) {
            return null;
        }
        ReentrantReadWriteLock.WriteLock lock = lockFor(ani).writeLock();
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    // ---------------- 读 ----------------

    /**
     * 尽力在 {@code timeoutMs} 内取得读锁并执行；取不到就<b>不持锁直接执行</b>。
     * <p>
     * 无论哪条分支都会执行 {@code action}，调用方不必处理"没拿到锁"的情况——
     * 这正是本方法的目的：让读路径既能在写操作收尾后拿到一致视图，
     * 又永远不会被长持锁的下载拖住。
     *
     * @param timeoutMs 尝试时长；非正数表示只试一次（{@code tryLock()}）
     */
    public static <T> T callWithTryRead(Ani ani, long timeoutMs, Supplier<T> action) {
        if (action == null) {
            return null;
        }
        ReentrantReadWriteLock.ReadLock lock = lockFor(ani).readLock();
        boolean acquired;
        if (timeoutMs <= 0L) {
            acquired = lock.tryLock();
        } else {
            try {
                acquired = lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // 读锁等待被中断：不算失败，按"没拿到"处理，直接执行
                Thread.currentThread().interrupt();
                acquired = false;
            }
        }
        if (!acquired) {
            readFallback.incrementAndGet();
            log.debug("订阅 {} 的写锁正被占用（>{}ms），本次读取不持锁、直接使用缓存数据",
                    idOf(ani), timeoutMs);
            return action.get();
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public static void runWithTryRead(Ani ani, long timeoutMs, Runnable action) {
        if (action == null) {
            return;
        }
        callWithTryRead(ani, timeoutMs, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callWithTryRead(Ani ani, Supplier<T> action) {
        return callWithTryRead(ani, DEFAULT_TRY_READ_MS, action);
    }

    // ---------------- 诊断 ----------------

    /**
     * 当前登记在册的订阅锁数量。订阅删除后锁对象不回收（数量级很小，且回收需要额外的引用计数，
     * 收益不抵复杂度），这里只用于诊断与测试。
     */
    public static int size() {
        return LOCKS.size();
    }

    public static long getReadFallbackCount() {
        return readFallback.get();
    }

    /**
     * 仅供测试：清空注册表与计数。
     * <p>
     * 注意：若此时仍有线程持锁，清空会让后续的加锁落到<b>新的</b>锁对象上，
     * 从而绕过互斥。只允许在没有并发任务的测试环境调用。
     */
    static void clearForTest() {
        LOCKS.clear();
        readFallback.set(0L);
    }
}
