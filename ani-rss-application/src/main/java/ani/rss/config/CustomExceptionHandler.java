package ani.rss.config;

import ani.rss.entity.web.Result;
import ani.rss.exception.ResultException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

@Slf4j
@RestControllerAdvice
public class CustomExceptionHandler {

    @ExceptionHandler({
            IllegalArgumentException.class,
            IllegalStateException.class
    })
    public Result<Void> exception(Exception e) {
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(ResultException.class)
    public Result<Void> resultException(ResultException e) {
        return e.getResult();
    }

    @ExceptionHandler({
            NoResourceFoundException.class,
            NoHandlerFoundException.class,
            HttpRequestMethodNotSupportedException.class
    })
    public Result<Void> notFoundException() {
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

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        if (isClientAbort(e)) {
            log.debug("client aborted request: {}", e.getMessage());
            return null;
        }
        log.error(e.getMessage(), e);
        return Result.error(e.getMessage());
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

}
