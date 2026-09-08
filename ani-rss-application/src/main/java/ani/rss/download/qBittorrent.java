package ani.rss.download;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentsInfo;
import ani.rss.enums.StringEnum;
import ani.rss.enums.TorrentsTags;
import ani.rss.service.DownloadService;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.EnumUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * qBittorrent
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class qBittorrent implements BaseDownload {

    private final DownloadService downloadService;

    private Config config;

    /**
     * 旧版 qBittorrent（≤5.1）用户名密码登录会话（host → SID cookie 映射，
     * 多 host/测试登录互不干扰；ApiKey 模式清空）
     */
    private static final Map<String, String> SESSION_COOKIES = new ConcurrentHashMap<>();

    /**
     * 获取对应任务的文件列表
     *
     * @param torrentsInfo
     * @param filter       过滤出视频与字幕
     * @param config
     * @return
     */
    public static List<FileEntity> files(TorrentsInfo torrentsInfo, Boolean filter, Config config) {
        String hash = torrentsInfo.getHash();

        return apiGet("/api/v2/torrents/files", req -> req.form("hash", hash), res -> {
            HttpReq.assertStatus(res);
            return GsonStatic.fromJsonList(res.body(), FileEntity.class).stream()
                            .filter(fileEntity -> {
                                if (!filter) {
                                    return true;
                                }
                                String name = fileEntity.getName();
                                String extName = FileUtil.extName(name);
                                if (StrUtil.isBlank(extName)) {
                                    return false;
                                }
                                extName = extName.toLowerCase();
                                Long size = fileEntity.getSize();
                                if (size < 1) {
                                    return false;
                                }
                                return FileUtils.isVideoFormat(extName) || FileUtils.isSubtitleFormat(extName);
                            })
                            .sorted((fileEntity1, fileEntity2) -> Long.compare(fileEntity2.getSize(), fileEntity1.getSize()))
                            .toList();
        });
    }

    @Override
    public Boolean login(Boolean test, Config config) {
        this.config = config;
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();

        if (StrUtil.isBlank(host) || StrUtil.isBlank(password)) {
            log.warn("qBittorrent 未配置完成");
            return false;
        }

        // 优先 ApiKey 授权（qBittorrent 5.2.0+）
        // 注意：探测端点必须需要认证（/app/version 历史版本可匿名访问，会误判）
        try {
            boolean bearerOk = HttpReq.get(host + "/api/v2/app/preferences")
                    .header(Header.AUTHORIZATION, "Bearer " + password)
                    .thenFunction(HttpResponse::isOk);
            if (bearerOk) {
                // ApiKey 模式：仅清除当前 host 的 cookie 会话，不影响其它 host
                SESSION_COOKIES.remove(host);
                log.info("qBittorrent ApiKey 授权成功");
                return true;
            }
            log.debug("qBittorrent ApiKey 授权失败(HTTP 非 2xx)，回退用户名密码登录");
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.debug("qBittorrent ApiKey 授权异常，回退用户名密码登录: {}", message);
        }

        // 回退：传统用户名密码登录（qBittorrent ≤5.1 / 未启用 ApiKey）
        return loginWithUsernamePassword(config);
    }

    /**
     * 传统用户名密码登录（qBittorrent ≤5.1 兼容），成功后保存 SID cookie
     */
    private static boolean loginWithUsernamePassword(Config config) {
        String host = config.getDownloadToolHost();
        String username = config.getDownloadToolUsername();
        String password = config.getDownloadToolPassword();

        if (StrUtil.isBlank(username)) {
            log.error("qBittorrent 登录失败：ApiKey 无效且未配置用户名");
            return false;
        }

        try {
            HttpResponse loginRes = HttpReq.post(host + "/api/v2/auth/login")
                    .form("username", username)
                    .form("password", password)
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        return res;
                    });
            String body = loginRes.body();
            if (!body.contains("Ok.")) {
                log.error("qBittorrent 用户名密码登录失败: {}", body);
                return false;
            }
            String cookieHeader = loginRes.header("Set-Cookie");
            String sid = ReUtil.get("(?i)SID=([^;]+)", StrUtil.nullToEmpty(cookieHeader), 1);
            if (StrUtil.isBlank(sid)) {
                log.error("qBittorrent 用户名密码登录成功但未获取到 SID cookie");
                return false;
            }
            SESSION_COOKIES.put(host, sid);
            log.info("qBittorrent 用户名密码登录成功");
            return true;
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
            return false;
        }
    }

    /**
     * 为 API 请求附加认证：优先 SID cookie 会话（旧版 qB），否则 Bearer ApiKey
     */
    private static void applyAuth(HttpRequest req, Config cfg) {
        String host = cfg.getDownloadToolHost();
        String sid = SESSION_COOKIES.get(host);
        if (StrUtil.isNotBlank(sid)) {
            req.header(Header.COOKIE, "SID=" + sid);
        } else {
            req.header(Header.AUTHORIZATION, "Bearer " + cfg.getDownloadToolPassword());
        }
    }

    /**
     * (E1) GET API 请求统一封装: 执行请求并在 401/403+SID 时自动重登重放一次
     */
    private static <T> T apiGet(String path, Function<HttpRequest, HttpRequest> customizer,
                                Function<HttpResponse, T> sender) {
        return authedApi(() -> buildApiRequest(path, false, customizer), sender, path);
    }

    /**
     * (E1) POST API 请求统一封装: 执行请求并在 401/403+SID 时自动重登重放一次
     */
    private static <T> T apiPost(String path, Function<HttpRequest, HttpRequest> customizer,
                                 Function<HttpResponse, T> sender) {
        return authedApi(() -> buildApiRequest(path, true, customizer), sender, path);
    }

    /**
     * (E1) 按方法构建带认证的 API 请求(customizer 附加 form/query 参数)
     */
    private static HttpRequest buildApiRequest(String path, boolean post, Function<HttpRequest, HttpRequest> customizer) {
        Config cfg = ConfigUtil.CONFIG;
        HttpRequest req = post
                ? HttpReq.post(cfg.getDownloadToolHost() + path)
                : HttpReq.get(cfg.getDownloadToolHost() + path);
        req = customizer.apply(req);
        applyAuth(req, cfg);
        return req;
    }

    /**
     * (E1) API 请求统一封装: 响应 401/403 且当前 host 存在 SID 会话时视为 SID 失效(qB 重启等),
     * 自动重登一次后重建请求原样重放(同一线程内最多重试一次, 防循环);
     * ApiKey 模式(无 SID)不重试, 保持原行为。
     */
    private static <T> T authedApi(Supplier<HttpRequest> reqFactory, Function<HttpResponse, T> sender, String path) {
        HttpResponse res = null;
        try {
            res = reqFactory.get().execute();
            int status = res.getStatus();
            String host = ConfigUtil.CONFIG.getDownloadToolHost();
            if ((status == 401 || status == 403)
                    && StrUtil.isNotBlank(SESSION_COOKIES.get(host))) {
                log.warn("qBittorrent 请求 {} 返回 {}，SID 可能失效，尝试重新登录", path, status);
                SESSION_COOKIES.remove(host);
                if (loginWithUsernamePassword(ConfigUtil.CONFIG)) {
                    // 重放仅此一次: 重登后的请求不会再进入本封装, 天然防循环
                    res.close();
                    try (HttpResponse retry = reqFactory.get().execute()) {
                        return sender.apply(retry);
                    }
                }
                log.error("qBittorrent 重新登录失败，保留 {} 响应", status);
            }
            return sender.apply(res);
        } finally {
            if (res != null) {
                res.close();
            }
        }
    }

    @Override
    public Boolean download(Ani ani, Item item, String savePath, File torrentFile) {
        String name = item.getReName();
        Boolean qbUseDownloadPath = config.getQbUseDownloadPath();

        List<String> tags = newTags(ani, item);

        Integer ratioLimit = config.getRatioLimit();
        Integer seedingTimeLimit = config.getSeedingTimeLimit();
        Integer inactiveSeedingTimeLimit = config.getInactiveSeedingTimeLimit();
        Boolean rename = config.getRename();

        Long upLimit = config.getUpLimit() * 1024;
        Long dlLimit = config.getDlLimit() * 1024;

        String qbContentLayout = config.getQbContentLayout();

        // (E1) 走统一封装: 请求构建放在 customizer 中, 重登重放时可原样重建请求
        boolean ok = apiPost("/api/v2/torrents/add", req -> {
            req.form("addToTopOfQueue", false)
                    .form("autoTMM", false)
                    .form("category", TorrentsTags.ANI_RSS.getValue())
                    .form("contentLayout", qbContentLayout)
                    .form("dlLimit", dlLimit)
                    .form("firstLastPiecePrio", false)
                    .form("rename", name)
                    .form("savepath", savePath)
                    .form("sequentialDownload", false)
                    .form("skip_checking", false)
                    .form("stopCondition", "None")
                    .form("upLimit", upLimit)
                    .form("useDownloadPath", qbUseDownloadPath)
                    .form("tags", CollUtil.join(tags, ","))
                    .form("ratioLimit", ratioLimit)
                    .form("seedingTimeLimit", seedingTimeLimit)
                    .form("inactiveSeedingTimeLimit", inactiveSeedingTimeLimit);

            String extName = FileUtil.extName(torrentFile);
            if ("txt".equals(extName)) {
                req
                        .form("paused", false)
                        .form("stopped", false)
                        .form("urls", FileUtil.readUtf8String(torrentFile));
            } else {
                if (torrentFile.length() > 0) {
                    // 开启了重命名则在重命名后再开始下载
                    req.form("paused", rename)
                            .form("stopped", rename)
                            .form("torrents", torrentFile);
                } else {
                    req
                            .form("paused", false)
                            .form("stopped", false)
                            .form("urls", "magnet:?xt=urn:btih:" + FileUtil.mainName(torrentFile));
                }
            }
            return req;
        }, res -> {
            // 提交响应校验：qB 成功返回 "Ok."，失败返回 "Fails."（重复/坏种），空 body 视为成功
            if (!res.isOk()) {
                return false;
            }
            String body = res.body();
            return StrUtil.isBlank(body) || body.contains("Ok.");
        });
        if (!ok) {
            log.error("qBittorrent 添加任务失败 {}", name);
            return false;
        }
        // 不再持锁 3×10s 轮询确认：提交成功即视为已入队，重命名/状态由 RenameTask 周期性兜底
        log.info("qBittorrent 添加任务成功 {}", name);
        return true;
    }

    /**
     * 开始下载
     *
     * @param torrentsInfo
     * @return
     */
    public static Boolean start(TorrentsInfo torrentsInfo, Config config) {
        // (E1) 走统一封装(401/403+SID 自动重登一次)
        boolean b = apiPost("/api/v2/torrents/start", req -> req.form("hashes", torrentsInfo.getHash()),
                HttpResponse::isOk);
        if (b) {
            return true;
        }

        return apiPost("/api/v2/torrents/resume", req -> req.form("hashes", torrentsInfo.getHash()),
                HttpResponse::isOk);
    }

    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        // (E1) 解析成功但为空 → 返回空列表; 请求/解析异常 → 向上抛 RuntimeException,
        // 由上层区分"无任务"与"查询失败"(查询失败不得当作无任务放行并发上限/误判坏种)
        // (E1) 走统一封装(401/403+SID 自动重登一次)
        return apiGet("/api/v2/torrents/info", req -> req, res -> {
            HttpReq.assertStatus(res);
            List<TorrentsInfo> torrentsInfoList = new ArrayList<>();
            JsonArray jsonElements = GsonStatic.fromJson(res.body(), JsonArray.class);
            for (JsonElement jsonElement : jsonElements) {
                JsonObject jsonObject = jsonElement.getAsJsonObject();
                // (E1) 逐字段守护: get 可能返回 null 或 JsonNull, 跳过脏条目
                JsonElement tagsEl = jsonObject.get("tags");
                if (Objects.isNull(tagsEl) || tagsEl.isJsonNull()) {
                    continue;
                }
                String tags = tagsEl.getAsString();

                if (StrUtil.isBlank(tags)) {
                    continue;
                }

                String hash = getJsonString(jsonObject, "hash");
                String name = getJsonString(jsonObject, "name");
                String savePath = getJsonString(jsonObject, "save_path");
                if (hash == null || name == null) {
                    continue;
                }
                Long completed = getJsonLong(jsonObject, "completed");
                Long size = getJsonLong(jsonObject, "size");
                if (completed == null || size == null) {
                    continue;
                }
                JsonElement state = jsonObject.get("state");

                List<String> tagList = StrUtil.split(tags, ",", true, true);

                TorrentsInfo torrentsInfo = new TorrentsInfo();

                torrentsInfo.setState(Objects.isNull(state) || state.isJsonNull() ?
                        TorrentsInfo.State.downloading : EnumUtil.fromString(TorrentsInfo.State.class, state.getAsString(), TorrentsInfo.State.downloading)
                );

                torrentsInfo
                        .progress(completed, size)
                        .setName(name)
                        .setHash(hash)
                        .setDownloadDir(FileUtils.getAbsolutePath(savePath))
                        .setTags(tagList)
                        .setFiles(() ->
                                files(torrentsInfo, true, config)
                                        .stream()
                                        .filter(fileEntity -> fileEntity.getPriority() > 0)
                                        .map(FileEntity::getName)
                                        .toList());
                // 包含标签
                if (tagList.contains(TorrentsTags.ANI_RSS.getValue())) {
                    torrentsInfoList.add(torrentsInfo);
                    continue;
                }

                JsonElement category = jsonObject.get("category");
                if (Objects.isNull(category) || category.isJsonNull()) {
                    continue;
                }
                if (category.getAsString().equals(TorrentsTags.ANI_RSS.getValue())) {
                    torrentsInfoList.add(torrentsInfo);
                }
            }
            return torrentsInfoList;
        });
    }

    /**
     * (E1) 取字符串字段: 字段缺失/null/JsonNull 返回 null, 由调用方决定跳过
     */
    private static String getJsonString(JsonObject jsonObject, String key) {
        JsonElement el = jsonObject.get(key);
        if (Objects.isNull(el) || el.isJsonNull()) {
            return null;
        }
        return el.getAsString();
    }

    /**
     * (E1) 取数值字段: 字段缺失/null/JsonNull 或格式非法返回 null, 由调用方决定跳过
     */
    private static Long getJsonLong(JsonObject jsonObject, String key) {
        JsonElement el = jsonObject.get(key);
        if (Objects.isNull(el) || el.isJsonNull()) {
            return null;
        }
        try {
            return el.getAsLong();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
        String name = torrentsInfo.getName();
        String hash = torrentsInfo.getHash();
        try {
            List<FileEntity> files = files(torrentsInfo, false, config);
            boolean b = apiPost("/api/v2/torrents/delete",
                    req -> req.form("hashes", hash).form("deleteFiles", deleteFiles),
                    HttpResponse::isOk);
            if (!b) {
                return false;
            }

            // 剧场版不用进行残留的文件夹清理
            if (!ReUtil.contains(StringEnum.SEASON_REG, name)) {
                return true;
            }

            String downloadDir = torrentsInfo.getDownloadDir();

            List<File> dirList = files.stream()
                    .map(FileEntity::getName)
                    .map(File::new)
                    .map(File::getParent)
                    .filter(StrUtil::isNotBlank)
                    .map(s -> downloadDir + "/" + s)
                    .distinct()
                    .map(File::new)
                    .filter(File::exists)
                    .filter(File::isDirectory)
                    .toList();

            Boolean subtitleIndependentFolderEnabled = config.getSubtitleIndependentFolderEnabled();
            String subtitleIndependentFolderName = config.getSubtitleIndependentFolderName();

            // 清空剩余文件夹
            for (File file : dirList) {
                if (subtitleIndependentFolderEnabled) {
                    if (subtitleIndependentFolderName.equals(file.getName())) {
                        // 字幕独立文件夹 不进行删除
                        continue;
                    }
                }

                log.info("删除剩余文件夹: {}", file);
                try {
                    FileUtil.del(file);
                } catch (Exception e) {
                    log.info("删除失败: {}", file);
                    log.error(e.getMessage(), e);
                }
            }

            return true;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return false;
        }
    }

    @Override
    public Boolean rename(TorrentsInfo torrentsInfo) {
        String reName = torrentsInfo.getName();

        if (StrUtil.isBlank(reName) || !ReUtil.contains(StringEnum.SEASON_REG, reName)) {
            // 磁力种子元数据未到达时种子名仍是 40 位 hash（不含 SxxExx）：
            // 此时不能直接 start+return true——TorrentUtil 会据此打上 RENAME 标签，
            // 元数据到达后本轮已结束、下轮被标签跳过，文件永远不被重命名（目录名=规则名、文件=原名）。
            // 必须先确认文件列表已就绪，空列表表示元数据仍在获取，交由下轮重试。
            List<FileEntity> metaFiles = files(torrentsInfo, true, config);
            if (metaFiles.isEmpty()) {
                log.debug("{} 磁力链接还在获取元数据中，暂不开始/打标签", torrentsInfo.getHash());
                return false;
            }
            // 真正的剧场版 OR OVA（文件列表已就绪）直接开始任务
            Boolean start = start(torrentsInfo, config);
            Assert.isTrue(start, "开始任务失败 {}", reName);
            if (start) {
                log.info("开始任务 {}", reName);
            }
            return true;
        }

        String hash = torrentsInfo.getHash();


        Optional<Ani> aniOpt = downloadService.findAniByDownloadPath(torrentsInfo);

        if (aniOpt.isEmpty()) {
            log.error("未能获取番剧对象: {}", torrentsInfo.getName());
            return false;
        }

        Ani ani = aniOpt.get();

        // 检查是否为新版命名
        Integer namingVersion = ani.getNamingVersion();
        boolean isNamingV2 = namingVersion != null && namingVersion == 2;

        List<String> priorityKeywords = getPriorityKeywords(config, ani);

        List<FileEntity> files = files(torrentsInfo, true, config);

        if (!priorityKeywords.isEmpty()) {
            files = files.stream()
                    .sorted(Comparator.comparingInt(file -> {
                        String fileName = file.getName();
                        String mainName = FileUtil.mainName(fileName);
                        int minIndex = Integer.MAX_VALUE;
                        for (int i = 0; i < priorityKeywords.size(); i++) {
                            String priorityKeyword = priorityKeywords.get(i);
                            if (!mainName.contains(priorityKeyword)) {
                                continue;
                            }
                            minIndex = Math.min(minIndex, i);
                        }
                        return minIndex;
                    }))
                    .toList();
        }

        List<String> names = files.stream()
                .map(FileEntity::getName)
                .toList();

        if (files.isEmpty()) {
            log.debug("{} 磁力链接还在获取原数据中", hash);
            return false;
        }

        Boolean subtitleIndependentFolderEnabled = config.getSubtitleIndependentFolderEnabled();
        String subtitleIndependentFolderName = config.getSubtitleIndependentFolderName();

        List<String> newNames = new ArrayList<>();

        // 统计视频文件数量，判断是否为多文件合集
        long videoCount = files.stream()
                .filter(f -> FileUtils.isVideoFormat(FileUtil.extName(f.getName())))
                .count();
        boolean isMultiFile = videoCount > 1;

        for (FileEntity fileEntity : files) {
            String name = fileEntity.getName();
            String ext = FileUtil.extName(name);
            boolean isSub = FileUtils.isSubtitleFormat(ext);

            String newPath;
            if (isMultiFile && isNamingV2) {
                // 多文件合集：从原始文件名提取集数
                newPath = getFileReNameMulti(name, reName, isSub);
            } else {
                // 单文件：使用原逻辑
                newPath = getFileReName(name, reName);
            }

            if (
                    FileUtils.isSubtitleFormat(newPath) &&
                            subtitleIndependentFolderEnabled &&
                            StrUtil.isNotBlank(subtitleIndependentFolderName)
            ) {
                // 字幕独立文件夹
                newPath = subtitleIndependentFolderName + "/" + newPath;
            }

            if (names.contains(newPath)) {
                continue;
            }
            if (newNames.contains(newPath)) {
                // 停止不必要的文件下载
                apiPost("/api/v2/torrents/filePrio",
                        req -> req.form("hash", hash)
                                .form("id", fileEntity.getIndex())
                                .form("priority", 0),
                        HttpResponse::isOk);
                continue;
            }
            newNames.add(newPath);

            // 文件名未发生改变
            if (name.equals(newPath)) {
                continue;
            }

            log.info("重命名 {} ==> {}", name, newPath);

            // newPath 在循环内被重赋值, 非 effectively-final, lambda 需要终态副本
            final String finalNewPath = newPath;
            Boolean b = apiPost("/api/v2/torrents/renameFile",
                    req -> req.form("hash", hash)
                            .form("oldPath", name)
                            .form("newPath", finalNewPath),
                    HttpResponse::isOk);
            Assert.isTrue(b, "重命名失败 {} ==> {}", name, newPath);
        }

        Boolean start = start(torrentsInfo, config);
        Assert.isTrue(start, "开始任务失败 {}", reName);
        log.info("开始任务 {}", reName);

        if (newNames.isEmpty()) {
            return true;
        }

        // qb重命名具有延迟，等待重命名完成
        for (int i = 0; i < 10; i++) {
            ThreadUtil.sleep(1000);
            names = torrentsInfo.getFiles().get();
            if (new HashSet<>(names).containsAll(newNames)) {
                return true;
            }
        }

        log.warn("重命名貌似出现了问题？{}", reName);
        return false;
    }

    @Override
    public Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        String hash = torrentsInfo.getHash();
        // (E1) 走统一封装(401/403+SID 自动重登一次)
        return apiPost("/api/v2/torrents/addTags",
                req -> req.form("hashes", hash).form("tags", tags),
                res -> {
                    boolean ok = res.isOk();
                    if (!ok) {
                        log.error(res.body());
                    }
                    return ok;
                });
    }

    @Override
    public void updateTrackers(Set<String> trackers) {
        // (E1) 走统一封装(401/403+SID 自动重登一次)
        JsonObject preferences = apiGet("/api/v2/app/preferences", req -> req, res -> {
            int status = res.getStatus();
            boolean ok = res.isOk();
            Assert.isTrue(ok, "更新trackers失败 {}", status);
            String body = res.body();
            return GsonStatic.fromJson(body, JsonObject.class);
        });

        preferences.addProperty("add_trackers", CollUtil.join(trackers, "\n"));
        preferences.addProperty("add_trackers_enabled", true);

        apiPost("/api/v2/app/setPreferences",
                req -> req.form("json", GsonStatic.toJson(preferences)),
                res -> {
                    if (res.isOk()) {
                        log.info("qBittorrent 更新Trackers完成 共{}条", trackers.size());
                    } else {
                        log.error("qBittorrent 更新Trackers失败 {}", res.getStatus());
                    }
                    return null;
                });

    }

    @Override
    public void setSavePath(TorrentsInfo torrentsInfo, String path) {
        // (E1) 走统一封装(401/403+SID 自动重登一次)
        apiPost("/api/v2/torrents/setAutoManagement",
                req -> req.form("hashes", torrentsInfo.getHash()).form("enable", false),
                HttpResponse::isOk);
        apiPost("/api/v2/torrents/setSavePath",
                req -> req.form("hashes", torrentsInfo.getHash()).form("path", path),
                res -> {
                    if (!res.isOk()) {
                        log.error(res.body());
                    }
                    return null;
                });
    }


    @Data
    @Accessors(chain = true)
    public static class FileEntity {
        private Integer index;
        private String name;
        private Long size;
        /**
         * 1 允许下载。2 禁止下载
         */
        private Integer priority;
    }

    private static List<String> getPriorityKeywords(Config config, Ani ani) {
        Boolean priorityKeywordsEnable = config.getPriorityKeywordsEnable();
        Boolean customPriorityKeywordsEnable = ani.getCustomPriorityKeywordsEnable();

        if (customPriorityKeywordsEnable) {
            return ani.getCustomPriorityKeywords();
        }

        if (priorityKeywordsEnable) {
            return config.getPriorityKeywords();
        }

        return new ArrayList<>();
    }


}
