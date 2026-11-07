package ani.rss.controller;

import ani.rss.entity.Ani;
import ani.rss.entity.CollectionSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 合集分段执行的路径 / 规则派生测试。
 * <p>
 * 这里覆盖的是「同一份种子落到多个目录」的关键换算：
 * qB 只允许一个 savepath，所以每段的落盘目录必须能相对公共祖先表达；
 * 匹配规则则用「种子内根目录」做 AND 约束，保证各段的文件互不重叠。
 */
class CollectionSegmentPlanTest {

    @Test
    void commonRootOfSiblingSeasons() {
        String root = CollectionController.commonRoot(List.of(
                "/115/动画/咒术回战/第1季",
                "/115/动画/咒术回战/第2季"
        ));
        assertEquals("/115/动画/咒术回战", root);
    }

    @Test
    void commonRootStopsAtLibraryBoundary() {
        // TV 库与电影库是兄弟目录时，公共祖先退到它们的共同父级
        String root = CollectionController.commonRoot(List.of(
                "/115/动画/咒术回战/第1季",
                "/115/电影/咒术回战 0"
        ));
        assertEquals("/115", root);
    }

    @Test
    void commonRootIgnoresBlankAndTrailingSlash() {
        assertEquals("/115/动画", CollectionController.commonRoot(List.of(
                "/115/动画/第1季/",
                "",
                "/115/动画/第2季"
        )));
    }

    @Test
    void relativizeProducesPerSegmentSubPath() {
        assertEquals("第2季", CollectionController.relativize("/115/动画/咒术回战", "/115/动画/咒术回战/第2季"));
        assertEquals("", CollectionController.relativize("/115/动画/咒术回战", "/115/动画/咒术回战"));
        // 不同库：给不出相对路径时返回空，由调用方决定是否落公共祖先
        assertEquals("", CollectionController.relativize("/115/动画", "/mnt/movie/x"));
        assertEquals("", CollectionController.relativize("", "/115/动画"));
    }

    @Test
    void scopeMatchIsAnchoredAndQuoted() {
        List<String> match = CollectionController.scopeMatch(
                List.of("字幕组"),
                "[BeanSub&FZSD&VCB-Studio] Jujutsu Kaisen S2 [Ma10p_1080p]");

        assertEquals(2, match.size(), "原始规则应保留，目录约束追加在后面");
        Pattern pattern = Pattern.compile(match.get(1));
        assertTrue(pattern.matcher("[BeanSub&FZSD&VCB-Studio] Jujutsu Kaisen S2 [Ma10p_1080p]/[01].mkv").find(),
                "该段目录内的文件应命中");
        assertFalse(pattern.matcher("[BeanSub&FZSD&VCB-Studio] Jujutsu Kaisen [Ma10p_1080p]/[01].mkv").find(),
                "其它段的文件不得命中");
        assertFalse(pattern.matcher("x[BeanSub&FZSD&VCB-Studio] Jujutsu Kaisen S2 [Ma10p_1080p]/[01].mkv").find(),
                "前缀必须锚定在开头");
    }

    @Test
    void scopeMatchKeepsOriginalRulesWhenScopeBlank() {
        List<String> match = CollectionController.scopeMatch(List.of("字幕组"), "");
        assertEquals(List.of("字幕组"), match);
    }

    @Test
    void segmentAniOverridesOnlySegmentFields() {
        Ani base = new Ani();
        base.setTitle("咒术回战");
        base.setSeason(1);
        base.setOffset(5);
        base.setSubgroup("BeanSub");

        CollectionSegment segment = new CollectionSegment()
                .setTitle("咒术回战 第二季")
                .setSeason(2)
                .setOffset(24)
                .setOva(false);

        Ani ani = CollectionController.segmentAni(base, segment);
        assertEquals("咒术回战 第二季", ani.getTitle());
        assertEquals(2, ani.getSeason());
        assertEquals(24, ani.getOffset());
        assertEquals("BeanSub", ani.getSubgroup(), "字幕组等未覆盖字段应沿用基础设置");
        assertFalse(ani.getOva());
        assertEquals("tv", ani.getMediaType());
    }

    @Test
    void segmentAniKeepsBaseOffsetWhenSegmentDoesNotSetIt() {
        Ani base = new Ani();
        base.setTitle("Show");
        base.setSeason(1);
        base.setOffset(3);

        CollectionSegment segment = new CollectionSegment().setSeason(2);
        Ani ani = CollectionController.segmentAni(base, segment);

        assertEquals(3, ani.getOffset(), "分段未指定偏移时不能把基础偏移清零");
        assertEquals(2, ani.getSeason());
    }

    @Test
    void segmentAniMarksMovie() {
        Ani base = new Ani();
        base.setTitle("Show");
        base.setSeason(1);

        Ani ani = CollectionController.segmentAni(base, new CollectionSegment()
                .setTitle("Show 0")
                .setOva(true));

        assertTrue(ani.getOva());
        assertEquals("movie", ani.getMediaType());
    }
}
