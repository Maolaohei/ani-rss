package ani.rss.service;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.util.other.ConfigUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F2 订阅级本地状态快照缓存。
 * <p>
 * 这个缓存要挡住的浪费很具体：预览、媒体库、RSS 主流程、手动搜索会各自去问
 * "这个订阅的下载目录里有哪些集"。本地磁盘模式下是 N 次目录遍历，
 * 网盘模式下就是 N 组 API 调用——同一份数据被反复列举，既慢又容易撞限流。
 * <p>
 * 固化四条底线：
 * <ol>
 *   <li><b>失败不缓存</b>：一次网盘抖动绝不能被固化成"目录里什么都没有"，
 *       否则整个 TTL 内都会谎报"本地不存在"；</li>
 *   <li><b>单飞</b>：同一 key 并发构建只跑一次，其余等同一份结果；</li>
 *   <li><b>失效优先于过期</b>：TTL 只是兜底，下载完成/改名/模板变更必须立刻失效；</li>
 *   <li><b>失效要能拦住在途构建</b>：构建期间发生失效时结果必须丢弃，
 *       否则"失效"会被一个更早开始、更晚结束的旧构建覆盖掉。</li>
 * </ol>
 */
class LocalStateCacheTest {

    private Integer prevLocalTtl;
    private Integer prevCloudTtl;

    @BeforeEach
    void setUp() {
        Config config = ConfigUtil.CONFIG;
        prevLocalTtl = config.getLocalStateCacheTtlSeconds();
        prevCloudTtl = config.getCloudStateCacheTtlSeconds();
        LocalStateCache.clear();
    }

    @AfterEach
    void tearDown() {
        ConfigUtil.CONFIG.setLocalStateCacheTtlSeconds(prevLocalTtl)
                .setCloudStateCacheTtlSeconds(prevCloudTtl);
        LocalStateCache.clear();
    }

    private static Ani ani(String id) {
        return new Ani().setId(id).setTitle("测试订阅" + id);
    }

    // ---------------- 缓存键 ----------------

    @Test
    void key_contains_ani_id_and_differs_by_path() {
        String a = LocalStateCache.key("ani-1", "/media/A");
        String b = LocalStateCache.key("ani-1", "/media/B");
        String c = LocalStateCache.key("ani-2", "/media/A");

        assertTrue(a.startsWith("ani-1|"), "键应以订阅 id 开头，便于按订阅整体失效");
        assertNotEquals(a, b, "下载路径不同必须是不同的键（模板改了要自然落到新键）");
        assertNotEquals(a, c, "订阅不同必须是不同的键");
        assertEquals(a, LocalStateCache.key("ani-1", "/media/A"), "同样的输入必须得到同样的键");
    }

    @Test
    void sha1_is_stable_and_short() {
        String hex = LocalStateCache.sha1("/media/番剧 A");
        assertEquals(40, hex.length(), "SHA-1 十六进制长度应为 40");
        assertEquals(hex, LocalStateCache.sha1("/media/番剧 A"));
        assertEquals(LocalStateCache.sha1(""), LocalStateCache.sha1(null), "null 与空串应等价，避免 NPE");
    }

    // ---------------- 命中与构建 ----------------

    @Test
    void second_call_hits_cache_without_rebuild() {
        AtomicInteger loads = new AtomicInteger();
        Ani a = ani("ani-1");
        LocalStateCache.Loader loader = () -> {
            loads.incrementAndGet();
            return LocalStateCache.Loaded.of(Set.of("1:1", "1:2"));
        };

        LocalStateCache.Snapshot first = assertDoesNotThrow(() ->
                LocalStateCache.getOrBuild(a, "/media/A", LocalStateCache.Source.CLOUD_API, loader));
        LocalStateCache.Snapshot second = assertDoesNotThrow(() ->
                LocalStateCache.getOrBuild(a, "/media/A", LocalStateCache.Source.CLOUD_API, loader));

        assertEquals(1, loads.get(), "同一 key 的第二次取用必须命中缓存，不能重新列举");
        assertSame(first, second);
        assertEquals(Set.of("1:1", "1:2"), second.episodeIndex());
        assertEquals(1L, LocalStateCache.getBuildCount());
        assertEquals(1L, LocalStateCache.getHit());
        assertEquals(1L, LocalStateCache.getMiss());
    }

    @Test
    void different_download_path_builds_separately() {
        AtomicInteger loads = new AtomicInteger();
        Ani a = ani("ani-1");
        LocalStateCache.Loader loader = () -> {
            loads.incrementAndGet();
            return LocalStateCache.Loaded.of(Set.of());
        };

        assertDoesNotThrow(() -> LocalStateCache.getOrBuild(a, "/media/A", LocalStateCache.Source.LOCAL_DISK, loader));
        assertDoesNotThrow(() -> LocalStateCache.getOrBuild(a, "/media/B", LocalStateCache.Source.LOCAL_DISK, loader));

        assertEquals(2, loads.get(), "模板变更后新路径应单独构建（旧键靠 TTL/LRU 自然淘汰）");
        assertEquals(2, LocalStateCache.size());
    }

    @Test
    void failed_load_is_never_cached() {
        AtomicInteger loads = new AtomicInteger();
        Ani a = ani("ani-1");
        LocalStateCache.Loader boom = () -> {
            loads.incrementAndGet();
            throw new IllegalStateException("网盘 502");
        };

        assertThrows(IllegalStateException.class, () ->
                LocalStateCache.getOrBuild(a, "/media/A", LocalStateCache.Source.CLOUD_API, boom));
        assertThrows(IllegalStateException.class, () ->
                LocalStateCache.getOrBuild(a, "/media/A", LocalStateCache.Source.CLOUD_API, boom));

        assertEquals(2, loads.get(), "失败结果绝不能入缓存，否则整个 TTL 内都会谎报「目录为空」");
        assertEquals(0, LocalStateCache.size());
        assertEquals(0L, LocalStateCache.getBuildCount(), "失败的构建不计入构建次数");
    }

    @Test
    void incomplete_index_is_preserved() {
        Ani a = ani("ani-1");
        LocalStateCache.Snapshot snapshot = assertDoesNotThrow(() ->
                LocalStateCache.getOrBuild(a, "/media/A", LocalStateCache.Source.CLOUD_API,
                        () -> LocalStateCache.Loaded.of(Set.of("1:1"), false)));

        assertFalse(snapshot.complete(), "列举被截断时快照必须标记为不完整");
        assertEquals(Set.of("1:1"), snapshot.episodeIndex(),
                "截断只影响「断言不存在」的能力，已找到的条目仍应保留（找到即存在）");
    }

    @Test
    void null_index_becomes_empty_set() {
        Ani a = ani("ani-1");
        LocalStateCache.Snapshot snapshot = assertDoesNotThrow(() ->
                LocalStateCache.getOrBuild(a, "/media/A", LocalStateCache.Source.LOCAL_DISK,
                        () -> LocalStateCache.Loaded.of(null)));
        assertNotNull(snapshot.episodeIndex());
        assertTrue(snapshot.episodeIndex().isEmpty());
    }

    // ---------------- 单飞 ----------------

    @Test
    void concurrent_builds_are_coalesced() throws Exception {
        int threads = 6;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        Ani a = ani("ani-1");
        LocalStateCache.Loader slow = () -> {
            loads.incrementAndGet();
            // 卡住一段时间，保证其余线程能挤进单飞等待
            Thread.sleep(400L);
            return LocalStateCache.Loaded.of(Set.of("1:1"));
        };

        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                ready.countDown();
                try {
                    fire.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    LocalStateCache.getOrBuild(a, "/media/A", LocalStateCache.Source.CLOUD_API, slow);
                } catch (Exception e) {
                    fail("单飞场景不应抛异常: " + e.getMessage());
                }
            });
            workers[i].start();
        }
        assertTrue(ready.await(3, TimeUnit.SECONDS), "工作线程未全部就绪");
        fire.countDown();
        for (Thread worker : workers) {
            worker.join(5000L);
        }

        assertEquals(1, loads.get(), "同一 key 的并发构建只能执行一次，实际执行 " + loads.get() + " 次");
        assertEquals(threads - 1L, LocalStateCache.getCoalesced(),
                "其余 " + (threads - 1) + " 个线程应被合并到同一份结果");
    }

    // ---------------- 失效 ----------------

    @Test
    void expired_entry_is_rebuilt() {
        Ani a = ani("ani-1");
        ConfigUtil.CONFIG.setLocalStateCacheTtlSeconds(10);
        // 造一份"11 秒前构建"的快照：TTL 下限是 10s，靠 sleep 验证过期会让测试慢到不可接受
        LocalStateCache.putForTest("ani-1", "/media/A", Set.of("1:1"),
                LocalStateCache.Source.LOCAL_DISK, System.currentTimeMillis() - 11_000L);

        AtomicInteger loads = new AtomicInteger();
        LocalStateCache.Snapshot snapshot = assertDoesNotThrow(() ->
                LocalStateCache.getOrBuild(a, "/media/A", LocalStateCache.Source.LOCAL_DISK, () -> {
                    loads.incrementAndGet();
                    return LocalStateCache.Loaded.of(Set.of("1:2"));
                }));

        assertEquals(1, loads.get(), "过期条目必须重新构建");
        assertEquals(Set.of("1:2"), snapshot.episodeIndex());
        assertEquals(1L, LocalStateCache.getExpiredCount());
    }

    @Test
    void fresh_entry_is_not_rebuilt() {
        Ani a = ani("ani-1");
        ConfigUtil.CONFIG.setLocalStateCacheTtlSeconds(60);
        LocalStateCache.putForTest("ani-1", "/media/A", Set.of("1:1"),
                LocalStateCache.Source.LOCAL_DISK, System.currentTimeMillis());

        AtomicInteger loads = new AtomicInteger();
        assertDoesNotThrow(() -> LocalStateCache.getOrBuild(a, "/media/A",
                LocalStateCache.Source.LOCAL_DISK, () -> {
                    loads.incrementAndGet();
                    return LocalStateCache.Loaded.of(Set.of("1:2"));
                }));

        assertEquals(0, loads.get());
        assertEquals(1L, LocalStateCache.getHit());
        assertEquals(0L, LocalStateCache.getExpiredCount());
    }

    @Test
    void invalidate_by_ani_id_forces_rebuild() {
        Ani a = ani("ani-1");
        AtomicInteger loads = new AtomicInteger();
        LocalStateCache.Loader loader = () -> {
            loads.incrementAndGet();
            return LocalStateCache.Loaded.of(Set.of());
        };

        assertDoesNotThrow(() -> LocalStateCache.getOrBuild(a, "/media/A", LocalStateCache.Source.CLOUD_API, loader));
        LocalStateCache.invalidate(a);
        assertDoesNotThrow(() -> LocalStateCache.getOrBuild(a, "/media/A", LocalStateCache.Source.CLOUD_API, loader));

        assertEquals(2, loads.get(), "失效后必须重新构建");
    }

    @Test
    void invalidate_all_forces_rebuild() {
        AtomicInteger loads = new AtomicInteger();
        LocalStateCache.Loader loader = () -> {
            loads.incrementAndGet();
            return LocalStateCache.Loaded.of(Set.of());
        };

        assertDoesNotThrow(() -> LocalStateCache.getOrBuild(ani("ani-1"), "/media/A",
                LocalStateCache.Source.LOCAL_DISK, loader));
        assertDoesNotThrow(() -> LocalStateCache.getOrBuild(ani("ani-2"), "/media/B",
                LocalStateCache.Source.LOCAL_DISK, loader));
        assertEquals(2, LocalStateCache.size());

        LocalStateCache.invalidateAll();
        assertEquals(0, LocalStateCache.size(), "整体失效（模板变更）应清空全部快照");
    }

    @Test
    void invalidate_by_download_path_only_affects_that_path() {
        AtomicInteger loads = new AtomicInteger();
        LocalStateCache.Loader loader = () -> {
            loads.incrementAndGet();
            return LocalStateCache.Loaded.of(Set.of());
        };

        assertDoesNotThrow(() -> LocalStateCache.getOrBuild(ani("ani-1"), "/media/A",
                LocalStateCache.Source.CLOUD_API, loader));
        assertDoesNotThrow(() -> LocalStateCache.getOrBuild(ani("ani-2"), "/media/B",
                LocalStateCache.Source.CLOUD_API, loader));

        LocalStateCache.invalidateByDownloadPath("/media/A");

        assertDoesNotThrow(() -> LocalStateCache.getOrBuild(ani("ani-2"), "/media/B",
                LocalStateCache.Source.CLOUD_API, loader));
        assertEquals(2, loads.get(), "/media/B 不该被 /media/A 的失效波及");

        assertDoesNotThrow(() -> LocalStateCache.getOrBuild(ani("ani-1"), "/media/A",
                LocalStateCache.Source.CLOUD_API, loader));
        assertEquals(3, loads.get(), "/media/A 应被精确失效并重建");
    }

    @Test
    void invalidate_with_blank_path_falls_back_to_all() {
        LocalStateCache.Loader loader = () -> LocalStateCache.Loaded.of(Set.of());
        assertDoesNotThrow(() -> LocalStateCache.getOrBuild(ani("ani-1"), "/media/A",
                LocalStateCache.Source.CLOUD_API, loader));
        assertEquals(1, LocalStateCache.size());

        // 拿不到下载路径（下载器未配置等）时宁可整体失效，也不能给错数据
        LocalStateCache.invalidateByDownloadPath("  ");
        assertEquals(0, LocalStateCache.size());
    }

    @Test
    void build_result_is_dropped_when_invalidated_midway() throws Exception {
        Ani a = ani("ani-1");
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();

        Thread builder = new Thread(() -> {
            try {
                LocalStateCache.getOrBuild(a, "/media/A", LocalStateCache.Source.CLOUD_API, () -> {
                    loads.incrementAndGet();
                    building.countDown();
                    try {
                        release.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    // 构建期间文件其实已经改名落地，这份索引是"过期"的
                    return LocalStateCache.Loaded.of(Set.of("1:1"));
                });
            } catch (Exception ignored) {
                // 断言只看缓存状态
            }
        });
        builder.start();
        assertTrue(building.await(3, TimeUnit.SECONDS), "构建未开始");

        // 构建进行中触发失效
        LocalStateCache.invalidate(a);
        release.countDown();
        builder.join(5000L);

        assertEquals(1L, LocalStateCache.getInvalidatedDropCount(),
                "构建期间发生失效时，旧结果必须被丢弃");
        assertEquals(0, LocalStateCache.size(),
                "被丢弃的结果不得写回缓存——否则「失效」会被更早开始、更晚结束的旧构建覆盖");

        // 下一次取用必须重新构建（证明缓存里确实没有旧结果）
        assertDoesNotThrow(() -> LocalStateCache.getOrBuild(a, "/media/A",
                LocalStateCache.Source.CLOUD_API, () -> {
                    loads.incrementAndGet();
                    return LocalStateCache.Loaded.of(Set.of("1:2"));
                }));
        assertEquals(2, loads.get());
    }

    // ---------------- 容量与统计 ----------------

    @Test
    void capacity_is_at_least_64() {
        assertTrue(LocalStateCache.resolveCapacity() >= 64,
                "订阅很少时也要留够容量，避免每两次就淘汰一次");
    }

    @Test
    void hit_rate_is_zero_without_samples() {
        assertEquals(0.0, LocalStateCache.getHitRate(), 1e-9, "无样本时命中率应为 0 而不是 NaN");
    }

    @Test
    void stats_exposes_all_counters() {
        assertEquals(8, LocalStateCache.stats().size(),
                "诊断摘要应包含 size/hit/miss/coalesced/build/expired/invalidatedDrop/hitRate");
    }

    @Test
    void ttl_is_clamped_by_source() {
        ConfigUtil.CONFIG.setLocalStateCacheTtlSeconds(0).setCloudStateCacheTtlSeconds(0);
        assertEquals(10_000L, LocalStateCache.resolveTtlMs(LocalStateCache.Source.LOCAL_DISK),
                "本地 TTL 下限 10s");
        assertEquals(30_000L, LocalStateCache.resolveTtlMs(LocalStateCache.Source.CLOUD_API),
                "网盘 TTL 下限 30s");

        ConfigUtil.CONFIG.setLocalStateCacheTtlSeconds(99999).setCloudStateCacheTtlSeconds(99999);
        assertEquals(3600_000L, LocalStateCache.resolveTtlMs(LocalStateCache.Source.LOCAL_DISK));
        assertEquals(3600_000L, LocalStateCache.resolveTtlMs(LocalStateCache.Source.CLOUD_API));
    }

    @Test
    void cloud_ttl_defaults_longer_than_local() {
        ConfigUtil.CONFIG.setLocalStateCacheTtlSeconds(null).setCloudStateCacheTtlSeconds(null);
        assertTrue(LocalStateCache.resolveTtlMs(LocalStateCache.Source.CLOUD_API)
                        > LocalStateCache.resolveTtlMs(LocalStateCache.Source.LOCAL_DISK),
                "网盘列举是真金白银的 API 调用，缓存应比本地磁盘更久");
    }
}
