package ani.rss.task;

import ani.rss.download.OpenList;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.TorrentsInfo;
import ani.rss.enums.TorrentsTags;
import ani.rss.util.other.TorrentUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F4 静默窗口：开轮前"等后处理收尾"的判定与等待。
 * <p>
 * 要挡住的真实场景：周期轮次每 {@code rssSleepMinutes} 无条件开轮，改名/上传/离线归位是独立任务，
 * 两者零协调。于是存在"下载已完成、文件还没改名落地"的窗口——此刻按真实文件判定"本地是否存在"
 * 会漏掉它（未改名文件不含 SxxExx，集数索引匹配不上），进而重复下发下载。
 * <p>
 * 三条底线：
 * <ol>
 *   <li><b>无法判定 = 不静默</b>：下载器查询失败时宁可等，绝不在状态未知时开轮；</li>
 *   <li><b>连续确认</b>：单次判定可能撞上瞬时空窗（文件刚被移走、标签还没写回）；</li>
 *   <li><b>有超时兜底</b>：一次卡住的改名不该让 RSS 永久停摆——超时强制开轮并明示。</li>
 * </ol>
 */
class QuiescentWindowTest {

    @TempDir
    Path tempDir;

    private String prevConfig;
    private List<TorrentsInfo> stubTorrents;
    private boolean stubThrows;

    @BeforeEach
    void setUp() {
        prevConfig = System.getProperty("CONFIG");
        System.setProperty("CONFIG", tempDir.toString());
        stubTorrents = new ArrayList<>();
        stubThrows = false;
        TorrentUtil.DOWNLOAD = new OpenList() {
            @Override
            public List<TorrentsInfo> getTorrentsInfos() {
                if (stubThrows) {
                    throw new IllegalStateException("下载器连接失败");
                }
                return new ArrayList<>(stubTorrents);
            }
        };
        TorrentUtil.refreshTorrentsCache();
        RssTask.RssJobState.download.set(false);
        RssTask.RssJobState.quiescentForced.set(false);
        RssTask.RssJobState.quiescentBusyAniIds.set(Set.of());
        RssTask.RssJobState.quiescentSkipped.set(0);
    }

    @AfterEach
    void tearDown() {
        if (prevConfig == null) {
            System.clearProperty("CONFIG");
        } else {
            System.setProperty("CONFIG", prevConfig);
        }
        TorrentUtil.DOWNLOAD = null;
        TorrentUtil.refreshTorrentsCache();
        RssTask.RssJobState.download.set(false);
        RssTask.RssJobState.quiescentForced.set(false);
        RssTask.RssJobState.quiescentBusyAniIds.set(Set.of());
        RssTask.RssJobState.quiescentSkipped.set(0);
    }

    private static TorrentsInfo torrent(String name, TorrentsInfo.State state, String... tags) {
        return new TorrentsInfo()
                .setName(name)
                .setHash(name)
                .setState(state)
                .setTags(new ArrayList<>(List.of(tags)));
    }

    // ---------------- 状态分类 ----------------

    @Test
    void seeding_states_are_recognised() {
        // 「已下载完成、进入做种/上传」——文件已落盘，缺 RENAME 标签即为"待改名"
        assertTrue(RssTask.isSeedingState(TorrentsInfo.State.queuedUP));
        assertTrue(RssTask.isSeedingState(TorrentsInfo.State.uploading));
        assertTrue(RssTask.isSeedingState(TorrentsInfo.State.stalledUP));
        assertTrue(RssTask.isSeedingState(TorrentsInfo.State.pausedUP));
        assertTrue(RssTask.isSeedingState(TorrentsInfo.State.stoppedUP));
    }

    @Test
    void in_flight_states_are_not_seeding() {
        assertFalse(RssTask.isSeedingState(TorrentsInfo.State.downloading));
        assertFalse(RssTask.isSeedingState(TorrentsInfo.State.stalledDL));
        assertFalse(RssTask.isSeedingState(TorrentsInfo.State.queuedDL));
        assertFalse(RssTask.isSeedingState(TorrentsInfo.State.checkingResumeData));
        assertFalse(RssTask.isSeedingState(TorrentsInfo.State.checkingDisk));
        assertFalse(RssTask.isSeedingState(TorrentsInfo.State.metaDownload));
        assertFalse(RssTask.isSeedingState(TorrentsInfo.State.error));
        assertFalse(RssTask.isSeedingState(TorrentsInfo.State.unknown));
        // null 状态按"未完成"处理（保守：宁可等）
        assertFalse(RssTask.isSeedingState(null));
    }

    // ---------------- 配置解析 ----------------

    @Test
    void confirm_times_defaults_and_clamps() {
        assertEquals(2, RssTask.resolveQuiescentConfirmTimes(null));
        assertEquals(2, RssTask.resolveQuiescentConfirmTimes(new Config()));
        assertEquals(5, RssTask.resolveQuiescentConfirmTimes(new Config().setQuiescentConfirmTimes(5)));
        // 下限 1：0/负值不能让"连续确认"退化成死循环
        assertEquals(1, RssTask.resolveQuiescentConfirmTimes(new Config().setQuiescentConfirmTimes(0)));
        assertEquals(1, RssTask.resolveQuiescentConfirmTimes(new Config().setQuiescentConfirmTimes(-3)));
        // 上限 10：确认次数越大等待越久
        assertEquals(10, RssTask.resolveQuiescentConfirmTimes(new Config().setQuiescentConfirmTimes(99)));
    }

    @Test
    void interval_follows_rename_sleep_seconds() {
        // 与改名任务同频，保证不会比后处理更慢地发现"已收尾"
        assertEquals(10_000L, RssTask.resolveQuiescentIntervalMs(null));
        assertEquals(10_000L, RssTask.resolveQuiescentIntervalMs(new Config()));
        assertEquals(3_000L, RssTask.resolveQuiescentIntervalMs(new Config().setRenameSleepSeconds(3)));
        // 非法值收敛到 1s，绝不出现 0（0 会变成忙等）
        assertEquals(1_000L, RssTask.resolveQuiescentIntervalMs(new Config().setRenameSleepSeconds(0)));
        assertEquals(1_000L, RssTask.resolveQuiescentIntervalMs(new Config().setRenameSleepSeconds(-5)));
    }

    @Test
    void timeout_defaults_to_twice_cycle() {
        // 默认 2 × rssSleepMinutes
        assertEquals(30L * 60_000L, RssTask.resolveQuiescentTimeoutMs(new Config()));
        assertEquals(60L * 60_000L, RssTask.resolveQuiescentTimeoutMs(new Config().setRssSleepMinutes(30)));
        // 下限 5 分钟：周期极短时也不能几乎不等待
        assertEquals(5L * 60_000L, RssTask.resolveQuiescentTimeoutMs(new Config().setRssSleepMinutes(1)));
        // 显式配置优先
        assertEquals(120L * 60_000L,
                RssTask.resolveQuiescentTimeoutMs(new Config().setQuiescentTimeoutMinutes(120)));
        // 非法值收敛到 1 分钟
        assertEquals(60_000L,
                RssTask.resolveQuiescentTimeoutMs(new Config().setQuiescentTimeoutMinutes(0)));
        assertEquals(60_000L,
                RssTask.resolveQuiescentTimeoutMs(new Config().setQuiescentTimeoutMinutes(-1)));
        // 上限 24h
        assertEquals(RssTask.MAX_QUIESCENT_TIMEOUT_MINUTES * 60_000L,
                RssTask.resolveQuiescentTimeoutMs(new Config().setQuiescentTimeoutMinutes(999_999)));
    }

    // ---------------- pending 标记 ----------------

    @Test
    void pending_markers_are_counted() throws IOException {
        assertEquals(0, RssTask.countPendingMarkers(), "无 .pending 目录应为 0");

        Path pending = tempDir.resolve("torrents").resolve(".pending").resolve("某番").resolve("Season 1");
        Files.createDirectories(pending);
        Files.writeString(pending.resolve("abc123.torrent"), "x");
        Files.writeString(pending.resolve("def456.txt"), "y");
        assertEquals(2, RssTask.countPendingMarkers());
    }

    @Test
    void empty_pending_directories_do_not_block() throws IOException {
        // 只建目录不建文件 → 仍算静默（残留空目录无害）
        Files.createDirectories(tempDir.resolve("torrents").resolve(".pending").resolve("某番"));
        assertEquals(0, RssTask.countPendingMarkers());
    }

    // ---------------- 静默判定 ----------------

    @Test
    void running_round_is_never_quiescent() {
        RssTask.RssJobState.download.set(true);
        RssTask.QuiescentState state = RssTask.evaluateQuiescent();
        assertFalse(state.quiescent());
        assertTrue(state.reason().contains("轮次"), state.reason());
    }

    @Test
    void downloader_query_failure_is_not_quiescent() {
        // 状态未知时绝不能开轮——否则会基于"以为没任务"做判断
        stubThrows = true;
        TorrentUtil.refreshTorrentsCache();
        RssTask.QuiescentState state = RssTask.evaluateQuiescent();
        assertFalse(state.quiescent());
        assertTrue(state.reason().contains("读取下载器任务失败"), state.reason());
    }

    @Test
    void in_flight_download_is_not_quiescent() {
        stubTorrents.add(torrent("某番 05", TorrentsInfo.State.downloading, TorrentsTags.ANI_RSS.getValue()));
        TorrentUtil.refreshTorrentsCache();
        RssTask.QuiescentState state = RssTask.evaluateQuiescent();
        assertFalse(state.quiescent());
        assertTrue(state.reason().contains("尚未完成"), state.reason());
    }

    @Test
    void completed_but_not_renamed_is_not_quiescent() {
        stubTorrents.add(torrent("某番 05", TorrentsInfo.State.stalledUP, TorrentsTags.ANI_RSS.getValue()));
        TorrentUtil.refreshTorrentsCache();
        RssTask.QuiescentState state = RssTask.evaluateQuiescent();
        assertFalse(state.quiescent());
        assertTrue(state.reason().contains("尚未改名"), state.reason());
    }

    @Test
    void completed_and_renamed_is_quiescent() {
        stubTorrents.add(torrent("某番 05", TorrentsInfo.State.stalledUP,
                TorrentsTags.ANI_RSS.getValue(), TorrentsTags.RENAME.getValue()));
        stubTorrents.add(torrent("某番 06", TorrentsInfo.State.uploading,
                TorrentsTags.ANI_RSS.getValue(), TorrentsTags.RENAME.getValue()));
        TorrentUtil.refreshTorrentsCache();
        RssTask.QuiescentState state = RssTask.evaluateQuiescent();
        assertTrue(state.quiescent(), state.reason());
        assertEquals("", state.reason());
    }

    @Test
    void null_tags_count_as_not_renamed() {
        TorrentsInfo ti = torrent("某番 05", TorrentsInfo.State.uploading);
        ti.setTags(null);
        stubTorrents.add(ti);
        TorrentUtil.refreshTorrentsCache();
        assertFalse(RssTask.evaluateQuiescent().quiescent());
    }

    @Test
    void pending_marker_blocks_quiescence() throws IOException {
        Files.createDirectories(tempDir.resolve("torrents").resolve(".pending").resolve("某番"));
        Files.writeString(tempDir.resolve("torrents").resolve(".pending").resolve("某番").resolve("a.txt"), "x");
        RssTask.QuiescentState state = RssTask.evaluateQuiescent();
        assertFalse(state.quiescent());
        assertTrue(state.reason().contains("离线任务尚未落地"), state.reason());
    }

    // ---------------- 等待与超时 ----------------

    @Test
    void await_returns_immediately_when_cancelled() {
        RssTask.RssJobState.download.set(true); // 即便不静默
        long start = System.currentTimeMillis();
        RssTask.QuiescentState state = RssTask.awaitQuiescent(new AtomicBoolean(false), 3, 100L, 60_000L);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(state.quiescent(), "已取消时不应继续等待，直接放行给调用方收尾");
        assertTrue(elapsed < 500L, "实际耗时 " + elapsed + "ms");
    }

    @Test
    void await_confirms_consecutively_then_passes() {
        // 静默环境 + 连续 3 次确认 → 通过，且耗时至少 2 个间隔
        long start = System.currentTimeMillis();
        RssTask.QuiescentState state = RssTask.awaitQuiescent(new AtomicBoolean(true), 3, 100L, 10_000L);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(state.quiescent());
        assertTrue(elapsed >= 180L, "连续确认之间必须有间隔，实际 " + elapsed + "ms");
    }

    @Test
    void await_times_out_and_reports_reason() {
        RssTask.RssJobState.download.set(true); // 永远不静默
        long start = System.currentTimeMillis();
        RssTask.QuiescentState state = RssTask.awaitQuiescent(new AtomicBoolean(true), 2, 100L, 600L);
        long elapsed = System.currentTimeMillis() - start;
        assertFalse(state.quiescent(), "超时应返回不静默，由调用方强制开轮");
        assertTrue(state.reason().contains("轮次"), state.reason());
        assertTrue(elapsed >= 500L, "应等满超时上限，实际 " + elapsed + "ms");
        assertTrue(elapsed < 3_000L, "不应远超超时上限，实际 " + elapsed + "ms");
    }

    @Test
    void await_treats_illegal_confirm_times_as_one() {
        long start = System.currentTimeMillis();
        RssTask.QuiescentState state = RssTask.awaitQuiescent(new AtomicBoolean(true), 0, 100L, 5_000L);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(state.quiescent());
        assertTrue(elapsed < 200L, "确认次数 0 应回落为 1（一次即过），实际 " + elapsed + "ms");
    }

    // ---------------- 周期补偿 ----------------

    @Test
    void post_round_sleep_compensates_the_wait() {
        Config c = new Config().setRssSleepMinutes(15);
        // 没等待 → 完整周期
        assertEquals(15L * 60_000L, RssTask.resolvePostRoundSleepMs(c, 0L));
        // 等了 10 分钟 → 只补 5 分钟，总周期仍约 15 分钟
        assertEquals(5L * 60_000L, RssTask.resolvePostRoundSleepMs(c, 10L * 60_000L));
        // 等待超过周期 → 保底 周期的 1/4，避免退化成忙等
        assertEquals(15L * 60_000L / 4, RssTask.resolvePostRoundSleepMs(c, 60L * 60_000L));
        // 负数（时钟回拨等异常）按 0 处理
        assertEquals(15L * 60_000L, RssTask.resolvePostRoundSleepMs(c, -5_000L));
        // 周期极短时保底 1 分钟
        Config tiny = new Config().setRssSleepMinutes(1);
        assertEquals(60_000L, RssTask.resolvePostRoundSleepMs(tiny, 10L * 60_000L));
        // 配置缺失 → 默认 15 分钟周期
        assertEquals(15L * 60_000L, RssTask.resolvePostRoundSleepMs(null, 0L));
        assertEquals(15L * 60_000L, RssTask.resolvePostRoundSleepMs(new Config(), 0L));
    }

    // ---------------- 强制开轮的订阅跳过（F4-6）----------------
    //
    // 这一段是"重复下载"的最后一道闸门，也是验收项 A5 / A13 的落点：
    // 强制开轮（静默超时）时，那些"文件已下完但改名/归位还没落地"的订阅，
    // 本地状态不可信，按文件名匹配集数会匹配不上 → 把"已下载"判成"未下载" → 重复下载。
    // 所以必须把它们剔出本轮，其余订阅照常扫描。

    @Test
    void busy_ani_ids_default_to_empty() {
        assertEquals(Set.of(), RssTask.RssJobState.quiescentBusyAniIds.get());
        assertFalse(RssTask.RssJobState.quiescentForced.get());
    }

    @Test
    void forced_round_drops_busy_subscriptions() {
        List<Ani> enabled = new ArrayList<>(List.of(ani("a1"), ani("a2"), ani("a3")));

        int skipped = RssTask.dropBusySubscriptionsOnForcedRound(enabled, true, Set.of("a2"));

        assertEquals(1, skipped);
        assertEquals(List.of("a1", "a3"), ids(enabled), "只有忙碌的那个订阅应被剔除");
    }

    @Test
    void forced_round_can_drop_multiple_subscriptions() {
        List<Ani> enabled = new ArrayList<>(List.of(ani("a1"), ani("a2"), ani("a3"), ani("a4")));

        int skipped = RssTask.dropBusySubscriptionsOnForcedRound(enabled, true, Set.of("a2", "a4"));

        assertEquals(2, skipped);
        assertEquals(List.of("a1", "a3"), ids(enabled));
    }

    @Test
    void non_forced_round_keeps_everything() {
        // 正常轮次本来就等到了静默，剔除会造成漏扫 —— 只有强制开轮才剔除
        List<Ani> enabled = new ArrayList<>(List.of(ani("a1"), ani("a2")));

        int skipped = RssTask.dropBusySubscriptionsOnForcedRound(enabled, false, Set.of("a1", "a2"));

        assertEquals(0, skipped);
        assertEquals(List.of("a1", "a2"), ids(enabled));
    }

    @Test
    void forced_round_without_busy_subscriptions_keeps_everything() {
        List<Ani> enabled = new ArrayList<>(List.of(ani("a1"), ani("a2")));

        assertEquals(0, RssTask.dropBusySubscriptionsOnForcedRound(enabled, true, Set.of()));
        assertEquals(List.of("a1", "a2"), ids(enabled));
    }

    @Test
    void forced_round_without_overlap_keeps_everything() {
        List<Ani> enabled = new ArrayList<>(List.of(ani("a1"), ani("a2")));

        assertEquals(0, RssTask.dropBusySubscriptionsOnForcedRound(enabled, true, Set.of("other")));
        assertEquals(List.of("a1", "a2"), ids(enabled));
    }

    @Test
    void null_inputs_are_safe() {
        assertEquals(0, RssTask.dropBusySubscriptionsOnForcedRound(null, true, Set.of("a1")));
        assertEquals(0, RssTask.dropBusySubscriptionsOnForcedRound(new ArrayList<>(), true, Set.of("a1")));

        List<Ani> enabled = new ArrayList<>(List.of(ani("a1")));
        assertEquals(0, RssTask.dropBusySubscriptionsOnForcedRound(enabled, true, null));
        assertEquals(List.of("a1"), ids(enabled));
    }

    @Test
    void entries_without_id_or_null_entry_are_kept() {
        // 不可变 Set 的 contains(null) 会抛 NPE；id 缺失的条目也不可能出现在 busyAniIds 里，
        // 保留它走正常路径即可，不能因此让整轮炸掉。
        List<Ani> enabled = new ArrayList<>();
        enabled.add(new Ani().setTitle("无 id"));
        enabled.add(null);
        enabled.add(ani("a1"));

        int skipped = RssTask.dropBusySubscriptionsOnForcedRound(enabled, true, Set.of("a1"));

        assertEquals(1, skipped);
        assertEquals(2, enabled.size());
    }

    private static Ani ani(String id) {
        return new Ani().setId(id).setTitle("订阅" + id);
    }

    private static List<String> ids(List<Ani> list) {
        return list.stream().map(Ani::getId).collect(Collectors.toList());
    }
}
