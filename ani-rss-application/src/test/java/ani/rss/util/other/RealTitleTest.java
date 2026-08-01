package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用 mikan 站点抓取的真实种子标题验证 RenameUtil.rename:
 * 覆盖剧场版/OVA/普通番剧(近期+老番), 数据源 src/test/resources/real-titles.json
 */
class RealTitleTest {

    private static final String RESOURCE = "real-titles.json";

    private Ani ani(boolean ova, String mediaType, String title) {
        Ani ani = new Ani();
        ani.setTitle(title)
                .setOva(ova)
                .setMediaType(mediaType)
                .setSeason(1)
                .setOffset(0)
                .setBgmUrl("https://bgm.tv/subject/123")
                .setThemoviedbName("")
                .setNamingVersion(2)
                .setCustomRenameTemplateEnable(false)
                .setCustomEpisode(false)
                .setReleaseDate(new Date(116, 9, 21));
        return ani;
    }

    private Item item(String title) {
        Item item = new Item();
        item.setTitle(title);
        item.setEpisode(1.0);
        item.setTorrent("magnet:?xt=urn:btih:abcdef0123456789");
        item.setInfoHash("abcdef0123456789");
        item.setSubgroup("测试字幕组");
        return item;
    }

    private void resetConfig() {
        ConfigUtil.CONFIG.setRenameTemplate(null)
                .setOvaRenameTemplate(null)
                .setRenameDelYear(false)
                .setRenameDelTmdbId(false);
    }

    private List<String> loadGroup(String key) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "缺少测试资源 " + RESOURCE);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JSONObject obj = JSONUtil.parseObj(json);
            JSONArray arr = obj.getJSONArray(key);
            List<String> out = new ArrayList<>();
            for (Object o : arr) {
                out.add(String.valueOf(o));
            }
            return out;
        } catch (Exception e) {
            fail("读取测试数据失败: " + e.getMessage());
            return List.of();
        }
    }

    @Test
    void all_real_titles_no_exception() {
        // 冒烟: 全部真实标题(剧场版/OVA/普通番剧/老番)rename 不抛异常
        resetConfig();
        String[] groups = {"movie", "ova", "tv_fresh", "tv_fresh2", "tv_old"};
        int total = 0;
        for (String g : groups) {
            for (String t : loadGroup(g)) {
                boolean ova = "movie".equals(g) || "ova".equals(g);
                String mediaType = "movie".equals(g) ? "movie" : ("ova".equals(g) ? "ova" : null);
                Ani ani = ani(ova, mediaType, "测试番剧");
                Item item = item(t);
                try {
                    RenameUtil.rename(ani, item);
                    total++;
                } catch (Exception e) {
                    fail("[" + g + "] rename 抛异常: " + t + " -> " + e);
                }
            }
        }
        assertTrue(total > 0, "应至少处理一条真实标题");
    }

    @Test
    void movie_real_titles_no_episode_format() {
        // 剧场版: 电影式命名, reName 不应含 SxxExx 剧集结构
        resetConfig();
        for (String t : loadGroup("movie")) {
            Ani ani = ani(true, "movie", "鬼灭之刃 无限城篇");
            Item item = item(t);
            assertTrue(RenameUtil.rename(ani, item), t);
            String reName = item.getReName();
            assertFalse(reName.matches(".*S\\d{2}\\s*[.]?E\\d+.*"),
                    "剧场版不应带剧集格式: " + t + " -> " + reName);
        }
    }

    @Test
    void ova_real_titles_use_s00() {
        // OVA(特典式): reName 应含 S00(season=0); 年份标签([1996] 等)不应被当集数
        resetConfig();
        for (String t : loadGroup("ova")) {
            Ani ani = ani(true, "ova", "日常 OVA");
            Item item = item(t);
            assertTrue(RenameUtil.rename(ani, item), t);
            String reName = item.getReName();
            assertTrue(reName.matches(".*S00.*"),
                    "OVA 特典式应含 S00: " + t + " -> " + reName);
            assertFalse(reName.matches(".*S00\\s*[.]?E\\d{4}.*"),
                    "年份标签被当集数: " + t + " -> " + reName);
        }
    }

    @Test
    void tv_real_titles_use_season_episode() {
        // 普通番剧: reName 应含 SxxExx 结构
        resetConfig();
        for (String t : loadGroup("tv_fresh")) {
            Ani ani = ani(false, null, "猫耳魔女的修行");
            Item item = item(t);
            if (RenameUtil.rename(ani, item)) {
                String reName = item.getReName();
                assertTrue(reName.matches(".*S\\d{2}\\s*[.]?E\\d+.*"),
                        "普通番剧应含 SxxExx: " + t + " -> " + reName);
            }
            // 无集数解析失败的标题允许返回 false(不抛异常即可)
        }
    }
}
