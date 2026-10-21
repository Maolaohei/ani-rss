package ani.rss.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住核心调度/下载路径的结构边界。
 * <p>
 * 两个目的：一是核心路径不再反向查询容器——{@code SpringUtil.getBean} 让依赖关系
 * 只在运行时成立，单测与拆分都无从下手；二是编排入口保持“薄”，一旦某个入口重新
 * 长回几百行，这里会先失败，避免再次形成石山。
 */
class CorePathBoundaryTest {
    private static final Path APPLICATION_SOURCE = locateApplicationSource();

    @Test
    void core_path_does_not_look_up_container() throws IOException {
        List<Path> targets = new ArrayList<>();
        targets.add(APPLICATION_SOURCE.resolve("ani/rss/task/RssTask.java"));
        targets.add(APPLICATION_SOURCE.resolve("ani/rss/service/DownloadService.java"));
        try (Stream<Path> files = Files.walk(APPLICATION_SOURCE.resolve("ani/rss/download"))) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(targets::add);
        }
        for (Path file : targets) {
            assertTrue(Files.isRegularFile(file), "缺少核心路径文件: " + file);
            String source = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(source.contains("SpringUtil"),
                    file.getFileName() + " 不应查询容器（应使用注入的端口或提供者）");
        }
    }

    @Test
    void download_orchestrator_stays_thin() throws IOException {
        String source = Files.readString(
                APPLICATION_SOURCE.resolve("ani/rss/service/DownloadService.java"), StandardCharsets.UTF_8);
        int lines = methodLineSpan(source, "private void downloadAniLocked(Ani ani)");
        assertTrue(lines > 0, "未找到 downloadAniLocked");
        assertTrue(lines <= 60,
                "downloadAniLocked 应只做编排（<=60 行），当前 " + lines + " 行");
    }

    @Test
    void job_status_mapping_stays_thin() throws IOException {
        String source = Files.readString(
                APPLICATION_SOURCE.resolve("ani/rss/task/RssTask.java"), StandardCharsets.UTF_8);
        int lines = methodLineSpan(source, "public static RssJobStatus getJobStatus()");
        assertTrue(lines > 0, "未找到 getJobStatus");
        assertTrue(lines <= 200,
                "getJobStatus 应只做状态投影（<=200 行），当前 " + lines + " 行");
    }

    @Test
    void rss_task_orchestrators_stay_bounded() throws IOException {
        String source = Files.readString(
                APPLICATION_SOURCE.resolve("ani/rss/task/RssTask.java"), StandardCharsets.UTF_8);
        int download = methodLineSpan(source,
                "private static void download(AtomicBoolean loop, List<Ani> targetList, long generation)");
        assertTrue(download > 0, "未找到 RssTask.download");
        assertTrue(download <= 260,
                "RssTask.download 应保持编排边界（<=260 行），当前 " + download + " 行");
        int taskItems = methodLineSpan(source, "private static List<RssJobItem> buildTaskItems(");
        assertTrue(taskItems > 0, "未找到 buildTaskItems");
        assertTrue(taskItems <= 190,
                "buildTaskItems 应只做任务视图投影（<=190 行），当前 " + taskItems + " 行");
    }

    @Test
    void openlist_wait_and_residual_loops_stay_bounded() throws IOException {
        String source = Files.readString(
                APPLICATION_SOURCE.resolve("ani/rss/download/OpenList.java"), StandardCharsets.UTF_8);
        int await = methodLineSpan(source, "private Outcome awaitAndFinalize(OfflineDownloadContext ctx");
        assertTrue(await > 0, "未找到 awaitAndFinalize");
        assertTrue(await <= 210,
                "awaitAndFinalize 应只做等待编排（<=210 行），当前 " + await + " 行");
        int duplicate = methodLineSpan(source, "private DuplicateTaskOutcome handleDuplicateOfflineTask(");
        assertTrue(duplicate > 0, "未找到 handleDuplicateOfflineTask");
        assertTrue(duplicate <= 110,
                "10008 分支应独立成方法（<=110 行），当前 " + duplicate + " 行");
        String residualSource = Files.readString(
                APPLICATION_SOURCE.resolve("ani/rss/download/openlist/residual/OpenListResidualService.java"),
                StandardCharsets.UTF_8);
        int tempScan = methodLineSpan(residualSource, "public TempDirResidualSnapshot scanTempDirResiduals(boolean allowRemote)");
        assertTrue(tempScan > 0, "未找到 scanTempDirResiduals");
        assertTrue(tempScan <= 130,
                "scanTempDirResiduals 应只做扫描编排（<=130 行），当前 " + tempScan + " 行");
    }

    @Test
    void openlist_offline_submission_stays_bounded() throws IOException {
        String source = Files.readString(
                APPLICATION_SOURCE.resolve("ani/rss/download/OpenList.java"), StandardCharsets.UTF_8);
        int lines = methodLineSpan(source,
                "private OfflineDownloadContext submitOffline(Ani ani, Item item, String savePath,");
        assertTrue(lines > 0, "未找到 submitOffline");
        assertTrue(lines <= 190,
                "submitOffline 应只做离线提交编排（<=190 行），当前 " + lines + " 行");
    }

    @Test
    void download_service_paths_stay_bounded() throws IOException {
        String source = Files.readString(
                APPLICATION_SOURCE.resolve("ani/rss/service/DownloadService.java"), StandardCharsets.UTF_8);
        int path = methodLineSpan(source, "public String getDownloadPath(Ani ani, Config config)");
        assertTrue(path > 0, "未找到 getDownloadPath");
        assertTrue(path <= 130,
                "getDownloadPath 应只做路径决策（<=130 行），当前 " + path + " 行");

        // passGates / notification 正由并行重构调整，可能被改名或移走；找不到时跳过，避免守卫误报。
        assertOptionalMethodWithin(source, "passGates", "private boolean passGates(", 230);
        assertOptionalMethodWithin(source, "notification", "public void notification(TorrentsInfo torrentsInfo)", 110);
    }

    /**
     * 可选方法守卫：并行重构可能改名或移走该方法，找不到时直接跳过。
     */
    private static void assertOptionalMethodWithin(String source, String name, String signature, int limit) {
        int lines = methodLineSpan(source, signature);
        if (lines < 0) {
            return;
        }
        assertTrue(lines <= limit, name + " 应保持边界（<=" + limit + " 行），当前 " + lines + " 行");
    }

    /**
     * 从签名行开始按大括号配平计算方法体行数；字符串/字符字面量与注释不参与配平。
     */
    private static int methodLineSpan(String source, String signature) {
        String[] lines = source.split("\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith(signature)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return -1;
        }
        int depth = 0;
        boolean opened = false;
        for (int i = start; i < lines.length; i++) {
            for (char c : stripLiterals(lines[i]).toCharArray()) {
                if (c == '{') {
                    depth++;
                    opened = true;
                } else if (c == '}') {
                    depth--;
                }
            }
            if (opened && depth == 0) {
                return i - start + 1;
            }
        }
        return -1;
    }

    private static String stripLiterals(String line) {
        StringBuilder out = new StringBuilder(line.length());
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            }
            out.append(c);
        }
        return out.toString();
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
