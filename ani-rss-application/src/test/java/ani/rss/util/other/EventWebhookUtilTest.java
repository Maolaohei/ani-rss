package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.enums.EventTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结构化事件 Webhook：事件过滤与事件体构造。
 * <p>
 * 事件体是给程序消费的契约，字段名一旦变化下游（HomeAssistant / n8n）会静默失效，
 * 因此这里固化字段集合。
 */
class EventWebhookUtilTest {

    @Test
    void parseTypes_supports_comma_space_and_chinese_comma() {
        Set<String> types = EventWebhookUtil.parseTypes("download_end, download_failed，RSS_ROUND_FINISHED disk_warning");
        assertEquals(Set.of("DOWNLOAD_END", "DOWNLOAD_FAILED", "RSS_ROUND_FINISHED", "DISK_WARNING"), types);
    }

    @Test
    void parseTypes_ignores_blank_parts() {
        assertTrue(EventWebhookUtil.parseTypes("  ,,  ").isEmpty());
        assertTrue(EventWebhookUtil.parseTypes("").isEmpty());
    }

    @Test
    void isSubscribed_defaults_to_all_when_unset() {
        Config config = new Config();
        assertTrue(EventWebhookUtil.isSubscribed(config, EventTypeEnum.DOWNLOAD_END));
        assertTrue(EventWebhookUtil.isSubscribed(config, EventTypeEnum.DISK_WARNING));
    }

    @Test
    void isSubscribed_filters_by_configured_types() {
        Config config = new Config().setEventWebhookTypes("DOWNLOAD_END");
        assertTrue(EventWebhookUtil.isSubscribed(config, EventTypeEnum.DOWNLOAD_END));
        assertFalse(EventWebhookUtil.isSubscribed(config, EventTypeEnum.DOWNLOAD_FAILED));
    }

    @Test
    void isSubscribed_honours_ALL() {
        Config config = new Config().setEventWebhookTypes("ALL");
        assertTrue(EventWebhookUtil.isSubscribed(config, EventTypeEnum.SUBSCRIPTION_DELETED));
    }

    @Test
    void buildPayload_contains_stable_fields() {
        Ani ani = new Ani()
                .setId("ani-1")
                .setTitle("番剧A")
                .setSeason(2)
                .setSubgroup("字幕组A")
                .setEnable(true)
                .setPriority(0);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("episode", 5.0);

        String payload = EventWebhookUtil.buildPayload(EventTypeEnum.DOWNLOAD_END, ani, data);
        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();

        assertEquals("DOWNLOAD_END", json.get("event").getAsString());
        assertEquals("下载完成", json.get("label").getAsString());
        assertEquals("ani-rss", json.get("source").getAsString());
        assertTrue(json.has("at"));
        assertEquals("ani-1", json.getAsJsonObject("ani").get("id").getAsString());
        assertEquals("番剧A", json.getAsJsonObject("ani").get("title").getAsString());
        assertEquals(5.0, json.getAsJsonObject("data").get("episode").getAsDouble());
    }

    @Test
    void buildPayload_tolerates_null_ani_and_data() {
        String payload = EventWebhookUtil.buildPayload(EventTypeEnum.DISK_WARNING, null, null);
        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
        assertEquals("DISK_WARNING", json.get("event").getAsString());
        assertFalse(json.has("ani"), "无关联订阅时不应输出 ani 字段");
        assertTrue(json.has("data"));
    }

    @Test
    void stats_are_exposed_for_self_check() {
        Map<String, Long> stats = EventWebhookUtil.stats();
        assertTrue(stats.containsKey("sent"));
        assertTrue(stats.containsKey("failed"));
        assertTrue(stats.containsKey("dropped"));
    }
}
