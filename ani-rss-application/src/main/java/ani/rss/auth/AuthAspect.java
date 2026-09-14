package ani.rss.auth;

import ani.rss.annotation.Auth;
import ani.rss.entity.Global;
import ani.rss.entity.web.Result;
import ani.rss.entity.web.ResultCode;
import ani.rss.exception.ResultException;
import ani.rss.util.other.AuthUtil;
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
        // 判定逻辑与响应脱敏共用 ViewerPolicy.isViewerRequest，避免两处口径不一致。
        if (ViewerPolicy.isViewerRequest(request) && !ViewerPolicy.isReadOnlyAllowed(request.getRequestURI())) {
            throw new ResultException(
                    Result.error(r ->
                            r.setCode(ResultCode.HTTP_FORBIDDEN)
                                    .setMessage("只读令牌无权执行该操作")
                    )
            );
        }
    }
}
