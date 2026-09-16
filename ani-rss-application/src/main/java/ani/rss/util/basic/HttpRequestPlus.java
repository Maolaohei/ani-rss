package ani.rss.util.basic;

import ani.rss.commons.ExceptionUtils;
import cn.hutool.core.net.url.UrlBuilder;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.Method;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@Slf4j
public class HttpRequestPlus extends HttpRequest {
    /**
     * 去除路径里重复的分隔符，但保留协议部分的 {@code http://} / {@code https://}。
     * <p>
     * (P2-12) 预编译成常量：原先两处 {@code of(...)} 都直接写 {@code url.replaceAll(regex, "/")}，
     * 也就是<b>每构造一个请求</b>都要把这条带后顾断言的正则重新编译一遍。本项目的所有 HTTP 请求
     * 都从这里走，属纯浪费。
     */
    private static final Pattern DUPLICATE_SLASH = Pattern.compile("(?<!https?:?)//");

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
        return HttpRequestPlus.of(normalize(url), StandardCharsets.UTF_8);
    }

    public static HttpRequest of(String url, Charset charset) {
        return HttpRequestPlus.of(UrlBuilder.ofHttp(normalize(url), charset));
    }

    /**
     * 去除路径里重复的分隔符（保留协议）
     */
    static String normalize(String url) {
        return DUPLICATE_SLASH.matcher(url).replaceAll("/");
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
