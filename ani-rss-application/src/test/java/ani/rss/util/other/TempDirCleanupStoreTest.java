package ani.rss.util.other;

import ani.rss.entity.TempDirCleanupRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 临时目录清理债务的落盘语义。
 * <p>
 * 这份账存在的原因：离线收尾的最后一步「删除临时目录」会撞上网盘限流/冷却，
 * 旧实现只打一条 warn 就继续判成功并销掉所有痕迹，于是 {@code S1/S1E01/} 永远留在盘上，
 * 媒体管理器把它和已归位的 {@code S1/S1E01.MKV} 数成两集。
 * 所以这里钉住三条契约：
 * <ol>
 *   <li>写前落盘：临时目录一创建就有记录（进程随时被杀也留得下线索）；</li>
 *   <li>阶段只前进：重复提交同一集不得把 DELETE_PENDING 退回 LEASED；</li>
 *   <li>只有 DELETE_PENDING 会被巡检取走 —— LEASED 时媒体还没归位，删了就毁产物。</li>
 * </ol>
 */
class TempDirCleanupStoreTest {

    private String previousConfigDir;
    private File configDir;

    private static final String SAVE_PATH = "/115/动漫/转存/追番/Show/Season 1";
    private static final String TEMP_DIR = "Show S01E01";

    @BeforeEach
    void setUp() {
        previousConfigDir = System.getProperty("CONFIG");
        configDir = new File(System.getProperty("java.io.tmpdir"), "ani-rss-cleanup-store-" + UUID.randomUUID());
        assertTrue(configDir.mkdirs());
        System.setProperty("CONFIG", configDir.getAbsolutePath());
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

    private TempDirCleanupRecord lease() {
        TempDirCleanupStore.lease(SAVE_PATH, TEMP_DIR, "Show S01E01.mkv", "HASH1", "ani-1", "Show");
        TempDirCleanupStore.invalidate();
        return TempDirCleanupStore.find(SAVE_PATH, TEMP_DIR);
    }

    @Test
    void lease_writes_record_before_any_media_is_moved() {
        TempDirCleanupRecord record = lease();

        assertNotNull(record, "临时目录一创建就必须有落盘记录");
        assertEquals(TempDirCleanupRecord.Phase.LEASED, record.getPhase());
        assertEquals(TEMP_DIR, record.getTempDirName());
        assertEquals(SAVE_PATH, record.getSavePath());
        assertEquals(SAVE_PATH + "/" + TEMP_DIR, record.getTempPath());
        assertEquals("HASH1", record.getInfoHash(), "hash 要留下，事后才能对上离线任务");
    }

    @Test
    void leased_records_are_never_picked_up_by_sweeper() {
        lease();

        List<TempDirCleanupRecord> due = TempDirCleanupStore.due(System.currentTimeMillis(), 10);

        assertTrue(due.isEmpty(), "媒体还没归位就删临时目录，会把唯一一份产物删掉");
    }

    @Test
    void mark_delete_pending_exposes_debt_to_sweeper() {
        lease();
        TempDirCleanupStore.markDeletePending(SAVE_PATH, TEMP_DIR, List.of("Show S01E01.mkv"));
        TempDirCleanupStore.invalidate();

        List<TempDirCleanupRecord> due = TempDirCleanupStore.due(System.currentTimeMillis(), 10);

        assertEquals(1, due.size());
        assertEquals(List.of("Show S01E01.mkv"), due.get(0).getFinalizedNames(),
                "已归位文件名是删除前的安全门，必须一起落盘");
    }

    @Test
    void repeated_lease_does_not_roll_back_delete_pending() {
        lease();
        TempDirCleanupStore.markDeletePending(SAVE_PATH, TEMP_DIR, List.of("Show S01E01.mkv"));
        TempDirCleanupStore.invalidate();

        // 洗版/重试会再提交同一集，此时不能再 lease 一遍
        lease();

        TempDirCleanupRecord record = TempDirCleanupStore.find(SAVE_PATH, TEMP_DIR);
        assertEquals(TempDirCleanupRecord.Phase.DELETE_PENDING, record.getPhase(),
                "阶段只允许前进；回退会让已经欠着的删除白等一轮");
    }

    @Test
    void confirm_removes_debt_from_disk_and_from_scan() {
        lease();
        TempDirCleanupStore.markDeletePending(SAVE_PATH, TEMP_DIR, List.of("Show S01E01.mkv"));
        TempDirCleanupStore.invalidate();
        String id = TempDirCleanupStore.idOf(SAVE_PATH, TEMP_DIR);

        TempDirCleanupStore.confirm(id);
        TempDirCleanupStore.invalidate();

        assertNull(TempDirCleanupStore.findById(id), "回读确认删除后必须销账，否则巡检会永远重复删");
        assertTrue(TempDirCleanupStore.due(System.currentTimeMillis(), 10).isEmpty());
        assertTrue(TempDirCleanupStore.pendingTempPaths().isEmpty(),
                "销账后媒体库不该再屏蔽这个目录");
    }

    @Test
    void defer_pushes_next_attempt_into_the_future() {
        lease();
        TempDirCleanupStore.markDeletePending(SAVE_PATH, TEMP_DIR, List.of("Show S01E01.mkv"));
        TempDirCleanupStore.invalidate();
        String id = TempDirCleanupStore.idOf(SAVE_PATH, TEMP_DIR);
        long now = System.currentTimeMillis();

        TempDirCleanupStore.defer(id, "网盘接口冷却中");
        TempDirCleanupStore.invalidate();

        TempDirCleanupRecord record = TempDirCleanupStore.findById(id);
        assertEquals(1, record.getAttempts());
        assertTrue(record.getNextAttemptAt() > now, "退避必须推到未来，否则会立刻再撞一次限流");
        assertTrue(TempDirCleanupStore.due(now, 10).isEmpty(), "退避期内不能被取走");
        assertTrue(TempDirCleanupStore.due(record.getNextAttemptAt(), 10).size() == 1,
                "到点后必须重新可被取走，否则债务永远还不掉");
    }

    @Test
    void needs_human_stops_automatic_retry_but_still_shields_library_scan() {
        lease();
        TempDirCleanupStore.markDeletePending(SAVE_PATH, TEMP_DIR, List.of("Show S01E01.mkv"));
        TempDirCleanupStore.invalidate();
        String id = TempDirCleanupStore.idOf(SAVE_PATH, TEMP_DIR);

        TempDirCleanupStore.needsHuman(id, "顶层同名项是文件");
        TempDirCleanupStore.invalidate();

        assertTrue(TempDirCleanupStore.due(System.currentTimeMillis(), 10).isEmpty(),
                "转人工后不许自动删：现场与预期不符，自动动作风险更高");
        assertTrue(TempDirCleanupStore.pendingTempPaths().contains(SAVE_PATH + "/" + TEMP_DIR),
                "但目录还在盘上，媒体库仍要屏蔽它，否则照样重复计集");
        assertEquals(1, TempDirCleanupStore.needsHumanCount());
    }

    @Test
    void pending_paths_are_absolute_temp_dirs_not_save_paths() {
        lease();
        TempDirCleanupStore.markDeletePending(SAVE_PATH, TEMP_DIR, List.of());
        TempDirCleanupStore.invalidate();

        var paths = TempDirCleanupStore.pendingTempPaths();

        assertTrue(paths.contains(SAVE_PATH + "/" + TEMP_DIR));
        assertFalse(paths.contains(SAVE_PATH), "屏蔽的是临时目录，不能连整个最终目录一起挡掉");
        assertEquals(1, TempDirCleanupStore.pendingCount());
    }

    @Test
    void records_are_persisted_as_files_so_a_restart_can_resume() {
        lease();
        TempDirCleanupStore.markDeletePending(SAVE_PATH, TEMP_DIR, List.of("Show S01E01.mkv"));
        TempDirCleanupStore.invalidate();

        File[] files = TempDirCleanupStore.dir().listFiles((d, n) -> n.endsWith(TempDirCleanupStore.SUFFIX));

        assertNotNull(files);
        assertEquals(1, files.length, "债务必须落成文件；只放内存的话重启就把未完成的删除忘了");
    }
}
