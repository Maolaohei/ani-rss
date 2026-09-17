package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.entity.Global;
import ani.rss.entity.Log;
import ani.rss.entity.web.Header;
import ani.rss.entity.web.Result;
import ani.rss.util.basic.LogUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ZipUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Cleanup;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
public class LogsController extends BaseController {
    List<Log> LOG_LIST = LogUtil.LOG_LIST;

    /**
     * P1: limit 参数（默认 200，夹紧 1~512），倒序返回最新 N 条。
     * 快照拷贝：只在 synchronized 块内拷贝引用（new ArrayList），截断/倒序在锁外做，
     * 避免长列表下持有 LOG_LIST 锁做 IO/排序。与别组 downloadLogs 的 .log 过滤配合（此处不动）。
     */
    @Auth
    @Operation(summary = "日志")
    @PostMapping("/logs")
    public Result<List<Log>> logs(@RequestBody(required = false) Map<String, Object> body) {
        int limit = 200;
        if (body != null && body.get("limit") != null) {
            try {
                limit = Integer.parseInt(String.valueOf(body.get("limit")));
            } catch (NumberFormatException ignored) {
            }
        }
        limit = Math.max(1, Math.min(limit, 512));

        List<Log> snapshot;
        synchronized (LOG_LIST) {
            snapshot = new ArrayList<>(LOG_LIST);
        }
        int from = Math.max(0, snapshot.size() - limit);
        List<Log> slice = new ArrayList<>(snapshot.subList(from, snapshot.size()));
        Collections.reverse(slice);
        return Result.success(slice);
    }

    @Auth
    @Operation(summary = "清理日志")
    @PostMapping("/clearLogs")
    @Synchronized("LOG_LIST")
    public Result<Void> clearLogs() {
        LOG_LIST.clear();
        log.info("清理日志");
        return Result.success();
    }

    @Auth
    @Operation(summary = "下载日志")
    @GetMapping("/downloadLogs")
    public void downloadLogs() throws IOException {
        File configDir = ConfigUtil.getConfigDir();
        File logsDir = new File(configDir, "logs");

        String filename = "logs.zip";

        String contentType = getContentType(filename);

        HttpServletResponse response = Global.RESPONSE.get();

        response.setContentType(contentType);
        response.setHeader(Header.CONTENT_DISPOSITION, StrFormatter.format("inline; filename=\"{}\"", filename));

        // 只打最近 3 天且总量 ≤200MB：全量打包会把历史归档一并带上，打爆内存/带宽
        final long maxTotalBytes = 200L * 1024 * 1024;
        final long threeDaysAgo = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000;
        File[] all = logsDir.isDirectory() ? logsDir.listFiles() : new File[0];
        List<File> candidates = new ArrayList<>();
        if (all != null) {
            for (File f : all) {
                if (f == null || !f.isFile()) {
                    continue;
                }
                if (!"log".equals(FileUtil.extName(f))) {
                    continue;
                }
                if (f.lastModified() < threeDaysAgo) {
                    continue;
                }
                candidates.add(f);
            }
        }
        candidates.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        long total = 0L;
        for (File f : candidates) {
            total += f.length();
        }
        if (total > maxTotalBytes) {
            throw new IllegalArgumentException("最近 3 天日志过大(>200MB)，请清理 logs 目录后重试");
        }

        @Cleanup
        OutputStream outputStream = response.getOutputStream();

        if (candidates.isEmpty()) {
            ZipUtil.zip(outputStream, StandardCharsets.UTF_8, false, name -> false, new File[0]);
            return;
        }
        ZipUtil.zip(outputStream, StandardCharsets.UTF_8, false, name -> {
            if (FileUtil.isDirectory(name)) {
                return true;
            }
            String extName = FileUtil.extName(name);
            if (StrUtil.isBlank(extName)) {
                return false;
            }
            return extName.equals("log");
        }, candidates.toArray(new File[0]));
    }
}
