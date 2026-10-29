package ani.rss.download.openlist.residual;

import ani.rss.download.openlist.runtime.OpenListDownloadPathResolver;
import ani.rss.entity.OpenListFileInfo;
import ani.rss.entity.OpenListTaskInfo;
import ani.rss.entity.TempDirCleanupRecord;
import ani.rss.util.other.TempDirCleanupStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 临时目录清理巡检。
 * <p>
 * 场景来自线上：收尾时删临时目录撞上网盘限流（或 115 的 990009 异步删除没落地），
 * 旧实现打一条 warn 就判成功，于是 {@code S1/S1E01/} 留在盘上，
 * 媒体管理器把 {@code S1/S1E01.MKV} 与 {@code S1/S1E01/S1E01.MKV} 数成两集。
 * <p>
 * 契约：
 * <ol>
 *   <li>冷却期<b>一条请求都不发</b>（清理风暴会把限流喂得更久）；</li>
 *   <li>单飞 + 每轮限额，不制造请求尖峰；</li>
 *   <li>安全门：查不清不删、同名项是文件不删、成片不在顶层不删；</li>
 *   <li>删除未确认时债务留着并按退避推后，而不是销账。</li>
 * </ol>
 */
class TempDirCleanupSweeperTest {

    private static final String SAVE_PATH = "/追番/Show/Season 1";
    private static final String TEMP_DIR = "Show S01E01";
    private static final String FINAL_NAME = "Show S01E01.mkv";

    private String previousConfigDir;
    private FakePort port;
    private TempDirCleanupSweeper sweeper;

    @BeforeEach
    void setUp() {
        previousConfigDir = System.getProperty("CONFIG");
        File dir = new File(System.getProperty("java.io.tmpdir"), "ani-rss-sweeper-test-" + UUID.randomUUID());
        assertTrue(dir.mkdirs());
        System.setProperty("CONFIG", dir.getAbsolutePath());
        TempDirCleanupStore.invalidate();
        port = new FakePort();
        sweeper = new TempDirCleanupSweeper(port);
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

    /** 造一条"媒体已归位、等删临时目录"的债务 */
    private String pendingDebt(String tempDirName, List<String> finalizedNames) {
        TempDirCleanupStore.lease(SAVE_PATH, tempDirName, FINAL_NAME, "HASH", "ani-1", "Show");
        TempDirCleanupStore.markDeletePending(SAVE_PATH, tempDirName, finalizedNames);
        TempDirCleanupStore.invalidate();
        return TempDirCleanupStore.idOf(SAVE_PATH, tempDirName);
    }

    private OpenListFileInfo dir(String name) {
        return new OpenListFileInfo().setName(name).setIsDir(true).setPath(SAVE_PATH);
    }

    private OpenListFileInfo file(String name) {
        return new OpenListFileInfo().setName(name).setIsDir(false).setPath(SAVE_PATH);
    }

    @Test
    void cooldown_means_zero_requests() {
        pendingDebt(TEMP_DIR, List.of(FINAL_NAME));
        port.top = List.of(dir(TEMP_DIR), file(FINAL_NAME));
        port.cooldownMs = 42_000L;

        TempDirCleanupSweeper.SweepResult result = sweeper.sweep();

        assertFalse(result.ran());
        assertEquals(0, port.listForCleanupCalls, "冷却期列举一次都不许发：清理动作自己就是限流的来源之一");
        assertEquals(0, port.deleteCalls, "更不该尝试删除");
        assertTrue(result.message().contains("冷却"), result.message());
    }

    @Test
    void not_logged_in_means_zero_requests() {
        pendingDebt(TEMP_DIR, List.of(FINAL_NAME));
        port.loggedIn = false;

        TempDirCleanupSweeper.SweepResult result = sweeper.sweep();

        assertFalse(result.ran());
        assertEquals(0, port.listForCleanupCalls);
        assertEquals(0, port.deleteCalls);
    }

    @Test
    void confirmed_delete_settles_the_debt() {
        String id = pendingDebt(TEMP_DIR, List.of(FINAL_NAME));
        port.top = List.of(dir(TEMP_DIR), file(FINAL_NAME));
        port.deleteResult = TempDirDeleteResult.DELETED;

        TempDirCleanupSweeper.SweepResult result = sweeper.sweep();

        assertTrue(result.ran());
        assertEquals(1, result.deleted());
        assertEquals(1, port.deleteCalls);
        TempDirCleanupStore.invalidate();
        assertEquals(null, TempDirCleanupStore.findById(id), "回读确认后必须销账");
    }

    @Test
    void unconfirmed_delete_keeps_debt_with_backoff() {
        String id = pendingDebt(TEMP_DIR, List.of(FINAL_NAME));
        port.top = List.of(dir(TEMP_DIR), file(FINAL_NAME));
        port.deleteResult = TempDirDeleteResult.FAILED_RETRYABLE;
        long now = System.currentTimeMillis();

        TempDirCleanupSweeper.SweepResult result = sweeper.sweep();

        assertEquals(0, result.deleted(), "限流导致的「没删成」绝不能计成已清理");
        assertEquals(1, result.deferred());
        TempDirCleanupStore.invalidate();
        TempDirCleanupRecord record = TempDirCleanupStore.findById(id);
        assertTrue(record != null, "债务必须留着，否则残留就再无线索");
        assertEquals(1, record.getAttempts());
        assertTrue(record.getNextAttemptAt() > now);
        assertTrue(TempDirCleanupStore.due(now, 10).isEmpty(), "退避期内不再打扰网盘");
    }

    @Test
    void listing_failure_is_not_treated_as_absent() {
        String id = pendingDebt(TEMP_DIR, List.of(FINAL_NAME));
        port.listForCleanupThrows = new IllegalStateException("网盘接口冷却中（剩余 12s）");

        TempDirCleanupSweeper.SweepResult result = sweeper.sweep();

        assertEquals(0, port.deleteCalls, "查不清 ≠ 没有了：列不出来时一次删除都不许发");
        assertEquals(1, result.deferred());
        TempDirCleanupStore.invalidate();
        assertTrue(TempDirCleanupStore.findById(id) != null, "债务不能因为「查不到」被销掉");
    }

    @Test
    void same_name_file_blocks_automatic_delete() {
        pendingDebt(TEMP_DIR, List.of(FINAL_NAME));
        // 顶层同名项是文件而不是目录：整树删除会误伤别的东西
        port.top = List.of(file(TEMP_DIR), file(FINAL_NAME));

        TempDirCleanupSweeper.SweepResult result = sweeper.sweep();

        assertEquals(0, port.deleteCalls);
        assertEquals(1, result.needsHuman());
        assertEquals(1, TempDirCleanupStore.needsHumanCount());
    }

    @Test
    void missing_finalized_media_blocks_automatic_delete() {
        pendingDebt(TEMP_DIR, List.of(FINAL_NAME));
        // 顶层没有那件已归位的成片：可能是唯一一份产物，删了就没了
        port.top = List.of(dir(TEMP_DIR));

        TempDirCleanupSweeper.SweepResult result = sweeper.sweep();

        assertEquals(0, port.deleteCalls);
        assertEquals(1, result.needsHuman(), "成片不在顶层时只转人工，绝不自动删");
    }

    @Test
    void already_absent_is_confirmed_not_failed() {
        String id = pendingDebt(TEMP_DIR, List.of(FINAL_NAME));
        port.top = List.of(file(FINAL_NAME));

        TempDirCleanupSweeper.SweepResult result = sweeper.sweep();

        assertEquals(1, result.deleted());
        assertEquals(0, port.deleteCalls, "已经不在盘上就不必再发删除请求");
        TempDirCleanupStore.invalidate();
        assertEquals(null, TempDirCleanupStore.findById(id));
    }

    @Test
    void active_temp_dir_is_protected() {
        pendingDebt(TEMP_DIR, List.of(FINAL_NAME));
        port.active = Set.of(TEMP_DIR);
        port.top = List.of(dir(TEMP_DIR), file(FINAL_NAME));

        TempDirCleanupSweeper.SweepResult result = sweeper.sweep();

        assertEquals(0, port.deleteCalls, "并行离线占用的临时目录一个都不能动");
        assertEquals(1, result.deferred());
    }

    @Test
    void batch_is_capped_per_sweep() {
        for (int i = 1; i <= 5; i++) {
            pendingDebt("Show S01E0" + i, List.of("Show S01E0" + i + ".mkv"));
        }
        List<OpenListFileInfo> top = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            top.add(dir("Show S01E0" + i));
            top.add(file("Show S01E0" + i + ".mkv"));
        }
        port.top = top;
        port.deleteResult = TempDirDeleteResult.DELETED;

        TempDirCleanupSweeper.SweepResult result = sweeper.sweep();

        assertEquals(3, result.deleted(), "每轮最多 3 条：清理风暴会自己把限流喂大");
        assertEquals(2, TempDirCleanupStore.pendingCount(), "剩下的留给下一轮，不是丢掉");
    }

    @Test
    void sweep_is_single_flight() {
        pendingDebt(TEMP_DIR, List.of(FINAL_NAME));
        port.top = List.of(dir(TEMP_DIR), file(FINAL_NAME));
        port.deleteResult = TempDirDeleteResult.DELETED;
        // 在删除回调里重入一次：并发触发（登录后/每轮/按钮）只允许一份在跑
        port.onDelete = () -> {
            TempDirCleanupSweeper.SweepResult nested = sweeper.sweep();
            assertFalse(nested.ran());
            assertEquals(0, port.deleteCalls - 1, "重入的那次不许再发请求");
        };

        sweeper.sweep();

        assertEquals(1, port.deleteCalls);
    }

    /**
     * 只实现巡检会用到的能力，其余按需返回空值。
     */
    private static final class FakePort implements OpenListResidualPort {
        List<OpenListFileInfo> top = List.of();
        long cooldownMs = 0L;
        boolean loggedIn = true;
        TempDirDeleteResult deleteResult = TempDirDeleteResult.DELETED;
        RuntimeException listForCleanupThrows;
        Runnable onDelete;
        Set<String> active = Set.of();
        List<String> waiting = List.of();
        int listForCleanupCalls = 0;
        int deleteCalls = 0;

        @Override
        public List<OpenListFileInfo> list(String path, boolean refresh) {
            return top;
        }

        @Override
        public List<OpenListFileInfo> listForCleanup(String path) {
            listForCleanupCalls++;
            if (listForCleanupThrows != null) {
                throw listForCleanupThrows;
            }
            return top;
        }

        @Override
        public List<OpenListTaskInfo> undoneTasks() {
            return List.of();
        }

        @Override
        public List<OpenListTaskInfo> doneTasks() {
            return List.of();
        }

        @Override
        public void cancelTask(String tid) {
        }

        @Override
        public void deleteTask(String tid) {
        }

        @Override
        public TempDirDeleteResult deleteTempDir(String savePath, String tempDirName) {
            deleteCalls++;
            if (onDelete != null) {
                onDelete.run();
            }
            return deleteResult;
        }

        @Override
        public long listingCooldownRemainingMs() {
            return cooldownMs;
        }

        @Override
        public boolean loggedIn() {
            return loggedIn;
        }

        @Override
        public Set<String> currentInfoHashes() {
            return new HashSet<>();
        }

        @Override
        public Set<String> activeTempDirNames() {
            return active;
        }

        @Override
        public List<String> offlineWaitNames() {
            return waiting;
        }

        @Override
        public OpenListDownloadPathResolver pathResolver() {
            return OpenListDownloadPathResolver.NONE;
        }

        @Override
        public void recoverOfflinePlans() {
        }
    }
}
