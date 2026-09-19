package ani.rss.util.other;

import cn.hutool.core.io.FileUtil;
import cn.hutool.crypto.SecureUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 磁力链接工具：纯函数与缓存路径（不触网、不依赖原生库）。
 * <p>
 * 抓取本身依赖 DHT/Tracker，不适合放进单测；但「缓存命中不碰原生库」这条不变量必须固化——
 * 它是原生库加载失败的环境（32 位 arm / musl 镜像）里磁力功能仅存的可用路径。
 */
class MagnetTorrentUtilTest {

    private static final String MAGNET =
            "magnet:?xt=urn:btih:d160b8d8ea35a5b4e52837468fc8f03d55cef1f7&dn=probe";

    @AfterEach
    void tearDown() {
        MagnetTorrentUtil.clearCache();
    }

    @Test
    void is_magnet_only_accepts_protocol_prefix() {
        assertTrue(MagnetTorrentUtil.isMagnet(MAGNET));
        assertTrue(MagnetTorrentUtil.isMagnet("  MAGNET:?xt=urn:btih:abc"));
        assertFalse(MagnetTorrentUtil.isMagnet(null));
        assertFalse(MagnetTorrentUtil.isMagnet(""));
        // Base64 种子内容不能被误判成磁力链接
        assertFalse(MagnetTorrentUtil.isMagnet("ZDEwOmFubm91bmNl"));
        assertFalse(MagnetTorrentUtil.isMagnet("http://example.com/a.torrent"));
    }

    @Test
    void normalize_appends_default_trackers_once() {
        String once = MagnetTorrentUtil.normalize(MAGNET);
        String twice = MagnetTorrentUtil.normalize(once);

        assertTrue(once.startsWith(MAGNET), "原始链接必须原样保留在前面");
        assertEquals(once, twice, "归一化必须幂等，否则同一磁力会算出两个缓存键");
        assertNotEquals(once, MAGNET, "应补上默认 Tracker");
        assertTrue(once.contains("tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"));
    }

    @Test
    void normalize_keeps_user_tracker_and_rejects_non_magnet() {
        String withTracker = MAGNET + "&tr=udp%3A%2F%2Fuser.example%3A1337%2Fannounce";
        String normalized = MagnetTorrentUtil.normalize(withTracker);

        assertTrue(normalized.contains("user.example"), "用户自带的 Tracker 不能丢");
        assertEquals(1, countOf(normalized, "user.example"), "用户 Tracker 不得被重复追加");
        assertThrows(IllegalStateException.class, () -> MagnetTorrentUtil.normalize("http://a.torrent"));
    }

    /**
     * 命中缓存时 {@link MagnetTorrentUtil#resolve(String)} 必须直接返回缓存文件：
     * 不建 SessionManager、不初始化原生库。
     */
    @Test
    void resolve_returns_cached_file_without_touching_native_lib() throws Exception {
        String normalized = MagnetTorrentUtil.normalize(MAGNET);
        File cached = new File(MagnetTorrentUtil.cacheDir(), SecureUtil.sha256(normalized) + ".torrent");
        // 造一份最小的 bencode 字典（首字节 'd'），够通过 isUsableTorrent 的健壮性检查
        FileUtil.writeBytes("d4:infod4:name4:testee".getBytes(StandardCharsets.UTF_8), cached);

        File resolved = MagnetTorrentUtil.resolve(MAGNET);

        assertEquals(cached.getCanonicalFile(), resolved.getCanonicalFile(), "缓存命中应直接返回缓存文件");
    }

    @Test
    void clear_cache_removes_directory() {
        String normalized = MagnetTorrentUtil.normalize(MAGNET);
        File cached = new File(MagnetTorrentUtil.cacheDir(), SecureUtil.sha256(normalized) + ".torrent");
        FileUtil.writeBytes("d4:info".getBytes(StandardCharsets.UTF_8), cached);

        assertTrue(cached.isFile());
        assertTrue(MagnetTorrentUtil.clearCache() > 0, "清理应报告释放的字节数");
        assertFalse(cached.isFile(), "缓存文件应被清理");
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
