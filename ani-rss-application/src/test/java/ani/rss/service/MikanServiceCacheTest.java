package ani.rss.service;

import ani.rss.commons.CacheUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Mikan;
import ani.rss.entity.MikanInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mikan 番剧详情缓存。
 * <p>
 * (P2-13) 此前 {@code getMikanInfo} 完全没有缓存，同一番剧在「添加订阅」「BGM 轮次」等
 * per-ani 路径上会被反复抓取 + Jsoup 解析整页。
 * <p>
 * 缓存存 <b>JSON 快照</b>而不是对象本身：{@code MikanService.list} 会就地改 item 的
 * score/bgmId/exists，直接共享实例会把上一轮的展示状态带进下一轮。本用例把这条锁住。
 * <p>
 * 全部用例只走"缓存命中"路径，<b>不发起真实网络</b>。
 */
class MikanServiceCacheTest {

    private static final String BANGUMI_ID = "MikanServiceCacheTest-88888";
    private static final String KEY = MikanService.mikanInfoCacheKey(BANGUMI_ID);

    @AfterEach
    void tearDown() {
        CacheUtils.remove(KEY);
    }

    private static MikanInfo sample() {
        Mikan.Item ep1 = new Mikan.Item()
                .setTitle("[字幕组] 测试番剧 - 01 [1080p]")
                .setMagnet("magnet:?xt=urn:btih:aaaa")
                .setSize(1_000_000L);
        Mikan.Item ep2 = new Mikan.Item()
                .setTitle("[字幕组] 测试番剧 - 02 [1080p]")
                .setMagnet("magnet:?xt=urn:btih:bbbb")
                .setSize(1_100_000L);

        Mikan.Group group = new Mikan.Group()
                .setSubgroupId("213")
                .setLabel("测试字幕组")
                .setRss("https://mikanani.me/RSS/Bangumi?bangumiId=" + BANGUMI_ID)
                .setUpdateDay("周五")
                .setItems(List.of(ep1, ep2));

        return new MikanInfo()
                .setUrl("https://mikanani.me/Home/Bangumi/" + BANGUMI_ID)
                .setTitle("测试番剧")
                .setBgmUrl("https://bgm.tv/subject/510710")
                .setCover("https://mikanani.me/images/cover.jpg")
                .setGroups(List.of(group));
    }

    @Test
    void cached_snapshot_is_returned_on_hit() {
        CacheUtils.put(KEY, GsonStatic.toJson(sample()), TimeUnit.MINUTES.toMillis(2));

        MikanInfo got = MikanService.getMikanInfo(BANGUMI_ID);

        assertEquals("测试番剧", got.getTitle());
        assertEquals("https://bgm.tv/subject/510710", got.getBgmUrl());
        assertEquals(1, got.getGroups().size());
        assertEquals("测试字幕组", got.getGroups().get(0).getLabel());
        assertEquals(2, got.getGroups().get(0).getItems().size());
        assertEquals("[字幕组] 测试番剧 - 01 [1080p]",
                got.getGroups().get(0).getItems().get(0).getTitle());
    }

    @Test
    void each_hit_returns_an_independent_instance() {
        CacheUtils.put(KEY, GsonStatic.toJson(sample()), TimeUnit.MINUTES.toMillis(2));

        MikanInfo first = MikanService.getMikanInfo(BANGUMI_ID);
        MikanInfo second = MikanService.getMikanInfo(BANGUMI_ID);

        // 关键不变量：两次命中不能是同一个对象。
        // MikanService.list 会就地 setScore/setBgmId/setExists，共享实例会把展示状态串到下一轮。
        assertNotSame(first, second, "缓存命中必须返回互相独立的实例");
    }

    @Test
    void in_place_mutation_of_first_hit_does_not_leak_into_second() {
        CacheUtils.put(KEY, GsonStatic.toJson(sample()), TimeUnit.MINUTES.toMillis(2));

        MikanInfo first = MikanService.getMikanInfo(BANGUMI_ID);
        // 模拟 MikanService.list 的展示态写入
        first.setScore(9.5).setBgmId("510710").setExists(true);

        MikanInfo second = MikanService.getMikanInfo(BANGUMI_ID);

        assertTrue(second.getScore() == null, "上一轮写入的 score 不应出现在下一轮");
        assertTrue(second.getExists() == null, "上一轮写入的 exists 不应出现在下一轮");
    }

    @Test
    void cache_key_is_per_bangumi_id() {
        assertTrue(!MikanService.mikanInfoCacheKey(BANGUMI_ID)
                .equals(MikanService.mikanInfoCacheKey(BANGUMI_ID + "x")));
    }
}
