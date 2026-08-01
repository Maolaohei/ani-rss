package ani.rss.service;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * itemDownloaded / buildLocalEpisodeIndex 本地已下载判断测试
 * 覆盖: 普通番剧(season:episode)、OVA 特典式(S00)、剧场版(M: 文件名)
 */
class ItemDownloadedTest {

    private final DownloadService downloadService = new DownloadService();

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        ConfigUtil.CONFIG.setRenameTemplate(null)
                .setOvaRenameTemplate(null)
                .setDownloadPathTemplate("/downloads/${title}")
                .setOvaDownloadPathTemplate("/downloads/剧场版/${title}")
                .setRename(true)
                .setFileExist(true)
                .setRenameDelYear(false)
                .setRenameDelTmdbId(false);
    }

    @AfterEach
    void tearDown() {
        ConfigUtil.CONFIG.setRename(null).setFileExist(null)
                .setDownloadPathTemplate(null).setOvaDownloadPathTemplate(null);
    }

    private Ani ani(boolean ova, String mediaType, String title, int season) {
        Ani ani = new Ani();
        ani.setTitle(title).setOva(ova).setMediaType(mediaType)
                .setSeason(season).setOffset(0).setBgmUrl("https://bgm.tv/subject/123")
                .setThemoviedbName("").setNamingVersion(2)
                .setCustomRenameTemplateEnable(false).setCustomEpisode(false)
                .setReleaseDate(new Date(116, 9, 21))
                .setCustomDownloadPath(true)
                .setDownloadPath(tempDir.getAbsolutePath());
        return ani;
    }

    private Item item(String title, String reName, Double episode) {
        Item item = new Item();
        item.setTitle(title).setEpisode(episode).setReName(reName)
                .setTorrent("magnet:?xt=urn:btih:abcdef0123456789")
                .setInfoHash("abcdef0123456789")
                .setMaster(true).setSubgroup("测试字幕组");
        return item;
    }

    private void putFile(String name) {
        FileUtil.writeUtf8String("test", new File(tempDir, name));
    }

    @Test
    void normal_season_episode_match() {
        putFile("测试番剧 S01E01.mkv");
        Ani ani = ani(false, null, "测试番剧", 1);
        assertTrue(downloadService.itemDownloaded(ani, item("x", "测试番剧 S01E01", 1.0), false),
                "S01E01 应判定已下载");
        assertFalse(downloadService.itemDownloaded(ani, item("x", "测试番剧 S01E02", 2.0), false),
                "S01E02 不应判定已下载");
    }

    @Test
    void ova_special_uses_season_zero() {
        // OVA 特典式落盘 S00Exx, 即使订阅 season=1 也应匹配(0:episode)
        putFile("测试OVA S00E01.mkv");
        Ani ani = ani(true, "ova", "测试OVA", 1);
        assertTrue(downloadService.itemDownloaded(ani, item("x", "测试OVA S00E01", 1.0), false),
                "OVA 特典式 S00E01 应判定已下载");
    }

    @Test
    void movie_matches_by_reName() {
        putFile("你的名字 (2016) [测试字幕组].mkv");
        Ani ani = ani(true, "movie", "你的名字", 1);
        assertTrue(downloadService.itemDownloaded(ani, item("x", "你的名字 (2016) [测试字幕组]", 1.0), false),
                "电影按 reName 匹配应判定已下载");
    }

    @Test
    void no_match_when_file_absent() {
        Ani ani = ani(false, null, "测试番剧", 1);
        assertFalse(downloadService.itemDownloaded(ani, item("x", "测试番剧 S01E01", 1.0), false),
                "无文件不应判定已下载");
    }

    @Test
    void rename_disabled_returns_false() {
        ConfigUtil.CONFIG.setRename(false);
        putFile("测试番剧 S01E01.mkv");
        Ani ani = ani(false, null, "测试番剧", 1);
        assertFalse(downloadService.itemDownloaded(ani, item("x", "测试番剧 S01E01", 1.0), false),
                "未开启重命名不判断本地");
    }
}
