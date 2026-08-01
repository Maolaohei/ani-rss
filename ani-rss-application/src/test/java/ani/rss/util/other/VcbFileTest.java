package ani.rss.download;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VCBD 真实 BT 文件结构适配测试
 * 数据源: src/test/resources/vcb-files.json(从 dmhy 详情页抓取的完整文件列表)
 */
class VcbFileTest {

    private static final String RESOURCE = "vcb-files.json";

    private final OpenList openList = new OpenList();

    private List<JSONObject> load() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "缺少 " + RESOURCE);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JSONArray arr = JSONUtil.parseArray(json);
            List<JSONObject> out = new ArrayList<>();
            for (Object o : arr) out.add((JSONObject) o);
            return out;
        }
    }

    private List<String> videoFiles(JSONObject item) {
        List<String> out = new ArrayList<>();
        for (Object o : item.getJSONArray("files")) {
            String f = String.valueOf(o);
            if (f.toLowerCase().endsWith(".mkv") || f.toLowerCase().endsWith(".mka")
                    || f.toLowerCase().endsWith(".mp4")) {
                out.add(f);
            }
        }
        return out;
    }

    @Test
    void vcb_tv_episode_extraction() throws Exception {
        // tv 整季包: [01][Ma10p_1080p] 每集单文件; 正片必须提取到集数, SPs 特典允许无集数
        for (JSONObject item : load()) {
            if (!"tv".equals(item.getStr("type"))) continue;
            List<String> vids = videoFiles(item);
            assertTrue(vids.size() >= 10, "整季包应有 ≥10 集视频");
            Set<String> eps = new TreeSet<>();
            int special = 0;
            for (String v : vids) {
                String ep = openList.extractEpisodeFromFileName(v);
                if (ep == null) {
                    assertTrue(isSpecial(v), "非特典视频未提取到集数: " + v);
                    special++;
                } else {
                    eps.add(ep);
                }
            }
            assertTrue(eps.contains("01"), "应从第 1 集开始");
            assertEquals(vids.size() - special, eps.size(), "正片集数应唯一");
            System.out.println("tv 正片集数: " + eps + ", 特典: " + special);
        }
    }

    /** 特典文件: SPs 子目录或 PV/CM/Menu/NCOP/NCED/SP/OP/ED 标记 */
    private static boolean isSpecial(String path) {
        String upper = path.toUpperCase();
        return upper.contains("/SPS/") || upper.contains("[CM]") || upper.contains("[MENU")
                || upper.contains("[PV") || upper.contains("NCOP") || upper.contains("NCED")
                || upper.contains("[SP") || upper.contains("[OP") || upper.contains("[ED")
                || upper.contains("AUDIO GUIDE");
    }

    @Test
    void vcb_movie_no_episode_expected() throws Exception {
        // 电影: 主片无集数, 特典([Audio Guide Menu] 等)也无集数
        for (JSONObject item : load()) {
            if (!"movie".equals(item.getStr("type"))) continue;
            for (String v : videoFiles(item)) {
                String ep = openList.extractEpisodeFromFileName(v);
                assertNull(ep, "电影文件不应提取到集数: " + v + " -> " + ep);
            }
            System.out.println("movie: " + videoFiles(item).size() + " 个视频均无集数 ✅");
        }
    }

    @Test
    void vcb_ova_episode_range() throws Exception {
        // OVA: [01-02] 两集合一文件, 应提取到集数(暴露现有缺口)
        for (JSONObject item : load()) {
            if (!"ova".equals(item.getStr("type"))) continue;
            List<String> vids = videoFiles(item);
            int rangeCount = 0;
            for (String v : vids) {
                if (v.matches(".*\\[\\d+-\\d+\\].*")) {
                    rangeCount++;
                    String ep = openList.extractEpisodeFromFileName(v);
                    assertNotNull(ep, "范围集数 [01-02] 未提取: " + v);
                }
            }
            System.out.println("ova 范围文件数: " + rangeCount);
        }
    }

    @Test
    void vcb_collection_rename_unique() throws Exception {
        // 整季包合集重命名: 每个视频目标名唯一, 集数正确
        for (JSONObject item : load()) {
            if (!"tv".equals(item.getStr("type"))) continue;
            String finalRenameBase = "乡下大叔成为剑圣 S01E01"; // 模拟 rename 结果
            Set<String> targets = new HashSet<>();
            for (String v : videoFiles(item)) {
                String renamed = openList.collectionEpisodeReName(v, finalRenameBase, 1);
                assertNotNull(renamed);
                assertTrue(targets.add(renamed), "重命名冲突: " + v + " -> " + renamed);
            }
            System.out.println("合集重命名唯一性 ✅ (" + targets.size() + " 个)");
        }
    }
}
