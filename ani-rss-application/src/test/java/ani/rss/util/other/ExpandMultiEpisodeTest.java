package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * expandMultiEpisode 展开/去重逻辑测试(合集种子 → 子集)
 */
class ExpandMultiEpisodeTest {

    private Ani ani(boolean ova, String mediaType) {
        Ani ani = new Ani();
        ani.setTitle("测试番剧").setOva(ova).setMediaType(mediaType)
                .setSeason(1).setOffset(0).setBgmUrl("https://bgm.tv/subject/123")
                .setThemoviedbName("").setNamingVersion(2)
                .setCustomRenameTemplateEnable(false).setCustomEpisode(false)
                .setReleaseDate(new Date(116, 9, 21));
        return ani;
    }

    private Item item(String title, String reName) {
        Item item = new Item();
        item.setTitle(title).setEpisode(1.0).setReName(reName)
                .setTorrent("magnet:?xt=urn:btih:abcdef0123456789")
                .setInfoHash("abcdef0123456789")
                .setMaster(true).setSubgroup("测试字幕组");
        return item;
    }

    @Test
    void range_expands_to_episodes_with_renamed_reName() {
        // 01-06 范围 → 6 个子集, episode 1-6, reName 同步 S01E01~E06
        Ani ani = ani(false, null);
        Item base = item("测试番剧 01-06 [1080P]", "测试番剧 S01E01");
        List<Item> out = ItemsUtil.expandMultiEpisode(ani, List.of(base));
        assertEquals(6, out.size(), "应展开 6 个子集");
        for (int i = 0; i < 6; i++) {
            Item c = out.get(i);
            assertEquals(i + 1.0, c.getEpisode(), "episode 应为 " + (i + 1));
            assertEquals("测试番剧 S01E0" + (i + 1), c.getReName(), "reName 集数应同步");
            assertNotNull(c.getEpisodeRange(), "应带范围标记");
        }
    }

    @Test
    void range_dotted_S00E01_updates_keeping_dot() {
        // OVA 特典式带点模板: S00.E01 → S00.E02(点保留)
        Ani ani = ani(true, "ova");
        Item base = item("测试番剧 01-02 [OVA]", "测试番剧 S00.E01");
        List<Item> out = ItemsUtil.expandMultiEpisode(ani, List.of(base));
        assertEquals(2, out.size());
        assertEquals("测试番剧 S00.E02", out.get(1).getReName(), "带点格式应正确更新");
    }

    @Test
    void ova_without_sxxexx_appends_episode_suffix() {
        // OVA 特典式模板不含 S/E: 追加 E 序号避免子集同名
        Ani ani = ani(true, "ova");
        Item base = item("测试番剧 01-02 [OVA]", "测试番剧 [字幕组]");
        List<Item> out = ItemsUtil.expandMultiEpisode(ani, List.of(base));
        assertEquals(2, out.size());
        assertNotEquals(out.get(0).getReName(), out.get(1).getReName(), "子集 reName 不应同名");
        assertEquals("测试番剧 [字幕组] E02", out.get(1).getReName());
    }

    @Test
    void movie_never_expands() {
        // 剧场版(电影式): 不按集数范围展开
        Ani ani = ani(true, "movie");
        Item base = item("测试电影 1-2 [电影]", "测试电影 (2016) [字幕组]");
        List<Item> out = ItemsUtil.expandMultiEpisode(ani, List.of(base));
        assertEquals(1, out.size(), "电影不应展开");
    }

    @Test
    void list_expands() {
        Ani ani = ani(false, null);
        Item base = item("测试番剧 01,02,03", "测试番剧 S01E01");
        List<Item> out = ItemsUtil.expandMultiEpisode(ani, List.of(base));
        assertEquals(3, out.size());
        assertEquals(2.0, out.get(1).getEpisode());
    }

    @Test
    void part_upper_lower_when_no_episode() {
        // 上/下篇(episode<=0 时)按 part 补充
        Ani ani = ani(true, "movie");
        Item upper = item("测试电影 上篇", "测试电影 (2016) [字幕组]");
        upper.setEpisode(0.0);
        List<Item> out = ItemsUtil.expandMultiEpisode(ani, List.of(upper));
        // movie 不展开(返回原列表), part 由 rename 处理
        assertEquals(1, out.size());
    }

    @Test
    void no_range_no_expand() {
        Ani ani = ani(false, null);
        Item base = item("测试番剧 - 05 [1080P]", "测试番剧 S01E05");
        List<Item> out = ItemsUtil.expandMultiEpisode(ani, List.of(base));
        assertEquals(1, out.size(), "无范围不应展开");
        assertEquals(1.0, out.get(0).getEpisode());
    }
}
