package ani.rss.service;

import ani.rss.service.SubtitleService.ImportResult;
import ani.rss.service.SubtitleService.LocalSubtitleFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地字幕批量导入。
 * <p>
 * 核心契约：
 * <ol>
 *   <li>按「季 + 集」匹配订阅目录下<b>已重命名</b>的视频，字幕落名 {@code 剧名 SxxExx[.{lang}].{ext}}；</li>
 *   <li>语言标识（sc/tc/chs/cht/jp/jpsc/jptc）保留并追加为后缀，无则不加后缀；</li>
 *   <li>集数不唯一时宁可失败，也不跨季误配；</li>
 *   <li>原始字节直写（不经过 UTF-8 重编码），避免 GBK 字幕被破坏；</li>
 *   <li>异常逐文件分类返回，不整体抛错。</li>
 * </ol>
 */
class SubtitleImportTest {

    @TempDir
    Path tempDir;

    private final SubtitleService service = new SubtitleService();

    private void video(String name) throws Exception {
        Files.write(new File(tempDir.toFile(), name).toPath(), new byte[64]);
    }

    private ImportResult run(LocalSubtitleFile... files) {
        return service.importLocalSubtitles(tempDir.toFile(), List.of(files));
    }

    private static LocalSubtitleFile sub(String name, String content) {
        return new LocalSubtitleFile(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> item(ImportResult result, int index) {
        return result.getItems().get(index);
    }

    @Test
    void import_matches_episode_without_season_and_uses_video_main_name() throws Exception {
        // 视频已重命名为标准名；字幕名只有集数（碧蓝之海 3 - 15）→ 回落到集数唯一匹配
        video("碧蓝之海 S03E15.mkv");

        ImportResult result = run(sub("GRAND BLUE 碧蓝之海 3 - 15.ass", "[Script Info]"));

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getSuccess());
        assertEquals(0, result.getFailed());
        assertEquals("碧蓝之海 S03E15.ass", item(result, 0).get("renamedName"));
        assertEquals("碧蓝之海 S03E15.mkv", item(result, 0).get("videoName"));
        assertTrue(new File(tempDir.toFile(), "碧蓝之海 S03E15.ass").exists());
    }

    @Test
    void import_keeps_language_suffix_from_subtitle_name() throws Exception {
        video("碧蓝之海 S03E15.mkv");

        ImportResult result = run(
                sub("碧蓝之海 S03E15.cht.ass", "cht"),
                sub("碧蓝之海 S03E15.jpsc.ass", "jpsc"),
                sub("碧蓝之海 S03E15.jptc.ass", "jptc"),
                sub("碧蓝之海 S03E15.sc.ass", "sc"),
                sub("碧蓝之海 S03E15.tc.ass", "tc"));

        assertEquals(5, result.getSuccess());
        assertEquals("碧蓝之海 S03E15.cht.ass", item(result, 0).get("renamedName"));
        assertEquals("碧蓝之海 S03E15.jpsc.ass", item(result, 1).get("renamedName"));
        assertEquals("碧蓝之海 S03E15.jptc.ass", item(result, 2).get("renamedName"));
        assertEquals("碧蓝之海 S03E15.sc.ass", item(result, 3).get("renamedName"));
        assertEquals("碧蓝之海 S03E15.tc.ass", item(result, 4).get("renamedName"));
        assertEquals("cht", item(result, 0).get("lang"));
    }

    @Test
    void import_srt_follows_same_rules_with_only_extension_changed() throws Exception {
        video("碧蓝之海 S03E15.mkv");

        ImportResult result = run(sub("碧蓝之海 S03E15.sc.srt", "1\n00:00:01,000 --> 00:00:02,000\nhi"));

        assertEquals("碧蓝之海 S03E15.sc.srt", item(result, 0).get("renamedName"));
    }

    @Test
    void import_matches_exact_season_when_episode_repeats_across_seasons() throws Exception {
        video("番剧A S01E05.mkv");
        video("番剧A S02E05.mkv");

        ImportResult result = run(sub("番剧A S02E05.ass", "x"));

        assertEquals(1, result.getSuccess());
        assertEquals("番剧A S02E05.mkv", item(result, 0).get("videoName"));
        assertEquals("番剧A S02E05.ass", item(result, 0).get("renamedName"));
    }

    @Test
    void import_normalizes_zero_padded_season_episode_between_video_and_subtitle() throws Exception {
        // 回归：索引键与查找键必须同源，否则 S02E05（视频）与 S2E5（字幕）永远匹配不上
        video("番剧A S02E05.mkv");

        ImportResult result = run(sub("番剧A S2E5.ass", "x"));

        assertEquals(1, result.getSuccess());
        assertEquals("番剧A S02E05.mkv", item(result, 0).get("videoName"));
        assertEquals("番剧A S02E05.ass", item(result, 0).get("renamedName"));
    }

    @Test
    void import_refuses_ambiguous_episode_without_season() throws Exception {
        video("番剧A S01E05.mkv");
        video("番剧A S02E05.mkv");

        // 只有集数 05，两季都有 → 无法唯一定位，宁可失败也不跨季误配
        ImportResult result = run(sub("番剧A 05.ass", "x"));

        assertEquals(0, result.getSuccess());
        assertEquals(1, result.getFailed());
        assertTrue(String.valueOf(item(result, 0).get("reason")).contains("集数不唯一"));
        assertFalse(new File(tempDir.toFile(), "番剧A S01E05.ass").exists());
    }

    @Test
    void import_reports_unparsable_episode() throws Exception {
        video("碧蓝之海 S03E15.mkv");

        ImportResult result = run(sub("碧蓝之海.ass", "x"));

        assertEquals(1, result.getFailed());
        assertTrue(String.valueOf(item(result, 0).get("reason")).contains("无法从字幕文件名解析集数"));
    }

    @Test
    void import_reports_missing_video_for_known_season_episode() throws Exception {
        video("碧蓝之海 S03E15.mkv");

        ImportResult result = run(sub("碧蓝之海 S03E99.ass", "x"));

        assertEquals(1, result.getFailed());
        assertTrue(String.valueOf(item(result, 0).get("reason")).contains("S3E99"));
    }

    @Test
    void import_rejects_unsupported_extension_and_empty_content() throws Exception {
        video("碧蓝之海 S03E15.mkv");

        ImportResult result = run(
                sub("碧蓝之海 S03E15.txt", "x"),
                new LocalSubtitleFile("碧蓝之海 S03E15.ass", new byte[0]));

        assertEquals(2, result.getFailed());
        assertTrue(String.valueOf(item(result, 0).get("reason")).contains("不支持的字幕格式"));
        assertTrue(String.valueOf(item(result, 1).get("reason")).contains("字幕内容为空"));
    }

    @Test
    void import_writes_bytes_verbatim_without_reencoding() throws Exception {
        video("番剧A S01E01.mkv");
        // 非法 UTF-8 序列：若被解码再按 UTF-8 重编码会损坏，必须原样落盘
        byte[] raw = {0x5B, 0x41, (byte) 0x80, (byte) 0xFF, 0x5D};

        ImportResult result = service.importLocalSubtitles(tempDir.toFile(),
                List.of(new LocalSubtitleFile("番剧A S01E01.ass", raw)));

        assertEquals(1, result.getSuccess());
        assertArrayEquals(raw, Files.readAllBytes(new File(tempDir.toFile(), "番剧A S01E01.ass").toPath()));
    }

    @Test
    void import_backs_up_existing_subtitle_before_overwrite() throws Exception {
        video("番剧A S01E01.mkv");
        Files.writeString(new File(tempDir.toFile(), "番剧A S01E01.ass").toPath(), "OLD");

        run(sub("番剧A S01E01.ass", "NEW"));

        assertEquals("NEW", Files.readString(new File(tempDir.toFile(), "番剧A S01E01.ass").toPath()));
        File backup = new File(new File(tempDir.toFile(), "sub_bak"), "番剧A S01E01.ass");
        assertTrue(backup.exists(), "覆盖前应备份到 sub_bak/，避免误覆盖用户已有字幕");
        assertEquals("OLD", Files.readString(backup.toPath()));
    }

    @Test
    void import_ignores_non_video_siblings() throws Exception {
        video("番剧A S01E01.mkv");
        Files.writeString(new File(tempDir.toFile(), "番剧A S01E01.jpg").toPath(), "not a video");

        ImportResult result = run(sub("番剧A S01E01.ass", "x"));

        assertEquals(1, result.getSuccess());
        assertEquals("番剧A S01E01.mkv", item(result, 0).get("videoName"));
    }

    @Test
    void import_cannot_escape_directory_via_uploaded_name() throws Exception {
        video("番剧A S01E01.mkv");

        ImportResult result = run(sub("../../evil.ass", "x"));

        assertEquals(1, result.getFailed());
        assertFalse(new File(tempDir.getParent().toFile(), "evil.ass").exists(),
                "上传文件名不得造成目录穿越");
    }

    @Test
    void import_reports_missing_directory_for_every_file() {
        File missing = new File(tempDir.toFile(), "not-exist");

        ImportResult result = service.importLocalSubtitles(missing,
                List.of(sub("番剧A S01E01.ass", "x"), sub("番剧A S01E02.ass", "y")));

        assertEquals(2, result.getTotal());
        assertEquals(0, result.getSuccess());
        assertEquals(2, result.getFailed());
        assertTrue(String.valueOf(item(result, 0).get("reason")).contains("订阅下载目录不存在"));
    }

    @Test
    void import_with_empty_input_returns_empty_result() {
        ImportResult result = service.importLocalSubtitles(tempDir.toFile(), List.of());
        assertEquals(0, result.getTotal());
        assertTrue(result.getItems().isEmpty());
    }
}
