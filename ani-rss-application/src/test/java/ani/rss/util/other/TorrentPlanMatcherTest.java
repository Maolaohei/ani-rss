package ani.rss.util.other;

import ani.rss.entity.Item;
import ani.rss.entity.OpenListFileInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划 ↔ 网盘产物 的匹配规则。
 * <p>
 * 这些用例固化的都是线上真实形态：
 * <ul>
 *   <li>115 把文件改成别的名字（甚至按文件名再套一层同名目录）→ 必须靠<b>字节数</b>认出来；</li>
 *   <li>同名多候选（多语言/多版本）与同长度多候选 → <b>不猜</b>，交回旧逻辑；</li>
 *   <li>视频与字幕同长度 → 视频优先认领，不能被字幕抢走。</li>
 * </ul>
 */
class TorrentPlanMatcherTest {

    private static Item plan(String title, String reName, Long length, Double episode) {
        return new Item().setTitle(title).setReName(reName).setLength(length).setEpisode(episode);
    }

    private static OpenListFileInfo file(String path, String name, long size) {
        return new OpenListFileInfo().setPath(path).setName(name).setSize(size).setIsDir(false);
    }

    private static OpenListFileInfo dir(String path, String name) {
        return new OpenListFileInfo().setPath(path).setName(name).setSize(0L).setIsDir(true);
    }

    @Test
    void matches_by_size_when_cloud_renamed_the_file() {
        List<Item> plan = List.of(
                plan("Show S01/Show - 03.mkv", "Show S01E03.mkv", 1031L, 3.0),
                plan("Show S01/Show - 03.ass", "Show S01E03.cht.ass", 41L, 3.0));
        // 115 形态：按文件名建同名目录 + 名字被改
        List<OpenListFileInfo> files = List.of(
                dir("/115/tmp", "Show - 03.mkv"),
                file("/115/tmp/Show - 03.mkv", "Show - 03.mkv", 1031L),
                file("/115/tmp", "random_subtitle_name.ass", 41L));

        TorrentPlanMatcher.Result result = TorrentPlanMatcher.match(plan, files);

        assertTrue(result.videosComplete(), "字节数命中即视为到位");
        assertTrue(result.complete(), "字幕也应按字节数命中");
        assertEquals("Show S01E03.mkv", result.matches().get(0).plan().getReName());
        assertEquals(1, result.videoMatches().size());
        assertTrue(result.unmatched().isEmpty(), "目录条目不算未匹配产物：" + result.unmatched());
    }

    @Test
    void missing_video_makes_incomplete_even_if_subtitle_present() {
        List<Item> plan = List.of(
                plan("Show/Show - 03.mkv", "Show S01E03.mkv", 1031L, 3.0),
                plan("Show/Show - 03.ass", "Show S01E03.ass", 41L, 3.0));
        List<OpenListFileInfo> files = List.of(file("/115/tmp", "sub.ass", 41L));

        TorrentPlanMatcher.Result result = TorrentPlanMatcher.match(plan, files);

        assertFalse(result.videosComplete(), "视频没到就不能算完成");
        assertEquals(1, result.missingVideos().size());
        assertTrue(result.missingVideos().get(0).getTitle().endsWith(".mkv"));
    }

    @Test
    void ambiguous_same_size_candidates_are_not_guessed() {
        List<Item> plan = List.of(
                plan("Show/Show - 03.mkv", "Show S01E03.mkv", 1031L, 3.0),
                plan("Show/Show - 04.mkv", "Show S01E04.mkv", 1031L, 4.0));
        // 两个文件同长度、名字都对不上 → 无法唯一确定
        List<OpenListFileInfo> files = List.of(
                file("/115/tmp", "a.mkv", 1031L),
                file("/115/tmp", "b.mkv", 1031L));

        TorrentPlanMatcher.Result result = TorrentPlanMatcher.match(plan, files);

        assertFalse(result.videosComplete(), "候选不唯一时必须放弃而不是乱认");
        assertEquals(2, result.missingVideos().size());
    }

    @Test
    void size_and_name_tier_disambiguates_same_size_candidates() {
        List<Item> plan = List.of(
                plan("Show/Show - 03.mkv", "Show S01E03.mkv", 1031L, 3.0),
                plan("Show/Show - 04.mkv", "Show S01E04.mkv", 1031L, 4.0));
        List<OpenListFileInfo> files = List.of(
                file("/115/tmp", "Show - 04.mkv", 1031L),
                file("/115/tmp", "Show - 03.mkv", 1031L));

        TorrentPlanMatcher.Result result = TorrentPlanMatcher.match(plan, files);

        assertTrue(result.videosComplete(), "字节数+文件名双命中即可唯一确定");
        assertTrue(result.matches().stream().anyMatch(m ->
                m.file().getName().equals("Show - 03.mkv") && m.plan().getReName().contains("E03")));
    }

    @Test
    void video_wins_over_subtitle_with_same_size() {
        // 极端但真实：字幕与视频恰好同长度
        List<Item> plan = List.of(
                plan("Show/Show - 03.mkv", "Show S01E03.mkv", 500L, 3.0),
                plan("Show/Show - 03.ass", "Show S01E03.ass", 500L, 3.0));
        List<OpenListFileInfo> files = List.of(file("/115/tmp", "unknown_file", 500L));

        TorrentPlanMatcher.Result result = TorrentPlanMatcher.match(plan, files);

        assertTrue(result.videosComplete(), "视频必须先被认领");
        assertEquals(1, result.videoMatches().size());
        assertTrue(result.missing().stream().anyMatch(i -> i.getTitle().endsWith(".ass")),
                "字幕没被认领（同长度只有一个文件）");
    }

    @Test
    void same_size_unique_candidate_is_accepted_without_name_match() {
        List<Item> plan = List.of(
                plan("Show/Show - 03.mkv", "Show S01E03.mkv", 1031L, 3.0),
                plan("Show/Show - 03.ass", "Show S01E03.ass", 41L, 3.0));
        List<OpenListFileInfo> files = List.of(
                file("/115/tmp", "AAAA.mkv", 1031L),
                file("/115/tmp", "BBBB.ass", 41L));

        TorrentPlanMatcher.Result result = TorrentPlanMatcher.match(plan, files);

        assertTrue(result.complete(), "唯一同长度候选可直接认领（115 改名场景）");
    }

    @Test
    void same_name_fallback_when_cloud_size_differs() {
        // 源站重新封装导致大小不一致：名字唯一命中时仍认领（否则永远等不到）
        List<Item> plan = List.of(plan("Show/Show - 03.mkv", "Show S01E03.mkv", 1031L, 3.0));
        List<OpenListFileInfo> files = List.of(file("/115/tmp", "Show - 03.mkv", 999L));

        TorrentPlanMatcher.Result result = TorrentPlanMatcher.match(plan, files);

        assertTrue(result.videosComplete(), "名字唯一命中应兜底认领");
        assertEquals(1, result.matches().size());
    }

    @Test
    void same_name_multiple_candidates_prefers_matching_parent_dir() {
        List<Item> plan = List.of(plan("Show S01/Show - 03.mkv", "Show S01E03.mkv", 1031L, 3.0));
        List<OpenListFileInfo> files = List.of(
                file("/115/tmp/other", "Show - 03.mkv", 1031L),
                file("/115/tmp/Show S01", "Show - 03.mkv", 1031L));

        TorrentPlanMatcher.Result result = TorrentPlanMatcher.match(plan, files);

        assertTrue(result.matches().size() == 1);
        assertEquals("/115/tmp/Show S01", result.matches().get(0).file().getPath(),
                "同名多候选应用父目录名消歧");
    }

    @Test
    void empty_plan_is_never_complete() {
        TorrentPlanMatcher.Result result = TorrentPlanMatcher.match(List.of(),
                List.of(file("/115/tmp", "x.mkv", 1L)));
        assertFalse(result.videosComplete());
        assertFalse(result.complete());
        assertEquals(1, result.unmatched().size());
    }

    @Test
    void one_file_claimed_once() {
        List<Item> plan = List.of(
                plan("Show/Show - 03.mkv", "Show S01E03.mkv", 1031L, 3.0),
                plan("Show/Show - 04.mkv", "Show S01E04.mkv", 1031L, 4.0));
        // 只有一个同长度文件：只能认领一条，另一条必须缺失
        List<OpenListFileInfo> files = List.of(file("/115/tmp", "only.mkv", 1031L));

        TorrentPlanMatcher.Result result = TorrentPlanMatcher.match(plan, files);

        assertEquals(1, result.matches().size(), "同一个文件不能被两条计划重复认领");
        assertEquals(1, result.missingVideos().size());
    }
}
