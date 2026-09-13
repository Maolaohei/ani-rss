package ani.rss.service;

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
        File backup = new File(tempDir.toFile(), "Show S01E01.ass.bak");
        assertTrue(backup.exists(), "覆盖前应备份，避免误覆盖用户已有字幕");
        assertEquals("OLD", Files.readString(backup.toPath(), StandardCharsets.UTF_8));
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
    void auto_fetch_is_off_by_default() {
        // 未显式开启时不应触发任何在线抓取
        assertFalse(service.isAutoFetchEnabled());
    }
}
