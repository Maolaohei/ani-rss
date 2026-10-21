package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 洗版纯判定的补充边界。
 * <p>
 * v1→v2、本版记录即终态、重复记录与合集最小版本已由 {@code WashDecisionTest} /
 * {@code WashStateTest} 覆盖；本类只补跨季、半集、订阅隔离、电影式主名和半集计划过滤。
 */
class WashContractTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.toString());
        WashState.resetForTest();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("CONFIG");
        WashState.resetForTest();
    }

    @Test
    @DisplayName("同一订阅的不同季不共享集数版本")
    void same_episode_in_different_seasons_is_independent() {
        Ani firstSeason = ani("ani-1", 1);
        Ani secondSeason = ani("ani-1", 2);

        assertTrue(WashState.markDownloaded(firstSeason, episode(1.0, 2)));

        assertEquals(2, WashState.versionOf(firstSeason, episode(1.0, 2)));
        assertEquals(0, WashState.versionOf(secondSeason, episode(1.0, 2)),
                "S01E01 的落地版本不得泄漏到 S02E01");
    }

    @Test
    @DisplayName("整数集与半集使用独立状态")
    void integer_and_half_episode_are_independent() {
        Ani ani = ani("ani-1", 1);
        WashState.markDownloaded(ani, episode(1.0, 2));

        assertEquals(2, WashState.versionOf(ani, episode(1.0, 2)));
        assertEquals(0, WashState.versionOf(ani, episode(1.5, 2)),
                "E01 与 E01.5 是两集，不能互相冒充");

        WashState.markDownloaded(ani, episode(1.5, 3));
        assertEquals(3, WashState.versionOf(ani, episode(1.5, 3)));
    }

    @Test
    @DisplayName("不同订阅的同一季集版本互不影响")
    void same_episode_in_different_subscriptions_is_independent() {
        Ani first = ani("ani-1", 1);
        Ani second = ani("ani-2", 1);

        WashState.markDownloaded(first, episode(1.0, 2));

        assertEquals(2, WashState.versionOf(first, episode(1.0, 2)));
        assertEquals(0, WashState.versionOf(second, episode(1.0, 2)));
    }

    @Test
    @DisplayName("电影式条目按主名记账，大小写与首尾空白不敏感")
    void movie_identity_is_case_and_whitespace_insensitive() {
        Ani ani = ani("ani-movie", 1);
        Item downloaded = new Item()
                .setTitle("Show Movie v2")
                .setReName("  Show Movie  ")
                .setVersion(2);
        Item lookup = new Item()
                .setReName("show movie")
                .setVersion(2);

        assertTrue(WashState.markDownloaded(ani, downloaded));
        assertEquals(2, WashState.versionOf(ani, lookup));
    }

    @Test
    @DisplayName("计划过滤只保留命中的半集，不把整数集一起放行")
    void torrent_plan_filters_exact_half_episode() {
        Item first = new Item().setTitle("Show S01E01.mkv").setEpisode(1.0);
        Item half = new Item().setTitle("Show S01E01.5.mkv").setEpisode(1.5);
        Item second = new Item().setTitle("Show S01E02.mkv").setEpisode(2.0);
        List<Item> plan = List.of(first, half, second);

        List<Item> filtered = TorrentPlanUtil.filterEpisodes(plan, List.of(1.5));

        assertEquals(List.of(half), filtered);
    }

    private static Ani ani(String id, int season) {
        return new Ani()
                .setId(id)
                .setTitle("Show")
                .setSeason(season)
                .setNamingVersion(2);
    }

    private static Item episode(double number, Integer version) {
        return new Item()
                .setTitle("Show E" + number + " v" + version)
                .setReName("Show S01E" + number)
                .setEpisode(number)
                .setVersion(version);
    }
}
