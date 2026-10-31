package ani.rss.util.other;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 集数偏移的负数闸门。
 * <p>
 * 线上真实事故（2026-10-31，v3.4.36）：青之箱 第二季的 RSS 用<b>季内编号</b>
 * （{@code [01]..[04]}），而该订阅被配了 {@code offset = -25}，
 * 于是 {@code 4 + (-25) = -21} 被直接拼进 renameTemplate，得到
 * {@code 青之箱 S02E-21} 这种名字——网盘里永远不可能存在，
 * 归位对账注定 NOT_FOUND，随后每轮「确认没有 → 保留记录」永不收敛，
 * 24 小时刷出 276 行告警（占 42.6%）。
 * <p>
 * 闸门的取向：**宁可回退成 RSS 原始集数并报错**，也不要静默产出非法文件名。
 */
class RenameUtilEpisodeOffsetTest {

    @Test
    @DisplayName("正常偏移：季内集数 → 绝对集数")
    void normal_offset() {
        assertEquals(21.0, RenameUtil.applyEpisodeOffset(4.0, 17, "某番"));
        assertEquals(13.0, RenameUtil.applyEpisodeOffset(1.0, 12, "某番"));
        assertEquals(25.0, RenameUtil.applyEpisodeOffset(25.0, 0, "某番"));
    }

    @Test
    @DisplayName("★ 算到负数：回退 RSS 原始集数（绝不放 S02E-21 出门）")
    void negative_result_falls_back() {
        assertEquals(4.0, RenameUtil.applyEpisodeOffset(4.0, -25, "青之箱 (2024) [tmdbid=207347]"),
                "4 + (-25) = -21 时必须回退成 4，否则网盘里那个名字永远不存在");
        assertEquals(1.0, RenameUtil.applyEpisodeOffset(1.0, -25, "青之箱"));
        assertEquals(2.0, RenameUtil.applyEpisodeOffset(2.0, -25, "青之箱"));
    }

    @Test
    @DisplayName("算到 0 也算非法：S02E00 不存在")
    void zero_result_falls_back() {
        assertEquals(25.0, RenameUtil.applyEpisodeOffset(25.0, -25, "某番"));
    }

    @Test
    @DisplayName("偏移恰好落在 1：合法，不触发回退")
    void boundary_one_is_legal() {
        assertEquals(1.0, RenameUtil.applyEpisodeOffset(26.0, -25, "某番"));
    }

    @Test
    @DisplayName("小数集数（.5 特别篇）在偏移后仍为正则保留")
    void fractional_episode() {
        assertEquals(12.5, RenameUtil.applyEpisodeOffset(0.5, 12, "某番"));
    }
}
