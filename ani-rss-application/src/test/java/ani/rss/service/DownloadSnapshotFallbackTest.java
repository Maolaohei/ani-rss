package ani.rss.service;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.service.DownloadService.Presence;
import ani.rss.service.DownloadService.PresenceDecision;
import ani.rss.service.DownloadService.UnknownReason;
import ani.rss.util.other.ConfigUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「网盘列举失败」时的快照兜底：<b>只信"有"，不信"没有"</b>。
 * <p>
 * 线上现象：上游一跳抖动（TLS 握手超时）时，<b>整批已下载过的集</b>全部变成"无法确认"，
 * 日志刷满"网盘不可用"——而它们其实早就在订阅级快照里写着"存在"。事实已经在手，
 * 就不该因为一次列举失败改口。
 * <p>
 * 反向约束同样重要：快照<b>不能</b>把"无法确认"变成"确认没有"——那会删掉种子记录并重新下单，
 * 正是 2026-09-18 事故的成因。
 */
class DownloadSnapshotFallbackTest {

    private final DownloadService downloadService = new DownloadService();

    @BeforeEach
    void setUp() {
        LocalStateCache.clear();
        ConfigUtil.CONFIG.setDownloadPathTemplate("/tmp/ani-rss-snapshot-test");
    }

    @AfterEach
    void tearDown() {
        LocalStateCache.clear();
        ConfigUtil.CONFIG.setDownloadPathTemplate(null);
    }

    private Ani ani() {
        // bgmUrl 必填：getDownloadPath → BgmUtil.getSubjectId 会断言它
        return new Ani().setId("ani-snapshot").setTitle("某番").setSeason(1)
                .setBgmUrl("https://bgm.tv/subject/12345")
                .setMediaType("tv").setOffset(0);
    }

    private void primeSnapshot(Ani ani, String... keys) throws Exception {
        LocalStateCache.getOrBuild(ani, downloadService.getDownloadPath(ani),
                LocalStateCache.Source.CLOUD_API,
                () -> LocalStateCache.Loaded.of(Set.of(keys), true));
    }

    private static Item item(double episode) {
        return new Item().setReName("某番 S01E0" + (int) episode).setEpisode(episode);
    }

    @Test
    @DisplayName("快照里已确认存在 → 无法确认改判「已存在」，不再显示存疑")
    void snapshot_rescues_unverifiable_to_exists() throws Exception {
        Ani ani = ani();
        primeSnapshot(ani, "1:1.0");

        PresenceDecision decision = downloadService.unverifiableOrSnapshot(
                ani, item(1.0), UnknownReason.VERIFY_FAILED);

        assertEquals(Presence.EXISTS, decision.presence());
        assertEquals(UnknownReason.NONE, decision.reason());
    }

    @Test
    @DisplayName("快照里没有这一集 → 仍是「无法确认」，绝不改判「确认没有」")
    void snapshot_never_turns_unverifiable_into_absent() throws Exception {
        Ani ani = ani();
        primeSnapshot(ani, "1:1.0");

        PresenceDecision decision = downloadService.unverifiableOrSnapshot(
                ani, item(9.0), UnknownReason.VERIFY_FAILED);

        assertEquals(Presence.UNVERIFIABLE, decision.presence(), "查不到只能说明没查成");
        assertEquals(UnknownReason.VERIFY_FAILED, decision.reason(), "成因要原样传下去");
    }

    @Test
    @DisplayName("没有快照 → 仍是「无法确认」")
    void without_snapshot_stays_unverifiable() {
        PresenceDecision decision = downloadService.unverifiableOrSnapshot(
                ani(), item(1.0), UnknownReason.COOLDOWN);

        assertEquals(Presence.UNVERIFIABLE, decision.presence());
        assertEquals(UnknownReason.COOLDOWN, decision.reason());
    }

    @Test
    @DisplayName("快照被截断（complete=false）时仍能证明「存在」")
    void truncated_snapshot_still_proves_existence() throws Exception {
        Ani ani = ani();
        LocalStateCache.getOrBuild(ani, downloadService.getDownloadPath(ani),
                LocalStateCache.Source.CLOUD_API,
                () -> LocalStateCache.Loaded.of(Set.of("1:2.0"), false));

        PresenceDecision decision = downloadService.unverifiableOrSnapshot(
                ani, item(2.0), UnknownReason.VERIFY_FAILED);

        assertEquals(Presence.EXISTS, decision.presence(),
                "截断的索引只能确认存在、不能断言不存在，而这里恰好只需要「存在」这一半能力");
    }
}
