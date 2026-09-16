package ani.rss.download;

import ani.rss.entity.OpenListFileInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-4/P1-5 目录列举缓存与请求合并：
 * <ol>
 *   <li><b>失效要定向</b>：一次 {@code fs/batch_rename} 只该让受影响的子树过期。
 *       原先无脑 {@code clear()}，而每下载完一集都要改名——下载期间 30s 列举缓存等于没有，
 *       订阅 A 的一集收尾会把 B/C/D 已构建的列举全部打掉，整轮 API 次数成倍上涨；</li>
 *   <li><b>失效范围要够</b>：目录自身、后代（递归列举缓存了子目录）、祖先
 *       （祖先的递归结果里含有该目录内容）都必须失效，否则会读到过期数据；
 *       路径缺失时退化为全清，绝不留下过期缓存——过期缓存把"其实存在"读成"不存在"
 *       会直接造成重复下载；</li>
 *   <li><b>不得回填旧结果</b>：失效发生在某个构建进行中时，该构建回来不得把变更前的
 *       数据写进缓存；</li>
 *   <li><b>预算口径</b>：只有真正发出的列举请求消耗"每轮列举预算"，
 *       缓存命中与被合并的等待方都不消耗。</li>
 * </ol>
 */
class OpenListApiCacheTest {

    @BeforeEach
    @AfterEach
    void clearStaticState() {
        OpenListApi.resetRateLimitState();
        // findFilesCache/listNamesCache/inFlight 都是 static，跨测试共享，必须清干净
        new OpenListApi().invalidateFindFilesCache();
    }

    // ---------------- P1-4 定向失效 ----------------

    @Test
    void invalidating_one_subtree_keeps_sibling_cache() {
        StubApi api = new StubApi();
        api.invalidateFindFilesCache();

        api.findFilesStrict("/media/A");
        api.findFilesStrict("/media/B");
        int warm = api.listCalls.get();
        assertEquals(2, warm, "两个目录各列举一次");

        // 订阅 A 下载完一集触发失效：B 的已构建列举不该被牵连
        api.invalidateFindFilesCache("/media/A");

        api.findFilesStrict("/media/B");
        assertEquals(warm, api.listCalls.get(), "失效 /media/A 不该影响 /media/B 的缓存");

        api.findFilesStrict("/media/A");
        assertEquals(warm + 1, api.listCalls.get(), "/media/A 自身已失效，必须重新列举");
    }

    @Test
    void invalidating_parent_drops_descendant_cache() {
        StubApi api = new StubApi()
                .dir("/media/A", "S01/")
                .dir("/media/A/S01", "E01.mkv");
        api.invalidateFindFilesCache();

        api.findFilesStrict("/media/A");
        int warm = api.listCalls.get();
        assertEquals(2, warm, "递归列举应缓存 /media/A 与 /media/A/S01");

        api.invalidateFindFilesCache("/media/A");
        api.findFilesStrict("/media/A/S01");
        assertEquals(warm + 1, api.listCalls.get(),
                "失效 /media/A 必须连带失效其后代 /media/A/S01（递归列举把子目录也缓存了）");
    }

    @Test
    void invalidating_child_drops_ancestor_cache() {
        StubApi api = new StubApi()
                .dir("/media/A", "S01/")
                .dir("/media/A/S01", "E01.mkv");
        api.invalidateFindFilesCache();

        api.findFilesStrict("/media/A");
        int warm = api.listCalls.get();
        assertEquals(2, warm);

        // 在 /media/A/S01 里改名：/media/A 的递归结果里含有该目录内容，同样过期
        api.invalidateFindFilesCache("/media/A/S01");
        api.findFilesStrict("/media/A");
        assertEquals(warm + 2, api.listCalls.get(),
                "祖先 /media/A 与其子目录 /media/A/S01 都应重新列举");
    }

    @Test
    void no_arg_invalidate_still_clears_everything() {
        StubApi api = new StubApi();
        api.invalidateFindFilesCache();

        api.findFilesStrict("/media/A");
        api.findFilesStrict("/media/B");
        int warm = api.listCalls.get();
        assertEquals(2, warm);

        api.invalidateFindFilesCache();
        api.findFilesStrict("/media/A");
        api.findFilesStrict("/media/B");
        assertEquals(warm + 2, api.listCalls.get(), "无参失效仍应清空全部缓存");
    }

    @Test
    void blank_changed_path_falls_back_to_clearing_everything() {
        StubApi api = new StubApi();
        api.invalidateFindFilesCache();

        api.findFilesStrict("/media/A");
        api.findFilesStrict("/media/B");
        int warm = api.listCalls.get();

        // 路径缺失时宁可全清，也不能留下过期缓存（"查不到"会被读成"没有文件"）
        api.invalidateFindFilesCache((String) null);
        api.findFilesStrict("/media/A");
        api.findFilesStrict("/media/B");
        assertEquals(warm + 2, api.listCalls.get(), "变更路径为 null 时应退化为清空全部");
    }

    @Test
    void repeated_separators_are_collapsed_before_matching() {
        StubApi api = new StubApi();
        api.invalidateFindFilesCache();

        // 缓存键带重复斜杠 → 变更根是规范写法
        api.findFilesStrict("/media/A//S01");
        int warm = api.listCalls.get();
        assertEquals(1, warm);

        api.invalidateFindFilesCache("/media/A/S01");
        api.findFilesStrict("/media/A//S01");
        assertEquals(warm + 1, api.listCalls.get(),
                "重复斜杠必须折叠后再比较，否则该条目会被静默漏失效（静默漏失效是最坏的失败模式）");

        // 反向拼写：变更根带重复斜杠 → 缓存键是规范写法
        api.findFilesStrict("/media/B/S02");
        int warm2 = api.listCalls.get();

        api.invalidateFindFilesCache("/media/B//S02");
        api.findFilesStrict("/media/B/S02");
        assertEquals(warm2 + 1, api.listCalls.get(),
                "反向拼写同样必须命中：变更根也要折叠重复斜杠");
    }

    @Test
    void invalidation_also_drops_list_names_cache() {
        StubApi api = new StubApi();
        api.invalidateFindFilesCache();

        api.listFileNamesStrict("/media/A");
        api.listFileNamesStrict("/media/B");
        int warm = api.listCalls.get();
        assertEquals(2, warm, "两个目录各列举一次（300s 长缓存）");

        api.invalidateFindFilesCache("/media/A");
        api.listFileNamesStrict("/media/B");
        assertEquals(warm, api.listCalls.get(), "失效 /media/A 不该影响 /media/B 的长缓存");

        api.listFileNamesStrict("/media/A");
        assertEquals(warm + 1, api.listCalls.get(), "/media/A 的长缓存应被定向失效");
    }

    // ---------------- P1-5 请求合并 ----------------

    @Test
    void concurrent_recursive_listings_are_merged_and_counted_once() throws Exception {
        OpenListApi.resetRateLimitState();
        StubApi api = new StubApi();
        api.invalidateFindFilesCache();
        OpenListApi.startRoundBudget(10);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        api.blockPath = "/media/A";
        api.blockLatch = release;
        api.enteredLatch = entered;

        int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        List<List<OpenListFileInfo>> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                ready.countDown();
                try {
                    fire.await(3, TimeUnit.SECONDS);
                    results.add(api.findFilesStrict("/media/A"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
            workers[i].start();
        }
        assertTrue(ready.await(3, TimeUnit.SECONDS), "工作线程未全部就绪");
        fire.countDown();
        assertTrue(entered.await(3, TimeUnit.SECONDS), "首个列举应已进入底层请求");

        // 等其余线程都进入"等待同一份结果"的状态，再放行首个请求
        long deadline = System.currentTimeMillis() + 3000L;
        while (OpenListApi.getListingCoalesced() < threads - 1L && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        release.countDown();
        for (Thread worker : workers) {
            worker.join(5000L);
        }

        assertTrue(errors.isEmpty(), "被合并的等待方不应报错: " + errors);
        assertEquals(1, api.listCalls.get(), "同一 path 的并发递归列举只能发一次请求");
        assertEquals(1L, OpenListApi.getListingCallCountRound(),
                "被合并的等待方没有发请求，不该消耗列举预算");
        assertEquals(threads - 1L, OpenListApi.getListingCoalesced(),
                "被合并的次数应可观测（这是请求合并的实际收益）");
        assertEquals(threads, results.size());
        for (List<OpenListFileInfo> result : results) {
            assertEquals(1, result.size(), "所有线程必须拿到同一份完整结果");
        }
    }

    // ---------------- 世代号：不得回填旧结果 ----------------

    @Test
    void build_started_before_invalidation_does_not_publish_stale_result() throws Exception {
        OpenListApi.resetRateLimitState();
        StubApi api = new StubApi();
        api.invalidateFindFilesCache();

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        api.blockPath = "/media/A";
        api.blockLatch = release;
        api.enteredLatch = entered;

        AtomicReference<List<OpenListFileInfo>> built = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread builder = new Thread(() -> {
            try {
                built.set(api.findFilesStrict("/media/A"));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        builder.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS), "列举应已开始");

        // 列举进行中目录发生变更（下载收尾的 rename/move 就是这种时序）
        api.invalidateFindFilesCache("/media/A");
        int callsBeforeRelease = api.listCalls.get();
        release.countDown();
        builder.join(5000L);

        assertNull(failure.get(), "进行中的构建应正常返回，只是不回填缓存");
        assertEquals(1, built.get().size());

        api.findFilesStrict("/media/A");
        assertEquals(callsBeforeRelease + 1, api.listCalls.get(),
                "变更前开始的构建不得把旧结果回填，下一次调用必须重新列举");
    }

    // ---------------- 预算口径 ----------------

    @Test
    void cache_hit_does_not_consume_round_budget_but_listing_does() {
        OpenListApi.resetRateLimitState();
        StubApi api = new StubApi();
        api.invalidateFindFilesCache();
        OpenListApi.startRoundBudget(10);

        api.findFilesStrict("/media/A");
        assertEquals(1L, OpenListApi.getListingCallCountRound(), "真实列举应消耗预算");

        api.findFilesStrict("/media/A");
        assertEquals(1L, OpenListApi.getListingCallCountRound(), "缓存命中不消耗预算");
        assertEquals(1, api.listCalls.get());
        assertFalse(OpenListApi.isRoundBudgetExhausted());
    }

    // ---------------- 桩：只替换最底层的 fs/list ----------------

    /**
     * 只替换最底层的 {@code fs/list}，让 {@code findFilesStrict} 的缓存、请求合并、
     * 世代号与预算计数全部走真实代码（若直接覆写 findFilesStrict 就测不到缓存本身）。
     * <p>
     * 未声明的路径默认返回一个普通文件；需要目录结构时用 {@link #dir} 声明。
     */
    private static class StubApi extends OpenListApi {
        final AtomicInteger listCalls = new AtomicInteger();
        final Map<String, List<OpenListFileInfo>> tree = new ConcurrentHashMap<>();
        /** 命中该路径时阻塞，用于制造"构建期间发生失效/合并"的竞态 */
        volatile String blockPath;
        volatile CountDownLatch blockLatch;
        volatile CountDownLatch enteredLatch;

        StubApi dir(String path, String... names) {
            List<OpenListFileInfo> children = new ArrayList<>();
            for (String name : names) {
                boolean isDir = name.endsWith("/");
                String n = isDir ? name.substring(0, name.length() - 1) : name;
                children.add(new OpenListFileInfo()
                        .setName(n)
                        .setPath(path)
                        .setIsDir(isDir)
                        .setSize(isDir ? 0L : 1024L));
            }
            tree.put(path, children);
            return this;
        }

        @Override
        public List<OpenListFileInfo> fsListStrict(String path, Boolean refresh) {
            listCalls.incrementAndGet();
            if (path.equals(blockPath) && blockLatch != null) {
                if (enteredLatch != null) {
                    enteredLatch.countDown();
                }
                try {
                    blockLatch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            List<OpenListFileInfo> children = tree.get(path);
            if (children != null) {
                return children;
            }
            return List.of(new OpenListFileInfo()
                    .setName("番剧 S01E01.mkv")
                    .setPath(path)
                    .setIsDir(false)
                    .setSize(1024L));
        }
    }
}
