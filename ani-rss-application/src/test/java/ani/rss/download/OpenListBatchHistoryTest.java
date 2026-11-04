package ani.rss.download;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合集逐集记账的纯逻辑测试。
 * <p>
 * 线上教训：一个 {@code [01-12 合集]} 条目只写一条 {@code S02E01} 历史，
 * 记录页看起来像"只下了第 1 集"。这里锁死"由合集 reName 推导每集名字"的推导规则。
 */
class OpenListBatchHistoryTest {

    private static List<Double> range(double from, double to) {
        List<Double> list = new ArrayList<>();
        for (double i = from; i <= to; i++) {
            list.add(i);
        }
        return list;
    }

    /**
     * 标准 v2 命名：前缀保留，集数按原宽度补零
     */
    @Test
    void expandsBatchReName() {
        List<String> names = OpenList.batchEpisodeReNames("彻夜之歌 S02E01", range(1, 12));

        assertEquals(12, names.size());
        assertEquals("彻夜之歌 S02E01", names.get(0));
        assertEquals("彻夜之歌 S02E12", names.get(11));
    }

    /**
     * 三位数集数不能被压回两位
     */
    @Test
    void keepsOriginalWidth() {
        List<String> names = OpenList.batchEpisodeReNames("某番 S01E100", Arrays.asList(100D, 101D));

        assertEquals(Arrays.asList("某番 S01E100", "某番 S01E101"), names);
    }

    /**
     * 结尾不是「E+数字」的自定义模板推导不出来，调用方必须退回单条记账
     */
    @Test
    void refusesNonEpisodeSuffix() {
        assertTrue(OpenList.batchEpisodeReNames("某番 第01集", range(1, 12)).isEmpty());
        assertTrue(OpenList.batchEpisodeReNames("某番 - 01", range(1, 12)).isEmpty());
        assertTrue(OpenList.batchEpisodeReNames("某番", range(1, 12)).isEmpty());
        assertTrue(OpenList.batchEpisodeReNames("", range(1, 12)).isEmpty());
    }

    /**
     * 单集条目不是合集，不该走逐集记账
     */
    @Test
    void singleEpisodeIsNotBatch() {
        assertTrue(OpenList.batchEpisodeReNames("某番 S01E05", List.of(5D)).isEmpty());
    }

    /**
     * 异常范围（超大、空）必须拒绝，避免把历史刷爆
     */
    @Test
    void refusesOversizedRange() {
        assertTrue(OpenList.batchEpisodeReNames("某番 S01E001", range(1, 201)).isEmpty());
        assertTrue(OpenList.batchEpisodeReNames("某番 S01E01", List.of()).isEmpty());
        assertTrue(OpenList.batchEpisodeReNames("某番 S01E01", null).isEmpty());
    }

    /**
     * 范围归一：去空、去重、升序
     */
    @Test
    void normalizesRange() {
        List<Double> normalized = OpenList.sortedEpisodeRange(Arrays.asList(3D, null, 1D, 3D, 2D));

        assertEquals(Arrays.asList(1D, 2D, 3D), normalized);
        assertTrue(OpenList.sortedEpisodeRange(null).isEmpty());
        assertTrue(OpenList.sortedEpisodeRange(List.of()).isEmpty());
    }

    /**
     * 摘要标签：连续写成区间，离散列出前几个
     */
    @Test
    void labelsSpan() {
        assertEquals("01-12", OpenList.episodeSpanLabel(range(1, 12)));
        assertEquals("01,03,05", OpenList.episodeSpanLabel(Arrays.asList(1D, 3D, 5D)));
        assertEquals("07", OpenList.episodeSpanLabel(List.of(7D)));
        assertEquals("", OpenList.episodeSpanLabel(List.of()));
        assertEquals("01,02,04,06,08,10,…",
                OpenList.episodeSpanLabel(Arrays.asList(1D, 2D, 4D, 6D, 8D, 10D, 12D)));
    }
}
