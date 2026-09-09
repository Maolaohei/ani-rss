package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ItemsUtil XML 层护栏: 用真实 feed 样本(mikan/nyaa/dmhy/acg.rip/bangumi.moe)
 * 直接喂 buildItems, 验证字段提取/异常源容错/磁力兜底/哈希安全合成。
 * rename 过滤/展开/去重属后续语义, 由专项测试覆盖, 不在本层断言。
 */
class ItemsUtilFeedTest {

    private Ani ani;

    @BeforeEach
    void setUp() {
        ani = new Ani();
        ani.setTitle("测试番剧").setOva(false).setSeason(1).setOffset(0)
                .setBgmUrl("https://bgm.tv/subject/123").setThemoviedbName("")
                .setNamingVersion(2).setCustomRenameTemplateEnable(false)
                .setCustomEpisode(false)
                .setExclude(new java.util.ArrayList<>()).setMatch(new java.util.ArrayList<>())
                .setGlobalExclude(false);
        ConfigUtil.CONFIG.setRenameTemplate(null).setOvaRenameTemplate(null)
                .setRenameDelYear(false).setRenameDelTmdbId(false).setSkip5(false);
        if (ConfigUtil.CONFIG.getExclude() == null) {
            ConfigUtil.CONFIG.setExclude(new java.util.ArrayList<>());
        }
    }

    private String feed(String name) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("feeds/" + name)) {
            assertNotNull(in, "缺少 fixture feeds/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<Item> parse(String feedFile) throws Exception {
        return ItemsUtil.buildItems(ani, feed(feedFile), "test://" + feedFile, "测试字幕组");
    }

    @Test
    void mikanClassic() throws Exception {
        List<Item> items = parse("mikan-classic.xml");
        assertEquals(100, items.size(), "mikan-classic 应解析出 100 条");
        for (Item item : items) {
            assertNotNull(item.getInfoHash());
            assertTrue(ItemHashValid.check(item.getInfoHash()), "infoHash 非法: " + item.getInfoHash());
            assertFalse(item.getTitle().isBlank());
            assertNotNull(item.getTorrent());
        }
    }

    @Test
    void mikanBangumiTorrentNamespace() throws Exception {
        List<Item> items = parse("mikan-bangumi-3060.xml");
        assertEquals(320, items.size());
        // torrent 命名空间 infohash 优先于 enclosure url 主名
        assertTrue(items.stream().allMatch(i -> ItemHashValid.check(i.getInfoHash())));
    }

    @Test
    void nyaaWithoutEnclosure() throws Exception {
        List<Item> items = parse("nyaa.xml");
        assertEquals(75, items.size(), "nyaa 无 enclosure, 应由 infoHash 构造 magnet 兜底");
        for (Item item : items) {
            assertNotNull(item.getTorrent());
            // magnet 兜底或 .torrent 直链(部分 nyaa 条目 link 即种子)
            assertTrue(item.getTorrent().startsWith("magnet:?xt=urn:btih:")
                            || item.getTorrent().endsWith(".torrent"),
                    "nyaa 兜底应为 magnet 或直链: " + item.getTorrent());
            assertTrue(item.getInfoHash().matches("^[0-9a-f]{40}$"),
                    "nyaa infoHash 应为 40 位 hex: " + item.getInfoHash());
        }
    }

    @Test
    void dmhyMagnetEnclosure() throws Exception {
        List<Item> items = parse("dmhy.xml");
        assertEquals(500, items.size());
        // dmhy enclosure 是 magnet 链接, base32 哈希
        assertTrue(items.stream().anyMatch(i -> i.getTorrent().startsWith("magnet:")));
        assertTrue(items.stream().allMatch(i -> ItemHashValid.check(i.getInfoHash())));
    }

    @Test
    void acgripSyntheticHash() throws Exception {
        // acg.rip 种子 URL 为数字 ID 非哈希: 应以 URL 的 SHA-256 合成稳定标识, 整源不拒收
        List<Item> items = parse("acgrip.xml");
        assertEquals(30, items.size(), "acg.rip 不得因 URL 形态整站丢弃");
        for (Item item : items) {
            assertTrue(item.getInfoHash().matches("^[0-9a-f]{64}$"),
                    "acg.rip 合成哈希应为 64 位 hex: " + item.getInfoHash());
            assertTrue(item.getTorrent().endsWith(".torrent"));
        }
    }

    @Test
    void bangumiMoe() throws Exception {
        List<Item> items = parse("bangumi-moe.xml");
        assertEquals(50, items.size());
        assertTrue(items.stream().allMatch(i -> ItemHashValid.check(i.getInfoHash())));
    }

    @Test
    void allFeedsTitleAndTorrentPresent() throws Exception {
        // 任何真实源解析结果: 标题非空, 种子链接存在, 无占位符残留
        for (String f : new String[]{"mikan-classic.xml", "mikan-bangumi-3060.xml",
                "nyaa.xml", "dmhy.xml", "acgrip.xml", "bangumi-moe.xml"}) {
            for (Item item : parse(f)) {
                assertFalse(item.getTitle().isBlank(), f + " 标题为空");
                assertFalse(item.getTorrent().isBlank(), f + " 缺种子链接: " + item.getTitle());
            }
        }
    }

    @Test
    void missingChannelThrows() {
        String xml = "<?xml version=\"1.0\"?><html><body>登录拦截页</body></html>";
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> ItemsUtil.buildItems(ani, xml, "test://intercept", "测试字幕组"));
        assertTrue(ex.getMessage().contains("RSS 无 channel 节点"),
                "缺 channel 应报明确错误: " + ex.getMessage());
    }

    @Test
    void malformedXmlThrows() {
        String xml = "<?xml version=\"1.0\"?><rss><channel><item><title>未闭合";
        assertThrows(RuntimeException.class,
                () -> ItemsUtil.buildItems(ani, xml, "test://bad", "测试字幕组"));
    }

    @Test
    void doctypeXxeNeutralized() {
        // XXE 载荷: 严格模式拒绝 DOCTYPE, 宽松模式禁外部实体 → 不得回显本地文件
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<rss><channel><item><title>&xxe;</title></item></channel></rss>";
        try {
            List<Item> items = ItemsUtil.buildItems(ani, xml, "test://xxe", "测试字幕组");
            assertTrue(items.isEmpty() || items.stream().noneMatch(i -> i.getTitle().contains("root:")),
                    "XXE 实体不得回显本地文件");
        } catch (RuntimeException expected) {
            // 严格/宽松都拒绝 → 允许
        }
    }

    @Test
    void enclosureWithoutUrlSkipped() {
        String xml = "<?xml version=\"1.0\"?><rss><channel>"
                + "<item><title>[A] 番剧 第01话 [1080p]</title>"
                + "<enclosure type=\"application/x-bittorrent\" length=\"1\"/></item>"
                + "</channel></rss>";
        List<Item> items = ItemsUtil.buildItems(ani, xml, "test://nourl", "测试字幕组");
        assertTrue(items.isEmpty(), "enclosure 缺 url 应跳过该条");
    }

    @Test
    void pathTraversalHashNeutralized() {
        // 路径穿越载荷: infoHash 合成 SHA-256 后必须为路径安全的 hex, 不得携带 ../
        String xml = "<?xml version=\"1.0\"?><rss><channel>"
                + "<item><title>[A] 番剧 第01话 [1080p]</title>"
                + "<link>https://e/../../../etc/passwd.torrent</link></item>"
                + "</channel></rss>";
        List<Item> items = ItemsUtil.buildItems(ani, xml, "test://traversal", "测试字幕组");
        assertEquals(1, items.size(), "穿越载荷应被中和而非崩溃");
        String hash = items.get(0).getInfoHash();
        assertTrue(hash.matches("^[0-9a-f]{64}$"), "合成哈希应为 64 位 hex: " + hash);
        assertFalse(hash.contains("..") || hash.contains("/"), "infoHash 不得含路径字符");
    }

    @Test
    void magnetEnclosureExtractsHash() {
        String xml = "<?xml version=\"1.0\"?><rss><channel>"
                + "<item><title>[A] 番剧 第01话 [1080p]</title>"
                + "<enclosure url=\"magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&amp;dn=x\"/></item>"
                + "</channel></rss>";
        List<Item> items = ItemsUtil.buildItems(ani, xml, "test://magnet", "测试字幕组");
        assertEquals(1, items.size());
        assertEquals("0123456789abcdef0123456789abcdef01234567", items.get(0).getInfoHash());
    }

    /** infoHash 合法形态: 40/64 位 hex 或 32 位 base32 */
    static final class ItemHashValid {
        private ItemHashValid() {
        }

        static boolean check(String hash) {
            return hash != null && (hash.matches("^[0-9a-f]{40}$")
                    || hash.matches("^[0-9a-f]{64}$") || hash.matches("^[a-z2-7]{32}$"));
        }
    }
}
