package ani.rss.util.other;

import ani.rss.entity.Ani;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 多订阅源「已订阅」同步的 BGM 条目 id 解析。
 * <p>
 * 订阅来源不同，落库的标识也不同：Mikan 是站内 bangumiId，AniBT 是 bgmId 参数，
 * AnimeGarden 是 subject 参数。列表页统一按 BGM 条目 id 比较；存量订阅可能没有
 * bgmUrl（3.0 之前的字段），因此必须能从 RSS 参数和 Mikan 详情页回退解析。
 */
class BgmUtilSubjectIdTest {

    private List<Ani> backup;

    @BeforeEach
    void setUp() {
        backup = new ArrayList<>(AniUtil.getAniList());
        AniUtil.getAniList().clear();
    }

    @AfterEach
    void tearDown() {
        List<Ani> live = AniUtil.getAniList();
        live.clear();
        live.addAll(backup);
    }

    private static Ani ani(String url) {
        return new Ani().setUrl(url).setTitle("测试订阅").setSeason(1);
    }

    @Test
    void resolves_subject_id_from_saved_bgm_url() {
        Ani ani = ani("https://mikanani.me/RSS/Bangumi?bangumiId=3060")
                .setBgmUrl("https://bgm.tv/subject/373247");

        assertEquals("373247", BgmUtil.resolveSubjectId(ani, id -> null));
    }

    @Test
    void resolves_subject_id_from_bgm_url_with_trailing_slash_or_query() {
        assertEquals("373247", BgmUtil.getSubjectId("https://bgm.tv/subject/373247/"));
        assertEquals("373247", BgmUtil.getSubjectId("https://bgm.tv/subject/373247?from=rss"));
    }

    @Test
    void resolves_anibt_rss_bgm_id() {
        Ani ani = ani("https://anibt.net/rss/anime.xml?bgmId=373247&groupSlug=group-a");

        assertEquals("373247", BgmUtil.resolveSubjectId(ani, id -> null));
    }

    @Test
    void resolves_animegarden_rss_subject() {
        Ani ani = ani("https://animes.garden/rss?subject=373247&fansub=subgroup-a");

        assertEquals("373247", BgmUtil.resolveSubjectId(ani, id -> null));
    }

    @Test
    void mikan_legacy_without_bgm_url_resolves_from_detail_page_and_backfills() {
        Ani ani = ani("https://mikanani.me/RSS/Bangumi?bangumiId=3060");
        List<String> requestedBangumiIds = new ArrayList<>();

        String subjectId = BgmUtil.resolveSubjectId(ani, bangumiId -> {
            requestedBangumiIds.add(bangumiId);
            return "https://bgm.tv/subject/373247";
        });

        assertEquals("373247", subjectId);
        assertEquals(List.of("3060"), requestedBangumiIds);
        assertEquals("https://bgm.tv/subject/373247", ani.getBgmUrl(), "解析成功后应回填到内存对象，避免重复抓页面");
    }

    @Test
    void mikan_bgm_url_without_numeric_id_returns_null() {
        Ani ani = ani("https://mikanani.me/RSS/Bangumi?bangumiId=3060");

        assertNull(BgmUtil.resolveSubjectId(ani, id -> "https://bgm.tv/subject/not-a-number"));
    }

    @Test
    void unparseable_subscription_returns_null_instead_of_throwing() {
        Ani ani = ani("https://example.com/rss");

        assertNull(BgmUtil.resolveSubjectId(ani, id -> null));
        assertNull(BgmUtil.getSubjectIdOrNull(ani));
    }

    @Test
    void list_aggregates_all_sources_and_deduplicates() {
        AniUtil.getAniList().add(ani("https://mikanani.me/RSS/Bangumi?bangumiId=3060")
                .setBgmUrl("https://bgm.tv/subject/373247"));
        AniUtil.getAniList().add(ani("https://anibt.net/rss/anime.xml?bgmId=373247&groupSlug=group-a"));
        AniUtil.getAniList().add(ani("https://animes.garden/rss?subject=373247&fansub=subgroup-a"));
        AniUtil.getAniList().add(ani("https://anibt.net/rss/anime.xml?bgmId=500000&groupSlug=group-b"));

        assertEquals(List.of("373247", "500000"), BgmUtil.getSubjectIdList());
    }

    @Test
    void get_subject_id_still_rejects_blank_url() {
        assertThrows(Exception.class, () -> BgmUtil.getSubjectId(""));
    }
}
