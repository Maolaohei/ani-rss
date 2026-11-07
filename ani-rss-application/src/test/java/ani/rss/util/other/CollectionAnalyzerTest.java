package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.CollectionSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 合集分段识别测试：多季 / 剧场版 / OVA 混排的种子应切成互不重叠的若干段。
 * <p>
 * 种子用最小 bencode 现场构造（生产口径：解码按 ISO-8859-1，路径再转回 UTF-8），
 * 不依赖外部样本文件。
 */
class CollectionAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void mixedSeasonsAndMovie() throws IOException {
        Map<String, Object> info = info(List.of(
                file("[BeanSub&FZSD&VCB-Studio] Jujutsu Kaisen [Ma10p_1080p]/[BeanSub&FZSD&VCB-Studio] Jujutsu Kaisen [01][Ma10p_1080p][x265_flac].mkv"),
                file("[BeanSub&FZSD&VCB-Studio] Jujutsu Kaisen [Ma10p_1080p]/[BeanSub&FZSD&VCB-Studio] Jujutsu Kaisen [02][Ma10p_1080p][x265_flac].mkv"),
                file("[BeanSub&FZSD&VCB-Studio] Jujutsu Kaisen S2 [Ma10p_1080p]/[BeanSub&FZSD&VCB-Studio] Jujutsu Kaisen S2 [25][Ma10p_1080p][x265_flac].mkv"),
                file("[BeanSub&FZSD&VCB-Studio] Jujutsu Kaisen S2 [Ma10p_1080p]/[BeanSub&FZSD&VCB-Studio] Jujutsu Kaisen S2 [26][Ma10p_1080p][x265_flac].mkv"),
                file("[BeanSub&FZSD&VCB-Studio] Gekijouban Jujutsu Kaisen 0 [Ma10p_1080p]/[BeanSub&FZSD&VCB-Studio] Gekijouban Jujutsu Kaisen 0 [Ma10p_1080p][x265_flac].mkv")
        ));

        TorrentMetadata metadata = torrent(info);
        Ani base = new Ani();
        base.setTitle("咒术回战");
        base.setSeason(1);

        CollectionAnalyzer.Analysis analysis = CollectionAnalyzer.analyze(metadata, base);
        List<CollectionSegment> segments = analysis.segments();

        assertEquals(3, segments.size(), "应识别出 3 个部分");
        assertEquals(List.of("TV", "TV", "MOVIE"), segments.stream().map(CollectionSegment::getKind).toList());
        assertEquals(List.of(1, 2, 1), segments.stream().map(CollectionSegment::getSeason).toList());
        assertTrue(segments.get(2).getOva(), "剧场版应走电影式命名");

        CollectionSegment movie = segments.get(2);
        assertEquals("Jujutsu Kaisen 0", movie.getTitle(), "剧场版标题应去掉 Gekijouban 等关键词");
        assertNotEquals(base.getTitle(), movie.getTitle(), "剧场版标题不能与剧集同名");

        assertTrue(segments.get(0).getScope().contains("Jujutsu Kaisen [Ma10p_1080p]"));
        assertTrue(segments.get(1).getScope().contains("Jujutsu Kaisen S2"));
        assertTrue(CollectionAnalyzer.isMixed(segments));
        assertFalse(analysis.warnings().isEmpty());
    }

    @Test
    void singleSeasonIsNotMixed() throws IOException {
        Map<String, Object> info = info(List.of(
                file("[Group] Show [1080p]/[Group] Show [01][1080p].mkv"),
                file("[Group] Show [1080p]/[Group] Show [02][1080p].mkv")
        ));

        Ani base = new Ani();
        base.setTitle("Show");
        base.setSeason(1);

        CollectionAnalyzer.Analysis analysis = CollectionAnalyzer.analyze(torrent(info), base);
        assertEquals(1, analysis.segments().size());
        assertEquals("TV", analysis.segments().get(0).getKind());
        assertEquals(1, analysis.segments().get(0).getSeason());
        assertFalse(CollectionAnalyzer.isMixed(analysis.segments()));
    }

    @Test
    void discFoldersMergeIntoOneSegment() throws IOException {
        Map<String, Object> info = info(List.of(
                file("[Group] Show S1 Disc 1/[Group] Show S1 [01].mkv"),
                file("[Group] Show S1 Disc 2/[Group] Show S1 [02].mkv")
        ));

        Ani base = new Ani();
        base.setTitle("Show");

        List<CollectionSegment> segments = CollectionAnalyzer.analyze(torrent(info), base).segments();
        assertEquals(1, segments.size(), "Disc1/Disc2 是同一季的分卷，不能拆成两段");
        assertEquals("TV", segments.get(0).getKind());
        assertEquals(1, segments.get(0).getSeason());
        assertTrue(segments.get(0).getScope().contains("Disc 1"));
        assertTrue(segments.get(0).getScope().contains("Disc 2"));
    }

    @Test
    void chineseSeasonFolder() throws IOException {
        Map<String, Object> info = info(List.of(
                file("咒术回战 第2季/[Group] 咒术回战 [01].mkv"),
                file("咒术回战 第2季/[Group] 咒术回战 [02].mkv")
        ));

        Ani base = new Ani();
        base.setTitle("咒术回战");

        List<CollectionSegment> segments = CollectionAnalyzer.analyze(torrent(info), base).segments();
        assertEquals(1, segments.size());
        assertEquals(2, segments.get(0).getSeason());
        assertEquals("第2季", segments.get(0).getName());
    }

    @Test
    void flatTorrentWithTwoSeasonsOnlyWarns() throws IOException {
        Map<String, Object> info = info(List.of(
                file("[Group] Show S01E01 [1080p].mkv"),
                file("[Group] Show S02E01 [1080p].mkv")
        ));

        Ani base = new Ani();
        base.setTitle("Show");

        CollectionAnalyzer.Analysis analysis = CollectionAnalyzer.analyze(torrent(info), base);
        assertEquals(1, analysis.segments().size(), "无顶层目录时无法按目录拆分，保持单段");
        assertTrue(analysis.warnings().stream().anyMatch(w -> w.contains("没有顶层目录")),
                "应提示无法自动拆分：" + analysis.warnings());
    }

    @Test
    void oneRootWithTwoSeasonsWarns() throws IOException {
        Map<String, Object> info = info(List.of(
                file("[Group] Show [1080p]/[Group] Show S01E01 [1080p].mkv"),
                file("[Group] Show [1080p]/[Group] Show S02E01 [1080p].mkv")
        ));

        Ani base = new Ani();
        base.setTitle("Show");

        CollectionAnalyzer.Analysis analysis = CollectionAnalyzer.analyze(torrent(info), base);
        assertEquals(1, analysis.segments().size());
        assertTrue(analysis.warnings().stream().anyMatch(w -> w.contains("季标记")),
                "一目录混多季应提示人工确认：" + analysis.warnings());
    }

    @Test
    void specialsFallIntoSeasonZero() throws IOException {
        Map<String, Object> info = info(List.of(
                file("[Group] Show OVA [1080p]/[Group] Show OVA [01].mkv"),
                file("[Group] Show [1080p]/[Group] Show [01].mkv")
        ));

        Ani base = new Ani();
        base.setTitle("Show");

        List<CollectionSegment> segments = CollectionAnalyzer.analyze(torrent(info), base).segments();
        assertEquals(2, segments.size());
        CollectionSegment special = segments.stream()
                .filter(s -> "SPECIAL".equals(s.getKind()))
                .findFirst()
                .orElseThrow();
        assertEquals(0, special.getSeason(), "OVA 没有季标记时落 S00，避免与正片撞集");
        assertTrue(special.getOva());
    }

    // ------------------------------------------------------------------
    // 最小 bencode 构造（字符串按 UTF-8 写字节，与生产解码口径一致）
    // ------------------------------------------------------------------

    private TorrentMetadata torrent(Map<String, Object> info) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("info", info);
        Path file = tempDir.resolve("collection.torrent");
        Files.write(file, bencode(root));
        return TorrentMetadata.from(file.toFile());
    }

    private static Map<String, Object> info(List<Map<String, Object>> files) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "collection");
        info.put("piece length", 16384L);
        info.put("pieces", "");
        info.put("files", new ArrayList<>(files));
        return info;
    }

    private static Map<String, Object> file(String path) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("length", 1024L);
        file.put("path", List.of(path.split("/")));
        return file;
    }

    private static byte[] bencode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encode(out, value);
        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void encode(ByteArrayOutputStream out, Object value) {
        if (value instanceof Map<?, ?> map) {
            out.write('d');
            new java.util.TreeMap<>((Map<String, Object>) map)
                    .forEach((k, v) -> {
                        writeString(out, k);
                        encode(out, v);
                    });
            out.write('e');
            return;
        }
        if (value instanceof List<?> list) {
            out.write('l');
            list.forEach(item -> encode(out, item));
            out.write('e');
            return;
        }
        if (value instanceof Long number) {
            out.writeBytes(("i" + number + "e").getBytes(StandardCharsets.ISO_8859_1));
            return;
        }
        writeString(out, String.valueOf(value));
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeBytes((bytes.length + ":").getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(bytes);
    }
}
