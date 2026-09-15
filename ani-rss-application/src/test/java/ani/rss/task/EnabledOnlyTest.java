package ani.rss.task;

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
    void budget_defaults_to_enabled_subscription_count() {
        Config config = new Config();
        config.setOpenListApiBudgetPerRound(null);
        assertEquals(80, RssTask.resolveApiBudgetPerRound(config, 80),
                "默认 = 启用订阅数 × 1（每个订阅至少一次列举）");
        assertEquals(1, RssTask.resolveApiBudgetPerRound(config, 0),
                "没有订阅时也要给 1，避免预算为 0 被当成「不限制」");
    }

    @Test
    void explicit_budget_wins_over_default() {
        Config config = new Config();
        config.setOpenListApiBudgetPerRound(50);
        assertEquals(50, RssTask.resolveApiBudgetPerRound(config, 500));
    }

    @Test
    void budget_is_capped_at_hard_limit() {
        Config config = new Config();
        config.setOpenListApiBudgetPerRound(9999);
        assertEquals(200, RssTask.resolveApiBudgetPerRound(config, 10),
                "无论订阅多少，单轮最多 200 次");
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
        assertEquals(5, RssTask.resolveApiBudgetPerRound(null, 5));
        assertEquals(1, RssTask.resolveApiBudgetPerRound(null, 0));
    }
}
