package ani.rss.download;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentPlanRecord;
import ani.rss.service.LocalStateCache;
import ani.rss.testsupport.TestTorrent;
import ani.rss.util.other.FailedDownloadQueue;
import cn.hutool.core.io.FileUtil;
import ani.rss.util.other.OfflinePlanStore;
import ani.rss.util.other.TorrentPlanUtil;
import ani.rss.util.other.TorrentUtil;
import org.eclipse.bittorrent.TorrentFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「期望文件计划」驱动的离线归位（P1/P2/P3/P4）。
 * <p>
 * 复用 {@link OpenListWorkflowSimulationTest} 的内存 AList mock（真实 OpenList 代码路径），
 * 但种子里<b>带 info dict</b>（{@link TestTorrent}），因此能测到计划链路；
 * 老用例用的是"空文件 + 文件名当 hash"，天然走旧启发式，正好当回归基线。
 * <p>
 * 重点固化"下载器报部分成功/一直 Running，其实文件全在"这一线上形态：
 * 只要计划内视频按字节数到位，就应该立刻归位，而不是等任务状态或等离线超时。
 */
class OpenListPlanWorkflowTest {

    private OpenListWorkflowSimulationTest.MockAlistServer server;
    private String savePath;
    private String tempDirName;
    private static final String HASH = "9c6c6e863114b7191b4c66699f3be2e55f1254cf";

    private String previousConfigDir;

    @BeforeEach
    void setUp() throws IOException {
        server = new OpenListWorkflowSimulationTest.MockAlistServer();
        server.start();
        new OpenListApi().invalidateFindFilesCache();
        savePath = "/追番/Show/Season 1";
        tempDirName = "Show S01E03";
        // 每个用例用独立配置目录：计划快照/失败队列/种子记录都不该写进真实配置目录
        previousConfigDir = System.getProperty("CONFIG");
        File dir = new File(System.getProperty("java.io.tmpdir"), "ani-rss-plan-test-" + UUID.randomUUID());
        assertTrue(dir.mkdirs());
        System.setProperty("CONFIG", dir.getAbsolutePath());
    }

    @AfterEach
    void tearDown() {
        server.stop();
        LocalStateCache.clear();
        if (previousConfigDir == null) {
            System.clearProperty("CONFIG");
        } else {
            System.setProperty("CONFIG", previousConfigDir);
        }
    }

    private Ani ani() {
        return new Ani()
                .setId("ani-plan-1")
                .setTitle("Show")
                .setSubgroup("LoliHouse")
                .setSeason(1)
                .setOffset(0)
                .setOva(false)
                .setMediaType("tv")
                .setBgmUrl("https://bgm.tv/subject/123")
                .setThemoviedbName("")
                .setNamingVersion(2)
                .setCustomEpisode(false)
                .setCustomRenameTemplateEnable(false)
                .setMatch(List.of())
                .setExclude(List.of())
                .setGlobalExclude(false)
                .setMessage(false);
    }

    private Item item(Double episode) {
        return new Item()
                .setTitle("Show")
                .setReName("Show S01E03")
                .setEpisodeRange(List.of())
                .setEpisode(episode)
                .setInfoHash(HASH);
    }

    private OpenList openList() {
        return openList(null);
    }

    private OpenList openList(String cloudDownloadDir) {
        ani.rss.entity.Config config = new ani.rss.entity.Config()
                .setDownloadToolHost("http://127.0.0.1:" + server.port())
                .setDownloadToolPassword("mock-token")
                .setDownloadPathTemplate("/追番")
                .setProvider("115 Cloud")
                .setDelete(true)
                .setRename(true)
                .setStandbyRss(false)
                .setCoexist(false)
                .setAlistDownloadTimeout(1)
                .setAlistDownloadRetryNumber(3L)
                .setNotificationConfigList(List.of());
        if (cloudDownloadDir != null) {
            config.setAlistCloudDownloadDir(cloudDownloadDir);
        }
        OpenList openList = new OpenList();
        assertEquals(Boolean.TRUE, openList.login(true, config), "mock login 应成功");
        return openList;
    }

    // ============ P1 + P2：计划内文件齐了就不再等任务状态 ============

    /**
     * 115 真实形态：任务一直 Running（progress 37%），文件其实已经全部就位，
     * 且名字被改、还套了一层"文件名.mkv"目录。旧实现要等到离线超时（本用例配置为 1 分钟）
     * 才可能通过终检，这里要求十几秒内按字节数比对直接归位。
     */
    @Test
    void plan_complete_finalizes_even_when_task_never_succeeds() throws Exception {
        server.placeTaskDirInTarget = false;
        server.taskState = 1; // Running：永不成功
        server.taskProgress = 37;
        server.putFile(savePath + "/" + tempDirName + "/Show - 03.mkv/Show - 03.mkv", 1031L);
        server.putFile(savePath + "/" + tempDirName + "/sub.ass", 41L);

        File torrent = TestTorrent.temp("p1", "Show S01E03", List.of(
                TestTorrent.file("Show S01E03/Show - 03.mkv", 1031L),
                TestTorrent.file("Show S01E03/Show - 03.ass", 41L)));
        String expectedVideo = expectedVideoName(torrent);

        OpenList openList = openList();
        assertEquals(Boolean.TRUE, openList.download(ani(), item(3.0), savePath, torrent));

        awaitPath(30_000, () -> server.topLevel(savePath).contains(expectedVideo),
                "计划内视频已按字节数命中，应立即归位为 " + expectedVideo
                        + "，实际=" + server.topLevel(savePath));

        // 临时目录（含 115 的任务目录壳）被清理
        awaitPath(20_000, () -> !server.exists(savePath + "/" + tempDirName),
                "临时目录应被清理，实际全树=" + server.allPaths());
        // 字幕跟着视频一起归位（计划里有它）
        assertTrue(server.topLevel(savePath).stream().anyMatch(n -> n.endsWith(".ass")),
                "字幕应一并归位: " + server.topLevel(savePath));
    }

    /**
     * 115 的 fs/rename 只改主名、保留原扩展名（实测）：盘上文件名会与"请求的目标名"不一致。
     * 必须在重命名后回读真实名再移动/校验，否则本集被判归位失败、文件永远搬不出临时目录。
     */
    @Test
    void rename_keeps_cloud_extension_and_still_relocates() throws Exception {
        server.placeTaskDirInTarget = false;
        server.taskState = 1;
        server.preserveExtOnRename = true;
        // 云端产物的扩展名与种子不一致（.bin vs 计划里的 .mkv）
        server.putFile(savePath + "/" + tempDirName + "/mystery.bin", 1031L);

        File torrent = TestTorrent.temp("ext", "Show S01E03", List.of(
                TestTorrent.file("Show S01E03/Show - 03.mkv", 1031L)));
        List<Item> plan = planOf(torrent);
        String expectedMain = FileUtil.mainName(plan.get(0).getReName());

        OpenList openList = openList();
        assertEquals(Boolean.TRUE, openList.download(ani(), item(3.0), savePath, torrent));

        awaitPath(30_000, () -> server.topLevel(savePath).stream()
                        .anyMatch(n -> FileUtil.mainName(n).equals(expectedMain)),
                "主名应与计划一致（扩展名跟随网盘），实际=" + server.topLevel(savePath));
        assertTrue(server.topLevel(savePath).contains(expectedMain + ".bin"),
                "115 会保留原扩展名，实际=" + server.topLevel(savePath));
        awaitPath(20_000, () -> !server.exists(savePath + "/" + tempDirName),
                "临时目录应被清理: " + server.allPaths());
    }

    // ============ P3：部分到位 → 只标记真正到位的集 + 缺文件进失败队列 ============

    @Test
    void partial_download_marks_only_resolved_episode_and_reports_gap() throws Exception {
        server.placeTaskDirInTarget = false;
        // 下载器报"已完成"，但盘上只有一部分：旧口径（文件名提集数）连这部分都认不出来
        server.taskState = 2;
        server.taskProgress = 100;
        // 只到位了 E03（名字被改成无法解析集数的形式），E04 缺失
        server.putFile(savePath + "/" + tempDirName + "/aaa.mkv", 1031L);

        File torrent = TestTorrent.temp("p3", "Show S01", List.of(
                TestTorrent.file("Show S01/Show - 03.mkv", 1031L),
                TestTorrent.file("Show S01/Show - 04.mkv", 1032L)));
        List<Item> plan = planOf(torrent);
        String expectedVideo = plan.stream()
                .filter(i -> i.getTitle().endsWith("03.mkv"))
                .map(Item::getReName).findFirst().orElseThrow();

        Ani ani = ani();
        // 预置一份空快照：归位成功后会往里"增量追加"真正完成的集
        LocalStateCache.getOrBuild(ani, savePath, LocalStateCache.Source.CLOUD_API,
                () -> LocalStateCache.Loaded.of(Set.of()));
        // 合集式条目（episodeRange 非空）的临时目录名取 item.title —— 必须与文件落点一致
        Item item = item(3.0).setTitle(tempDirName).setEpisodeRange(List.of(3.0, 4.0));
        // 真实链路里待完成标记由 DownloadService 在提交前写下；这里手工补上，
        // 否则 promoteTorrent 会因为"没有待完成标记"直接跳过（也就测不到标记完成的接缝）
        seedPendingTorrent(ani, item);

        OpenList openList = openList();
        assertEquals(Boolean.TRUE, openList.download(ani, item, savePath, torrent));

        awaitPath(30_000, () -> server.topLevel(savePath).contains(expectedVideo),
                "到位的 E03 应被归位，实际=" + server.topLevel(savePath));

        // 只标记 E03：整个 episodeRange 都被标成已下载正是"部分成功"造成永久漏下的根因
        LocalStateCache.Snapshot snapshot = snapshotOrNull(ani, savePath);
        assertNotNull(snapshot, "归位后应能读回快照");
        assertEquals(Set.of("1:3.0"), snapshot.episodeIndex(),
                "只应标记真正归位的集，实际=" + snapshot.episodeIndex());

        // E04 缺失 → 失败队列可见（而不是只有一行 warn 日志）
        awaitPath(20_000, () -> FailedDownloadQueue.list().stream()
                        .anyMatch(f -> f.getMessage() != null && f.getMessage().contains("未到位")),
                "计划内缺视频应写入失败队列，实际=" + FailedDownloadQueue.list().size() + " 条");
        assertFalse(server.topLevel(savePath).stream().anyMatch(n -> n.contains("E04")),
                "缺失的集不应凭空出现");
    }

    // ============ P4：启动恢复（计划快照 + 只读比对 → 归位） ============

    @Test
    void startup_recovery_relocates_files_left_by_previous_run() throws Exception {
        server.placeTaskDirInTarget = false;
        // 上一次进程留下的形态：文件在临时目录里（名字乱），计划快照还在
        server.putFile(savePath + "/" + tempDirName + "/mystery.mkv", 1031L);

        File torrent = TestTorrent.temp("p4", "Show S01E03", List.of(
                TestTorrent.file("Show S01E03/Show - 03.mkv", 1031L)));
        List<Item> plan = planOf(torrent);
        String expectedVideo = plan.get(0).getReName();

        Ani ani = ani();
        OfflinePlanStore.save(HASH, ani.getId(), savePath, tempDirName, "Show S01E03", plan);
        assertTrue(OfflinePlanStore.planFile(HASH).isFile(), "计划快照应已落盘");

        OpenList openList = openList();
        openList.recoverOfflinePlans(List.of(ani));

        assertTrue(server.topLevel(savePath).contains(expectedVideo),
                "启动恢复应把上次没归位的文件搬到顶层并改名，实际=" + server.allPaths());
        assertFalse(OfflinePlanStore.planFile(HASH).isFile(), "恢复完成后快照应被清理");
    }

    @Test
    void startup_recovery_drops_snapshot_when_files_not_ready() throws Exception {
        server.placeTaskDirInTarget = false;
        server.putFile(savePath + "/" + tempDirName + "/mystery.mkv", 999L); // 字节数不匹配

        File torrent = TestTorrent.temp("p4b", "Show S01E03", List.of(
                TestTorrent.file("Show S01E03/Show - 03.mkv", 1031L)));
        Ani ani = ani();
        OfflinePlanStore.save(HASH, ani.getId(), savePath, tempDirName, "Show S01E03", planOf(torrent));

        openList().recoverOfflinePlans(List.of(ani));

        assertFalse(OfflinePlanStore.planFile(HASH).isFile(),
                "没齐就丢掉快照，交回 RSS 正常轮次（避免历史快照无限堆积）");
        assertFalse(server.topLevel(savePath).stream().anyMatch(n -> n.endsWith(".mkv")),
                "没齐时不应把任何视频搬到顶层，实际=" + server.topLevel(savePath));
        assertTrue(server.exists(savePath + "/" + tempDirName + "/mystery.mkv"),
                "原文件应留在临时目录里（由后续 RSS 轮次接管）");
    }

    @Test
    void startup_recovery_keeps_snapshot_when_subscription_list_not_ready() throws Exception {
        File torrent = TestTorrent.temp("p4c", "Show S01E03", List.of(
                TestTorrent.file("Show S01E03/Show - 03.mkv", 1031L)));
        OfflinePlanStore.save(HASH, "ani-plan-1", savePath, tempDirName, "Show S01E03", planOf(torrent));

        openList().recoverOfflinePlans(List.of()); // 订阅列表还没加载出来

        assertTrue(OfflinePlanStore.planFile(HASH).isFile(),
                "订阅列表未就绪时不能因为'查不到订阅'就删快照");
    }

    @Test
    void plan_snapshot_is_removed_after_finalize() throws Exception {
        server.placeTaskDirInTarget = false;
        server.taskState = 1;
        server.putFile(savePath + "/" + tempDirName + "/mystery.mkv", 1031L);

        File torrent = TestTorrent.temp("p4d", "Show S01E03", List.of(
                TestTorrent.file("Show S01E03/Show - 03.mkv", 1031L)));

        OpenList openList = openList();
        assertEquals(Boolean.TRUE, openList.download(ani(), item(3.0), savePath, torrent));
        awaitPath(30_000, () -> !server.exists(savePath + "/" + tempDirName),
                "应完成归位并清理临时目录: " + server.allPaths());

        awaitPath(20_000, () -> !OfflinePlanStore.planFile(HASH).isFile(),
                "任务收尾后计划快照应被清理，避免残留堆积");
    }

    // ============ 辅助 ============

    /** 写入"待完成标记"，模拟 DownloadService 提交前的动作 */
    private void seedPendingTorrent(Ani ani, Item item) throws IOException {
        File pending = TorrentUtil.getPendingTorrent(ani, item);
        FileUtil.mkParentDirs(pending);
        FileUtil.writeUtf8String("magnet:?xt=urn:btih:" + item.getInfoHash(), pending);
    }

    private List<Item> planOf(File torrent) throws IOException {
        return TorrentPlanUtil.filterEpisodes(
                TorrentPlanUtil.build(new TorrentFile(torrent), ani()), List.of());
    }

    private String expectedVideoName(File torrent) throws IOException {
        return planOf(torrent).stream()
                .filter(TorrentPlanUtil::isVideo)
                .map(Item::getReName)
                .findFirst()
                .orElseThrow();
    }

    private void awaitPath(long timeoutMs, java.util.function.BooleanSupplier condition, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        fail("等待超时：" + message);
    }

    /** 供断言使用的快照读取（快照不存在时返回 null） */
    private static LocalStateCache.Snapshot snapshotOrNull(Ani ani, String path) {
        try {
            return LocalStateCache.getOrBuild(ani, path, LocalStateCache.Source.CLOUD_API, () -> {
                throw new IllegalStateException("no snapshot");
            });
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void plan_record_round_trip() throws Exception {
        File torrent = TestTorrent.temp("p4e", "Show S01E03", List.of(
                TestTorrent.file("Show S01E03/Show - 03.mkv", 1031L)));
        List<Item> plan = planOf(torrent);
        OfflinePlanStore.save(HASH, "ani-plan-1", savePath, tempDirName, "Show S01E03", plan);

        List<TorrentPlanRecord> records = OfflinePlanStore.list();

        assertEquals(1, records.size());
        TorrentPlanRecord record = records.get(0);
        assertEquals(HASH, record.getInfoHash());
        assertEquals(savePath, record.getDownloadPath());
        assertEquals(1, record.getItems().size());
        assertEquals(1031L, record.getItems().get(0).getLength());
        assertEquals(plan.get(0).getReName(), record.getItems().get(0).getReName());
        assertNotNull(record.getCreatedAt());
    }
}
