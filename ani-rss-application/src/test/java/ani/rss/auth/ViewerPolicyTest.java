package ani.rss.auth;

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
}
