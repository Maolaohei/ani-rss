package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.testsupport.TestTorrent;
import org.eclipse.bittorrent.TorrentFile;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「期望文件计划」：种子 → 计划（文件名/字节数/最终命名/集数）。
 * <p>
 * 这里的字节数正确性是整个计划链路的基石——它是后面匹配网盘产物的主键，
 * 一旦与种子内文件错位（早期实现"先过滤再递增下标"就有这个毛病），
 * 线上表现为"文件明明下完了却永远匹配不上"。
 */
class TorrentPlanUtilTest {

    private static Ani ani() {
        return new Ani()
                .setId("ani-1")
                .setTitle("Show")
                .setSubgroup("LoliHouse")
                .setSeason(1)
                .setOffset(0)
                .setOva(false)
                .setMediaType("tv")
                .setBgmUrl("https://bgm.tv/subject/123")
                .setThemoviedbName("")
                .setNamingVersion(2)
                .setCustomEpisode(false)
                .setMatch(List.of())
                .setExclude(List.of())
                .setGlobalExclude(false)
                .setCustomRenameTemplateEnable(false);
    }

    @Test
    void build_keeps_byte_length_aligned_with_each_file() throws Exception {
        File torrent = TestTorrent.temp("plan-align", "Show S01", List.of(
                TestTorrent.file("Show S01/Show - 01.mkv", 1031L),
                TestTorrent.file("Show S01/Show - 01.ass", 41L),
                TestTorrent.file("Show S01/Show - 02.mkv", 1032L)));

        List<Item> plan = TorrentPlanUtil.build(new TorrentFile(torrent), ani());

        assertEquals(3, plan.size());
        Item video = plan.stream().filter(i -> i.getTitle().endsWith("01.mkv")).findFirst().orElseThrow();
        Item sub = plan.stream().filter(i -> i.getTitle().endsWith(".ass")).findFirst().orElseThrow();
        Item video2 = plan.stream().filter(i -> i.getTitle().endsWith("02.mkv")).findFirst().orElseThrow();

        assertEquals(1031L, video.getLength(), "视频字节数必须与种子内对应条目一致（含过滤后仍与原始下标对齐）");
        assertEquals(41L, sub.getLength(), "字幕字节数必须与种子内对应条目一致");
        assertEquals(1032L, video2.getLength(), "第二个视频的字节数必须与种子内对应条目一致");
        assertEquals(1.0, video.getEpisode(), "正片应解析出集数");
        assertTrue(video.getReName().contains("E01"), "最终命名应套用模板: " + video.getReName());
        assertTrue(TorrentPlanUtil.isVideo(video));
        assertTrue(TorrentPlanUtil.isSubtitle(sub));
    }

    @Test
    void build_filters_by_exclude_rules() throws Exception {
        File torrent = TestTorrent.temp("plan-exclude", "Show S01", List.of(
                TestTorrent.file("Show S01/Show - 01.mkv", 100L),
                TestTorrent.file("Show S01/Fonts/font.ttf", 200L)));
        Ani ani = ani().setExclude(List.of("Fonts"));

        List<Item> plan = TorrentPlanUtil.build(new TorrentFile(torrent), ani);

        assertEquals(1, plan.size(), "Fonts 应被排除规则过滤");
        assertTrue(plan.get(0).getTitle().endsWith(".mkv"));
    }

    @Test
    void filter_episodes_drops_other_episodes_of_a_season_pack() throws Exception {
        File torrent = TestTorrent.temp("plan-filter", "Show S01", List.of(
                TestTorrent.file("Show S01/Show - 01.mkv", 101L),
                TestTorrent.file("Show S01/Show - 02.mkv", 102L),
                TestTorrent.file("Show S01/Show - 03.mkv", 103L)));
        List<Item> full = TorrentPlanUtil.build(new TorrentFile(torrent), ani());

        List<Item> only3 = TorrentPlanUtil.filterEpisodes(full, List.of(3.0));

        assertEquals(1, only3.size(), "单集订阅从整季包里只该拿走自己那一集");
        assertEquals(3.0, only3.get(0).getEpisode());
        assertEquals(103L, only3.get(0).getLength());
    }

    @Test
    void filter_episodes_keeps_single_video_torrent_whole() throws Exception {
        File torrent = TestTorrent.temp("plan-single", "Show S01E05", List.of(
                TestTorrent.file("Show S01E05.mkv", 500L),
                TestTorrent.file("Show S01E05.ass", 50L)));
        List<Item> full = TorrentPlanUtil.build(new TorrentFile(torrent), ani());

        // 期望集数对不上（比如改名后解析成别的集数）也要整份保留：单文件种子整份就是这一集
        List<Item> plan = TorrentPlanUtil.filterEpisodes(full, List.of(99.0));

        assertEquals(2, plan.size(), "单视频种子应整份保留（含字幕）");
    }

    @Test
    void filter_episodes_keeps_everything_when_no_expected_episode() throws Exception {
        File torrent = TestTorrent.temp("plan-movie", "Movie", List.of(
                TestTorrent.file("Movie/Movie.mkv", 900L),
                TestTorrent.file("Movie/Movie.ass", 90L)));
        List<Item> full = TorrentPlanUtil.build(new TorrentFile(torrent), ani());

        assertEquals(full, TorrentPlanUtil.filterEpisodes(full, List.of()));
    }

    @Test
    void expected_episodes_prefers_range_then_single() {
        Item item = new Item().setEpisode(5.0);
        assertEquals(List.of(5.0), TorrentPlanUtil.expectedEpisodesOf(item));

        item.setEpisodeRange(List.of(1.0, 2.0));
        assertEquals(List.of(1.0, 2.0), TorrentPlanUtil.expectedEpisodesOf(item));

        assertTrue(TorrentPlanUtil.expectedEpisodesOf(new Item()).isEmpty());
        assertTrue(TorrentPlanUtil.expectedEpisodesOf(null).isEmpty());
    }

    @Test
    void episode_index_keys_only_cover_resolved_videos() throws Exception {
        File torrent = TestTorrent.temp("plan-keys", "Show S01", List.of(
                TestTorrent.file("Show S01/Show - 01.mkv", 101L),
                TestTorrent.file("Show S01/Show - 01.ass", 11L),
                TestTorrent.file("Show S01/Show - 02.mkv", 102L)));
        List<Item> plan = TorrentPlanUtil.build(new TorrentFile(torrent), ani());

        List<Item> only1 = plan.stream().filter(i -> i.getTitle().endsWith("01.mkv")).toList();
        var keys = TorrentPlanUtil.episodeIndexKeys(ani(), only1);

        assertEquals(1, keys.size(), "只应标记真正归位的视频: " + keys);
        assertTrue(keys.stream().anyMatch(k -> k.endsWith(":1.0")), keys.toString());
        assertFalse(keys.stream().anyMatch(k -> k.endsWith(":2.0")), "没归位的集不能被标记");
    }
}
