package ani.rss.service;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.util.other.WashState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 洗版判定表（{@code DownloadService#needsWash}）。
 * <p>
 * 线上事故（2026-10）就发生在这张表的"格子"里：v2 <b>已经下载过、种子记录都在</b>，
 * 但判定只看 `version > 1`，于是每轮 RSS 扫描都重新洗版。修复后判定必须是
 * <b>「本版没有自己的记录」且「版本严格高于已落地版本」</b>两条同时成立。
 * <p>
 * 这里直接固化整张表：任何一条被简化掉，都会重新长出无限洗版或"漏洗"。
 * 端到端链路见 {@code DownloadJourneyE2ETest#wash_runs_once_per_version}。
 */
class WashDecisionTest {

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

    private static Ani ani(int season, Integer namingVersion) {
        Ani ani = new Ani();
        ani.setId("ani-1").setTitle("测试番剧").setSeason(season).setNamingVersion(namingVersion);
        return ani;
    }

    private static Item item(double episode, Integer version) {
        Item item = new Item();
        item.setTitle("测试番剧 S0" + (int) episode + " v" + version)
                .setReName("测试番剧 S01E0" + (int) episode)
                .setEpisode(episode)
                .setVersion(version);
        return item;
    }

    /**
     * 用户场景：第一次下载的是无版本标记的 {@code XXX S01E01}，第二次 RSS 出 {@code XXX S01E01v2}。
     * <p>
     * 期望恰好洗一次，然后彻底停住（无论后续记录在不在、状态在不在）。
     */
    @Test
    void v1_then_v2_is_exactly_one_wash() {
        Ani ani = ani(1, 2);
        Item v1 = item(1.0, 1);
        Item v2 = item(1.0, 2);

        // 第一次：无记录、无状态。v1 不是洗版目标，走普通下载；受理后记账 = 1
        assertFalse(DownloadService.needsWash(ani, v1, false));
        WashState.markDownloaded(ani, v1);

        // 第二次：v2 到达（新 infoHash，本版尚无记录）→ 必须洗一次
        assertTrue(DownloadService.needsWash(ani, v2, false), "v1→v2 必须洗一次版");
        WashState.markDownloaded(ani, v2);

        // 之后：本版记录已存在 → 不洗；即使记录丢了，版本也不再高于已落地版本 → 仍不洗
        assertFalse(DownloadService.needsWash(ani, v2, true), "v2 已下载（记录在）不得再洗");
        assertFalse(DownloadService.needsWash(ani, v2, false), "v2 已落地（状态在）不得再洗");
    }

    @Test
    void record_exists_terminates_wash() {
        // 事故现场：v2 已经下载完（记录在），wash-state.json 里什么都没有（升级前存量数据）。
        // 判定必须为"不洗版"——而不是"本地当前 v0"。
        assertFalse(DownloadService.needsWash(ani(1, 2), item(1.0, 2), true),
                "本版记录已存在就是已下载，不得再判洗版（无限洗版的根因）");
    }

    @Test
    void wash_only_when_strictly_higher_than_landed() {
        Ani ani = ani(1, 2);
        // 无记录且无状态：v2 需要洗一次（首次/存量）
        assertTrue(DownloadService.needsWash(ani, item(1.0, 2), false));

        WashState.markDownloaded(ani, item(1.0, 2));
        // 同一版本再扫：不洗
        assertFalse(DownloadService.needsWash(ani, item(1.0, 2), false),
                "同一版本不得重复洗版");
        // 更高版本：洗
        assertTrue(DownloadService.needsWash(ani, item(1.0, 3), false),
                "更高版本才洗版");
        // 更低版本：不洗（老版本不该回退覆盖）
        assertFalse(DownloadService.needsWash(ani, item(1.0, 1), false));
    }

    @Test
    void version_one_and_non_v2_naming_never_wash() {
        assertFalse(DownloadService.needsWash(ani(1, 2), item(1.0, 1), false),
                "无版本标记（v1）不是洗版目标");
        assertFalse(DownloadService.needsWash(ani(1, 2), item(1.0, null), false),
                "版本号缺失时保守不洗");
        assertFalse(DownloadService.needsWash(ani(1, 1), item(1.0, 2), false),
                "非 v2 命名下版本号不参与判定");
    }

    @Test
    void collection_washes_until_every_episode_landed() {
        Ani ani = ani(1, 2);
        Item part = item(1.0, 2);
        part.setEpisodeRange(List.of(1.0, 2.0));
        WashState.markDownloaded(ani, part);

        Item full = item(3.0, 2);
        full.setEpisodeRange(List.of(1.0, 2.0, 3.0));
        assertTrue(DownloadService.needsWash(ani, full, false),
                "合集里还有一集没落地，就仍需洗版");

        Item full2 = item(2.0, 2);
        full2.setEpisodeRange(List.of(1.0, 2.0));
        assertFalse(DownloadService.needsWash(ani, full2, false),
                "合集范围内每一集都已落地则不再洗版");
    }
}
