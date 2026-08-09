package ani.rss.util.basic;

import ani.rss.commons.ExceptionUtils;
import cn.hutool.core.net.url.UrlBuilder;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.Method;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Slf4j
public class HttpRequestPlus extends HttpRequest {
    /**
     * 调用方正在对瞬时故障执行重试时置位：底层不再打 ERROR，避免与调用方
     * 带上下文的重试日志（如 OpenListApi.retryIdempotent 的 WARN）重复刷屏。
     * 仅影响执行线程内后续请求，线程安全。
     */
    private static final ThreadLocal<Boolean> RETRY_MODE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void setRetryMode(boolean on) {
        RETRY_MODE.set(on);
    }

    public HttpRequestPlus(UrlBuilder url) {
        super(url);
    }

    public static HttpRequest of(UrlBuilder url) {
        return new HttpRequestPlus(url);
    }

    public static HttpRequest of(String url) {
        // 去除分隔符重复
        url = url.replaceAll("(?<!https?:?)//", "/");
        return HttpRequestPlus.of(UrlBuilder.ofHttp(url, StandardCharsets.UTF_8));
    }

    public static HttpRequest of(String url, Charset charset) {
        // 去除分隔符重复
        url = url.replaceAll("(?<!https?:?)//", "/");
        return HttpRequestPlus.of(UrlBuilder.ofHttp(url, charset));
    }

    public static HttpRequest get(String url) {
        return HttpRequestPlus.of(url).method(Method.GET);
    }

    public static HttpRequest post(String url) {
        return HttpRequestPlus.of(url).method(Method.POST);
    }

    @Override
    public HttpResponse execute(boolean isAsync) {
        String url = getUrl();
        try {
            return super.execute(isAsync);
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            if (Boolean.TRUE.equals(RETRY_MODE.get())) {
                // 调用方正在重试并会记录带上下文的日志，这里只留 DEBUG 便于排查
                log.debug("url: {}, error: {} (重试中，由调用方记录)", url, message);
            } else {
                log.error("url: {}, error: {}", url, message);
            }
            throw e;
        }
    }
}
