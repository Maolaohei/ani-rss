package ani.rss.auth;

import cn.hutool.core.util.StrUtil;

import java.util.Set;

/**
 * 只读访问策略（多用户/权限的轻量实现）。
 * <p>
 * 完整的多用户体系需要重构登录与会话（当前 token 是无状态哈希，没有服务端会话），
 * 属于高风险改动。这里用<b>只读令牌</b>提供一个向后兼容的"家人共享"方案：
 * <ul>
 *   <li>未配置只读令牌时，行为与之前完全一致（零影响）；</li>
 *   <li>配置后，用只读令牌访问的请求只允许"看和播"类端点，写操作一律 403。</li>
 * </ul>
 * 采用<b>白名单</b>而非黑名单：将来新增端点默认对只读者关闭，
 * 避免"忘了加黑名单"导致的越权。
 */
public final class ViewerPolicy {

    private ViewerPolicy() {
    }

    /**
     * 只读令牌允许访问的端点（全部为只读/播放类）
     */
    private static final Set<String> READ_ONLY_ENDPOINTS = Set.of(
            // 订阅与预览
            "/listAni", "/previewAni", "/downloadPath", "/failedDownloadQueue",
            "/downloadHistory", "/downloadHistoryStats",
            // 任务与日志
            "/rssJobStatus", "/logs", "/torrentsInfos", "/about",
            // 设置读取
            "/config", "/notificationLastSend",
            // 播放与文件
            "/playList", "/getSubtitles", "/file",
            // 媒体库
            "/library", "/libraryDetail",
            // 字幕
            "/subtitleScan", "/subtitleStatus",
            // 搜索类（不落库）
            "/manualSearch", "/mikan", "/mikanGroup", "/aniBT", "/aniBTGroup",
            "/animeGardenList", "/animeGardenGroup",
            "/searchBgm", "/getAniBySubjectId", "/getBgmTitle", "/rate",
            "/getThemoviedbName", "/searchThemoviedb", "/getThemoviedbGroup",
            "/getEmbyViews", "/previewCollection", "/getCollectionSubgroup",
            // 日历与图片
            "/calendar.ics", "/proxyImage",
            // 自检
            "/doctor"
    );

    /**
     * 判断某请求路径是否允许只读访问
     *
     * @param uri 请求 URI（可能带 /api 前缀）
     */
    public static boolean isReadOnlyAllowed(String uri) {
        if (StrUtil.isBlank(uri)) {
            return false;
        }
        String path = uri;
        int apiIndex = path.toLowerCase().indexOf("/api/");
        if (apiIndex >= 0) {
            path = path.substring(apiIndex + 4);
        }
        // 去掉查询串（部分容器会带上）
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        return READ_ONLY_ENDPOINTS.contains(path);
    }

    /**
     * 测试/展示用：当前允许的只读端点
     */
    public static Set<String> allowedEndpoints() {
        return READ_ONLY_ENDPOINTS;
    }
}
