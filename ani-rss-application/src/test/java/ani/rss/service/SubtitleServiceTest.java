package ani.rss.service;

import ani.rss.service.subtitle.SubtitleCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字幕就地附加。
 * <p>
 * 字幕命名必须与视频主文件名严格对齐（播放侧按"主文件名前缀"匹配），
 * 且写盘必须是原子的——写一半留下损坏字幕比没有字幕更糟。
 */
class SubtitleServiceTest {

    @TempDir
    Path tempDir;

    private final SubtitleService service = new SubtitleService();

    private File video(String name) throws Exception {
        File file = new File(tempDir.toFile(), name);
        Files.write(file.toPath(), new byte[64]);
        return file;
    }

    @Test
    void attach_writes_sibling_subtitle_matching_video_main_name() throws Exception {
        File video = video("番剧A S01E03.mkv");

        File subtitle = service.attachSubtitle(video, "[Script Info]\nTitle: test", "ass", "chs");

        assertTrue(subtitle.exists());
        assertEquals("番剧A S01E03.chs.ass", subtitle.getName());
        assertEquals(video.getParentFile(), subtitle.getParentFile(), "字幕必须与视频同目录");
        String content = Files.readString(subtitle.toPath(), StandardCharsets.UTF_8);
        assertTrue(content.contains("[Script Info]"));
    }

    @Test
    void attach_without_language_tag_uses_plain_name() throws Exception {
        File video = video("Show S01E01.mkv");
        File subtitle = service.attachSubtitle(video, "1\n00:00:01,000 --> 00:00:02,000\nhi", "srt", null);
        assertEquals("Show S01E01.srt", subtitle.getName());
    }

    @Test
    void attach_normalizes_extension_input() throws Exception {
        File video = video("Show S01E02.mkv");
        File subtitle = service.attachSubtitle(video, "content", ".ASS", null);
        assertEquals("Show S01E02.ass", subtitle.getName());
    }

    @Test
    void attach_rejects_unsupported_extension() throws Exception {
        File video = video("Show S01E01.mkv");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.attachSubtitle(video, "content", "exe", null));
        assertTrue(e.getMessage().contains("不支持的字幕格式"));
    }

    @Test
    void attach_rejects_blank_content() throws Exception {
        File video = video("Show S01E01.mkv");
        assertThrows(IllegalArgumentException.class,
                () -> service.attachSubtitle(video, "   ", "ass", null));
    }

    @Test
    void attach_rejects_missing_video() {
        File missing = new File(tempDir.toFile(), "nope.mkv");
        assertThrows(IllegalArgumentException.class,
                () -> service.attachSubtitle(missing, "content", "ass", null));
    }

    @Test
    void attach_backs_up_existing_subtitle_before_overwrite() throws Exception {
        File video = video("Show S01E01.mkv");
        File first = service.attachSubtitle(video, "OLD", "ass", null);
        assertTrue(first.exists());

        File second = service.attachSubtitle(video, "NEW", "ass", null);

        assertEquals("NEW", Files.readString(second.toPath(), StandardCharsets.UTF_8));
        File backup = new File(new File(tempDir.toFile(), "sub_bak"), "Show S01E01.ass");
        assertTrue(backup.exists(), "覆盖前应备份到 sub_bak/，避免误覆盖用户已有字幕");
        assertEquals("OLD", Files.readString(backup.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void attach_creates_backup_dir_when_absent() throws Exception {
        File video = video("Show S01E01.mkv");
        service.attachSubtitle(video, "OLD", "ass", null);
        File backupDir = new File(tempDir.toFile(), "sub_bak");
        assertFalse(backupDir.exists(), "首次写入不应凭空创建 sub_bak/");

        service.attachSubtitle(video, "NEW", "ass", null);

        assertTrue(backupDir.isDirectory(), "需要备份时应自动新建 sub_bak/");
    }

    @Test
    void attach_leaves_no_temp_file_behind() throws Exception {
        File video = video("Show S01E01.mkv");
        service.attachSubtitle(video, "content", "ass", null);
        File[] leftovers = tempDir.toFile()
                .listFiles((dir, name) -> name.endsWith(".temp"));
        assertNotNull(leftovers);
        assertEquals(0, leftovers.length, "原子写完成后不应残留 .temp 文件");
    }

    @Test
    void resolveVideo_only_accepts_real_video_files() throws Exception {
        File video = video("Show S01E01.mkv");
        assertNotNull(service.resolveVideo(video.getAbsolutePath()));
        assertNull(service.resolveVideo(null));
        assertNull(service.resolveVideo(new File(tempDir.toFile(), "missing.mkv").getAbsolutePath()));

        File notVideo = new File(tempDir.toFile(), "notes.txt");
        Files.write(notVideo.toPath(), new byte[8]);
        assertNull(service.resolveVideo(notVideo.getAbsolutePath()));
    }

    @Test
    void manual_fetch_is_off_by_default() {
        // 未显式开启时不应触发任何在线抓取；字幕一律由用户手动确认后写入
        assertFalse(service.isManualFetchEnabled());
    }

    @Test
    void attach_keeps_multi_level_language_tag() throws Exception {
        File video = video("番剧A S01E03.mkv");
        File subtitle = service.attachSubtitle(video, "content", "ass", "chs&eng.simplified");
        assertEquals("番剧A S01E03.chs&eng.simplified.ass", subtitle.getName());
    }

    @Test
    void attach_sanitizes_language_tag_path_payload() throws Exception {
        File video = video("Show S01E01.mkv");
        File subtitle = service.attachSubtitle(video, "content", "ass", "../../evil");
        assertEquals(video.getParentFile(), subtitle.getParentFile(), "语言标签不应造成目录穿越");
        assertEquals("Show S01E01.evil.ass", subtitle.getName());
    }

    @Test
    void resolveLangTag_prefers_source_file_language_token() {
        // 规则 2: 字幕源文件名带语言标识时, 追加对应后缀(保留原始标识, 统一小写)
        SubtitleCandidate c = new SubtitleCandidate();
        c.setLang("chs");
        assertEquals("cht", service.resolveLangTag("碧蓝之海 3 - 15.cht.ass", c));
        assertEquals("jpsc", service.resolveLangTag("碧蓝之海 3 - 15.jpsc.ass", c));
        assertEquals("jptc", service.resolveLangTag("碧蓝之海 3 - 15.jptc.ass", c));
        assertEquals("sc", service.resolveLangTag("碧蓝之海 3 - 15.sc.ass", c));
        assertEquals("tc", service.resolveLangTag("碧蓝之海 3 - 15.tc.ass", c));
    }

    @Test
    void resolveLangTag_falls_back_to_api_lang_then_blank() {
        SubtitleCandidate c = new SubtitleCandidate();
        c.setLang("chs");
        // 规则 3: 源文件名无语言标识 → 回落接口 lang 字段; 字段也为空 → 无后缀
        assertEquals("chs", service.resolveLangTag("Grand Blue Dreaming - 15.ass", c));
        c.setLang("");
        assertEquals("", service.resolveLangTag("Grand Blue Dreaming - 15.ass", c));
    }
}
