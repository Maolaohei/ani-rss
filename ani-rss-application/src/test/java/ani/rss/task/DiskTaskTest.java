package ani.rss.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 磁盘预警的档位与阈值收敛。
 * <p>
 * 去重逻辑依赖"档位只升不降"：同一档位内不重复推送，跨档位才升级提醒，
 * 使用率回落后清态以便下次越线能重新提醒。
 */
class DiskTaskTest {

    @AfterEach
    void tearDown() {
        DiskTask.resetForTest();
    }

    @Test
    void tier_escalates_with_usage() {
        assertEquals(0, DiskTask.tierOf(85.0, 85));
        assertEquals(0, DiskTask.tierOf(89.9, 85));
        assertEquals(1, DiskTask.tierOf(90.0, 85));
        assertEquals(1, DiskTask.tierOf(94.9, 85));
        assertEquals(2, DiskTask.tierOf(95.0, 85));
        assertEquals(2, DiskTask.tierOf(100.0, 85));
    }

    @Test
    void tier_is_relative_to_configured_threshold() {
        // 阈值 90 时，90 仍是第 0 档，95 才是第 1 档
        assertEquals(0, DiskTask.tierOf(90.0, 90));
        assertEquals(1, DiskTask.tierOf(95.0, 90));
        assertEquals(2, DiskTask.tierOf(100.0, 90));
    }

    @Test
    void clampPercent_keeps_sane_range() {
        assertEquals(50, DiskTask.clampPercent(0));
        assertEquals(50, DiskTask.clampPercent(-10));
        assertEquals(80, DiskTask.clampPercent(80));
        assertEquals(99, DiskTask.clampPercent(100));
        assertEquals(99, DiskTask.clampPercent(1000));
    }
}
