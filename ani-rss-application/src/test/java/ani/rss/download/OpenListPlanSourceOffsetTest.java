package ani.rss.download;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.testsupport.TestTorrent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 期望文件计划的集数口径：必须用「产生该条目的那条 RSS」的偏移，而不是订阅的 {@code ani.offset}。
 * <p>
 * 为什么必须有这个测试：一个订阅可挂多条备用 RSS，每条有自己的集数偏移。同一部番的 Baha 源
 * 按累计集数编号（96），主源按季内集数编号（24）；用户给备用源单独配 -72 后，
 * RSS 条目的 {@code reName} 是 {@code S04E24}，但计划里的目标名是按订阅的 {@code ani.offset}
 * 算的，于是离线完成后把文件重命名成 {@code S04E96} —— 集数错了整整一个偏移量。
 * <p>
 * 它能失败的所有方式（这是保留它的唯一理由）：
 * <ol>
 *   <li>计划构建回到用订阅的 {@code ani.offset} → 目标名回到 {@code S04E96}（本测试直接失败）；</li>
 *   <li>{@link Item#getRssOffset()} 没被 RSS 解析侧写入（备用分支只 setMaster）→
 *       计划拿不到来源偏移，同上失败；</li>
 *   <li>{@code planAni} 的克隆把偏移改丢了 / 改错方向 → 断言失败；</li>
 *   <li>{@code filterEpisodes} 的期望集数（item 口径）与计划集数（来源偏移口径）对不上时
 *       整份计划被清空 → 计划为空，断言失败。</li>
 * </ol>
 * 反向验证方法：把 {@code buildPlanFromTorrentFile} 里的 {@code planAni(ani, item)} 换回
 * {@code ani}，本测试必须失败（目标名变回 {@code S04E96.mp4}）。
 */
class OpenListPlanSourceOffsetTest {

    private static final String SOURCE_FILE =
            "[ANi] 關於我轉生變成史萊姆這檔事 第四季 - 96 [1080P][Baha][WEB-DL][AAC AVC][CHT].mp4";

    private static Ani subscription() {
        return new Ani()
                .setId("ani-slime")
                .setTitle("关于我转生变成史莱姆这档事")
                .setSubgroup("ANi")
                .setSeason(4)
                // 主订阅没配偏移：条目来自带 -72 的备用 RSS
                .setOffset(0)
                .setOva(false)
                .setMediaType("tv")
                .setBgmUrl("https://bgm.tv/subject/123")
                .setThemoviedbName("")
                .setNamingVersion(2)
                .setCustomEpisode(false)
                .setMatch(List.of())
                .setExclude(List.of())
                .setGlobalExclude(false)
                .setCustomRenameTemplateEnable(false);
    }

    @Test
    @DisplayName("备用 RSS 带 -72 偏移：计划目标名落在 S04E24，而不是文件名的 S04E96")
    void plan_uses_source_rss_offset() throws Exception {
        File torrent = TestTorrent.temp("plan-source-offset",
                "[ANi] 關於我轉生變成史萊姆這檔事 第四季",
                List.of(TestTorrent.file(SOURCE_FILE, 1031L)));

        // RSS 条目：episode/reName 都是按来源那条 RSS 的 -72 算出来的
        Item item = new Item()
                .setTitle(SOURCE_FILE)
                .setReName("关于我转生变成史莱姆这档事 S04E24")
                .setEpisode(24.0)
                .setRssOffset(-72);

        List<Item> plan = OpenList.buildPlanFromTorrentFile(torrent, subscription(), item, List.of());

        assertEquals(1, plan.size(), "单文件种子应产出 1 条计划");
        assertEquals(24.0, plan.get(0).getEpisode(), "计划集数必须按来源 RSS 的偏移换算（96 - 72 = 24）");
        // 只断言集数口径，不锁模板文案（模板由用户配置决定，重命名用的是 reName 而非 episode）
        String reName = plan.get(0).getReName();
        assertTrue(reName.contains("E24"), "归位目标名必须落在 S04E24，实际: " + reName);
        assertFalse(reName.contains("E96"), "归位目标名不得落在文件名的 S04E96，实际: " + reName);
    }

    @Test
    @DisplayName("条目没带来源偏移（老数据）：保持原行为，不做平移")
    void plan_without_source_offset_keeps_previous_behaviour() throws Exception {
        File torrent = TestTorrent.temp("plan-no-source-offset",
                "[ANi] 關於我轉生變成史萊姆這檔事 第四季",
                List.of(TestTorrent.file(SOURCE_FILE, 1031L)));

        Item item = new Item()
                .setTitle(SOURCE_FILE)
                .setReName("关于我转生变成史莱姆这档事 S04E96")
                .setEpisode(96.0);

        List<Item> plan = OpenList.buildPlanFromTorrentFile(torrent, subscription(), item, List.of());

        assertEquals(1, plan.size());
        assertEquals(96.0, plan.get(0).getEpisode(), "来源偏移缺失时必须回退订阅口径（此处订阅偏移为 0）");
        assertTrue(plan.get(0).getReName().contains("E96"),
                "不得凭空平移，实际: " + plan.get(0).getReName());
    }
}
