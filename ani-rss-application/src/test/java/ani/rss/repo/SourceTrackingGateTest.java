package ani.rss.repo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 闸门：源码/资源目录里不允许出现「已被 .gitignore 忽略、且未纳入版本控制」的文件。
 * <p>
 * 为什么需要它：这类文件在 {@code git status} 与 {@code git add -A} 里完全隐形，
 * 于是能一路通过本地编译，却让干净检出的 CI 编译失败。真实事故：{@code .gitignore}
 * 里一条裸模式 {@code files} 把 {@code src/main/java/ani/rss/download/openlist/files}
 * 整个包吞掉，推送后 CI 报 9 个「package ... does not exist / cannot find symbol」，
 * 而本地（文件还在磁盘上）编译全绿。用 {@code git add -f} 只能补回已经丢掉的这一个包，
 * 这里改为在测试阶段拦住整类问题。
 * <p>
 * 判定口径：在仓库根执行 {@code git ls-files --others --ignored --exclude-standard}
 * 仍能列出文件即失败。用标准 excludes（仓库 .gitignore + .git/info/exclude + 全局 excludes），
 * 与 {@code git add} 的实际可见性一致；失败信息附带 {@code git check-ignore -v}，
 * 直接指出是哪一行规则吞掉的。
 * <p>
 * 两种情况按跳过处理：非 Git 检出（源码包解压、构建产物内跑测试）、机器上没有 git。
 * 少数「有意忽略」的构建期产物在 {@link #INTENTIONALLY_IGNORED} 中显式登记。
 */
class SourceTrackingGateTest {

    /** 参与检查的源码/资源/脚本根目录（相对仓库根）。 */
    private static final List<String> SOURCE_ROOTS = List.of(
            "ani-rss-application/src/main/java",
            "ani-rss-application/src/main/resources",
            "ani-rss-application/src/test/java",
            "ani-rss-application/src/test/resources",
            "ani-rss-ui/src",
            ".github/workflows",
            "docker",
            "linux"
    );

    /**
     * 有意忽略的构建期产物，与 .gitignore 中的说明一一对应：
     * CI 会自己下载 {@code ani-rss-update.exe}、写入 {@code build.info}；
     * {@code %SystemDrive%/} 是 Windows 组件服务拿未展开环境变量当目录名写出的缓存。
     * 除此之外任何被忽略的源码文件都按事故处理。
     */
    private static final List<String> INTENTIONALLY_IGNORED = List.of(
            "ani-rss-application/src/main/resources/ani-rss-update.exe",
            "ani-rss-application/src/main/resources/build.info",
            "ani-rss-application/src/main/resources/%SystemDrive%/"
    );

    @Test
    void source_roots_have_no_ignored_untracked_files() throws IOException, InterruptedException {
        Path repositoryRoot = locateRepositoryRoot();
        assumeTrue(repositoryRoot != null, "未找到带 pom.xml 的 .git 检出，跳过源码纳管闸门");

        for (String root : SOURCE_ROOTS) {
            assertTrue(Files.exists(repositoryRoot.resolve(root)),
                    "闸门配置的源码根不存在，请同步 SOURCE_ROOTS: " + root);
        }

        List<String> args = new ArrayList<>(List.of(
                "ls-files", "--others", "--ignored", "--exclude-standard", "--"));
        args.addAll(SOURCE_ROOTS);
        GitOutput found = git(repositoryRoot, args);
        assumeTrue(found.exitCode() == 0,
                "git ls-files 执行失败（exit=" + found.exitCode() + "）:\n" + found.output());

        List<String> offenders = new ArrayList<>();
        for (String line : found.output().split("\n")) {
            String path = line.strip();
            if (!path.isEmpty() && !isIntentionallyIgnored(path)) {
                offenders.add(path);
            }
        }
        assertTrue(offenders.isEmpty(), describe(repositoryRoot, offenders));
    }

    private static boolean isIntentionallyIgnored(String path) {
        return INTENTIONALLY_IGNORED.stream().anyMatch(path::startsWith);
    }

    private static String describe(Path repositoryRoot, List<String> offenders)
            throws IOException, InterruptedException {
        StringBuilder message = new StringBuilder()
                .append("以下源码文件被忽略且未纳入版本控制，提交时会被静默漏掉")
                .append("（本地编译通过、干净检出的 CI 编译失败）：\n");
        for (String path : offenders) {
            message.append("  ").append(path).append('\n');
        }
        message.append("命中规则：\n");
        List<String> args = new ArrayList<>(List.of("check-ignore", "-v", "--"));
        args.addAll(offenders);
        message.append(git(repositoryRoot, args).output());
        message.append("处理方式：收窄 .gitignore 的匹配范围（用 / 前缀锚定仓库根），")
                .append("不要用 git add -f 掩盖；确实是有意忽略的构建期产物，")
                .append("请登记进 SourceTrackingGateTest#INTENTIONALLY_IGNORED。");
        return message.toString();
    }

    /**
     * 从当前工作目录向上找到本仓库根（同时具备 .git 与 pom.xml）。
     * 找不到就返回 null（由调用方跳过），避免把外层无关仓库当成本仓库来判定。
     */
    private static Path locateRepositoryRoot() {
        Path candidate = Path.of("src/main/java").toAbsolutePath();
        while (candidate != null) {
            if (Files.exists(candidate.resolve(".git"))) {
                return Files.isRegularFile(candidate.resolve("pom.xml")) ? candidate : null;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    private static GitOutput git(Path repositoryRoot, List<String> args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repositoryRoot.toString()));
        command.addAll(args);
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            // 机器上没有 git：闸门无从判定，跳过而不是误报
            assumeTrue(false, "未安装 git，跳过源码纳管闸门: " + e.getMessage());
            return new GitOutput(-1, "");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new GitOutput(exitCode, output);
    }

    private record GitOutput(int exitCode, String output) {
    }
}
