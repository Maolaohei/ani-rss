package ani.rss.service;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.RenameUtil;
import wushuo.tmdb.api.entity.Tmdb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * getDownloadPath 下载路径模板替换测试
 */
class DownloadPathTest {

    private final DownloadService downloadService = new DownloadService();

    @BeforeEach
    void setUp() {
        ConfigUtil.CONFIG.setRenameTemplate(null)
                .setOvaRenameTemplate(null)
                .setRenameDelYear(false)
                .setRenameDelTmdbId(false);
    }

    @AfterEach
    void tearDown() {
        ConfigUtil.CONFIG.setDownloadPathTemplate(null)
                .setOvaDownloadPathTemplate(null);
    }

    private Ani ani(boolean ova, String mediaType) {
        Ani ani = new Ani();
        ani.setTitle("你的名字").setOva(ova).setMediaType(mediaType)
                .setSeason(2).setOffset(0).setBgmUrl("https://bgm.tv/subject/12345")
                .setThemoviedbName("你的名字 剧场版").setSubgroup("测试字幕组")
                .setNamingVersion(2)
                .setReleaseDate(new Date(116, 9, 21)) // 2016-10
                .setTmdb(new Tmdb().setId("378064"));
        return ani;
    }

    @Test
    void template_placeholders_replaced() {
        ConfigUtil.CONFIG.setDownloadPathTemplate(
                "/downloads/${letter}/${title}/Season ${seasonFormat}/${tmdbid}/${subgroup}");
        String path = downloadService.getDownloadPath(ani(false, null), ConfigUtil.CONFIG);
        // letter = 拼音首字母大写(你 -> N), title 原样, season=2 -> 02
        assertTrue(path.contains("/downloads/N/你的名字/Season 02/378064/测试字幕组"),
                "占位符应全部替换: " + path);
    }

    @Test
    void ova_uses_ova_template() {
        ConfigUtil.CONFIG.setDownloadPathTemplate("/downloads/番剧/${title}/Season ${seasonFormat}");
        ConfigUtil.CONFIG.setOvaDownloadPathTemplate("/downloads/剧场版/${title}");
        String path = downloadService.getDownloadPath(ani(true, "movie"), ConfigUtil.CONFIG);
        assertTrue(path.contains("/downloads/剧场版/你的名字"),
                "剧场版应使用 ova 模板: " + path);
        assertFalse(path.contains("Season"), "剧场版路径不应含季");
    }

    @Test
    void year_quarter_month() {
        ConfigUtil.CONFIG.setDownloadPathTemplate(
                "/downloads/${year}/${quarterName}/${quarterFormat}/${monthFormat}/${month}");
        String path = downloadService.getDownloadPath(ani(false, null), ConfigUtil.CONFIG);
        // 2016-10 月 → 秋(quarter=10), 月份 10
        assertTrue(path.contains("/downloads/2016/秋/10/10/10"), "年份/季度/月份应正确: " + path);
    }

    @Test
    void december_quarter_rolls_year() {
        // 12 月 → 冬(quarter=1), 年份 +1
        Ani ani = ani(false, null);
        ani.setReleaseDate(new Date(116, 11, 5)); // 2016-12
        ConfigUtil.CONFIG.setDownloadPathTemplate("/downloads/${year}/${quarterName}/${quarterFormat}");
        String path = downloadService.getDownloadPath(ani, ConfigUtil.CONFIG);
        assertTrue(path.contains("/downloads/2017/冬/01"), "12月应归冬季且年份+1: " + path);
    }

    @Test
    void custom_download_path_wins() {
        ConfigUtil.CONFIG.setDownloadPathTemplate("/downloads/默认/${title}");
        Ani ani = ani(false, null);
        ani.setCustomDownloadPath(true)
                .setDownloadPath("E:\\media\\自定义路径\\${title}");
        String path = downloadService.getDownloadPath(ani, ConfigUtil.CONFIG);
        assertTrue(path.contains("自定义路径"), "自定义路径应优先: " + path);
    }
}
