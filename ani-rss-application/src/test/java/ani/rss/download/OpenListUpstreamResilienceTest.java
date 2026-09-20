package ani.rss.download;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.util.other.ConfigUtil;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上游（OpenList → 115 那一跳）抖动时的三条缓解：
 * <ol>
 *   <li>握手超时算瞬时故障 → 自动重试，一过性抖动不该变成"一次失败"；</li>
 *   <li>同一目录失败在本轮内只打一次 → 一个订阅的 N 条记录不会把熔断计数顶到阈值；</li>
 *   <li>云下载目录本轮只真列一次 → 不再每条记录都失效重列。</li>
 * </ol>
 * <p>
 * 背景是线上这条反复刷屏的日志（TLS 握手超时被 115 驱动层层包装后以 code=500 回来）：
 * <pre>
 * fs/list 失败 code=500 path=/115/…/Season 1 message=failed get objs: failed to list objs:
 *         Get "https://webapi.115.com/files?…": net/http: TLS handshake timeout
 * </pre>
 */
class OpenListUpstreamResilienceTest {

    private HttpServer server;
    /**
     * 每个测试用独立路径：{@code findFilesCache} 是进程级静态缓存，
     * 若各用例共用同一路径，前一个用例的"空目录"结果会把后一个用例的请求全部吃掉。
     */
    private String savePath;
    private String cloudDir;
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    /** 接下来 N 次 fs/list 注入失败（模拟一过性抖动） */
    private final AtomicInteger failuresToInject = new AtomicInteger(0);
    /** 这些目录上的 fs/list 永久失败（模拟持续性故障） */
    private final Set<String> alwaysFailPaths = ConcurrentHashMap.newKeySet();
    /** 这些目录不存在（业务结果，不是故障） */
    private final Set<String> missingPaths = ConcurrentHashMap.newKeySet();
    /** 目录存在时的直接子项名（给 probeDirectChildren 用） */
    private volatile List<String> childNames = List.of();

    private Integer prevFailThreshold;
    private Integer prevCooldownSeconds;
    private String prevCloudDir;

    @BeforeEach
    void setUp() throws IOException {
        requests.clear();
        failuresToInject.set(0);
        alwaysFailPaths.clear();
        missingPaths.clear();
        childNames = List.of();
        savePath = "/115/动漫/转存/追番/某番-" + java.util.UUID.randomUUID() + " (2026) [tmdbid=1]/Season 1";
        cloudDir = "/115/云下载-" + java.util.UUID.randomUUID();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/fs/list", exchange -> {
            String path = requestPath(exchange);
            requests.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            if (missingPaths.contains(path)) {
                // 目录不存在：业务 code != 200，文案命中 isDirNotFoundMessage
                respond(exchange, 200, "{\"code\":500,\"message\":\"failed to get dir: object not found\"}");
                return;
            }
            if (shouldFail(path)) {
                // 线上原文：OpenList 把 Go 的错误以 **HTTP 200 + 业务 code=500** 回给我们
                // （HTTP 状态码是 200，所以 assertStatus 不会先生；这正是实际形状）
                respond(exchange, 200, "{\"code\":500,\"message\":\"failed get objs: failed get dir: "
                        + "failed to list objs: Get \\\"https://webapi.115.com/files?aid=1&limit=1000\\\": "
                        + "net/http: TLS handshake timeout\"}");
                return;
            }
            respond(exchange, 200, GsonStatic.toJson(Map.of(
                    "code", 200,
                    "data", Map.of("content", childNames.stream()
                            .map(name -> Map.of("name", name, "is_dir", false))
                            .toList()))));
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        prevFailThreshold = ConfigUtil.CONFIG.getOpenListFailThreshold();
        prevCooldownSeconds = ConfigUtil.CONFIG.getOpenListCooldownSeconds();
        prevCloudDir = ConfigUtil.CONFIG.getAlistCloudDownloadDir();
        // 固定云下载目录：否则会触发"自动发现"递归扫描，污染请求计数
        ConfigUtil.CONFIG.setAlistCloudDownloadDir(cloudDir);
        OpenListApi.resetRateLimitState();
    }

    @AfterEach
    void tearDown() {
        ConfigUtil.CONFIG.setOpenListFailThreshold(prevFailThreshold)
                .setOpenListCooldownSeconds(prevCooldownSeconds)
                .setAlistCloudDownloadDir(prevCloudDir);
        OpenListApi.resetRateLimitState();
        server.stop(0);
    }

    private boolean shouldFail(String path) {
        if (alwaysFailPaths.contains(path)) {
            return true;
        }
        synchronized (failuresToInject) {
            if (failuresToInject.get() > 0) {
                failuresToInject.decrementAndGet();
                return true;
            }
        }
        return false;
    }

    private static String requestPath(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject json = GsonStatic.fromJson(body, JsonObject.class);
        return json.has("path") ? json.get("path").getAsString() : "";
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
        // 云下载目录必须配在这一份 config 上：auto-discovery 会从 "/" 递归扫描，污染请求计数
        openList.login(true, new Config()
                .setDownloadToolHost("http://127.0.0.1:" + server.getAddress().getPort())
                .setDownloadToolPassword("mock-token")
                .setProvider("115 Cloud")
                .setDownloadPathTemplate("/追番")
                .setAlistCloudDownloadDir(cloudDir));
        return openList;
    }

    private int requests(String path) {
        AtomicInteger counter = requests.get(path);
        return counter == null ? 0 : counter.get();
    }

    private static Item item(String reName) {
        return new Item().setReName(reName).setInfoHash("hash-" + reName);
    }

    private static Ani ani() {
        return new Ani().setId("ani-upstream").setTitle("某番").setSeason(1);
    }

    // ---------------- 1. 握手超时 → 重试 ----------------

    @Test
    @DisplayName("上游 TLS 握手超时按瞬时故障重试：抖动一次不该直接记一次失败")
    void upstream_timeout_is_retried() {
        failuresToInject.set(2);
        assertEquals(OfflineDownloader.RelocateResult.NOT_FOUND,
                openList().relocateEpisodeFiles(ani(), item("某番 S01E01"), savePath),
                "重试到成功就该正常给结论，而不是 UNVERIFIABLE");
        assertEquals(3, requests(savePath), "应重试到第 3 次成功（首次 + 2 次重试）");
        assertFalse(OpenListApi.isListingCoolingDown(), "重试成功后不该留下失败计数");
    }

    // ---------------- 2. 同一目录失败记忆 ----------------

    @Test
    @DisplayName("同一目录的失败本轮只打一次：一个订阅的 N 条记录不该把熔断顶开")
    void same_path_failure_is_only_attempted_once() {
        ConfigUtil.CONFIG.setOpenListFailThreshold(3).setOpenListCooldownSeconds(60);
        alwaysFailPaths.add(savePath);

        OpenList openList = openList();
        assertEquals(OfflineDownloader.RelocateResult.UNVERIFIABLE,
                openList.relocateEpisodeFiles(ani(), item("某番 S01E01"), savePath));
        int afterFirst = requests(savePath);
        assertTrue(afterFirst > 1, "第一次应包含重试，实际 " + afterFirst);

        // 同订阅的第二条记录：同一目录已经失败过 → 直接上抛，不再打网盘、不再计失败
        assertEquals(OfflineDownloader.RelocateResult.UNVERIFIABLE,
                openList.relocateEpisodeFiles(ani(), item("某番 S01E02"), savePath));
        assertEquals(afterFirst, requests(savePath), "同一目录的失败不该被重复打出去");
        assertTrue(OpenListApi.getListingFailureMemoHit() > 0, "应命中失败记忆");
        assertFalse(OpenListApi.isListingCoolingDown(),
                "只应计 1 次失败（重试后仍失败），不该熔断并让整批订阅停摆");
    }

    // ---------------- 3. 云下载目录本轮只列一次 ----------------

    @Test
    @DisplayName("云下载目录本轮只真列一次：N 条记录不该打 N 次网盘")
    void cloud_dir_is_listed_once_per_round() {
        OpenList openList = openList();
        assertEquals(OfflineDownloader.RelocateResult.NOT_FOUND,
                openList.relocateEpisodeFiles(ani(), item("某番 S01E01"), savePath));
        assertEquals(1, requests(cloudDir), "第一次对账应真列一次云下载目录");

        assertEquals(OfflineDownloader.RelocateResult.NOT_FOUND,
                openList.relocateEpisodeFiles(ani(), item("某番 S01E02"), savePath));
        assertEquals(1, requests(cloudDir), "同一轮内不该为第二条记录再列一次云下载目录");
        assertEquals(1, requests(savePath), "下载目录本身应命中 30s 列举缓存");
    }

    // ---------------- 4. 自检用的目录探测 ----------------

    @Test
    @DisplayName("probeDirectChildren：目录存在返回直接子项，不存在抛 OpenListDirNotFoundException")
    void probe_direct_children_distinguishes_missing() {
        OpenList openList = openList();
        childNames = List.of("某番 S01E01.mkv", "某番 S01E02.mkv");
        assertTrue(openList.probeDirectChildren(savePath).containsAll(childNames));

        String missing = savePath + "/不存在";
        missingPaths.add(missing);
        assertThrows(OpenListApi.OpenListDirNotFoundException.class,
                () -> openList.probeDirectChildren(missing),
                "目录不存在必须是可识别的业务结果（自检据此区分「未创建」与「查不到」）");
        // 单层探测：不因为子目录多而打出一整棵树
        assertEquals(1, requests(savePath), "同一目录只发一次单层列举");
    }
}
