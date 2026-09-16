package ani.rss.util.other;

import ani.rss.commons.CacheUtils;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BGM 剧集列表缓存。
 * <p>
 * (P2-13) 此前 {@code getEpisodes} 完全没有缓存，同一番剧在同一轮里会被
 * {@code getEpisodeId} / {@code getEpisodeTitleMap} / {@code getEps} 各抓一次。
 * <p>
 * 全部用例只走"缓存命中"路径，<b>不发起真实网络</b>。
 */
class BgmUtilEpisodeCacheTest {

    private static final String SUBJECT_ID = "BgmUtilEpisodeCacheTest-99999";
    private static final String KEY = BgmUtil.episodesCacheKey(SUBJECT_ID, 0);

    /**
     * 贴近真实响应：type=0 是正片，getEpisodes 会按 type 过滤
     */
    private static final String SNAPSHOT = """
            [
              {"id":"ep-1","ep":1,"sort":1,"type":0,"name":"第一话","name_cn":"第一集"},
              {"id":"ep-2","ep":2,"sort":2,"type":0,"name":"第二话","name_cn":"第二集"}
            ]
            """;

    @AfterEach
    void tearDown() {
        CacheUtils.remove(KEY);
    }

    @Test
    void cached_snapshot_is_returned_on_hit() {
        CacheUtils.put(KEY, SNAPSHOT, TimeUnit.MINUTES.toMillis(5));

        List<JsonObject> episodes = BgmUtil.getEpisodes(SUBJECT_ID, 0);

        assertEquals(2, episodes.size(), "缓存命中时应直接返回快照内容");
        assertEquals("ep-1", episodes.get(0).get("id").getAsString());
        assertEquals(1, episodes.get(0).get("ep").getAsInt());
        assertEquals("第二话", episodes.get(1).get("name").getAsString());
    }

    @Test
    void each_hit_returns_an_independent_list_and_elements() {
        CacheUtils.put(KEY, SNAPSHOT, TimeUnit.MINUTES.toMillis(5));

        List<JsonObject> first = BgmUtil.getEpisodes(SUBJECT_ID, 0);
        List<JsonObject> second = BgmUtil.getEpisodes(SUBJECT_ID, 0);

        // 快照反序列化天然给出独立实例：某个调用点就地改 JsonObject 不会污染其它调用点
        assertNotEquals(System.identityHashCode(first), System.identityHashCode(second),
                "两次命中不应返回同一个 List 实例");
        first.get(0).addProperty("被就地修改", true);
        assertEquals("ep-1", second.get(0).get("id").getAsString());
        assertTrue(!second.get(0).has("被就地修改"),
                "第一个调用点的就地修改不应出现在第二个调用点的结果里");
    }

    @Test
    void type_is_part_of_the_cache_key() {
        // 正片与番外是两次不同的查询结果（客户端按 type 过滤），不能互相命中
        assertNotEquals(BgmUtil.episodesCacheKey(SUBJECT_ID, 0),
                BgmUtil.episodesCacheKey(SUBJECT_ID, 1));
        assertNotEquals(BgmUtil.episodesCacheKey(SUBJECT_ID, 0),
                BgmUtil.episodesCacheKey(SUBJECT_ID + "x", 0));
    }

    @Test
    void empty_snapshot_yields_empty_list_not_null() {
        CacheUtils.put(KEY, "[]", TimeUnit.MINUTES.toMillis(5));

        List<JsonObject> episodes = BgmUtil.getEpisodes(SUBJECT_ID, 0);

        // 调用方会直接迭代/取 size，返回 null 会 NPE
        assertTrue(episodes != null, "不应返回 null");
        assertTrue(episodes.isEmpty(), "空数组快照应得到空列表");
    }
}
