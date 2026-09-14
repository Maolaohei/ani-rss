package ani.rss.download;

import ani.rss.entity.Item;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 合集判定：总集数为 1 时必须按单集处理，不得判为合集。
 * <p>
 * 覆盖两处此前按「数量」而非「集数」判定的逻辑：
 * <ul>
 *   <li>{@link BaseDownload#isMultiEpisode(List)} —— 本地下载器重命名（qB / Aria2）；</li>
 *   <li>{@link OpenList#buildCollectionItem(List, String)} —— 手动「添加合集」上传种子。</li>
 * </ul>
 */
class MultiEpisodeDetectTest {

    /**
     * isMultiEpisode 是 BaseDownload 的 default 方法，qBittorrent / Aria2 的重命名都直接调用它，
     * 各实现未覆写；用无参可构造的 OpenList 实例即可验证同一份逻辑。
     */
    private final BaseDownload downloader = new OpenList();

    // ========== 本地下载器多集判定 ==========

    @Test
    void single_episode_with_extra_video_files_is_not_multi() {
        // 单集种子常带 NCOP/PV/菜单等视频文件，按「文件数」会误判为多文件合集
        assertFalse(downloader.isMultiEpisode(List.of(
                "番剧 S01E01 [1080p].mkv", "NCOP.mkv", "PV.mkv")));
    }

    @Test
    void two_distinct_episodes_is_multi() {
        assertTrue(downloader.isMultiEpisode(List.of("番剧 01.mkv", "番剧 02.mkv")));
        assertTrue(downloader.isMultiEpisode(List.of("番剧 S01E01.mkv", "番剧 S01E02.mkv")));
    }

    @Test
    void zero_padded_variants_count_as_same_episode() {
        // "01" 与 "1" 是同一集，不应因字符串不同被算成两集
        assertFalse(downloader.isMultiEpisode(List.of("番剧 01.mkv", "番剧 1.mkv")));
    }

    @Test
    void subtitles_do_not_inflate_episode_count() {
        assertFalse(downloader.isMultiEpisode(List.of(
                "番剧 S01E05.mkv", "番剧 S01E05.chs.ass", "番剧 S01E05.cht.ass")));
    }

    @Test
    void empty_or_unparsable_is_not_multi() {
        assertFalse(downloader.isMultiEpisode(List.of()));
        assertFalse(downloader.isMultiEpisode(List.of("a.mkv", "b.mkv")), "提取不到集数不判为多集");
        assertFalse(downloader.isMultiEpisode(List.of("番剧 S01E01.mkv")));
    }

    // ========== 手动「添加合集」合成 Item ==========

    private Item planItem(String title, double episode) {
        return new Item().setTitle(title).setReName(title).setEpisode(episode);
    }

    @Test
    void single_episode_plan_is_not_collection() {
        OpenList openList = new OpenList();
        Item built = openList.buildCollectionItem(List.of(
                planItem("番剧 S01E01.mkv", 1.0),
                planItem("番剧 S01E01.chs.ass", 1.0)), "torrent-root");

        assertNull(built.getEpisodeRange(), "总集数为 1 不应标记 episodeRange（否则被当合集处理）");
        assertEquals(1.0, built.getEpisode());
        assertEquals("番剧 S01E01", built.getReName(), "基名取正片 reName 主名");
    }

    @Test
    void multi_episode_plan_is_collection() {
        OpenList openList = new OpenList();
        Item built = openList.buildCollectionItem(List.of(
                planItem("番剧 S01E01.mkv", 1.0),
                planItem("番剧 S01E02.mkv", 2.0)), "torrent-root");

        assertEquals(List.of(1.0, 2.0), built.getEpisodeRange());
        assertEquals("番剧 S01E01", built.getReName());
    }
}
