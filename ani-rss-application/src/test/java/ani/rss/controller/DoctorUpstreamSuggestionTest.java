package ani.rss.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自检页「OpenList → 上游网盘」的建议文案。
 * <p>
 * 上游失败的类别必须给出<b>可照做</b>的建议：把"TLS 握手超时"笼统地说成"检查网络"，
 * 等于把用户又丢回"网盘不可用"那句话——那正是这次要修的东西。
 */
class DoctorUpstreamSuggestionTest {

    @Test
    @DisplayName("TLS 握手超时给出 DNS/MTU/IPv6/代理 四件事")
    void tls_handshake_gives_actionable_steps() {
        String suggestion = DoctorController.upstreamSuggestion("TLS 握手超时（网络层丢包 / MTU / IPv6 / 代理）");
        assertTrue(suggestion.contains("DNS"), suggestion);
        assertTrue(suggestion.contains("MTU"), suggestion);
        assertTrue(suggestion.contains("curl -4"), suggestion);
        assertTrue(suggestion.contains("代理"), suggestion);
    }

    @Test
    @DisplayName("各类别都有自己的建议，不能退化成一句空话")
    void every_category_has_its_own_suggestion() {
        String dns = DoctorController.upstreamSuggestion("DNS 解析失败");
        String rate = DoctorController.upstreamSuggestion("上游限流");
        String timeout = DoctorController.upstreamSuggestion("超时");
        assertTrue(dns.contains("getent hosts"), dns);
        assertTrue(rate.contains("速率"), rate);
        assertTrue(timeout.contains("DNS"), timeout);
        // 类别未知时也要给出下一步，而不是空白
        assertFalse(DoctorController.upstreamSuggestion("").isBlank());
        assertFalse(DoctorController.upstreamSuggestion(null).isBlank());
    }
}
