package ani.rss.controller;

import ani.rss.entity.Ani;
import ani.rss.service.LocalStateCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体库在 OpenList 模式下<b>不再列举网盘</b>，集数改由订阅级本地状态快照派生。
 * <p>
 * 原因：批量扫描每次都遍历全部订阅、与预览/RSS 抢令牌桶与列举预算，是媒体库最"鸡肋"的一项开销。
 * 而快照（与预览 / 手动搜索 / RSS 主流程共用的那一份）本来就有集数，取它零 API 调用。
 * <p>
 * 两条必须守住的语义：
 * <ul>
 *   <li><b>没有快照 ≠ 没有内容</b>：标「未确认」，否则 OpenList 用户的库看起来是空的；</li>
 *   <li>集数是"快照里已知的集数"、占用空间不可测 —— 必须让前端能区分（{@code cacheOnly}）。</li>
 * </ul>
 */
class LibraryOfflineSnapshotTest {

    @AfterEach
    void tearDown() {
        LocalStateCache.clear();
    }

    private static Ani ani() {
        return new Ani().setId("ani-library").setTitle("乡下大叔成了剑圣").setSeason(2);
    }

    private static LocalStateCache.Snapshot snapshot(Set<String> episodes) throws Exception {
        Ani ani = ani();
        return LocalStateCache.getOrBuild(ani, "/115/动漫/转存/追番/乡下大叔成了剑圣/Season 2",
                LocalStateCache.Source.CLOUD_API,
                () -> LocalStateCache.Loaded.of(episodes, true));
    }

    @Test
    @DisplayName("没有快照 → 「未确认」，不是 0 集")
    void without_snapshot_is_unknown_not_zero() {
        LibraryController.LibraryItem item = LibraryController.applyLocalSnapshot(
                new LibraryController.LibraryItem(), null);

        assertFalse(item.isExists());
        assertTrue(item.isUnknown(), "「不知道」不能混成「确认没有」，否则 OpenList 用户的库看起来是空的");
        assertEquals(0, item.getVideoCount());
        assertFalse(item.isCacheOnly());
    }

    @Test
    @DisplayName("有快照 → 已知集数 + 快照时间，并标 cacheOnly（占用空间不可测）")
    void snapshot_fills_known_episodes() throws Exception {
        LibraryController.LibraryItem item = LibraryController.applyLocalSnapshot(
                new LibraryController.LibraryItem(), snapshot(Set.of("2:1.0", "2:2.0", "2:3.0")));

        assertTrue(item.isExists());
        assertFalse(item.isUnknown());
        assertTrue(item.isCloud(), "数据来自网盘侧（快照）");
        assertTrue(item.isCacheOnly(), "必须让前端知道这不是实时列举的结果");
        assertEquals(3, item.getVideoCount(), "此时 videoCount 的含义是「快照里已知的集数」");
        assertEquals("不可测", item.getFormatSize(), "大小要列网盘才知道，不能拿 0 冒充");
        assertEquals(0L, item.getTotalSize(), "cacheOnly 条目不参与占用空间汇总");
        assertTrue(item.getLastModify() > 0L, "快照时间即数据新鲜度");
    }

    @Test
    @DisplayName("空快照 → 已确认没有内容（不是未确认）")
    void empty_snapshot_is_absent() throws Exception {
        LibraryController.LibraryItem item = LibraryController.applyLocalSnapshot(
                new LibraryController.LibraryItem(), snapshot(Set.of()));

        assertFalse(item.isExists());
        assertFalse(item.isUnknown(), "快照是成功的列举结果，「空」就是确认没有");
        assertTrue(item.isCacheOnly());
    }
}
