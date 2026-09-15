package ani.rss.service;

import ani.rss.entity.Ani;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 射手网搜索关键词的选取规则。
 * <p>
 * ASSRT 的条目名以英文/原文为主，用中文标题常常搜不到，因此必须<b>优先英文标题</b>；
 * 同时要剔除 TMDB 附加的年份与 id 后缀，否则关键词会被收得过窄。
 */
class SubtitleSearchKeywordTest {

    @Test
    void prefers_tmdb_name_when_it_has_latin_letters() {
        Ani ani = new Ani()
                .setTitle("碧蓝之海")
                .setJpTitle("ぐらんぶる")
                .setThemoviedbName("Grand Blue");
        assertEquals("Grand Blue", SubtitleService.searchKeyword(ani));
    }

    @Test
    void falls_back_to_title_when_tmdb_name_is_chinese() {
        Ani ani = new Ani()
                .setTitle("Grand Blue Season 3")
                .setJpTitle("ぐらんぶる")
                .setThemoviedbName("碧蓝之海");
        assertEquals("Grand Blue Season 3", SubtitleService.searchKeyword(ani));
    }

    @Test
    void falls_back_to_jp_title_when_no_latin_available() {
        Ani ani = new Ani()
                .setTitle("碧蓝之海")
                .setJpTitle("ぐらんぶる")
                .setThemoviedbName("碧蓝之海 第三季");
        assertEquals("ぐらんぶる", SubtitleService.searchKeyword(ani));
    }

    @Test
    void strips_tmdb_year_and_id_suffix() {
        Ani ani = new Ani().setThemoviedbName("High School DxD (2018) {tmdb-12345}");
        assertEquals("High School DxD", SubtitleService.searchKeyword(ani));
    }

    @Test
    void strips_year_in_brackets_and_collapses_separators() {
        Ani ani = new Ani().setThemoviedbName("KonoSuba_-_Gods_Blessing [2016]");
        assertEquals("KonoSuba Gods Blessing", SubtitleService.searchKeyword(ani));
    }

    @Test
    void falls_back_to_chinese_title_when_nothing_else_available() {
        Ani ani = new Ani().setTitle("碧蓝之海");
        assertEquals("碧蓝之海", SubtitleService.searchKeyword(ani));
    }

    @Test
    void handles_null_and_blank_fields() {
        assertEquals("", SubtitleService.searchKeyword(null));
        assertEquals("", SubtitleService.searchKeyword(new Ani()));
        assertEquals("Grand Blue", SubtitleService.searchKeyword(
                new Ani().setTitle("  ").setThemoviedbName("  Grand Blue  ")));
    }
}
