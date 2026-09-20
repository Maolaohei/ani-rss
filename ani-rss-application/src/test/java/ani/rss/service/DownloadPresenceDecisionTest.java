package ani.rss.service;

import ani.rss.download.OfflineDownloader;
import ani.rss.service.DownloadService.Presence;
import ani.rss.service.DownloadService.UnknownReason;
import cn.hutool.core.util.StrUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「本地状态」三态判定的决策表。
 * <p>
 * 这一格的价值用 2026-09-18 的线上日志说明最清楚：14:29:23 打出
 * 「归位对账未找到且兜底检查无文件，清理过期种子记录并重新下载」，
 * 14:29:27 立刻打出「本地已存在 / 本地文件已存在」——同一集四分钟内两个相反结论，
 * 说明前一次判定纯粹是"没查成"被读成了"确认没有"。
 * <p>
 * 后果是整季种子记录被删并重新下单，而重下的产物又和原有文件撞名，
 * 触发重命名冲突后判失败、清 pending、下一轮再重下 —— 死循环，每轮往网盘多堆一份文件。
 * <p>
 * 因此本用例钉住三件事：
 * <ol>
 *   <li>{@code NOT_FOUND + UNVERIFIABLE} 必须是 UNVERIFIABLE，<b>不是</b> ABSENT
 *       （ABSENT 是唯一允许删记录的判定）；</li>
 *   <li>只有"对账没找到 + 兜底确认没有"才允许 ABSENT；</li>
 *   <li>UNVERIFIABLE 绝不能被算作"记录有效"而跳过下载之外的任何破坏性动作。</li>
 * </ol>
 */
class DownloadPresenceDecisionTest {

    private static final OfflineDownloader.RelocateResult NOT_FOUND = OfflineDownloader.RelocateResult.NOT_FOUND;
    private static final OfflineDownloader.RelocateResult UNVERIFIABLE = OfflineDownloader.RelocateResult.UNVERIFIABLE;
    private static final OfflineDownloader.RelocateResult RELOCATED = OfflineDownloader.RelocateResult.RELOCATED;
    private static final OfflineDownloader.RelocateResult ALREADY_AT_TOP = OfflineDownloader.RelocateResult.ALREADY_AT_TOP;

    // ---------------- 事故那一格 ----------------

    /**
     * 核心回归：对账没找到 + 兜底也"查不到"（网盘不可用）→ 只能回报「无法判断」。
     * 若这里返回 ABSENT，调用方就会删掉种子记录并重新下单。
     */
    @Test
    @DisplayName("对账没找到 + 兜底查不到 → 无法判断（不得删记录重下）")
    void not_found_plus_unverifiable_stays_unverifiable() {
        assertEquals(Presence.UNVERIFIABLE, DownloadService.combineFallback(NOT_FOUND, Presence.UNVERIFIABLE));
    }

    @Test
    @DisplayName("对账本身无法判断 → 无法判断（不采信任何兜底结论）")
    void unverifiable_relocate_short_circuits() {
        assertEquals(Presence.UNVERIFIABLE, DownloadService.combineFallback(UNVERIFIABLE, Presence.EXISTS));
        assertEquals(Presence.UNVERIFIABLE, DownloadService.combineFallback(UNVERIFIABLE, Presence.ABSENT));
    }

    /** 兜底结果缺失（异常路径）同样不能当成"确认没有"。 */
    @Test
    @DisplayName("兜底结果缺失 → 无法判断，不能默认成「没有」")
    void missing_fallback_is_not_absent() {
        assertEquals(Presence.UNVERIFIABLE, DownloadService.combineFallback(NOT_FOUND, null));
    }

    // ---------------- 允许清理记录的那一格 ----------------

    @Test
    @DisplayName("对账没找到 + 兜底确认没有 → 确认没有（唯一允许清理记录的情形）")
    void only_confirmed_absence_allows_cleanup() {
        assertEquals(Presence.ABSENT, DownloadService.combineFallback(NOT_FOUND, Presence.ABSENT));
    }

    @Test
    @DisplayName("对账没找到但兜底发现文件 → 视为存在，不重下")
    void fallback_exists_wins() {
        assertEquals(Presence.EXISTS, DownloadService.combineFallback(NOT_FOUND, Presence.EXISTS));
    }

    // ---------------- 对账已给结论时不再兜底 ----------------

    @Test
    @DisplayName("归位成功 / 本就在顶层 → 存在")
    void relocate_success_is_exists() {
        assertEquals(Presence.EXISTS, DownloadService.combineFallback(RELOCATED, Presence.ABSENT));
        assertEquals(Presence.EXISTS, DownloadService.combineFallback(ALREADY_AT_TOP, Presence.ABSENT));
    }

    // ---------------- 「记录有效」的口径 ----------------

    /**
     * 删除动作只在 {@code !presence.valid()} 时执行，而 UNVERIFIABLE / RETRY_EXHAUSTED
     * 都会在到达该分支之前 continue。这里钉住"哪些算有效"，避免有人把 UNVERIFIABLE
     * 也划进 valid（那会让无法确认的集被当作已下载而永久跳过）。
     */
    @Test
    @DisplayName("只有 EXISTS / VALID 算「记录有效」，无法判断与重推耗尽都不算")
    void valid_means_record_can_be_trusted() {
        assertTrue(Presence.VALID.valid());
        assertTrue(Presence.EXISTS.valid());
        assertFalse(Presence.ABSENT.valid());
        assertFalse(Presence.UNVERIFIABLE.valid());
        assertFalse(Presence.RETRY_EXHAUSTED.valid());
    }

    // ---------------- 存疑成因必须可区分（2026-09-20） ----------------

    /**
     * 二次排查的卡点：三种完全不同的故障被同一句「网盘不可用」盖住，用户看到
     * "都显示网盘不可用"，既分不清"等 60s 冷却"和"网盘坏了"，也看不出
     * "下载路径配错"。文案本身就是可排查性，故在此固化。
     */
    @Test
    @DisplayName("存疑成因文案必须可区分：冷却 ≠ 列举失败")
    void unknown_reason_texts_are_distinguishable() {
        String cooldown = DownloadService.unknownReasonText(UnknownReason.COOLDOWN);
        String verifyFailed = DownloadService.unknownReasonText(UnknownReason.VERIFY_FAILED);
        assertNotEquals(cooldown, verifyFailed, "冷却与列举失败不能共用一句话");
        assertTrue(cooldown.contains("冷却"), cooldown);
        assertTrue(verifyFailed.contains("列举失败"), verifyFailed);
        for (UnknownReason reason : UnknownReason.values()) {
            assertFalse(StrUtil.isBlank(DownloadService.unknownReasonText(reason)),
                    "每种成因都要有可读文案，空白等于把用户又丢回「原因未知」: " + reason);
        }
    }

    /**
     * 判定结果必须带成因，且缺成因时不能变成 {@code null}
     * （调用方直接把它交给计数与文案，null 会静默丢信息）。
     */
    @Test
    @DisplayName("PresenceDecision 缺成因时归一成 NONE")
    void presence_decision_normalises_null_reason() {
        assertEquals(UnknownReason.NONE,
                DownloadService.PresenceDecision.of(Presence.UNVERIFIABLE, null).reason());
        assertTrue(DownloadService.PresenceDecision.of(Presence.UNVERIFIABLE, UnknownReason.COOLDOWN)
                .unverifiable());
        assertFalse(DownloadService.PresenceDecision.of(Presence.EXISTS, UnknownReason.NONE)
                .unverifiable());
    }
}
