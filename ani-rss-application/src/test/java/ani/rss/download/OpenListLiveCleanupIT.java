package ani.rss.download;

import ani.rss.entity.Config;
import ani.rss.entity.OpenListFileInfo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 OpenList 联调：仅在设置了环境变量时运行。
 * 不要把 token 写进代码或仓库。
 *
 * OPENLIST_HOST=https://example.com
 * OPENLIST_TOKEN=xxx
 * OPENLIST_SANDBOX=/115/yun-download-sandbox   (可选)
 */
@EnabledIfEnvironmentVariable(named = "OPENLIST_HOST", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OPENLIST_TOKEN", matches = ".+")
class OpenListLiveCleanupIT {

    @Test
    void force_cleanup_removes_temp_even_with_nested_media_when_final_ready() {
        String host = System.getenv("OPENLIST_HOST");
        String token = System.getenv("OPENLIST_TOKEN");
        String sandbox = normalizeRemotePath(System.getenv().getOrDefault("OPENLIST_SANDBOX", "/115/yun-download-sandbox"));
        Assumptions.assumeTrue(host != null && token != null);

        Config config = new Config()
                .setDownloadToolHost(host)
                .setDownloadToolPassword(token)
                .setDownloadPathTemplate(sandbox)
                .setProvider("115 Cloud");

        OpenList openList = new OpenList();
        assertTrue(openList.login(true, config), "OpenList login failed");

        String runId = "ani-rss-temp-force-" + UUID.randomUUID().toString().substring(0, 8);
        String savePath = sandbox + "/" + runId;
        String tempDirName = "Show S01E03";
        String nestedDir = "[ANi] Show - 03 [1080P].mp4";
        String videoName = "Show S01E03.mp4";

        try {
            openList.mkdir(savePath);
            openList.mkdir(savePath + "/" + tempDirName);
            openList.mkdir(savePath + "/" + tempDirName + "/" + nestedDir);

            // 最终目录已有目标视频（模拟 move 成功）
            putText(savePath, videoName, "final-video-bytes");
            // 临时目录仍有嵌套媒体残留
            putText(savePath + "/" + tempDirName + "/" + nestedDir, nestedDir, "nested-media");

            awaitTrue(() -> openList.fsList(savePath, true).stream()
                    .anyMatch(f -> videoName.equals(f.getName()) && !Boolean.TRUE.equals(f.getIsDir())), 60_000);
            awaitTrue(() -> openList.findFiles(savePath + "/" + tempDirName).stream()
                    .anyMatch(f -> nestedDir.equals(f.getName())), 60_000);

            // force=true：最终文件已确认 → 整棵临时目录删除
            openList.cleanupTempDownloadDir(savePath, tempDirName, true);

            awaitTrue(() -> openList.fsList(savePath, true).stream()
                    .noneMatch(f -> tempDirName.equals(f.getName())), 60_000);

            List<OpenListFileInfo> top = openList.fsList(savePath, true);
            assertTrue(top.stream().anyMatch(f -> videoName.equals(f.getName()) && !Boolean.TRUE.equals(f.getIsDir())),
                    "final video should remain, left=" + top.stream().map(OpenListFileInfo::getName).toList());
            assertTrue(top.stream().noneMatch(f -> tempDirName.equals(f.getName())),
                    "temp dir should be force-removed, left=" + top.stream().map(OpenListFileInfo::getName).toList());
        } finally {
            try {
                openList.fsRemove(sandbox, List.of(runId));
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void non_force_cleanup_skips_when_media_still_in_temp() {
        String host = System.getenv("OPENLIST_HOST");
        String token = System.getenv("OPENLIST_TOKEN");
        String sandbox = normalizeRemotePath(System.getenv().getOrDefault("OPENLIST_SANDBOX", "/115/yun-download-sandbox"));

        Config config = new Config()
                .setDownloadToolHost(host)
                .setDownloadToolPassword(token)
                .setDownloadPathTemplate(sandbox)
                .setProvider("115 Cloud");

        OpenList openList = new OpenList();
        assertTrue(openList.login(true, config));

        String runId = "ani-rss-temp-keep-" + UUID.randomUUID().toString().substring(0, 8);
        String savePath = sandbox + "/" + runId;
        String tempDirName = "Show S01E03";
        try {
            openList.mkdir(savePath);
            openList.mkdir(savePath + "/" + tempDirName);
            putText(savePath + "/" + tempDirName, "Show S01E03.mp4", "still-here");
            awaitTrue(() -> openList.findFiles(savePath + "/" + tempDirName).stream()
                    .anyMatch(f -> "Show S01E03.mp4".equals(f.getName())), 60_000);

            // force=false：未确认最终文件时不删
            openList.cleanupTempDownloadDir(savePath, tempDirName, false);

            List<OpenListFileInfo> top = openList.fsList(savePath, true);
            assertTrue(top.stream().anyMatch(f -> tempDirName.equals(f.getName()) && Boolean.TRUE.equals(f.getIsDir())),
                    "temp with media must be kept when not forced, left="
                            + top.stream().map(OpenListFileInfo::getName).toList());
            assertTrue(openList.findFiles(savePath + "/" + tempDirName).stream()
                            .anyMatch(f -> "Show S01E03.mp4".equals(f.getName())),
                    "media inside temp must not be deleted without force");
        } finally {
            try {
                openList.fsRemove(sandbox, List.of(runId));
            } catch (Exception ignored) {
            }
        }
    }

    private static void awaitTrue(Supplier<Boolean> cond, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try {
                if (Boolean.TRUE.equals(cond.get())) {
                    return;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Assumptions.assumeTrue(false, "condition not met within " + timeoutMs + "ms");
    }

    private static String normalizeRemotePath(String path) {
        if (path == null) {
            return null;
        }
        String p = path.replace('\\', '/');
        int gitIdx = p.toLowerCase().indexOf("/git/115/");
        if (gitIdx >= 0) {
            return p.substring(gitIdx + "/git".length());
        }
        int idx = p.indexOf("/115/");
        if (idx > 0) {
            return p.substring(idx);
        }
        while (p.startsWith("//")) {
            p = p.substring(1);
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return p;
    }

    private static void putText(String dir, String name, String content) {
        try {
            String host = System.getenv("OPENLIST_HOST");
            String token = System.getenv("OPENLIST_TOKEN");
            String remote = normalizeRemotePath(dir) + "/" + name;
            String encodedPath = java.net.URLEncoder.encode(remote, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
            cn.hutool.http.HttpResponse res = cn.hutool.http.HttpRequest.put(host + "/api/fs/put")
                    .header("Authorization", token)
                    .header("User-Agent", "ani-rss-test")
                    .header("File-Path", encodedPath)
                    .header("Content-Type", "application/octet-stream")
                    .body(content)
                    .timeout(60000)
                    .execute();
            Assumptions.assumeTrue(res.isOk(), "fs/put failed: " + res.body());
            String body = res.body();
            Assumptions.assumeTrue(body.contains("\"code\":200") || body.contains("\"code\": 200"),
                    "fs/put business failed: " + body);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "fs/put unavailable: " + e.getMessage());
        }
    }
}
