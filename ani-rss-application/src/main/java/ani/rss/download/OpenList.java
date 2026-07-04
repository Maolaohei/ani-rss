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
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenList implements BaseDownload {
    private Config config;

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
        // 用 InfoHash 替代完整 magnet 进行任务匹配
        String infoHash = ReUtil.get(StringEnum.MAGNET_REG, magnet, 1);
        String reName = item.getReName();
        Boolean isCollection = item.getEpisodeRange() != null && item.getEpisodeRange().size() > 1;
        String path = isCollection ? savePath : savePath + "/" + reName;
        Boolean standbyRss = config.getStandbyRss();
        Boolean delete = config.getDelete();
        Boolean coexist = config.getCoexist();

        String tid = null;
        try {
            mkdir(path);

            // ④ 用 InfoHash 清理残留任务
            deleteResidualTasks(infoHash);

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
            log.info("添加离线下载成功 {}", reName);

            // ⑤ 等待完成（区分重试策略）
            DateTime startTime = DateTime.now();
            long retry = 0;
            while (true) {
                Integer alistDownloadTimeout = config.getAlistDownloadTimeout();
                Long alistDownloadRetryNumber = config.getAlistDownloadRetryNumber();

                if (DateTime.now().getTime() >= DateUtil.offsetMinute(startTime, alistDownloadTimeout).getTime()) {
                    log.error("{} {} 分钟还未下载完成, 停止检测下载", reName, alistDownloadTimeout);
                    return false;
                }

                Optional<OpenListTaskInfo> taskInfoOpt = taskInfo(tid);
                if (taskInfoOpt.isEmpty()) {
                    continue;
                }

                OpenListTaskInfo taskInfo = taskInfoOpt.get();
                OpenListTaskInfo.State state = taskInfo.getState();
                OpenListTaskInfo.RetryPolicy policy = state.getRetryPolicy();

                switch (policy) {
                    case SUCCESS:
                        break;

                    case NO_RETRY:
                        log.error("离线任务不可重试 {} state={} error={}", reName, state, taskInfo.getError());
                        return false;

                    case RETRY:
                        // 兜底：文件已存在但状态未刷新
                        Optional<OpenListFileInfo> first = findFiles(path).stream()
                                .filter(f -> FileUtils.isVideoFormat(f.getName()))
                                .findFirst();
                        if (first.isPresent()) {
                            log.info("资源已下载完毕，OpenList 可能处于卡死状态，此处跳过");
                            break;
                        }
                        if (alistDownloadRetryNumber > -1 && retry >= alistDownloadRetryNumber) {
                            log.error("离线下载失败 {} (已重试{}次)", taskInfo.getError(), retry);
                            return false;
                        }
                        retry++;
                        log.info("离线任务重试 {}/{} state={}", retry, alistDownloadRetryNumber, state);
                        taskRetry(tid);
                        continue; // 继续轮询
                }
                break; // SUCCESS 或兜底跳出
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
                for (OpenListFileInfo sub : subtitleList) {
                    if (renameMap.containsKey(sub.getName())) continue;
                    String name = sub.getName();
                    String ext = FileUtil.extName(name);
                    String newName = reName;
                    String lang = FileUtil.extName(FileUtil.mainName(name));
                    if (StrUtil.isNotBlank(lang)) {
                        newName = newName + "." + lang;
                    }
                    renameMap.put(name, newName + "." + ext);
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

            String firstVideoPath = videoList.get(0).getPath();

            if (rename) {
                List<Map<String, String>> renameObjects = renameMap.entrySet().stream()
                        .map(map -> {
                            log.info("重命名 {} ==> {}", map.getKey(), map.getValue());
                            return Map.of("src_name", map.getKey(), "new_name", map.getValue());
                        }).toList();
                fsBatchRename(renameObjects, firstVideoPath);
            }

            // 移动
            List<String> names = renameMap.entrySet().stream()
                    .map(m -> rename ? m.getValue() : m.getKey())
                    .toList();
            fsMove(firstVideoPath, savePath, names);

            // 缺集校验：对比标题声明范围与实际下载文件
            if (isCollection && item.getEpisodeRange() != null) {
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

            // ③ 重新扫描源目录，确认视频/字幕已全部移走再删
            if (isCollection) {
                List<OpenListFileInfo> remaining = findFiles(path).stream()
                        .filter(f -> FileUtils.isVideoFormat(f.getName()) || FileUtils.isSubtitleFormat(f.getName()))
                        .toList();
                if (remaining.isEmpty()) {
                    // 源目录已空，安全删除子目录
                    fsList(path, true).stream()
                            .filter(OpenListFileInfo::getIsDir)
                            .forEach(dir -> fsRemove(path, List.of(dir.getName())));
                } else {
                    log.warn("源目录仍有 {} 个视频/字幕未移走，跳过删除", remaining.size());
                }
            } else {
                // 单集：重新扫描确认为空再删
                List<OpenListFileInfo> remaining = findFiles(savePath + "/" + reName).stream()
                        .filter(f -> FileUtils.isVideoFormat(f.getName()) || FileUtils.isSubtitleFormat(f.getName()))
                        .toList();
                if (remaining.isEmpty()) {
                    fsRemove(savePath, List.of(reName));
                } else {
                    log.warn("目录 {}/{} 仍有文件未移走，跳过删除", savePath, reName);
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
                    Assert.isTrue(jsonObject.get("code").getAsInt() == 200);
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
            log.error(e.getMessage(), e);
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
            log.error(e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * 删除残留任务
     *
     * @param magnet 磁力
     */
    public void deleteResidualTasks(String infoHash) {
        List<OpenListTaskInfo> taskDoneList = taskDoneList();
        List<OpenListTaskInfo> taskUnDoneList = taskUnDoneList();

        List<OpenListTaskInfo> tasks = new ArrayList<>();
        tasks.addAll(taskDoneList);
        tasks.addAll(taskUnDoneList);

        for (OpenListTaskInfo task : tasks) {
            String id = task.getId();
            String name = task.getName();
            if (name.toLowerCase().contains(infoHash.toLowerCase())) {
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
     * 获取目录下及子目录的文件
     *
     * @param path 目录
     * @return 文件列表
     */
    public synchronized List<OpenListFileInfo> findFiles(String path) {
        List<OpenListFileInfo> openListFileInfos = fsList(path, true);
        List<OpenListFileInfo> list = openListFileInfos.stream()
                .flatMap(openListFileInfo -> {
                    if (openListFileInfo.getIsDir()) {
                        return findFiles(path + "/" + openListFileInfo.getName()).stream();
                    }
                    return Stream.of(openListFileInfo);
                }).toList();

        return ListUtil.sort(new ArrayList<>(list), Comparator.comparing(fileInfo -> {
            Long size = fileInfo.getSize();
            return Long.MAX_VALUE - ObjectUtil.defaultIfNull(size, 0L);
        }));
    }

    /**
     * get api
     *
     * @param action
     * @return
     */
    public synchronized HttpRequest getApi(String action) {
        ThreadUtil.sleep(2000);
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
        ThreadUtil.sleep(2000);
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        return HttpReq.post(host + "/api/" + action)
                .header(Header.AUTHORIZATION, password);
    }

}
