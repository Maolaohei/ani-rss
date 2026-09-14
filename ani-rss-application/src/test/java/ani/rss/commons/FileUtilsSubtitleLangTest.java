package ani.rss.commons;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字幕语言后缀提取（字幕重命名规则 2/3）。
 * <p>
 * 规则：字幕名含语言标识（SC/TC/JP/CHT/CHS 等）时，重命名追加对应语言后缀
 * （如 碧蓝之海 S03E15.cht.ass / .jpsc.ass / .sc.ass）；未匹配到语言则不加后缀。
 */
class FileUtilsSubtitleLangTest {

    @Test
    void recognizes_common_language_tokens() {
        assertEquals("chs", FileUtils.extractSubtitleLangSuffix("碧蓝之海 S03E15.chs.ass"));
        assertEquals("cht", FileUtils.extractSubtitleLangSuffix("碧蓝之海 S03E15.cht.ass"));
        assertEquals("sc", FileUtils.extractSubtitleLangSuffix("碧蓝之海 S03E15.sc.ass"));
        assertEquals("tc", FileUtils.extractSubtitleLangSuffix("碧蓝之海 S03E15.tc.ass"));
        assertEquals("jpsc", FileUtils.extractSubtitleLangSuffix("碧蓝之海 S03E15.jpsc.ass"));
        assertEquals("jptc", FileUtils.extractSubtitleLangSuffix("碧蓝之海 S03E15.jptc.ass"));
        assertEquals("jp", FileUtils.extractSubtitleLangSuffix("碧蓝之海 S03E15.JP.srt"));
    }

    @Test
    void normalizes_token_to_lowercase() {
        assertEquals("chs", FileUtils.extractSubtitleLangSuffix("Show S01E01.CHS.ass"));
        assertEquals("jpsc", FileUtils.extractSubtitleLangSuffix("Show S01E01.JPSC.ass"));
        assertEquals("cht", FileUtils.extractSubtitleLangSuffix("Show S01E01.CHT.ass"));
    }

    @Test
    void returns_null_when_no_language_token() {
        assertNull(FileUtils.extractSubtitleLangSuffix("碧蓝之海 S03E15.ass"), "无语言标识不应追加后缀");
        assertNull(FileUtils.extractSubtitleLangSuffix("Grand Blue Dreaming - 15.ass"));
        assertNull(FileUtils.extractSubtitleLangSuffix(""));
        assertNull(FileUtils.extractSubtitleLangSuffix(null));
    }

    @Test
    void does_not_treat_resolution_or_number_as_language() {
        assertNull(FileUtils.extractSubtitleLangSuffix("Show S01E01.1080p.ass"));
        assertNull(FileUtils.extractSubtitleLangSuffix("Show Vol.1.ass"));
    }

    @Test
    void strips_directory_prefix() {
        assertEquals("cht", FileUtils.extractSubtitleLangSuffix("Subs/Show S01E01.cht.ass"));
    }

    @Test
    void supports_multi_level_language_suffix() {
        assertEquals("chs&eng.simplified",
                FileUtils.extractSubtitleLangSuffix("Show S01E01.chs&eng.simplified.ass"));
    }

    @Test
    void known_token_check_is_case_insensitive() {
        assertTrue(FileUtils.isKnownSubtitleLangToken("JPSC"));
        assertTrue(FileUtils.isKnownSubtitleLangToken("cht"));
        assertFalse(FileUtils.isKnownSubtitleLangToken("1080p"));
        assertFalse(FileUtils.isKnownSubtitleLangToken(null));
    }
}
