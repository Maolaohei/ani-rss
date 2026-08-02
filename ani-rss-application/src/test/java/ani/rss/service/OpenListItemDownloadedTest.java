package ani.rss.service;

import ani.rss.download.OpenList;
import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenList 网盘"本地已下载"判断测试 — 用 mock OpenList 模拟 AList API 返回的文件列表
 * 场景来自实机验证: /115/动漫/转存/追番/乡下大叔成了剑圣/Season 2 的 S02E01-03.mkv
 */
class OpenListItemDownloadedTest {

    private final DownloadService downloadService = new DownloadService();

    private OpenList mockOpenList(List<String> fileNames) {
        return new OpenList() {
            @Override
            public List<String> listFileNames(String dirPath) {
                return fileNames;
            }
        };
    }

    @BeforeEach
    void setUp() {
        ConfigUtil.CONFIG.setRenameTemplate(null)
                .setOvaRenameTemplate(null)
                .setDownloadPathTemplate("/115/动漫/转存/追番/${title}/Season ${seasonFormat}")
                .setOvaDownloadPathTemplate("/115/动漫/转存/剧场版/${title}")
                .setDownloadToolType("OpenList")
                .setRename(true).setFileExist(true)
                .setRenameDelYear(false).setRenameDelTmdbId(false);
    }

    @AfterEach
    void tearDown() {
        ConfigUtil.CONFIG.setDownloadToolType(null).setRename(null).setFileExist(null)
                .setDownloadPathTemplate(null).setOvaDownloadPathTemplate(null);
        TorrentUtil.DOWNLOAD = null;
    }

    private Ani ani(boolean ova, String mediaType, String title, int season) {
        Ani ani = new Ani();
        ani.setTitle(title).setOva(ova).setMediaType(mediaType)
                .setSeason(season).setOffset(0).setBgmUrl("https://bgm.tv/subject/123")
                .setThemoviedbName("").setNamingVersion(2)
                .setCustomRenameTemplateEnable(false).setCustomEpisode(false)
                .setReleaseDate(new Date(116, 9, 21));
        return ani;
    }

    private Item item(String reName, Double episode) {
        Item item = new Item();
        item.setTitle("x").setEpisode(episode).setReName(reName)
                .setTorrent("magnet:?xt=urn:btih:abcdef0123456789")
                .setInfoHash("abcdef0123456789").setMaster(true).setSubgroup("测试字幕组");
        return item;
    }

    @Test
    void openlist_normal_episodes_detected() {
        // 实机场景: Season 2 目录有 S02E01-03.mkv
        TorrentUtil.DOWNLOAD = mockOpenList(List.of(
                "乡下大叔成了剑圣 S02E01.mkv",
                "乡下大叔成了剑圣 S02E02.mkv",
                "乡下大叔成了剑圣 S02E03.mkv"));
        Ani ani = ani(false, null, "乡下大叔成了剑圣 (2025) [tmdbid=260823]", 2);
        assertTrue(downloadService.itemDownloaded(ani, item("乡下大叔成了剑圣 S02E01", 1.0), false),
                "S02E01 应判定已下载");
        assertTrue(downloadService.itemDownloaded(ani, item("乡下大叔成了剑圣 S02E03", 3.0), false),
                "S02E03 应判定已下载");
        assertFalse(downloadService.itemDownloaded(ani, item("乡下大叔成了剑圣 S02E05", 5.0), false),
                "S02E05(未下载)不应判定已下载");
    }

    @Test
    void openlist_movie_detected() {
        // 剧场版: 网盘文件名与 reName 匹配
        TorrentUtil.DOWNLOAD = mockOpenList(List.of(
                "你的名字 (2016) [测试字幕组].mkv"));
        Ani ani = ani(true, "movie", "你的名字", 1);
        assertTrue(downloadService.itemDownloaded(ani, item("你的名字 (2016) [测试字幕组]", 1.0), false),
                "剧场版网盘文件应判定已下载");
    }

    @Test
    void openlist_ova_special_s00() {
        // OVA 特典式: 网盘 S00E01 匹配 season=0
        TorrentUtil.DOWNLOAD = mockOpenList(List.of(
                "魔法使的新娘 等待繁星之人 S00E01.mkv"));
        Ani ani = ani(true, "ova", "魔法使的新娘 等待繁星之人", 1);
        assertTrue(downloadService.itemDownloaded(ani, item("魔法使的新娘 等待繁星之人 S00E01", 1.0), false),
                "OVA 特典式 S00E01 应判定已下载");
    }

    @Test
    void openlist_empty_dir_not_downloaded() {
        // 空目录(或未下载): 判定未下载
        TorrentUtil.DOWNLOAD = mockOpenList(List.of());
        Ani ani = ani(false, null, "未下载番剧 (2026) [tmdbid=1]", 1);
        assertFalse(downloadService.itemDownloaded(ani, item("未下载番剧 S01E01", 1.0), false),
                "空目录不应判定已下载");
    }
}
