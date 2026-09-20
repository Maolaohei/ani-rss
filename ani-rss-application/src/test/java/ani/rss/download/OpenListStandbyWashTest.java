package ani.rss.download;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenList 侧的备用RSS洗版（{@code washStandbyFiles}）。
 * <p>
 * 两条契约：
 * <ol>
 *   <li><b>只删"同一集"</b>：{@code S01E01} 不得牵连 {@code S01E010} / {@code S01E01.5}，
 *       也不能删本次刚创建的任务目录（删了 = 115 任务 Failed → 10008 死循环）；</li>
 *   <li><b>列举失败不能静默</b>：宽容版 {@code fsList} 会把失败吞成空列表 →
 *       洗版什么都没删、用户却以为已替换。必须上抛让调用方记"本次洗版跳过"。</li>
 * </ol>
 */
class OpenListStandbyWashTest {

    private static final String SAVE_PATH = "/115/动漫/转存/追番/某番 (2026)/Season 2";

    private HttpServer server;
    private final AtomicInteger listRequests = new AtomicInteger();
    private final List<String> removed = Collections.synchronizedList(new ArrayList<>());
    /** 注入列举失败（模拟网盘抖动） */
    private volatile boolean listFails = false;

    @BeforeEach
    void setUp() throws IOException {
        listRequests.set(0);
        removed.clear();
        listFails = false;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/fs/list", exchange -> {
            listRequests.incrementAndGet();
            if (listFails) {
                respond(exchange, 200, "{\"code\":500,\"message\":\"failed to list objs\"}");
                return;
            }
            List<Map<String, Object>> content = new ArrayList<>();
            for (String name : List.of(
                    "某番 S01E01.mkv",          // 本集旧视频：应删
                    "某番 S01E01.ass",          // 本集旧字幕：也应删（旧字幕留着会与新视频错配）
                    "某番 S01E010.mkv",         // 别的集：绝不能删
                    "某番 S01E01.5.mkv",        // .5 集：绝不能删
                    "某番 S01E01-taskdir",      // 本次刚创建的任务目录：绝不能删
                    "某番 S01E02.mkv")) {       // 别的集：绝不能删
                content.add(Map.of("name", name, "is_dir", name.endsWith("taskdir")));
            }
            respond(exchange, 200, GsonStatic.toJson(Map.of("code", 200, "data", Map.of("content", content))));
        });
        server.createContext("/api/fs/remove", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject json = GsonStatic.fromJson(body, JsonObject.class);
            if (json.has("names")) {
                json.getAsJsonArray("names").forEach(name -> removed.add(name.getAsString()));
            }
            respond(exchange, 200, "{\"code\":200,\"message\":\"success\"}");
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        // 必须复位：失败记忆/熔断是静态状态，上一个用例注入的"列举失败"会按 path 命中本用例
        OpenListApi.resetRateLimitState();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private OpenList openList() {
        OpenList openList = new OpenList();
        openList.login(true, new Config()
                .setDownloadToolHost("http://127.0.0.1:" + server.getAddress().getPort())
                .setDownloadToolPassword("mock-token")
                .setProvider("115 Cloud")
                .setDownloadPathTemplate("/追番"));
        return openList;
    }

    @Test
    @DisplayName("只删本集：不牵连 S01E010 / S01E01.5 / 其它集 / 本次任务目录")
    void wash_deletes_only_the_same_episode() {
        int washed = openList().washStandbyFiles(SAVE_PATH, "S01E01", "某番 S01E01-taskdir");

        assertEquals(2, washed, "本集的视频 + 字幕都要清（旧字幕留着会与新视频错配）");
        assertEquals(List.of("某番 S01E01.mkv", "某番 S01E01.ass"), removed,
                "只有本集条目能删；S01E010 / S01E01.5 / 任务目录 / 其它集一律不能动");
    }

    @Test
    @DisplayName("列举失败必须上抛（不能静默什么都不删），且一个删除请求都不发")
    void wash_failure_is_not_silent() {
        listFails = true;

        assertThrows(RuntimeException.class,
                () -> openList().washStandbyFiles(SAVE_PATH, "S01E01", "某番 S01E01-taskdir"),
                "宽容版会把失败吞成空列表 —— 必须上抛让调用方记「本次洗版跳过」");
        assertTrue(removed.isEmpty(), "列举都没成功，绝不该发删除请求");
        assertTrue(listRequests.get() > 0, "前置条件：确实发起过列举");
    }
}
