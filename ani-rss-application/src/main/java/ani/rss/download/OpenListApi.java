package ani.rss.download;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.entity.OpenListFileInfo;
import ani.rss.entity.OpenListTaskInfo;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OpenList/Alist 网盘 API 客户端：认证、限流、文件系统与离线任务接口。
 * <p>
 * 从 OpenList 上帝类中拆出的纯 API 层，不含业务编排（下载等待/重命名/残留治理）。
 * 无状态：host/token 通过 {@link #setConfig(Config)} 注入（登录时同步）。
 */
@Slf4j
public class OpenListApi {

    private volatile Config config;

    /**
     * 登录成功后注入配置（host/token/provider）
     */
    public void setConfig(Config config) {
        this.config = config;
    }

    // API 最小间隔限流（替代每次固定 sleep 2s）
    private static final long API_MIN_INTERVAL_MS = 300L;
    private static final Object API_RATE_LOCK = new Object();
    private static volatile long lastApiCallAt = 0L;

    // findFiles 短缓存，轮询期间减少递归 list
    private static final long FIND_FILES_TTL_MS = 3000L;
    private static final Map<String, CachedFileList> findFilesCache = new ConcurrentHashMap<>();

    // listFileNames 长缓存: "本地已下载"判断用, 文件列表变化不频繁
    private static final long LIST_NAMES_TTL_MS = java.util.concurrent.TimeUnit.SECONDS.toMillis(60);
    private static final Map<String, List<String>> listNamesCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> listNamesExpire = new ConcurrentHashMap<>();

    private static final int IDEMPOTENT_API_MAX_ATTEMPTS = 3;
    private static final long[] IDEMPOTENT_API_RETRY_DELAYS_MS = {500L, 1500L};

    private static final class CachedFileList {
        final long expireAt;
        final List<OpenListFileInfo> files;

        CachedFileList(List<OpenListFileInfo> files, long ttlMs) {
            this.files = files;
            this.expireAt = System.currentTimeMillis() + ttlMs;
        }
    }

    /**
     * 列出网盘目录下文件路径(递归, 60s 缓存), 供"本地已下载"判断使用。
     * 下载目录是网盘虚拟路径(本地文件系统不可见), 需通过 API 检查文件真实存在。
     */
    public List<String> listFileNames(String dirPath) {
        Long expire = listNamesExpire.get(dirPath);
        if (expire != null && expire > System.currentTimeMillis()) {
            List<String> cached = listNamesCache.get(dirPath);
            if (cached != null) {
                return cached;
            }
        }
        List<String> names;
        try {
            names = findFiles(dirPath).stream()
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                    .map(f -> {
                        String dir = f.getPath();
                        String name = f.getName();
                        return StrUtil.isBlank(dir) ? name : dir + "/" + name;
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // API 故障: 不缓存, 下次重试; 记日志避免静默误判"目录无文件"
            log.warn("列出网盘目录失败 {}: {}", dirPath, ExceptionUtils.getMessage(e));
            return List.of();
        }
        listNamesCache.put(dirPath, names);
        listNamesExpire.put(dirPath, System.currentTimeMillis() + LIST_NAMES_TTL_MS);
        return names;
    }

    /**
     * 创建文件夹
     *
     * @param path 路径
     */
    public void mkdir(String path) {
        invalidateFindFilesCache();
        retryIdempotent("fs/mkdir " + path, () -> {
            postApi("fs/mkdir")
                    .body(GsonStatic.toJson(Map.of(
                            "path", path
                    )))
                    .then(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        int code = jsonObject.get("code").getAsInt();
                        String message = jsonObject.has("message") ? jsonObject.get("message").getAsString() : "";
                        if (code == 200) {
                            log.info("创建文件夹: {}", path);
                            return;
                        }

                        if (!message.startsWith("failed to check if dir exists")) {
                            throw new IllegalStateException("fs/mkdir 失败 code=" + code + " " + message);
                        }

                        Path pathObj = Path.of(path);
                        if (pathObj.getNameCount() <= 1) {
                            throw new IllegalStateException("fs/mkdir 失败 code=" + code + " " + message);
                        }

                        String parentPath = pathObj
                                .getParent()
                                .toString()
                                .replace('\\', '/');
                        mkdir(parentPath);
                        mkdir(path);
                    });
            return null;
        });
    }

    /**
     * 移动文件
     *
     * @param srcDir 原目录
     * @param dstDir 目标目录
     * @param names  文件名
     */
    public void fsMove(String srcDir, String dstDir, List<String> names) {
        invalidateFindFilesCache();
        postApi("fs/move")
                .body(GsonStatic.toJson(Map.of(
                        "src_dir", srcDir,
                        "dst_dir", dstDir,
                        "names", names
                ))).then(res -> {
                    log.info(res.body());
                    assertOpenListOk(res, "fs/move " + srcDir + " -> " + dstDir);
                });
    }

    /**
     * 删除文件
     *
     * @param dir   目录
     * @param names 文件名
     */
    public void fsRemove(String dir, List<String> names) {
        invalidateFindFilesCache();
        postApi("fs/remove")
                .body(GsonStatic.toJson(Map.of(
                        "dir", dir,
                        "names", names
                ))).then(res -> assertOpenListOk(res, "fs/remove " + dir));
    }

    /**
     * 批量重命名
     *
     * @param mapList 重命名列表
     * @param srcDir  目录
     */
    public void fsBatchRename(List<Map<String, String>> mapList, String srcDir) {
        invalidateFindFilesCache();
        postApi("fs/batch_rename")
                .body(GsonStatic.toJson(Map.of(
                        "src_dir", srcDir,
                        "rename_objects", mapList
                ))).then(res -> {
                    log.info(res.body());
                    assertOpenListOk(res, "fs/batch_rename " + srcDir);
                });
    }

    /**
     * 添加离线下载
     *
     * @param magnet 磁力链接
     * @param path   离线位置
     * @return tid
     */
    public String fsAddOfflineDownload(String magnet, String path) {
        invalidateFindFilesCache();
        return postApi("fs/add_offline_download")
                .body(GsonStatic.toJson(Map.of(
                        "path", path,
                        "urls", List.of(magnet),
                        "tool", config.getProvider(),
                        "delete_policy", "delete_on_upload_succeed"
                )))
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    log.debug(jsonObject.toString());
                    int code = jsonObject.get("code").getAsInt();
                    // 10008: 任务已存在，视为成功（幂等）
                    if (code == 10008) {
                        log.info("离线任务已存在，跳过重复提交 {}", path);
                        return null;
                    }
                    Assert.isTrue(code == 200);
                    return jsonObject.getAsJsonObject("data")
                            .getAsJsonArray("tasks")
                            .get(0).getAsJsonObject()
                            .get("id").getAsString();
                });
    }

    /**
     * 文件列表
     *
     * @param path 目录
     * @return 文件列表
     */
    public List<OpenListFileInfo> fsList(String path, Boolean refresh) {
        try {
            return retryIdempotent("fs/list " + path, () -> postApi("fs/list")
                    .body(GsonStatic.toJson(Map.of(
                            "path", path,
                            "page", 1,
                            "per_page", 0,
                            "refresh", refresh
                    )))
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        int code = jsonObject.get("code").getAsInt();
                        if (code != 200) {
                            return List.of();
                        }
                        JsonElement data = jsonObject.get("data");
                        if (Objects.isNull(data) || data.isJsonNull()) {
                            return List.of();
                        }
                        JsonElement content = data.getAsJsonObject().get("content");
                        if (Objects.isNull(content) || content.isJsonNull()) {
                            return List.of();
                        }
                        List<OpenListFileInfo> infos = GsonStatic.fromJsonList(content.getAsJsonArray(), OpenListFileInfo.class);
                        for (OpenListFileInfo info : infos) {
                            info.setPath(path);
                        }
                        return ListUtil.sort(new ArrayList<>(infos), Comparator.comparing(fileInfo -> {
                            Long size = fileInfo.getSize();
                            return Long.MAX_VALUE - ObjectUtil.defaultIfNull(size, 0L);
                        }));
                    }));
        } catch (Exception e) {
            log.warn("OpenList fs/list 调用失败 path={}: {}", path, ExceptionUtils.getMessage(e));
            return List.of();
        }
    }

    /**
     * 递归列出目录下所有文件（3s 短缓存）
     *
     * @param path 目录
     * @return 文件列表
     */
    public synchronized List<OpenListFileInfo> findFiles(String path) {
        CachedFileList cached = findFilesCache.get(path);
        if (cached != null && cached.expireAt > System.currentTimeMillis()) {
            return cached.files;
        }

        List<OpenListFileInfo> openListFileInfos = fsList(path, true);
        List<OpenListFileInfo> list = openListFileInfos.stream()
                .flatMap(openListFileInfo -> {
                    if (openListFileInfo.getIsDir()) {
                        return findFiles(path + "/" + openListFileInfo.getName()).stream();
                    }
                    return Stream.of(openListFileInfo);
                }).toList();

        List<OpenListFileInfo> sorted = ListUtil.sort(new ArrayList<>(list), Comparator.comparing(fileInfo -> {
            Long size = fileInfo.getSize();
            return Long.MAX_VALUE - ObjectUtil.defaultIfNull(size, 0L);
        }));
        findFilesCache.put(path, new CachedFileList(sorted, FIND_FILES_TTL_MS));
        return sorted;
    }

    /**
     * 查看任务
     *
     * @param tid 任务id
     * @return 任务信息
     */
    public Optional<OpenListTaskInfo> taskInfo(String tid) {
        try {
            String safeTid = requireSafeTid(tid);
            OpenListTaskInfo taskInfo = retryIdempotent("task/info " + safeTid,
                    () -> postApi("task/offline_download/info?tid=" + safeTid)
                            .thenFunction(res -> {
                                HttpReq.assertStatus(res);
                                JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                                int code = jsonObject.get("code").getAsInt();
                                if (code != 200 || !jsonObject.has("data") || jsonObject.get("data").isJsonNull()) {
                                    throw new IllegalStateException("task/info 失败 code=" + code);
                                }
                                return GsonStatic.fromJson(jsonObject.get("data").getAsJsonObject(), OpenListTaskInfo.class);
                            }));
            return Optional.ofNullable(taskInfo);
        } catch (Exception e) {
            log.warn("OpenList task/info 调用失败 tid={}: {}", tid, ExceptionUtils.getMessage(e));
            return Optional.empty();
        }
    }

    /**
     * 未完成的离线任务
     *
     * @return 任务列表
     */
    public List<OpenListTaskInfo> taskUnDoneList() {
        return getApi("task/offline_download/undone")
                .thenFunction(res -> {
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonArray jsonArray = jsonObject.get("data").getAsJsonArray();
                    return GsonStatic.fromJsonList(jsonArray, OpenListTaskInfo.class);
                });
    }

    /**
     * 已完成的离线任务
     *
     * @return 任务列表
     */
    public List<OpenListTaskInfo> taskDoneList() {
        return getApi("task/offline_download/done")
                .thenFunction(res -> {
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonArray jsonArray = jsonObject.get("data").getAsJsonArray();
                    return GsonStatic.fromJsonList(jsonArray, OpenListTaskInfo.class);
                });
    }

    /**
     * 重试任务
     *
     * @param tid 任务id
     */
    public void taskRetry(String tid) {
        if (StrUtil.isBlank(tid)) {
            return;
        }
        if (!tid.matches("[A-Za-z0-9_\\-]+")) {
            // 非法 tid（服务端异常数据）：静默跳过，避免把重试升级为整体失败；剥离换行防日志伪造
            log.warn("taskRetry 跳过非法任务ID: {}", StrUtil.maxLength(tid.replaceAll("[\\r\\n]", " "), 64));
            return;
        }
        postApi("task/offline_download/retry")
                .form("tid", tid)
                .thenFunction(HttpResponse::isOk);
    }

    /**
     * 取消任务（运行中任务应先 cancel 再 delete）
     *
     * @param tid 任务id
     */
    public void taskCancel(String tid) {
        if (StrUtil.isBlank(tid)) {
            return;
        }
        String safeTid = requireSafeTid(tid);
        // 现网 OpenList/AList：query tid 有效；form 常返回 HTTP 200 + body code=404 且任务仍在
        if (tryTaskAction("task/offline_download/cancel?tid=" + safeTid, null, "cancel/query", safeTid)) {
            return;
        }
        if (tryTaskAction("task/offline_download/cancel", safeTid, "cancel/form", safeTid)) {
            return;
        }
        log.debug("cancel 任务失败 {}", safeTid);
    }

    /**
     * 删除任务
     *
     * @param tid 任务id
     */
    public void taskDelete(String tid) {
        if (StrUtil.isBlank(tid)) {
            return;
        }
        String safeTid = requireSafeTid(tid);
        // 现网有效路径：POST delete?tid=
        // form delete_some 常返回 code=200 data={} 但 running 任务仍残留，不能优先也不能只信 HTTP 200
        if (tryTaskAction("task/offline_download/delete?tid=" + safeTid, null, "delete/query", safeTid)) {
            return;
        }
        if (tryTaskAction("task/offline_download/delete", safeTid, "delete/form", safeTid)) {
            return;
        }
        if (tryTaskAction("task/offline_download/delete_some", safeTid, "delete_some/form", safeTid)) {
            return;
        }
        // 兼容旧版：JSON 数组 body（部分服务器会 400 invalid request format）
        try {
            HttpResponse res = postApi("task/offline_download/delete_some")
                    .body(GsonStatic.toJson(List.of(safeTid)))
                    .execute();
            if (isOpenListCodeOk(res)) {
                return;
            }
            log.debug("delete_some/json 失败 {}: {}", safeTid, res.body());
        } catch (Exception e) {
            log.debug("delete_some/json 异常 {}: {}", safeTid, e.getMessage());
        }
        log.warn("删除离线任务失败 {}", safeTid);
    }

    /**
     * tid 会拼入 URL query，白名单校验防恶意服务端响应注入额外参数
     */
    private static String requireSafeTid(String tid) {
        if (StrUtil.isBlank(tid) || !tid.matches("[A-Za-z0-9_\\-]+")) {
            throw new IllegalArgumentException("非法任务ID: " + tid);
        }
        return tid;
    }

    /**
     * 执行 cancel/delete 类动作，并校验 body.code==200（不能只看 HTTP 200）。
     *
     * @param action  API path（可含 query）
     * @param formTid 非空时用 form tid；空则只发 path
     */
    private boolean tryTaskAction(String action, String formTid, String label, String tid) {
        try {
            HttpRequest req = postApi(action);
            if (StrUtil.isNotBlank(formTid)) {
                req.form("tid", formTid);
            }
            HttpResponse res = req.execute();
            if (isOpenListCodeOk(res)) {
                return true;
            }
            log.debug("{} 未生效 tid={} body={}", label, tid, res.body());
        } catch (Exception e) {
            log.debug("{} 异常 tid={}: {}", label, tid, e.getMessage());
        }
        return false;
    }

    /**
     * 校验 OpenList API 返回 code==200
     */
    private void assertOpenListOk(HttpResponse res, String action) {
        HttpReq.assertStatus(res);
        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
        int code = jsonObject.get("code").getAsInt();
        if (code != 200) {
            String message = jsonObject.has("message") ? jsonObject.get("message").getAsString() : "";
            throw new IllegalStateException(action + " 失败 code=" + code + " " + message);
        }
    }

    static boolean isOpenListCodeOk(HttpResponse res) {
        if (res == null) {
            return false;
        }
        return isOpenListBusinessOk(res.isOk(), res.body());
    }

    static boolean isOpenListBusinessOk(boolean httpOk, String body) {
        if (!httpOk) {
            return false;
        }
        try {
            if (StrUtil.isBlank(body)) {
                // 少数实现只回 HTTP 200
                return true;
            }
            JsonObject jsonObject = GsonStatic.fromJson(body, JsonObject.class);
            if (jsonObject == null || !jsonObject.has("code")) {
                return true;
            }
            return jsonObject.get("code").getAsInt() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    <T> T retryIdempotent(String action, Supplier<T> supplier) {
        return retryIdempotent(action, supplier, IDEMPOTENT_API_RETRY_DELAYS_MS);
    }

    static <T> T retryIdempotent(String action, Supplier<T> supplier, long[] retryDelaysMs) {
        int attempts = Math.max(1, Math.min(IDEMPOTENT_API_MAX_ATTEMPTS,
                (retryDelaysMs == null ? 0 : retryDelaysMs.length) + 1));
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return supplier.get();
            } catch (RuntimeException e) {
                last = e;
                if (!isTransientOpenListFailure(e) || attempt >= attempts) {
                    throw e;
                }
                long delay = Math.max(0L, retryDelaysMs[attempt - 1]);
                log.warn("OpenList 临时故障，准备重试 action={} attempt={}/{} delayMs={} error={}",
                        action, attempt, attempts, delay, ExceptionUtils.getMessage(e));
                if (delay > 0) {
                    ThreadUtil.sleep(delay);
                }
            }
        }
        throw last == null ? new IllegalStateException(action + " failed") : last;
    }

    static boolean isTransientOpenListFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String message = StrUtil.blankToDefault(current.getMessage(), "").toLowerCase(Locale.ROOT);
            if (className.contains("sockettimeout")
                    || className.contains("connectexception")
                    || className.contains("noroutetohost")
                    || message.contains("read timed out")
                    || message.contains("connect timed out")
                    || message.contains("connection reset")
                    || message.contains("connection refused")
                    || message.contains("status: 502")
                    || message.contains("status: 503")
                    || message.contains("status: 504")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * API 最小间隔限流，避免固定 sleep 2s 拖慢轮询
     */
    private static void throttleApi() {
        synchronized (API_RATE_LOCK) {
            long now = System.currentTimeMillis();
            long wait = API_MIN_INTERVAL_MS - (now - lastApiCallAt);
            if (wait > 0) {
                ThreadUtil.sleep(wait);
            }
            lastApiCallAt = System.currentTimeMillis();
        }
    }

    /**
     * 目录变更后清理 findFiles/listFileNames 缓存
     */
    void invalidateFindFilesCache() {
        findFilesCache.clear();
        listNamesCache.clear();
        listNamesExpire.clear();
    }

    /**
     * get api
     *
     * @param action
     * @return
     */
    public synchronized HttpRequest getApi(String action) {
        throttleApi();
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        return HttpReq.get(host + "/api/" + action)
                .header(Header.AUTHORIZATION, password);
    }

    /**
     * post api
     *
     * @param action
     * @return
     */
    public synchronized HttpRequest postApi(String action) {
        throttleApi();
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        return HttpReq.post(host + "/api/" + action)
                .header(Header.AUTHORIZATION, password);
    }

}
