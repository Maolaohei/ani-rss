package ani.rss.util.other;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 磁盘监控：路径解析与探测容错。
 * <p>
 * 重点是"不可测"必须与"空间充足"区分开——网络盘 / 未挂载时
 * getTotalSpace 会返回 0，若当成 0% 使用率就会静默漏报磁盘写满。
 */
class DiskMonitorUtilTest {

    @TempDir
    Path tempDir;

    @Test
    void staticRoot_takes_prefix_before_placeholder() {
        assertEquals("/media/anime", DiskMonitorUtil.staticRoot("/media/anime/${title}"));
        assertEquals("D:/media/anime", DiskMonitorUtil.staticRoot("D:/media/anime/${title}/S${season}"));
        // 无占位符：整串即根
        assertEquals("/media/anime", DiskMonitorUtil.staticRoot("/media/anime"));
        // 尾部斜杠应被去掉
        assertEquals("/media/anime", DiskMonitorUtil.staticRoot("/media/anime/"));
    }

    @Test
    void staticRoot_returns_null_when_not_determinable() {
        assertNull(DiskMonitorUtil.staticRoot(null));
        assertNull(DiskMonitorUtil.staticRoot(""));
        assertNull(DiskMonitorUtil.staticRoot("   "));
        // 以变量开头 → 无静态前缀，无法静态校验
        assertNull(DiskMonitorUtil.staticRoot("${title}/S${season}"));
    }

    @Test
    void staticRoot_uses_first_line_of_multi_line_template() {
        assertEquals("/media/anime", DiskMonitorUtil.staticRoot("/media/anime/${title}\n/other/${title}"));
    }

    @Test
    void probeOne_marks_blank_path_as_unmeasurable() {
        DiskMonitorUtil.Mount mount = DiskMonitorUtil.probeOne("未配置", "");
        assertFalse(mount.measurable(), "未配置路径应判为不可测，而不是 0% 使用率");
        assertEquals(-1, mount.usedPercent(), 1e-9);
        assertEquals(-1, mount.total());
    }

    @Test
    void probeOne_marks_null_path_as_unmeasurable() {
        DiskMonitorUtil.Mount mount = DiskMonitorUtil.probeOne("未配置", null);
        assertFalse(mount.measurable());
    }

    @Test
    void probeOne_measures_real_directory() {
        DiskMonitorUtil.Mount mount = DiskMonitorUtil.probeOne("临时目录", tempDir.toString());
        assertTrue(mount.measurable(), "本地已存在目录应可测量");
        assertTrue(mount.total() > 0);
        assertTrue(mount.usedPercent() >= 0 && mount.usedPercent() <= 100);
    }

    @Test
    void probeOne_falls_back_to_nearest_existing_ancestor() {
        // 子目录不存在，但祖先存在 → 应仍可测量（首次启动时下载目录尚未创建的场景）
        File nested = new File(tempDir.toFile(), "a/b/c/d");
        DiskMonitorUtil.Mount mount = DiskMonitorUtil.probeOne("待创建目录", nested.getAbsolutePath());
        assertTrue(mount.measurable(), "目录尚未创建时应向上回溯到最近的已存在祖先");
    }

    @Test
    void probe_returns_configured_mounts() {
        // 不依赖真实配置：至少要能跑通且不抛异常
        List<DiskMonitorUtil.Mount> mounts = DiskMonitorUtil.probe();
        assertNotNull(mounts);
        for (DiskMonitorUtil.Mount mount : mounts) {
            assertNotNull(mount.label());
        }
    }

    @Test
    void format_marks_unmeasurable_explicitly() {
        assertEquals("不可测", DiskMonitorUtil.format(-1));
        assertNotEquals("不可测", DiskMonitorUtil.format(1024L));
    }
}
