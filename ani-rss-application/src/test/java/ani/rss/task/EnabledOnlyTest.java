package ani.rss.task;

import ani.rss.download.OpenListApi;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.util.other.AniUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F3 仅对「已启用」订阅执行更新 + F7-5 预算取值。
 * <p>
 * 需求文档里最容易做错的一点是"过滤一次就够"：轮次开始前按 {@code enable} 过滤一次，
 * 之后就一直用轮次开始时的<b>副本</b>。用户扫到一半关掉某个订阅，它照样会被扫描完——
 * 更糟的是 {@code notDownload} / {@code season} 的改动也会被这一轮的副本吃掉。
 * <p>
 * 这里固化两条：
 * <ol>
 *   <li>提交前、子任务开头都要重新读<b>实时对象</b>（{@code AniUtil.getAniList()}），
 *       而不是轮次开始时的快照；</li>
 *   <li>预算默认 = 启用订阅数 × 1，且一律受 200 次硬顶约束——
 *       订阅 500 个就允许打 500 次，等于没有预算。</li>
 * </ol>
 */
class EnabledOnlyTest {

    private List<Ani> backup;

    @BeforeEach
    void setUp() {
        backup = new ArrayList<>(AniUtil.getAniList());
        AniUtil.getAniList().clear();
    }

    @AfterEach
    void tearDown() {
        AniUtil.getAniList().clear();
        AniUtil.getAniList().addAll(backup);
    }

    // ---------------- F3-1 / F3-2 实时对象 ----------------

    @Test
    void enable_change_is_visible_through_live_lookup() {
        Ani live = new Ani().setId("ani-1").setTitle("测试").setEnable(true);
        AniUtil.getAniList().add(live);

        assertTrue(RssTask.isStillEnabled("ani-1"), "初始为启用");

        // 用户扫到一半关掉订阅
        live.setEnable(false);

        assertFalse(RssTask.isStillEnabled("ani-1"),
                "必须读到实时值，否则「关掉了却还在扫」会一直存在");
        assertSame(live, RssTask.resolveLiveAni("ani-1"), "应返回列表里的实时对象");
    }

    @Test
    void live_object_is_not_a_stale_copy() {
        Ani live = new Ani().setId("ani-1").setTitle("旧标题").setEnable(true);
        AniUtil.getAniList().add(live);

        // 轮次开始时按"值"复制一份（这是旧实现的问题所在）
        Ani staleCopy = new Ani().setId("ani-1").setTitle("旧标题").setEnable(true);

        live.setTitle("新标题").setEnable(false);

        Ani resolved = RssTask.resolveLiveAni("ani-1");
        assertNotNull(resolved);
        assertEquals("新标题", resolved.getTitle(), "应拿到实时标题");
        assertFalse(resolved.getEnable(), "应拿到实时启用状态");
        assertEquals("旧标题", staleCopy.getTitle(), "副本仍然是旧值——这正是必须重新读取的原因");
    }

    @Test
    void disabled_subscription_is_not_enabled() {
        AniUtil.getAniList().add(new Ani().setId("ani-1").setTitle("已关闭").setEnable(false));
        assertFalse(RssTask.isStillEnabled("ani-1"));
        assertNotNull(RssTask.resolveLiveAni("ani-1"), "对象还在，只是未启用");
    }

    @Test
    void missing_subscription_is_skipped() {
        // 轮次进行中删除了订阅
        assertFalse(RssTask.isStillEnabled("ani-gone"), "订阅不存在时应视为不可处理");
        assertNull(RssTask.resolveLiveAni("ani-gone"));
    }

    @Test
    void null_enable_is_treated_as_disabled() {
        AniUtil.getAniList().add(new Ani().setId("ani-1").setTitle("未设置"));
        assertFalse(RssTask.isStillEnabled("ani-1"),
                "enable 为 null 不能当成启用（只处理显式启用的订阅）");
    }

    @Test
    void blank_or_null_id_is_rejected() {
        AniUtil.getAniList().add(new Ani().setId("ani-1").setTitle("测试").setEnable(true));
        assertFalse(RssTask.isStillEnabled(null));
        assertFalse(RssTask.isStillEnabled(""));
        assertFalse(RssTask.isStillEnabled("   "));
        assertNull(RssTask.resolveLiveAni(null));
        assertNull(RssTask.resolveLiveAni(""));
    }

    @Test
    void live_lookup_matches_by_id_not_title() {
        AniUtil.getAniList().add(new Ani().setId("ani-1").setTitle("同名").setEnable(true));
        AniUtil.getAniList().add(new Ani().setId("ani-2").setTitle("同名").setEnable(false));

        assertTrue(RssTask.isStillEnabled("ani-1"));
        assertFalse(RssTask.isStillEnabled("ani-2"));
        assertEquals("ani-2", RssTask.resolveLiveAni("ani-2").getId());
    }

    // ---------------- F7-5 预算取值 ----------------

    @Test
    void budget_defaults_to_estimated_listings_not_subscription_count() {
        Config config = new Config();
        config.setOpenListApiBudgetPerRound(null);
        // 50 订阅：需求估算 = 50 × 4 = 200；周期负担（默认 1 次/秒 × 15 分钟 ÷ 4）= 225 → 取 200。
        // 旧口径给的是 50，而每个订阅实际要 1 + 子目录数 次列举，大库必然中途耗尽。
        assertEquals(50 * RssTask.LISTINGS_PER_SUBSCRIPTION_ESTIMATE,
                RssTask.resolveApiBudgetPerRound(config, 50));
    }

    /**
     * 真正要守的性质是"算出来不能是 0"：0 在 {@code startRoundBudget} 的语义里是"不限制"，
     * 所以 0 订阅也必须给出一个正数预算。这条断言不依赖具体取值，只锁意图。
     */
    @Test
    void budget_is_never_zero() {
        assertTrue(RssTask.resolveApiBudgetPerRound(new Config(), 0) > 0);
        assertTrue(RssTask.resolveApiBudgetPerRound(null, 0) > 0);

        Config zeroConfigured = new Config();
        zeroConfigured.setOpenListApiBudgetPerRound(0);
        assertTrue(RssTask.resolveApiBudgetPerRound(zeroConfigured, 0) > 0);
    }

    /**
     * 订阅数超过"周期负担"时的行为：保底值胜出，等于每订阅一次列举。
     * <p>
     * 这是<b>有意</b>的取舍，不是漏掉的边界：让每个订阅都至少能列举一次，好过让任意一批订阅
     * 完全不被校验——后者正是"一批订阅长期显示存疑"的成因，也正是本项要修的 bug。
     * 代价是这一轮被限流拖住的时长约为 {@code 订阅数 ÷ 速率}（1000 订阅 / 1 次每秒 ≈ 16.7 分钟，
     * 已超过默认 15 分钟周期，下一轮会顺延）；真要更快的全量扫描只能提高速率或缩短周期。
     */
    @Test
    void library_larger_than_period_allowance_falls_back_to_one_listing_per_subscription() {
        Config config = new Config();
        config.setOpenListApiBudgetPerRound(null);
        int affordable = RssTask.resolveAffordableListingsPerRound(config);
        assertEquals(affordable, RssTask.resolveApiBudgetPerRound(config, affordable),
                "恰好等于周期负担时两者一致");
        assertTrue(1000 > affordable, "前置条件：订阅数需超过周期负担，实际 " + affordable);
        assertEquals(1000, RssTask.resolveApiBudgetPerRound(config, 1000),
                "超过周期负担后由保底值决定：每订阅一次列举，旧实现只给 200");
    }

    @Test
    void default_budget_may_exceed_the_configured_value_hard_cap() {
        Config config = new Config();
        config.setOpenListApiBudgetPerRound(null);
        int budget = RssTask.resolveApiBudgetPerRound(config, 200);
        // 200 订阅需要约 800 次列举；旧实现无论怎么算都被 200 硬顶压住，后半程订阅全被判「存疑」。
        assertTrue(budget > OpenListApi.MAX_API_BUDGET_PER_ROUND,
                "大库的默认预算必须能突破 200，否则修复等于没做，实际 " + budget);
        assertEquals(RssTask.resolveAffordableListingsPerRound(config), budget,
                "200 订阅（需求 800 次）受周期负担约束，不得被 200 硬顶压回");
    }

    @Test
    void affordable_bound_follows_rate_and_period() {
        Config config = new Config()
                .setOpenListApiPerSecond(2)
                .setRssSleepMinutes(60);
        // 周期负担 = 2 × 3600 ÷ 4 = 1800；需求估算 = 500 × 4 = 2000 → 取 1800
        assertEquals(1800, RssTask.resolveApiBudgetPerRound(config, 500));
    }

    @Test
    void budget_never_drops_below_subscription_count() {
        Config config = new Config()
                .setOpenListApiPerSecond(1)
                .setRssSleepMinutes(4);
        // 周期负担只有 1 × 240 ÷ 4 = 60，但 500 个订阅至少要各列举一次 → 保底 500
        assertEquals(500, RssTask.resolveApiBudgetPerRound(config, 500),
                "每个订阅至少要能列举一次，否则会被整体判成「存疑」");
    }

    @Test
    void explicit_budget_wins_over_default() {
        Config config = new Config();
        config.setOpenListApiBudgetPerRound(50);
        assertEquals(50, RssTask.resolveApiBudgetPerRound(config, 500));
    }

    @Test
    void explicit_budget_is_still_capped_at_hard_limit() {
        Config config = new Config();
        config.setOpenListApiBudgetPerRound(9999);
        assertEquals(OpenListApi.MAX_API_BUDGET_PER_ROUND, RssTask.resolveApiBudgetPerRound(config, 10),
                "手填值仍受硬顶约束（防手滑）；只有计算出的默认值可以突破它");
    }

    @Test
    void illegal_budget_falls_back_to_one() {
        Config config = new Config();
        config.setOpenListApiBudgetPerRound(0);
        assertEquals(1, RssTask.resolveApiBudgetPerRound(config, 10),
                "0 应回落为 1（0 在后端语义里是「不限制」，不能由用户误配出来）");
        config.setOpenListApiBudgetPerRound(-3);
        assertEquals(1, RssTask.resolveApiBudgetPerRound(config, 10));
    }

    @Test
    void null_config_does_not_throw() {
        assertEquals(5 * RssTask.LISTINGS_PER_SUBSCRIPTION_ESTIMATE,
                RssTask.resolveApiBudgetPerRound(null, 5));
        assertEquals(RssTask.LISTINGS_PER_SUBSCRIPTION_ESTIMATE,
                RssTask.resolveApiBudgetPerRound(null, 0));
    }

    @Test
    void affordable_listings_uses_effective_rate_not_raw_config() {
        // 速率未配置时应按限流器的默认值 1 计算，而不是当成 0
        assertEquals(1 * 900 / 4, RssTask.resolveAffordableListingsPerRound(new Config()));
        // 超过上限的速率按上限 20 钳制，与 OpenListApi.effectiveApiPerSecond 保持一致
        assertEquals(20 * 900 / 4,
                RssTask.resolveAffordableListingsPerRound(new Config().setOpenListApiPerSecond(999)));
        // 速率低于下限按 1 钳制
        assertEquals(1 * 900 / 4,
                RssTask.resolveAffordableListingsPerRound(new Config().setOpenListApiPerSecond(0)));
    }
}
