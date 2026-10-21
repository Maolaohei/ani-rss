package ani.rss.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已有记录判定的动作映射。
 * <p>
 * 关键约束：UNVERIFIABLE / RETRY_EXHAUSTED 会返回 SKIP，不能计入“已存在”；
 * STANDBY 表示主 RSS 要替换备用占位，必须继续走正常下载流程。
 */
class ExistingRecordResultTest {
    @Test
    void only_confirmed_existing_counts() {
        assertTrue(DownloadService.ExistingRecordResult.EXISTS.countsAsExisting());
        assertFalse(DownloadService.ExistingRecordResult.SKIP.countsAsExisting());
        assertFalse(DownloadService.ExistingRecordResult.STANDBY.countsAsExisting());
        assertFalse(DownloadService.ExistingRecordResult.CONTINUE.countsAsExisting());
    }

    @Test
    void standby_continues_into_download_pipeline() {
        assertTrue(DownloadService.ExistingRecordResult.EXISTS.stopsCurrentItem());
        assertTrue(DownloadService.ExistingRecordResult.SKIP.stopsCurrentItem());
        assertFalse(DownloadService.ExistingRecordResult.STANDBY.stopsCurrentItem());
        assertFalse(DownloadService.ExistingRecordResult.CONTINUE.stopsCurrentItem());
    }
}
