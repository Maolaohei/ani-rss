package ani.rss.download;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.OpenListFileInfo;
import ani.rss.entity.OpenListTaskInfo;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenList 离线下载全流程模拟测试：
 * 用本地 HTTP mock server 模拟 AList/115 API（内存文件系统 + 离线任务状态机），
 * OpenList 走真实代码路径（login -> download -> 异步等待 -> 重命名 -> 移动 -> 清理）。
 * <p>
 * 重点回归：115 离线任务在落点目录下按任务名（=种子文件名含扩展名）再建一层目录，
 * 单文件种子出现「模板目录/文件名.mkv/同名文件」嵌套时，必须正确识别文件、
 * 移动归位并清理空壳，不允许把目录当视频处理或残留嵌套。
 */
class OpenListWorkflowSimulationTest {

    private MockAlistServer server;

    private static final String HASH1 = "1c6c6e863114b7191b4c66699f3be2e55f1254cf";
    private static final String HASH2 = "2c6c6e863114b7191b4c66699f3be2e55f1254cf";
    private static final String HASH3 = "3c6c6e863114b7191b4c66699f3be2e55f1254cf";
    private static final String HASH4 = "4c6c6e863114b7191b4c66699f3be2e55f1254cf";
    private static final String HASH5 = "5c6c6e863114b7191b4c66699f3be2e55f1254cf";
    private static final String RAW_FILE_NAME = "[LoliHouse] Show - 03 [1080p].mkv";

    @BeforeEach
    void setUp() throws IOException {
        server = new MockAlistServer();
        server.start();
        // OpenListApi.findFilesCache 是静态的、跨测试共享：清掉上一个测试的缓存，避免路径串扰
        new OpenListApi().invalidateFindFilesCache();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // ============ 场景 1：单文件种子 + 115 任务目录嵌套（目标路径） ============

    @Test
    void single_file_seed_with_task_dir_nested_is_moved_and_cleaned() throws Exception {
        String savePath = "/追番/Show/Season 1";
        String tempDirName = "Show S01E03";
        // mock: 提交后 115 在目标目录下建「任务目录=种子文件名.mkv」并放入同名文件
        server.placeTaskDirInTarget = true;

        OpenList openList = openList(savePath);
        File torrent = torrentFile(HASH1);

        assertEquals(Boolean.TRUE, openList.download(ani(), item(HASH1), savePath, torrent));

        // 等待完整后处理结束（移动 + 空壳清理）：savePath 顶层只剩最终文件
        awaitFinalTopLevel(savePath, List.of(tempDirName + ".mkv"), 30_000);

        // 全树无嵌套残留（模板目录与任务名.mkv 目录都不该存在）
        assertFalse(server.exists(savePath + "/" + tempDirName), "模板目录应被清理");
        assertFalse(server.exists(savePath + "/" + tempDirName + "/" + RAW_FILE_NAME),
                "115 任务目录（名=文件名.mkv）应被清理");
        assertFalse(server.exists(savePath + "/" + tempDirName + "/" + RAW_FILE_NAME + "/" + RAW_FILE_NAME),
                "不应残留 文件.mkv/文件.mkv 嵌套");

        // findFiles 结果必须全是文件（目录不得被当视频）
        List<OpenListFileInfo> all = openList.findFiles(savePath);
        assertFalse(all.isEmpty(), "savePath 下应有最终文件");
        assertTrue(all.stream().noneMatch(f -> Boolean.TRUE.equals(f.getIsDir())),
                "findFiles 不得返回目录条目");
        assertTrue(all.stream().anyMatch(f -> (tempDirName + ".mkv").equals(f.getName())),
                "应存在最终文件 " + tempDirName + ".mkv");
    }

    // ============ 场景 2：savepath 未生效，文件落在云下载目录（兜底识别+移动+清理空壳） ============

    @Test
    void cloud_download_fallback_moves_file_and_cleans_shell_dir() throws Exception {
        String savePath = "/追番/Show/Season 1";
        String tempDirName = "Show S01E03";
        // mock: 提交后目标目录不放文件（savepath 未生效），文件预留在 /云下载/文件名.mkv/文件名.mkv
        server.placeTaskDirInTarget = false;
        server.putFile("/云下载/" + RAW_FILE_NAME + "/" + RAW_FILE_NAME, 1000L);

        OpenList openList = openList(savePath);
        assertEquals(Boolean.TRUE, openList.download(ani(), item(HASH2), savePath, torrentFile(HASH2)));

        awaitFinalTopLevel(savePath, List.of(tempDirName + ".mkv"), 30_000);
        // 云下载空壳清理同样是后处理的一部分，等待其完成
        awaitFinalTopLevel("/云下载", List.of(), 30_000);

        // 正面证据：兜底路径真实触发——程序必须 fs/list 扫描过云下载目录
        assertTrue(server.fsListCalls.stream().anyMatch(p -> p.contains("云下载")),
                "云下载兜底应扫描 /云下载 目录（resolveCloudDownloadDir + findFiles），实际 fs/list 调用=" + server.fsListCalls);

        // 云下载目录空壳应被清理
        assertFalse(server.exists("/云下载/" + RAW_FILE_NAME),
                "云下载目录下的任务空壳（名=文件名.mkv）应被清理");
        assertFalse(server.exists("/云下载/" + RAW_FILE_NAME + "/" + RAW_FILE_NAME),
                "云下载目录下不应残留 文件.mkv/文件.mkv 嵌套");
    }

    // ============ 场景 3：合集多文件（原始标题命名）重命名+移动 ============

    @Test
    void collection_multi_file_renamed_and_moved() throws Exception {
        String savePath = "/追番/Show/Season 1";
        String tempDirName = "Show S01E01";
        server.placeTaskDirInTarget = true;
        server.multiFileSeed = true;

        Item item = item(HASH3);
        item.setEpisodeRange(List.of(1.0, 2.0));
        item.setReName("Show S01E01");

        OpenList openList = openList(savePath);
        assertEquals(Boolean.TRUE, openList.download(ani(), item, savePath, torrentFile(HASH3)));

        awaitFinalTopLevel(savePath, List.of("Show S01E01.mkv", "Show S01E02.mkv"), 30_000);
        assertFalse(server.exists(savePath + "/" + tempDirName), "合集模板目录应被清理");
    }

    // ============ 场景 4：遗留嵌套目录（本地已存在）+ 10008 云端残留 → 识别并归位 ============

    @Test
    void legacy_template_dir_detected_as_locally_existing_and_recovered() throws Exception {
        String savePath = "/追番/Show/Season 1";
        String tempDirName = "Show S01E03";
        // 115 云端去重残留：任何提交都 10008；文件早已在目标目录（遗留的嵌套结构）
        server.forceDuplicateAdd = true;
        server.putFile(savePath + "/" + tempDirName + "/" + RAW_FILE_NAME + "/" + RAW_FILE_NAME, 1000L);

        OpenList openList = openList(savePath);
        assertEquals(Boolean.TRUE, openList.download(ani(), item(HASH4), savePath, torrentFile(HASH4)));

        awaitFinalTopLevel(savePath, List.of(tempDirName + ".mkv"), 30_000);

        List<String> top = server.topLevel(savePath);
        assertEquals(List.of(tempDirName + ".mkv"), top,
                "遗留嵌套目录应被识别为本地已存在并归位，实际=" + top);
        assertFalse(server.exists(savePath + "/" + tempDirName),
                "遗留模板目录应被清理（不再残留嵌套）");
        assertFalse(server.exists(savePath + "/" + tempDirName + "/" + RAW_FILE_NAME),
                "遗留 115 任务目录（名=文件名.mkv）应被清理");
    }

    // ============ 场景 5：云下载目录在挂载点下（/115/云下载），自动发现需递归 ============

    @Test
    void cloud_dir_under_mount_point_auto_discovered_recursively() throws Exception {
        String savePath = "/追番/Show/Season 1";
        String tempDirName = "Show S01E03";
        server.placeTaskDirInTarget = false;
        // 根目录 / 下只有 115 目录；云下载在 /115/云下载（真实环境形态）
        server.putFile("/115/云下载/" + RAW_FILE_NAME + "/" + RAW_FILE_NAME, 1000L);

        OpenList openList = openList(savePath);
        assertEquals(Boolean.TRUE, openList.download(ani(), item(HASH5), savePath, torrentFile(HASH5)));

        awaitFinalTopLevel(savePath, List.of(tempDirName + ".mkv"), 30_000);
        // 挂载点下的云下载空壳应被清理
        awaitFinalTopLevel("/115/云下载", List.of(), 30_000);
        assertFalse(server.exists("/115/云下载/" + RAW_FILE_NAME),
                "挂载点下云下载任务空壳应被清理");

        // 正面证据：自动发现递归扫描到了挂载点下的云下载目录
        assertTrue(server.fsListCalls.stream().anyMatch(p -> p.contains("/115/云下载")),
                "自动发现应递归扫描到 /115/云下载，实际 fs/list 调用=" + server.fsListCalls);
    }

    // ============ 场景 6：任务管理器「遗留问题修复」= 嵌套文件归位 + 空壳清理 ============

    @Test
    void legacy_repair_moves_nested_files_to_top_and_cleans_shells() {
        String savePath = "/追番/Show/Season 1";
        // 两层嵌套遗留：模板目录/文件名.mkv/文件
        server.putFile(savePath + "/Show S01E03/" + RAW_FILE_NAME + "/" + RAW_FILE_NAME, 1000L);
        // 简单嵌套遗留：模板目录/文件
        server.putFile(savePath + "/Show S01E05/[LoliHouse] Show - 05 [1080p].mkv", 900L);

        OpenList openList = openList(savePath);
        java.util.List<String> details = new ArrayList<>();
        int repaired = openList.repairNestedUnder(savePath, details);

        assertTrue(repaired >= 2, "应归位 2 个嵌套文件，实际=" + repaired + "，details=" + details);
        assertTrue(server.topLevel(savePath).contains(RAW_FILE_NAME),
                "文件应归位到 savePath 顶层，实际=" + server.topLevel(savePath));
        assertFalse(server.exists(savePath + "/Show S01E03"), "嵌套模板目录应被清理");
        assertFalse(server.exists(savePath + "/Show S01E03/" + RAW_FILE_NAME), "文件名.mkv 空壳应被清理");
        assertFalse(server.exists(savePath + "/Show S01E05"), "另一层嵌套也应被清理");
        // 顶层已有同名的文件不应被再次移动（幂等）
        assertEquals(0, openList.repairNestedUnder(savePath, new ArrayList<>()),
                "再次修复应幂等（无新增归位）");
    }

    // ============ 辅助 ============

    private OpenList openList(String savePath) {
        Config config = new Config()
                .setDownloadToolHost("http://127.0.0.1:" + server.port())
                .setDownloadToolPassword("mock-token")
                .setDownloadPathTemplate("/追番")
                .setProvider("115 Cloud")
                .setDelete(true)
                .setRename(true)
                .setStandbyRss(false)
                .setCoexist(false)
                .setAlistDownloadTimeout(1)
                .setAlistDownloadRetryNumber(3L)
                .setNotificationConfigList(List.of());
        OpenList openList = new OpenList();
        assertEquals(Boolean.TRUE, openList.login(true, config), "mock login 应成功");
        return openList;
    }

    private Ani ani() {
        return new Ani()
                .setId("ani-1")
                .setTitle("Show")
                .setMessage(false);
    }

    private Item item(String hash) {
        return new Item()
                .setTitle("Show")
                .setReName("Show S01E03")
                .setEpisodeRange(List.of())
                .setInfoHash(hash);
    }

    private File torrentFile(String hash) throws IOException {
        // getMagnet: 空文件直接以文件名为 hexHash → magnet:?xt=urn:btih:<文件名>
        // 文件名必须是纯 40 位 hex（createTempFile 会加随机后缀，不可用）
        File dir = Files.createTempDirectory("ani-magnet").toFile();
        dir.deleteOnExit();
        File tmp = new File(dir, hash);
        tmp.deleteOnExit();
        return tmp;
    }

    private void awaitFinalTopLevel(String dir, List<String> expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (server.topLevel(dir).equals(expected)) {
                return;
            }
            Thread.sleep(200);
        }
        fail("等待超时：" + dir + " 顶层应为 " + expected + "，当前=" + server.topLevel(dir)
                + "，全树=" + server.allPaths());
    }

    // ============ AList/115 mock server ============

    static class MockAlistServer {
        private HttpServer httpServer;
        /** path -> entry；entry.dir=true 表示目录 */
        final Map<String, Entry> entries = new ConcurrentHashMap<>();
        /** 离线任务：id -> task */
        final Map<String, JsonObject> tasks = new ConcurrentHashMap<>();

        /** true: 提交后 115 在目标目录建任务目录并放文件；false: 目标目录不放文件（文件预放在云下载） */
        volatile boolean placeTaskDirInTarget = true;
        /** true: 种子含 2 个文件（合集） */
        volatile boolean multiFileSeed = false;
        /** true: add_offline_download 返回 10008（任务已存在，模拟 115 云端去重残留） */
        volatile boolean forceDuplicateAdd = false;
        /** 记录 fs/list 被访问过的路径（验证兜底路径真实触发） */
        final java.util.Set<String> fsListCalls = ConcurrentHashMap.newKeySet();

        private final Gson gson = new Gson();

        void start() throws IOException {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 50);
            httpServer.createContext("/api/", this::handle);
            httpServer.setExecutor(Executors.newCachedThreadPool());
            httpServer.start();
        }

        void stop() {
            if (httpServer != null) {
                httpServer.stop(0);
            }
        }

        int port() {
            return httpServer.getAddress().getPort();
        }

        // ---------- 内存文件树操作 ----------

        static final class Entry {
            final boolean dir;
            long size;

            Entry(boolean dir, long size) {
                this.dir = dir;
                this.size = size;
            }
        }

        private static String norm(String path) {
            if (path == null || path.isEmpty() || "/".equals(path)) {
                return "/";
            }
            String p = path.replace('\\', '/');
            while (p.endsWith("/") && p.length() > 1) {
                p = p.substring(0, p.length() - 1);
            }
            return p;
        }

        boolean exists(String path) {
            return entries.containsKey(norm(path));
        }

        void putFile(String path, long size) {
            String p = norm(path);
            String parent = parentOf(p);
            if (!entries.containsKey(parent)) {
                mkdir(parent);
            }
            entries.put(p, new Entry(false, size));
        }

        private static String parentOf(String path) {
            String p = norm(path);
            int idx = p.lastIndexOf('/');
            return idx <= 0 ? "/" : p.substring(0, idx);
        }

        private void mkdir(String path) {
            String p = norm(path);
            if (entries.containsKey(p)) {
                return;
            }
            if (!"/".equals(p)) {
                mkdir(parentOf(p));
            }
            entries.put(p, new Entry(true, 0));
        }

        List<String> topLevel(String path) {
            String parent = norm(path);
            return entries.keySet().stream()
                    .filter(p -> !"/".equals(p))
                    .filter(p -> parentOf(p).equals(parent))
                    .map(p -> p.substring(parent.equals("/") ? 1 : parent.length() + 1))
                    .filter(s -> !s.contains("/"))
                    .sorted()
                    .toList();
        }

        List<String> allPaths() {
            return new ArrayList<>(entries.keySet());
        }

        private void removeRecursive(String path) {
            String p = norm(path);
            if (entries.remove(p) == null) {
                return;
            }
            String prefix = p + "/";
            entries.keySet().removeIf(k -> k.startsWith(prefix));
        }

        private void moveEntry(String srcDir, String dstDir, String name) {
            String src = norm(srcDir + "/" + name);
            String dst = norm(dstDir + "/" + name);
            Entry e = entries.remove(src);
            if (e == null) {
                throw new IllegalStateException("mock: 移动源不存在 " + src);
            }
            entries.put(dst, e);
            // 目录子树前缀更新
            if (e.dir) {
                String srcPrefix = src + "/";
                String dstPrefix = dst + "/";
                List<String> moved = entries.keySet().stream()
                        .filter(k -> k.startsWith(srcPrefix))
                        .toList();
                for (String k : moved) {
                    Entry v = entries.remove(k);
                    entries.put(dstPrefix + k.substring(srcPrefix.length()), v);
                }
            }
        }

        private void renameEntry(String dir, String srcName, String newName) {
            String src = norm(dir + "/" + srcName);
            String dst = norm(dir + "/" + newName);
            Entry e = entries.remove(src);
            if (e == null) {
                throw new IllegalStateException("mock: 重命名源不存在 " + src);
            }
            entries.put(dst, e);
            if (e.dir) {
                String srcPrefix = src + "/";
                String dstPrefix = dst + "/";
                List<String> moved = entries.keySet().stream()
                        .filter(k -> k.startsWith(srcPrefix))
                        .toList();
                for (String k : moved) {
                    Entry v = entries.remove(k);
                    entries.put(dstPrefix + k.substring(srcPrefix.length()), v);
                }
            }
        }

        // ---------- HTTP 处理 ----------

        private void handle(HttpExchange exchange) throws IOException {
            String action = exchange.getRequestURI().getPath().substring("/api/".length());
            String query = exchange.getRequestURI().getQuery();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject resp = dispatch(action, query, body);
            byte[] bytes = resp.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private JsonObject dispatch(String action, String query, String body) {
            try {
                JsonObject req = body.isEmpty() ? new JsonObject() : gson.fromJson(body, JsonObject.class);
                switch (action) {
                    case "me":
                        return ok(data -> data.addProperty("name", "mock"));
                    case "fs/list": {
                        String path = str(req, "path");
                        fsListCalls.add(path);
                        JsonArray content = new JsonArray();
                        String parent = norm(path);
                        entries.forEach((p, e) -> {
                            if ("/".equals(p)) {
                                return;
                            }
                            int idx = p.lastIndexOf('/');
                            String pParent = idx <= 0 ? "/" : p.substring(0, idx);
                            if (!pParent.equals(parent)) {
                                return;
                            }
                            JsonObject item = new JsonObject();
                            item.addProperty("name", p.substring(idx + 1));
                            item.addProperty("is_dir", e.dir);
                            item.addProperty("size", e.size);
                            content.add(item);
                        });
                        return ok(data -> {
                            JsonObject contentObj = new JsonObject();
                            contentObj.add("content", content);
                            data.add("data", contentObj);
                        });
                    }
                    case "fs/mkdir":
                        mkdir(str(req, "path"));
                        return ok(data -> {
                        });
                    case "fs/move": {
                        String srcDir = str(req, "src_dir");
                        String dstDir = str(req, "dst_dir");
                        for (JsonElement el : req.getAsJsonArray("names")) {
                            moveEntry(srcDir, dstDir, el.getAsString());
                        }
                        return ok(data -> {
                        });
                    }
                    case "fs/batch_rename": {
                        String srcDir = str(req, "src_dir");
                        for (JsonElement el : req.getAsJsonArray("rename_objects")) {
                            JsonObject obj = el.getAsJsonObject();
                            renameEntry(srcDir, obj.get("src_name").getAsString(),
                                    obj.get("new_name").getAsString());
                        }
                        return ok(data -> {
                        });
                    }
                    case "fs/remove": {
                        String dir = str(req, "dir");
                        for (JsonElement el : req.getAsJsonArray("names")) {
                            removeRecursive(dir + "/" + el.getAsString());
                        }
                        return ok(data -> {
                        });
                    }
                    case "fs/add_offline_download": {
                        if (forceDuplicateAdd) {
                            // 115 云端去重残留：任务已存在
                            JsonObject dup = new JsonObject();
                            dup.addProperty("code", 10008);
                            dup.addProperty("message", "任务已存在，请勿输入重复的链接地址");
                            return dup;
                        }
                        String path = str(req, "path");
                        String magnet = req.getAsJsonArray("urls").get(0).getAsString();
                        String tid = "tid-" + UUID.randomUUID().toString().substring(0, 8);
                        if (placeTaskDirInTarget) {
                            // 模拟 115：落点目录下按任务名（=种子文件名含扩展名）建目录，文件在目录内
                            if (multiFileSeed) {
                                mkdir(path + "/" + RAW_FILE_DIR);
                                putFile(path + "/" + RAW_FILE_DIR + "/[LoliHouse] Show - 01 [1080p].mkv", 900L);
                                putFile(path + "/" + RAW_FILE_DIR + "/[LoliHouse] Show - 02 [1080p].mkv", 900L);
                            } else {
                                mkdir(path + "/" + RAW_FILE_NAME);
                                putFile(path + "/" + RAW_FILE_NAME + "/" + RAW_FILE_NAME, 1000L);
                            }
                        }
                        JsonObject task = new JsonObject();
                        task.addProperty("id", tid);
                        task.addProperty("name", "offline-" + magnet.substring(magnet.length() - 40));
                        task.addProperty("state", 2); // Succeeded
                        task.addProperty("progress", 100);
                        tasks.put(tid, task);
                        JsonObject t = new JsonObject();
                        t.addProperty("id", tid);
                        JsonArray arr = new JsonArray();
                        arr.add(t);
                        return ok(data -> {
                            JsonObject d = new JsonObject();
                            d.add("tasks", arr);
                            data.add("data", d);
                        });
                    }
                    case "task/offline_download/info": {
                        String tid = query == null ? "" : query.replace("tid=", "");
                        JsonObject task = tasks.get(tid);
                        if (task == null) {
                            return ok(data -> {
                                data.addProperty("code", 404);
                                data.add("data", null);
                            });
                        }
                        return ok(data -> data.add("data", task));
                    }
                    case "task/offline_download/undone":
                    case "task/offline_download/done":
                        return ok(data -> data.add("data", new JsonArray()));
                    case "task/offline_download/delete_some":
                    case "task/offline_download/cancel":
                    case "task/offline_download/retry":
                        return ok(data -> {
                        });
                    default:
                        return ok(data -> {
                        });
                }
            } catch (Exception e) {
                JsonObject err = new JsonObject();
                err.addProperty("code", 500);
                err.addProperty("message", String.valueOf(e));
                return err;
            }
        }

        private static String str(JsonObject obj, String key) {
            return obj.has(key) ? obj.get(key).getAsString() : "";
        }

        private JsonObject ok(java.util.function.Consumer<JsonObject> fill) {
            JsonObject resp = new JsonObject();
            resp.addProperty("code", 200);
            fill.accept(resp);
            return resp;
        }
    }

    static final String RAW_FILE_DIR = "[LoliHouse] Show [1080p]";
}
