package ani.rss.download;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.entity.OpenListFileInfo;
import ani.rss.entity.OpenListTaskInfo;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.basic.HttpRequestPlus;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
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

    // ---- 网盘 API 限流：令牌桶（替代原先的固定最小间隔）----
    private static final Object API_RATE_LOCK = new Object();
    private static final double DEFAULT_API_PER_SECOND = 3.0;
    private static final double DEFAULT_API_BURST = 1.0;
    private static final double MAX_API_PER_SECOND = 20.0;
    private static final double MAX_API_BURST = 5.0;
    /**
     * 单次限流最长等待。配置过小（如 1 次/秒）时等待会很长，
     * 但绝不能变成"无上限挂起"——那会让整个轮次静默卡死。
     */
    private static final long MAX_THROTTLE_WAIT_MS = 5000L;
    /** 上次补充令牌的时间（nanoTime：不受系统时钟回拨影响） */
    private static long lastTokenAtNanos = 0L;
    /** 当前可用令牌（浮点累积，避免小速率下取整归零） */
    private static double availableTokens = 0.0;
    /** 限流累计等待毫秒数（可观测：到底被限流拖了多少时间） */
    private static final java.util.concurrent.atomic.AtomicLong throttleWaitMs =
            new java.util.concurrent.atomic.AtomicLong(0L);

    // ---- 熔断：连续失败到阈值后进入冷却，冷却期内不再发起网盘请求 ----
    private static final java.util.concurrent.atomic.AtomicInteger consecutiveListingFailures =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicInteger cooldownLevel =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static volatile long cooldownUntilMs = 0L;
    private static final long MAX_COOLDOWN_MS = java.util.concurrent.TimeUnit.MINUTES.toMillis(10);
    /** 熔断触发次数（可观测） */
    private static final java.util.concurrent.atomic.AtomicLong cooldownTriggered =
            new java.util.concurrent.atomic.AtomicLong(0L);

    // findFiles 短缓存，轮询期间减少递归 list
    private static final long FIND_FILES_TTL_MS = java.util.concurrent.TimeUnit.SECONDS.toMillis(30);
    private static final Map<String, CachedFileList> findFilesCache = new ConcurrentHashMap<>();

    // listFileNames 长缓存: "本地已下载"判断用, 文件列表变化不频繁
    private static final long LIST_NAMES_TTL_MS = java.util.concurrent.TimeUnit.SECONDS.toMillis(300);
    private static final Map<String, List<String>> listNamesCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> listNamesExpire = new ConcurrentHashMap<>();

    // ---- 可观测计数（F7-7）----
    // 目的：限流是否生效、缓存是否真的省下了请求、熔断何时被触发，必须能被看见。
    // 否则"网盘慢"只能靠猜，调参也无从验证。
    /** 累计 API 调用次数（不清零，看长期趋势） */
    private static final java.util.concurrent.atomic.AtomicLong apiCallCount =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /** 本轮 API 调用次数（每轮 RSS 扫描开始时复位） */
    private static final java.util.concurrent.atomic.AtomicLong apiCallCountRound =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /** 目录列举缓存命中次数 */
    private static final java.util.concurrent.atomic.AtomicLong listingCacheHit =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /** 目录列举缓存未命中次数（= 真的发了请求） */
    private static final java.util.concurrent.atomic.AtomicLong listingCacheMiss =
            new java.util.concurrent.atomic.AtomicLong(0L);

    // ---- F7-2 请求合并（coalescing）----
    // 缓存只能挡住"已经算过"的请求，挡不住"正在算"的请求：两个线程同时问同一个目录，
    // 缓存都未命中，于是同一份列举被做两遍。请求合并让后来者等第一个人的结果。
    /** 同一 path 正在构建中的列举结果 */
    private static final Map<String, java.util.concurrent.CompletableFuture<List<String>>> listNamesInFlight =
            new ConcurrentHashMap<>();
    /** 等待上限：等待方绝不无限期挂起，超时按"查询失败"处理（→ 存疑） */
    private static final long MAX_COALESCE_WAIT_MS = 60_000L;
    /** 被合并掉（省下）的列举次数 */
    private static final java.util.concurrent.atomic.AtomicLong listingCoalesced =
            new java.util.concurrent.atomic.AtomicLong(0L);

    // ---- F7-5 每轮 API 预算 ----
    // 预算 = 0 表示不限制（非轮次场景，如用户手动预览）。由 RssTask 在轮次开始时设置、结束时清除。
    /** 本轮预算（0 = 不限制） */
    private static final java.util.concurrent.atomic.AtomicInteger roundBudget =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** 因预算耗尽而放弃校验的次数（可观测） */
    private static final java.util.concurrent.atomic.AtomicLong budgetExhausted =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /** 本轮预算上限的硬顶：无论订阅多少，单轮最多 200 次，防止"订阅 500 个就允许打 500 次" */
    public static final int MAX_API_BUDGET_PER_ROUND = 200;

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
     * <p>
     * 查询失败时返回空列表(保持旧行为)。调用方若需要区分"目录确实为空"与"查询失败"
     * (例如预览要显示"存疑"), 请改用 {@link #listFileNamesStrict(String)}。
     */
    public List<String> listFileNames(String dirPath) {
        try {
            return listFileNamesStrict(dirPath);
        } catch (Exception e) {
            // API 故障: 不缓存, 下次重试; 记日志避免静默误判"目录无文件"
            log.warn("列出网盘目录失败 {}: {}", dirPath, ExceptionUtils.getMessage(e));
            return List.of();
        }
    }

    /**
     * 严格版列举：查询失败时抛出而非返回空列表, 且失败结果不写缓存。
     * 用于必须区分"目录确实为空"与"查询失败"的场景, 避免网盘抖动被误判成"文件都不存在"。
     * <p>
     * F7-2 请求合并：同一 path 的并发列举只发一次请求，其余调用等待同一份结果。
     */
    public List<String> listFileNamesStrict(String dirPath) {
        Long expire = listNamesExpire.get(dirPath);
        if (expire != null && expire > System.currentTimeMillis()) {
            List<String> cached = listNamesCache.get(dirPath);
            if (cached != null) {
                listingCacheHit.incrementAndGet();
                return cached;
            }
        }

        // 缓存未命中：先看有没有人正在算同一个目录
        java.util.concurrent.CompletableFuture<List<String>> mine = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<List<String>> existing = listNamesInFlight.putIfAbsent(dirPath, mine);
        if (existing != null) {
            listingCoalesced.incrementAndGet();
            return awaitCoalesced(dirPath, existing);
        }
        try {
            List<String> names = buildFileNames(dirPath);
            mine.complete(names);
            return names;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            listNamesInFlight.remove(dirPath, mine);
        }
    }

    /**
     * 等待同一 path 的列举结果。
     * <p>
     * 超时/被中断一律抛异常而不是返回空列表——空列表会被下游当成"目录里没有文件"，
     * 从而把"查不到"说成"不存在"，正是本需求要消灭的误判。
     */
    private static List<String> awaitCoalesced(String dirPath,
                                               java.util.concurrent.CompletableFuture<List<String>> future) {
        try {
            return future.get(MAX_COALESCE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("等待同一目录列举结果失败 path=" + dirPath, cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("等待同一目录列举结果超时（" + MAX_COALESCE_WAIT_MS
                    + "ms） path=" + dirPath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待同一目录列举结果被中断 path=" + dirPath, e);
        }
    }

    /**
     * 真正执行列举（含 300s 长缓存写入）。只在缓存未命中且无同 path 在途请求时进入。
     */
    private List<String> buildFileNames(String dirPath) {
        List<OpenListFileInfo> files;
        try {
            files = findFilesStrict(dirPath);
        } catch (OpenListDirNotFoundException e) {
            // 目录不存在 = 确认没有文件。这是正常结果而非查询失败，
            // 否则新订阅（下载目录还没被创建）一进预览就会被标成"存疑"。
            // 不写缓存：目录随时可能因首个下载落地而被创建。
            log.debug("网盘目录不存在，视为空目录 {}", dirPath);
            return List.of();
        }
        List<String> names = files.stream()
                .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                .map(f -> {
                    String dir = f.getPath();
                    String name = f.getName();
                    return StrUtil.isBlank(dir) ? name : dir + "/" + name;
                })
                .collect(Collectors.toList());
        listNamesCache.put(dirPath, names);
        listNamesExpire.put(dirPath, System.currentTimeMillis() + LIST_NAMES_TTL_MS);
        return names;
    }

    /**
     * 列出网盘目录下文件信息(递归, 3s 缓存), 供媒体库等需要大小/修改时间的场景使用。
     * 查询失败时抛出, 调用方可据此保守处理(不把"查不到"当成"没有")。
     */
    public List<OpenListFileInfo> listFilesStrict(String dirPath) {
        return findFilesStrict(dirPath);
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
        retryIdempotent("fs/move " + srcDir + " -> " + dstDir, () -> {
            postApi("fs/move")
                    .body(GsonStatic.toJson(Map.of(
                            "src_dir", srcDir,
                            "dst_dir", dstDir,
                            "names", names
                    ))).then(res -> {
                        log.info(res.body());
                        assertOpenListOk(res, "fs/move " + srcDir + " -> " + dstDir);
                    });
            return null;
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
        retryIdempotent("fs/remove " + dir, () -> {
            postApi("fs/remove")
                    .body(GsonStatic.toJson(Map.of(
                            "dir", dir,
                            "names", names
                    ))).then(res -> assertOpenListOk(res, "fs/remove " + dir));
            return null;
        });
    }

    /**
     * 上传文件到网盘（fs/put），用于把字幕写入与视频相同的云端目录。
     * 复用 OpenListUploadNotification 的 PUT 模式，但使用下载器自身的 host/token。
     *
     * @param dir      目录
     * @param fileName 文件名（含扩展名）
     * @param content  文件内容
     */
    public void fsPut(String dir, String fileName, byte[] content) {
        invalidateFindFilesCache();
        retryIdempotent("fs/put " + dir + "/" + fileName, () -> {
            String url = config.getDownloadToolHost() + "/api/fs/put";
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofMinutes(5))
                    .header("Authorization", config.getDownloadToolPassword())
                    .header("As-Task", "false")
                    .header("File-Path", URLUtil.encode(dir + "/" + fileName))
                    .header("Content-Type", "application/octet-stream")
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(content))
                    .build();
            java.net.http.HttpResponse<String> response;
            try {
                response = SUBTITLE_HTTP_CLIENT.send(
                        request, java.net.http.HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            Assert.isTrue(response.statusCode() == 200,
                    "上传字幕失败 {} 状态码:{}", dir + "/" + fileName, response.statusCode());
            JsonObject jsonObject = GsonStatic.fromJson(response.body(), JsonObject.class);
            int code = jsonObject.get("code").getAsInt();
            Assert.isTrue(code == 200, "上传字幕失败 {} 状态码:{}", dir + "/" + fileName, code);
            log.info("OpenList 字幕上传完成 {}", fileName);
            return null;
        });
    }

    /**
     * fs/put 复用单例 HttpClient，避免逐文件新建导致 selector 线程泄漏
     */
    private static final java.net.http.HttpClient SUBTITLE_HTTP_CLIENT =
            java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(30)).build();

    /**
     * 批量重命名
     *
     * @param mapList 重命名列表
     * @param srcDir  目录
     */
    public void fsBatchRename(List<Map<String, String>> mapList, String srcDir) {
        invalidateFindFilesCache();
        retryIdempotent("fs/batch_rename " + srcDir, () -> {
            postApi("fs/batch_rename")
                    .body(GsonStatic.toJson(Map.of(
                            "src_dir", srcDir,
                            "rename_objects", mapList
                    ))).then(res -> {
                        log.info(res.body());
                        assertOpenListOk(res, "fs/batch_rename " + srcDir);
                    });
            return null;
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
            return fsListStrict(path, refresh);
        } catch (Exception e) {
            log.warn("OpenList fs/list 调用失败 path={}: {}", path, ExceptionUtils.getMessage(e));
            return List.of();
        }
    }

    /**
     * 严格版文件列表：HTTP 或业务错误码失败时抛出，而非静默返回空列表。
     * <p>
     * 供必须区分"目录确实为空"与"查询失败"的场景使用（预览的「本地存在」列、媒体库），
     * 否则网盘抖动会被当成"文件都不存在"，误导用户重新下载。
     */
    public List<OpenListFileInfo> fsListStrict(String path, Boolean refresh) {
        // 熔断冷却期内直接拒绝，不再发请求：网盘已明确在限流/故障，
        // 继续打只会加重限流并拖长整轮时间。调用方据此把条目标为"存疑"。
        long cooldownRemaining = listingCooldownRemainingMs();
        if (cooldownRemaining > 0L) {
            throw new IllegalStateException("网盘接口冷却中（剩余 " + ((cooldownRemaining + 999L) / 1000L)
                    + "s），本次不发起请求 path=" + path);
        }
        try {
            List<OpenListFileInfo> result = retryIdempotent("fs/list " + path, () -> postApi("fs/list")
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
                            String message = jsonObject.has("message") ? jsonObject.get("message").getAsString() : "";
                            // 目录不存在是"确认没有"，不是故障：既不该重试，也不该计入熔断
                            if (isDirNotFoundMessage(message)) {
                                throw new OpenListDirNotFoundException(
                                        "fs/list 目录不存在 path=" + path + " message=" + message);
                            }
                            throw new IllegalStateException(
                                    "fs/list 失败 code=" + code + " path=" + path + " message=" + message);
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
            onListingSuccess();
            return result;
        } catch (RuntimeException e) {
            onListingFailure(e);
            throw e;
        }
    }

    /**
     * 递归列出目录下所有文件（3s 短缓存）
     *
     * @param path 目录
     * @return 文件列表
     */
    public synchronized List<OpenListFileInfo> findFiles(String path) {
        try {
            return findFilesStrict(path);
        } catch (Exception e) {
            log.warn("递归列出网盘目录失败 {}: {}", path, ExceptionUtils.getMessage(e));
            return List.of();
        }
    }

    /**
     * 严格版递归列举：查询失败时抛出，供必须区分"目录确实为空"与"查询失败"的调用方使用。
     */
    public synchronized List<OpenListFileInfo> findFilesStrict(String path) {
        CachedFileList cached = findFilesCache.get(path);
        if (cached != null && cached.expireAt > System.currentTimeMillis()) {
            listingCacheHit.incrementAndGet();
            return cached.files;
        }
        listingCacheMiss.incrementAndGet();

        List<OpenListFileInfo> openListFileInfos = fsListStrict(path, true);
        List<OpenListFileInfo> list = openListFileInfos.stream()
                .flatMap(openListFileInfo -> {
                    if (Boolean.TRUE.equals(openListFileInfo.getIsDir())) {
                        // 子目录可能在列举与递归之间被删/改名：那只是"这个子目录没有文件"，
                        // 不该让整个目录的列举失败（否则一次并发删除就会把整订阅判成"存疑"）
                        try {
                            return findFilesStrict(path + "/" + openListFileInfo.getName()).stream();
                        } catch (OpenListDirNotFoundException e) {
                            log.debug("子目录已不存在，跳过 {}/{}", path, openListFileInfo.getName());
                            return Stream.empty();
                        }
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
     * (E8) 对齐 fsList 容错: 先校验 HTTP 状态; data 缺失/isJsonNull 返回空列表,
     * 调用失败返回空列表, 不再让脏响应打挂整条链路
     *
     * @return 任务列表
     */
    public List<OpenListTaskInfo> taskUnDoneList() {
        try {
            return getApi("task/offline_download/undone")
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        JsonElement data = jsonObject == null ? null : jsonObject.get("data");
                        if (data == null || data.isJsonNull()) {
                            log.warn("OpenList task/undone 返回缺少 data, 按空任务处理");
                            return List.of();
                        }
                        return GsonStatic.fromJsonList(data.getAsJsonArray(), OpenListTaskInfo.class);
                    });
        } catch (Exception e) {
            log.warn("OpenList task/undone 调用失败: {}", ExceptionUtils.getMessage(e));
            return List.of();
        }
    }

    /**
     * 已完成的离线任务
     * (E8) 对齐 fsList 容错: 先校验 HTTP 状态; data 缺失/isJsonNull 返回空列表,
     * 调用失败返回空列表, 不再让脏响应打挂整条链路
     *
     * @return 任务列表
     */
    public List<OpenListTaskInfo> taskDoneList() {
        try {
            return getApi("task/offline_download/done")
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        JsonElement data = jsonObject == null ? null : jsonObject.get("data");
                        if (data == null || data.isJsonNull()) {
                            log.warn("OpenList task/done 返回缺少 data, 按空任务处理");
                            return List.of();
                        }
                        return GsonStatic.fromJsonList(data.getAsJsonArray(), OpenListTaskInfo.class);
                    });
        } catch (Exception e) {
            log.warn("OpenList task/done 调用失败: {}", ExceptionUtils.getMessage(e));
            return List.of();
        }
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
        String body = res.body();
        if (StrUtil.isBlank(body)) {
            // 少数实现只回 HTTP 200 空 body：视为成功
            return;
        }
        JsonObject jsonObject = GsonStatic.fromJson(body, JsonObject.class);
        if (jsonObject == null || !jsonObject.has("code")) {
            // 少数实现只回 HTTP 200 无 code：视为成功
            return;
        }
        int code = jsonObject.get("code").getAsInt();
        if (code != 200) {
            String message = jsonObject.has("message") ? jsonObject.get("message").getAsString() : "";
            // 115/OpenList 异步操作：errno 990009/"操作尚未执行完成"表示删除/移动正在异步处理中，
            // 容忍而非抛异常，避免并发操作冲突（如洗版连续删除）导致整流程失败
            if (code == 990009 || message.contains("990009")
                    || message.contains("尚未执行完成") || message.contains("操作尚未执行完成")) {
                log.debug("OpenList 异步操作进行中(990009) {}: {}", action, message);
                return;
            }
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
            // 重试期间抑制 HttpRequestPlus 的 ERROR（瞬时故障会在此重试并由 WARN 记录），
            // 避免单次故障在日志里刷出「ERROR + WARN」两条重复记录
            HttpRequestPlus.setRetryMode(true);
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
            } finally {
                HttpRequestPlus.setRetryMode(false);
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
     * 目录不存在（业务错误码，不是故障）。
     * <p>
     * 与"查询失败"必须区分开：
     * <ul>
     *   <li>语义上：目录不存在 = <b>确认没有</b>；查询失败 = <b>不知道</b>；</li>
     *   <li>熔断上：一个不存在的目录绝不能把整个网盘接口判为故障，
     *       否则新订阅（下载目录还没被创建）一进预览就会触发熔断。</li>
     * </ul>
     */
    public static class OpenListDirNotFoundException extends IllegalStateException {
        public OpenListDirNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * 业务错误文案是否表示"目录不存在"。不同网盘/版本的文案不一致，故做包含匹配。
     */
    static boolean isDirNotFoundMessage(String message) {
        if (StrUtil.isBlank(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("not found")
                || lower.contains("failed to get dir")
                || lower.contains("object not exist")
                || lower.contains("no such file")
                || lower.contains("dir not exist");
    }

    /**
     * 网盘列举是否处于熔断冷却期。剩余毫秒数，0 表示可用。
     */
    public static long listingCooldownRemainingMs() {
        long until = cooldownUntilMs;
        long now = System.currentTimeMillis();
        return until > now ? until - now : 0L;
    }

    public static boolean isListingCoolingDown() {
        return listingCooldownRemainingMs() > 0L;
    }

    /**
     * 限流累计等待毫秒数（诊断用）
     */
    public static long getThrottleWaitMs() {
        return throttleWaitMs.get();
    }

    /**
     * 熔断触发次数（诊断用）
     */
    public static long getCooldownTriggeredCount() {
        return cooldownTriggered.get();
    }

    /**
     * 累计 API 调用次数（诊断用）
     */
    public static long getApiCallCount() {
        return apiCallCount.get();
    }

    /**
     * 本轮 API 调用次数（诊断用）
     */
    public static long getApiCallCountRound() {
        return apiCallCountRound.get();
    }

    /**
     * 目录列举缓存命中次数（诊断用）
     */
    public static long getListingCacheHit() {
        return listingCacheHit.get();
    }

    /**
     * 目录列举缓存未命中次数（诊断用，= 真正发出去的列举请求数）
     */
    public static long getListingCacheMiss() {
        return listingCacheMiss.get();
    }

    /**
     * 目录列举缓存命中率，[0,1]；样本为 0 时返回 0。
     */
    public static double getListingCacheHitRate() {
        long hit = listingCacheHit.get();
        long total = hit + listingCacheMiss.get();
        return total == 0L ? 0.0 : (double) hit / (double) total;
    }

    /**
     * 复位本轮调用计数（每轮 RSS 扫描开始时调用）。累计值与缓存命中率不清零。
     */
    public static void resetRoundApiStats() {
        apiCallCountRound.set(0L);
    }

    // ---- F7-5 每轮 API 预算 ----

    /**
     * 开启本轮预算限制（由 {@code RssTask} 在轮次开始时调用）。
     * <p>
     * 预算 = 0 表示不限制；负值按 0 处理。
     */
    public static void startRoundBudget(int budget) {
        roundBudget.set(Math.max(0, Math.min(budget, MAX_API_BUDGET_PER_ROUND)));
    }

    /**
     * 关闭预算限制（轮次结束时调用）。不关闭的话，轮次结束后用户手动预览会被上一轮的预算卡住。
     */
    public static void clearRoundBudget() {
        roundBudget.set(0);
    }

    public static int getRoundBudget() {
        return roundBudget.get();
    }

    /**
     * 本轮预算是否已耗尽。
     * <p>
     * 语义是"<b>还能不能再为确认本地文件而列举</b>"：耗尽后应停止 Phase B，
     * 剩余条目保持「存疑」。注意这里只看调用次数，不看成功与否——
     * 失败的调用同样消耗了配额，继续打只会更快触发限流。
     */
    public static boolean isRoundBudgetExhausted() {
        int budget = roundBudget.get();
        return budget > 0 && apiCallCountRound.get() >= budget;
    }

    /**
     * 记录一次"因预算耗尽而放弃校验"，供诊断页展示
     */
    public static void markBudgetExhausted() {
        budgetExhausted.incrementAndGet();
    }

    public static long getBudgetExhaustedCount() {
        return budgetExhausted.get();
    }

    /**
     * 被请求合并省下的列举次数（可观测 F7-2 的实际收益）
     */
    public static long getListingCoalesced() {
        return listingCoalesced.get();
    }

    /**
     * 列举成功 → 复位熔断计数与退避级别。
     */
    static void onListingSuccess() {        if (consecutiveListingFailures.get() != 0 || cooldownLevel.get() != 0) {
            consecutiveListingFailures.set(0);
            cooldownLevel.set(0);
            cooldownUntilMs = 0L;
        }
    }

    /**
     * 列举失败 → 累计；达到阈值进入冷却（指数退避，上限 10 分钟）。
     * <p>
     * 冷却期内所有列举直接抛错、<b>不发请求</b>：网盘已明确在限流或故障，
     * 继续打只会加重限流并拖长整轮时间。调用方据此把条目标为"存疑"而不是"不存在"。
     */
    static void onListingFailure(Exception e) {
        if (e instanceof OpenListDirNotFoundException) {
            // 目录不存在不是故障，不参与熔断
            return;
        }
        int threshold = intConfig(ConfigUtil.CONFIG == null ? null : ConfigUtil.CONFIG.getOpenListFailThreshold(),
                3, 1, 10);
        int failures = consecutiveListingFailures.incrementAndGet();
        if (failures < threshold) {
            return;
        }
        int level = Math.min(cooldownLevel.incrementAndGet(), 6);
        long baseMs = intConfig(ConfigUtil.CONFIG == null ? null : ConfigUtil.CONFIG.getOpenListCooldownSeconds(),
                60, 10, 600) * 1000L;
        long cooldownMs = Math.min(baseMs * (1L << (level - 1)), MAX_COOLDOWN_MS);
        cooldownUntilMs = System.currentTimeMillis() + cooldownMs;
        consecutiveListingFailures.set(0);
        cooldownTriggered.incrementAndGet();
        log.warn("网盘列举连续失败 {} 次，进入冷却 {}s（第 {} 级）。冷却期内不再发起网盘请求，"
                        + "本地状态一律标记为「存疑」。原因: {}",
                threshold, cooldownMs / 1000L, level, ExceptionUtils.getMessage(e));
    }

    /**
     * 仅供测试/诊断：复位熔断、令牌桶与全部可观测计数
     */
    public static void resetRateLimitState() {
        synchronized (API_RATE_LOCK) {
            lastTokenAtNanos = 0L;
            availableTokens = 0.0;
        }
        consecutiveListingFailures.set(0);
        cooldownLevel.set(0);
        cooldownUntilMs = 0L;
        throttleWaitMs.set(0L);
        cooldownTriggered.set(0L);
        apiCallCount.set(0L);
        apiCallCountRound.set(0L);
        listingCacheHit.set(0L);
        listingCacheMiss.set(0L);
        listingCoalesced.set(0L);
        budgetExhausted.set(0L);
        roundBudget.set(0);
    }

    private static int intConfig(Integer value, int fallback, int min, int max) {
        int v = value == null ? fallback : value;
        return Math.max(min, Math.min(v, max));
    }

    /**
     * 令牌桶限流（仍<b>全局串行</b>）。
     * <p>
     * 网盘按账号限流，并发发请求只会更容易被拒；串行 + 令牌桶既能削峰又不会像固定
     * 300ms 那样在订阅多时把整轮拖成线性等待（速率可配、可突发）。
     */
    static void throttleApi() {
        apiCallCount.incrementAndGet();
        apiCallCountRound.incrementAndGet();
        synchronized (API_RATE_LOCK) {
            double perSecond = intConfig(ConfigUtil.CONFIG == null ? null : ConfigUtil.CONFIG.getOpenListApiPerSecond(),
                    (int) DEFAULT_API_PER_SECOND, 1, (int) MAX_API_PER_SECOND);
            double burst = intConfig(ConfigUtil.CONFIG == null ? null : ConfigUtil.CONFIG.getOpenListApiBurst(),
                    (int) DEFAULT_API_BURST, 1, (int) MAX_API_BURST);

            long now = System.nanoTime();
            if (lastTokenAtNanos == 0L) {
                availableTokens = burst;
            } else {
                double elapsedSec = (now - lastTokenAtNanos) / 1_000_000_000.0;
                availableTokens = Math.min(burst, availableTokens + elapsedSec * perSecond);
            }
            lastTokenAtNanos = now;

            if (availableTokens < 1.0) {
                long waitMs = (long) Math.ceil((1.0 - availableTokens) / perSecond * 1000.0);
                waitMs = Math.max(1L, Math.min(waitMs, MAX_THROTTLE_WAIT_MS));
                ThreadUtil.sleep(waitMs);
                throttleWaitMs.addAndGet(waitMs);

                long after = System.nanoTime();
                double sleptSec = (after - lastTokenAtNanos) / 1_000_000_000.0;
                availableTokens = Math.min(burst, availableTokens + sleptSec * perSecond);
                lastTokenAtNanos = after;
            }
            availableTokens -= 1.0;
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
     * fs/ 目录操作（fs/list、fs/mkdir 等）响应慢：放宽超时，避免 20s 读超时引发重试风暴
     */
    private static final int OPENLIST_FS_TIMEOUT_MS = 60 * 1000;

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
        HttpRequest req = action != null && action.startsWith("fs/")
                ? HttpReq.get(host + "/api/" + action, OPENLIST_FS_TIMEOUT_MS)
                : HttpReq.get(host + "/api/" + action);
        return req.header(Header.AUTHORIZATION, password);
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
        HttpRequest req = action != null && action.startsWith("fs/")
                ? HttpReq.post(host + "/api/" + action, OPENLIST_FS_TIMEOUT_MS)
                : HttpReq.post(host + "/api/" + action);
        return req.header(Header.AUTHORIZATION, password);
    }

}
