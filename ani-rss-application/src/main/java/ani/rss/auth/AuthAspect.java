package ani.rss.auth;

import ani.rss.annotation.Auth;
import ani.rss.auth.fun.ApiKey;
import ani.rss.entity.Global;
import ani.rss.entity.web.Result;
import ani.rss.entity.web.ResultCode;
import ani.rss.exception.ResultException;
import ani.rss.util.other.AuthUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class AuthAspect {
    @Before("@annotation(auth)")
    public void before(JoinPoint joinPoint, Auth auth) throws Exception {
        HttpServletRequest request = Global.REQUEST.get();
        if (!AuthUtil.test(request, auth)) {
            throw new ResultException(
                    Result.error(r ->
                            r.setCode(ResultCode.HTTP_FORBIDDEN)
                                    .setMessage("登录已失效")
                    )
            );
        }

        // 只读令牌：鉴权通过后仍要限制到"看和播"类端点。
        // 未配置 viewerApiKey 时 isViewerRequest 恒为 false，行为与之前完全一致。
        if (isViewerRequest(request) && !ViewerPolicy.isReadOnlyAllowed(request.getRequestURI())) {
            throw new ResultException(
                    Result.error(r ->
                            r.setCode(ResultCode.HTTP_FORBIDDEN)
                                    .setMessage("只读令牌无权执行该操作")
                    )
            );
        }
    }

    /**
     * 本次请求是否使用了"只读令牌"（同时配了管理令牌时，管理令牌优先）
     */
    private static boolean isViewerRequest(HttpServletRequest request) {
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
}
