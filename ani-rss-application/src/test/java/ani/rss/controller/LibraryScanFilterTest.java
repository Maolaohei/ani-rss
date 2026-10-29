package ani.rss.controller;

import ani.rss.entity.OpenListFileInfo;
import ani.rss.util.other.TempDirCleanupStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体库列举的显示层兜底：正在欠着清理的临时目录整棵屏蔽。
 * <p>
 * 症状：收尾时删临时目录撞上限流，盘上同时存在
 * {@code XXX/S1/S1E01.MKV}（已归位）与 {@code XXX/S1/S1E01/S1E01.MKV}（要删的残留），
 * 按文件名扫就会数成两集。删除由巡检补做，这里只保证"还没删掉的那几轮"不会立刻表现为重复集。
 */
class LibraryScanFilterTest {

    private static final String SAVE_PATH = "/115/动漫/转存/追番/Show/Season 1";
    private static final String TEMP_DIR = "Show S01E01";
    private static final String TEMP_PATH = SAVE_PATH + "/" + TEMP_DIR;

    private String previousConfigDir;

    @BeforeEach
    void setUp() {
        previousConfigDir = System.getProperty("CONFIG");
        File dir = new File(System.getProperty("java.io.tmpdir"), "ani-rss-scan-filter-" + UUID.randomUUID());
        assertTrue(dir.mkdirs());
        System.setProperty("CONFIG", dir.getAbsolutePath());
        TempDirCleanupStore.invalidate();
    }

    @AfterEach
    void tearDown() {
        TempDirCleanupStore.invalidate();
        if (previousConfigDir == null) {
            System.clearProperty("CONFIG");
        } else {
            System.setProperty("CONFIG", previousConfigDir);
        }
    }

    private void pending(String savePath, String tempDirName) {
        TempDirCleanupStore.lease(savePath, tempDirName, "Show S01E01.mkv", "HASH", "ani-1", "Show");
        TempDirCleanupStore.markDeletePending(savePath, tempDirName, List.of("Show S01E01.mkv"));
        TempDirCleanupStore.invalidate();
    }

    /** 递归列举把"所在目录"放在 path 上 */
    private OpenListFileInfo video(String dir, String name) {
        return new OpenListFileInfo().setName(name).setIsDir(false).setPath(dir);
    }

    @Test
    void files_inside_pending_temp_dir_are_hidden() {
        pending(SAVE_PATH, TEMP_DIR);

        List<OpenListFileInfo> kept = LibraryController.excludePendingTempDirFiles(List.of(
                video(SAVE_PATH, "Show S01E01.mkv"),
                video(TEMP_PATH, "Show S01E01.mkv")));

        assertEquals(1, kept.size(), "残留目录里那份必须被屏蔽，否则同一集被数两遍");
        assertEquals(SAVE_PATH, kept.get(0).getPath(), "保留的是已归位到顶层的那一份");
    }

    @Test
    void nested_subtitles_inside_temp_dir_are_hidden_too() {
        pending(SAVE_PATH, TEMP_DIR);

        List<OpenListFileInfo> kept = LibraryController.excludePendingTempDirFiles(List.of(
                video(SAVE_PATH, "Show S01E01.mkv"),
                video(TEMP_PATH + "/Subs", "Show S01E01.ass")));

        assertEquals(1, kept.size(), "字幕也要一起屏蔽：它的同名视频被屏蔽了，留着就成孤儿");
    }

    @Test
    void sibling_episodes_are_untouched() {
        pending(SAVE_PATH, TEMP_DIR);

        List<OpenListFileInfo> kept = LibraryController.excludePendingTempDirFiles(List.of(
                video(SAVE_PATH, "Show S01E01.mkv"),
                video(SAVE_PATH, "Show S01E02.mkv"),
                video(SAVE_PATH + "/Show S01E02", "Show S01E02.mkv")));

        assertEquals(3, kept.size(), "只屏蔽债务内的那一棵，别的集不许误伤");
    }

    @Test
    void path_prefix_must_match_a_whole_segment() {
        pending(SAVE_PATH, TEMP_DIR);

        // 「Show S01E010」不是「Show S01E01」的子目录，纯前缀比较会误杀
        List<OpenListFileInfo> kept = LibraryController.excludePendingTempDirFiles(List.of(
                video(SAVE_PATH + "/Show S01E010", "Show S01E10.mkv")));

        assertEquals(1, kept.size(), "按目录段比较，不能按字符串前缀");
    }

    @Test
    void no_debt_means_no_filtering() {
        List<OpenListFileInfo> files = List.of(
                video(SAVE_PATH, "Show S01E01.mkv"),
                video(TEMP_PATH, "Show S01E01.mkv"));

        List<OpenListFileInfo> kept = LibraryController.excludePendingTempDirFiles(files);

        assertEquals(2, kept.size(), "没有债务时不该改变原有行为");
    }

    @Test
    void settled_debt_stops_shielding() {
        pending(SAVE_PATH, TEMP_DIR);
        TempDirCleanupStore.confirm(TempDirCleanupStore.idOf(SAVE_PATH, TEMP_DIR));
        TempDirCleanupStore.invalidate();

        assertTrue(TempDirCleanupStore.pendingTempPaths().isEmpty());
        List<OpenListFileInfo> kept = LibraryController.excludePendingTempDirFiles(List.of(
                video(TEMP_PATH, "Show S01E01.mkv")));
        assertEquals(1, kept.size(), "销账后不再屏蔽：目录已经不在了，屏蔽会掩盖真正的内容");
    }
}
