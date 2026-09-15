package ani.rss.service.subtitle;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ASSRT 网络层的重试判定与单文件候选的季/集门槛。
 * <p>
 * 这两条逻辑直接决定「临时故障能不能自愈」与「会不会把第 3 集的字幕挂到第 5 集」，
 * 因此单独覆盖。
 */
class AssrtSubtitleProviderTest {

    /* ==================== 瞬时故障判定 ==================== */

    @Test
    void transient_network_failures_are_retryable() {
        assertTrue(AssrtSubtitleProvider.isTransient(new SocketTimeoutException("connect timed out")));
        assertTrue(AssrtSubtitleProvider.isTransient(new SocketTimeoutException("Read timed out")));
        assertTrue(AssrtSubtitleProvider.isTransient(new ConnectException("Connection timed out: connect")));
        assertTrue(AssrtSubtitleProvider.isTransient(new UnknownHostException("api.assrt.net")));
    }

    @Test
    void wrapped_network_failures_are_retryable() {
        // hutool 会把 IOException 包一层，判定必须能穿透 cause 链
        assertTrue(AssrtSubtitleProvider.isTransient(
                new RuntimeException("http error", new ConnectException("Connection timed out"))));
        assertTrue(AssrtSubtitleProvider.isTransient(
                new RuntimeException("nested", new IllegalStateException(new SocketTimeoutException("read timed out")))));
    }

    @Test
    void server_side_and_quota_errors_are_retryable() {
        assertTrue(AssrtSubtitleProvider.isTransient(new IllegalStateException("url: x, status: 500")));
        assertTrue(AssrtSubtitleProvider.isTransient(new IllegalStateException("url: x, status: 503")));
        assertTrue(AssrtSubtitleProvider.isTransient(new IllegalStateException("url: x, status: 429")));
        // 30900 = 超出接口调用限制，ASSRT 文档要求退避重试
        assertTrue(AssrtSubtitleProvider.isTransient(new IllegalStateException("status: 30900 超出调用限制")));
    }

    @Test
    void deterministic_errors_are_not_retried() {
        // token 无效 / 关键词过短 / 未找到 等 4xx，重试只是白白消耗配额
        assertFalse(AssrtSubtitleProvider.isTransient(new IllegalStateException("url: x, status: 400")));
        assertFalse(AssrtSubtitleProvider.isTransient(new IllegalStateException("url: x, status: 401")));
        assertFalse(AssrtSubtitleProvider.isTransient(new IllegalStateException("status: 20001 Token 无效")));
        assertFalse(AssrtSubtitleProvider.isTransient(new IllegalArgumentException("参数缺失")));
        assertFalse(AssrtSubtitleProvider.isTransient(null));
    }

    /* ==================== 单文件候选的季/集门槛 ==================== */

    @Test
    void candidate_matching_episode_is_accepted() {
        assertTrue(AssrtSubtitleProvider.matchesEpisode("Show S01E05.chs.ass", "", 1, 5));
        assertTrue(AssrtSubtitleProvider.matchesEpisode("Show - 05.chs.ass", "", null, 5));
        // 1080p 等干扰数字不能把集数解析带偏
        assertTrue(AssrtSubtitleProvider.matchesEpisode("Show S01E05 1080p.chs.ass", "", 1, 5));
    }

    @Test
    void candidate_with_other_episode_is_rejected() {
        assertFalse(AssrtSubtitleProvider.matchesEpisode("Show S01E06.chs.ass", "", 1, 5));
        assertFalse(AssrtSubtitleProvider.matchesEpisode("Show - 06.chs.ass", "", null, 5));
    }

    @Test
    void candidate_with_other_season_is_rejected() {
        assertFalse(AssrtSubtitleProvider.matchesEpisode("Show S02E05.chs.ass", "", 1, 5));
    }

    @Test
    void unparsable_candidate_is_accepted_to_avoid_false_rejection() {
        // 解析不出集数时放行——宁可让用户在选择阶段把关，也不要误伤
        assertTrue(AssrtSubtitleProvider.matchesEpisode("Show.chs.ass", "", 1, 5));
        assertTrue(AssrtSubtitleProvider.matchesEpisode("合集.chs.ass", "", 3, 12));
    }
}
