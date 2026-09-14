package ani.rss.auth;

import ani.rss.entity.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 只读令牌策略。
 * <p>
 * 这是"家人共享"的唯一防线，断言必须偏保守：
 * 未在白名单内的端点一律拒绝，且<b>写操作</b>必须被挡在门外。
 */
class ViewerPolicyTest {

    @Test
    void allows_read_and_play_endpoints() {
        assertTrue(ViewerPolicy.isReadOnlyAllowed("/api/listAni"));
        assertTrue(ViewerPolicy.isReadOnlyAllowed("/api/previewAni"));
        assertTrue(ViewerPolicy.isReadOnlyAllowed("/api/playList"));
        assertTrue(ViewerPolicy.isReadOnlyAllowed("/api/file"));
        assertTrue(ViewerPolicy.isReadOnlyAllowed("/api/library"));
        assertTrue(ViewerPolicy.isReadOnlyAllowed("/api/downloadHistory"));
        assertTrue(ViewerPolicy.isReadOnlyAllowed("/api/doctor"));
        assertTrue(ViewerPolicy.isReadOnlyAllowed("/api/calendar.ics"));
    }

    @Test
    void rejects_write_endpoints() {
        assertFalse(ViewerPolicy.isReadOnlyAllowed("/api/addAni"));
        assertFalse(ViewerPolicy.isReadOnlyAllowed("/api/setAni"));
        assertFalse(ViewerPolicy.isReadOnlyAllowed("/api/deleteAni"));
        assertFalse(ViewerPolicy.isReadOnlyAllowed("/api/setConfig"));
        assertFalse(ViewerPolicy.isReadOnlyAllowed("/api/forceDownload"));
        assertFalse(ViewerPolicy.isReadOnlyAllowed("/api/manualDownload"));
        assertFalse(ViewerPolicy.isReadOnlyAllowed("/api/rssJobCancel"));
        assertFalse(ViewerPolicy.isReadOnlyAllowed("/api/importAniByCode"));
        assertFalse(ViewerPolicy.isReadOnlyAllowed("/api/subtitleAttach"));
        assertFalse(ViewerPolicy.isReadOnlyAllowed("/api/clearCache"));
    }

    @Test
    void rejects_unknown_endpoints_by_default() {
        // 白名单语义：将来新增的端点默认对只读者关闭
        assertFalse(ViewerPolicy.isReadOnlyAllowed("/api/someBrandNewWriteThing"));
        assertFalse(ViewerPolicy.isReadOnlyAllowed("/api/upload"));
    }

    @Test
    void handles_uri_without_api_prefix_and_query_string() {
        assertTrue(ViewerPolicy.isReadOnlyAllowed("/listAni"));
        assertTrue(ViewerPolicy.isReadOnlyAllowed("/api/listAni?x=1"));
        assertFalse(ViewerPolicy.isReadOnlyAllowed("/api/addAni?x=1"));
    }

    @Test
    void rejects_blank_uri() {
        assertFalse(ViewerPolicy.isReadOnlyAllowed(null));
        assertFalse(ViewerPolicy.isReadOnlyAllowed(""));
    }

    @Test
    void isViewerRequest_is_false_without_request() {
        assertFalse(ViewerPolicy.isViewerRequest(null));
    }

    /**
     * 只读令牌能读到 /config，所以响应必须脱敏，否则可拿 apiKey 提权。
     * <p>
     * 这里<b>硬编码</b>字段清单而不是复用实现里的正则：实现改成硬编码名单时漏掉字段，本测试要能红。
     */
    @Test
    void sanitizeCredentials_blanks_every_credential_field() {
        Config config = new Config();
        config.setTmdbApiKey("tmdb-key");
        config.setDownloadToolPassword("qb-pass");
        config.setProxyPassword("proxy-pass");
        config.setBgmToken("bgm-token");
        config.setBgmAppSecret("bgm-secret");
        config.setBgmRefreshToken("bgm-refresh");
        config.setApiKey("ADMIN-API-KEY");
        config.setGithubToken("gh-token");
        config.setAssrtToken("assrt-token");
        config.setViewerApiKey("viewer-key");

        ViewerPolicy.sanitizeCredentials(config);

        assertAll(
                () -> assertEquals("", config.getTmdbApiKey()),
                () -> assertEquals("", config.getDownloadToolPassword()),
                () -> assertEquals("", config.getProxyPassword()),
                () -> assertEquals("", config.getBgmToken()),
                () -> assertEquals("", config.getBgmAppSecret()),
                () -> assertEquals("", config.getBgmRefreshToken()),
                // 提权的关键：管理令牌绝不能出现在只读响应里
                () -> assertEquals("", config.getApiKey()),
                () -> assertEquals("", config.getGithubToken()),
                () -> assertEquals("", config.getAssrtToken()),
                () -> assertEquals("", config.getViewerApiKey())
        );
    }

    /**
     * 脱敏只应作用于凭据字段，不能误伤展示类配置（否则只读用户的前端会拿不到下载目录模板等）。
     */
    @Test
    void sanitizeCredentials_keeps_non_credential_fields() {
        Config config = new Config();
        config.setMikanHost("https://mikanani.me");
        config.setDownloadToolHost("http://127.0.0.1:8080");
        config.setDownloadToolUsername("admin");
        config.setDownloadPathTemplate("/media/番剧");
        config.setTmdbApi("tmdb-api-host");
        // 名字含 "key" 但是集合，不是凭据
        config.setPriorityKeywords(java.util.List.of("简繁", "1080p"));

        ViewerPolicy.sanitizeCredentials(config);

        assertAll(
                () -> assertEquals("https://mikanani.me", config.getMikanHost()),
                () -> assertEquals("http://127.0.0.1:8080", config.getDownloadToolHost()),
                () -> assertEquals("admin", config.getDownloadToolUsername()),
                () -> assertEquals("/media/番剧", config.getDownloadPathTemplate()),
                () -> assertEquals("tmdb-api-host", config.getTmdbApi()),
                () -> assertEquals(java.util.List.of("简繁", "1080p"), config.getPriorityKeywords())
        );
    }

    @Test
    void sanitizeCredentials_tolerates_null() {
        assertDoesNotThrow(() -> ViewerPolicy.sanitizeCredentials(null));
    }
}
