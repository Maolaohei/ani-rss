package ani.rss.controller;

import ani.rss.entity.Ani;
import ani.rss.entity.CollectionSegment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分段继承规则。
 * <p>
 * 回归点：老版本分段 Ani 会连「自定义下载位置」一起继承，
 * 结果三个分段全部落到同一个目录（剧场版进番剧库、第 2 季也进 Season 1）。
 */
class CollectionSegmentAniTest {

    private Ani base() {
        Ani base = new Ani();
        base.setTitle("咒术回战");
        base.setSeason(1);
        base.setOffset(0);
        base.setOva(false);
        base.setCustomDownloadPath(true);
        base.setDownloadPath("/115/动漫/转存/追番/咒术回战/Season 1");
        base.setCustomRenameTemplateEnable(false);
        return base;
    }

    private static boolean customDownloadPath(Ani ani) {
        return Boolean.TRUE.equals(ani.getCustomDownloadPath());
    }

    @Test
    void seasonSegmentDropsCustomDownloadPath() {
        CollectionSegment segment = new CollectionSegment();
        segment.setTitle("咒术回战");
        segment.setSeason(2);
        segment.setOffset(0);

        Ani ani = CollectionController.segmentAni(base(), segment);

        assertFalse(customDownloadPath(ani), "分段必须放开自定义目录，否则所有段落进同一个文件夹");
        assertEquals(2, ani.getSeason());
        assertEquals("咒术回战", ani.getTitle());
        assertFalse(Boolean.TRUE.equals(ani.getOva()));
    }

    @Test
    void movieSegmentIsMarkedAsMovie() {
        CollectionSegment segment = new CollectionSegment();
        segment.setTitle("咒术回战 0");
        segment.setOffset(0);
        segment.setOva(true);

        Ani ani = CollectionController.segmentAni(base(), segment);

        assertTrue(Boolean.TRUE.equals(ani.getOva()));
        assertEquals("movie", ani.getMediaType());
        assertFalse(customDownloadPath(ani));
    }
}
