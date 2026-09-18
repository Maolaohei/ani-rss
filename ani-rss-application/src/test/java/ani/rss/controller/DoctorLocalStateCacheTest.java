package ani.rss.controller;

import ani.rss.entity.Ani;
import ani.rss.service.LocalStateCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自检页必须把缓存计数器<b>全部</b>展示出来 —— "埋了指标没人看"等于没有指标。
 * <p>
 * 背景：加「增量追加」计数器时只更新了 {@code LocalStateCache.stats()}，忘了自检页的展示文案
 * （那里读的是各个独立 getter，不走 {@code stats()}），于是这个指标在界面上根本看不见。
 * 而它恰恰是网盘 TTL 敢用 24h 的<b>前提</b>：若它长期为 0，说明每集仍在走"整份失效 + 重列"，
 * 网盘 API 消耗会悄悄回到改造前的量级，但界面上一片正常。
 * <p>
 * 所以这里断言的不是"文案里有这几个字"，而是"计数真的变，文案真的跟着变"。
 */
class DoctorLocalStateCacheTest {

    private static final String PATH = "/115/动漫/转存/追番/某番 (2026) [tmdbid=1]/Season 1";

    @BeforeEach
    void setUp() {
        LocalStateCache.clear();
    }

    @AfterEach
    void tearDown() {
        LocalStateCache.clear();
    }

    @Test
    @DisplayName("自检文案包含「增量追加」计数，且随实际追加变化")
    void evidence_exposes_appended_counter() throws Exception {
        Ani ani = new Ani().setId("ani-1").setTitle("测试订阅");

        String before = DoctorController.localStateCacheEvidence();
        assertTrue(before.contains("增量追加 0 次"),
                "前置条件：清空后追加计数应为 0，实际: " + before);

        // 先建一份快照（追加的前提），再追加一集
        LocalStateCache.getOrBuild(ani, PATH, LocalStateCache.Source.CLOUD_API,
                () -> LocalStateCache.Loaded.of(Set.of("1:1.0"), true));
        LocalStateCache.appendEpisode("ani-1", PATH, Set.of("1:2.0"));

        String after = DoctorController.localStateCacheEvidence();
        assertTrue(after.contains("增量追加 1 次"),
                "追加一次后自检文案必须体现出来，实际: " + after);
    }
}
