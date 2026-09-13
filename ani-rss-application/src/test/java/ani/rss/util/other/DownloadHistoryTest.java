package ani.rss.util.other;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 下载历史：读写、筛选、统计与持久化往返。
 * <p>
 * 这是"成功侧不留痕"的补位功能，一旦查询/统计口径错了，
 * 首页趋势、周报、AI 诊断会一起给出错误结论，所以口径必须固化。
 */
class DownloadHistoryTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.toString());
        DownloadHistory.resetForTest();
    }

    @AfterEach
    void tearDown() {
        DownloadHistory.resetForTest();
        System.clearProperty("CONFIG");
    }

    @Test
    void record_then_query_returns_record() {
        DownloadHistory.record("ani-1", "番剧A", "番剧A S01E01.mkv", "hash1", 1.0,
                1024L, "qBittorrent", "字幕组A", DownloadHistory.Result.SUCCESS, "下载完成");

        List<DownloadHistory.DownloadRecord> all = DownloadHistory.query(null, null, null, 0);
        assertEquals(1, all.size());
        DownloadHistory.DownloadRecord r = all.get(0);
        assertEquals("ani-1", r.getAniId());
        assertEquals("番剧A", r.getTitle());
        assertEquals(1.0, r.getEpisode());
        assertEquals(1024L, r.getSize());
        assertEquals("SUCCESS", r.getResult());
        assertNotNull(r.getId(), "应自动生成 id");
        assertNotNull(r.getAt(), "应自动补时间戳");
    }

    @Test
    void query_filters_by_ani_and_result() {
        DownloadHistory.record("ani-1", "A", "a1", "h1", 1.0, 100L, "qB", null,
                DownloadHistory.Result.SUCCESS, null);
        DownloadHistory.record("ani-1", "A", "a2", "h2", 2.0, 100L, "qB", null,
                DownloadHistory.Result.FAILED, null);
        DownloadHistory.record("ani-2", "B", "b1", "h3", 1.0, 100L, "qB", null,
                DownloadHistory.Result.SUCCESS, null);

        assertEquals(2, DownloadHistory.query("ani-1", null, null, 0).size());
        assertEquals(1, DownloadHistory.query(null, "FAILED", null, 0).size());
        assertEquals(2, DownloadHistory.query(null, "SUCCESS", null, 0).size());
        assertEquals(1, DownloadHistory.query("ani-2", "SUCCESS", null, 0).size());
        assertEquals(0, DownloadHistory.query("ani-9", null, null, 0).size());
    }

    @Test
    void query_respects_limit_and_returns_newest_first() {
        for (int i = 1; i <= 5; i++) {
            DownloadHistory.record("ani-1", "A", "ep" + i, "h" + i, (double) i, 10L * i,
                    "qB", null, DownloadHistory.Result.SUCCESS, null);
            // 保证 at 递增，避免同一毫秒内顺序不确定
            try {
                Thread.sleep(2);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        List<DownloadHistory.DownloadRecord> limited = DownloadHistory.query(null, null, null, 3);
        assertEquals(3, limited.size());
        // 最新的在前
        assertTrue(limited.get(0).getAt() >= limited.get(2).getAt());
    }

    @Test
    void summary_counts_success_failed_and_skip() {
        DownloadHistory.record("a", "A", "x1", "h1", 1.0, 100L, "qB", null,
                DownloadHistory.Result.SUCCESS, null);
        DownloadHistory.record("a", "A", "x2", "h2", 2.0, 200L, "qB", null,
                DownloadHistory.Result.WASH, null);
        DownloadHistory.record("a", "A", "x3", "h3", 3.0, null, "qB", null,
                DownloadHistory.Result.FAILED, null);
        DownloadHistory.record("a", "A", "x4", "h4", 4.0, null, "qB", null,
                DownloadHistory.Result.SKIP, null);

        DownloadHistory.Summary summary = DownloadHistory.summary(0);
        assertEquals(2, summary.success(), "SUCCESS 与 WASH 都算完成");
        assertEquals(1, summary.failed());
        assertEquals(1, summary.skip());
        assertEquals(300L, summary.size(), "只累计完成侧体积");
        assertEquals(2.0 / 3.0, summary.successRate(), 1e-9);
    }

    @Test
    void dayStats_returns_continuous_days_and_buckets_today() {
        DownloadHistory.record("a", "A", "x1", "h1", 1.0, 500L, "qB", null,
                DownloadHistory.Result.SUCCESS, null);
        DownloadHistory.record("a", "A", "x2", "h2", 2.0, null, "qB", null,
                DownloadHistory.Result.FAILED, null);

        List<DownloadHistory.DayStat> stats = DownloadHistory.dayStats(7);
        assertEquals(7, stats.size(), "即使没有记录也应返回连续 N 天，便于前端画柱状图");

        DownloadHistory.DayStat today = stats.get(stats.size() - 1);
        assertEquals(1, today.success());
        assertEquals(1, today.failed());
        assertEquals(500L, today.size());
    }

    @Test
    void records_survive_reload_from_disk() {
        DownloadHistory.record("ani-1", "番剧A", "ep1", "h1", 1.0, 2048L,
                "OpenList", "组A", DownloadHistory.Result.SUCCESS, "下载完成");

        // 清空内存态后从磁盘重读，验证原子写确实落盘
        DownloadHistory.resetForTest();
        DownloadHistory.load();

        List<DownloadHistory.DownloadRecord> reloaded = DownloadHistory.query(null, null, null, 0);
        assertEquals(1, reloaded.size());
        assertEquals("番剧A", reloaded.get(0).getTitle());
        assertEquals(2048L, reloaded.get(0).getSize());
    }

    @Test
    void remove_and_clear() {
        DownloadHistory.DownloadRecord r = DownloadHistory.record("a", "A", "x", "h", 1.0, 1L,
                "qB", null, DownloadHistory.Result.SUCCESS, null);
        assertTrue(DownloadHistory.remove(r.getId()));
        assertFalse(DownloadHistory.remove("not-exist"));
        assertEquals(0, DownloadHistory.query(null, null, null, 0).size());

        DownloadHistory.record("a", "A", "y", "h2", 1.0, 1L, "qB", null,
                DownloadHistory.Result.SUCCESS, null);
        assertEquals(1, DownloadHistory.clear());
        assertEquals(0, DownloadHistory.query(null, null, null, 0).size());
    }

    @Test
    void corrupted_file_is_quarantined_instead_of_silently_emptied() {
        java.io.File file = new java.io.File(tempDir.toFile(), DownloadHistory.FILE_NAME);
        cn.hutool.core.io.FileUtil.writeString("{ this is not json", file,
                java.nio.charset.StandardCharsets.UTF_8);

        DownloadHistory.resetForTest();
        DownloadHistory.load();

        assertEquals(0, DownloadHistory.query(null, null, null, 0).size());
        java.io.File[] bad = tempDir.toFile()
                .listFiles((dir, name) -> name.startsWith(DownloadHistory.FILE_NAME + ".bad-"));
        assertNotNull(bad);
        assertEquals(1, bad.length, "坏文件应改名保留现场，而不是被直接覆盖");
    }
}
