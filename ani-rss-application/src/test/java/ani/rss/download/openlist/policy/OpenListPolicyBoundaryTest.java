package ani.rss.download.openlist.policy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住 OpenList 策略层的依赖边界。
 * <p>
 * 策略层必须是纯判定代码：不能反向依赖调度/下载编排，也不能持有实例状态。
 * 这样后续把 OpenList 拆成更多模块时，不会再次形成“拆开但互相回指”的结构。
 */
class OpenListPolicyBoundaryTest {
    private static final List<String> FORBIDDEN_REFERENCES = List.of(
            "ani.rss.task.",
            "ani.rss.service.",
            "SpringUtil",
            "java.net.",
            "java.util.concurrent."
    );

    @Test
    void policy_has_no_instance_state() {
        for (Field field : OpenListPolicy.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            assertTrue(Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers),
                    "策略类字段必须是 static final: " + field.getName());
        }
    }

    @Test
    void policy_does_not_reference_orchestration_or_io() throws IOException {
        Path sourceDir = locateSourceDirectory("src/main/java/ani/rss/download/openlist/policy",
                "ani-rss-application/src/main/java/ani/rss/download/openlist/policy");
        try (Stream<Path> files = Files.list(sourceDir)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (String forbidden : FORBIDDEN_REFERENCES) {
                    assertFalse(source.contains(forbidden),
                            file.getFileName() + " 不应引用 " + forbidden);
                }
            }
        }
    }

    @Test
    void openlist_module_does_not_reference_scheduler_or_container_lookup() throws IOException {
        Path sourceDir = locateSourceDirectory("src/main/java/ani/rss/download/openlist",
                "ani-rss-application/src/main/java/ani/rss/download/openlist");
        List<String> forbidden = List.of("ani.rss.task.", "ani.rss.service.", "SpringUtil");
        try (Stream<Path> files = Files.walk(sourceDir)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (String token : forbidden) {
                    assertFalse(source.contains(token),
                            file.getFileName() + " 不应引用 " + token);
                }
            }
        }
    }

    private static Path locateSourceDirectory(String moduleRelativePath, String repositoryRelativePath) {
        Path moduleRelative = Path.of(moduleRelativePath);
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative;
        }
        Path repositoryRelative = Path.of(repositoryRelativePath);
        assertTrue(Files.isDirectory(repositoryRelative),
                "无法定位 OpenList 策略源码目录");
        return repositoryRelative;
    }
}
