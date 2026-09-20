package ani.rss.controller;

import ani.rss.download.OpenList;
import ani.rss.entity.OpenListFileInfo;
import ani.rss.entity.PlayItem;
import ani.rss.util.other.TorrentUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 媒体库的网盘侧：
 * <ul>
 *   <li>{@code scanCloud} / {@code toPlayItems} 现在<b>只服务于单订阅详情</b>
 *       （用户点开某个订阅时的按需列举）；</li>
 *   <li>媒体库<b>批量</b>扫描已不再列举网盘，集数改由订阅级本地状态快照派生
 *       （见 {@code LibraryOfflineSnapshotTest}）。</li>
 * </ul>
 */
class LibraryCloudScanTest {

    private static final String CLOUD_DIR = "/115/动漫/转存/追番/乡下大叔成了剑圣/Season 2";

    private OpenListFileInfo video(String name, long size) {
        return new OpenListFileInfo()
                .setName(name).setSize(size).setIsDir(false)
                .setPath(CLOUD_DIR).setModified(new Date(126, 0, 1));
    }

    private void mockOpenList(List<OpenListFileInfo> files) {
        TorrentUtil.DOWNLOAD = new OpenList() {
            @Override
            public List<OpenListFileInfo> listFilesStrict(String dirPath) {
                return files;
            }
        };
    }

    @AfterEach
    void tearDown() {
        TorrentUtil.DOWNLOAD = null;
    }

    @Test
    void cloud_scan_counts_videos_and_total_size() {
        mockOpenList(List.of(
                video("乡下大叔成了剑圣 S02E01.mkv", 400L * 1024 * 1024),
                video("乡下大叔成了剑圣 S02E02.mkv", 300L * 1024 * 1024),
                new OpenListFileInfo().setName("sub_bak").setIsDir(true).setPath(CLOUD_DIR)));

        LibraryController.CloudScan scan = LibraryController.scanCloud(CLOUD_DIR);

        assertNotNull(scan, "查询成功应返回结果（null 只用于表示查询失败）");
        assertEquals(2, scan.videos.size(), "目录项不应计入视频");
        assertEquals(700L * 1024 * 1024, scan.totalSize);
        assertEquals(new Date(126, 0, 1).getTime(), scan.lastModify);
    }

    @Test
    void cloud_scan_skips_tiny_and_non_video_but_keeps_them_for_subtitle_count() {
        mockOpenList(List.of(
                video("乡下大叔成了剑圣 S02E01.mkv", 400L * 1024 * 1024),
                video("预告 PV.mkv", 5L * 1024 * 1024),
                video("乡下大叔成了剑圣 S02E01.chs.ass", 1024L),
                video("readme.txt", 1024L)));

        LibraryController.CloudScan scan = LibraryController.scanCloud(CLOUD_DIR);

        assertEquals(1, scan.videos.size(), "过小文件与字幕不应计入集数");
        assertEquals(4, scan.allFiles.size(), "全量列表要保留字幕，供详情页统计字幕数");
    }

    @Test
    void cloud_scan_failure_returns_null_not_empty() {
        TorrentUtil.DOWNLOAD = new OpenList() {
            @Override
            public List<OpenListFileInfo> listFilesStrict(String dirPath) {
                throw new IllegalStateException("网盘 API 502");
            }
        };

        assertNull(LibraryController.scanCloud(CLOUD_DIR),
                "查询失败必须返回 null，不能与「目录确实为空」混为一谈");
    }

    @Test
    void non_offline_tool_yields_empty_scan() {
        TorrentUtil.DOWNLOAD = null;

        LibraryController.CloudScan scan = LibraryController.scanCloud(CLOUD_DIR);

        assertNotNull(scan);
        assertTrue(scan.videos.isEmpty());
        assertEquals(0L, scan.totalSize);
    }

    @Test
    void to_play_items_parses_episode_size_and_subtitles() {
        mockOpenList(List.of(
                video("乡下大叔成了剑圣 S02E03.mkv", 400L * 1024 * 1024),
                video("乡下大叔成了剑圣 S02E03.chs.ass", 1024L)));

        LibraryController.CloudScan scan = LibraryController.scanCloud(CLOUD_DIR);
        List<PlayItem> items = LibraryController.toPlayItems(scan);

        assertEquals(1, items.size());
        PlayItem playItem = items.get(0);
        assertEquals(3.0, playItem.getEpisode().doubleValue(), "集数应从 SxxExx 解析，而不是恒为 1");
        assertEquals("S02E03", playItem.getTitle());
        assertEquals(CLOUD_DIR + "/乡下大叔成了剑圣 S02E03.mkv", playItem.getFilename());
        assertNotNull(playItem.getFormatSize());
        assertEquals(1, playItem.getSubtitles().size(), "同主名字幕应被统计");
        assertEquals("乡下大叔成了剑圣 S02E03.chs.ass", playItem.getSubtitles().get(0).getName());
    }
}
