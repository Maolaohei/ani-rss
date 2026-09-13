package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.QualityProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-11 质量择优规则。
 * <p>
 * 固化三条产品契约：
 * <ol>
 *   <li>默认关闭时沿用旧的分辨率 + 体积排序；</li>
 *   <li>规则开启后支持编码/体积/字幕组/做种数硬过滤与偏好排序；</li>
 *   <li>过滤后不能把一集的候选清空，必须回退为不过滤。</li>
 * </ol>
 */
class QualityRuleTest {

    private static Item item(String title, long size, String subgroup, Integer seeders) {
        return new Item()
                .setTitle(title)
                .setReName(title)
                .setLength(size)
                .setSubgroup(subgroup)
                .setSeeders(seeders)
                .setEpisode(1.0);
    }

    @Test
    void disabled_profile_keeps_legacy_resolution_then_size_order() {
        QualityProfile disabled = QualityProfile.disabled();
        Item hevc1080 = item("Show 1080p HEVC", 2_000, "组A", null);
        Item avc2160 = item("Show 2160p AVC", 1_000, "组B", null);
        Item small1080 = item("Show 1080p AVC", 500, "组C", null);

        List<Item> ranked = QualityRule.rank(List.of(hevc1080, avc2160, small1080), disabled);
        assertSame(avc2160, ranked.get(0), "关闭规则时 2160p 仍应优先");
        assertSame(hevc1080, ranked.get(1), "同分辨率按体积降序");
        assertSame(small1080, ranked.get(2));
    }

    @Test
    void configurable_resolution_order_overrides_legacy_order() {
        QualityProfile profile = new QualityProfile()
                .setEnable(true)
                .setResolutionOrder(new ArrayList<>(List.of("1080p", "2160p")));
        Item p2160 = item("Show 2160p", 100, "A", null);
        Item p1080 = item("Show 1080p", 100, "B", null);

        List<Item> ranked = QualityRule.rank(List.of(p2160, p1080), profile);
        assertSame(p1080, ranked.get(0));
    }

    @Test
    void resolution_range_filters_below_and_above_bounds() {
        QualityProfile profile = new QualityProfile()
                .setEnable(true)
                .setMinResolution("1080p")
                .setMaxResolution("1440p");
        Item p720 = item("Show 720p", 10_000, "A", null);
        Item p1080 = item("Show 1080p", 10_000, "B", null);
        Item p1440 = item("Show 1440p", 10_000, "C", null);
        Item p2160 = item("Show 2160p", 10_000, "D", null);

        assertFalse(QualityRule.evaluate(p720, profile).allowed());
        assertTrue(QualityRule.evaluate(p1080, profile).allowed());
        assertTrue(QualityRule.evaluate(p1440, profile).allowed());
        assertFalse(QualityRule.evaluate(p2160, profile).allowed());

        List<Item> ranked = QualityRule.rank(List.of(p720, p1080, p1440, p2160), profile);
        assertEquals(List.of(p1440, p1080), ranked);
    }

    @Test
    void unknown_resolution_is_kept_when_range_is_configured() {
        QualityProfile profile = new QualityProfile()
                .setEnable(true)
                .setMinResolution("1080p")
                .setMaxResolution("2160p");
        Item unknown = item("Show WEB", 10_000, "A", null);
        assertTrue(QualityRule.evaluate(unknown, profile).allowed());
    }

    @Test
    void custom_profile_switch_forces_nested_profile_enabled() {
        Ani ani = new Ani()
                .setCustomQualityProfileEnable(true)
                .setCustomQualityProfile(new QualityProfile()
                        .setEnable(false)
                        .setExcludeCodecs(new ArrayList<>(List.of("avc"))));
        QualityProfile effective = QualityRule.effective(ani, new Config());
        assertTrue(effective.enabled());
        assertTrue(ani.getCustomQualityProfile().enabled(), "兼容旧数据时应修正嵌套默认 enable=false");
    }

    @Test
    void codec_preference_beats_size_when_resolution_same() {
        QualityProfile profile = new QualityProfile()
                .setEnable(true)
                .setPreferCodecs(new ArrayList<>(List.of("hevc", "avc")));
        Item avc = item("Show 1080p AVC", 10_000, "A", null);
        Item hevc = item("Show 1080p HEVC", 5_000, "B", null);

        List<Item> ranked = QualityRule.rank(List.of(avc, hevc), profile);
        assertSame(hevc, ranked.get(0), "编码偏好应高于同分辨率下的体积次排序");
    }

    @Test
    void exclude_codec_filters_candidate() {
        QualityProfile profile = new QualityProfile()
                .setEnable(true)
                .setExcludeCodecs(new ArrayList<>(List.of("avc")));
        Item avc = item("Show 1080p AVC", 10_000, "A", null);
        Item hevc = item("Show 1080p HEVC", 5_000, "B", null);

        List<Item> ranked = QualityRule.rank(List.of(avc, hevc), profile);
        assertEquals(1, ranked.size());
        assertSame(hevc, ranked.get(0));
        assertFalse(QualityRule.evaluate(avc, profile).allowed());
    }

    @Test
    void subgroup_preference_beats_size() {
        QualityProfile profile = new QualityProfile()
                .setEnable(true)
                .setPreferSubgroups(new ArrayList<>(List.of("优先组")));
        Item ordinary = item("Show 1080p", 20_000, "普通组", null);
        Item preferred = item("Show 1080p", 10_000, "优先组", null);

        List<Item> ranked = QualityRule.rank(List.of(ordinary, preferred), profile);
        assertSame(preferred, ranked.get(0));
    }

    @Test
    void size_range_filters_only_when_size_is_known() {
        QualityProfile profile = new QualityProfile()
                .setEnable(true)
                .setMinSizeMb(10)
                .setMaxSizeMb(20);
        Item tooSmall = item("Show 1080p", 5L * 1024 * 1024, "A", null);
        Item inRange = item("Show 1080p", 15L * 1024 * 1024, "B", null);
        Item tooLarge = item("Show 1080p", 25L * 1024 * 1024, "C", null);
        Item unknown = item("Show 1080p", 0, "D", null);
        unknown.setLength(null);

        List<Item> ranked = QualityRule.rank(List.of(tooSmall, inRange, tooLarge), profile);
        assertEquals(1, ranked.size());
        assertSame(inRange, ranked.get(0));
        assertTrue(QualityRule.evaluate(unknown, profile).allowed(), "未知体积不应被误过滤");
    }

    @Test
    void min_seeders_filters_when_source_provides_seed_count() {
        QualityProfile profile = new QualityProfile()
                .setEnable(true)
                .setMinSeeders(5);
        Item low = item("Show 1080p", 10_000, "A", 2);
        Item enough = item("Show 1080p", 5_000, "B", 8);

        List<Item> ranked = QualityRule.rank(List.of(low, enough), profile);
        assertEquals(1, ranked.size());
        assertSame(enough, ranked.get(0));
    }

    @Test
    void unknown_seeders_are_not_filtered() {
        QualityProfile profile = new QualityProfile()
                .setEnable(true)
                .setMinSeeders(5);
        Item unknown = item("Show 1080p", 10_000, "A", null);
        assertTrue(QualityRule.evaluate(unknown, profile).allowed(),
                "RSS 没有做种数时不能把候选误判为不满足");
    }

    @Test
    void too_strict_rules_fall_back_to_unfiltered_candidates() {
        QualityProfile profile = new QualityProfile()
                .setEnable(true)
                .setExcludeCodecs(new ArrayList<>(List.of("avc", "hevc")))
                .setMinSeeders(100);
        Item a = item("Show 1080p AVC", 10_000, "A", 2);
        Item b = item("Show 1080p HEVC", 5_000, "B", 3);

        List<Item> ranked = QualityRule.rank(List.of(a, b), profile);
        assertEquals(2, ranked.size(), "规则太严时回退，不能导致该集永远不下");
    }

    @Test
    void resolution_and_codec_detection_handles_common_aliases() {
        assertEquals("2160p", QualityRule.detectResolution("Show 4K UHD"));
        assertEquals("1080p", QualityRule.detectResolution("Show 1080P"));
        assertEquals("720p", QualityRule.detectResolution("Show 720p"));
        assertEquals("hevc", QualityRule.detectCodec("Show x265"));
        assertEquals("hevc", QualityRule.detectCodec("Show H.265"));
        assertEquals("avc", QualityRule.detectCodec("Show H264"));
        assertEquals("av1", QualityRule.detectCodec("Show AV1"));
    }
}
