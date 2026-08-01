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
 * VCBD(VCB-Studio)压制组 RSS 适配分析: 整季 BDRip 压制包, 无单集集数
 */
class VcbAnalyzeTest {

    private static final String RESOURCE = "vcb-titles.json";

    private List<String> group(String key) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
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

        String[][] defs = {
                {"tv", "false", "测试番剧"},
                {"movie", "true", "movie", "名侦探柯南"},
                {"ova", "true", "ova", "玉响"},
        };
        for (String[] def : defs) {
            String g = def[0];
            boolean ova = Boolean.parseBoolean(def[1]);
            String mediaType = def.length > 2 && !"null".equals(def[2]) ? def[2] : null;
            String title = def[def.length - 1];
            List<String> titles = group(g);
            int ok = 0, fail = 0;
            List<String> fails = new ArrayList<>();
            for (String t : titles) {
                Item item = item(t);
                try {
                    if (RenameUtil.rename(ani(ova, mediaType, title), item)) {
                        ok++;
                    } else {
                        fail++;
                        fails.add(t);
                    }
                } catch (Exception e) {
                    fail++;
                    fails.add(t + " [异常 " + e.getClass().getSimpleName() + "]");
                }
            }
            System.out.println("==== " + g + ": 总数=" + titles.size() + " 成功=" + ok + " 失败=" + fail
                    + " 适配率=" + (titles.isEmpty() ? 0 : 100.0 * ok / titles.size()) + "% ====");
            System.out.println("--- 失败样本(前 25) ---");
            for (String f : fails.subList(0, Math.min(25, fails.size()))) {
                System.out.println("  " + f);
            }
        }
    }
}
