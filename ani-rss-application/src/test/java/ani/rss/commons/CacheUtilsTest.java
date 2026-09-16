package ani.rss.commons;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 进程内通用缓存（P2-11）。
 * <p>
 * 这个类被 {@code HttpReq.isProxy(url)} 在<b>每一个 HTTP 请求</b>上调用，
 * 原实现给每个方法都挂了 {@code @Synchronized}（全应用一把锁），把出网点全部串行化。
 * 换成 {@link java.util.concurrent.ConcurrentHashMap} 之后，必须守住三条：
 * <ol>
 *   <li>底层结构线程安全——hutool 的 {@code FIFOCache} 连 {@code get} 都可能改内部结构，
 *       换回去会立刻在并发下抛异常；</li>
 *   <li>TTL 语义不变（{@code timeout <= 0} = 不过期，过期项对调用方表现为"不存在"）；</li>
 *   <li>容量上限与淘汰顺序不变（超限先清过期，再按插入顺序淘汰最旧）。</li>
 * </ol>
 */
class CacheUtilsTest {

    @BeforeEach
    void setUp() {
        CacheUtils.clearForTest();
    }

    @AfterEach
    void tearDown() {
        CacheUtils.clearForTest();
    }

    // ---------------- 基本读写 ----------------

    @Test
    void put_then_get_roundtrip() {
        CacheUtils.put("k1", "v1");
        assertEquals("v1", CacheUtils.get("k1"));
    }

    @Test
    void get_returns_null_for_missing_key() {
        assertNull(CacheUtils.get("nothing"));
        assertFalse(CacheUtils.containsKey("nothing"));
    }

    @Test
    void contains_key_tracks_put_and_remove() {
        assertFalse(CacheUtils.containsKey("ck"));
        CacheUtils.put("ck", "v");
        assertTrue(CacheUtils.containsKey("ck"));
        CacheUtils.remove("ck");
        assertFalse(CacheUtils.containsKey("ck"));
    }

    // ---------------- TTL ----------------

    @Test
    void put_without_timeout_never_expires() {
        CacheUtils.put("forever", "v");
        sleep(30);
        assertEquals("v", CacheUtils.get("forever"));
    }

    @Test
    void non_positive_timeout_means_no_expiry() {
        // 与原 FIFOCache 的 CacheObj 判定保持一致：timeout <= 0 视为不过期
        CacheUtils.put("zero", "v0", 0L);
        CacheUtils.put("negative", "v-", -1L);
        sleep(30);
        assertEquals("v0", CacheUtils.get("zero"));
        assertEquals("v-", CacheUtils.get("negative"));
    }

    @Test
    void entry_expires_after_its_timeout() {
        CacheUtils.put("short", "v", 60L);
        assertEquals("v", CacheUtils.get("short"));
        sleep(200);
        assertNull(CacheUtils.get("short"), "过期项必须对调用方表现为不存在");
        assertFalse(CacheUtils.containsKey("short"));
    }

    // ---------------- null 语义 ----------------

    @Test
    void null_value_is_equivalent_to_remove() {
        CacheUtils.put("nk", "v");
        assertTrue(CacheUtils.containsKey("nk"));

        // ConcurrentHashMap 不接受 null 值；而原实现存进去 null 之后 get 回来也是 null，
        // 与"不存在"无法区分，所以这个归一化对调用方不可观测
        CacheUtils.put("nk", null);
        assertFalse(CacheUtils.containsKey("nk"));
        assertNull(CacheUtils.get("nk"));
    }

    @Test
    void null_key_is_ignored() {
        CacheUtils.put(null, "v");
        assertNull(CacheUtils.get(null));
        assertFalse(CacheUtils.containsKey(null));
        assertDoesNotThrow(() -> CacheUtils.remove(null));
    }

    // ---------------- 并发 ----------------

    /**
     * 反向验证：并发读写不得抛异常、不得丢已写入的值。
     * <p>
     * 把底层换回非线程安全的 {@code FIFOCache} 时，这条用例会随机以
     * {@code ConcurrentModificationException} / 空指针 / 数据错乱失败——
     * 它是"为什么不能只是把那把 {@code @Synchronized} 去掉"的防线。
     */
    @Test
    void concurrent_read_write_remove_does_not_throw() throws Exception {
        int threads = 8;
        int loops = 2000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger observedMismatch = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int id = t;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < loops; i++) {
                        // 热点 key：所有线程都读写同一批 key，制造真实竞争
                        String hot = "hot-" + (i % 8);
                        CacheUtils.put(hot, id + ":" + i, 5000L);
                        String got = CacheUtils.get(hot);
                        if (got == null) {
                            // 只可能是被同线程之外的 put 覆盖成过期值，不该出现 null
                            observedMismatch.incrementAndGet();
                        }
                        CacheUtils.get("proxyList:" + (i % 4));
                        if (i % 16 == 0) {
                            CacheUtils.put("cold-" + id + "-" + i, "v");
                        }
                        if (i % 32 == 0) {
                            CacheUtils.remove("cold-" + id + "-" + (i - 32));
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发用例未在 60s 内结束");
        if (failure.get() != null) {
            fail("并发读写抛出异常: " + failure.get(), failure.get());
        }
        assertEquals(0, observedMismatch.get(), "热点 key 刚写入就应可读，出现 null 说明结构不安全");
    }

    // ---------------- 容量 ----------------

    /**
     * 超限时按插入顺序淘汰最旧（与原 FIFO 语义一致），且不得影响最近写入的项。
     * <p>
     * 容量上限是 1024 * 8，因此这里要写满 8192 条以上才会触发淘汰。
     */
    @Test
    void evicts_oldest_entries_when_over_capacity() {
        int capacity = 1024 * 8;
        int extra = 32;

        CacheUtils.put("oldest-0", "v");
        for (int i = 1; i < capacity + extra; i++) {
            CacheUtils.put("k-" + i, "v");
        }

        assertNull(CacheUtils.get("oldest-0"), "超限后最旧的条目应被淘汰");
        assertEquals("v", CacheUtils.get("k-" + (capacity + extra - 1)), "最新写入的条目必须还在");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
