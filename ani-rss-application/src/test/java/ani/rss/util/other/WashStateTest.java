package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 洗版状态持久化（{@link WashState}）。
 * <p>
 * 这份状态存在的唯一理由是"重命名后的文件名里没有版本号"：
 * 洗版判定若无状态，同一 v2 标题每轮 RSS 扫描都会被判成"待洗版"而无限重下。
 * 因此这里必须固化两条不变量：<b>跨重启可读</b>与<b>无记录按 0 处理</b>。
 */
class WashStateTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.toString());
        WashState.resetForTest();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("CONFIG");
        WashState.resetForTest();
    }

    private static Ani ani() {
        Ani ani = new Ani();
        ani.setId("ani-1").setTitle("测试番剧").setSeason(1).setNamingVersion(2);
        return ani;
    }

    private static Item item(double episode, Integer version) {
        Item item = new Item();
        item.setTitle("测试番剧 S01E0" + (int) episode + " v" + version)
                .setReName("测试番剧 S01E0" + (int) episode)
                .setEpisode(episode)
                .setVersion(version);
        return item;
    }

    @Test
    void mark_then_version_is_visible_after_reload() {
        assertTrue(WashState.markDownloaded(ani(), item(1.0, 2)),
                "首次记账应报告状态变化（供补记只打一条日志）");
        assertEquals(2, WashState.versionOf(ani(), item(1.0, 2)));
        assertFalse(WashState.markDownloaded(ani(), item(1.0, 2)),
                "同版本重复记账不是状态变化");

        // 模拟进程重启：丢掉内存态，重新从盘上读
        WashState.resetForTest();
        assertEquals(2, WashState.versionOf(ani(), item(1.0, 2)),
                "洗版状态必须落盘，重启后同一版本不得再洗一次");
    }

    @Test
    void unknown_episode_defaults_to_zero() {
        assertEquals(0, WashState.versionOf(ani(), item(7.0, 2)),
                "无记录按 0 处理：会洗一次，随后收敛");
    }

    @Test
    void version_never_downgrades() {
        WashState.markDownloaded(ani(), item(1.0, 3));
        // 陈旧的低版本条目（RSS 回退 / 同集多路源版本不同）不得把已记录的高版本改回去，
        // 否则下一个 v3 条目会又被判成“待洗版”而重新下载。
        assertFalse(WashState.markDownloaded(ani(), item(1.0, 2)),
                "低版本记账不应改变状态");
        assertEquals(3, WashState.versionOf(ani(), item(1.0, 2)),
                "已落地版本必须单调不减");
    }

    @Test
    void collection_version_is_min_of_range() {
        // 合集 E01-E03 已完成 E01/E02，E03 尚未落地
        Item part = item(1.0, 2);
        part.setEpisodeRange(List.of(1.0, 2.0));
        WashState.markDownloaded(ani(), part);

        Item full = item(3.0, 2);
        full.setEpisodeRange(List.of(1.0, 2.0, 3.0));
        assertEquals(0, WashState.versionOf(ani(), full),
                "合集内只要有未落地的集，整条合集就仍算未洗版");
    }

    @Test
    void only_v2_naming_is_recorded() {
        Ani legacy = ani().setNamingVersion(1);
        WashState.markDownloaded(legacy, item(1.0, 2));
        assertEquals(0, WashState.versionOf(legacy, item(1.0, 2)),
                "非 v2 命名下版本号不参与判定，不应留下记录");
    }

    @Test
    void forget_removes_subscription_records() {
        WashState.markDownloaded(ani(), item(1.0, 2));
        assertTrue(new File(tempDir.toFile(), WashState.FILE_NAME).exists(),
                "记账后应落盘 " + WashState.FILE_NAME);

        WashState.forget(ani());
        assertEquals(0, WashState.versionOf(ani(), item(1.0, 2)),
                "删除订阅应回收它的洗版状态");
    }

    @Test
    void corrupted_file_is_quarantined() {
        File file = new File(tempDir.toFile(), WashState.FILE_NAME);
        FileUtil.writeString("not json at all", file, StandardCharsets.UTF_8);

        assertEquals(0, WashState.versionOf(ani(), item(1.0, 2)),
                "坏文件应退化为空状态，绝不抛异常打断下载主流程");
        File[] bad = tempDir.toFile()
                .listFiles((dir, name) -> name.startsWith(WashState.FILE_NAME + ".bad-"));
        assertNotNull(bad);
        assertTrue(bad.length >= 1, "坏文件应改名保留现场");
    }
}
