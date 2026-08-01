package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RenameUtil.rename 命名输出测试 — 使用真实风格的 RSS 种子标题
 * 覆盖: 剧场版电影式(单部/多部 Part)、OVA 特典式、年份处理(A3 修复)
 */
class RenameOutputTest {

    private Ani ani(boolean ova, String mediaType, String title) {
        Ani ani = new Ani();
        ani.setTitle(title)
                .setOva(ova)
                .setMediaType(mediaType)
                .setSeason(1)
                .setOffset(0)
                .setBgmUrl("https://bgm.tv/subject/123")
                .setThemoviedbName("")
                .setNamingVersion(2)
                .setCustomRenameTemplateEnable(false)
                .setCustomEpisode(false)
                .setReleaseDate(new Date(116, 9, 21)); // 2016-10-21
        return ani;
    }

    private Item item(String title) {
        Item item = new Item();
        item.setTitle(title);
        item.setEpisode(1.0);
        item.setTorrent("magnet:?xt=urn:btih:abcdef0123456789");
        item.setInfoHash("abcdef0123456789");
        item.setSubgroup("喵萌茶会字幕组");
        return item;
    }

    /** 重置共享配置为默认值(测试间避免相互污染) */
    private void resetConfig() {
        ConfigUtil.CONFIG.setRenameTemplate(null)
                .setOvaRenameTemplate(null)
                .setRenameDelYear(false)
                .setRenameDelTmdbId(false);
    }

    @Test
    void movie_single() {
        resetConfig();
        // 剧场版单部: 电影式命名, 带年份
        Ani ani = ani(true, "movie", "你的名字");
        Item item = item("【喵萌字幕组】你的名字 [1080P][简繁日]");
        assertTrue(RenameUtil.rename(ani, item));
        assertEquals("你的名字 (2016) [喵萌茶会字幕组]", item.getReName());
    }

    @Test
    void movie_part_upper_lower() {
        resetConfig();
        // 上篇/下篇 两部式: Part 1 / Part 2, Part 紧跟标题
        Ani ani = ani(true, "movie", "伤物语");

        Item upper = item("伤物语 上篇 [1080P]");
        assertTrue(RenameUtil.rename(ani, upper));
        assertEquals("伤物语 Part 1 (2016) [喵萌茶会字幕组]", upper.getReName());

        Item lower = item("伤物语 下篇 [1080P]");
        assertTrue(RenameUtil.rename(ani, lower));
        assertEquals("伤物语 Part 2 (2016) [喵萌茶会字幕组]", lower.getReName());
    }

    @Test
    void movie_part_roman() {
        resetConfig();
        // Part II 罗马数字 → Part 2
        Ani ani = ani(true, "movie", "伤物语");
        Item item = item("伤物语 Part II [1080P]");
        assertTrue(RenameUtil.rename(ani, item));
        assertEquals("伤物语 Part 2 (2016) [喵萌茶会字幕组]", item.getReName());
    }

    @Test
    void ova_special() {
        resetConfig();
        // OVA 特典式: S00Exx(season=0), 集数解析失败保持 E01
        ConfigUtil.CONFIG.setRenameTemplate("[${subgroup}] ${title} S${seasonFormat}.E${episodeFormat}");
        Ani ani = ani(true, "ova", "魔法使的新娘 等待繁星之人");
        Item item = item("【喵萌茶会字幕组】★OVA【魔法使的新娘 等待繁星之人】[01-03][End][1080P][MP4][繁体]");
        assertTrue(RenameUtil.rename(ani, item));
        assertEquals("[喵萌茶会字幕组] 魔法使的新娘 等待繁星之人 S00.E01", item.getReName());
    }

    @Test
    void movie_year_kept_when_del_year_enabled() {
        resetConfig();
        // A3 修复: 开启"剔除年份"时, ${year} 补回的年份不应被 renameDel 删掉
        ConfigUtil.CONFIG.setRenameDelYear(true);
        Ani ani = ani(true, "movie", "你的名字");
        Item item = item("【喵萌字幕组】你的名字 [1080P]");
        assertTrue(RenameUtil.rename(ani, item));
        assertEquals("你的名字 (2016) [喵萌茶会字幕组]", item.getReName());
    }

    @Test
    void movie_year_empty_no_bare_brackets() {
        resetConfig();
        // releaseDate 为空: 不残留空括号 "()"
        Ani ani = ani(true, "movie", "你的名字");
        ani.setReleaseDate(null);
        Item item = item("【喵萌字幕组】你的名字 [1080P]");
        assertTrue(RenameUtil.rename(ani, item));
        assertEquals("你的名字 [喵萌茶会字幕组]", item.getReName());
        assertFalse(item.getReName().contains("()"));
    }
}
