package ani.rss.auth.fun;

import ani.rss.entity.Config;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.function.Function;

/**
 * api key 鉴权
 */
public class ApiKey implements Function<HttpServletRequest, Boolean> {
    @Override
    public Boolean apply(HttpServletRequest request) {
        Config config = ConfigUtil.CONFIG;
        String apiKey = config.getApiKey();
        String viewerApiKey = config.getViewerApiKey();

        String presented = presentedKey(request);
        if (StrUtil.isBlank(presented)) {
            return false;
        }
        // 管理令牌
        if (StrUtil.isNotBlank(apiKey) && StrUtil.equals(apiKey, presented)) {
            return true;
        }
        // 只读令牌：仅授予"看和播"，写操作由 ViewerPolicy 在切面里拦
        return StrUtil.isNotBlank(viewerApiKey) && StrUtil.equals(viewerApiKey, presented);
    }

    /**
     * 取出请求中携带的密钥（支持 header 与 query 两种形式）
     */
    public static String presentedKey(HttpServletRequest request) {
        for (String key : List.of("api-key", "x-api-key", "s")) {
            String s = request.getHeader(key);
            if (StrUtil.isBlank(s)) {
                s = request.getParameter(key);
            }
            if (StrUtil.isNotBlank(s)) {
                return s;
            }
        }
        return null;
    }
}
