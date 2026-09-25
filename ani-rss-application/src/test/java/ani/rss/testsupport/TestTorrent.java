package ani.rss.testsupport;

import ani.rss.util.other.TorrentMetadata;
import cn.hutool.core.io.FileUtil;


import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试用最小种子生成器：手写 bencode（不依赖任何"造种"API），生成
 * 能解析的多文件/单文件种子。
 * <p>
 * 为什么需要它：验证「期望文件计划」必须用真实可解析的种子——用空文件 + 文件名当 hash 的
 * 老写法（{@code OpenListWorkflowSimulationTest#torrentFile}）里没有 info dict，
 * 计划会退化成空，测不到计划链路。
 */
public final class TestTorrent {

    private TestTorrent() {
    }

    /**
     * 一个待写入种子的文件
     *
     * @param path   种子内相对路径（多文件时会被拆成 path 列表）
     * @param length 字节数
     */
    public record Entry(String path, long length) {
    }

    public static Entry file(String path, long length) {
        return new Entry(path, length);
    }

    /**
     * 写入一份多文件种子（单个文件时写成单文件种子，与真实种子结构一致）
     *
     * @param target 目标文件
     * @param name   根目录名（多文件）
     */
    public static File write(File target, String name, List<Entry> entries) throws IOException {
        List<Entry> list = new ArrayList<>(entries);
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        if (list.size() == 1) {
            info.write(bytes("d6:lengthi" + list.get(0).length() + "e4:name"));
            info.write(bencodeString(list.get(0).path()));
        } else {
            info.write(bytes("d5:filesl"));
            for (Entry entry : list) {
                info.write(bytes("d6:lengthi" + entry.length() + "e4:pathl"));
                for (String seg : entry.path().split("/")) {
                    info.write(bencodeString(seg));
                }
                info.write(bytes("ee"));
            }
            info.write(bytes("e4:name"));
            info.write(bencodeString(name));
        }
        info.write(bytes("12:piece lengthi16384e6:pieces20:"));
        info.write(new byte[20]);
        info.write(bytes("e"));

        ByteArrayOutputStream torrent = new ByteArrayOutputStream();
        torrent.write(bytes("d8:announce"));
        torrent.write(bencodeString("http://tracker.example/announce"));
        torrent.write(bytes("4:info"));
        torrent.write(info.toByteArray());
        torrent.write(bytes("e"));

        FileUtil.writeBytes(torrent.toByteArray(), target);
        return target;
    }

    /**
     * 生成一个 infoHash 唯一的临时种子（用名字区分，避免多份种子的 hash 撞车）
     */
    public static File temp(String tag, String name, List<Entry> entries) throws IOException {
        File probe = FileUtil.createTempFile();
        File dir = probe.getParentFile();
        FileUtil.del(probe);
        File target = new File(dir, "test-" + tag + "-" + System.nanoTime() + ".torrent");
        target.deleteOnExit();
        return write(target, name, entries);
    }

    /**
     * 真实 infoHash（40 位 hex），用于断言 hash 一致性
     */
    public static String infoHash(File torrent) throws IOException {
        TorrentMetadata parsed = TorrentMetadata.from(torrent);
        return parsed.getHash();
    }

    public static String sha1Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] bencodeString(String s) {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        return concat(bytes(raw.length + ":"), raw);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
