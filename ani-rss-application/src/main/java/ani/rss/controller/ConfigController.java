package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.auth.ViewerPolicy;
import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.MavenUtils;
import ani.rss.commons.URLUtils;
import ani.rss.config.CronConfig;
import ani.rss.download.BaseDownload;
import ani.rss.entity.Config;
import ani.rss.entity.Global;
import ani.rss.entity.ProxyTest;
import ani.rss.entity.web.ContentType;
import ani.rss.entity.web.Header;
import ani.rss.entity.web.Result;
import ani.rss.entity.web.ResultCode;
import ani.rss.service.ClearService;
import ani.rss.service.TaskService;
import ani.rss.start.BaseStart;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.AfdianUtil;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.MagnetTorrentUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.ClassUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.core.util.ZipUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.http.HttpRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Cleanup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ConfigController extends BaseController {

    private final CronConfig cronConfig;

    @Resource
    private TaskService taskService;

    @Resource
    private ClearService clearService;

    /**
     * 构建信息
     */
    public String buildInfo() {
        String buildInfo = "";
        try {
            buildInfo = ResourceUtil.readUtf8Str("build.info");
        } catch (Exception ignored) {
        }
        return buildInfo;
    }

    @Auth
    @Operation(summary = "获取设置")
    @PostMapping("/config")
    public Result<Config> config() {
        String version = MavenUtils.getVersion();
        String buildInfo = buildInfo();
        Config config = ObjectUtil.clone(ConfigUtil.CONFIG);
        config.getLogin().setPassword("");
        // 只读令牌脱敏：/config 在只读白名单内(前端启动依赖它读取展示类配置)，
        // 但响应里带着 apiKey 等管理凭据。若不抹掉，只读者可拿 apiKey 去调 /setConfig 完成提权。
        // 脱敏按字段名匹配，后续新增 xxxToken 之类字段会自动被覆盖，不会漏。
        if (ViewerPolicy.isViewerRequest(Global.REQUEST.get())) {
            ViewerPolicy.sanitizeCredentials(config);
            config.getLogin().setKey("");
        }
        config.setVersion(version)
                .setBuildInfo(buildInfo)
                .setVerifyExpirationTime(AfdianUtil.verifyExpirationTime());
        return Result.success(config);
    }

    @Auth
    @Operation(summary = "修改设置")
    @PostMapping("/setConfig")
    public Result<Void> setConfig(@RequestBody Config newConfig) {
        // 保存旧值用于变更对比(此后 CONFIG 会被整体原子替换, 旧引用不再被修改)
        Config oldConfig = ConfigUtil.CONFIG;
        Config config = oldConfig;
        Integer renameSleepSeconds = config.getRenameSleepSeconds();
        Integer sleep = config.getRssSleepMinutes();
        String download = config.getDownloadToolType();
        Boolean autoStart = config.getAutoStart();
        String networkPrefer = config.getNetworkPrefer();

        boolean saved;
        try {
            saved = ConfigUtil.updateFromApi(newConfig);
        } catch (IllegalArgumentException e) {
            // 合并后的参数校验未通过(如代理参数不完整), 配置未被修改
            return Result.error(e.getMessage());
        }
        if (!saved) {
            return Result.error("保存失败,请检查磁盘/权限");
        }

        // 重新读取交换后的新配置快照
        config = ConfigUtil.CONFIG;

        Integer newRenameSleepSeconds = config.getRenameSleepSeconds();
        Integer newSleep = config.getRssSleepMinutes();
        Boolean newAutoStart = config.getAutoStart();

        // 时间间隔发生改变，重启任务
        if (
                !Objects.equals(newSleep, sleep) ||
                        !Objects.equals(newRenameSleepSeconds, renameSleepSeconds)
        ) {
            taskService.restart();
        }
        // 下载工具发生改变
        if (!download.equals(config.getDownloadToolType())) {
            TorrentUtil.load();
        }

        // F6-4 配置变更联动：downloadPathTemplate / ovaDownloadPathTemplate / rename /
        // fileExist / downloadToolType 决定"本地状态判定"的输入或口径，改了必须让缓存作废，
        // 否则用户改完设置仍会看到旧结果（最长 stateCacheTtlDays = 90 天才自然过期）。
        //
        // 注意这里只补媒体库缓存：DownloadService.invalidateDownloadPathIndex()
        // （内部含 LocalStateCache.invalidateAll()）已由 ConfigUtil.syncChecked() 在每次保存时
        // 无条件执行，重复调用没有意义；而媒体库缓存只认 LibraryController.invalidate()，
        // 不跟着配置走——它才是真正会"改了设置却还显示旧内容"的那一处。
        if (ConfigUtil.localStateInputChanged(oldConfig, config)) {
            LibraryController.invalidate();
            log.info("配置变更影响本地状态判定，已失效媒体库缓存");
        }
        // 网络协议发生改变，更新 systemd 服务配置并重启
        String newNetworkPrefer = config.getNetworkPrefer();
        boolean networkChanged = !Objects.equals(networkPrefer, newNetworkPrefer);
        if (networkChanged) {
            applyNetworkPrefer(newNetworkPrefer);
        }
        // 开机自启发生改变
        if (!newAutoStart.equals(autoStart)) {
            if (BaseStart.isSupported()) {
                BaseStart instance = BaseStart.getInstance();
                instance.sync();
            }
        }

        if (networkChanged) {
            return Result.success("网络协议设置已保存，请手动重启服务: systemctl restart ani-rss 或 ani-rss restart");
        }
        return Result.success("修改成功");
    }

    @Auth
    @Operation(summary = "清理缓存")
    @PostMapping("/clearCache")
    public Result<Void> clearCache() {
        File configDir = ConfigUtil.getConfigDir();
        String configDirStr = FileUtils.getAbsolutePath(configDir);

        Long size = clearService.clearCover()
                // 磁力元数据缓存（合集添加磁力链接时抓取的 .torrent）
                + MagnetTorrentUtil.clearCache();

        // 清理 mikan 预览封面
        FileUtil.del(configDirStr + "/img");

        String formatSize = FileUtils.formatSize(size, true);

        return Result.success("清理完成, 共清理 {}", formatSize);
    }

    @Auth
    @Operation(summary = "更新trackers")
    @PostMapping("/trackersUpdate")
    public Result<Void> trackersUpdate(@RequestBody Config config) {
        cronConfig.updateTrackers(config);
        return Result.success();
    }

    @Auth
    @Operation(summary = "代理测试")
    @PostMapping("/testProxy")
    public Result<ProxyTest> testProxy(@RequestParam("url") String url, @RequestBody Config config) {
        url = Base64.decodeStr(url);

        log.info(url);

        // P1: SSRF 防护（与 /proxyImage 同规则）+ 5s 超时 + 64KB 截断。
        try {
            URLUtils.verify(url);
            URLUtils.verifyResolveAll(URLUtil.url(url).getHost());
        } catch (Exception e) {
            return Result.<ProxyTest>error("非法的代理测试地址: " + e.getMessage());
        }

        HttpRequest httpRequest = HttpReq.get(url, 5000);
        HttpReq.setProxy(httpRequest, config);

        ProxyTest proxyTest = new ProxyTest();
        Result<ProxyTest> result = Result.success(proxyTest);

        long start = LocalDateTimeUtil.toEpochMilli(LocalDateTimeUtil.now());
        try {
            httpRequest
                    .then(res -> {
                        int status = res.getStatus();
                        proxyTest.setStatus(status);

                        String body = res.body();
                        // 代理测试只取 <title>，截断到 64KB 后再解析，防止超大响应打爆内存
                        if (body != null && body.length() > 65536) {
                            body = body.substring(0, 65536);
                        }
                        String title = Jsoup.parse(body)
                                .title();
                        proxyTest.setTitle(title);
                    });
        } catch (Exception e) {
            result.setMessage(e.getMessage())
                    .setCode(ResultCode.HTTP_INTERNAL_ERROR);
        }

        long end = LocalDateTimeUtil.toEpochMilli(LocalDateTimeUtil.now());
        proxyTest.setTime(end - start);
        return result;
    }

    @Auth
    @Operation(summary = "下载器测试")
    @PostMapping("/downloadLoginTest")
    public Result<Void> downloadLoginTest(@RequestBody Config config) {
        ConfigUtil.format(config);
        String download = config.getDownloadToolType();
        // 兼容旧配置：Alist 已迁移为 OpenList
        if ("Alist".equals(download)) {
            download = "OpenList";
        }
        // 白名单校验：请求体可控的下载器类型不得反射加载任意类
        Set<String> allowedTools = Set.of("qBittorrent", "Transmission", "Aria2", "OpenList");
        Assert.isTrue(allowedTools.contains(download), "不支持的下载工具类型: " + download);
        Class<BaseDownload> loadClass = ClassUtil.loadClass("ani.rss.download." + download);
        BaseDownload baseDownload = SpringUtil.getBean(loadClass);
        Boolean login = baseDownload.login(true, config);
        if (login) {
            return Result.success("登录成功");
        }
        if ("qBittorrent".equalsIgnoreCase(download)) {
            return Result.error("登录失败：请检查 ApiKey（≥5.2，填密码栏）或用户名密码（≤5.1）是否正确。");
        }
        if ("OpenList".equalsIgnoreCase(download) || "Alist".equalsIgnoreCase(download)) {
            return Result.error("登录失败：请检查 Host、Token 与保存位置/临时目录配置。");
        }
        if ("Transmission".equalsIgnoreCase(download)) {
            return Result.error("登录失败：请检查地址是否带 /transmission/rpc、用户名密码是否正确，以及是否开启「禁止公网访问」导致本机以外的地址被拒。");
        }
        if ("Aria2".equalsIgnoreCase(download)) {
            return Result.error("登录失败：请检查 JsonRPC 地址（通常为 http://host:6800/jsonrpc）与 RPC 密钥是否正确。");
        }
        return Result.error("登录失败：请检查下载器地址、账号与网络连通性。");
    }

    @Auth
    @Operation(summary = "自定义JS")
    @GetMapping("/custom.js")
    public void customJs() throws IOException {
        HttpServletResponse response = Global.RESPONSE.get();
        setCacheControl(response, 0);

        String customJs = ConfigUtil.CONFIG.getCustomJs();
        customJs = StrUtil.blankToDefault(customJs, "// empty js");

        write(200, ContentType.JAVASCRIPT, customJs);
    }

    @Auth
    @Operation(summary = "自定义CSS")
    @GetMapping("/custom.css")
    public void customCss() throws IOException {
        HttpServletResponse response = Global.RESPONSE.get();
        setCacheControl(response, 0);

        String customCss = ConfigUtil.CONFIG.getCustomCss();
        customCss = StrUtil.blankToDefault(customCss, "/* empty css */");

        write(200, ContentType.TEXT_CSS, customCss);
    }

    @Auth
    @Operation(summary = "导出设置")
    @GetMapping("/exportConfig")
    public void backupConfig() throws IOException {
        String version = MavenUtils.getVersion();
        String filename = StrUtil.format("ani-rss.backup.{}.zip", version);

        String contentType = getContentType(filename);

        HttpServletResponse response = Global.RESPONSE.get();

        response.setContentType(contentType);
        response.setHeader(Header.CONTENT_DISPOSITION, StrFormatter.format("inline; filename=\"{}\"", filename));

        @Cleanup
        OutputStream outputStream = response.getOutputStream();

        ConfigUtil.backup(outputStream);
    }

    @Auth
    @Operation(summary = "导入设置")
    @PostMapping(value = "/importConfig", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Void> importConfig(@RequestParam("file") MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String extName = FileUtil.extName(originalFilename);
        Assert.isTrue("zip".equals(extName), "导入格式异常");

        File configDir = ConfigUtil.getConfigDir();

        // 白名单校验：备份包只允许已知文件，拒绝 .. / 绝对路径 / 未知文件（防配置投毒与 zip-slip）
        // 先校验、再解压暂存、最后替换: 任何一步失败都不触碰现有配置目录
        verifyZipEntries(file.getInputStream());

        String ts = DateUtil.format(new Date(), "yyyyMMddHHmmss");
        File stagingDir = new File(configDir, "import-staging-" + ts);
        FileUtil.del(stagingDir);
        FileUtil.mkdir(stagingDir);

        try {
            @Cleanup
            InputStream inputStream = file.getInputStream();
            ZipUtil.unzip(inputStream, stagingDir, StandardCharsets.UTF_8);

            // 校验暂存目录内容非空
            File[] stagedItems = stagingDir.listFiles();
            Assert.isTrue(stagedItems != null && stagedItems.length > 0, "备份包内容为空");

            // 解压后二次校验：实际落盘大小超 200MB 则清 staging 并拒绝（防 entry.getSize 伪造）
            long stagedSize = FileUtil.size(stagingDir);
            if (stagedSize > 200L * 1024 * 1024) {
                FileUtil.del(stagingDir);
                throw new IllegalArgumentException("备份包解压后过大(>200MB)，已拒绝导入");
            }

            // P0-4：两阶段提交——torrents 改名备份而非直接删除，全量 move 成功后再删 bak；
            // 任一步失败回滚 bak，避免"已删旧种子 + 新配置半截"的永久丢失窗口。
            File torrentsDir = new File(configDir, "torrents");
            File torrentsBak = new File(configDir, "torrents.bak-" + ts);
            boolean hasTorrentsBak = false;
            if (torrentsDir.exists()) {
                FileUtil.move(torrentsDir, torrentsBak, true);
                hasTorrentsBak = true;
            }
            try {
                for (File item : stagedItems) {
                    FileUtil.move(item, configDir, true);
                }
                // 全成功才删 bak
                if (hasTorrentsBak) {
                    FileUtil.del(torrentsBak);
                }
            } catch (Exception moveEx) {
                // 回滚：清掉已搬入的半截文件（仅本次 staged 的顶层项），恢复 torrents
                for (File item : stagedItems) {
                    File moved = new File(configDir, item.getName());
                    // 只删本次搬入且与暂存同名的顶层项，避免误删用户原有文件
                    if (moved.exists() && !moved.equals(stagingDir)) {
                        try {
                            FileUtil.del(moved);
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (hasTorrentsBak && !torrentsDir.exists()) {
                    try {
                        FileUtil.move(torrentsBak, torrentsDir, true);
                    } catch (Exception rollbackEx) {
                        moveEx.addSuppressed(rollbackEx);
                    }
                }
                throw moveEx;
            }
        } catch (Exception e) {
            // 任一步失败: 清理暂存目录并上抛, 现有目录保持原状(种子记录已删的窗口仅在校验通过后)
            FileUtil.del(stagingDir);
            throw e;
        } finally {
            FileUtil.del(stagingDir);
        }

        // 重新加载设置
        ConfigUtil.load();
        AniUtil.load();
        taskService.restart();

        return Result.success("导入成功");
    }

    @Operation(summary = "存活测试")
    @RequestMapping("/ping")
    public Result<Void> ping() {
        return Result.success();
    }

    /**
     * 校验备份 zip 的条目：仅允许备份白名单内的文件，拒绝绝对路径与 .. 穿越（zip-slip）
     */
    private void verifyZipEntries(InputStream inputStream) {
        // 导入包总字节上限：防 zip bomb 把磁盘打满（累计未压缩大小超限即拒）
        final long maxTotalBytes = 200L * 1024 * 1024;
        try (ZipInputStream zipIn = new ZipInputStream(inputStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            boolean hasEntry = false;
            int entryCount = 0;
            long totalBytes = 0L;
            byte[] buf = new byte[8192];
            while ((entry = zipIn.getNextEntry()) != null) {
                // 防 zip bomb：条目数上限
                Assert.isTrue(++entryCount <= 10000, "备份包条目过多");
                hasEntry = true;
                String name = entry.getName();
                String normalized = name.replace('\\', '/');
                boolean pathSafe = !normalized.startsWith("/")
                        && !ReUtil.contains("(^|/)\\.\\.(/|$)", normalized);
                Assert.isTrue(pathSafe, "备份包包含非法路径: " + name);

                // 与 ConfigUtil.backup 的备份清单保持一致
                boolean allowed = normalized.equals("config.v2.json")
                        || normalized.equals("ani.v2.json")
                        || normalized.equals("database.db")
                        || normalized.startsWith("torrents/")
                        || normalized.startsWith("files/")
                        || normalized.endsWith("/");
                Assert.isTrue(allowed, "备份包包含未授权文件: " + name);

                long size = entry.getSize();
                if (size >= 0) {
                    totalBytes += size;
                } else {
                    // 未知大小：实际读取计数（ZipInputStream 下同时消耗流，校验后需调用方重取流）
                    int n;
                    while ((n = zipIn.read(buf)) != -1) {
                        totalBytes += n;
                        Assert.isTrue(totalBytes <= maxTotalBytes, "备份包过大(>200MB)，已拒绝导入");
                    }
                }
                Assert.isTrue(totalBytes <= maxTotalBytes, "备份包过大(>200MB)，已拒绝导入");
            }
            Assert.isTrue(hasEntry, "备份包为空");
        } catch (IOException e) {
            throw new RuntimeException("备份包解析失败: " + ExceptionUtils.getMessage(e), e);
        }
    }

    /**
     * 应用网络协议偏好设置 - 提示用户手动重启
     */
    private void applyNetworkPrefer(String networkPrefer) {
        log.info("网络协议已切换为: {}, 请手动重启服务", networkPrefer.isEmpty() ? "系统默认" : networkPrefer);
    }
}
