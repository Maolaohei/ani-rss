package ani.rss.service;

import ani.rss.download.OpenList;
import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 预览「本地存在」判定：以<b>目标路径下的真实文件</b>为准，而不是本地 .torrent 记录。
 * <p>
 * 旧实现只要 {@code TorrentUtil.getTorrent()} 这个本地缓存记录存在就标"存在"并短路，
 * 于是网盘文件被删/移动/改名、或种子从未落地时会把"不存在"谎报成"存在"。
 * 本测试覆盖新判定的三条边界：
 * <ul>
 *   <li>事实与策略分离：关掉「文件存在时不下载」不应让"文件在"变成"文件不在"；</li>
 *   <li>严格索引：网盘列举失败必须抛出（供展示层标"存疑"），而非静默当成"目录为空"；</li>
 *   <li>本地/网盘两条路径都按季集匹配真实文件。</li>
 * </ul>
 */
class PreviewLocalExistsTest {

    private static final String CLOUD_DIR = "/115/动漫/转存/追番/乡下大叔成了剑圣/Season 2";

    private final DownloadService downloadService = new DownloadService();

    private OpenList mockOpenList(List<String> fileNames) {
        return new OpenList() {
            @Override
            public List<String> listFileNames(String dirPath) {
                return fileNames;
            }

            @Override
            public List<String> listFileNamesStrict(String dirPath) {
                return fileNames;
            }
        };
    }

    @BeforeEach
    void setUp() {
        ConfigUtil.CONFIG.setRenameTemplate(null)
                .setOvaRenameTemplate(null)
                .setDownloadPathTemplate(CLOUD_DIR)
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

    private Ani ani(String title, int season) {
        Ani ani = new Ani();
        ani.setTitle(title).setOva(false).setSeason(season).setOffset(0)
                .setBgmUrl("https://bgm.tv/subject/123")
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
    void real_file_wins_over_policy_switches() {
        TorrentUtil.DOWNLOAD = mockOpenList(List.of("乡下大叔成了剑圣 S02E01.mkv"));
        Ani ani = ani("乡下大叔成了剑圣", 2);
        Item item = item("乡下大叔成了剑圣 S02E01", 1.0);
        Set<String> index = downloadService.buildEpisodeIndexStrict(ani, CLOUD_DIR);

        // 关掉「文件存在时不下载」：下载流程不再跳过，但"文件在不在"这一事实不受影响
        ConfigUtil.CONFIG.setFileExist(false);
        assertFalse(downloadService.itemDownloaded(ani, item, false),
                "策略开关关闭时下载去重应放行（旧语义不变）");
        assertTrue(downloadService.itemFileExists(ani, item, index),
                "预览「本地存在」表达事实，不应被策略开关改写");
    }

    @Test
    void openlist_index_reports_only_real_files() {
        TorrentUtil.DOWNLOAD = mockOpenList(List.of(
                "乡下大叔成了剑圣 S02E01.mkv",
                "乡下大叔成了剑圣 S02E03.mkv"));
        Ani ani = ani("乡下大叔成了剑圣", 2);
        Set<String> index = downloadService.buildEpisodeIndexStrict(ani, CLOUD_DIR);

        assertTrue(downloadService.itemFileExists(ani, item("乡下大叔成了剑圣 S02E01", 1.0), index));
        assertTrue(downloadService.itemFileExists(ani, item("乡下大叔成了剑圣 S02E03", 3.0), index));
        assertFalse(downloadService.itemFileExists(ani, item("乡下大叔成了剑圣 S02E05", 5.0), index),
                "网盘里没有 E05，不能因为本地有种子记录就判定存在");
    }

    @Test
    void listing_failure_throws_in_strict_mode_but_degrades_in_dedup_mode() {
        TorrentUtil.DOWNLOAD = new OpenList() {
            @Override
            public List<String> listFileNames(String dirPath) {
                // 非严格版：旧行为，失败退化成空列表
                return List.of();
            }

            @Override
            public List<String> listFileNamesStrict(String dirPath) {
                throw new IllegalStateException("网盘 API 502");
            }
        };
        Ani ani = ani("乡下大叔成了剑圣", 2);

        assertThrows(IllegalStateException.class,
                () -> downloadService.buildEpisodeIndexStrict(ani, CLOUD_DIR),
                "严格版必须抛出，供展示层区分「目录确实为空」与「查询失败」");
        assertFalse(downloadService.itemDownloaded(ani, item("乡下大叔成了剑圣 S02E01", 1.0), false),
                "下载去重路径应保持旧行为：查不到就当未下载（保守重下，不会误跳过）");
    }

    @Test
    void local_mode_index_matches_episode_from_disk(@TempDir Path tempDir) throws IOException {
        TorrentUtil.DOWNLOAD = null;
        Files.writeString(tempDir.resolve("测试番剧 S01E01.mkv"), "x", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("测试番剧 S01E01.chs.ass"), "x", StandardCharsets.UTF_8);

        Ani ani = ani("测试番剧", 1);
        Set<String> index = downloadService.buildEpisodeIndexStrict(ani, tempDir.toString());

        assertTrue(downloadService.itemFileExists(ani, item("测试番剧 S01E01", 1.0), index));
        assertFalse(downloadService.itemFileExists(ani, item("测试番剧 S01E02", 2.0), index),
                "字幕文件不应被当成视频计入集数索引");
    }

    @Test
    void missing_local_dir_is_empty_not_failure() {
        TorrentUtil.DOWNLOAD = null;
        Ani ani = ani("测试番剧", 1);
        Set<String> index = downloadService.buildEpisodeIndexStrict(ani, "/definitely/not/exists");
        assertTrue(index.isEmpty(), "本地目录不存在属于「确认没有」，不是查询失败");
        assertFalse(downloadService.itemFileExists(ani, item("测试番剧 S01E01", 1.0), index));
    }

    // ---------------- F5-2：记录与文件不一致 ----------------

    @Test
    void stale_torrent_records_are_counted_but_never_removed() {
        // 有记录 + 确认没有文件 → 不一致（界面提示"可清理"）
        Item stale = new Item().setHasTorrentRecord(true);
        // 有记录 + 文件确实在 → 一致
        Item present = new Item().setHasTorrentRecord(true).setHasDownloaded(true);
        // 有记录 + 无法确认（网盘列举失败）→ 不算不一致，只是"不知道"
        Item unknown = new Item().setHasTorrentRecord(true).setHasDownloadedUnknown(true);
        // 有记录 + 正在下载（离线任务未落地）→ 不算不一致
        Item downloading = new Item().setHasTorrentRecord(true).setDownloading(true);
        // 无记录 + 没有文件 → 本来就干净
        Item clean = new Item();

        assertEquals(1, DownloadService.countStaleTorrentRecords(
                List.of(stale, present, unknown, downloading, clean)));

        // 只统计、不改动：记录必须原样保留 —— 它是"这集曾经下过"的唯一线索
        assertTrue(stale.getHasTorrentRecord(), "不得因判定为不一致而清掉种子记录");
        assertEquals(0, DownloadService.countStaleTorrentRecords(List.of()));
        assertEquals(0, DownloadService.countStaleTorrentRecords(null));
    }
}
