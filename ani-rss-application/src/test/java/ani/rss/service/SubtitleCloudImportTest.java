package ani.rss.service;

import ani.rss.entity.OpenListFileInfo;
import ani.rss.service.SubtitleService.ImportResult;
import ani.rss.service.SubtitleService.LocalSubtitleFile;
import ani.rss.service.subtitle.AssrtSubtitleProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网盘（OpenList/AList）订阅的字幕手动导入：只验证纯匹配逻辑（fed 条目 + dryRun），
 * 不触网、不写盘。
 */
class SubtitleCloudImportTest {

    private static final String[] KAGURA_NAMES = {
            "[Kagura] Maoyuu Maou Yuusha - 06 [BDrip 1920x1080 x264 Hi10P FLAC].ass",
            "[Kagura] Maoyuu Maou Yuusha - 01 [BDrip 1920x1080 x264 Hi10P FLAC].ass",
            "[Kagura] Maoyuu Maou Yuusha - 04 [BDrip 1920x1080 x264 Hi10P FLAC][v2].ass",
            "[Kagura] Maoyuu Maou Yuusha - 03 [BDrip 1920x1080 x264 Hi10P FLAC][v2].ass",
    };

    /**
     * 用户实际文件名必须能解析出正确集数：技术参数（1920x1080 / x264 / Hi10P）不能被当成集数。
     */
    @Test
    void parse_user_subtitle_names_gives_right_episode() {
        int[] expected = {6, 1, 4, 3};
        for (int i = 0; i < KAGURA_NAMES.length; i++) {
            int[] se = AssrtSubtitleProvider.extractSeasonEpisode(KAGURA_NAMES[i]);
            assertEquals(expected[i], se[1], "解析出的集数不对: " + KAGURA_NAMES[i]);
        }
    }

    /**
     * 目录已列举到条目时，12 个字幕应逐集匹配到对应视频，并生成「视频主名 + 语言 + 后缀」的目标名。
     */
    @Test
    void cloud_dry_run_matches_episode_videos() {
        List<OpenListFileInfo> entries = videoEntries(1, 12);
        List<LocalSubtitleFile> files = new ArrayList<>();
        for (String name : KAGURA_NAMES) {
            files.add(sub(name));
        }

        ImportResult result = new SubtitleService()
                .importCloudSubtitles("/115/tv/Season 1", entries, files, true, null);

        assertEquals(4, result.getTotal());
        assertEquals(4, result.getSuccess());
        assertEquals(0, result.getFailed());
        for (Map<String, Object> item : result.getItems()) {
            assertEquals("已匹配", item.get("status"));
            assertNotNull(item.get("renamedName"));
        }
        // 第 6 集那条：目标是「视频主名 + 语言 + 后缀」
        Map<String, Object> ep6 = result.getItems().get(0);
        assertEquals("魔王勇者 S01E06.mkv", ep6.get("videoName"));
        assertEquals("魔王勇者 S01E06.ass", ep6.get("renamedName"));
    }

    /**
     * 目标同名文件已存在时，预览要提示「导入时会先备份到 sub_bak/」。
     */
    @Test
    void cloud_dry_run_hints_existing_target() {
        List<OpenListFileInfo> entries = videoEntries(1, 12);
        entries.add(file("/115/tv/Season 1", "魔王勇者 S01E06.ass", false));
        List<LocalSubtitleFile> files = List.of(sub(KAGURA_NAMES[0]));

        ImportResult result = new SubtitleService()
                .importCloudSubtitles("/115/tv/Season 1", entries, files, true, null);

        assertEquals(1, result.getSuccess());
        assertTrue(String.valueOf(result.getItems().get(0).get("reason")).contains("sub_bak"));
    }

    /**
     * 同一集存在多个候选视频时，不能猜，要报「集数不唯一」并提示补季数。
     */
    @Test
    void cloud_reports_ambiguous_episode() {
        List<OpenListFileInfo> entries = new ArrayList<>();
        entries.add(file("/115/tv/Season 1", "魔王勇者 S01E06.mkv", false));
        entries.add(file("/115/tv/S2", "魔王勇者 S02E06.mkv", false));

        ImportResult result = new SubtitleService()
                .importCloudSubtitles("/115/tv/Season 1", entries, List.of(sub(KAGURA_NAMES[0])), true, null);

        assertEquals(0, result.getSuccess());
        assertEquals(1, result.getFailed());
        assertTrue(String.valueOf(result.getItems().get(0).get("reason")).contains("集数不唯一"));
    }

    /**
     * 目录里没有该集视频时报「未找到第 N 集视频」，而不是原来那句误导的「目录不存在」。
     */
    @Test
    void cloud_reports_missing_episode() {
        List<OpenListFileInfo> entries = videoEntries(1, 3);

        ImportResult result = new SubtitleService()
                .importCloudSubtitles("/115/tv/Season 1", entries, List.of(sub(KAGURA_NAMES[0])), true, null);

        assertEquals(1, result.getFailed());
        assertTrue(String.valueOf(result.getItems().get(0).get("reason")).contains("未找到第 6 集视频"));
    }

    /**
     * 带季标记的视频，字幕只有集数时要能按「集数唯一」兜底命中。
     */
    @Test
    void cloud_matches_by_episode_when_season_absent() {
        List<OpenListFileInfo> entries = videoEntries(1, 12);

        ImportResult result = new SubtitleService()
                .importCloudSubtitles("/115/tv/Season 1", entries,
                        List.of(sub("魔王勇者 - 第6话 [Kagura].ass")), true, null);

        assertEquals(1, result.getSuccess());
        assertEquals("魔王勇者 S01E06.ass", result.getItems().get(0).get("renamedName"));
    }

    /**
     * 路径规范化：用户配置里带空格/反斜杠/重复斜杠/末尾斜杠都要能对齐网盘路径。
     */
    @Test
    void normalize_cloud_dir() {
        assertEquals("/115/动漫/转存/追番/魔王勇者 (2013) [tmdbid=46302]/Season 1",
                SubtitleService.normalizeCloudDir("  /115/动漫/转存/追番/魔王勇者 (2013) [tmdbid=46302]/Season 1/  "));
        assertEquals("/115/a/b", SubtitleService.normalizeCloudDir("\\115\\\\a\\b\\"));
        assertEquals("/", SubtitleService.normalizeCloudDir("/"));
        assertEquals("", SubtitleService.normalizeCloudDir(null));
        assertEquals("", SubtitleService.normalizeCloudDir("   "));
    }

    // ---------------------------------------------------------------- helpers

    private static LocalSubtitleFile sub(String name) {
        return new LocalSubtitleFile(name, "[Script Info]\nTitle: t\n".getBytes(StandardCharsets.UTF_8));
    }

    private static OpenListFileInfo file(String dir, String name, boolean isDir) {
        return new OpenListFileInfo()
                .setName(name)
                .setIsDir(isDir)
                .setPath(dir)
                .setSize(1024L);
    }

    private static List<OpenListFileInfo> videoEntries(int fromEp, int toEp) {
        List<OpenListFileInfo> entries = new ArrayList<>();
        for (int ep = fromEp; ep <= toEp; ep++) {
            entries.add(file("/115/tv/Season 1",
                    String.format("魔王勇者 S01E%02d.mkv", ep), false));
        }
        return entries;
    }
}
