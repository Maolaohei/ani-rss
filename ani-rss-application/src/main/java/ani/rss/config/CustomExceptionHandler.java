package ani.rss.config;

import ani.rss.entity.web.Result;
import ani.rss.exception.ResultException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

/**
 * 全局异常处理
 * <p>
 * 日志口径（移植上游 a210ebc1e 的精华）：4xx 类「客户端问题」降为 warn/debug，
 * 5xx 类「服务端故障」保留 error + 堆栈；每条日志都带 method/path/remote 上下文，
 * 且所有字段先经 {@link #sanitize(String)} 抹掉换行 —— 否则异常消息里的 \n 会把一条日志
 * 拆成多条，既污染日志结构也给了伪造日志行的机会。
 */
@Slf4j
@RestControllerAdvice
public class CustomExceptionHandler {

    /**
     * 处理参数或状态不符合业务要求的异常。
     */
    @ExceptionHandler({
            IllegalArgumentException.class,
            IllegalStateException.class
    })
    public Result<Void> exception(Exception e, HttpServletRequest request) {
        log.warn("请求参数或状态异常 {}", requestContext(request, e));
        return Result.error(errorMessage(e));
    }

    /**
     * 处理缺少必要请求参数的异常。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> missingParameterException(Exception e, HttpServletRequest request) {
        log.warn("请求缺少必要参数 {}", requestContext(request, e));
        return Result.error(errorMessage(e));
    }

    /**
     * 处理主动抛出的业务结果异常。
     * <p>
     * 返回体由异常自带（不是通用错误），只补一条 warn 用于定位是哪个接口失败。
     */
    @ExceptionHandler(ResultException.class)
    public Result<Void> resultException(ResultException e, HttpServletRequest request) {
        Result<Void> result = e.getResult();
        if (result == null) {
            log.warn("业务请求失败 result=null {}", requestContext(request, e));
            return Result.error(errorMessage(e));
        }
        log.warn("业务请求失败 code={} message={} method={} path={} remote={}",
                result.getCode(),
                sanitize(result.getMessage()),
                request.getMethod(),
                sanitize(request.getRequestURI()),
                sanitize(request.getRemoteAddr()));
        return result;
    }

    /**
     * 处理未找到控制器或静态资源的请求。
     * <p>
     * 未知资源请求（扫描器、过期前端资源）非常常见，只在 debug 保留定位信息。
     */
    @ExceptionHandler({
            NoResourceFoundException.class,
            NoHandlerFoundException.class
    })
    public Result<Void> notFoundException(Exception e, HttpServletRequest request) {
        log.debug("请求资源不存在 {}", requestContext(request, e));
        return new Result<>(404, "404 Not Found !");
    }

    /**
     * 处理请求方法不受支持的异常。
     * <p>
     * 与「资源不存在」分开：方法用错往往是调用方 bug，值得 warn。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> methodNotSupportedException(Exception e, HttpServletRequest request) {
        log.warn("请求方法不受支持 {}", requestContext(request, e));
        return new Result<>(404, "404 Not Found !");
    }

    /**
     * 客户端主动断开（刷新/切页/超时取消）时，响应写出失败。
     * 非业务故障，降为 debug，避免 ERROR 刷屏。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void asyncRequestNotUsable(AsyncRequestNotUsableException e) {
        log.debug("client aborted request: {}", e.getMessage());
    }

    /**
     * 处理未被其他处理器识别的异常。
     * <p>
     * 未知异常保留请求上下文与完整堆栈，便于定位服务端错误。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        if (isClientAbort(e)) {
            log.debug("client aborted request: {}", e.getMessage());
            return null;
        }
        log.error("请求处理异常 {}", requestContext(request, e), e);
        return Result.error(errorMessage(e));
    }

    /**
     * 识别客户端断连：Connection reset / Broken pipe / ClientAbortException 等
     */
    private static boolean isClientAbort(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof AsyncRequestNotUsableException) {
                return true;
            }
            String name = cur.getClass().getName();
            // 不直接 import Tomcat 类，避免耦合；按类名识别
            if (name.endsWith("ClientAbortException")
                    || name.contains("ClientAbortException")) {
                return true;
            }
            if (cur instanceof IOException) {
                String msg = cur.getMessage();
                if (msg != null) {
                    String lower = msg.toLowerCase();
                    if (lower.contains("connection reset")
                            || lower.contains("broken pipe")
                            || lower.contains("abort")) {
                        return true;
                    }
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 生成不包含查询参数和请求体的安全请求上下文。
     * <p>
     * 刻意只取 method / URI / 远端地址：查询串与请求体可能携带 token、口令，
     * 落进日志等于把凭据写进磁盘。
     */
    private String requestContext(HttpServletRequest request, Exception e) {
        return String.format(
                "method=%s path=%s remote=%s exception=%s message=%s",
                request.getMethod(),
                sanitize(request.getRequestURI()),
                sanitize(request.getRemoteAddr()),
                e.getClass().getSimpleName(),
                errorMessage(e)
        );
    }

    /**
     * 获取适合返回和记录的异常消息。
     *
     * @return 非空的单行异常消息
     */
    private String errorMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return sanitize(message);
    }

    /**
     * 清理日志字段中的换行符，避免破坏单条日志结构。
     */
    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }
}
