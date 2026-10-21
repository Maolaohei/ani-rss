package ani.rss.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住 DownloadService 闸门链与通知方法的结构边界。
 */
class DownloadServiceStructureTest {
    private static final Path APPLICATION_SOURCE = locateApplicationSource();

    @Test
    void pass_gates_stay_explicit_and_bounded() throws IOException {
        String source = Files.readString(
                APPLICATION_SOURCE.resolve("ani/rss/service/DownloadService.java"), StandardCharsets.UTF_8);
        assertWithin(source, "private boolean passGates(", 60);
        List<String> gates = List.of(
                "private GateResult versionGate(",
                "private GateResult existingRecordGate(",
                "private GateResult episodeDuplicateGate(",
                "private GateResult rangeDuplicateGate(",
                "private GateResult notDownloadGate(",
                "private GateResult latestOnlyGate(",
                "private GateResult delayGate(",
                "private GateResult newTorrentWaitGate(",
                "private GateResult standbyCleanupGate(",
                "private GateResult existingTaskGate(",
                "private GateResult pushedHashGate(",
                "private GateResult localPresentGate(",
                "private GateResult concurrencyGate("
        );
        for (String gate : gates) {
            assertWithin(source, gate, 40);
        }
        assertOrder(source, "private boolean passGates(", gates);
    }

    @Test
    void download_orchestrator_and_notification_stay_thin() throws IOException {
        String source = Files.readString(
                APPLICATION_SOURCE.resolve("ani/rss/service/DownloadService.java"), StandardCharsets.UTF_8);
        assertWithin(source, "private void downloadAniLocked(Ani ani)", 60);
        assertWithin(source, "public void notification(TorrentsInfo torrentsInfo)", 50);
    }

    private static void assertWithin(String source, String signature, int maxLines) {
        int lines = methodLineSpan(source, signature);
        assertTrue(lines > 0, "未找到方法: " + signature);
        assertTrue(lines <= maxLines, signature + " 应 <= " + maxLines + " 行，当前 " + lines + " 行");
    }

    private static void assertOrder(String source, String methodSignature, List<String> calls) {
        int bodyStart = methodStart(source, methodSignature);
        assertTrue(bodyStart >= 0, "未找到方法: " + methodSignature);
        int end = methodEnd(source, bodyStart);
        assertTrue(end > bodyStart, "无法确定方法边界: " + methodSignature);
        String body = source.substring(bodyStart, end);
        int cursor = -1;
        for (String call : calls) {
            String invocation = call.substring(call.lastIndexOf(' ') + 1);
            int found = body.indexOf(invocation, cursor + 1);
            assertTrue(found > cursor, "闸门顺序不符合预期: " + invocation);
            cursor = found;
        }
    }

    private static int methodLineSpan(String source, String signature) {
        int start = methodStart(source, signature);
        if (start < 0) {
            return -1;
        }
        int end = methodEnd(source, start);
        return end < 0 ? -1 : source.substring(start, end).split("\n", -1).length;
    }

    private static int methodStart(String source, String signature) {
        String[] lines = source.split("\n");
        int offset = 0;
        for (String line : lines) {
            if (line.trim().startsWith(signature)) {
                return offset;
            }
            offset += line.length() + 1;
        }
        return -1;
    }

    private static int methodEnd(String source, int start) {
        int depth = 0;
        boolean opened = false;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(source, i, c);
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = skipLineComment(source, i);
                continue;
            }
            if (c == '{') {
                depth++;
                opened = true;
            } else if (c == '}') {
                depth--;
                if (opened && depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private static int skipLiteral(String source, int start, char quote) {
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            }
        }
        return source.length() - 1;
    }

    private static int skipLineComment(String source, int start) {
        int newline = source.indexOf('\n', start + 2);
        return newline < 0 ? source.length() - 1 : newline - 1;
    }

    private static Path locateApplicationSource() {
        Path moduleRelative = Path.of("src/main/java");
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative;
        }
        Path repositoryRelative = Path.of("ani-rss-application/src/main/java");
        assertTrue(Files.isDirectory(repositoryRelative), "无法定位应用模块源码目录");
        return repositoryRelative;
    }
}
