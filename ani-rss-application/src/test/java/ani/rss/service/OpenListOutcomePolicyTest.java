package ani.rss.service;

import ani.rss.download.OfflineDownloader;
import ani.rss.download.OpenListApi;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.RenameUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3「存疑」策略在判定链路上的三条硬约束。
 * <p>
 * 背景（2026-09-25 实测故障）：上游 115 抖动时 {@code fs/list} 返回
 * {@code code=500 failed get objs … TLS handshake timeout}，重试 3 次后熔断。
 * 在旧口径下，"查不清"与"网盘上确实没有"都收敛成 {@code false}，
 * 于是"删记录 + 清 pending + 整季重新下单"——而文件其实已经躺在网盘上。
 * <p>
 * 这里固化三件事：
 * <ol>
 *   <li><b>已确认条目零请求</b>（D4）：本地快照已确认本集存在时，判定不许发任何网盘请求。
 *       每轮对每条已确认的集重做一次归位对账 = 每条一次往返，订阅一多就是整批放大；</li>
 *   <li><b>查不到 ≠ 确认没有</b>：对账返回 {@code UNVERIFIABLE} 时只能是「存疑」，
 *       绝不许变成 {@code ABSENT}（那是唯一允许删记录重下的判定）；</li>
 *   <li><b>「确认没有」必须跨时间</b>：网盘目录列举自带缓存（Alist 默认可达 1 小时），
 *       一次判没有就删记录会换来重复下载 + 与已落盘文件撞名。</li>
 * </ol>
 */
class OpenListOutcomePolicyTest {

    private final DownloadService downloadService = new DownloadService();

    private Integer prevFailThreshold;
    private Integer prevCooldownSeconds;

    /**
     * 只计数、不发任何网盘请求的假离线下载器。
     * <p>
     * {@code forceDeleteFiles} 直接抛错：判定路径出现任何删除动作都必须是测试失败，
     * 而不是"恰好没被断言到"。
     */
    private static class CountingOffline implements OfflineDownloader {
        final AtomicInteger relocateCalls = new AtomicInteger();
        volatile RelocateResult relocateResult = RelocateResult.NOT_FOUND;

        @Override
        public void forceDeleteFiles(String dirPath, String reName) {
            throw new AssertionError("判定路径不得产生任何删除动作");
        }

        @Override
        public List<String> listFileNames(String dirPath) {
            return List.of();
        }

        @Override
        public List<String> listFileNamesStrict(String dirPath) {
            return List.of();
        }

        @Override
        public RelocateResult relocateEpisodeFiles(Ani ani, Item item, String downloadPath) {
            relocateCalls.incrementAndGet();
            return relocateResult;
        }
    }

    @BeforeEach
    void setUp() {
        LocalStateCache.clear();
        prevFailThreshold = ConfigUtil.CONFIG.getOpenListFailThreshold();
        prevCooldownSeconds = ConfigUtil.CONFIG.getOpenListCooldownSeconds();
        ConfigUtil.CONFIG.setRenameTemplate(null)
                .setOvaRenameTemplate(null)
                .setDownloadPathTemplate("/115/动漫/转存/追番/${title}/Season ${seasonFormat}")
                .setOvaDownloadPathTemplate("/115/动漫/转存/剧场版/${title}")
                .setDownloadToolType("OpenList")
                .setRename(true).setFileExist(true)
                .setRenameDelYear(false).setRenameDelTmdbId(false);
        OpenListApi.resetRateLimitState();
    }

    @AfterEach
    void tearDown() {
        ConfigUtil.CONFIG.setDownloadToolType(null).setRename(null).setFileExist(null)
                .setDownloadPathTemplate(null).setOvaDownloadPathTemplate(null)
                .setOpenListFailThreshold(prevFailThreshold)
                .setOpenListCooldownSeconds(prevCooldownSeconds);
        LocalStateCache.clear();
        OpenListApi.resetRateLimitState();
    }

    private static Ani ani() {
        return new Ani().setId("ani-v3").setTitle("测试番剧").setSeason(1).setOva(false)
                .setMediaType(null).setOffset(0).setBgmUrl("https://bgm.tv/subject/1")
                .setThemoviedbName("").setNamingVersion(2)
                .setCustomRenameTemplateEnable(false).setCustomEpisode(false)
                .setReleaseDate(new Date(116, 9, 21));
    }

    private static Item item(double episode) {
        return new Item().setReName("测试番剧 S01E0" + (int) episode)
                .setEpisode(episode).setInfoHash("hash-v3").setMaster(true).setSubgroup("测试字幕组");
    }

    /** 把"本集已存在于网盘"这件事写进本地快照（不产生任何网盘请求）。 */
    private void seedSnapshotWith(Ani ani, String episodeFileName) throws Exception {
        String downloadPath = downloadService.getDownloadPath(ani);
        LocalStateCache.getOrBuild(ani, downloadPath, LocalStateCache.Source.CLOUD_API, () -> {
            Set<String> index = new HashSet<>();
            RenameUtil.addFileToEpisodeIndex(index, episodeFileName, false);
            return LocalStateCache.Loaded.of(index, true);
        });
    }

    // ---------------- 1. 已确认条目零请求（D4）----------------

    @Test
    @DisplayName("快照已确认本集存在 → 判定零网盘请求，不再每轮对账")
    void snapshot_hit_answers_without_any_network_request() throws Exception {
        Ani ani = ani();
        Item item = item(1.0);
        seedSnapshotWith(ani, "测试番剧 S01E01.mkv");

        CountingOffline offline = new CountingOffline();
        DownloadService.PresenceDecision decision = downloadService.resolveOpenListPresence(
                ani, item, item.getReName(), downloadService.getDownloadPath(ani), offline, Set.of());

        assertEquals(DownloadService.Presence.EXISTS, decision.presence(),
                "快照已确认本集存在，应直接采信");
        assertEquals(0, offline.relocateCalls.get(),
                "已确认条目必须零请求：每轮对每条已确认的集重做一次归位对账，订阅一多就是整批放大");
    }

    @Test
    @DisplayName("没有快照时必须真的对账，不能凭空说「存在」")
    void snapshot_miss_falls_back_to_relocate() {
        Ani ani = ani();
        Item item = item(2.0);
        CountingOffline offline = new CountingOffline();
        offline.relocateResult = OfflineDownloader.RelocateResult.ALREADY_AT_TOP;

        DownloadService.PresenceDecision decision = downloadService.resolveOpenListPresence(
                ani, item, item.getReName(), downloadService.getDownloadPath(ani), offline, Set.of());

        assertEquals(1, offline.relocateCalls.get(), "没有快照就必须真的对账一次");
        assertEquals(DownloadService.Presence.EXISTS, decision.presence());
    }

    // ---------------- 2. 查不到 ≠ 确认没有 ----------------

    @Test
    @DisplayName("对账 UNVERIFIABLE 只能是「存疑」，绝不能变成 ABSENT")
    void unverifiable_relocate_never_reads_as_confirmed_absent() {
        Ani ani = ani();
        Item item = item(3.0);
        CountingOffline offline = new CountingOffline();
        offline.relocateResult = OfflineDownloader.RelocateResult.UNVERIFIABLE;

        DownloadService.PresenceDecision decision = downloadService.resolveOpenListPresence(
                ani, item, item.getReName(), downloadService.getDownloadPath(ani), offline, Set.of());

        assertEquals(DownloadService.Presence.UNVERIFIABLE, decision.presence(),
                "对账没跑完只能说「查不清」");
        assertNotEquals(DownloadService.Presence.ABSENT, decision.presence(),
                "判成 ABSENT 就是唯一允许删记录重下的路径——一次网盘抖动会毁掉整季记录");
    }

    @Test
    @DisplayName("熔断冷却期：一个请求都不发，直接「存疑（冷却中）」")
    void cooldown_short_circuits_without_any_request() {
        ConfigUtil.CONFIG.setOpenListFailThreshold(1).setOpenListCooldownSeconds(60);
        // 走真实的失败路径触发熔断（127.0.0.1:1 必然连接被拒），而不是直接改静态状态——
        // 这样"冷却是怎么形成的"这件事本身也被覆盖到了
        OpenListApi api = new OpenListApi();
        api.setConfig(new Config()
                .setDownloadToolHost("http://127.0.0.1:1")
                .setDownloadToolPassword("mock-token")
                .setProvider("115 Cloud"));
        assertThrows(RuntimeException.class, () -> api.fsListStrict("/any-path", true));
        assertTrue(OpenListApi.isListingCoolingDown(), "前置条件：阈值 1 时一次失败即应进入冷却");

        Ani ani = ani();
        Item item = item(4.0);
        CountingOffline offline = new CountingOffline();
        DownloadService.PresenceDecision decision = downloadService.resolveOpenListPresence(
                ani, item, item.getReName(), downloadService.getDownloadPath(ani), offline, Set.of());

        assertEquals(DownloadService.Presence.UNVERIFIABLE, decision.presence());
        assertEquals(DownloadService.UnknownReason.COOLDOWN, decision.reason(),
                "冷却必须与「网盘坏了」区分开：一个是等一会儿会自愈，一个是需要排查");
        assertEquals(0, offline.relocateCalls.get(), "冷却期连对账都不该发起");
    }

    // ---------------- 3. 「确认没有」必须跨时间 ----------------

    @Test
    @DisplayName("首次判「没有」只登记不确认：一次抖动不许演变成删记录重下")
    void first_absence_is_never_confirmed_in_one_shot() {
        Ani ani = ani();
        Item item = item(5.0);
        CountingOffline offline = new CountingOffline(); // NOT_FOUND + 兜底空索引 → 组合为 ABSENT

        DownloadService.PresenceDecision decision = downloadService.resolveOpenListPresence(
                ani, item, item.getReName(), downloadService.getDownloadPath(ani), offline, Set.of());

        assertEquals(DownloadService.Presence.UNVERIFIABLE, decision.presence(),
                "「确认没有」必须跨时间确认（网盘目录缓存可达 1 小时），一次判没有不许删记录");
        assertEquals(DownloadService.UnknownReason.ABSENCE_UNCONFIRMED, decision.reason());
    }

    @Test
    @DisplayName("跨时间确认：间隔前不认、间隔后才认")
    void absence_confirmation_requires_cross_time() {
        String key = "ani-v3|hash-v3|测试番剧 S01E06";
        long t0 = 1_000_000L;

        assertFalse(DownloadService.absenceConfirmed(key, t0), "第一次看到只登记，不许确认");
        assertFalse(DownloadService.absenceConfirmed(key, t0 + DownloadService.ABSENT_CONFIRM_INTERVAL_MS - 1),
                "还没跨过间隔就不算确认");
        assertTrue(DownloadService.absenceConfirmed(key, t0 + DownloadService.ABSENT_CONFIRM_INTERVAL_MS),
                "跨过间隔（刻意比 Alist 默认 1 小时目录缓存更长）才算确认");
    }
}
