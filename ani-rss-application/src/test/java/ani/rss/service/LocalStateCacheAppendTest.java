package ani.rss.service;

import ani.rss.entity.Ani;
import ani.rss.util.other.ConfigUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结果缓存的「增量追加」维护方式。
 * <p>
 * 背景：原先某一集离线归位成功后是 {@code LocalStateCache.invalidate(ani)} —— 把整份快照打掉，
 * 下一轮重新列举网盘。网盘列举是最贵的一步（限流 + 单轮预算），而"这一集刚落地"是<b>已知</b>的
 * 增量事实，为此重列不划算；更要紧的是，重列会把"列举失败"这条路径拉回每轮必经，
 * 而查询失败一旦被当成"目录里什么都没有"，就会演变成删记录重下。
 * <p>
 * 因此改为追加维护，并把结果缓存 TTL 统一放到天级（见 {@link LocalStateCacheTest}）。
 * 追加的语义约束由本用例固化：只增不减、不创建残缺快照、不延长 TTL、不放宽 complete。
 */
class LocalStateCacheAppendTest {

    private static final String PATH = "/115/动漫/转存/追番/某番 (2026) [tmdbid=1]/Season 1";

    private Integer prevTtl;

    @BeforeEach
    void setUp() {
        prevTtl = ConfigUtil.CONFIG.getStateCacheTtlDays();
        LocalStateCache.clear();
    }

    @AfterEach
    void tearDown() {
        ConfigUtil.CONFIG.setStateCacheTtlDays(prevTtl);
        LocalStateCache.clear();
    }

    private static Ani ani(String id) {
        return new Ani().setId(id).setTitle("测试订阅" + id);
    }

    private static LocalStateCache.Snapshot seed(Ani ani, Set<String> index, boolean complete) throws Exception {
        return LocalStateCache.getOrBuild(ani, PATH, LocalStateCache.Source.CLOUD_API,
                () -> LocalStateCache.Loaded.of(index, complete));
    }

    /**
     * 命中已缓存的快照，不应触发任何重新列举 —— 用"loader 直接抛异常"来证明它没被调用。
     */
    private static LocalStateCache.Snapshot cachedWithoutRebuild(Ani ani) throws Exception {
        return LocalStateCache.getOrBuild(ani, PATH, LocalStateCache.Source.CLOUD_API,
                () -> {
                    throw new IllegalStateException("不应重新列举网盘");
                });
    }

    @Test
    @DisplayName("追加把本集并入已有快照，且不触发重新列举")
    void append_merges_into_existing_snapshot() throws Exception {
        Ani ani = ani("a1");
        seed(ani, Set.of("1:1", "1:2"), true);

        assertTrue(LocalStateCache.appendEpisode("a1", PATH, Set.of("1:3")),
                "有新条目时应报告追加成功");

        assertEquals(Set.of("1:1", "1:2", "1:3"), cachedWithoutRebuild(ani).episodeIndex());
    }

    @Test
    @DisplayName("快照不存在时追加是 no-op，不创建只有一集的残缺快照")
    void append_without_snapshot_is_noop() {
        assertFalse(LocalStateCache.appendEpisode("a1", PATH, Set.of("1:3")),
                "没有可追加的快照时应返回 false");
        assertEquals(0, LocalStateCache.size(), "不应因此凭空造出一份快照");
    }

    @Test
    @DisplayName("条目已存在时不改写快照")
    void append_existing_key_is_noop() throws Exception {
        Ani ani = ani("a1");
        LocalStateCache.Snapshot before = seed(ani, Set.of("1:1", "1:2"), true);

        assertFalse(LocalStateCache.appendEpisode("a1", PATH, Set.of("1:2")));

        assertSame(before, cachedWithoutRebuild(ani), "无新增时不应替换快照对象");
    }

    /**
     * builtAt 必须保持"上次真实列举"的时刻。若追加把它刷新成 now，
     * 一份内容陈旧的快照就会因为不断有集落地而无限续命，兜底对账永远等不到。
     */
    @Test
    @DisplayName("追加不延长 TTL：builtAt 保持上次真实列举的时刻")
    void append_does_not_refresh_built_at() throws Exception {
        ConfigUtil.CONFIG.setStateCacheTtlDays(10);
        Ani ani = ani("a1");
        long builtAt = System.currentTimeMillis() - 60_000L;
        LocalStateCache.putForTest("a1", PATH, Set.of("1:1"), LocalStateCache.Source.CLOUD_API, builtAt);

        assertTrue(LocalStateCache.appendEpisode("a1", PATH, Set.of("1:2")));

        LocalStateCache.Snapshot after = cachedWithoutRebuild(ani);
        assertEquals(builtAt, after.builtAt(), "builtAt 不应被追加刷新");
        assertEquals(Set.of("1:1", "1:2"), after.episodeIndex());
    }

    /**
     * 列举被截断（complete=false）意味着"只能确认存在、不能断言不存在"。
     * 追加已知存在的条目并不能让这个能力变强，complete 必须保持 false。
     */
    @Test
    @DisplayName("追加不放宽 complete：被截断的列举仍不可断言「不存在」")
    void append_keeps_incomplete_flag() throws Exception {
        Ani ani = ani("a1");
        seed(ani, Set.of("1:1"), false);

        assertTrue(LocalStateCache.appendEpisode("a1", PATH, Set.of("1:2")));

        LocalStateCache.Snapshot after = cachedWithoutRebuild(ani);
        assertFalse(after.complete(), "追加不得把不完整的列举提升为完整");
        assertEquals(LocalStateCache.Source.CLOUD_API, after.source(), "来源不应改变");
    }

    @Test
    @DisplayName("期间被失效时追加不落地，避免把陈旧结果写回")
    void append_after_invalidate_is_dropped() throws Exception {
        Ani ani = ani("a1");
        seed(ani, Set.of("1:1"), true);

        LocalStateCache.invalidate("a1");

        assertFalse(LocalStateCache.appendEpisode("a1", PATH, Set.of("1:2")),
                "快照已被失效，追加不应把它复活");
        assertEquals(0, LocalStateCache.size());
    }

    @Test
    @DisplayName("追加成功后计数可见，便于对照列举次数看收益")
    void append_count_is_exposed() throws Exception {
        Ani ani = ani("a1");
        seed(ani, Set.of("1:1"), true);
        long before = LocalStateCache.getAppendedCount();

        LocalStateCache.appendEpisode("a1", PATH, Set.of("1:2"));

        assertEquals(before + 1, LocalStateCache.getAppendedCount());
        assertEquals(before + 1, ((Number) LocalStateCache.stats().get("appended")).longValue());
    }
}
