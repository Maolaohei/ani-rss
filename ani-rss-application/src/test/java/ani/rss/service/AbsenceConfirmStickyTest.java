package ani.rss.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static ani.rss.service.DownloadService.ABSENT_CONFIRM_INTERVAL_MS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 回归闸门：「确认没有」一旦成立，就必须**持续**成立。
 * <p>
 * v3 的写法是
 * <pre>{@code if (absenceConfirmed(key)) { clearAbsence(key); return decision; }}</pre>
 * 于是确认成立的那一刻同时把计时清零：本轮放行一次动作，但只要动作那一侧没真的删记录重下
 * （OpenList 分支当时就是直接 {@code return SKIP}），下一轮又从零开始等 62 分钟。
 * 线上表现是 24 小时刷出 112 条「判为没有文件但尚未跨时间确认」+ 112 条「缺席未确认」
 * + 52 条「按 v3 保留记录」，占全部 WARN/ERROR 的 42.6%，那几集永远停在原地。
 * <p>
 * 本用例把「确认后仍确认」固化下来：谁再把 {@code clearAbsence} 塞回判定里，这里立刻红。
 */
class AbsenceConfirmStickyTest {

    private static String key() {
        return "ani-" + UUID.randomUUID() + "|hash|某番 S02E-21";
    }

    private static DownloadService.PresenceDecision absent() {
        return DownloadService.PresenceDecision.of(DownloadService.Presence.ABSENT,
                DownloadService.UnknownReason.NONE);
    }

    @Test
    @DisplayName("首次看到缺席：不放行，改成「存疑」并给出成因文案")
    void first_sighting_is_unverifiable() {
        DownloadService.PresenceDecision d =
                DownloadService.absenceOrUnconfirmed(key(), "某番 S02E-21", absent(), 1_000L);
        assertEquals(DownloadService.Presence.UNVERIFIABLE, d.presence());
        assertEquals(DownloadService.UnknownReason.ABSENCE_UNCONFIRMED, d.reason());
    }

    @Test
    @DisplayName("间隔内仍不放行（网盘列举缓存还没过期）")
    void within_interval_still_unverifiable() {
        String key = key();
        DownloadService.absenceOrUnconfirmed(key, "x", absent(), 0L);
        DownloadService.PresenceDecision d = DownloadService.absenceOrUnconfirmed(
                key, "x", absent(), ABSENT_CONFIRM_INTERVAL_MS - 1);
        assertNotEquals(DownloadService.Presence.ABSENT, d.presence(), "几分钟内的两次「没找到」说明不了问题");
    }

    @Test
    @DisplayName("跨过间隔：放行 ABSENT，交给调用方删记录重下")
    void after_interval_confirms() {
        String key = key();
        DownloadService.absenceOrUnconfirmed(key, "x", absent(), 0L);
        DownloadService.PresenceDecision d = DownloadService.absenceOrUnconfirmed(
                key, "x", absent(), ABSENT_CONFIRM_INTERVAL_MS);
        assertEquals(DownloadService.Presence.ABSENT, d.presence());
        assertEquals(DownloadService.UnknownReason.NONE, d.reason());
    }

    @Test
    @DisplayName("★ 确认之后继续确认：判定本身不清零计时（v3 死循环的回归点）")
    void confirmation_is_sticky() {
        String key = key();
        DownloadService.absenceOrUnconfirmed(key, "x", absent(), 0L);
        assertEquals(DownloadService.Presence.ABSENT,
                DownloadService.absenceOrUnconfirmed(key, "x", absent(), ABSENT_CONFIRM_INTERVAL_MS).presence());

        assertEquals(DownloadService.Presence.ABSENT, DownloadService.absenceOrUnconfirmed(
                        key, "x", absent(), ABSENT_CONFIRM_INTERVAL_MS + 1).presence(),
                "确认过一次后紧接着再来一轮，不许退回「尚未跨时间确认」");
        assertEquals(DownloadService.Presence.ABSENT, DownloadService.absenceOrUnconfirmed(
                        key, "x", absent(), ABSENT_CONFIRM_INTERVAL_MS + TimeUnit.HOURS.toMillis(6)).presence(),
                "六小时后仍应保持确认：把清零放回判定里，线上就会重新开始等 62 分钟");
    }

    @Test
    @DisplayName("放行时原样返回调用方给的判定（不另造对象、不改写成 ABSENT）")
    void passes_through_the_callers_decision() {
        String key = key();
        DownloadService.absenceOrUnconfirmed(key, "x", absent(), 0L);
        DownloadService.PresenceDecision exists = DownloadService.PresenceDecision.of(
                DownloadService.Presence.EXISTS, DownloadService.UnknownReason.NONE);
        assertEquals(exists, DownloadService.absenceOrUnconfirmed(key, "x", exists, ABSENT_CONFIRM_INTERVAL_MS));
    }
}
