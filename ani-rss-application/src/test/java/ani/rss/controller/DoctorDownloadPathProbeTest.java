package ani.rss.controller;

import ani.rss.controller.DoctorController.DirProbe;
import ani.rss.controller.DoctorController.DirProbeResult;
import ani.rss.controller.DoctorController.DirVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自检「下载路径」在 OpenList 模式下的分层判定。
 * <p>
 * 原来它用本地 {@code File.exists()} 判一个网盘虚拟路径（{@code /115/动漫/转存/追番}）——
 * 永远不存在，于是每次自检都报「下载根目录尚不存在」，既没信息量，
 * 又把「挂载配错」和「还没下载过」说成同一件事。
 * <p>
 * 分层后三种情况各自有结论，且<b>没有一次网盘请求是白发的</b>：
 * 目录在 → ok；只是没创建 → ok；真探查不到 → warn（明确是"没查成"）。
 */
class DoctorDownloadPathProbeTest {

    private static DirProbeResult exists(int children) {
        return new DirProbeResult(DirProbe.EXISTS, children, "");
    }

    private static DirProbeResult missing() {
        return DirProbeResult.of(DirProbe.MISSING);
    }

    private static DirProbeResult failed(String reason) {
        return new DirProbeResult(DirProbe.FAILED, 0, reason);
    }

    private static final String PREFIX = "/115/动漫/转存/追番";
    private static final String MOUNT = "/115";

    @Test
    @DisplayName("目录存在 → ok，并给出直接子项数")
    void existing_dir_is_ok() {
        DirVerdict verdict = DoctorController.judgeOpenListPath(PREFIX, MOUNT, exists(7), null);
        assertEquals("ok", verdict.level());
        assertTrue(verdict.detail().contains("直接子项 7 个"), verdict.detail());
    }

    @Test
    @DisplayName("目录未创建但挂载点在 → ok（首次下载会自动创建）——这正是原来的噪音")
    void missing_dir_but_mount_exists_is_ok() {
        DirVerdict verdict = DoctorController.judgeOpenListPath(PREFIX, MOUNT, missing(), exists(3));
        assertEquals("ok", verdict.level());
        assertTrue(verdict.detail().contains("尚未创建"), verdict.detail());
    }

    @Test
    @DisplayName("目录与首段都不存在 → warn，且两种可能都摆出来")
    void missing_dir_and_mount_is_warn_with_both_readings() {
        DirVerdict verdict = DoctorController.judgeOpenListPath(PREFIX, MOUNT, missing(), missing());
        assertEquals("warn", verdict.level(), "首段不一定是挂载点，报 fail 会把「第一次用」误判成配错");
        assertTrue(verdict.detail().contains(MOUNT), verdict.detail());
        assertTrue(verdict.suggestion().contains("provider"), verdict.suggestion());
        assertTrue(verdict.suggestion().contains("自动创建"), verdict.suggestion());
    }

    @Test
    @DisplayName("探测失败 → warn，且明确是「没查成」不是「没有」")
    void probe_failure_is_warn_not_missing() {
        DirVerdict verdict = DoctorController.judgeOpenListPath(PREFIX, MOUNT,
                failed("fs/list 失败 code=500 … TLS handshake timeout"), null);
        assertEquals("warn", verdict.level());
        assertTrue(verdict.detail().contains("没查成"), verdict.detail());
        assertTrue(verdict.detail().contains("TLS handshake timeout"), verdict.detail());
    }

    @Test
    @DisplayName("首段探测失败时不猜「配错」，只说无法确认")
    void mount_probe_failure_is_warn() {
        DirVerdict verdict = DoctorController.judgeOpenListPath(PREFIX, MOUNT, missing(), failed("read timed out"));
        assertEquals("warn", verdict.level());
        assertTrue(verdict.detail().contains("无法确认"), verdict.detail());
    }

    // ---------------- 首段提取 ----------------

    @Test
    @DisplayName("取路径首段：/115/动漫/追番 → /115")
    void first_segment_is_the_mount_name() {
        assertEquals("/115", DoctorController.firstSegment("/115/动漫/转存/追番"));
        assertEquals("/115", DoctorController.firstSegment("/115"));
        assertEquals("/115", DoctorController.firstSegment("115/动漫"));
        assertEquals("/115", DoctorController.firstSegment("/115\\动漫"));
        assertNull(DoctorController.firstSegment(""));
        assertNull(DoctorController.firstSegment("/"));
        assertNull(DoctorController.firstSegment(null));
    }

    @Test
    @DisplayName("目录存在时不做第二次探测（mountProbe 允许为 null）")
    void no_second_probe_when_dir_exists() {
        DirVerdict verdict = DoctorController.judgeOpenListPath(PREFIX, MOUNT, exists(0), null);
        assertEquals("ok", verdict.level(), "目录就是空目录也算 ok，不该再去看首段");
        assertNotNull(verdict.detail());
    }
}
