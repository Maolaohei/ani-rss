package ani.rss.e2e;

import ani.rss.commons.GsonStatic;
import cn.hutool.core.io.FileUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 端到端测试的<b>可验证、可复现工件</b>。
 * <p>
 * 规则要求"端到端测试结束时生成一个可验证且可重复的工件"。这里落三样东西：
 * <ol>
 *   <li>{@code report.json}：机器可读，包含输入哈希、每个场景的结论与证据（含断言过的实际值）；</li>
 *   <li>{@code report.md}：人可读，同一份内容的表格 + 最终文件树；</li>
 *   <li>{@code inputs/}：把本次运行真正喂给应用的原始输入（RSS XML、种子文件、脱敏配置、
 *       订阅落盘文件）原样存下来 —— 任何人拿这些输入重跑都应得到同样的结论。</li>
 * </ol>
 * 工件目录固定在 {@code target/e2e-artifact}（构建产物，不进仓库），
 * 测试结束会打印绝对路径，CI 直接作为 artifact 上传。
 */
public final class E2EArtifact {

    public static final Path DIR = Path.of("target", "e2e-artifact");

    private final JsonObject root = new JsonObject();
    private final JsonArray scenarios = new JsonArray();
    private final JsonObject inputs = new JsonObject();
    private final JsonObject sections = new JsonObject();

    private E2EArtifact(String suiteName) {
        root.addProperty("suite", suiteName);
        root.addProperty("generatedAt", OffsetDateTime.now().toString());
        root.addProperty("appVersion", readVersion());
        root.addProperty("gitCommit", readGitCommit());
        root.addProperty("javaVersion", System.getProperty("java.version"));
        root.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        root.addProperty("reproduce",
                "mvn -o -pl ani-rss-application test -Dtest=" + suiteName + " -Dexec.skip=true");
    }

    public static E2EArtifact create(String suiteName) {
        return new E2EArtifact(suiteName);
    }

    /** 记录一项输入（键 + 值），值会原样进报告 */
    public void input(String key, String value) {
        inputs.addProperty(key, value == null ? "" : value);
    }

    /**
     * 把原始输入字节落盘（{@code inputs/}），并记录其 SHA-256 —— 哈希让"输入没被换过"可验证
     */
    public void inputFile(String name, byte[] content) {
        try {
            Path dir = DIR.resolve("inputs");
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), content);
            inputs.addProperty(name + ".sha256", DigestUtil.sha256Hex(content));
        } catch (Exception e) {
            inputs.addProperty(name + ".error", String.valueOf(e.getMessage()));
        }
    }

    public void inputFile(String name, String content) {
        inputFile(name, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 记录一个场景结论
     *
     * @param status PASS/FAIL
     * @param detail 一句话结论（失败时写清实际值）
     * @param evidence 断言到的实际值（目录树、任务状态、请求明细…）
     */
    public void scenario(String name, String status, long durationMs, String detail,
                         Map<String, Object> evidence) {
        JsonObject s = new JsonObject();
        s.addProperty("name", name);
        s.addProperty("status", status);
        s.addProperty("durationMs", durationMs);
        s.addProperty("detail", detail);
        Gson gson = new Gson();
        s.add("evidence", evidence == null
                ? new JsonObject()
                : gson.toJsonTree(new LinkedHashMap<>(evidence)));
        scenarios.add(s);
    }

    public int failureCount() {
        return (int) scenarios.asList().stream()
                .filter(e -> "FAIL".equals(e.getAsJsonObject().get("status").getAsString()))
                .count();
    }

    /** 附加一段长文本（报告 md 里原样输出，例如云盘最终目录树 / 订阅落盘内容） */
    public void section(String title, String text) {
        sections.addProperty(title, text);
    }

    /**
     * 落盘 report.json + report.md，返回绝对路径（打印给使用者；CI 直接上传该目录）
     */
    public Path write() {
        try {
            Files.createDirectories(DIR);
            root.add("inputs", inputs);
            root.add("scenarios", scenarios);
            root.add("sections", sections);

            Path json = DIR.resolve("report.json");
            Files.writeString(json, GsonStatic.prettyJson(root.toString()), StandardCharsets.UTF_8);

            Path md = DIR.resolve("report.md");
            Files.writeString(md, markdown(), StandardCharsets.UTF_8);

            Path abs = DIR.toAbsolutePath().normalize();
            System.out.println("[e2e] 工件已生成: " + abs + " (report.json / report.md / inputs/)");
            return abs;
        } catch (Exception e) {
            throw new IllegalStateException("写入 e2e 工件失败: " + e.getMessage(), e);
        }
    }

    private String markdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ani-rss 端到端测试报告\n\n");
        sb.append("| 项 | 值 |\n|---|---|\n");
        sb.append("| 套件 | ").append(root.get("suite").getAsString()).append(" |\n");
        sb.append("| 时间 | ").append(root.get("generatedAt").getAsString()).append(" |\n");
        sb.append("| 版本 | ").append(root.get("appVersion").getAsString()).append(" |\n");
        sb.append("| 提交 | ").append(root.get("gitCommit").getAsString()).append(" |\n");
        sb.append("| 环境 | ").append(root.get("javaVersion").getAsString())
                .append(" / ").append(root.get("os").getAsString()).append(" |\n");
        sb.append("| 复现命令 | `").append(root.get("reproduce").getAsString()).append("` |\n\n");

        sb.append("## 结论\n\n");
        sb.append("| 场景 | 结果 | 耗时 | 说明 |\n|---|---|---|---|\n");
        for (var el : scenarios) {
            JsonObject s = el.getAsJsonObject();
            sb.append("| ").append(s.get("name").getAsString())
                    .append(" | ").append(s.get("status").getAsString())
                    .append(" | ").append(s.get("durationMs").getAsLong()).append("ms")
                    .append(" | ").append(s.get("detail").getAsString()).append(" |\n");
        }
        sb.append("\n失败场景数: ").append(failureCount()).append("\n");

        sb.append("\n## 输入（含 SHA-256）\n\n");
        sb.append("```json\n").append(GsonStatic.prettyJson(inputs.toString())).append("\n```\n");

        if (!sections.isEmpty()) {
            sb.append("\n## 证据\n\n");
            for (String key : sections.keySet()) {
                sb.append("### ").append(key).append("\n\n```\n")
                        .append(sections.get(key).getAsString()).append("\n```\n");
            }
        }

        sb.append("\n## 场景证据明细\n\n```json\n")
                .append(GsonStatic.prettyJson(scenarios.toString()))
                .append("\n```\n");
        return sb.toString();
    }

    private static String readVersion() {
        try {
            String v = ani.rss.commons.MavenUtils.getVersion();
            return v == null ? "unknown" : v;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 仓库提交（构建时由 generate-resources.sh 写入 build.info；测试环境可能不存在） */
    private static String readGitCommit() {
        try {
            String info = cn.hutool.core.io.IoUtil.readUtf8(
                    FakeOriginServer.class.getResourceAsStream("/build.info")).trim();
            return info.replace('\n', ' ');
        } catch (Exception e) {
            return "unknown";
        }
    }
}
