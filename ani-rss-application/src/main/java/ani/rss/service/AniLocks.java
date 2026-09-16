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
 *
 * <h2>锁覆盖矩阵（读之前先看这张表，别假设"加了锁所以安全"）</h2>
 * <table border="1">
 *   <caption>订阅锁的实际覆盖面</caption>
 *   <tr><th>路径</th><th>是否取订阅锁</th><th>说明</th></tr>
 *   <tr>
 *     <td>预览 {@code AniController.previewAni}</td>
 *     <td>读（{@code callWithTryRead}，300ms）</td>
 *     <td>F6-1</td>
 *   </tr>
 *   <tr>
 *     <td>手动搜索 {@code ManualSearchController}</td>
 *     <td>读（{@code callWithTryRead}）</td>
 *     <td>F6-1</td>
 *   </tr>
 *   <tr>
 *     <td>媒体库详情 {@code LibraryController.libraryDetail}</td>
 *     <td>读（{@code callWithTryRead}）</td>
 *     <td>F6-1</td>
 *   </tr>
 *   <tr>
 *     <td>媒体库批量 {@code LibraryController.library}</td>
 *     <td><b>不取</b></td>
 *     <td>F6-5 矩阵刻意为之：批量路径遍历全部订阅，逐个 {@code tryLock(300ms)} 在多订阅
 *         同时下载时会线性叠加成秒级延迟；它读的只是展示计数，不会诱发写动作</td>
 *   </tr>
 *   <tr>
 *     <td><b>RSS 轮次主判定路径</b>（{@code RssTask} 构建本地集数索引、列举网盘、
 *         判定 {@code itemDownloaded}）</td>
 *     <td><b>不取</b></td>
 *     <td><b>这是最重要的一条</b>：F6 的"读写互斥"实际只保护了三个展示面。
 *         主判定路径靠 F4 静默窗口（下载/改名窗口内按 infoHash 识别在途任务）兜底，
 *         而不是靠订阅锁。见 {@code 稳定性与性能专项优化清单.md} P1-6——
 *         若要给判定段补锁，<b>只能用 {@code callWithTryRead}</b>，
 *         绝不能改成无条件 {@code lock()}：写锁在下载期间是分钟级，会让整轮卡死</td>
 *   </tr>
 *   <tr>
 *     <td>写：{@code DownloadService.downloadAni / retryFailedItem / forceDownloadItem}</td>
 *     <td>写</td>
 *     <td>F6-2</td>
 *   </tr>
 *   <tr>
 *     <td>写：{@code RenameTask}（整段）、{@code TorrentController.deleteTorrent}</td>
 *     <td>写</td>
 *     <td>F6-2；{@code RenameTask} 用 {@code findAniByDownloadPath} 反查订阅拿锁键</td>
 *   </tr>
 * </table>
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

    // ---------------- 回收 ----------------

    /**
     * 订阅被删除/被替换后回收它的锁对象（P2-4）。
     * <p>
     * 原实现里 {@link #LOCKS} 只增不删：反复增删订阅会让锁对象常驻（长期运行缓慢泄漏）。
     * <p>
     * <b>只在能安全回收时才回收</b>——必须同时满足：
     * <ol>
     *   <li>能立刻拿到写锁（说明当前没有读者/写者持有它）。拿不到通常意味着该订阅正在下载或改名，
     *       此时<b>直接放弃回收</b>：让残留的锁对象继续生效，比"换一把新锁从而绕过互斥"安全得多。</li>
     *   <li>没有线程在排队等这把锁（否则移除后它仍持旧锁、新请求拿新锁，互斥被绕开）。</li>
     * </ol>
     * 因此本方法<b>是尽力而为的</b>：漏掉几个锁对象不会造成任何功能问题，最坏情况是下次删除时再回收。
     */
    public static void release(Ani ani) {
        release(idOf(ani));
    }

    public static void release(String aniId) {
        String key = idOf(aniId);
        ReentrantReadWriteLock lock = LOCKS.get(key);
        if (lock == null) {
            return;
        }
        ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
        if (!writeLock.tryLock()) {
            log.debug("订阅 {} 的锁正被占用, 跳过回收（残留锁对象不影响正确性）", key);
            return;
        }
        try {
            if (lock.hasQueuedThreads()) {
                log.debug("订阅 {} 有线程在排队等锁, 跳过回收", key);
                return;
            }
            LOCKS.remove(key, lock);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 当前登记在册的订阅锁数量。订阅删除时会尽力回收（见 {@link #release(Ani)}），
     * 拿不到锁的少数残留对象保留到进程重启。这里只用于诊断与测试。
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
