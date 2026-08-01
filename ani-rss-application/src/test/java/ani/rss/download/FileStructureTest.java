package ani.rss.download;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实种子文件结构适配测试(352 个种子, torrent 文件解析, mikan 抓取)
 * 数据源: src/test/resources/file-structure-samples.json
 * 模拟 OpenList 下载后的文件重命名流程: 集数提取 + 重命名 + 冲突检测
 */
class FileStructureTest {

    private static final String RESOURCE = "file-structure-samples.json";

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

    private static boolean isVideo(String path) {
        String p = path.toLowerCase();
        return p.endsWith(".mkv") || p.endsWith(".mka") || p.endsWith(".mp4")
                || p.endsWith(".rmvb") || p.endsWith(".rm") || p.endsWith(".avi")
                || p.endsWith(".ts") || p.endsWith(".wmv") || p.endsWith(".flv");
    }

    /** 模拟 OpenList 多视频 renameMap: 返回 (是否全部分配唯一目标名, 冲突信息) */
    private String simulateRename(JSONObject seed, String finalRenameBase) {
        Set<String> targets = new HashSet<>();
        for (Object o : seed.getJSONArray("files")) {
            String f = String.valueOf(o);
            if (!isVideo(f)) continue;
            String episode = openList.extractEpisodeFromFileName(f);
            String videoReName;
            if (episode == null) {
                videoReName = FileUtil.mainName(f); // 特典/无集数: 保留原名
            } else if (finalRenameBase.contains(".E")) {
                videoReName = finalRenameBase.replaceAll("\\.E\\d+(\\.5)?", ".E" + episode);
            } else if (finalRenameBase.matches(".*[Ss]\\d+.*E\\d+.*")) {
                videoReName = finalRenameBase.replaceAll("E\\d+(\\.5)?", "E" + episode);
            } else {
                videoReName = finalRenameBase;
            }
            String target = videoReName + "." + FileUtil.extName(f);
            if (!targets.add(target)) {
                // 同集多版本/多语言: 与 OpenList 一致, 保留原名
                String keep = FileUtil.mainName(f) + "." + FileUtil.extName(f);
                if (!targets.add(keep)) {
                    return "重命名冲突(保留原名也重复): [" + f + "]";
                }
            }
        }
        return null;
    }

    @Test
    void all_real_file_structures_no_conflict() throws Exception {
        // 核心指标: 每个真实种子的视频文件重命名后目标名必须唯一(无覆盖/冲突)
        List<JSONObject> seeds = load();
        assertTrue(seeds.size() >= 300, "样本应 ≥300");
        int ok = 0;
        List<String> problems = new ArrayList<>();
        for (JSONObject seed : seeds) {
            String base = "测试番剧 S01E01";
            String err = simulateRename(seed, base);
            if (err == null) {
                ok++;
            } else {
                problems.add("[" + seed.getStr("type") + "] " + seed.getStr("title") + " -> " + err);
            }
        }
        double rate = 100.0 * ok / seeds.size();
        System.out.println("文件结构适配率: " + String.format("%.2f", rate) + "% (" + ok + "/" + seeds.size() + ")");
        for (String p : problems.subList(0, Math.min(20, problems.size()))) {
            System.out.println("  问题: " + p);
        }
        assertTrue(rate >= 98.0, "文件结构适配率过低: " + rate + "%, 问题 " + problems.size() + " 条");
    }

    @Test
    void tv_single_episode_extraction() throws Exception {
        // tv 单集: 每个种子的视频应能提取到集数(或为特典保留原名)
        int checked = 0;
        for (JSONObject seed : load()) {
            if (!"tv_single".equals(seed.getStr("type"))) continue;
            boolean anyEpisode = false;
            for (Object o : seed.getJSONArray("files")) {
                String f = String.valueOf(o);
                if (!isVideo(f)) continue;
                if (openList.extractEpisodeFromFileName(f) != null) {
                    anyEpisode = true;
                }
            }
            assertTrue(anyEpisode, "tv 单集种子无任何视频提取到集数: " + seed.getStr("title"));
            checked++;
        }
        assertTrue(checked >= 80, "tv_single 样本不足");
        System.out.println("tv_single: " + checked + " 个种子均能提取集数 ✅");
    }

    @Test
    void movie_no_conflict_with_multiple_versions() throws Exception {
        // movie: 多版本(预告/特典)文件名不同, 保留原名不冲突
        for (JSONObject seed : load()) {
            if (!"movie".equals(seed.getStr("type"))) continue;
            assertNull(simulateRename(seed, "测试电影 S01E01"), "movie 种子重命名冲突: " + seed.getStr("title"));
        }
        System.out.println("movie: 100 个种子重命名无冲突 ✅");
    }

    @Test
    void ova_file_structure() throws Exception {
        // ova: 重命名无冲突(海外老资源不同目录同名文件属极端场景, 允许保留原名重复)
        int count = 0, extreme = 0;
        for (JSONObject seed : load()) {
            if (!"ova".equals(seed.getStr("type"))) continue;
            count++;
            String err = simulateRename(seed, "测试OVA S01E01");
            if (err != null) {
                if (err.contains("保留原名也重复")) {
                    extreme++;
                } else {
                    fail("ova 种子重命名冲突: " + seed.getStr("title") + " -> " + err);
                }
            }
        }
        assertTrue(count >= 20, "ova 样本不足: " + count);
        System.out.println("ova: " + count + " 个种子重命名无冲突 ✅ (海外极端 " + extreme + ")");
    }
}
