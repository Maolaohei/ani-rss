package ani.rss.util.other;

import ani.rss.entity.Ani;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UxEnhancementPolicyTest {

    @Test
    void humanize_duplicate_and_timeout() {
        var d = TaskFailureHumanizer.humanize("errcode 10008 任务已存在");
        assertEquals(TaskFailureHumanizer.ErrorCode.DUPLICATE_TASK, d.code());
        assertTrue(d.suggestion().contains("残留") || d.suggestion().contains("等待"));

        var t = TaskFailureHumanizer.humanize("Show 超过离线超时 60 分钟");
        assertEquals(TaskFailureHumanizer.ErrorCode.OFFLINE_TIMEOUT, t.code());
    }

    @Test
    void humanize_auth_and_bad_torrent() {
        assertEquals(TaskFailureHumanizer.ErrorCode.AUTH,
                TaskFailureHumanizer.humanize("qBittorrent ApiKey unauthorized 401").code());
        assertEquals(TaskFailureHumanizer.ErrorCode.BAD_TORRENT,
                TaskFailureHumanizer.humanize("无法解析 infoHash，拒绝提交").code());
        assertEquals(TaskFailureHumanizer.ErrorCode.OPENLIST_FAIL,
                TaskFailureHumanizer.humanize("离线下载未完成（OpenList 返回失败，非坏种）").code());
    }

    @Test
    void formatNotify_includes_name() {
        String n = TaskFailureHumanizer.formatNotify("碧蓝之海", "10008");
        assertTrue(n.startsWith("碧蓝之海"));
        assertTrue(n.contains("重复") || n.contains("已存在"));
    }

    @Test
    void tempDir_force_when_final_ready() {
        var d = TempDirResidualPolicy.decide("Show S01E03", true, true, false, Set.of());
        assertEquals(TempDirResidualPolicy.Action.FORCE_CLEAN, d.action());
    }

    @Test
    void tempDir_protect_active() {
        var d = TempDirResidualPolicy.decide("Show S01E03", true, true, false, Set.of("Show S01E03"));
        assertEquals(TempDirResidualPolicy.Action.PROTECT_ACTIVE, d.action());
    }

    @Test
    void tempDir_junk_only() {
        var d = TempDirResidualPolicy.decide("tmp", false, false, true, Set.of());
        assertEquals(TempDirResidualPolicy.Action.JUNK_CLEAN, d.action());
    }

    @Test
    void tempDir_keep_media_without_final() {
        var d = TempDirResidualPolicy.decide("nested", false, true, false, Set.of());
        assertEquals(TempDirResidualPolicy.Action.KEEP, d.action());
    }

    @Test
    void wash_preview_matches_episode_case_insensitive() {
        List<WashPreview.Candidate> c = WashPreview.preview(
                "Show S01E03",
                List.of("Other S01E03 1080p", "Show S01E02"),
                List.of("Show.s01e03.mkv", "readme.txt"),
                true, true, false);
        assertEquals(2, c.size());
        assertTrue(c.stream().anyMatch(x -> x.kind().equals("torrent")));
        assertTrue(c.stream().anyMatch(x -> x.kind().equals("file")));
    }

    @Test
    void wash_preview_disabled_when_coexist_or_no_delete() {
        assertTrue(WashPreview.preview("Show S01E03", List.of("x S01E03"), List.of(), true, true, true).isEmpty());
        assertTrue(WashPreview.preview("Show S01E03", List.of("x S01E03"), List.of(), true, false, false).isEmpty());
        assertTrue(WashPreview.preview("Show S01E03", List.of("x S01E03"), List.of(), false, true, false).isEmpty());
    }

    @Test
    void health_completed_and_omit() {
        Ani done = new Ani().setEnable(true).setCurrentEpisodeNumber(12).setTotalEpisodeNumber(12);
        assertEquals("completed", SubscriptionHealth.compute(done, 0, System.currentTimeMillis()).level());

        Ani omit = new Ani().setEnable(true).setCurrentEpisodeNumber(3).setTotalEpisodeNumber(12)
                .setLastDownloadTime(System.currentTimeMillis());
        var s = SubscriptionHealth.compute(omit, 3, System.currentTimeMillis());
        assertTrue(s.score() < 100);
        assertTrue(s.reasons().stream().anyMatch(r -> r.contains("漏集")));
    }

    @Test
    void health_stale_download() {
        long now = 1_700_000_000_000L;
        Ani stale = new Ani().setEnable(true).setCurrentEpisodeNumber(1).setTotalEpisodeNumber(12)
                .setLastDownloadTime(now - java.util.concurrent.TimeUnit.DAYS.toMillis(22));
        var s = SubscriptionHealth.compute(stale, 0, now);
        // 仅超期未下载：100-30=70 → warn
        assertEquals("warn", s.level());
        assertTrue(s.score() <= 70);

        Ani worse = new Ani().setEnable(true).setCurrentEpisodeNumber(1).setTotalEpisodeNumber(12)
                .setLastDownloadTime(now - java.util.concurrent.TimeUnit.DAYS.toMillis(22))
                .setProcrastinating(true);
        var s2 = SubscriptionHealth.compute(worse, 3, now);
        assertEquals("bad", s2.level());
    }

    @Test
    void failed_queue_record_dedupes() {
        FailedDownloadQueue.resetForTest();
        FailedDownloadQueue.record("a1", "Show", "Show S01E01", "ABCDEF", "10008");
        FailedDownloadQueue.record("a1", "Show", "Show S01E01", "abcdef", "10008 again");
        assertEquals(1, FailedDownloadQueue.list().size());
        assertEquals(2, FailedDownloadQueue.list().get(0).getAttempts());
        assertTrue(FailedDownloadQueue.remove(FailedDownloadQueue.list().get(0).getId()));
        assertEquals(0, FailedDownloadQueue.list().size());
    }

    @Test
    void health_uses_cached_omit_count() {
        Ani ani = new Ani().setEnable(true).setCurrentEpisodeNumber(3).setTotalEpisodeNumber(12)
                .setOmit(true).setOva(false)
                .setOmitCount(2).setOmitCheckedAt(System.currentTimeMillis())
                .setLastDownloadTime(System.currentTimeMillis());
        int omit = SubscriptionHealth.cachedOmitCount(ani, true);
        assertEquals(2, omit);
        var s = SubscriptionHealth.compute(ani, omit, System.currentTimeMillis());
        assertTrue(s.reasons().stream().anyMatch(r -> r.contains("漏集")));
        assertTrue(s.score() <= 80);
    }

    @Test
    void health_cached_omit_respects_flags() {
        Ani ani = new Ani().setEnable(true).setOmit(false).setOmitCount(5);
        assertEquals(0, SubscriptionHealth.cachedOmitCount(ani, true));
        ani.setOmit(true).setOva(true);
        assertEquals(0, SubscriptionHealth.cachedOmitCount(ani, true));
        ani.setOva(false);
        assertEquals(0, SubscriptionHealth.cachedOmitCount(ani, false));
        assertEquals(5, SubscriptionHealth.cachedOmitCount(ani, true));
    }

    @Test
    void remember_omit_writes_fields() {
        Ani ani = new Ani();
        long now = 1_700_000_000_000L;
        SubscriptionHealth.rememberOmit(ani, 3, now);
        assertEquals(3, ani.getOmitCount());
        assertEquals(now, ani.getOmitCheckedAt());
        SubscriptionHealth.rememberOmit(ani, -1, now + 1);
        assertEquals(0, ani.getOmitCount());
    }

    @Test
    void tempDir_protect_collection_name_differs_from_rename() {
        // 合集临时目录用源标题，与 reName 不同时仍应保护
        var d = TempDirResidualPolicy.decide("Collection Source Title", false, true, false,
                Set.of("Collection Source Title"));
        assertEquals(TempDirResidualPolicy.Action.PROTECT_ACTIVE, d.action());
    }

    @Test
    void failed_queue_key_prefers_hash() {
        assertEquals("a1:abcdef", FailedDownloadQueue.keyOf("a1", "ABCDEF", "Show S01E01"));
        assertEquals("a1:Show S01E01", FailedDownloadQueue.keyOf("a1", "", "Show S01E01"));
    }
}
