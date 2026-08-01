package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全量真实种子适配分析(不设断言, 输出问题样本报告)
 * 数据源: src/test/resources/all-real-titles.json (8553 条, mikan 抓取)
 */
class AdaptAnalyzeTest {

    private static final String RESOURCE = "all-real-titles.json";

    private List<String> group(String key) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "缺少 " + RESOURCE);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JSONArray arr = JSONUtil.parseObj(json).getJSONObject("groups").getJSONArray(key);
            List<String> out = new ArrayList<>();
            for (Object o : arr) out.add(String.valueOf(o));
            return out;
        }
    }

    private Ani ani(boolean ova, String mediaType, String title) {
        Ani ani = new Ani();
        ani.setTitle(title).setOva(ova).setMediaType(mediaType)
                .setSeason(1).setOffset(0).setBgmUrl("https://bgm.tv/subject/123")
                .setThemoviedbName("").setNamingVersion(2)
                .setCustomRenameTemplateEnable(false).setCustomEpisode(false)
                .setReleaseDate(new Date(116, 9, 21));
        return ani;
    }

    private Item item(String title) {
        Item item = new Item();
        item.setTitle(title).setEpisode(1.0)
                .setTorrent("magnet:?xt=urn:btih:abcdef0123456789")
                .setInfoHash("abcdef0123456789").setSubgroup("测试字幕组");
        return item;
    }

    @Test
    void analyze() throws Exception {
        ConfigUtil.CONFIG.setRenameTemplate(null).setOvaRenameTemplate(null)
                .setRenameDelYear(false).setRenameDelTmdbId(false);

        Map<String, List<String[]>> problems = new LinkedHashMap<>();
        Map<String, int[]> stats = new LinkedHashMap<>();

        String[][] defs = {
                {"tv", "false", "测试番剧"},
                {"movie", "true", "movie", "鬼灭之刃 无限城篇"},
                {"ova", "true", "ova", "日常 OVA"},
        };

        for (String[] def : defs) {
            String g = def[0];
            boolean ova = Boolean.parseBoolean(def[1]);
            String mediaType = def.length > 2 && !"null".equals(def[2]) ? def[2] : null;
            String title = def[def.length - 1];
            List<String> titles = group(g);
            int ok = 0, fail = 0, except = 0;
            List<String[]> list = new ArrayList<>();
            for (String t : titles) {
                Item item = item(t);
                try {
                    if (RenameUtil.rename(ani(ova, mediaType, title), item)) {
                        ok++;
                        String rn = item.getReName();
                        // 可疑: 未替换占位符 / 空
                        if (rn.contains("${")) {
                            list.add(new String[]{t, "未替换占位符", rn});
                        }
                        // movie: 不应含 SxxExx; ova: 年份不当集数
                        if ("movie".equals(g) && rn.matches(".*S\\d{2}\\s*[.]?E\\d+.*")) {
                            list.add(new String[]{t, "电影带剧集格式", rn});
                        }
                        if ("ova".equals(g) && rn.matches(".*S00\\s*[.]?E\\d{4}.*")) {
                            list.add(new String[]{t, "年份当集数", rn});
                        }
                    } else {
                        fail++;
                        list.add(new String[]{t, "解析失败(返回false)", item.getReName()});
                    }
                } catch (Exception e) {
                    except++;
                    list.add(new String[]{t, "异常: " + e.getClass().getSimpleName(), String.valueOf(e.getMessage()).substring(0, Math.min(80, String.valueOf(e.getMessage()).length()))});
                }
            }
            stats.put(g, new int[]{ok, fail, except});
            problems.put(g, list);
            System.out.println("==== " + g + ": 总数=" + titles.size()
                    + " 成功=" + ok + " 失败=" + fail + " 异常=" + except
                    + " 适配率=" + (titles.isEmpty() ? 0 : (100.0 * ok / titles.size())) + "% ====");
        }

        System.out.println("\n===== 问题样本(前 40 条/组) =====");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String[]>> e : problems.entrySet()) {
            System.out.println("--- " + e.getKey() + " 问题数: " + e.getValue().size() + " ---");
            for (String[] p : e.getValue()) {
                System.out.println("[" + p[1] + "] " + p[0]);
                System.out.println("    -> " + p[2]);
                sb.append(e.getKey()).append("\t").append(p[1]).append("\t").append(p[0]).append("\n");
            }
        }
        // 落盘便于分析
        java.nio.file.Files.write(java.nio.file.Path.of("target/adapt-problems.txt"),
                sb.toString().getBytes(StandardCharsets.UTF_8));

        // 适配率底线(防止正则改动导致真实种子适配率回退)
        int[] movieS = stats.get("movie");
        int[] ovaS = stats.get("ova");
        int[] tvS = stats.get("tv");
        assertEquals(movieS[0], movieS[0] + movieS[1], "movie 不应有解析失败");
        assertEquals(ovaS[0], ovaS[0] + ovaS[1], "ova 不应有解析失败");
        double tvRate = 100.0 * tvS[0] / (tvS[0] + tvS[1]);
        assertTrue(tvRate >= 95.0, "tv 适配率过低: " + tvRate + "%(失败 " + tvS[1] + " 条)");
        System.out.println("适配率底线校验通过: tv=" + String.format("%.2f", tvRate) + "%");
    }
}
