package ani.rss.util.other;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tv 组 180 条解析失败样本根因分类(分析用, 不做行为断言)。
 * 数据源: src/test/resources/tv-fail-titles.txt(每行一条, AdaptAnalyzeTest 产物)
 */
class TvFailClassifyTest {

    private static final String RESOURCE = "tv-fail-titles.txt";

    private static final Pattern SPECIAL_NUM = Pattern.compile(
            "(?i)(\\bSP\\s*x?\\s*\\d|\\[SP\\d|OVA\\s*\\d|OAD\\s*\\d|\\[OAD\\d|EPISODE\\.0|第\\d+回|\\d{1,3}\\s+V2|\\d{1,3}\\.[56]\\b|&SP\\b|SP&|\\d+\\s*[-~]\\s*\\d+话|S00\\s+SP)");

    private static final Pattern COLLECTION = Pattern.compile(
            "(合集|全集|COMPLETA|正片\\+|TV动画|TV\\+|全\\d+话|\\+SP|SP\\]|OADs|\\+OVA|TVSP|\\+OAD|特别篇|特別篇|総集編|总集篇|剧场总集|特典映像|全3集|\\+ All SP|\\+SP)");

    private static final Pattern JUNK = Pattern.compile(
            "(?i)(Wii|RMVB|Spa Latino|PAL\\.|VOSTF|粤语|REMUX|\\[S\\.01)");

    @Test
    void classify() throws Exception {
        List<String> titles = load();
        assertFalse(titles.isEmpty(), "失败清单不应为空");

        Map<String, List<String>> cat = new LinkedHashMap<>();
        for (String t : titles) {
            String c;
            if (SPECIAL_NUM.matcher(t).find()) {
                c = "numbered_special(可修复: 应提取集数)";
            } else if (COLLECTION.matcher(t).find()) {
                c = "collection(合集/无单一集数)";
            } else if (JUNK.matcher(t).find()) {
                c = "junk(非番剧/边缘)";
            } else if (Pattern.compile("(?i)(\\bSP\\b|\\[SP\\]|OAD|Special|特别|特別|总集|総集)").matcher(t).find()) {
                c = "unnumbered_special(可修复: S00E00)";
            } else {
                c = "other(需人工判断)";
            }
            cat.computeIfAbsent(c, k -> new ArrayList<>()).add(t);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : cat.entrySet()) {
            System.out.println("==== " + e.getKey() + " : " + e.getValue().size() + " 条 ====");
            for (String t : e.getValue()) {
                System.out.println("  " + t);
                sb.append(e.getKey()).append('\t').append(t).append('\n');
            }
        }
        Files.write(Path.of("target/tv-fail-classified.txt"), sb.toString().getBytes(StandardCharsets.UTF_8));

        // 总量守卫: 分类必须覆盖全部失败样本
        int sum = cat.values().stream().mapToInt(List::size).sum();
        assertEquals(titles.size(), sum, "分类遗漏");
    }

    private List<String> load() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "缺少 " + RESOURCE);
            List<String> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank()) {
                    out.add(line.trim());
                }
            }
            return out;
        }
    }
}
