package ani.rss.testsupport;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 内存版 AList/115 服务端（OpenList 离线下载真实 API 形状）。
 * <p>
 * 为什么需要它：OpenList 那条链路（login → fs/mkdir → add_offline_download → 轮询任务 →
 * fs/batch_rename → fs/move → fs/remove 清空壳）只有在<b>真的发出 HTTP 请求</b>时才会暴露问题。
 * 直接 mock {@code OpenListApi} 会把「请求参数拼装、重试、归位对账」这些最容易出错的环节
 * 一起 mock 掉，测出来的是测试自己的假设。
 * <p>
 * 由 {@code OpenListWorkflowSimulationTest} / {@code OpenListPlanWorkflowTest} 升级抽取而来：
 * 两处各自维护一份 mock 会让「115 的真实行为」出现两个版本，回归结论互相矛盾。
 * <p>
 * 线程安全：entries/tasks 为并发 Map，测试线程与 HTTP 线程可同时访问。
 */
public class FakeOpenListServer {

    private HttpServer httpServer;
    /** path -> entry；entry.dir=true 表示目录 */
    final Map<String, Entry> entries = new ConcurrentHashMap<>();
    /** 离线任务：id -> task */
    final Map<String, JsonObject> tasks = new ConcurrentHashMap<>();

    /** true: 提交后 115 在目标目录建任务目录并放文件；false: 目标目录不放文件（文件预放在云下载） */
    public volatile boolean placeTaskDirInTarget = true;
    /** true: 种子含 2 个文件（合集） */
    public volatile boolean multiFileSeed = false;
    /** true: add_offline_download 返回 10008（任务已存在，模拟 115 云端去重残留） */
    public volatile boolean forceDuplicateAdd = false;
    /** 新增离线任务的状态码：默认 2=Succeeded；改成 1=Running 可模拟"任务一直跑着但文件已齐" */
    public volatile int taskState = 2;
    public volatile int taskProgress = 100;
    /**
     * true: 重命名只改主名、保留原扩展名 —— 115（经 AList）的真实行为
     * （实测：x.bin 请求改成 y.mkv，结果得到 y.bin）
     */
    public volatile boolean preserveExtOnRename = false;
    /** 记录 fs/list 被访问过的路径（验证兜底路径真实触发） */
    public final Set<String> fsListCalls = ConcurrentHashMap.newKeySet();

    /**
     * 单文件种子的原始文件名（= 115 建的任务目录名 + 目录内文件名）。
     * 不同用例的种子名不同，因此做成可配置字段而不是常量。
     */
    public volatile String seedFileName = "[LoliHouse] Show - 03 [1080p].mkv";
    /** 多文件种子的原始目录名 */
    public volatile String seedDirName = "[LoliHouse] Show [1080p]";

    /** 收到的离线提交（按提交顺序），断言"提交了什么"时用，也是 e2e 报告的一部分 */
    public final List<OfflineSubmission> offlineSubmissions =
            java.util.Collections.synchronizedList(new ArrayList<>());

    /** 一次 add_offline_download 的可核对记录 */
    public record OfflineSubmission(String path, String magnet, String tid, boolean duplicateRejected) {
    }

    private final Gson gson = new Gson();

    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 50);
        httpServer.createContext("/api/", this::handle);
        httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.start();
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    public String host() {
        return "http://127.0.0.1:" + port();
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

    static String norm(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "/";
        }
        String p = path.replace('\\', '/');
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    public boolean exists(String path) {
        return entries.containsKey(norm(path));
    }

    public boolean isDir(String path) {
        Entry e = entries.get(norm(path));
        return e != null && e.dir;
    }

    public void mkdir(String path) {
        String p = norm(path);
        if (entries.containsKey(p)) {
            return;
        }
        if (!"/".equals(p)) {
            mkdir(parentOf(p));
        }
        entries.put(p, new Entry(true, 0));
    }

    public void putFile(String path, long size) {
        String p = norm(path);
        String parent = parentOf(p);
        if (!entries.containsKey(parent)) {
            mkdir(parent);
        }
        entries.put(p, new Entry(false, size));
    }

    static String parentOf(String path) {
        String p = norm(path);
        int idx = p.lastIndexOf('/');
        return idx <= 0 ? "/" : p.substring(0, idx);
    }

    /** 某目录的直接子项名（排序后），断言"归位后的最终形态"用 */
    public List<String> topLevel(String path) {
        String parent = norm(path);
        return entries.keySet().stream()
                .filter(p -> !"/".equals(p))
                .filter(p -> parentOf(p).equals(parent))
                .map(p -> p.substring(parent.equals("/") ? 1 : parent.length() + 1))
                .filter(s -> !s.contains("/"))
                .sorted()
                .toList();
    }

    /** 全树路径（排序后），落进报告便于人工核对 */
    public List<String> allPaths() {
        return entries.keySet().stream().sorted().toList();
    }

    /** 目录树文本（缩进形式），给 e2e 报告用 */
    public String tree() {
        StringBuilder sb = new StringBuilder();
        for (String p : allPaths()) {
            if ("/".equals(p)) {
                sb.append("/").append('\n');
                continue;
            }
            int depth = (int) p.chars().filter(c -> c == '/').count();
            Entry e = entries.get(p);
            sb.append("  ".repeat(depth))
                    .append(p.substring(p.lastIndexOf('/') + 1))
                    .append(e != null && e.dir ? "/" : " (" + (e == null ? 0 : e.size) + "B)")
                    .append('\n');
        }
        return sb.toString();
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
        relink(norm(srcDir + "/" + name), norm(dstDir + "/" + name), "移动");
    }

    private void renameEntry(String dir, String srcName, String newName) {
        relink(norm(dir + "/" + srcName), norm(dir + "/" + newName), "重命名");
    }

    private void relink(String src, String dst, String action) {
        Entry e = entries.remove(src);
        if (e == null) {
            throw new IllegalStateException("mock: " + action + "源不存在 " + src);
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

    /** 便于日志/断言：路径规范化 */
    public static String lower(String path) {
        return norm(path).toLowerCase(Locale.ROOT);
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
                        String srcName = obj.get("src_name").getAsString();
                        String newName = obj.get("new_name").getAsString();
                        if (preserveExtOnRename) {
                            // 115：只改主名，扩展名跟随原文件
                            String srcExt = srcName.contains(".")
                                    ? srcName.substring(srcName.lastIndexOf('.') + 1) : "";
                            int dot = newName.lastIndexOf('.');
                            String newMain = dot < 0 ? newName : newName.substring(0, dot);
                            newName = srcExt.isEmpty() ? newMain : newMain + "." + srcExt;
                        }
                        renameEntry(srcDir, srcName, newName);
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
                case "fs/put":
                case "fs/get":
                    return ok(data -> {
                    });
                case "fs/add_offline_download": {
                    if (forceDuplicateAdd) {
                        // 115 云端去重残留：任务已存在
                        offlineSubmissions.add(new OfflineSubmission(str(req, "path"), "", "", true));
                        JsonObject dup = new JsonObject();
                        dup.addProperty("code", 10008);
                        dup.addProperty("message", "任务已存在，请勿输入重复的链接地址");
                        return dup;
                    }
                    String path = str(req, "path");
                    String magnet = req.getAsJsonArray("urls").get(0).getAsString();
                    String tid = "tid-" + UUID.randomUUID().toString().substring(0, 8);
                    offlineSubmissions.add(new OfflineSubmission(path, magnet, tid, false));
                    if (placeTaskDirInTarget) {
                        // 模拟 115：落点目录下按任务名（=种子文件名含扩展名）建目录，文件在目录内
                        if (multiFileSeed) {
                            mkdir(path + "/" + seedDirName);
                            putFile(path + "/" + seedDirName + "/[LoliHouse] Show - 01 [1080p].mkv", 900L);
                            putFile(path + "/" + seedDirName + "/[LoliHouse] Show - 02 [1080p].mkv", 900L);
                        } else {
                            mkdir(path + "/" + seedFileName);
                            putFile(path + "/" + seedFileName + "/" + seedFileName, 1000L);
                        }
                    }
                    JsonObject task = new JsonObject();
                    task.addProperty("id", tid);
                    task.addProperty("name", "offline-" + magnet.substring(magnet.length() - 40));
                    task.addProperty("state", taskState);
                    task.addProperty("progress", taskProgress);
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
