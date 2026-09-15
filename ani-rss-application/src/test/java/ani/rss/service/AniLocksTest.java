package ani.rss.service;

import ani.rss.entity.Ani;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F6-1 订阅级读写锁 + F6-5 互斥矩阵。
 * <p>
 * 锁本身不产生功能，它只保证"不该并发的事情没有并发"。这里把互斥矩阵里
 * 每一条可被单测观察的语义固化下来，避免后续改动把读写锁退回成普通互斥、
 * 或者把"读不阻塞写"改回"读无条件排队"——后者会让用户点开预览卡在下载上几分钟。
 * <p>
 * 覆盖：
 * <ol>
 *   <li>同订阅写串行、跨订阅写并行；</li>
 *   <li>读不阻塞读；</li>
 *   <li>写锁被持有时读<b>不等</b>，直接执行并计入 readFallback（矩阵里
 *       "预览 vs 下载写文件 → 读锁 + 缓存 TTL"的落地形态）；</li>
 *   <li>写可重入、写→读降级允许（读→写升级禁止，故不测）；</li>
 *   <li>异常路径必须释放锁，否则一次失败就永久锁死该订阅；</li>
 *   <li>id 缺失时退化到同一把 {@code unknown} 锁。</li>
 * </ol>
 */
class AniLocksTest {

    @BeforeEach
    void setUp() {
        AniLocks.clearForTest();
    }

    @AfterEach
    void tearDown() {
        AniLocks.clearForTest();
    }

    private static Ani ani(String id) {
        return new Ani().setId(id).setTitle("测试订阅" + id);
    }

    // ---------------- 锁键 ----------------

    @Test
    void lock_for_same_id_is_stable() {
        assertSame(AniLocks.lockFor("stable"), AniLocks.lockFor("stable"));
        assertSame(AniLocks.lockFor("stable"), AniLocks.lockFor(ani("stable")));
    }

    @Test
    void different_ids_get_different_locks() {
        assertNotSame(AniLocks.lockFor("x1"), AniLocks.lockFor("x2"));
    }

    @Test
    void null_and_blank_ids_share_the_unknown_lock() {
        assertEquals("unknown", AniLocks.idOf((Ani) null));
        assertEquals("unknown", AniLocks.idOf((String) null));
        assertEquals("unknown", AniLocks.idOf(""));
        assertEquals("unknown", AniLocks.idOf(new Ani()));

        assertSame(AniLocks.lockFor("unknown"), AniLocks.lockFor((Ani) null));
        assertSame(AniLocks.lockFor("unknown"), AniLocks.lockFor(""));
    }

    @Test
    void size_counts_registered_subscriptions() {
        assertEquals(0, AniLocks.size());
        AniLocks.lockFor("sz1");
        AniLocks.lockFor("sz2");
        // 同一 id 重复取不该重复计数
        AniLocks.lockFor("sz1");
        assertEquals(2, AniLocks.size());
    }

    // ---------------- 写串行 / 并行 ----------------

    @Test
    void same_subscription_writes_are_serialized() throws Exception {
        Ani ani = ani("s1");
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();

        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread t1 = new Thread(() -> AniLocks.runWithWrite(ani, () -> {
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            firstHolds.countDown();
            awaitQuietly(release);
            concurrent.decrementAndGet();
        }));
        t1.start();
        assertTrue(firstHolds.await(5, TimeUnit.SECONDS), "第一个写操作应能进入临界区");

        Thread t2 = new Thread(() -> AniLocks.runWithWrite(ani, () -> {
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            concurrent.decrementAndGet();
        }));
        t2.start();
        // 给第二个写操作充分的机会去"插队"；正确实现下它必须还在等
        Thread.sleep(200);
        assertEquals(1, maxConcurrent.get(), "同订阅的两个写操作不得并发");

        release.countDown();
        t1.join(5000);
        t2.join(5000);
        assertFalse(t1.isAlive());
        assertFalse(t2.isAlive());
        assertEquals(1, maxConcurrent.get());
    }

    @Test
    void different_subscriptions_write_in_parallel() throws Exception {
        Ani a1 = ani("p1");
        Ani a2 = ani("p2");
        CountDownLatch aHolds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch bEntered = new CountDownLatch(1);

        Thread ta = new Thread(() -> AniLocks.runWithWrite(a1, () -> {
            aHolds.countDown();
            awaitQuietly(release);
        }));
        ta.start();
        assertTrue(aHolds.await(5, TimeUnit.SECONDS));

        Thread tb = new Thread(() -> AniLocks.runWithWrite(a2, bEntered::countDown));
        tb.start();
        assertTrue(bEntered.await(2, TimeUnit.SECONDS), "不同订阅之间不应互相阻塞");

        release.countDown();
        ta.join(5000);
        tb.join(5000);
    }

    @Test
    void null_ani_writes_are_still_serialized() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread t1 = new Thread(() -> AniLocks.runWithWrite(null, () -> {
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            firstHolds.countDown();
            awaitQuietly(release);
            concurrent.decrementAndGet();
        }));
        t1.start();
        assertTrue(firstHolds.await(5, TimeUnit.SECONDS));

        Thread t2 = new Thread(() -> AniLocks.runWithWrite(null, () -> {
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            concurrent.decrementAndGet();
        }));
        t2.start();
        Thread.sleep(200);
        assertEquals(1, maxConcurrent.get(), "id 缺失的订阅也必须互相串行");

        release.countDown();
        t1.join(5000);
        t2.join(5000);
    }

    // ---------------- 读不阻塞读 ----------------

    @Test
    void reads_do_not_block_each_other() throws Exception {
        Ani ani = ani("r1");
        CountDownLatch aHolds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread ta = new Thread(() -> AniLocks.runWithTryRead(ani, 1000L, () -> {
            aHolds.countDown();
            awaitQuietly(release);
        }));
        ta.start();
        assertTrue(aHolds.await(5, TimeUnit.SECONDS));

        long before = AniLocks.getReadFallbackCount();
        assertEquals("ok", AniLocks.callWithTryRead(ani, 500L, () -> "ok"));
        assertEquals(before, AniLocks.getReadFallbackCount(), "读锁之间不该互相阻塞");

        release.countDown();
        ta.join(5000);
    }

    @Test
    void read_acquires_immediately_when_no_writer_holds_the_lock() {
        Ani ani = ani("r2");
        long before = AniLocks.getReadFallbackCount();
        assertEquals("v", AniLocks.callWithTryRead(ani, 100L, () -> "v"));
        assertEquals(before, AniLocks.getReadFallbackCount(), "无人持写锁时不该退化为不持锁读");
    }

    // ---------------- 读遇写：不等，直接走缓存 ----------------

    @Test
    void read_falls_back_without_blocking_when_write_lock_is_held() throws Exception {
        Ani ani = ani("f1");
        CountDownLatch writeHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread writer = new Thread(() -> AniLocks.runWithWrite(ani, () -> {
            writeHeld.countDown();
            awaitQuietly(release);
        }));
        writer.start();
        assertTrue(writeHeld.await(5, TimeUnit.SECONDS));

        long before = AniLocks.getReadFallbackCount();
        long start = System.currentTimeMillis();
        String value = AniLocks.callWithTryRead(ani, 200L, () -> "cached");
        long cost = System.currentTimeMillis() - start;

        // 关键语义：action 一定被执行（调用方不必处理"没拿到锁"）
        assertEquals("cached", value);
        assertEquals(before + 1, AniLocks.getReadFallbackCount(), "应计入 readFallback 以便观测");
        assertTrue(cost >= 120, "应先尝试到超时，实际 " + cost + "ms");
        assertTrue(cost < 3000, "不该被写锁拖住，实际 " + cost + "ms");

        release.countDown();
        writer.join(5000);
    }

    @Test
    void zero_timeout_does_not_wait() throws Exception {
        Ani ani = ani("z1");
        CountDownLatch writeHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread writer = new Thread(() -> AniLocks.runWithWrite(ani, () -> {
            writeHeld.countDown();
            awaitQuietly(release);
        }));
        writer.start();
        assertTrue(writeHeld.await(5, TimeUnit.SECONDS));

        long start = System.currentTimeMillis();
        assertEquals("v", AniLocks.callWithTryRead(ani, 0L, () -> "v"));
        assertTrue(System.currentTimeMillis() - start < 150, "timeout=0 应只试一次、立即返回");

        release.countDown();
        writer.join(5000);
    }

    @Test
    void default_try_read_timeout_is_short() {
        // 这个值必须"短到用户感觉不到"：写锁在下载期间是分钟级的，
        // 一旦被调大，预览就会开始出现肉眼可见的等待。
        assertTrue(AniLocks.DEFAULT_TRY_READ_MS > 0 && AniLocks.DEFAULT_TRY_READ_MS <= 1000,
                "默认读尝试时长应在一个感知阈值内，实际 " + AniLocks.DEFAULT_TRY_READ_MS);
    }

    // ---------------- 重入与降级 ----------------

    @Test
    void write_is_reentrant_on_the_same_thread() {
        Ani ani = ani("re1");
        AtomicInteger depth = new AtomicInteger();
        AniLocks.runWithWrite(ani, () -> {
            depth.incrementAndGet();
            AniLocks.runWithWrite(ani, depth::incrementAndGet);
        });
        assertEquals(2, depth.get());
    }

    @Test
    void write_then_read_downgrade_is_allowed() {
        Ani ani = ani("d1");
        long before = AniLocks.getReadFallbackCount();
        String value = AniLocks.callWithWrite(ani,
                () -> AniLocks.callWithTryRead(ani, 200L, () -> "inner"));
        assertEquals("inner", value);
        // 持写锁的线程取读锁是降级，必须成功（不能退化成"不持锁"）
        assertEquals(before, AniLocks.getReadFallbackCount());
    }

    @Test
    void call_with_write_returns_the_action_result() {
        Ani ani = ani("ret1");
        assertEquals(42, AniLocks.callWithWrite(ani, () -> 42));
    }

    @Test
    void run_with_try_read_executes_the_action() {
        Ani ani = ani("ret2");
        AtomicBoolean ran = new AtomicBoolean();
        AniLocks.runWithTryRead(ani, 100L, () -> ran.set(true));
        assertTrue(ran.get());
    }

    @Test
    void null_action_is_a_noop() {
        assertNull(AniLocks.callWithWrite(ani("n1"), null));
        assertNull(AniLocks.callWithTryRead(ani("n2"), 10L, null));
        AniLocks.runWithWrite(ani("n3"), null);
        AniLocks.runWithTryRead(ani("n4"), 10L, null);
    }

    // ---------------- 异常路径必须释放锁 ----------------

    @Test
    void write_lock_is_released_after_exception() {
        Ani ani = ani("e1");
        assertThrows(IllegalStateException.class, () -> AniLocks.runWithWrite(ani, () -> {
            throw new IllegalStateException("boom");
        }));
        // 一次失败绝不能把该订阅永久锁死
        AtomicBoolean ran = new AtomicBoolean();
        AniLocks.runWithWrite(ani, () -> ran.set(true));
        assertTrue(ran.get());
    }

    @Test
    void read_lock_is_released_after_exception() {
        Ani ani = ani("e2");
        assertThrows(IllegalStateException.class, () -> AniLocks.callWithTryRead(ani, 100L, () -> {
            throw new IllegalStateException("boom");
        }));
        assertTrue(AniLocks.lockFor(ani).writeLock().tryLock(), "读锁必须在 finally 中释放");
        AniLocks.lockFor(ani).writeLock().unlock();
    }

    @Test
    void write_lock_is_released_after_exception_in_nested_read() {
        Ani ani = ani("e3");
        assertThrows(IllegalStateException.class, () -> AniLocks.callWithWrite(ani,
                () -> AniLocks.callWithTryRead(ani, 100L, () -> {
                    throw new IllegalStateException("boom");
                })));
        assertTrue(AniLocks.lockFor(ani).writeLock().tryLock(), "内层读锁与外层写锁都应释放");
        AniLocks.lockFor(ani).writeLock().unlock();
    }

    // ---------------- 工具 ----------------

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
