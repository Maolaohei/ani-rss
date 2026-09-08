package ani.rss.download;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentsInfo;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.basic.RenameCacheUtil;
import ani.rss.util.other.RenameUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

/**
 * Aria2
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Aria2 implements BaseDownload {
    private Config config;

    @Override
    public Boolean login(Boolean test, Config config) {
        this.config = config;
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();

        if (StrUtil.isBlank(host) || StrUtil.isBlank(password)) {
            log.warn("Aria2 未配置完成");
            return false;
        }

        String body = ResourceUtil.readUtf8Str("aria2/getGlobalStat.json");
        body = StrFormatter.format(body, password);
        // (E3) 登录校验必须看 JSON-RPC 业务结果, 不能只看 HTTP 200:
        // 密钥错误时 aria2 仍可能返回 HTTP 200 + error 体, 原实现会假成功
        try {
            JsonElement result = postRpc(host, body);
            return result != null;
        } catch (Exception e) {
            log.error("Aria2 登录失败: {}", ExceptionUtils.getMessage(e));
            return false;
        }
    }

    /**
     * (E3) 统一 JSON-RPC 调用与响应处理: HTTP 校验后先判 error 成员,
     * 存在即抛 RuntimeException(带 error.message); 正常返回 result 成员
     * (可能为 null, 类型与含义由调用方判断——tell* 为数组, add 等为标量/对象)。
     */
    private static JsonElement postRpc(String host, String body) {
        return HttpReq.post(host + "/jsonrpc")
                .body(body)
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonElement error = jsonObject == null ? null : jsonObject.get("error");
                    if (error != null && !error.isJsonNull()) {
                        String message = error.isJsonObject() && error.getAsJsonObject().has("message")
                                ? error.getAsJsonObject().get("message").getAsString()
                                : String.valueOf(error);
                        throw new RuntimeException("aria2 JSON-RPC 错误: " + message);
                    }
                    JsonElement result = jsonObject == null ? null : jsonObject.get("result");
                    return result == null || result.isJsonNull() ? null : result;
                });
    }

    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        List<TorrentsInfo> torrentsInfos = new ArrayList<>();
        // (A4) 原 sleep(1000) 位于调用方(TorrentUtil.getTorrentsInfos)类锁内, 已删除
        try {
            torrentsInfos.addAll(getTorrentsInfos("aria2/tellActive.json"));
            torrentsInfos.addAll(getTorrentsInfos("aria2/tellWaiting.json"));
            torrentsInfos.addAll(getTorrentsInfos("aria2/tellStopped.json"));
        } catch (Exception e) {
            // 保持原语义: 单批失败记录日志, 返回已获取的部分(不整体上抛)
            log.error(e.getMessage(), e);
        }
        return torrentsInfos;
    }

    /**
     * 批量查询(内部): 走统一 JSON-RPC 处理
     */
    private List<TorrentsInfo> getTorrentsInfos(String type) {
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        String body = ResourceUtil.readUtf8Str(type);
        body = StrFormatter.format(body, password);
        List<TorrentsInfo> torrentsInfos = new ArrayList<>();
        JsonElement result = postRpc(host, body);
        if (result == null || !result.isJsonArray()) {
            return torrentsInfos;
        }
        List<JsonElement> list = result.getAsJsonArray().asList();
        for (JsonElement jsonElement : list) {
            JsonObject asJsonObject = jsonElement.getAsJsonObject();
            JsonElement bittorrent = asJsonObject.get("bittorrent");
            if (Objects.isNull(bittorrent) || bittorrent.isJsonNull()) {
                continue;
            }
            JsonElement info = bittorrent.getAsJsonObject()
                    .get("info");
            if (Objects.isNull(info)) {
                continue;
            }
            String name = info.getAsJsonObject()
                    .get("name").getAsString();
            String infoHash = asJsonObject.get("infoHash").getAsString();
            String status = asJsonObject.get("status").getAsString();
            TorrentsInfo.State state = "complete".equals(status) ?
                    TorrentsInfo.State.pausedUP : TorrentsInfo.State.downloading;
            String dir = asJsonObject.get("dir").getAsString();
            String gid = asJsonObject.get("gid").getAsString();

            List<String> files = asJsonObject.get("files")
                    .getAsJsonArray()
                    .asList()
                    .stream().map(JsonElement::getAsJsonObject)
                    .map(o -> o.get("path").getAsString())
                    .toList();

            long size = asJsonObject.get("totalLength").getAsLong();
            long completed = asJsonObject.get("completedLength").getAsLong();

            TorrentsInfo torrentsInfo = new TorrentsInfo();
            torrentsInfo
                    .progress(completed, size)
                    .setTags(List.of())
                    .setId(gid)
                    .setName(name)
                    .setHash(infoHash)
                    .setState(state)
                    .setDownloadDir(FileUtils.getAbsolutePath(dir))
                    .setFiles(() -> files);
            torrentsInfos.add(torrentsInfo);
        }
        return torrentsInfos;
    }


    @Override
    public Boolean download(Ani ani, Item item, String savePath, File torrentFile) {
        String name = item.getReName();
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        String body;

        String extName = FileUtil.extName(torrentFile);
        if (StrUtil.isBlank(extName)) {
            return false;
        }

        if ("txt".equals(extName)) {
            log.error("Aria2 暂不支持磁力链接下载与重命名");
            return false;
        } else {
            body = ResourceUtil.readUtf8Str("aria2/addTorrent.json");
            body = StrFormatter.format(body, password, Base64.encode(torrentFile), savePath);
        }

        // (E3) 走统一 JSON-RPC 处理: add 的 result 为 gid; result 缺失/为 null 时判失败
        JsonElement result = postRpc(host, body);
        String id = result == null ? null : result.getAsString();

        log.info("aria2 添加下载 => name: {} id: {}", name, id);

        if (StrUtil.isBlank(id)) {
            log.error("aria2 添加下载未返回 gid {}", name);
            return false;
        }

        Boolean ova = ani.getOva();
        boolean v2 = RenameUtil.isNamingV2(ani);
        if (!ova || v2) {
            RenameCacheUtil.put(id, name);
        }

        // addTorrent 返回 gid 即已入队，无需 3×10s 轮询确认；状态由 RenameTask 周期性兜底
        return true;
    }

    @Override
    public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        String id = torrentsInfo.getId();
        String body = ResourceUtil.readUtf8Str("aria2/removeDownloadResult.json");
        body = StrFormatter.format(body, password, id);

        try {
            // (E3) delete 同样判 error 成员: 出错(含任务不存在)返回 false, 不再假成功
            JsonElement result = postRpc(host, body);
            return result != null;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return false;
        }
    }

    @Override
    public Boolean rename(TorrentsInfo torrentsInfo) {
        String id = torrentsInfo.getId();
        String downloadDir = torrentsInfo.getDownloadDir();
        TorrentsInfo.State state = torrentsInfo.getState();

        if (Objects.isNull(state)) {
            return false;
        }

        // 仅支持下载完成后重命名
        if (!state.name().equals(TorrentsInfo.State.pausedUP.name())) {
            return false;
        }

        String reName = RenameCacheUtil.get(id);
        if (StrUtil.isBlank(reName)) {
            log.debug("未获取到重命名 => id: {}", id);
            return false;
        }

        List<File> files = torrentsInfo.getFiles().get()
                .stream()
                .map(File::new)
                .filter(File::exists)
                .filter(file -> {
                    String extName = FileUtil.extName(file);
                    if (StrUtil.isBlank(extName)) {
                        return false;
                    }
                    if (file.length() < 1) {
                        return false;
                    }
                    return FileUtils.isVideoFormat(extName) || FileUtils.isSubtitleFormat(extName);
                })
                .sorted(Comparator.comparingLong(file -> Long.MAX_VALUE - file.length()))
                .toList();

        if (files.isEmpty()) {
            // (E3) 映射路径下无可用文件: 一次性告警并返回 false, 不再抛异常(原 Assert 会触发 RenameTask 无限重试)
            log.warn("映射路径存在错误, 无法重命名(无可用文件) id={} dir={}", id, downloadDir);
            return false;
        }

        // 统计视频文件数量，判断是否为多文件合集
        long videoCount = files.stream()
                .filter(f -> FileUtils.isVideoFormat(FileUtil.extName(f.getName())))
                .count();
        boolean isMultiFile = videoCount > 1;

        int attempted = 0;
        int failed = 0;
        for (File src : files) {
            String name = src.getName();
            String ext = FileUtil.extName(name);
            boolean isSub = FileUtils.isSubtitleFormat(ext);

            String fileReName;
            if (isMultiFile) {
                fileReName = getFileReNameMulti(name, reName, isSub);
            } else {
                fileReName = getFileReName(name, reName);
            }

            File newPath = new File(downloadDir + "/" + fileReName);
            if (FileUtil.equals(src, newPath)) {
                continue;
            }
            attempted++;
            try {
                // (E3) 单文件移动失败仅跳过该文件, 不中断整个重命名
                FileUtil.move(src, newPath, false);
                log.info("重命名 {} ==> {}", name, newPath);
            } catch (Exception e) {
                failed++;
                log.warn("重命名失败 {} ==> {}: {}", name, newPath, ExceptionUtils.getMessage(e));
            }
        }
        if (attempted > 0 && failed == attempted) {
            // (E3) 全部文件移动失败: 视为重命名失败(返回 false), 保留缓存供下轮重试
            log.error("重命名全部失败 id={} dir={}", id, downloadDir);
            return false;
        }
        RenameCacheUtil.remove(id);

        return true;
    }

    @Override
    public Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        return false;
    }

    @Override
    public void updateTrackers(Set<String> trackers) {
        String trackersStr = CollUtil.join(trackers, "\\n");
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        String body = ResourceUtil.readUtf8Str("aria2/changeGlobalOption.json");
        body = StrFormatter.format(body, password, trackersStr);

        HttpReq.post(host + "/jsonrpc")
                .body(body)
                .then(res -> {
                    if (res.isOk()) {
                        log.info("Aria2 更新Trackers完成 共{}条", trackers.size());
                        return;
                    }
                    log.error("Aria2 更新Trackers失败 {}", res.getStatus());
                });
    }

    @Override
    public void setSavePath(TorrentsInfo torrentsInfo, String path) {
        // api 不支持
    }
}
