package ani.rss.auth;

import ani.rss.auth.fun.ApiKey;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.regex.Pattern;

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
 * <p>
 * 注意：只读白名单只解决"能不能调"，不解决"响应里有什么"。
 * {@code /config} 是只读语义但响应含管理凭据，因此
 * {@code ConfigController#config()} 对只读请求额外调用 {@link #sanitizeCredentials(Object)} 脱敏。
 */
public final class ViewerPolicy {

    private ViewerPolicy() {
    }

    /**
     * 只读令牌允许访问的端点（全部为只读/播放类）
     * <p>
     * {@code /config} 之所以在名单内，是因为前端启动时要靠它拿到下载目录模板、基础地址等展示配置；
     * 它携带的凭据字段已由 {@link #sanitizeCredentials(Object)} 对只读请求抹掉。
     */
    private static final Set<String> READ_ONLY_ENDPOINTS = Set.of(
            // 订阅与预览
            "/listAni", "/previewAni", "/downloadPath", "/failedDownloadQueue",
            "/downloadHistory", "/downloadHistoryStats",
            // 任务与日志
            "/rssJobStatus", "/logs", "/torrentsInfos", "/about",
            // 设置读取（凭据字段对只读请求脱敏）
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
     * 凭据类字段名特征。用于 {@link #sanitizeCredentials(Object)} 的按名匹配。
     */
    private static final Pattern CREDENTIAL_FIELD_NAME =
            Pattern.compile(".*(token|password|secret|key|pwd|pass).*", Pattern.CASE_INSENSITIVE);

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
     * 本次请求是否使用了"只读令牌"（同时配了管理令牌时，管理令牌优先）
     */
    public static boolean isViewerRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String viewerApiKey = ConfigUtil.CONFIG.getViewerApiKey();
        if (StrUtil.isBlank(viewerApiKey)) {
            return false;
        }
        String presented = ApiKey.presentedKey(request);
        if (StrUtil.isBlank(presented)) {
            return false;
        }
        // 与管理令牌相同时按管理身份处理，避免配置重复导致权限被意外降级
        String apiKey = ConfigUtil.CONFIG.getApiKey();
        if (StrUtil.isNotBlank(apiKey) && StrUtil.equals(apiKey, presented)) {
            return false;
        }
        return StrUtil.equals(viewerApiKey, presented);
    }

    /**
     * 抹掉对象上所有"凭据类"字符串字段，用于只读令牌访问设置接口时的脱敏。
     * <p>
     * 采用<b>按字段名匹配</b>而非硬编码字段清单：以后新增 {@code xxxToken} 之类的字段会被自动覆盖，
     * 不会因为"忘了加进脱敏名单"而再次泄露（这正是本方法存在的原因）。
     * <p>
     * 只处理顶层字段。嵌套对象（如 {@code Login}）需要调用方单独处理：
     * {@code Login.password} / {@code Login.key} 由 {@code ConfigController#config()} 显式置空。
     *
     * @param bean 待脱敏对象，可为 {@code null}
     */
    public static void sanitizeCredentials(Object bean) {
        if (bean == null) {
            return;
        }
        for (Field field : bean.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            // 只动 String 字段：priorityKeywords 之类的集合虽然名字含 "key"，但不是凭据
            if (field.getType() != String.class) {
                continue;
            }
            if (!CREDENTIAL_FIELD_NAME.matcher(field.getName()).matches()) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.set(bean, "");
            } catch (Exception ignored) {
                // 脱敏失败不应让接口 500；字段为 final 等异常场景忽略即可
            }
        }
    }

    /**
     * 测试/展示用：当前允许的只读端点
     */
    public static Set<String> allowedEndpoints() {
        return READ_ONLY_ENDPOINTS;
    }
}
