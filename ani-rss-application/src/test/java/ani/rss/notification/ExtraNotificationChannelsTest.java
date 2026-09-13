package ani.rss.notification;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 新增通知渠道的纯函数行为。
 * <p>
 * 这些渠道的失败往往表现为"消息发出去了但内容不对/被服务端拒绝"，
 * 因此边界（优先级越界、中文标题、超长正文、加签 URL）必须固化。
 */
class ExtraNotificationChannelsTest {

    // ---------- ntfy ----------

    @Test
    void ntfy_priority_clamped_to_1_5() {
        assertEquals(3, NtfyNotification.clampPriority(null));
        assertEquals(1, NtfyNotification.clampPriority(0));
        assertEquals(1, NtfyNotification.clampPriority(-10));
        assertEquals(3, NtfyNotification.clampPriority(3));
        assertEquals(5, NtfyNotification.clampPriority(99));
    }

    @Test
    void ntfy_ascii_title_passes_through() {
        assertEquals("ani-rss", NtfyNotification.encodeHeader("ani-rss"));
    }

    @Test
    void ntfy_non_ascii_title_is_rfc2047_encoded() {
        // ntfy 的 Title 头只接受 ASCII，中文必须编码，否则服务端 400
        String encoded = NtfyNotification.encodeHeader("番剧A");
        assertTrue(encoded.startsWith("=?UTF-8?B?"));
        assertTrue(encoded.endsWith("?="));

        String b64 = encoded.substring("=?UTF-8?B?".length(), encoded.length() - 2);
        assertEquals("番剧A", new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8));
    }

    // ---------- Gotify ----------

    @Test
    void gotify_priority_clamped_to_0_10() {
        assertEquals(5, GotifyNotification.clampPriority(null));
        assertEquals(0, GotifyNotification.clampPriority(-1));
        assertEquals(10, GotifyNotification.clampPriority(100));
        assertEquals(7, GotifyNotification.clampPriority(7));
    }

    // ---------- 企业微信 ----------

    @Test
    void wecom_short_content_unchanged() {
        String content = "短消息";
        assertEquals(content, WeComNotification.truncate(content));
    }

    @Test
    void wecom_long_content_truncated_without_breaking_utf8() {
        // 构造远超 3800 字节的中文正文
        String content = "番剧更新".repeat(3000);
        String truncated = WeComNotification.truncate(content);

        assertTrue(truncated.endsWith("...(内容过长已截断)"));
        byte[] bytes = truncated.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length <= 3800 + "...(内容过长已截断)".getBytes(StandardCharsets.UTF_8).length + 8);

        // 关键：截断不能切在多字节字符中间，否则企微会收到乱码
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(decoded.contains("\uFFFD"), "截断后不应出现替换字符");
    }

    @Test
    void wecom_null_content_is_safe() {
        assertEquals("", WeComNotification.truncate(null));
    }

    // ---------- 钉钉 ----------

    @Test
    void dingtalk_without_secret_keeps_url() {
        String url = "https://oapi.dingtalk.com/robot/send?access_token=abc";
        assertEquals(url, DingTalkNotification.appendSign(url, null));
        assertEquals(url, DingTalkNotification.appendSign(url, "   "));
    }

    @Test
    void dingtalk_sign_appends_timestamp_and_signature() {
        String url = "https://oapi.dingtalk.com/robot/send?access_token=abc";
        String signed = DingTalkNotification.appendSign(url, "SEC0123456789");

        assertTrue(signed.startsWith(url));
        // 已有 query，应使用 & 追加
        assertTrue(signed.contains("&timestamp="));
        assertTrue(signed.contains("&sign="));
    }

    @Test
    void dingtalk_sign_uses_question_mark_when_no_query() {
        String signed = DingTalkNotification.appendSign("https://example.com/hook", "SEC0123456789");
        assertTrue(signed.contains("?timestamp="));
        assertTrue(signed.contains("&sign="));
    }
}
