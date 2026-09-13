package ani.rss.util.other;

import ani.rss.entity.vo.RssJobStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RSS 调度快照持久化。
 * <p>
 * 只持久化"已完成轮次的结果"，<b>不</b>持久化运行中/排队中的活动态——
 * 重启后那些任务客观上已不存在，恢复出"运行中"只会制造幽灵状态。
 */
class RssJobStateStoreTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("CONFIG");
    }

    private static RssJobStatus.FailedSubscription failed(String id, String title) {
        return new RssJobStatus.FailedSubscription()
                .setAniId(id)
                .setTitle(title)
                .setStage("download")
                .setHumanizedMessage("下载超时")
                .setSuggestion("检查网络")
                .setAt(System.currentTimeMillis());
    }

    @Test
    void save_then_load_roundtrip() {
        RssJobStateStore.Snapshot snapshot = new RssJobStateStore.Snapshot()
                .setLastFinishedAt(1700000000000L)
                .setLastDurationMs(12345L)
                .setLastResultMessage("完成 12 条")
                .setLastTitle("全部启用订阅")
                .setLastSource("periodic")
                .setLastScope("all")
                .setFailedSubscriptions(new ArrayList<>(List.of(failed("a", "番剧A"))));

        RssJobStateStore.save(snapshot);
        RssJobStateStore.Snapshot loaded = RssJobStateStore.load();

        assertNotNull(loaded);
        assertEquals(1700000000000L, loaded.getLastFinishedAt());
        assertEquals(12345L, loaded.getLastDurationMs());
        assertEquals("完成 12 条", loaded.getLastResultMessage());
        assertEquals("periodic", loaded.getLastSource());
        assertEquals(1, loaded.getFailedSubscriptions().size());
        assertEquals("番剧A", loaded.getFailedSubscriptions().get(0).getTitle());
    }

    @Test
    void load_returns_null_when_absent() {
        assertNull(RssJobStateStore.load());
    }

    @Test
    void failed_details_are_capped() {
        List<RssJobStatus.FailedSubscription> many = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            many.add(failed("id-" + i, "title-" + i));
        }
        RssJobStateStore.save(new RssJobStateStore.Snapshot().setFailedSubscriptions(many));

        RssJobStateStore.Snapshot loaded = RssJobStateStore.load();
        assertNotNull(loaded);
        assertEquals(50, loaded.getFailedSubscriptions().size(), "失败明细应裁剪到 50 条上限");
    }

    @Test
    void corrupted_file_is_quarantined() {
        File file = new File(tempDir.toFile(), RssJobStateStore.FILE_NAME);
        cn.hutool.core.io.FileUtil.writeString("not json at all", file, StandardCharsets.UTF_8);

        assertNull(RssJobStateStore.load());
        File[] bad = tempDir.toFile()
                .listFiles((dir, name) -> name.startsWith(RssJobStateStore.FILE_NAME + ".bad-"));
        assertNotNull(bad);
        assertEquals(1, bad.length, "坏文件应改名保留现场");
    }

    @Test
    void save_null_is_noop() {
        RssJobStateStore.save(null);
        assertNull(RssJobStateStore.load());
    }
}
