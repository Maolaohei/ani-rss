package ani.rss.util.other;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BGM 限流：令牌桶的等待时间计算 + 「不再每个请求固定睡眠」的行为。
 * <p>
 * (P1-14) 原实现把节流藏在 {@code setToken}（加 Authorization 头的方法）里，每个请求无条件睡
 * 500~1000ms，且在 {@code getEpisodes}/{@code getSubjectId} 之外又各睡 500/1000ms。
 * 本用例锁定两件事：
 * <ol>
 *   <li>等待时间由"当前令牌数"决定，令牌够用时为 0（<b>旧实现下这条必然失败</b>）；</li>
 *   <li>突发容量允许连续 2 个请求立即通过。</li>
 * </ol>
 */
class BgmUtilRateLimitTest {

    /**
     * 与实现一致的速率：1000/750 ≈ 1.3333 次/秒
     */
    private static final double PER_SECOND = 1000.0 / 750.0;

    @BeforeEach
    void setUp() {
        BgmUtil.resetBgmRateLimiterForTest();
    }

    @AfterEach
    void tearDown() {
        BgmUtil.resetBgmRateLimiterForTest();
    }

    /* ==================== 纯函数：等待时间 ==================== */

    @Test
    void tokens_at_or_above_one_need_no_wait() {
        assertEquals(0L, BgmUtil.resolveTokenWaitMs(2.0, PER_SECOND));
        assertEquals(0L, BgmUtil.resolveTokenWaitMs(1.0, PER_SECOND));
        assertEquals(0L, BgmUtil.resolveTokenWaitMs(1.5, PER_SECOND));
    }

    @Test
    void empty_bucket_waits_one_full_interval() {
        // 0 个令牌 → 需等 1/1.3333 秒 = 750ms
        assertEquals(750L, BgmUtil.resolveTokenWaitMs(0.0, PER_SECOND));
    }

    @Test
    void half_bucket_waits_half_interval() {
        assertEquals(375L, BgmUtil.resolveTokenWaitMs(0.5, PER_SECOND));
    }

    @Test
    void wait_is_inversely_proportional_to_rate() {
        // 1 次/秒 → 1 秒；2 次/秒 → 0.5 秒
        assertEquals(1000L, BgmUtil.resolveTokenWaitMs(0.0, 1.0));
        assertEquals(500L, BgmUtil.resolveTokenWaitMs(0.0, 2.0));
    }

    @Test
    void fractional_wait_rounds_up_not_down() {
        // 0.9 个令牌 → 0.1/1.3333 秒 = 75ms 整；用不规则值验证向上取整
        assertEquals(75L, BgmUtil.resolveTokenWaitMs(0.9, PER_SECOND));
        // 1 - 0.8 = 0.2 → 0.2/1.3333*1000 = 150ms
        assertEquals(150L, BgmUtil.resolveTokenWaitMs(0.8, PER_SECOND));
    }

    /* ==================== 行为：突发 2 个不等待 ==================== */

    @Test
    void burst_of_two_passes_without_sleeping() {
        long start = System.currentTimeMillis();
        BgmUtil.throttleBgmApi();
        BgmUtil.throttleBgmApi();
        long elapsed = System.currentTimeMillis() - start;

        // 旧实现每个请求固定睡 500~1000ms，两次至少 1000ms；这里只应是两次加锁的开销。
        // 阈值放到 400ms 以避免慢机器上的偶发抖动导致假失败。
        assertTrue(elapsed < 400L,
                "突发容量内的两个请求不应睡眠, 实际耗时 " + elapsed + "ms");
    }

    @Test
    void third_request_waits_for_token_refill() {
        BgmUtil.throttleBgmApi();
        BgmUtil.throttleBgmApi();

        long start = System.currentTimeMillis();
        BgmUtil.throttleBgmApi();
        long elapsed = System.currentTimeMillis() - start;

        // 第 3 个令牌需要等约 750ms。下界放宽到 500ms（避免时钟粒度/调度导致假失败），
        // 上界 3000ms 用于捕捉"等待时间算错导致睡得过久"。
        assertTrue(elapsed >= 500L,
                "超出突发容量后应当等待补桶, 实际仅 " + elapsed + "ms");
        assertTrue(elapsed < 3000L,
                "等待时间不应超过一个补桶周期太多, 实际 " + elapsed + "ms");
    }

    @Test
    void tokens_refill_after_idle() {
        BgmUtil.throttleBgmApi();
        BgmUtil.throttleBgmApi();
        // 空闲一个补桶周期后，桶里应至少回满 1 个令牌
        try {
            Thread.sleep(800L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long start = System.currentTimeMillis();
        BgmUtil.throttleBgmApi();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 400L,
                "空闲一个周期后应无需再等, 实际 " + elapsed + "ms");
    }

    /* ==================== setToken 不再睡眠 ==================== */

    @Test
    void setToken_only_adds_header_and_never_sleeps() {
        long start = System.currentTimeMillis();
        // 不配置 token 时也应立即返回（旧实现无论是否配置都睡）
        BgmUtil.setToken(cn.hutool.http.HttpRequest.get("http://127.0.0.1/"));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 200L,
                "setToken 应只加请求头、不睡眠, 实际耗时 " + elapsed + "ms");
    }
}
