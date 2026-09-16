package ani.rss.service.subtitle;

import ani.rss.entity.Config;
import ani.rss.util.other.ConfigUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 字幕下载的「计划内字节缓存」。
 * <p>
 * (P1-17) 合集压缩包候选会在 {@code SubtitleService#planOne} 的逐视频循环里被反复取用：
 * 同一 URL 若不缓存，每个视频都要把整包重新下载一遍（一季 12~24 集 = 同样一次下载做 12~24 次）。
 * <p>
 * 通过覆写 {@link AssrtSubtitleProvider#fetchBytes} 做计数，<b>不发起真实网络</b>。
 */
class AssrtSubtitleProviderBytesCacheTest {

    private Config previousConfig;

    /**
     * 记录每个 URL 被真正取了几次
     */
    private static class CountingProvider extends AssrtSubtitleProvider {
        final Map<String, Integer> hits = new HashMap<>();
        final Map<String, byte[]> canned = new HashMap<>();

        @Override
        byte[] fetchBytes(String url) {
            hits.merge(url, 1, Integer::sum);
            return canned.getOrDefault(url, new byte[0]);
        }
    }

    @BeforeEach
    void setUp() {
        previousConfig = ConfigUtil.CONFIG;
        // 关掉字幕元数据解析：避免用例里触发 TMDB 查询（download 会走 SubtitleSeasonResolver）
        ConfigUtil.CONFIG = new Config().setSubtitleMetaEnabled(false);
    }

    @AfterEach
    void tearDown() {
        ConfigUtil.CONFIG = previousConfig;
    }

    private static SubtitleCandidate singleFile(String url) {
        SubtitleCandidate c = new SubtitleCandidate();
        c.setUrl(url);
        c.setFileName("测试番剧 - 01.ass");
        c.setExt("ass");
        c.setArchive(false);
        return c;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void without_cache_same_url_is_fetched_every_time() {
        CountingProvider provider = new CountingProvider();
        String url = "https://example.invalid/sub-uncached.ass";
        provider.canned.put(url, bytes("[Script Info]"));

        // 旧行为：逐视频调用会重复下载同一 URL
        provider.download(singleFile(url), "chs", 1, 1, "测试番剧 - 01.mkv", null);
        provider.download(singleFile(url), "chs", 1, 2, "测试番剧 - 02.mkv", null);

        assertEquals(2, provider.hits.get(url), "不传缓存时应每次都取（对照旧行为）");
    }

    @Test
    void with_cache_same_url_is_fetched_once() {
        CountingProvider provider = new CountingProvider();
        String url = "https://example.invalid/sub-cached.ass";
        provider.canned.put(url, bytes("[Script Info]"));

        Map<String, byte[]> cache = new HashMap<>();
        provider.download(singleFile(url), "chs", 1, 1, "测试番剧 - 01.mkv", null, cache);
        provider.download(singleFile(url), "chs", 1, 2, "测试番剧 - 02.mkv", null, cache);

        assertEquals(1, provider.hits.get(url), "同一 URL 在计划内只应下载一次");
    }

    @Test
    void cached_bytes_are_the_same_content() {
        CountingProvider provider = new CountingProvider();
        String url = "https://example.invalid/sub-content.ass";
        byte[] content = bytes("[Script Info]\nTitle: 测试");
        provider.canned.put(url, content);

        Map<String, byte[]> cache = new HashMap<>();
        SubtitlePick first = provider.download(singleFile(url), "chs", 1, 1, "测试番剧 - 01.mkv", null, cache);
        SubtitlePick second = provider.download(singleFile(url), "chs", 1, 2, "测试番剧 - 02.mkv", null, cache);

        assertNotNull(first);
        assertNotNull(second);
        assertArrayEquals(content, first.getContent());
        assertArrayEquals(content, second.getContent(), "缓存命中应给出与首次完全一致的内容");
    }

    @Test
    void cache_stores_post_redirect_bytes_so_redirect_is_followed_once() {
        CountingProvider provider = new CountingProvider();
        String url = "https://example.invalid/redirect.ass";
        String realUrl = "https://cdn.example.invalid/real.ass";
        // 首次返回 JSON 重定向，真实内容在另一个 URL
        provider.canned.put(url, bytes("{\"url\":\"" + realUrl + "\"}"));
        provider.canned.put(realUrl, bytes("[Script Info]"));

        Map<String, byte[]> cache = new HashMap<>();
        provider.download(singleFile(url), "chs", 1, 1, "测试番剧 - 01.mkv", null, cache);
        provider.download(singleFile(url), "chs", 1, 2, "测试番剧 - 02.mkv", null, cache);

        // 缓存键是原始 URL，存的是"跟随重定向之后"的字节：第二次不应再走一次重定向
        assertEquals(1, provider.hits.get(url));
        assertEquals(1, provider.hits.get(realUrl), "重定向目标也只应取一次");
    }

    @Test
    void empty_result_is_not_cached() {
        CountingProvider provider = new CountingProvider();
        String url = "https://example.invalid/empty.ass";
        // 未配置 canned → fetchBytes 返回空数组，模拟取不到内容

        Map<String, byte[]> cache = new HashMap<>();
        provider.download(singleFile(url), "chs", 1, 1, "测试番剧 - 01.mkv", null, cache);
        provider.download(singleFile(url), "chs", 1, 2, "测试番剧 - 02.mkv", null, cache);

        // 失败/空结果绝不能入缓存，否则一次抖动会被固化成"这个候选是空的"
        assertEquals(2, provider.hits.get(url), "空结果不应被缓存");
    }
}
