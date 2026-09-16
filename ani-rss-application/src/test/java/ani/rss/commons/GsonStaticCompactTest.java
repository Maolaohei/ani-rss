package ani.rss.commons;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全局 Gson 的输出格式（P1-12）。
 * <p>
 * {@code GsonStatic.GSON} 是全应用唯一的 Gson，同时承担三件事：落盘
 * （{@code ani.v2.json} / {@code config.v2.json} / 各类状态快照）、OpenList 请求体、
 * 以及所有外部响应解析。它原先开着 {@code setPrettyPrinting()}——
 * 解析时空白会被跳过所以无害，但<b>写出</b>是纯损耗：实测 200 条订阅下
 * pretty 224.7KB vs compact 171.4KB（1.31×，+31%），而 OpenList 的每个请求体
 * 都白带着换行发出去。
 * <p>
 * 格式化能力没有丢，只是挪到了只给"人看"的 {@link GsonStatic#PRETTY_GSON} /
 * {@link GsonStatic#prettyJson(String)}（导出 {@code /exportConfig} 时用）。
 */
class GsonStaticCompactTest {

    @Test
    void default_gson_writes_compact_json() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", "测试番剧");
        map.put("season", 1);

        String json = GsonStatic.toJson(map);

        assertFalse(json.contains("\n"), "默认实例不应再输出换行: " + json);
        assertFalse(json.contains("  "), "默认实例不应再输出缩进: " + json);
        assertTrue(json.contains("\"title\":\"测试番剧\""), "紧凑输出不该在冒号后加空格: " + json);
    }

    @Test
    void pretty_gson_writes_indented_json() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", "测试番剧");
        map.put("season", 1);

        String json = GsonStatic.toPrettyJson(map);

        assertTrue(json.contains("\n"), "格式化实例必须保留换行: " + json);
        assertTrue(json.contains("\"title\": \"测试番剧\""), "格式化实例应在冒号后加空格: " + json);
    }

    /**
     * 导出用的是 {@link GsonStatic#prettyJson(String)}（文本 → 格式化文本）。
     * <p>
     * 它必须走 {@code JsonParser} → {@code JsonElement}，<b>不能</b>先解析成 {@code Object}：
     * 后者会把数字统一读成 {@code Double}，大整数（如更新包 size）会被写成科学计数法，
     * 导出的备份文件就再也回不去了。
     */
    @Test
    void pretty_json_preserves_large_integer_literals() {
        String compact = "{\"size\":66087671,\"ratio\":0.5,\"flag\":true}";

        String pretty = GsonStatic.prettyJson(compact);

        assertTrue(pretty.contains("66087671"), "大整数必须原样保留: " + pretty);
        assertFalse(pretty.contains("E"), "不该出现科学计数法: " + pretty);
        assertFalse(pretty.contains("6.6087671"), "不该被写成浮点: " + pretty);
        assertTrue(pretty.contains("\"ratio\": 0.5"), pretty);
        assertTrue(pretty.contains("\"flag\": true"), pretty);
    }

    /**
     * Gson 默认<b>不</b>序列化 null（两个实例都没开 {@code serializeNulls()}）。
     * <p>
     * 这条看似多余，但它保证了导出的 json 与落盘内容在"有没有 null 字段"上一致——
     * 否则美化后的备份会凭空多出一批 {@code "field": null}，导入回去就成了另一份配置。
     */
    @Test
    void pretty_json_drops_null_members_like_the_compact_serializer() {
        String compact = "{\"a\":1,\"n\":null}";

        String pretty = GsonStatic.prettyJson(compact);

        assertTrue(pretty.contains("\"a\": 1"), pretty);
        assertFalse(pretty.contains("\"n\""), "null 成员不该被凭空写出来: " + pretty);
        assertFalse(GsonStatic.toJson(GsonStatic.GSON.fromJson(compact, Object.class)).contains("null"));
    }

    @Test
    void pretty_json_only_changes_whitespace() {
        String compact = "{\"a\":[\"x\",\"y\"],\"b\":{\"c\":\"d\"}}";

        String pretty = GsonStatic.prettyJson(compact);

        // 格式化只是排版：把空白去掉必须与原文本逐字相同
        assertEquals(compact, pretty.replaceAll("\\s", ""));
    }

    @Test
    void pretty_output_is_larger_than_compact() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 200; i++) {
            sb.append("\"f").append(i).append("\":\"some-value-").append(i).append("\",");
        }
        sb.append("\"tail\":\"0\"}");
        String source = sb.toString();

        String pretty = GsonStatic.prettyJson(source);

        assertTrue(pretty.length() > source.length(),
                "格式化必然更大（实测 1.31×），这正是落盘要 compact 的理由");
        assertEquals(source, pretty.replaceAll("\\s", ""));
    }
}
