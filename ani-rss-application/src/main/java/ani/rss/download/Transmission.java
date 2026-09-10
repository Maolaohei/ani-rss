package ani.rss.download;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentsInfo;
import ani.rss.entity.web.Header;
import ani.rss.enums.TorrentsTags;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.basic.RenameCacheUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.RenameUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transmission
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Transmission implements BaseDownload {
    /**
     * (E2) RPC 会话按 host 隔离: key=host, value=[sessionId, authorization]。
     * 会话状态不再承载在单例 bean 的实例字段上, 测试登录(test=true)只写自己 host 的会话,
     * 不再覆盖/偷换其它 host 的运行中会话。
     */
    private static final ConcurrentHashMap<String, String[]> SESSIONS = new ConcurrentHashMap<>();

    /**
     * (E2) 当前运行配置对应的下载器 host
     */
    private static String currentHost() {
        return ConfigUtil.CONFIG.getDownloadToolHost();
    }

    /**
     * (E2) 当前 host 的会话(缺失时按当前配置凭据惰性创建, sessionId 由 409 握手补齐)
     */
    private static String[] currentSession() {
        String host = currentHost();
        String[] session = SESSIONS.get(host);
        if (session == null) {
            Config config = ConfigUtil.CONFIG;
            session = new String[]{"", StrFormatter.format("Basic {}",
                    Base64.encode(config.getDownloadToolUsername() + ":" + config.getDownloadToolPassword()))};
            SESSIONS.put(host, session);
        }
        return session;
    }

    @Override
    public Boolean login(Boolean test, Config config) {
        String username = config.getDownloadToolUsername();
        String password = config.getDownloadToolPassword();
        String loginHost = config.getDownloadToolHost();

        if (StrUtil.isBlank(loginHost) || StrUtil.isBlank(username)
                || StrUtil.isBlank(password)) {
            log.warn("Transmission 未配置完成");
            return false;
        }

        String authorization = StrFormatter.format("Basic {}", Base64.encode(username + ":" + password));
        Boolean isOk = HttpReq.get(loginHost)
                .header(Header.AUTHORIZATION, authorization)
                .thenFunction(HttpResponse::isOk);
        if (!isOk) {
            log.error("登录 Transmission 失败");
            return false;
        }
        // (E2) 仅注册/更新本 host 的会话, 不影响其它 host
        String[] session = new String[]{"", authorization};
        SESSIONS.put(loginHost, session);
        try {
            // 预热: 通过 409 握手获取 session id(原实现经 getTorrentsInfos 建立会话)
            rpc(session, loginHost, ResourceUtil.readUtf8Str("transmission/torrent-get.json"), 0);
        } catch (Exception e) {
            // 与原实现一致: 会话预热失败不阻断登录, 交由后续 RPC 的 409 握手自愈
            log.warn("Transmission 会话预热失败(将由后续 RPC 自愈): {}", ExceptionUtils.getMessage(e));
        }
        return true;
    }

    /**
     * (E2) 统一 RPC 调用封装: 发送请求 → 若 409 则读取 X-Transmission-Session-Id
     * 更新该 host 的 sessionId 后重发(重试上限 1 次) → 校验 HTTP 状态并解析 JSON。
     * 原 getTorrentsInfos 的裸递归(无深度上限)由这里的固定 1 次重试取代。
     */
    private JsonObject rpc(String body) {
        return rpc(currentSession(), currentHost(), body, 0);
    }

    /**
     * (E2) 统一 RPC 调用封装(自定义超时)
     */
    private JsonObject rpc(String body, int timeoutMs) {
        return rpc(currentSession(), currentHost(), body, timeoutMs);
    }

    private JsonObject rpc(String[] session, String host, String body, int timeoutMs) {
        HttpResponse res = sendRpc(host, session[1], session[0], body, timeoutMs);
        if (res.getStatus() == 409) {
            // 409: 会话 id 失效(TR 重启等), 读取响应头里的新 id 后重发一次
            String newSessionId = res.header("X-Transmission-Session-Id");
            res.close();
            if (StrUtil.isBlank(newSessionId)) {
                throw new IllegalStateException("Transmission RPC 409 且未返回新会话ID");
            }
            SESSIONS.put(host, new String[]{newSessionId, session[1]});
            res = sendRpc(host, session[1], newSessionId, body, timeoutMs);
        }
        try {
            HttpReq.assertStatus(res);
            return GsonStatic.fromJson(res.body(), JsonObject.class);
        } finally {
            res.close();
        }
    }

    private HttpResponse sendRpc(String host, String authorization, String sessionId, String body, int timeoutMs) {
        HttpRequest req = HttpReq.post(host + "/transmission/rpc")
                .header(Header.AUTHORIZATION, authorization)
                .header("X-Transmission-Session-Id", sessionId)
                .body(body);
        if (timeoutMs > 0) {
            req.timeout(timeoutMs);
        }
        return req.execute();
    }

    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        String body = ResourceUtil.readUtf8Str("transmission/torrent-get.json");
        try {
            JsonObject jsonObject = rpc(body);
            List<TorrentsInfo> torrentsInfos = new ArrayList<>();
            JsonArray torrents = jsonObject.get("arguments")
                    .getAsJsonObject()
                    .get("torrents")
                    .getAsJsonArray();
            for (JsonElement jsonElement : torrents.asList()) {
                JsonObject item = jsonElement.getAsJsonObject();
                List<String> tags = item.get("labels").getAsJsonArray()
                        .asList().stream().map(JsonElement::getAsString)
                        .toList();
                if (!tags.contains(TorrentsTags.ANI_RSS.getValue())) {
                    continue;
                }
                List<String> files = item.get("files").getAsJsonArray().asList()
                        .stream().map(JsonElement::getAsJsonObject)
                        .map(o -> o.get("name").getAsString())
                        .toList();

                // 状态： https://github.com/jayzcoder/TrguiNG/blob/zh/src/rpc/transmission.ts
                // 0=停止 1=校验等待 2=校验中 3=下载等待 4=下载中 5=做种等待 6=做种中
                // 此前只映射 3 种，导致「手动暂停 / 出错停止」也被显示成 downloading
                int statusCode = item.get("status").getAsInt();
                boolean finished = item.get("isFinished").getAsBoolean();

                TorrentsInfo.State state;
                if (finished) {
                    state = statusCode == 5 ? TorrentsInfo.State.queuedUP : TorrentsInfo.State.pausedUP;
                } else {
                    switch (statusCode) {
                        case 0 -> state = TorrentsInfo.State.pausedDL;
                        case 1, 2 -> state = TorrentsInfo.State.checkingResumeData;
                        case 3 -> state = TorrentsInfo.State.queuedDL;
                        case 5 -> state = TorrentsInfo.State.queuedUP;
                        default -> state = TorrentsInfo.State.downloading;
                    }
                }

                String downloadDir = item.get("downloadDir").getAsString();
                long size = item.get("totalSize").getAsLong();
                long completed = item.get("haveValid").getAsLong();

                Long rateDownload = getJsonLong(item, "rateDownload");
                Long etaSeconds = getJsonLong(item, "eta");

                TorrentsInfo torrentsInfo = new TorrentsInfo();
                torrentsInfo.progress(completed, size)
                        .speed(
                                rateDownload == null ? 0L : rateDownload,
                                etaSeconds == null || etaSeconds < 0 ? null : etaSeconds * 1000L
                        )
                        .setName(item.get("name").getAsString())
                        .setTags(tags)
                        .setHash(item.get("hashString").getAsString())
                        .setState(state)
                        .setId(item.get("id").getAsString())
                        .setDownloadDir(FileUtils.getAbsolutePath(downloadDir))
                        .setFiles(() -> files);
                torrentsInfos.add(torrentsInfo);
            }
            return torrentsInfos;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return new ArrayList<>();
    }

    @Override
    public Boolean download(Ani ani, Item item, String savePath, File torrentFile) {
        String name = item.getReName();
        String body = ResourceUtil.readUtf8Str("transmission/torrent-add.json");
        String extName = FileUtil.extName(torrentFile);
        if (StrUtil.isBlank(extName)) {
            return false;
        }

        List<String> tags = newTags(ani, item);

        String torrent;
        if ("txt".equals(extName)) {
            torrent = FileUtil.readUtf8String(torrentFile);
            body = StrFormatter.format(body, GsonStatic.toJson(tags), savePath, "", torrent);
        } else {
            if (torrentFile.length() > 0) {
                torrent = Base64.encode(torrentFile);
                body = StrFormatter.format(body, GsonStatic.toJson(tags), savePath, torrent, "");
            } else {
                torrent = "magnet:?xt=urn:btih:" + FileUtil.mainName(torrentFile);
                body = StrFormatter.format(body, GsonStatic.toJson(tags), savePath, "", torrent);
            }
        }

        // (E2) 走统一 rpc 封装: 409 握手由封装处理; 原 download 请求的 60s 超时保持不变
        JsonObject jsonObject = rpc(body, 1000 * 60);
        JsonObject arguments = jsonObject == null ? null : jsonObject.getAsJsonObject("arguments");
        if (arguments == null || !arguments.has("torrent-added")) {
            log.error("Transmission 添加任务失败: {}", jsonObject);
            return false;
        }
        String id = arguments.getAsJsonObject("torrent-added")
                .get("id").getAsString();

        if (StrUtil.isBlank(id)) {
            return false;
        }

        log.info("tr 添加下载 => name: {} id: {}", name, id);

        Boolean ova = ani.getOva();
        boolean v2 = RenameUtil.isNamingV2(ani);
        if (!ova || v2) {
            RenameCacheUtil.put(id, name);
        }

        // torrent-added 返回 id 即已入队，无需 3×10s 轮询确认；状态由 RenameTask 周期性兜底
        return true;
    }

    @Override
    public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
        String body = ResourceUtil.readUtf8Str("transmission/torrent-remove.json");
        body = StrFormatter.format(body, torrentsInfo.getId(), deleteFiles);
        try {
            // (E2) 走统一 rpc 封装, 409 由封装处理
            JsonObject jsonObject = rpc(body);
            return jsonObject != null;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return false;
        }
    }

    @Override
    public Boolean rename(TorrentsInfo torrentsInfo) {
        String id = torrentsInfo.getId();
        String name = torrentsInfo.getName();

        if (ReUtil.contains("^\\w{40}$", name)) {
            log.debug("{} 磁力链接还在获取原数据中", name);
            return false;
        }

        String reName = RenameCacheUtil.get(id);
        if (StrUtil.isBlank(reName)) {
            log.debug("未获取到重命名 => id: {}", id);
            return false;
        }

        // TR 的 rename 只作用于种子根（多文件种子=顶层目录）。
        // 种子名可能是目录名：目录名常含点（如 Show.Vol.01），不能对「目录名」取 extName 追加后缀，
        // 否则目录会被改名为「标题 S01E01.01」。仅当根是文件（单文件种子）且扩展名真实时才追加。
        String extName = FileUtil.extName(name);
        if (StrUtil.isNotBlank(extName)
                && (FileUtils.isVideoFormat(extName) || FileUtils.isSubtitleFormat(extName))) {
            reName = reName + "." + extName;
        }

        String body = ResourceUtil.readUtf8Str("transmission/torrent-rename-path.json");
        body = StrFormatter.format(body, id, name, reName);

        log.info("重命名 {} ==> {}", name, reName);

        boolean ok = false;
        try {
            // (E2) 走统一 rpc 封装, 409 由封装处理
            rpc(body);
            ok = true;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        Assert.isTrue(ok, "重命名失败 {} ==> {}", name, reName);
        RenameCacheUtil.remove(id);

        for (int i = 0; i < 10; i++) {
            ThreadUtil.sleep(1000);
            Optional<TorrentsInfo> first = getTorrentsInfos().stream()
                    .filter(info -> info.getId().equals(id))
                    .findFirst();
            if (first.isEmpty()) {
                break;
            }
            if (first.get().getName().equals(reName)) {
                return true;
            }
        }

        log.warn("重命名貌似出现了问题？{}", reName);
        return false;
    }

    @Override
    public Boolean addTags(TorrentsInfo torrentsInfo, String tag) {
        String id = torrentsInfo.getId();
        List<String> tags = torrentsInfo.getTags();
        List<String> strings = new ArrayList<>(tags);
        strings.add(tag);

        String body = ResourceUtil.readUtf8Str("transmission/torrent-set.json");
        body = StrFormatter.format(body, GsonStatic.toJson(strings), id);
        try {
            // (E2) 走统一 rpc 封装, 409 由封装处理
            rpc(body);
            return true;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void updateTrackers(Set<String> trackers) {
        log.info("Transmission暂时还不支持 自动更新Trackers");
    }

    @Override
    public void setSavePath(TorrentsInfo torrentsInfo, String path) {
        String id = torrentsInfo.getId();
        String body = ResourceUtil.readUtf8Str("transmission/torrent-set-location.json");
        body = StrFormatter.format(body, id, path);
        try {
            // (E2) 走统一 rpc 封装, 409 由封装处理
            rpc(body);
        } catch (Exception e) {
            log.error("Transmission 修改保存位置失败 {}: {}", id, e.getMessage());
        }
    }

    /**
     * 取数值字段：缺失/null 或格式非法返回 null，由调用方决定默认值。
     * 速度/剩余时间属“有则更好”的展示字段，缺失不应让整个列表失败。
     */
    private static Long getJsonLong(JsonObject jsonObject, String key) {
        JsonElement el = jsonObject.get(key);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        try {
            return el.getAsLong();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
