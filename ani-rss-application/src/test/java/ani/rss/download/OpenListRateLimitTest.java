package ani.rss.download;

import ani.rss.entity.Config;
import ani.rss.entity.OpenListFileInfo;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.thread.ThreadUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F7 网盘 API 限流规避：令牌桶 + 熔断 + "目录不存在"分类。
 * <p>
 * 背景：网盘按账号限流，一轮全量扫描会瞬间打出成百次 {@code fs/list}。
 * 原先的固定 300ms 串行间隔有两个问题——订阅多时把整轮拖成线性等待，
 * 且被限流后<b>没有任何退让机制</b>，只会持续撞墙。
 * <p>
 * 这里固化三条底线：
 * <ol>
 *   <li><b>速率可配、可突发</b>，且非法配置一律回落，绝不出现 0/s（除零）或超大速率；</li>
 *   <li><b>连续失败要退让</b>：达到阈值后进入指数退避冷却，冷却期内不再发请求；
 *       中间只要成功一次就复位，避免把偶发抖动累积成熔断；</li>
 *   <li><b>"目录不存在"不是故障</b>：它是业务结果（= 确认没有），
 *       绝不能把新订阅（下载目录尚未创建）判成网盘故障而触发全局熔断。</li>
 * </ol>
 */
class OpenListRateLimitTest {

    private Integer prevPerSecond;
    private Integer prevBurst;
    private Integer prevThreshold;
    private Integer prevCooldown;

    @BeforeEach
    void setUp() {
        Config config = ConfigUtil.CONFIG;
        prevPerSecond = config.getOpenListApiPerSecond();
        prevBurst = config.getOpenListApiBurst();
        prevThreshold = config.getOpenListFailThreshold();
        prevCooldown = config.getOpenListCooldownSeconds();
        OpenListApi.resetRateLimitState();
    }

    @AfterEach
    void tearDown() {
        ConfigUtil.CONFIG.setOpenListApiPerSecond(prevPerSecond)
                .setOpenListApiBurst(prevBurst)
                .setOpenListFailThreshold(prevThreshold)
                .setOpenListCooldownSeconds(prevCooldown);
        OpenListApi.resetRateLimitState();
    }

    // ---------------- 错误分类 ----------------

    @Test
    void dir_not_found_message_is_recognised() {
        // 不同网盘/版本的文案不一致，做包含匹配
        assertTrue(OpenListApi.isDirNotFoundMessage("failed to get dir: object not found"));
        assertTrue(OpenListApi.isDirNotFoundMessage("object not found"));
        assertTrue(OpenListApi.isDirNotFoundMessage("No Such File or Directory"));
        assertTrue(OpenListApi.isDirNotFoundMessage("dir not exist"));
        assertTrue(OpenListApi.isDirNotFoundMessage("failed to get dir"));
        // 真正的故障/限流文案不能被误判为"目录不存在"
        assertFalse(OpenListApi.isDirNotFoundMessage("connection reset by peer"));
        assertFalse(OpenListApi.isDirNotFoundMessage("429 Too Many Requests"));
        assertFalse(OpenListApi.isDirNotFoundMessage("read timed out"));
        assertFalse(OpenListApi.isDirNotFoundMessage(""));
        assertFalse(OpenListApi.isDirNotFoundMessage(null));
    }

    @Test
    void dir_not_found_does_not_trip_breaker() {
        ConfigUtil.CONFIG.setOpenListFailThreshold(3).setOpenListCooldownSeconds(60);
        for (int i = 0; i < 10; i++) {
            OpenListApi.onListingFailure(new OpenListApi.OpenListDirNotFoundException(
                    "failed to get dir: object not found"));
        }
        assertFalse(OpenListApi.isListingCoolingDown(),
                "目录不存在是业务结果，不是故障；否则新订阅一进预览就会触发全局熔断");
        assertEquals(0L, OpenListApi.getCooldownTriggeredCount());
    }

    // ---------------- 熔断 ----------------

    @Test
    void breaker_opens_after_threshold_failures() {
        ConfigUtil.CONFIG.setOpenListFailThreshold(3).setOpenListCooldownSeconds(60);
        RuntimeException boom = new RuntimeException("connection reset by peer");

        OpenListApi.onListingFailure(boom);
        OpenListApi.onListingFailure(boom);
        assertFalse(OpenListApi.isListingCoolingDown(), "未达阈值不应冷却");

        OpenListApi.onListingFailure(boom);
        assertTrue(OpenListApi.isListingCoolingDown(), "连续 3 次失败应进入冷却");
        assertEquals(1L, OpenListApi.getCooldownTriggeredCount());
        assertTrue(OpenListApi.listingCooldownRemainingMs() > 0L);
        assertTrue(OpenListApi.listingCooldownRemainingMs() <= 60_000L,
                "第一级冷却应为配置的 60s，实际 " + OpenListApi.listingCooldownRemainingMs());
    }

    @Test
    void success_resets_failure_counter() {
        ConfigUtil.CONFIG.setOpenListFailThreshold(3).setOpenListCooldownSeconds(60);
        RuntimeException boom = new RuntimeException("read timed out");

        OpenListApi.onListingFailure(boom);
        OpenListApi.onListingFailure(boom);
        OpenListApi.onListingSuccess();
        OpenListApi.onListingFailure(boom);
        OpenListApi.onListingFailure(boom);
        assertFalse(OpenListApi.isListingCoolingDown(),
                "中途成功一次即应复位计数，否则偶发抖动会被累积成熔断");

        OpenListApi.onListingFailure(boom);
        assertTrue(OpenListApi.isListingCoolingDown(), "复位后重新累计到阈值仍应冷却");
    }

    @Test
    void cooldown_escalates_exponentially() {
        ConfigUtil.CONFIG.setOpenListFailThreshold(1).setOpenListCooldownSeconds(60);
        RuntimeException boom = new RuntimeException("boom");

        OpenListApi.onListingFailure(boom);
        long first = OpenListApi.listingCooldownRemainingMs();
        OpenListApi.onListingFailure(boom);
        long second = OpenListApi.listingCooldownRemainingMs();

        assertEquals(2L, OpenListApi.getCooldownTriggeredCount());
        assertTrue(second > first, "退避应逐级放大: first=" + first + " second=" + second);
        assertTrue(first <= 60_000L && second <= 120_000L, "第 1/2 级应约为 60s / 120s");
    }

    @Test
    void cooldown_is_capped_at_ten_minutes() {
        ConfigUtil.CONFIG.setOpenListFailThreshold(1).setOpenListCooldownSeconds(600);
        RuntimeException boom = new RuntimeException("boom");
        // 600s 指数放大 10 次是天文数字，必须封顶
        for (int i = 0; i < 10; i++) {
            OpenListApi.onListingFailure(boom);
        }
        assertEquals(10L, OpenListApi.getCooldownTriggeredCount());
        long remain = OpenListApi.listingCooldownRemainingMs();
        assertTrue(remain <= 10 * 60 * 1000L, "退避上限 10 分钟，实际 " + remain);
    }

    @Test
    void threshold_and_cooldown_are_clamped() {
        // 阈值下限 1、上限 10；冷却下限 10s、上限 600s
        ConfigUtil.CONFIG.setOpenListFailThreshold(0).setOpenListCooldownSeconds(1);
        OpenListApi.onListingFailure(new RuntimeException("boom"));
        assertTrue(OpenListApi.isListingCoolingDown(), "阈值 0 应回落为 1，单次失败即冷却");
        long remain = OpenListApi.listingCooldownRemainingMs();
        assertTrue(remain >= 9_000L, "冷却 1s 应回落为下限 10s，实际 " + remain);
    }

    @Test
    void reset_clears_breaker_and_counters() {
        ConfigUtil.CONFIG.setOpenListFailThreshold(1).setOpenListCooldownSeconds(60);
        OpenListApi.onListingFailure(new RuntimeException("boom"));
        assertTrue(OpenListApi.isListingCoolingDown());

        OpenListApi.resetRateLimitState();
        assertFalse(OpenListApi.isListingCoolingDown());
        assertEquals(0L, OpenListApi.getCooldownTriggeredCount());
        assertEquals(0L, OpenListApi.getThrottleWaitMs());
        assertEquals(0L, OpenListApi.getApiCallCount());
        assertEquals(0L, OpenListApi.getApiCallCountRound());
        assertEquals(0L, OpenListApi.getListingCallCountRound());
        assertEquals(0L, OpenListApi.getListingCacheHit());
        assertEquals(0L, OpenListApi.getListingCacheMiss());
    }

    // ---------------- 可观测计数（F7-7）----------------

    @Test
    void api_call_counter_tracks_throttled_calls() {
        ConfigUtil.CONFIG.setOpenListApiPerSecond(20).setOpenListApiBurst(5);
        OpenListApi.resetRateLimitState();
        assertEquals(0L, OpenListApi.getApiCallCount());

        for (int i = 0; i < 4; i++) {
            OpenListApi.throttleApi();
        }
        assertEquals(4L, OpenListApi.getApiCallCount(), "累计调用数应随每次限流入口递增");
        assertEquals(4L, OpenListApi.getApiCallCountRound());

        // 每轮开始复位本轮计数，累计值保留（便于看长期趋势）
        OpenListApi.resetRoundApiStats();
        assertEquals(0L, OpenListApi.getApiCallCountRound());
        assertEquals(4L, OpenListApi.getApiCallCount());
    }

    @Test
    void cache_hit_rate_is_zero_without_samples() {
        OpenListApi.resetRateLimitState();
        assertEquals(0.0, OpenListApi.getListingCacheHitRate(), 1e-9,
                "无样本时命中率应为 0 而不是 NaN");
    }

    @Test
    void burst_allows_immediate_calls() {
        ConfigUtil.CONFIG.setOpenListApiPerSecond(1).setOpenListApiBurst(3);
        OpenListApi.resetRateLimitState();

        long start = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            OpenListApi.throttleApi();
        }
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 300L, "突发额度内的调用不应等待，实际 " + elapsed + "ms");
        assertEquals(0L, OpenListApi.getThrottleWaitMs());
    }

    @Test
    void token_bucket_throttles_beyond_burst() {
        ConfigUtil.CONFIG.setOpenListApiPerSecond(10).setOpenListApiBurst(1);
        OpenListApi.resetRateLimitState();

        long start = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            OpenListApi.throttleApi();
        }
        long elapsed = System.currentTimeMillis() - start;

        // 突发 1 个，其余 4 个各需 ~100ms
        assertTrue(elapsed >= 300L, "5 次调用（10/s，突发 1）应至少等待约 400ms，实际 " + elapsed + "ms");
        assertTrue(OpenListApi.getThrottleWaitMs() > 0L, "限流等待应被累计，便于诊断");
    }

    @Test
    void illegal_rate_falls_back_to_one_per_second() {
        // perSecond=0 若不被 clamp 会直接除零；burst=0 会让所有请求都等待
        ConfigUtil.CONFIG.setOpenListApiPerSecond(0).setOpenListApiBurst(0);
        OpenListApi.resetRateLimitState();

        OpenListApi.throttleApi();
        long start = System.currentTimeMillis();
        OpenListApi.throttleApi();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 800L, "非法速率应回落为 1/s（约 1000ms），实际 " + elapsed + "ms");
        assertTrue(elapsed <= 2000L, "不应出现远超 1/s 的异常等待，实际 " + elapsed + "ms");
    }

    // ---------------- F7-2 请求合并 ----------------

    /**
     * 只数调用次数、并卡住一段时间的假客户端。
     * <p>
     * 缓存只能挡住"已经算过"的请求，挡不住"正在算"的请求：两个线程同时问同一个目录，
     * 缓存都未命中，于是同一份列举被做两遍。这里验证后来者确实被合并到了第一个人的结果上。
     */
    private static class CountingApi extends OpenListApi {
        final AtomicInteger calls = new AtomicInteger();
        final long holdMs;

        CountingApi(long holdMs) {
            this.holdMs = holdMs;
        }

        @Override
        public synchronized List<OpenListFileInfo> findFilesStrict(String path) {
            calls.incrementAndGet();
            if (holdMs > 0L) {
                ThreadUtil.sleep(holdMs);
            }
            return List.of(new OpenListFileInfo()
                    .setName("番剧 S01E01.mkv")
                    .setPath(path)
                    .setIsDir(false)
                    .setSize(1024L));
        }
    }

    @Test
    void concurrent_listings_of_same_path_are_merged() throws Exception {
        ConfigUtil.CONFIG.setOpenListApiPerSecond(20).setOpenListApiBurst(5);
        OpenListApi.resetRateLimitState();

        CountingApi api = new CountingApi(400L);
        api.invalidateFindFilesCache();

        int threads = 5;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        List<List<String>> results = Collections.synchronizedList(new ArrayList<>());

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
                results.add(api.listFileNamesStrict("/media/A"));
            });
            workers[i].start();
        }
        assertTrue(ready.await(3, TimeUnit.SECONDS), "工作线程未全部就绪");
        fire.countDown();
        for (Thread worker : workers) {
            worker.join(5000L);
        }

        assertEquals(1, api.calls.get(),
                "同一 path 的并发列举只能发一次请求，实际 " + api.calls.get() + " 次");
        assertEquals(threads, results.size());
        for (List<String> result : results) {
            assertEquals(List.of("/media/A/番剧 S01E01.mkv"), result,
                    "被合并的线程必须拿到与首个请求完全一致的结果");
        }
        assertEquals(threads - 1L, OpenListApi.getListingCoalesced(),
                "被合并掉的次数应可观测（这是请求合并的实际收益）");
    }

    @Test
    void different_paths_are_not_merged() throws Exception {
        ConfigUtil.CONFIG.setOpenListApiPerSecond(20).setOpenListApiBurst(5);
        OpenListApi.resetRateLimitState();

        CountingApi api = new CountingApi(0L);
        api.invalidateFindFilesCache();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        Thread t1 = new Thread(() -> {
            ready.countDown();
            await(fire);
            api.listFileNamesStrict("/media/A");
        });
        Thread t2 = new Thread(() -> {
            ready.countDown();
            await(fire);
            api.listFileNamesStrict("/media/B");
        });
        t1.start();
        t2.start();
        assertTrue(ready.await(3, TimeUnit.SECONDS));
        fire.countDown();
        t1.join(5000L);
        t2.join(5000L);

        assertEquals(2, api.calls.get(), "不同 path 是不同请求，不该被合并");
        assertEquals(0L, OpenListApi.getListingCoalesced());
    }

    @Test
    void second_listing_hits_cache_instead_of_requesting_again() {
        ConfigUtil.CONFIG.setOpenListApiPerSecond(20).setOpenListApiBurst(5);
        OpenListApi.resetRateLimitState();

        CountingApi api = new CountingApi(0L);
        api.invalidateFindFilesCache();

        api.listFileNamesStrict("/media/A");
        api.listFileNamesStrict("/media/A");

        assertEquals(1, api.calls.get(), "第二次列举应命中 300s 长缓存");
        assertEquals(1L, OpenListApi.getListingCacheHit());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------- F7-5 每轮 API 预算 ----------------

    /**
     * 只替换最底层的 fs/list，让 {@code findFilesStrict} 的缓存、请求合并与
     * 「列举预算」计数全部走真实代码。
     * <p>
     * 预算的语义是"还能不能再<b>为确认本地文件而列举</b>"，所以必须用真实列举来驱动，
     * 不能用 {@code throttleApi()} 这种"任意 API 调用"来凑数。
     */
    private static class FsListStubApi extends OpenListApi {
        final AtomicInteger listCalls = new AtomicInteger();

        @Override
        public List<OpenListFileInfo> fsListStrict(String path, Boolean refresh) {
            listCalls.incrementAndGet();
            return List.of(new OpenListFileInfo()
                    .setName("番剧 S01E01.mkv")
                    .setPath(path)
                    .setIsDir(false)
                    .setSize(1024L));
        }
    }

    @Test
    void budget_is_exhausted_after_configured_listings() {
        OpenListApi.resetRateLimitState();
        FsListStubApi api = new FsListStubApi();
        api.invalidateFindFilesCache();

        OpenListApi.startRoundBudget(2);
        assertEquals(2, OpenListApi.getRoundBudget());
        assertFalse(OpenListApi.isRoundBudgetExhausted(), "还没列举就不该判定为耗尽");

        api.findFilesStrict("/media/A");
        assertEquals(1L, OpenListApi.getListingCallCountRound());
        assertFalse(OpenListApi.isRoundBudgetExhausted(), "1/2 未耗尽");

        api.findFilesStrict("/media/B");
        assertTrue(OpenListApi.isRoundBudgetExhausted(),
                "列举数达到预算即视为耗尽，剩余条目应保持「存疑」而不是继续打网盘");
    }

    @Test
    void cache_hit_does_not_consume_listing_budget() {
        OpenListApi.resetRateLimitState();
        FsListStubApi api = new FsListStubApi();
        api.invalidateFindFilesCache();
        OpenListApi.startRoundBudget(10);

        api.findFilesStrict("/media/A");
        long afterFirst = OpenListApi.getListingCallCountRound();
        assertEquals(1L, afterFirst);

        api.findFilesStrict("/media/A");
        assertEquals(afterFirst, OpenListApi.getListingCallCountRound(),
                "缓存命中没有发请求，不该消耗列举预算");
        assertEquals(1, api.listCalls.get());
    }

    @Test
    void non_listing_api_calls_do_not_consume_listing_budget() {
        ConfigUtil.CONFIG.setOpenListApiPerSecond(20).setOpenListApiBurst(5);
        OpenListApi.resetRateLimitState();
        OpenListApi.startRoundBudget(2);

        for (int i = 0; i < 5; i++) {
            OpenListApi.throttleApi();
        }
        assertEquals(5L, OpenListApi.getApiCallCountRound());
        assertEquals(0L, OpenListApi.getListingCallCountRound());
        assertFalse(OpenListApi.isRoundBudgetExhausted(),
                "fs/mkdir、上传、下载器查询等不该吃掉「确认本地文件」的列举预算");
    }

    @Test
    void zero_budget_means_unlimited() {
        OpenListApi.resetRateLimitState();
        OpenListApi.startRoundBudget(0);
        assertEquals(0, OpenListApi.getRoundBudget());
        assertFalse(OpenListApi.isRoundBudgetExhausted(),
                "预算 0 = 不限制（非轮次场景，例如用户手动预览）");

        ConfigUtil.CONFIG.setOpenListApiPerSecond(20).setOpenListApiBurst(5);
        for (int i = 0; i < 30; i++) {
            OpenListApi.throttleApi();
        }
        assertFalse(OpenListApi.isRoundBudgetExhausted(), "未设置预算时永远不该耗尽");
    }

    @Test
    void start_round_budget_does_not_clamp_here() {
        OpenListApi.resetRateLimitState();
        // 大库一轮的列举次数天然超过硬顶，RssTask 算出的默认值必须能生效；
        // 钳制只由 RssTask 对"用户显式配置的值"负责，这里再钳一次会把那条修复静默废掉
        OpenListApi.startRoundBudget(1000);
        assertEquals(1000, OpenListApi.getRoundBudget());
        assertEquals(200, OpenListApi.MAX_API_BUDGET_PER_ROUND, "硬顶常量本身保持不变");
    }

    @Test
    void negative_budget_is_treated_as_zero() {
        OpenListApi.resetRateLimitState();
        OpenListApi.startRoundBudget(-5);
        assertEquals(0, OpenListApi.getRoundBudget());
        assertFalse(OpenListApi.isRoundBudgetExhausted());
    }

    @Test
    void clearing_budget_releases_the_limit() {
        OpenListApi.resetRateLimitState();
        FsListStubApi api = new FsListStubApi();
        api.invalidateFindFilesCache();
        OpenListApi.startRoundBudget(1);
        api.findFilesStrict("/media/A");
        assertTrue(OpenListApi.isRoundBudgetExhausted());

        // 轮次结束后必须撤掉预算，否则用户随后手动预览会被上一轮的预算卡住
        OpenListApi.clearRoundBudget();
        assertFalse(OpenListApi.isRoundBudgetExhausted());
    }

    @Test
    void budget_exhausted_counter_is_observable() {
        OpenListApi.resetRateLimitState();
        assertEquals(0L, OpenListApi.getBudgetExhaustedCount());
        OpenListApi.markBudgetExhausted();
        OpenListApi.markBudgetExhausted();
        assertEquals(2L, OpenListApi.getBudgetExhaustedCount(),
                "「因超预算放弃校验」的次数要能看见，否则用户只会看到一片「存疑」却找不到原因");
    }

    @Test
    void reset_clears_coalescing_and_budget() {
        ConfigUtil.CONFIG.setOpenListApiPerSecond(20).setOpenListApiBurst(5);
        OpenListApi.startRoundBudget(5);
        OpenListApi.markBudgetExhausted();

        OpenListApi.resetRateLimitState();
        assertEquals(0, OpenListApi.getRoundBudget());
        assertEquals(0L, OpenListApi.getBudgetExhaustedCount());
        assertEquals(0L, OpenListApi.getListingCoalesced());
    }
}
