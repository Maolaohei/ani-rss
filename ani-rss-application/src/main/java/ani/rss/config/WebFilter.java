package ani.rss.config;

import ani.rss.entity.Config;
import ani.rss.entity.Global;
import ani.rss.entity.web.ResultCode;
import ani.rss.util.other.AuthUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.PatternPool;
import cn.hutool.core.net.Ipv4Util;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static ani.rss.controller.BaseController.setCacheControl;
import static ani.rss.controller.BaseController.writeHtml;

@Component
public class WebFilter implements Filter {
    /**
     * 指定缓存的文件
     */
    private static final List<String> CACHE_EXT = List.of("css", "js", "jpg", "png", "svg", "ico");

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String uri = request.getRequestURI();

        // ThreadLocal 装填与清理必须覆盖 forward 分支, 保证转发后的控制器仍可取到请求上下文
        Global.REQUEST.set(request);
        Global.RESPONSE.set(response);
        try {
            // 禁止公网访问（port upstream d8b654d8）: 判定必须在 forward / 静态资源分支之前,
            // 否则非 api 路径会在鉴权前就被转发出去; 同时保证 Global.REQUEST 已装填, AuthUtil.getIp 才能取到 ip
            if (isPublicAccessForbidden()) {
                writeHtml(ResultCode.HTTP_FORBIDDEN, "禁止公网访问");
                return;
            }

            // 非 api (路由不区分大小写)
            if (!uri.toLowerCase().startsWith("/api")) {
                String extName = FileUtil.extName(uri);

                if (StrUtil.isBlank(extName) && !uri.endsWith("/")) {
                    String htmlPath = uri + ".html";
                    request.getRequestDispatcher(htmlPath).forward(request, response);
                    return;
                }

                if (StrUtil.isNotBlank(extName) && CACHE_EXT.contains(extName)) {
                    setCacheControl(response, 86400);
                } else {
                    setCacheControl(response, 0);
                }
            }

            cors(request, response);
            filterChain.doFilter(req, res);
        } finally {
            Global.REQUEST.remove();
            Global.RESPONSE.remove();
        }
    }

    /**
     * 是否拒绝本次请求（port upstream d8b654d8）
     * <p>
     * 仅在开启「禁止公网访问」时生效; 非 IPv4（如 IPv6 / 无法解析）一律 fail-closed,
     * 避免通过非常规地址绕过内网限制
     */
    private boolean isPublicAccessForbidden() {
        if (!Boolean.TRUE.equals(ConfigUtil.CONFIG.getInnerIP())) {
            return false;
        }
        return !isInnerIp(AuthUtil.getIp());
    }

    /**
     * 是否为内网 IPv4 地址
     * <p>
     * 解析失败或非 IPv4 返回 false, 交由调用方按 fail-closed 处理
     */
    static boolean isInnerIp(String ip) {
        if (StrUtil.isBlank(ip)) {
            return false;
        }

        if (PatternPool.IPV4.matcher(ip).matches()) {
            return Ipv4Util.isInnerIP(ip);
        }

        return false;
    }

    private void cors(HttpServletRequest request, HttpServletResponse response) {
        Config config = ConfigUtil.CONFIG;
        Boolean allowCors = config.getAllowCors();
        if (!allowCors) {
            return;
        }

        // 仅允许白名单内 Origin 回显；不再使用通配符 *，避免任意站点跨域访问
        String origin = request.getHeader("Origin");
        String corsOrigins = config.getCorsOrigins();
        if (StrUtil.isBlank(origin) || StrUtil.isBlank(corsOrigins)) {
            return;
        }

        List<String> allowList = StrUtil.split(corsOrigins, ",", true, true);
        if (!allowList.contains(origin)) {
            return;
        }

        response.addHeader("Access-Control-Allow-Origin", origin);
        response.addHeader("Vary", "Origin");
        response.addHeader("Access-Control-Allow-Methods", "*");
        response.addHeader("Access-Control-Allow-Headers", "*");
        response.addHeader("Access-Control-Max-Age", "0");
    }
}
