package ani.rss.download;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.*;
import ani.rss.entity.web.Header;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.StringEnum;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.NotificationUtil;
import ani.rss.util.other.RenameUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.Method;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenList implements BaseDownload {
    private Config config;

    // 提交中去重：防止同一 infoHash 被重复提交到 OpenList
    private static final Set<String> inFlightTasks = ConcurrentHashMap.newKeySet();

    // API 最小间隔限流（替代每次固定 sleep 2s）
    private static final long API_MIN_INTERVAL_MS = 300L;
    private static final Object API_RATE_LOCK = new Object();
    private static volatile long lastApiCallAt = 0L;

    // findFiles 短缓存，轮询期间减少递归 list
    private static final long FIND_FILES_TTL_MS = 3000L;
    private static final Map<String, CachedFileList> findFilesCache = new ConcurrentHashMap<>();

    private static final class CachedFileList {
        final long expireAt;
        final List<OpenListFileInfo> files;

        CachedFileList(List<OpenListFileInfo> files, long ttlMs) {
            this.files = files;
            this.expireAt = System.currentTimeMillis() + ttlMs;
        }
    }

    @Override
    public Boolean login(Boolean test, Config config) {
        this.config = config;
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        if (StrUtil.isBlank(host) || StrUtil.isBlank(password)) {
            log.warn("OpenList 未配置完成");
            return false;
        }
        String downloadPath = config.getDownloadPathTemplate();
        Assert.notBlank(downloadPath, "未设置下载位置");
        String provider = config.getProvider();
        Assert.notBlank(provider, "请选择 Driver");
        try {
            return postApi("me")
                    .setMethod(Method.GET)
                    .thenFunction(res -> {
                        if (!res.isOk()) {
                            log.error("登录 OpenList 失败");
                            return false;
                        }
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        if (jsonObject.get("code").getAsInt() != 200) {
                            log.error("登录 OpenList 失败");
                            return false;
                        }
                        return true;
                    });
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(e.getMessage(), e);
            log.error("登录 OpenList 失败 {}", message);
        }
        return false;
    }


    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        return List.of();
    }

    @Override
    public synchronized Boolean download(Ani ani, Item item, String savePath, File torrentFile) {
        savePath = ReUtil.replaceAll(savePath, "^[A-z]:", "");

        String magnet = TorrentUtil.getMagnet(torrentFile);
        String infoHash = ReUtil.get(StringEnum.MAGNET_REG, magnet, 1);
        String reName = item.getReName();

        // 合集：使用原始标题作为临时目录名，避免用单集名导致目录混乱
        boolean isCollection = item.getEpisodeRange() != null && !item.getEpisodeRange().isEmpty();
        if (isCollection) {
            String collectionName = item.getTitle();
            // 清理标题中的路径分隔符，取第一层文件夹名
            collectionName = collectionName.replace("\\", "/");
            if (collectionName.contains("/")) {
                collectionName = collectionName.substring(0, collectionName.indexOf("/"));
            }
            collectionName = RenameUtil.getName(collectionName);
            if (StrUtil.isNotBlank(collectionName)) {
                reName = collectionName;
            }
            log.info("合集下载，使用原始标题作为临时目录: {}", reName);
        }

        // 下载位置：与 savePath 不同则为临时目录，移动后需清理
        String path = savePath + "/" + reName;
        String tempDownloadDir = path.equals(savePath) ? null : path;
        Boolean standbyRss = config.getStandbyRss();
        Boolean delete = config.getDelete();
        Boolean coexist = config.getCoexist();

        String tid = null;
        try {
            // 提交中去重：防止同一 infoHash 被重复提交
            if (!inFlightTasks.add(infoHash)) {
                log.info("infoHash 正在提交中，跳过 {}", reName);
                return true;
            }

            mkdir(path);

            // 用 magnet 清理残留任务
            deleteResidualTasks(magnet);

            // 洗版
            if (standbyRss && delete && !coexist) {
                String s = ReUtil.get(StringEnum.SEASON_REG, reName, 0);
                String finalSavePath = savePath;
                fsList(savePath, true)
                        .stream()
                        .map(OpenListFileInfo::getName)
                        .filter(name -> name.contains(s))
                        .forEach(name -> {
                            fsRemove(finalSavePath, List.of(name));
                            log.info("已开启备用RSS, 自动删除 {}/{}", finalSavePath, name);
                        });
            }

            // 提交离线
            tid = fsAddOfflineDownload(magnet, path);

            // 10008: 任务已存在，检查文件是否已下载完成
            if (tid == null) {
                Optional<OpenListFileInfo> existing = findFiles(path).stream()
                        .filter(f -> FileUtils.isVideoFormat(f.getName()))
                        .findFirst();
                if (existing.isPresent()) {
                    log.info("离线任务已存在且文件已下载，跳过 {}", reName);
                    // 跳到后续的文件处理流程
                } else {
                    log.warn("离线任务已存在但文件未就绪，等待完成 {}", reName);
                    // 继续等待（但 tid 为空，需要从任务列表查找）
                    tid = findExistingTaskId(infoHash);
                }
            } else {
                log.info("添加离线下载成功 {}", reName);
            }

            // ⑤ 等待完成：有 tid 时以 taskInfo 为主；findFiles 仅在兜底/最终扫描使用
            Integer alistDownloadTimeout = config.getAlistDownloadTimeout();
            Long alistDownloadRetryNumber = config.getAlistDownloadRetryNumber();
            DateTime startTime = DateTime.now();
            long retry = 0;
            long filePollMiss = 0;

            while (DateTime.now().getTime() < DateUtil.offsetMinute(startTime, alistDownloadTimeout).getTime()) {
                if (tid != null) {
                    Optional<OpenListTaskInfo> taskInfoOpt = taskInfo(tid);
                    if (taskInfoOpt.isEmpty()) {
                        // 避免 taskInfo 空响应时 tight loop
                        ThreadUtil.sleep(1000);
                        continue;
                    }

                    OpenListTaskInfo taskInfo = taskInfoOpt.get();
                    OpenListTaskInfo.State state = taskInfo.getState();
                    OpenListTaskInfo.RetryPolicy policy = state.getRetryPolicy();

                    if (policy == OpenListTaskInfo.RetryPolicy.SUCCESS) {
                        break;
                    }
                    if (policy == OpenListTaskInfo.RetryPolicy.NO_RETRY) {
                        log.error("离线任务不可重试 {} state={} error={}", reName, state, taskInfo.getError());
                        return false;
                    }

                    // Pending/Running：只等待，不触发重试、不扫目录
                    if (state == OpenListTaskInfo.State.Pending
                            || state == OpenListTaskInfo.State.Running
                            || state == OpenListTaskInfo.State.Waiting_for_Retry
                            || state == OpenListTaskInfo.State.Preparing_to_Retry) {
                        ThreadUtil.sleep(2000);
                        continue;
                    }

                    // Error/Failed 等：先兜底看文件
                    if (hasVideoFile(path)) {
                        log.info("资源已下载完毕，OpenList 可能处于卡死状态，此处跳过");
                        break;
                    }
                    // 终态任务（Failed/Error/Canceled）：重试无意义，直接放弃
                    if (state == OpenListTaskInfo.State.Failed
                            || state == OpenListTaskInfo.State.Error
                            || state == OpenListTaskInfo.State.Canceled) {
                        log.error("离线任务已终结 state={} error={}，放弃重试", state, taskInfo.getError());
                        return false;
                    }
                    // 非终态异常（Failing 等）：按次数重试
                    if (alistDownloadRetryNumber > -1 && retry >= alistDownloadRetryNumber) {
                        log.error("离线下载失败 {} (已重试{}次)", taskInfo.getError(), retry);
                        return false;
                    }
                    retry++;
                    log.info("离线任务重试 {}/{} state={}", retry, alistDownloadRetryNumber, state);
                    taskRetry(tid);
                    ThreadUtil.sleep(2000);
                    continue;
                }

                // 无 tid（10008）：低频轮询文件是否出现
                if (hasVideoFile(path)) {
                    log.info("10008 任务文件已就绪 {}", reName);
                    break;
                }
                filePollMiss++;
                // 3s 起跳，最多 8s，减少无意义 findFiles
                ThreadUtil.sleep(Math.min(3000L + filePollMiss * 500L, 8000L));
            }

            if (DateTime.now().getTime() >= DateUtil.offsetMinute(startTime, alistDownloadTimeout).getTime()) {
                // 有 tid 超时直接失败；无 tid 交由后续 videoList 判定
                if (tid != null) {
                    log.error("{} {} 分钟还未下载完成, 停止检测下载", reName, alistDownloadTimeout);
                    return false;
                }
            }

            // ① finally 前先扫描文件
            List<OpenListFileInfo> openListFileInfos = findFiles(path);
            List<OpenListFileInfo> videoList = openListFileInfos.stream()
                    .filter(f -> FileUtils.isVideoFormat(f.getName()))
                    .sorted(Comparator.comparingLong(OpenListFileInfo::getSize).reversed())
                    .toList();
            List<OpenListFileInfo> subtitleList = openListFileInfos.stream()
                    .filter(f -> FileUtils.isSubtitleFormat(f.getName()))
                    .toList();

            if (videoList.isEmpty()) {
                return false;
            }

            Boolean rename = config.getRename();
            Map<String, String> renameMap = new HashMap<>();

            if (videoList.size() == 1) {
                OpenListFileInfo videoFile = videoList.get(0);
                renameMap.put(videoFile.getName(), reName + "." + FileUtil.extName(videoFile.getName()));
                for (OpenListFileInfo sub : subtitleList) {
                    String name = sub.getName();
                    String ext = FileUtil.extName(name);
                    String newName = reName;
                    String lang = FileUtil.extName(FileUtil.mainName(name));
                    if (StrUtil.isNotBlank(lang)) {
                        newName = newName + "." + lang;
                    }
                    renameMap.put(name, newName + "." + ext);
                }
            } else {
                for (OpenListFileInfo video : videoList) {
                    String videoName = video.getName();
                    String videoBase = FileUtil.mainName(videoName);
                    String videoExt = FileUtil.extName(videoName);
                    String ep = extractEpisodeFromFileName(videoName);
                    String videoReName;
                    if (ep != null && reName.contains(".E")) {
                        videoReName = reName.replaceAll("\\.E\\d+(\\.5)?", ".E" + ep);
                    } else if (ep != null && reName.matches(".*[Ss]\\d+.*E\\d+.*")) {
                        videoReName = reName.replaceAll("E\\d+(\\.5)?", "E" + ep);
                    } else {
                        videoReName = reName;
                    }
                    renameMap.put(videoName, videoReName + "." + videoExt);

                    for (OpenListFileInfo sub : subtitleList) {
                        String subName = sub.getName();
                        String subBase = FileUtil.mainName(subName);
                        String subExt = FileUtil.extName(subName);
                        String subBaseClean = subBase;
                        String lang = FileUtil.extName(subBase);
                        if (StrUtil.isNotBlank(lang) && !FileUtils.isVideoFormat(lang)) {
                            subBaseClean = FileUtil.mainName(subBase);
                        }
                        if (videoBase.equals(subBase) || videoBase.equals(subBaseClean)) {
                            String subReName = videoReName;
                            if (StrUtil.isNotBlank(lang) && !FileUtils.isVideoFormat(lang)) {
                                subReName = subReName + "." + lang;
                            }
                            renameMap.put(subName, subReName + "." + subExt);
                        }
                    }
                }
                // 处理未匹配的字幕文件 - 确保它们也被移动
                for (OpenListFileInfo sub : subtitleList) {
                    if (renameMap.containsKey(sub.getName())) continue;
                    String name = sub.getName();
                    String ext = FileUtil.extName(name);
                    // 使用视频文件名作为基础名，而不是统一的 reName
                    String videoBase = videoList.isEmpty() ? reName : FileUtil.mainName(videoList.get(0).getName());
                    String newName = videoBase;
                    String lang = FileUtil.extName(FileUtil.mainName(name));
                    if (StrUtil.isNotBlank(lang)) {
                        newName = newName + "." + lang;
                    }
                    renameMap.put(name, newName + "." + ext);
                    log.info("未匹配字幕文件: {} -> {}", name, newName + "." + ext);
                }
            }

            // ② renameMap 目标名冲突检测
            Set<String> targetNames = new HashSet<>();
            for (Map.Entry<String, String> entry : renameMap.entrySet()) {
                if (!targetNames.add(entry.getValue())) {
                    log.error("重命名目标冲突: {} -> {} (已存在)", entry.getKey(), entry.getValue());
                    throw new IllegalStateException("重命名目标文件名冲突: " + entry.getValue());
                }
            }

            // 按原始路径分组，处理文件可能分布在多个子目录的情况
            Map<String, List<String>> pathToNames = new HashMap<>();
            for (Map.Entry<String, String> entry : renameMap.entrySet()) {
                String srcName = entry.getKey();
                String newName = rename ? entry.getValue() : srcName;
                // 找到原始文件所在目录
                Optional<OpenListFileInfo> fileInfo = openListFileInfos.stream()
                        .filter(f -> f.getName().equals(srcName))
                        .findFirst();
                String dirPath = fileInfo.map(OpenListFileInfo::getPath).orElse(videoList.get(0).getPath());
                pathToNames.computeIfAbsent(dirPath, k -> new ArrayList<>()).add(newName);
            }

            // 重命名
            if (rename) {
                for (Map.Entry<String, List<String>> entry : pathToNames.entrySet()) {
                    String dirPath = entry.getKey();
                    List<Map<String, String>> renameObjects = new ArrayList<>();
                    for (String srcName : renameMap.keySet()) {
                        Optional<OpenListFileInfo> fi = openListFileInfos.stream()
                                .filter(f -> f.getName().equals(srcName)).findFirst();
                        if (fi.isPresent() && fi.get().getPath().equals(dirPath)) {
                            String newName = renameMap.get(srcName);
                            log.info("重命名 {} ==> {}", srcName, newName);
                            renameObjects.add(Map.of("src_name", srcName, "new_name", newName));
                        }
                    }
                    if (!renameObjects.isEmpty()) {
                        fsBatchRename(renameObjects, dirPath);
                    }
                }
            }

            // 移动：从每个子目录分别移动
            Set<String> allMovedNames = new HashSet<>();
            for (Map.Entry<String, List<String>> entry : pathToNames.entrySet()) {
                String dirPath = entry.getKey();
                List<String> names = entry.getValue();
                fsMove(dirPath, savePath, names);
                allMovedNames.addAll(names);
            }

            // 验证文件是否全部移动成功
            List<OpenListFileInfo> movedFiles = findFiles(savePath).stream()
                    .filter(f -> allMovedNames.contains(f.getName()))
                    .toList();
            List<String> missingNames = allMovedNames.stream()
                    .filter(name -> movedFiles.stream().noneMatch(f -> f.getName().equals(name)))
                    .toList();

            if (!missingNames.isEmpty()) {
                log.warn("部分文件移动失败，保留临时目录: {}", missingNames);
            } else {
                // 清理临时下载目录（仅在验证通过后）
                if (tempDownloadDir != null) {
                    fsRemove(savePath, List.of(reName));
                    log.info("已删除临时目录 {}/{}", savePath, reName);
                }
            }

            // 缺集校验：对比标题声明范围与实际下载文件
            if (item.getEpisodeRange() != null && !item.getEpisodeRange().isEmpty()) {
                List<Double> expected = item.getEpisodeRange();
                List<OpenListFileInfo> actualVideos = findFiles(savePath).stream()
                        .filter(f -> FileUtils.isVideoFormat(f.getName()))
                        .toList();
                Set<Double> downloadedEps = actualVideos.stream()
                        .map(f -> extractEpisodeFromFileName(f.getName()))
                        .filter(Objects::nonNull)
                        .map(ep -> Double.parseDouble(ep.replace(".5", "")))
                        .collect(Collectors.toSet());
                List<Double> missing = expected.stream()
                        .filter(ep -> !downloadedEps.contains(ep))
                        .toList();
                if (!missing.isEmpty()) {
                    log.warn("合集缺集: {} 预期 {} 集, 实际 {} 集, 缺失 {}",
                            reName, expected.size(), downloadedEps.size(), missing);
                }
            }

            NotificationUtil.send(config, ani,
                    StrFormatter.format("{} 下载完成", item.getReName()),
                    NotificationStatusEnum.DOWNLOAD_END);
            return true;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return false;
        } finally {
            // 清理提交中标记
            inFlightTasks.remove(infoHash);
            // ① 无论如何都清理离线任务
            if (tid != null && delete) {
                try {
                    taskDelete(tid);
                } catch (Exception e) {
                    log.warn("删除离线任务失败 {}: {}", tid, e.getMessage());
                }
            }
        }
    }

    @Override
    public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
        return false;
    }

    @Override
    public Boolean rename(TorrentsInfo torrentsInfo) {
        return false;
    }

    @Override
    public Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        return false;
    }

    @Override
    public void updateTrackers(Set<String> trackers) {

    }

    @Override
    public void setSavePath(TorrentsInfo torrentsInfo, String path) {

    }

    /**
     * 创建文件夹
     *
     * @param path 路径
     */
    public void mkdir(String path) {
        invalidateFindFilesCache();
        postApi("fs/mkdir")
                .body(GsonStatic.toJson(Map.of(
                        "path", path
                )))
                .then(res -> {
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    int code = jsonObject.get("code").getAsInt();
                    String message = jsonObject.get("message").getAsString();
                    if (code == 200) {
                        log.info("创建文件夹: {}", path);
                        return;
                    }

                    if (!message.startsWith("failed to check if dir exists")) {
                        return;
                    }

                    Path pathObj = Path.of(path);

                    if (pathObj.getNameCount() <= 1) {
                        return;
                    }

                    String parentPath = pathObj
                            .getParent()
                            .toString()
                            .replace('\\', '/');
                    mkdir(parentPath);
                    mkdir(path);
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
                ))).then(res -> log.info(res.body()));
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
                ))).then(HttpResponse::isOk);
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
                ))).then(res -> log.info(res.body()));
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
            return postApi("fs/list")
                    .body(GsonStatic.toJson(Map.of(
                            "path", path,
                            "page", 1,
                            "per_page", 0,
                            "refresh", refresh
                    )))
                    .thenFunction(res -> {
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        int code = jsonObject.get("code").getAsInt();
                        if (code != 200) {
                            return List.of();
                        }
                        JsonElement data = jsonObject.get("data");
                        if (Objects.isNull(data) || data.isJsonNull()) {
                            return List.of();
                        }
                        JsonElement content = data.getAsJsonObject()
                                .get("content");
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
                    });
        } catch (Exception e) {
            log.info("OpenList API 调用失败: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * 查看任务
     *
     * @param tid 任务id
     * @return 任务信息
     */
    public Optional<OpenListTaskInfo> taskInfo(String tid) {
        try {
            OpenListTaskInfo taskInfo = postApi("task/offline_download/info?tid=" + tid)
                    .thenFunction(res -> {
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        JsonObject data = jsonObject.get("data").getAsJsonObject();
                        return GsonStatic.fromJson(data, OpenListTaskInfo.class);
                    });
            return Optional.of(taskInfo);
        } catch (Exception e) {
            log.info("OpenList API 调用失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * 根据 infoHash 查找已存在的离线任务 ID
     */
    private String findExistingTaskId(String infoHash) {
        List<OpenListTaskInfo> tasks = new ArrayList<>();
        tasks.addAll(taskDoneList());
        tasks.addAll(taskUnDoneList());
        for (OpenListTaskInfo task : tasks) {
            if (task.getName() != null && task.getName().toLowerCase().contains(infoHash.toLowerCase())) {
                return task.getId();
            }
        }
        return null;
    }

    /**
     * 删除残留任务
     *
     * @param magnet 磁力
     */
    public void deleteResidualTasks(String magnet) {
        List<OpenListTaskInfo> taskDoneList = taskDoneList();
        List<OpenListTaskInfo> taskUnDoneList = taskUnDoneList();

        List<OpenListTaskInfo> tasks = new ArrayList<>();
        tasks.addAll(taskDoneList);
        tasks.addAll(taskUnDoneList);

        for (OpenListTaskInfo task : tasks) {
            String id = task.getId();
            String name = task.getName();
            if (name.contains(magnet)) {
                log.info("删除残留任务: {} {}", id, name);
                taskDelete(id);
            }
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
        postApi("task/offline_download/retry")
                .form("tid", tid)
                .thenFunction(HttpResponse::isOk);
    }

    /**
     * 删除任务
     *
     * @param tid 任务id
     */
    public void taskDelete(String tid) {
        postApi("task/offline_download/delete_some")
                .body(GsonStatic.toJson(List.of(tid)))
                .thenFunction(HttpResponse::isOk);
    }

    /**
     * 快速判断目录下是否已有视频（走 findFiles 缓存）
     */
    private boolean hasVideoFile(String path) {
        return findFiles(path).stream().anyMatch(f -> FileUtils.isVideoFormat(f.getName()));
    }

    /**
     * 获取目录下及子目录的文件
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
     * 目录变更后清理 findFiles 缓存
     */
    private static void invalidateFindFilesCache() {
        findFilesCache.clear();
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
