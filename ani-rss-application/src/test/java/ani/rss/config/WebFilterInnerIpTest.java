package ani.rss.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「禁止公网访问」内网判定（port upstream d8b654d8）
 * <p>
 * 重点固化 fail-closed 语义: 空白 / 非 IPv4（含 IPv6、域名、畸形串）一律判为非内网,
 * 避免用户开启开关后仍可通过非常规地址从公网访问
 */
class WebFilterInnerIpTest {

    @Test
    void private_ipv4_ranges_are_inner() {
        assertTrue(WebFilter.isInnerIp("127.0.0.1"));
        assertTrue(WebFilter.isInnerIp("10.0.0.1"));
        assertTrue(WebFilter.isInnerIp("10.255.255.254"));
        assertTrue(WebFilter.isInnerIp("172.16.0.1"));
        assertTrue(WebFilter.isInnerIp("172.31.255.254"));
        assertTrue(WebFilter.isInnerIp("192.168.1.1"));
    }

    @Test
    void public_ipv4_is_not_inner() {
        assertFalse(WebFilter.isInnerIp("8.8.8.8"));
        assertFalse(WebFilter.isInnerIp("1.1.1.1"));
        // 172.16.0.0/12 之外, 常被误判为内网的边界值
        assertFalse(WebFilter.isInnerIp("172.15.255.255"));
        assertFalse(WebFilter.isInnerIp("172.32.0.1"));
        assertFalse(WebFilter.isInnerIp("192.169.1.1"));
        // 链路本地 169.254.0.0/16 不在 hutool Ipv4Util.isInnerIP 覆盖范围内（与上游行为一致）
        assertFalse(WebFilter.isInnerIp("169.254.1.1"));
    }

    @Test
    void blank_and_non_ipv4_fail_closed() {
        assertFalse(WebFilter.isInnerIp(null));
        assertFalse(WebFilter.isInnerIp(""));
        assertFalse(WebFilter.isInnerIp("   "));
        assertFalse(WebFilter.isInnerIp("未知"));
        assertFalse(WebFilter.isInnerIp("localhost"));
        assertFalse(WebFilter.isInnerIp("::1"));
        assertFalse(WebFilter.isInnerIp("fd00::1"));
        assertFalse(WebFilter.isInnerIp("192.168.1.1:8080"));
        assertFalse(WebFilter.isInnerIp("192.168.1.256"));
        assertFalse(WebFilter.isInnerIp("192.168.1"));
    }
}
