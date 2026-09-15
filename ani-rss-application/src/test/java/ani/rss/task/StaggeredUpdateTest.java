package ani.rss.task;

import ani.rss.entity.Config;
import ani.rss.task.RssTask.RssJobState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F1 错峰更新：解析逻辑与提交节奏的可验收断言。
 * <p>
 * 错峰的目的是<b>削峰</b>——把"同一瞬间 N 个订阅一起打 RSS 源 / 下载器 / 网盘"摊成几批。
 * 因此这里固化三条底线：
 * <ol>
 *   <li><b>可整体关闭</b>：{@code staggeredUpdateEnable=false} 时批次数退化为 1、
 *       进度文案不追加批次后缀，行为与改造前一致（不引入"关不掉"的新机制）；</li>
 *   <li><b>不能把整轮拉长</b>：错峰等待总时长受 {@code resolveStaggerBudgetMs} 封顶。
 *       订阅一多，"批数 × 间隔"会线性膨胀，若不封顶反而会挤掉下一轮；</li>
 *   <li><b>等待可中断</b>：用户点取消后不能傻等完一个上限 60s 的批间隔。</li>
 * </ol>
 */
class StaggeredUpdateTest {

    @AfterEach
    void tearDown() {
        RssTask.RssJobState.currentBatch.set(0);
        RssTask.RssJobState.totalBatch.set(0);
        RssTask.RssJobState.nextBatchAt.set(0L);
    }

    // ---------------- 开关 ----------------

    @Test
    void stagger_enabled_by_default() {
        assertTrue(RssTask.isStaggeredUpdateEnabled(null), "配置缺失（旧配置文件）应按启用处理");
        assertTrue(RssTask.isStaggeredUpdateEnabled(new Config()));
        assertTrue(RssTask.isStaggeredUpdateEnabled(new Config().setStaggeredUpdateEnable(true)));
        assertFalse(RssTask.isStaggeredUpdateEnabled(new Config().setStaggeredUpdateEnable(false)));
    }

    @Test
    void disabled_collapses_to_single_batch() {
        Config on = new Config().setStaggeredUpdateEnable(true).setRssConcurrency(3);
        Config off = new Config().setStaggeredUpdateEnable(false).setRssConcurrency(3);
        // 100 个订阅 / 并发 3 → 启用时 34 批
        assertEquals(34, RssTask.resolveTotalBatch(on, 100, 3));
        // 关闭时必须退化为 1 批：等价于改造前"一次性全量提交"
        assertEquals(1, RssTask.resolveTotalBatch(off, 100, 3));
        assertEquals(34, RssTask.resolveTotalBatch(null, 100, 3),
                "配置为 null（旧配置文件）时按启用处理，应正常分批");
    }

    // ---------------- 批间隔 ----------------

    @Test
    void batch_interval_defaults_and_clamps() {
        assertEquals(2000L, RssTask.resolveStaggerBatchIntervalMs(null));
        assertEquals(2000L, RssTask.resolveStaggerBatchIntervalMs(new Config()));
        assertEquals(5000L, RssTask.resolveStaggerBatchIntervalMs(new Config().setStaggerBatchIntervalMs(5000)));
        // 0 合法：表示"分批但不等"
        assertEquals(0L, RssTask.resolveStaggerBatchIntervalMs(new Config().setStaggerBatchIntervalMs(0)));
        // 负值收敛到 0，绝不能变成负数 sleep
        assertEquals(0L, RssTask.resolveStaggerBatchIntervalMs(new Config().setStaggerBatchIntervalMs(-100)));
        // 上限 60s：超过会挤占轮询周期
        assertEquals(60_000L, RssTask.resolveStaggerBatchIntervalMs(new Config().setStaggerBatchIntervalMs(999_999)));
    }

    // ---------------- 总时长预算 ----------------

    @Test
    void budget_follows_plan_when_plan_is_small() {
        Config c = new Config().setRssSleepMinutes(15);
        // 15 分钟周期 → 上限 225s；4 批 × 2s = 6s 远小于上限 → 按计划值
        assertEquals(6_000L, RssTask.resolveStaggerBudgetMs(c, 2000, 4));
    }

    @Test
    void budget_is_capped_by_cycle_quarter() {
        Config c = new Config().setRssSleepMinutes(15);
        // 200 批 × 2s = 398s，但周期 1/4 = 225s → 封顶，避免整轮被拖长
        assertEquals(225_000L, RssTask.resolveStaggerBudgetMs(c, 2000, 200));
    }

    @Test
    void budget_uses_default_cycle_when_unset_or_illegal() {
        // rssSleepMinutes 缺失 → 按默认 15 分钟（225s）
        assertEquals(225_000L, RssTask.resolveStaggerBudgetMs(new Config(), 2000, 200));
        // 非法值（0 / 负）收敛到 1 分钟 → 15s
        assertEquals(15_000L, RssTask.resolveStaggerBudgetMs(new Config().setRssSleepMinutes(0), 2000, 200));
        assertEquals(15_000L, RssTask.resolveStaggerBudgetMs(new Config().setRssSleepMinutes(-5), 2000, 200));
    }

    @Test
    void budget_is_zero_for_single_batch() {
        // 只有 1 批 = 没有批间等待，预算为 0，不会凭空 sleep
        assertEquals(0L, RssTask.resolveStaggerBudgetMs(new Config(), 2000, 1));
    }

    // ---------------- 批次数 ----------------

    @Test
    void total_batch_shards_by_parallelism() {
        Config c = new Config();
        int count = 10;
        int parallelism = RssTask.resolveParallelism(c, count);
        assertEquals(3, parallelism, "并发默认值变化会影响分片，需重新评估");
        assertEquals(4, RssTask.resolveTotalBatch(c, count, parallelism));
    }

    @Test
    void total_batch_edge_cases() {
        assertEquals(1, RssTask.resolveTotalBatch(new Config(), 0, 3));
        assertEquals(1, RssTask.resolveTotalBatch(new Config(), -5, 3));
        // batchSize 非法（0/负）时按 1 处理，绝不除零
        assertEquals(5, RssTask.resolveTotalBatch(new Config(), 5, 0));
        assertEquals(5, RssTask.resolveTotalBatch(new Config(), 5, -1));
        assertEquals(3, RssTask.resolveTotalBatch(new Config(), 9, 3));
        assertEquals(3, RssTask.resolveTotalBatch(new Config(), 10, 4));
    }

    // ---------------- 进度文案 ----------------

    @Test
    void suffix_is_empty_for_single_batch() {
        // 尚未开始分批 / 未启用错峰 → 文案与改造前完全一致
        assertEquals("", RssTask.staggerSuffix());
        RssTask.RssJobState.totalBatch.set(1);
        assertEquals("", RssTask.staggerSuffix());
    }

    @Test
    void suffix_reports_batch_progress() {
        RssTask.RssJobState.totalBatch.set(4);
        RssJobState.currentBatch.set(2);
        String text = RssTask.staggerSuffix();
        assertTrue(text.contains("批次 2/4"), text);
        assertFalse(text.contains("后下一批"), "不在等待时不应出现倒计时: " + text);
    }

    @Test
    void suffix_clamps_current_batch() {
        RssJobState.totalBatch.set(4);
        // 0 = 尚未进入任何批次 → 显示 1/4，不出现 "批次 0/4"
        RssJobState.currentBatch.set(0);
        assertTrue(RssTask.staggerSuffix().contains("批次 1/4"), RssTask.staggerSuffix());
        // 越界值收敛，不出现 "批次 99/4"
        RssJobState.currentBatch.set(99);
        assertTrue(RssTask.staggerSuffix().contains("批次 4/4"), RssTask.staggerSuffix());
    }

    @Test
    void suffix_shows_countdown_while_waiting() {
        RssJobState.totalBatch.set(4);
        RssJobState.currentBatch.set(1);
        RssJobState.nextBatchAt.set(System.currentTimeMillis() + 5000L);
        String text = RssTask.staggerSuffix();
        assertTrue(text.contains("批次 1/4"), text);
        assertTrue(text.contains("后下一批"), text);

        // 已过期的 nextBatchAt 至少显示 1s，不出现 0s / 负数
        RssJobState.nextBatchAt.set(System.currentTimeMillis() - 5000L);
        assertTrue(RssTask.staggerSuffix().contains("（1s 后下一批）"), RssTask.staggerSuffix());
    }

    // ---------------- 可中断等待 ----------------

    @Test
    void sleep_interruptible_returns_immediately_for_non_positive() {
        long start = System.currentTimeMillis();
        assertEquals(0L, RssTask.sleepInterruptible(new AtomicBoolean(true), 0L));
        assertEquals(0L, RssTask.sleepInterruptible(new AtomicBoolean(true), -1L));
        assertTrue(System.currentTimeMillis() - start < 200L, "非正数不应产生等待");
    }

    @Test
    void sleep_interruptible_waits_full_duration_when_active() {
        long start = System.currentTimeMillis();
        long slept = RssTask.sleepInterruptible(new AtomicBoolean(true), 1200L);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 1100L, "实际等待 " + elapsed + "ms，应接近 1200ms");
        assertEquals(elapsed, slept, 100L, "返回值应反映真实等待时长");
    }

    @Test
    void sleep_interruptible_aborts_on_cancel() {
        AtomicBoolean loop = new AtomicBoolean(true);
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            loop.set(false);
        });
        canceller.start();
        long start = System.currentTimeMillis();
        RssTask.sleepInterruptible(loop, 30_000L);
        long elapsed = System.currentTimeMillis() - start;
        try {
            canceller.join(2000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(elapsed < 3000L, "取消后应在 1 个分片内返回，实际 " + elapsed + "ms");
    }
}
