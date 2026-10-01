package ani.rss.util.other;

import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 种子信息哈希口径。
 * <p>
 * 回归点：纯 v2 种子曾经返回完整的 64 位 SHA-256，而 qBittorrent API
 * （{@code /api/v2/torrents/*} 的 {@code hash}/{@code hashes}）只认 40 位，
 * 于是合集下载提交后按 hash 轮询/重命名、qB 偏移缓存键全部对不上
 * （上游 {@code 0963d05b3} close #731，fork 在 v3.4.28 只移植了修复前的版本）。
 * <p>
 * 用例直接手写 bencode 并由测试自己计算 info 字节的摘要，因此同时校验了
 * 「原始 info 字节提取」与「各版本哈希口径」两件事。
 */
class TorrentMetadataTest {

    /** 全零 pieces：只参与哈希计算，不校验内容 */
    private static final byte[] PIECES = new byte[20];

    private static byte[] infoV1(String name, long length) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        write(o, "d6:lengthi" + length + "e4:name");
        write(o, bencodeString(name));
        write(o, "12:piece lengthi16384e6:pieces");
        write(o, bencodeRaw(PIECES));
        write(o, "e");
        return o.toByteArray();
    }

    private static byte[] infoPureV2(String name, long length) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        // key 需按字节序：file tree < meta version < name
        write(o, "d9:file treed");
        write(o, bencodeString(name));
        write(o, "d0:d6:lengthi" + length + "eeee");
        write(o, "12:meta versioni2e4:name");
        write(o, bencodeString(name));
        write(o, "e");
        return o.toByteArray();
    }

    private static byte[] infoHybrid(String name, long length) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        // file tree < files < meta version < name < piece length < pieces
        write(o, "d9:file treed");
        write(o, bencodeString(name));
        write(o, "d0:d6:lengthi" + length + "eeee");
        write(o, "5:filesld6:lengthi" + length + "e4:pathl");
        write(o, bencodeString(name));
        write(o, "eee");
        write(o, "12:meta versioni2e4:name");
        write(o, bencodeString(name));
        write(o, "12:piece lengthi16384e6:pieces");
        write(o, bencodeRaw(PIECES));
        write(o, "e");
        return o.toByteArray();
    }

    private static File torrent(byte[] infoBytes) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        write(o, "d8:announce17:http://a/announce4:info");
        write(o, infoBytes);
        write(o, "e");
        File file = FileUtil.createTempFile("md-", ".torrent", null, true);
        FileUtil.writeBytes(o.toByteArray(), file);
        return file;
    }

    private static String hex(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void write(ByteArrayOutputStream out, byte[] raw) {
        out.writeBytes(raw);
    }

    private static byte[] bencodeString(String s) {
        return bencodeRaw(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bencodeRaw(byte[] raw) {
        byte[] prefix = (raw.length + ":").getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[prefix.length + raw.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(raw, 0, out, prefix.length, raw.length);
        return out;
    }

    @Test
    void v1_hash_is_sha1_of_raw_info_bytes() throws Exception {
        byte[] info = infoV1("Show - 01.mkv", 1031L);
        TorrentMetadata metadata = TorrentMetadata.from(torrent(info));

        assertEquals(hex("SHA-1", info), metadata.getHash());
        assertEquals(40, metadata.getHash().length());
    }

    @Test
    void pure_v2_hash_is_truncated_sha256_so_qbittorrent_can_match_it() throws Exception {
        byte[] info = infoPureV2("Show - 01.mkv", 1031L);
        TorrentMetadata metadata = TorrentMetadata.from(torrent(info));

        assertEquals(hex("SHA-256", info).substring(0, 40), metadata.getHash(),
                "纯 v2 必须按 qBittorrent API 口径截断到 40 hex");
        assertEquals(40, metadata.getHash().length());
    }

    @Test
    void hybrid_hash_keeps_v1_preference() throws Exception {
        byte[] info = infoHybrid("Show S01", 1031L);
        TorrentMetadata metadata = TorrentMetadata.from(torrent(info));

        assertEquals(hex("SHA-1", info), metadata.getHash(),
                "hybrid 同时带 pieces，libtorrent info_hash().get_best() 取 v1 SHA-1");
        assertEquals(40, metadata.getHash().length());
    }

    @Test
    void magnet_uri_declares_btmh_for_v2_only() throws Exception {
        byte[] v1 = infoV1("a.mkv", 1L);
        TorrentMetadata v1Metadata = TorrentMetadata.from(torrent(v1));
        assertTrue(v1Metadata.getMagnetUri().contains("urn:btih:" + hex("SHA-1", v1)));
        assertFalse(v1Metadata.getMagnetUri().contains("btmh"));

        byte[] v2 = infoPureV2("a.mkv", 1L);
        TorrentMetadata v2Metadata = TorrentMetadata.from(torrent(v2));
        assertTrue(v2Metadata.getMagnetUri().contains("urn:btmh:1220" + hex("SHA-256", v2)));
        assertFalse(v2Metadata.getMagnetUri().contains("btih"));
    }
}
