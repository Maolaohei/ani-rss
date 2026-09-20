package ani.rss.task;

import ani.rss.service.DownloadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F5-6：本轮本地状态分布统计。
 * <p>
 * 语义是<b>本轮处置结果</b>，不是展示层的三态：
 * {@code EXISTS} = 判定已有、跳过下载；{@code UNKNOWN} = 无法确认、跳过下载；
 * {@code ABSENT} = 确认没有、已下发下载。
 * <p>
 * 用途是回答"这一轮为什么什么都没下"——此前只能靠翻日志，而日志面板不支持关键词检索。
 */
class RoundLocalStateSummaryTest {

    @AfterEach
    void tearDown() {
        RssTask.RssJobState.localExists.set(0);
        RssTask.RssJobState.localUnknown.set(0);
        RssTask.RssJobState.localAbsent.set(0);
        RssTask.RssJobState.unknownCooldown.set(0);
        RssTask.RssJobState.unknownVerifyFailed.set(0);
    }

    @Test
    void counters_accumulate_by_state() {
        RssTask.countRoundLocalState(DownloadService.LocalState.EXISTS);
        RssTask.countRoundLocalState(DownloadService.LocalState.EXISTS);
        RssTask.countRoundLocalState(DownloadService.LocalState.UNKNOWN);
        RssTask.countRoundLocalState(DownloadService.LocalState.ABSENT);
        RssTask.countRoundLocalState(DownloadService.LocalState.ABSENT);
        RssTask.countRoundLocalState(DownloadService.LocalState.ABSENT);

        Map<String, Integer> summary = RssTask.getRoundLocalStateSummary();
        assertEquals(2, summary.get("exists").intValue());
        assertEquals(1, summary.get("unknown").intValue());
        assertEquals(3, summary.get("absent").intValue());
    }

    @Test
    void null_state_is_ignored() {
        RssTask.countRoundLocalState(null);
        Map<String, Integer> summary = RssTask.getRoundLocalStateSummary();
        assertEquals(0, summary.get("exists").intValue());
        assertEquals(0, summary.get("unknown").intValue());
        assertEquals(0, summary.get("absent").intValue());
    }

    @Test
    void starting_a_new_round_clears_counters() {
        RssTask.countRoundLocalState(DownloadService.LocalState.EXISTS);
        RssTask.countRoundLocalState(DownloadService.LocalState.ABSENT);
        RssTask.RssJobState.resetRoundState(RssTask.JobSource.PERIODIC, "启动中");

        Map<String, Integer> summary = RssTask.getRoundLocalStateSummary();
        assertEquals(0, summary.get("exists").intValue(), "新一轮必须从 0 开始，否则分布会跨轮累积失真");
        assertEquals(0, summary.get("unknown").intValue());
        assertEquals(0, summary.get("absent").intValue());
    }

    /**
     * 「冷却」必须与「列举失败」分开计数：前者的对策是等一会儿，后者要去查网盘。
     * 合并成一格就回到了"都显示网盘不可用"那个排查死角。
     */
    @Test
    void unknown_reasons_are_counted_separately() {
        RssTask.countRoundUnknownReason(DownloadService.UnknownReason.COOLDOWN);
        RssTask.countRoundUnknownReason(DownloadService.UnknownReason.COOLDOWN);
        RssTask.countRoundUnknownReason(DownloadService.UnknownReason.VERIFY_FAILED);
        RssTask.countRoundUnknownReason(DownloadService.UnknownReason.ABSENCE_UNCONFIRMED);

        Map<String, Integer> reasons = RssTask.getRoundUnknownReasonSummary();
        assertEquals(2, reasons.get("cooldown").intValue());
        assertEquals(1, reasons.get("verifyFailed").intValue());
        assertEquals(1, reasons.get("absenceUnconfirmed").intValue());
    }

    @Test
    void unknown_reason_counters_reset_with_round() {
        RssTask.countRoundUnknownReason(DownloadService.UnknownReason.COOLDOWN);
        RssTask.countRoundUnknownReason(DownloadService.UnknownReason.VERIFY_FAILED);
        RssTask.RssJobState.resetRoundState(RssTask.JobSource.PERIODIC, "启动中");

        Map<String, Integer> reasons = RssTask.getRoundUnknownReasonSummary();
        assertEquals(0, reasons.get("cooldown").intValue(), "存疑成因不能跨轮累积");
        assertEquals(0, reasons.get("verifyFailed").intValue());
    }
}
