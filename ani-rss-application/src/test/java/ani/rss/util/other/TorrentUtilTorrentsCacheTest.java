package ani.rss.util.other;

import ani.rss.download.OpenList;
import ani.rss.entity.TorrentsInfo;
import cn.hutool.core.thread.ThreadUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-9：{@code TorrentUtil.getTorrentsInfos()} 不再用类锁包住下载器 HTTP。
 * <p>
 * 改之前是 {@code public static synchronized}，与 {@code login()} / {@code delete()} /
 * {@code renameOnce()} 共用同一把类锁，而下载器查询超时是 20s。于是：
 * <ul>
 *   <li>一次慢查询会把"删除任务""登录下载器""重命名"全部按住排队；</li>
 *   <li>前端每 5 秒轮询一次任务列表、RSS 轮次里每个 worker 也在调它，争抢尤其明显。</li>
 * </ul>
 * 改之后只有"缓存读写 + 在途标记"互斥，网络调用在锁外，并补了请求合并（同 F7-2 的思路）：
 * 并发未命中时只让一个线程去问下载器。
 * <p>
 * 另外两条不许退化的既有语义：
 * <ol>
 *   <li><b>查询失败必须向上抛</b>（E1）——不能吞成空列表，否则会被当成"下载器里没有任务"
 *       而放行并发上限 / 误判坏种；</li>
 *   <li><b>返回的必须是副本</b>——{@code DownloadService} 会就地 {@code remove} 已删除的任务，
 *       返回缓存引用会被调用方污染。</li>
 * </ol>
 */
class TorrentUtilTorrentsCacheTest {

    @TempDir
    Path tempDir;

    private String prevConfig;
    private final AtomicInteger queries = new AtomicInteger();
    private volatile List<TorrentsInfo> stubTorrents = new ArrayList<>();
    private volatile boolean stubThrows = false;
    private volatile long stubHoldMs = 0L;

    @BeforeEach
    void setUp() {
        prevConfig = System.getProperty("CONFIG");
        System.setProperty("CONFIG", tempDir.toString());
        queries.set(0);
        stubTorrents = new ArrayList<>();
        stubThrows = false;
        stubHoldMs = 0L;
        TorrentUtil.DOWNLOAD = new OpenList() {
            @Override
            public List<TorrentsInfo> getTorrentsInfos() {
                queries.incrementAndGet();
                // 先取值、再等待：模拟"这次查询在等待期间就已经拿到了当时的数据"。
                // 若在等待之后才读 stubTorrents，在途查询永远会返回最新值，
                // 就测不出"刷新后到达的读者是否吃到了刷新前的数据"。
                List<TorrentsInfo> snapshot = stubTorrents;
                if (stubHoldMs > 0L) {
                    ThreadUtil.sleep(stubHoldMs);
                }
                if (stubThrows) {
                    throw new IllegalStateException("下载器连接失败");
                }
                // snapshot 为 null 时按"下载器返回 null"处理，而不是在假实现里先抛 NPE
                return snapshot == null ? null : new ArrayList<>(snapshot);
            }
        };
        TorrentUtil.refreshTorrentsCache();
    }

    @AfterEach
    void tearDown() {
        if (prevConfig == null) {
            System.clearProperty("CONFIG");
        } else {
            System.setProperty("CONFIG", prevConfig);
        }
        TorrentUtil.DOWNLOAD = null;
        TorrentUtil.refreshTorrentsCache();
    }

    private static TorrentsInfo torrent(String name) {
        return new TorrentsInfo().setName(name).setHash(name);
    }

    @Test
    void cache_hit_within_ttl_queries_downloader_once() {
        stubTorrents = List.of(torrent("A"), torrent("B"));

        assertEquals(2, TorrentUtil.getTorrentsInfos().size());
        assertEquals(2, TorrentUtil.getTorrentsInfos().size());
        assertEquals(1, queries.get(), "5 秒 TTL 内第二次调用应命中缓存");
    }

    @Test
    void refresh_forces_refetch() {
        TorrentUtil.getTorrentsInfos();
        TorrentUtil.refreshTorrentsCache();
        TorrentUtil.getTorrentsInfos();

        assertEquals(2, queries.get());
    }

    @Test
    void query_failure_propagates_and_is_not_cached() {
        stubThrows = true;
        assertThrows(IllegalStateException.class, TorrentUtil::getTorrentsInfos,
                "E1：查询失败必须抛出，不能吞成空列表");

        stubThrows = false;
        stubTorrents = List.of(torrent("A"));
        assertEquals(1, TorrentUtil.getTorrentsInfos().size(), "失败结果不该被缓存");
        assertEquals(2, queries.get());
    }

    @Test
    void returned_list_is_mutable_copy() {
        stubTorrents = List.of(torrent("A"), torrent("B"));

        List<TorrentsInfo> first = TorrentUtil.getTorrentsInfos();
        first.remove(0);
        assertEquals(1, first.size());

        List<TorrentsInfo> second = TorrentUtil.getTorrentsInfos();
        assertEquals(2, second.size(), "调用方原地修改不得污染缓存（DownloadService 会 remove）");
        assertEquals(1, queries.get(), "第二次仍应是缓存命中");
    }

    @Test
    void empty_and_null_result_return_empty_list() {
        stubTorrents = new ArrayList<>();
        assertTrue(TorrentUtil.getTorrentsInfos().isEmpty());

        TorrentUtil.refreshTorrentsCache();
        stubTorrents = null;
        assertTrue(TorrentUtil.getTorrentsInfos().isEmpty(), "null 结果按空列表返回，不抛 NPE");
    }

    @Test
    void concurrent_misses_coalesce_into_single_query() throws Exception {
        stubTorrents = List.of(torrent("A"), torrent("B"));
        stubHoldMs = 300L;

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<TorrentsInfo>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return TorrentUtil.getTorrentsInfos();
                }));
            }
            start.countDown();
            for (Future<List<TorrentsInfo>> future : futures) {
                assertEquals(2, future.get(30, TimeUnit.SECONDS).size());
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, queries.get(), "8 个线程同时未命中应只发一次下载器查询");
    }

    @Test
    void waiters_receive_query_failure() throws Exception {
        stubThrows = true;
        stubHoldMs = 300L;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        TorrentUtil.getTorrentsInfos();
                        return false;
                    } catch (IllegalStateException e) {
                        return true;
                    }
                }));
            }
            start.countDown();
            for (Future<Boolean> future : futures) {
                assertTrue(future.get(30, TimeUnit.SECONDS), "失败必须传播给等待方，不能返回空列表");
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void refresh_is_not_blocked_by_class_lock() throws Exception {
        // 让一次查询长时间占住"在途"位置，同时另一线程做 refresh：
        // refresh 只写 volatile + 递增世代号，必须立刻返回（改前它会去抢类锁）
        stubHoldMs = 1500L;
        stubTorrents = List.of(torrent("A"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> slow = pool.submit(TorrentUtil::getTorrentsInfos);
            ThreadUtil.sleep(200);
            long begin = System.currentTimeMillis();
            TorrentUtil.refreshTorrentsCache();
            long elapsed = System.currentTimeMillis() - begin;

            assertTrue(elapsed < 500L, "refresh 应立即返回，实测 " + elapsed + "ms");
            slow.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void stale_inflight_result_is_not_cached_after_refresh() throws Exception {
        stubHoldMs = 600L;
        stubTorrents = List.of(torrent("旧"));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<List<TorrentsInfo>> slow = pool.submit(TorrentUtil::getTorrentsInfos);
            ThreadUtil.sleep(150);
            // 在途查询还在跑，此时刷新：它的结果不该被写进缓存
            TorrentUtil.refreshTorrentsCache();
            assertEquals(1, slow.get(30, TimeUnit.SECONDS).size());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        stubHoldMs = 0L;
        stubTorrents = List.of(torrent("新"));
        List<TorrentsInfo> after = TorrentUtil.getTorrentsInfos();
        assertEquals(1, after.size());
        assertEquals("新", after.get(0).getName(), "刷新之后的读取必须重新查询，不能吃到刷新前的在途结果");
        assertTrue(queries.get() >= 2, "应当发生了第二次查询");
    }

    /**
     * 刷新之后<b>才到达</b>的读者不得加入"刷新之前发起"的那次在途查询。
     * <p>
     * 这是去类锁时最容易漏掉的一条语义：旧实现里 {@code refreshTorrentsCache()} 也是
     * {@code static synchronized}，与 {@code getTorrentsInfos()} 共用类锁，所以刷新必然排在
     * 在途查询之后执行，之后再进来的读者一定会重新查询——这个场景在旧实现下根本不存在。
     * 去掉类锁后，如果只递增世代号而不丢弃在途条目，新读者会挂到旧 future 上拿到刷新前的数据。
     */
    @Test
    void reader_arriving_after_refresh_does_not_join_pre_refresh_query() throws Exception {
        stubHoldMs = 600L;
        stubTorrents = List.of(torrent("旧"));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 占住"在途查询"位置：假下载器已先取到旧数据，随后才进入等待
            Future<List<TorrentsInfo>> slow = pool.submit(TorrentUtil::getTorrentsInfos);
            ThreadUtil.sleep(150);

            TorrentUtil.refreshTorrentsCache();

            stubHoldMs = 0L;
            stubTorrents = List.of(torrent("新"));
            List<TorrentsInfo> after = TorrentUtil.getTorrentsInfos();
            assertEquals("新", after.get(0).getName(),
                    "刷新之后才到达的读者拿到了刷新前的数据（在途查询没有被丢弃）");

            assertEquals(1, slow.get(30, TimeUnit.SECONDS).size());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
