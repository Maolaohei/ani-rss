package ani.rss.util.other;

import ani.rss.controller.CollectionController;
import ani.rss.entity.Ani;
import ani.rss.entity.CollectionSegment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真实混合合集种子回归一次识别结果。
 * <p>
 * 种子是本机样本（S1 + S2 绝对集 25–47 + 剧场版 三段），CI 上没有该文件时自动跳过，
 * 只作为本地改规则后的回归锚点。
 */
class CollectionSampleTorrentTest {

    private static final String SAMPLE = "C:/Users/Admin/Downloads/095bd5b1fd91656e63da3523262ef056d93e6ac9.torrent";

    @Test
    void 混合合集应识别为三段() throws Exception {
        File file = new File(SAMPLE);
        Assumptions.assumeTrue(file.isFile(), "本机无样本种子，跳过");

        TorrentMetadata metadata = TorrentMetadata.from(file);
        Ani ani = new Ani().setTitle("咒术回战").setSeason(1).setOffset(0);

        CollectionAnalyzer.Analysis analysis = CollectionAnalyzer.analyze(metadata, ani);
        List<CollectionSegment> segments = analysis.segments();

        assertEquals(3, segments.size(), "应识别出 3 段: " + describe(segments));

        List<CollectionSegment> tv = segments.stream()
                .filter(s -> "TV".equalsIgnoreCase(s.getKind()))
                .sorted(Comparator.comparing(CollectionSegment::getSeason))
                .toList();
        assertEquals(2, tv.size(), "应识别出两季: " + describe(segments));
        assertEquals(1, tv.get(0).getSeason());
        assertEquals(2, tv.get(1).getSeason());
        // S2 目录名标了季，文件却是绝对集 25–47：识别阶段只负责分季，不改偏移
        assertTrue(tv.get(1).getScope().contains("S2"), "S2 段应绑定 S2 目录: " + tv.get(1).getScope());

        CollectionSegment movie = segments.stream()
                .filter(s -> "MOVIE".equalsIgnoreCase(s.getKind()))
                .findFirst().orElse(null);
        assertNotNull(movie, "应识别出剧场版: " + describe(segments));
        // 剧场版没有集号，不应与 S1E01 / S2E01 抢同一个文件名
        assertTrue(movie.getEpisodeRange() == null || movie.getEpisodeRange().isEmpty(),
                "剧场版段不应产生集号: " + movie.getEpisodeRange());

        Ani movieAni = CollectionController.segmentAni(ani, movie);
        assertTrue(Boolean.TRUE.equals(movieAni.getOva()), "剧场版段应走剧场版命名");
        assertEquals("movie", movieAni.getMediaType());
    }

    private static String describe(List<CollectionSegment> segments) {
        return segments.stream()
                .map(s -> s.getKind() + "/" + s.getName() + "/" + s.getScope() + "/" + s.getSeason())
                .toList()
                .toString();
    }
}
