package ani.rss.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static ani.rss.service.DownloadService.ABSENT_CONFIRM_INTERVAL_MS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「确认没有」必须跨时间确认。
 * <p>
 * Alist/OpenList 的目录列举<b>自带缓存</b>（存储驱动的"缓存过期时间"默认可达 1 小时）：
 * 刚下完 / 刚移动完去列目录经常列不到。若一次判 ABSENT 就删种子记录重下，
 * 代价是重复下载 + 与已落盘文件撞名——正是"看起来网盘里没文件"最容易踩的坑。
 * <p>
 * 本用例用合成时间固化这条闸门的边界（不真的等 30 分钟）。
 */
class AbsenceConfirmationTest {

    private static String key() {
        return "ani-" + UUID.randomUUID() + "|hash|某番 S01E01";
    }

    @Test
    @DisplayName("第一次看到缺席只登记，绝不确认")
    void first_sighting_never_confirms() {
        assertFalse(DownloadService.absenceConfirmed(key(), 1_000L));
    }

    @Test
    @DisplayName("间隔内再次看到仍不确认（网盘缓存还没过期）")
    void within_interval_still_unconfirmed() {
        String key = key();
        assertFalse(DownloadService.absenceConfirmed(key, 0L));
        assertFalse(DownloadService.absenceConfirmed(key, ABSENT_CONFIRM_INTERVAL_MS - 1),
                "只隔几秒/几分钟的两次「没找到」说明不了问题");
    }

    @Test
    @DisplayName("跨过间隔仍然缺席 → 才允许删记录重下")
    void after_interval_confirms() {
        String key = key();
        assertFalse(DownloadService.absenceConfirmed(key, 0L));
        assertTrue(DownloadService.absenceConfirmed(key, ABSENT_CONFIRM_INTERVAL_MS));
    }

    @Test
    @DisplayName("间隔必须长于网盘列举缓存（默认 1 小时），否则两次观察可能落在同一份过期缓存里")
    void interval_must_exceed_listing_cache() {
        assertTrue(ABSENT_CONFIRM_INTERVAL_MS > java.util.concurrent.TimeUnit.HOURS.toMillis(1),
                "实际 " + ABSENT_CONFIRM_INTERVAL_MS + "ms");
    }

    @Test
    @DisplayName("没有 key 时永不确认（宁可保留记录）")
    void blank_key_never_confirms() {
        assertFalse(DownloadService.absenceConfirmed("", 0L));
        assertFalse(DownloadService.absenceConfirmed(null, Long.MAX_VALUE));
    }
}
