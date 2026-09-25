package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.download.BaseDownload;
import ani.rss.download.OfflineDownloader;
import ani.rss.download.OpenList;
import ani.rss.download.OpenListApi;
import ani.rss.entity.Config;
import ani.rss.entity.NotificationConfig;
import ani.rss.entity.vo.DoctorCheck;
import ani.rss.entity.web.Result;
import ani.rss.service.LocalStateCache;
import ani.rss.service.TaskService;
import ani.rss.task.RssTask;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.DiskMonitorUtil;
import ani.rss.util.other.NotificationUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ClassUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.http.HttpResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统自检（Doctor）。
 * <p>
 * 此前"配置是否通了"要分别去设置页的 5 个地方点测试（下载器 / 通知 / 代理 / IP 白名单 / Emby），
 * 用户最痛的不是"功能少"，而是"我配了但不知道哪一步断了"。
 * <p>
 * 本控制器<b>不新增任何探测逻辑</b>，只把已有能力聚合为一份"一行一件事"的清单，
 * 每行给出结论 + 证据 + 下一步建议。
 */
@Slf4j
@RestController
public class DoctorController extends BaseController {

    /**
     * 网络探测超时（毫秒）：自检是交互式操作，不能让用户等太久
     */
    private static final int PROBE_TIMEOUT_MS = 5000;

    @Auth
    @Operation(summary = "系统自检")
    @PostMapping("/doctor")
    public Result<Map<String, Object>> doctor() {
        Config config = ConfigUtil.CONFIG;
        List<DoctorCheck> checks = new ArrayList<>();

        checks.add(checkConfigDir(config));
        checks.add(checkDownloadPath(config));
        checks.add(checkDownloader(config));
        checks.add(checkDisk(config));
        checks.add(checkTmdb(config));
        checks.add(checkBgm(config));
        checks.add(checkMikan(config));
        checks.add(checkNotification(config));
        checks.add(checkTasks());
        checks.add(checkOpenListRateLimit(config));
        checks.add(checkOpenListUpstream(config));
        checks.add(checkOpenListOutcome(config));
        checks.add(checkLocalStateCache(config));

        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("ok", count(checks, "ok"));
        summary.put("warn", count(checks, "warn"));
        summary.put("fail", count(checks, "fail"));
        summary.put("skip", count(checks, "skip"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", checks);
        data.put("summary", summary);
        data.put("checkedAt", System.currentTimeMillis());
        return Result.success(data);
    }

    private static int count(List<DoctorCheck> checks, String level) {
        return (int) checks.stream().filter(c -> level.equals(c.getLevel())).count();
    }

    private DoctorCheck checkConfigDir(Config config) {
        String key = "configDir";
        String label = "配置目录可写";
        long start = System.currentTimeMillis();
        try {
            File dir = ConfigUtil.getConfigDir();
            if (!dir.exists() && !dir.mkdirs()) {
                return timed(DoctorCheck.fail(key, label,
                        "无法创建配置目录: " + dir, "检查挂载与目录权限"), start);
            }
            if (!dir.canWrite()) {
                return timed(DoctorCheck.fail(key, label,
                        "配置目录不可写: " + dir, "检查目录权限/只读挂载"), start);
            }
            return timed(DoctorCheck.ok(key, label, FileUtils.getAbsolutePath(dir)), start);
        } catch (Exception e) {
            return timed(DoctorCheck.fail(key, label, ExceptionUtils.getMessage(e), "检查目录权限"), start);
        }
    }

    private DoctorCheck checkDownloadPath(Config config) {
        String key = "downloadPath";
        String label = "下载路径";
        long start = System.currentTimeMillis();
        try {
            String template = config.getDownloadPathTemplate();
            if (StrUtil.isBlank(template)) {
                return timed(DoctorCheck.fail(key, label, "未配置下载路径模板", "在「基本设置」中填写下载路径"), start);
            }
            String staticRoot = DiskMonitorUtil.staticRoot(template);
            if (StrUtil.isBlank(staticRoot)) {
                return timed(DoctorCheck.warn(key, label,
                        "路径模板以变量开头，无法静态校验: " + template,
                        "确认模板静态前缀存在且可写"), start);
            }
            // OpenList/Alist：下载目录是网盘虚拟路径，本地 File 永远不存在。
            // 继续用本地语义会每次都报「下载根目录尚不存在」——既是噪音，
            // 又把「挂载配错」与「还没下载过」说成同一件事。
            if (RssTask.isOpenListTool(config)) {
                return checkOpenListDownloadPath(key, label, staticRoot, config, start);
            }
            File dir = new File(staticRoot);
            if (!dir.exists()) {
                return timed(DoctorCheck.warn(key, label,
                        "下载根目录尚不存在（首次下载会自动创建）: " + staticRoot,
                        "确认上级目录存在且可写"), start);
            }
            if (!dir.canWrite()) {
                return timed(DoctorCheck.fail(key, label,
                        "下载根目录不可写: " + staticRoot, "检查目录权限/只读挂载"), start);
            }
            return timed(DoctorCheck.ok(key, label, FileUtils.getAbsolutePath(dir)), start);
        } catch (Exception e) {
            return timed(DoctorCheck.fail(key, label, ExceptionUtils.getMessage(e), "检查下载路径配置"), start);
        }
    }

    /**
     * 目录探测三态（自检专用）。
     * <p>
     * 与"有没有文件"无关：{@link #MISSING} 是业务结果（目录不存在），
     * {@link #FAILED} 是"没查成"——两者混为一谈就会把网盘抖动说成"路径配错"。
     */
    enum DirProbe {
        EXISTS, MISSING, FAILED
    }

    /**
     * 一次目录探测的结果：三态 + 直接子项数（存在时）+ 失败原因（失败时）
     */
    record DirProbeResult(DirProbe probe, int children, String failure) {
        static DirProbeResult of(DirProbe probe) {
            return new DirProbeResult(probe, 0, "");
        }
    }

    /**
     * 下载路径检查结论（纯数据，便于把判定表固化下来）
     */
    record DirVerdict(String level, String detail, String suggestion) {
    }

    /**
     * OpenList 模式的分层判定。
     * <p>
     * 只用两次探测（下载前缀 / 首段挂载名）就能把三件事分开：
     * <ul>
     *   <li>目录在 → {@code ok}（并给出直接子项数）；</li>
     *   <li>目录不在、首段挂载名也不在 → {@code warn} 并给出两种可能（挂载点配错 / 目录尚未创建）。
     *       <b>刻意用 warn 而不是 fail</b>：模板首段不一定是挂载点（也可能只是用户自建目录），
     *       报 fail 会把"第一次用、什么都还没下"误判成配置错误；</li>
     *   <li>探测失败（超时/5xx/冷却中）→ {@code warn}，明确说是"没查成"而不是"没有"。</li>
     * </ul>
     * 纯函数：值就在这张表上，必须能直接测。
     */
    static DirVerdict judgeOpenListPath(String prefix, String mount,
                                       DirProbeResult prefixProbe, DirProbeResult mountProbe) {
        if (prefixProbe.probe() == DirProbe.EXISTS) {
            return new DirVerdict("ok",
                    StrUtil.format("网盘目录存在: {}（直接子项 {} 个）", prefix, prefixProbe.children()), null);
        }
        if (prefixProbe.probe() == DirProbe.FAILED) {
            return new DirVerdict("warn",
                    StrUtil.format("网盘目录探测失败（没查成，不是「没有」）: {}；原因: {}", prefix, prefixProbe.failure()),
                    "看 OpenList 自己的日志确认上游错误；常见是容器 DNS / MTU / IPv6 / 代理");
        }
        if (mountProbe == null || mountProbe.probe() == DirProbe.EXISTS) {
            return new DirVerdict("ok",
                    StrUtil.format("网盘目录尚未创建（首次下载会自动创建）: {}", prefix),
                    "确认挂载/provider 与下载位置首段一致");
        }
        if (mountProbe.probe() == DirProbe.FAILED) {
            return new DirVerdict("warn",
                    StrUtil.format("网盘目录不存在，且首段 {} 探测失败（无法确认是否配错）: {}；原因: {}",
                            mount, prefix, mountProbe.failure()),
                    "稍后重试；若持续失败，看 OpenList 自己的日志");
        }
        return new DirVerdict("warn",
                StrUtil.format("网盘目录与首段 {} 都不存在: {}", mount, prefix),
                "若首段是挂载点（如 /115 对应 115 Cloud）：检查 Driver(provider) 与下载位置首段是否一致；"
                        + "若它只是你自建的目录：首次下载会自动创建，可以忽略");
    }

    /**
     * 取路径首段（{@code /115/动漫/追番} → {@code /115}），识别不出返回 null。
     */
    static String firstSegment(String path) {
        if (StrUtil.isBlank(path)) {
            return null;
        }
        String p = path.replace('\\', '/');
        int start = p.startsWith("/") ? 1 : 0;
        int slash = p.indexOf('/', start);
        String segment = slash < 0 ? p.substring(start) : p.substring(start, slash);
        return StrUtil.isBlank(segment) ? null : "/" + segment;
    }

    /**
     * 探测一次网盘目录（不抛异常，转成三态）。
     */
    private static DirProbeResult probeDir(OfflineDownloader offline, String path) {
        try {
            return new DirProbeResult(DirProbe.EXISTS, offline.probeDirectChildren(path).size(), "");
        } catch (OpenListApi.OpenListDirNotFoundException e) {
            return DirProbeResult.of(DirProbe.MISSING);
        } catch (Exception e) {
            return new DirProbeResult(DirProbe.FAILED, 0, ExceptionUtils.getMessage(e));
        }
    }

    /**
     * OpenList/Alist 的「下载路径」检查：网盘虚拟路径必须走网盘语义。
     * <p>
     * 顺带校验云下载目录（{@code alistCloudDownloadDir}）：它不存在会让"云下载兜底归位"
     * 形同虚设；未配置时只提示"按根目录自动发现"。
     */
    private DoctorCheck checkOpenListDownloadPath(String key, String label, String prefix,
                                                 Config config, long start) {
        long coolRemain = OpenListApi.listingCooldownRemainingMs();
        if (coolRemain > 0L) {
            return timed(DoctorCheck.warn(key, label,
                    StrUtil.format("网盘接口熔断冷却中（剩余 {}s），本次跳过目录探测: {}",
                            (coolRemain + 999L) / 1000L, prefix),
                    "冷却结束后重试（冷却期内不发起任何网盘请求）"), start);
        }
        if (!(TorrentUtil.DOWNLOAD instanceof OfflineDownloader offline)) {
            return timed(DoctorCheck.skip(key, label,
                    "下载器未登录或不是 OpenList/Alist，无法探测网盘目录: " + prefix), start);
        }

        DirProbeResult prefixProbe = probeDir(offline, prefix);
        String mount = firstSegment(prefix);
        DirProbeResult mountProbe = prefixProbe.probe() == DirProbe.MISSING && mount != null
                ? probeDir(offline, mount)
                : null;
        DirVerdict verdict = judgeOpenListPath(prefix, mount, prefixProbe, mountProbe);

        // 云下载目录：可选配置；不存在会让云下载归位兜底失效
        String cloudDir = StrUtil.trimToNull(config.getAlistCloudDownloadDir());
        DirProbeResult cloudProbe = null;
        String cloudNote;
        if (cloudDir == null) {
            cloudNote = "云下载目录未配置（按根目录自动发现）";
        } else {
            cloudProbe = probeDir(offline, cloudDir);
            cloudNote = switch (cloudProbe.probe()) {
                case EXISTS -> "云下载目录存在: " + cloudDir;
                case MISSING -> "云下载目录不存在: " + cloudDir + "（云下载兜底归位会失效）";
                case FAILED -> "云下载目录探测失败（没查成）: " + cloudDir;
            };
        }

        String level = verdict.level();
        if (cloudProbe != null && cloudProbe.probe() != DirProbe.EXISTS && "ok".equals(level)) {
            level = "warn";
        }
        String suggestion = verdict.suggestion();
        if (cloudProbe != null && cloudProbe.probe() == DirProbe.MISSING) {
            suggestion = joinSuggestion(suggestion, "把「云下载目录」改为实际存在的目录（或清空以自动发现）");
        } else if (cloudProbe != null && cloudProbe.probe() == DirProbe.FAILED) {
            suggestion = joinSuggestion(suggestion, "稍后重试云下载目录探测");
        }

        String evidence = StrUtil.format("{}；{}（OpenList 模式：本地文件系统看不到该路径，已改用网盘读取）",
                verdict.detail(), cloudNote);
        return timed(checkOf(level, key, label, evidence, suggestion), start);
    }

    /**
     * 按级别构造自检项（{@code ok} 没有 suggestion 入参）。
     */
    private static DoctorCheck checkOf(String level, String key, String label,
                                       String evidence, String suggestion) {
        return switch (level) {
            case "fail" -> DoctorCheck.fail(key, label, evidence, suggestion);
            case "warn" -> DoctorCheck.warn(key, label, evidence, suggestion);
            default -> DoctorCheck.ok(key, label, evidence);
        };
    }

    private static String joinSuggestion(String a, String b) {
        if (StrUtil.isBlank(a)) {
            return b;
        }
        return a + "；" + b;
    }

    /**
     * 下载器登录：复用设置页「下载器测试」的同一条链路，保证结论一致
     */
    private DoctorCheck checkDownloader(Config config) {
        String key = "downloader";
        String label = "下载器连接";
        long start = System.currentTimeMillis();
        try {
            String download = config.getDownloadToolType();
            if (StrUtil.isBlank(download)) {
                return timed(DoctorCheck.fail(key, label, "未选择下载工具", "在「下载设置」中选择下载工具"), start);
            }
            if ("Alist".equals(download)) {
                download = "OpenList";
            }
            Set<String> allowed = Set.of("qBittorrent", "Transmission", "Aria2", "OpenList");
            if (!allowed.contains(download)) {
                return timed(DoctorCheck.fail(key, label,
                        "不支持的下载工具类型: " + download, "重新选择下载工具"), start);
            }
            Class<BaseDownload> loadClass = ClassUtil.loadClass("ani.rss.download." + download);
            BaseDownload baseDownload = SpringUtil.getBean(loadClass);
            Boolean login = baseDownload.login(true, config);
            if (Boolean.TRUE.equals(login)) {
                return timed(DoctorCheck.ok(key, label, download + " 连接正常"), start);
            }
            return timed(DoctorCheck.fail(key, label,
                    download + " 连接失败",
                    downloaderSuggestion(download)), start);
        } catch (Exception e) {
            return timed(DoctorCheck.fail(key, label, ExceptionUtils.getMessage(e), "检查下载器地址与账号"), start);
        }
    }

    private static String downloaderSuggestion(String download) {
        return switch (download) {
            case "qBittorrent" -> "检查 ApiKey（≥5.2，填密码栏）或用户名密码（≤5.1）";
            case "Transmission" -> "检查地址是否带 /transmission/rpc、账号密码，以及是否开启「禁止公网访问」";
            case "Aria2" -> "检查 JsonRPC 地址（通常 http://host:6800/jsonrpc）与 RPC 密钥";
            case "OpenList" -> "检查 Host、Token 与保存位置/临时目录配置";
            default -> "检查地址、账号与网络连通性";
        };
    }

    private DoctorCheck checkDisk(Config config) {
        String key = "disk";
        String label = "磁盘空间";
        long start = System.currentTimeMillis();
        try {
            Integer configured = config.getDiskWarnPercent();
            int warnPercent = configured == null ? 85 : Math.max(50, Math.min(99, configured));
            List<String> bad = new ArrayList<>();
            List<String> unmeasurable = new ArrayList<>();
            for (DiskMonitorUtil.Mount mount : DiskMonitorUtil.probe()) {
                if (!mount.measurable()) {
                    unmeasurable.add(mount.label());
                    continue;
                }
                if (mount.usedPercent() >= warnPercent) {
                    bad.add(StrUtil.format("{} 已用 {}%（剩余 {}）",
                            mount.label(),
                            String.format("%.1f", mount.usedPercent()),
                            DiskMonitorUtil.format(mount.usable())));
                }
            }
            if (!bad.isEmpty()) {
                return timed(DoctorCheck.fail(key, label, String.join("；", bad),
                        "清理空间或调低「磁盘预警阈值」，并确认已开启磁盘监控"), start);
            }
            String detail = unmeasurable.isEmpty()
                    ? "所有路径空间充足"
                    : "空间充足（" + String.join("、", unmeasurable) + " 为网络盘/未挂载，无法测量）";
            return timed(DoctorCheck.ok(key, label, detail), start);
        } catch (Exception e) {
            return timed(DoctorCheck.warn(key, label, ExceptionUtils.getMessage(e), null), start);
        }
    }

    private DoctorCheck checkTmdb(Config config) {
        String key = "tmdb";
        String label = "TMDB";
        long start = System.currentTimeMillis();
        if (!Boolean.TRUE.equals(config.getTmdb())) {
            return timed(DoctorCheck.skip(key, label, "未启用 TMDB"), start);
        }
        if (StrUtil.isBlank(config.getTmdbApi()) || StrUtil.isBlank(config.getTmdbApiKey())) {
            return timed(DoctorCheck.fail(key, label, "已启用 TMDB 但未填写 API 地址或 Key",
                    "在「基本设置 → TMDB」中补全 Api 与 ApiKey"), start);
        }
        return timed(DoctorCheck.ok(key, label, config.getTmdbApi()), start);
    }

    private DoctorCheck checkBgm(Config config) {
        String key = "bgm";
        String label = "Bangumi";
        long start = System.currentTimeMillis();
        String token = config.getBgmToken();
        if (StrUtil.isBlank(token)) {
            return timed(DoctorCheck.warn(key, label, "未配置 BGM Token（评分/总集数功能不可用）",
                    "在「基本设置 → Bangumi」中完成授权"), start);
        }
        return timed(DoctorCheck.ok(key, label, "Token 已配置"), start);
    }

    private DoctorCheck checkMikan(Config config) {
        String key = "mikan";
        String label = "Mikan 可达性";
        long start = System.currentTimeMillis();
        String host = config.getMikanHost();
        if (StrUtil.isBlank(host)) {
            return timed(DoctorCheck.warn(key, label, "未配置 Mikan Host", "在「基本设置」中填写 Mikan Host"), start);
        }
        try {
            // try-with-resources 关闭连接：本方法只看状态码不读 body，
            // 不关闭会泄漏连接(该端点在只读白名单内, 可被反复调用)。
            try (HttpResponse response = HttpReq.get(host, PROBE_TIMEOUT_MS).execute()) {
                int status = response.getStatus();
                if (status >= 200 && status < 400) {
                    return timed(DoctorCheck.ok(key, label, StrUtil.format("{} 返回 HTTP {}", host, status)), start);
                }
                return timed(DoctorCheck.fail(key, label,
                        StrUtil.format("{} 返回 HTTP {}", host, status),
                        "可能是 Cloudflare 拦截或站点改版，尝试配置代理"), start);
            }
        } catch (Exception e) {
            return timed(DoctorCheck.fail(key, label,
                    "无法访问 " + host + ": " + ExceptionUtils.getMessage(e),
                    "检查网络/代理设置"), start);
        }
    }

    private DoctorCheck checkNotification(Config config) {
        String key = "notification";
        String label = "通知渠道";
        long start = System.currentTimeMillis();
        List<NotificationConfig> list = config.getNotificationConfigList();
        if (list == null || list.isEmpty()) {
            return timed(DoctorCheck.warn(key, label, "未配置任何通知渠道",
                    "在「通知设置」中添加渠道，否则下载完成/失败都不会提醒"), start);
        }
        long enabled = list.stream().filter(n -> Boolean.TRUE.equals(n.getEnable())).count();
        if (enabled == 0) {
            return timed(DoctorCheck.warn(key, label,
                    StrUtil.format("共 {} 个渠道但全部未启用", list.size()),
                    "至少启用一个渠道"), start);
        }
        NotificationUtil.LastSend last = NotificationUtil.getLastSend();
        if (last == null) {
            return timed(DoctorCheck.warn(key, label,
                    StrUtil.format("已启用 {} 个渠道，本次运行尚未发送过通知", enabled),
                    "可在「通知设置」中逐个点「测试」验证"), start);
        }
        String detail = StrUtil.format("已启用 {} 个渠道；最近一次：{} {}",
                enabled, last.comment(), last.success() ? "成功" : "失败（" + last.message() + "）");
        if (last.success()) {
            return timed(DoctorCheck.ok(key, label, detail), start);
        }
        return timed(DoctorCheck.warn(key, label, detail, "检查该渠道的 Token/Webhook 是否有效"), start);
    }

    private DoctorCheck checkTasks() {
        String key = "tasks";
        String label = "后台任务线程";
        long start = System.currentTimeMillis();
        boolean running = TaskService.isRunning();
        List<Thread> threads = TaskService.THREADS;
        List<String> abandoned = TaskService.abandonedThreadNames();
        long alive = threads.stream().filter(Thread::isAlive).count();
        if (!running) {
            return timed(DoctorCheck.fail(key, label, "任务循环未运行", "尝试重启服务；若仍不恢复请检查启动日志"), start);
        }
        if (!abandoned.isEmpty()) {
            // 有线程没退干净：它们不会再跑新一轮（代际旗标已关），但仍在占用资源。
            // 摊开来说，避免用户只看到"任务好像变慢了"却查不到原因。
            return timed(DoctorCheck.warn(key, label,
                    StrUtil.format("任务循环运行中，但有 {} 个上一代线程未退出: {}", abandoned.size(),
                            String.join(", ", abandoned)),
                    "这些线程卡在不可中断的 IO 上；重启服务可彻底回收"), start);
        }
        if (threads.isEmpty() || alive < threads.size()) {
            return timed(DoctorCheck.warn(key, label,
                    StrUtil.format("任务循环运行中，但存活线程 {}/{}", alive, threads.size()),
                    "单次异常会自动退避重试，持续不恢复请查看日志"), start);
        }
        return timed(DoctorCheck.ok(key, label, StrUtil.format("{} 个任务线程存活", alive)), start);
    }

    /**
     * 网盘 API 限流 / 熔断状态（F7-7）。
     * <p>
     * 限流与熔断都是"隐式生效"的机制：用户只会感觉到"怎么变慢了 / 怎么都显示存疑"，
     * 却看不到到底是被限流拖了多久、缓存有没有省下请求、熔断被触发过几次。
     * 这一项把这些计数摊开，让"调参"和"排查网盘慢"有据可依。
     */
    private DoctorCheck checkOpenListRateLimit(Config config) {
        String key = "openListRateLimit";
        String label = "网盘 API 限流";
        long start = System.currentTimeMillis();
        if (!RssTask.isOpenListTool(config)) {
            return timed(DoctorCheck.skip(key, label, "当前下载器不是 OpenList / Alist，不限流"), start);
        }
        String evidence = StrUtil.format(
                "速率 {}/s，突发 {}；累计调用 {} 次（本轮列举 {}/{}），目录列举缓存命中 {}/{}（{}%），"
                        + "请求合并省下 {} 次，限流累计等待 {}ms，熔断 {} 次，超预算放弃 {} 次",
                OpenListApi.effectiveApiPerSecond(config),
                config.getOpenListApiBurst() == null ? 1 : config.getOpenListApiBurst(),
                OpenListApi.getApiCallCount(),
                OpenListApi.getListingCallCountRound(),
                OpenListApi.getRoundBudget() > 0 ? OpenListApi.getRoundBudget() : "不限",
                OpenListApi.getListingCacheHit(),
                OpenListApi.getListingCacheHit() + OpenListApi.getListingCacheMiss(),
                Math.round(OpenListApi.getListingCacheHitRate() * 100.0),
                OpenListApi.getListingCoalesced(),
                OpenListApi.getThrottleWaitMs(),
                OpenListApi.getCooldownTriggeredCount(),
                OpenListApi.getBudgetExhaustedCount());

        long remain = OpenListApi.listingCooldownRemainingMs();
        if (remain > 0L) {
            return timed(DoctorCheck.warn(key, label,
                    StrUtil.format("网盘列举处于熔断冷却中，剩余 {}s；冷却期内不再发起请求，受影响条目显示为「存疑」。{}",
                            remain / 1000L, evidence),
                    "稍后重试；若频繁触发，调低「网盘 API 限流」速率或调大冷却时间"), start);
        }
        if (OpenListApi.getCooldownTriggeredCount() > 0L) {
            return timed(DoctorCheck.warn(key, label,
                    "曾触发熔断，当前已恢复。" + evidence,
                    "若反复触发，检查网盘是否在限流、token 是否有效"), start);
        }
        return timed(DoctorCheck.ok(key, label, evidence), start);
    }

    /**
     * 「OpenList → 上游网盘」归因。
     * <p>
     * 用户看到的报错常常只有一句"网盘不可用"，而真正的因果埋在 OpenList 的 Go 错误里：
     * <pre>
     * Get "https://webapi.115.com/files?…": net/http: TLS handshake timeout
     * </pre>
     * 那是 <b>OpenList 所在机器到上游网盘</b> 的那一跳，不是 ani-rss 到 OpenList。
     * 本项把最近一次列举失败摊开，并给出可直接照做的排查方向。
     */
    private DoctorCheck checkOpenListUpstream(Config config) {
        String key = "openListUpstream";
        String label = "OpenList → 上游网盘";
        long start = System.currentTimeMillis();
        if (!RssTask.isOpenListTool(config)) {
            return timed(DoctorCheck.skip(key, label, "当前下载器不是 OpenList / Alist，无上游一跳"), start);
        }
        long lastAt = OpenListApi.getLastListingFailureAt();
        if (lastAt <= 0L) {
            return timed(DoctorCheck.ok(key, label,
                    "本次运行尚未出现列举失败；失败记忆省下 " + OpenListApi.getListingFailureMemoHit()
                            + " 次重复请求"), start);
        }
        String message = OpenListApi.getLastListingFailureMessage();
        String host = OpenListApi.extractUpstreamHost(message);
        String category = OpenListApi.classifyUpstreamFailure(message);
        String evidence = StrUtil.format(
                "最近一次列举失败（{} 前）：上游={}，类别={}；失败记忆省下 {} 次重复请求；原文: {}",
                ((System.currentTimeMillis() - lastAt) / 1000L) + "s",
                StrUtil.blankToDefault(host, "未识别"),
                StrUtil.blankToDefault(category, "未知"),
                OpenListApi.getListingFailureMemoHit(),
                StrUtil.maxLength(message, 300));
        return timed(DoctorCheck.warn(key, label, evidence, upstreamSuggestion(category)), start);
    }

    /**
     * v3 三态判定状态：成功 / 存疑 / 失败，以及存疑的成因分布 + 判定路径不变量。
     * <p>
     * 为什么这一项必须存在：v3 之后「存疑」成为默认态，它<b>不删任何东西</b>、不写失败队列、
     * 不发失败通知——好处是网盘抖一下不会毁数据，代价是"抖动"变得安静了。
     * 没有这一项，用户只能看到"这一集一直没下完"，却分不清是：
     * <ul>
     *   <li>网盘一直在抖（存疑多、失败少）→ 等，或调低「网盘 API 限流」速率；</li>
     *   <li>归位参数对不上（relocate 多）→ 看计划快照 / 计划内缺集；</li>
     *   <li>下载器真的下不动（taskState 多）→ 去 115 / OpenList 侧看离线任务。</li>
     * </ul>
     * 同时把<b>判定路径的列举次数</b>摊开：递归列举应为 0（见
     * {@link OpenListApi#getJudgementRecursiveListing()}）。
     * <p>
     * <b>这个指标的覆盖范围有限，别把它读成"整条判定链都合规"</b>：守卫
     * （{@code OpenListApi.inJudgementPath}）是"自愿包裹"式的——<b>没被包住的判定链不记账</b>。
     * 因此"递归列举 = 0"只说明<b>已包守卫的那部分</b>没有退化。目前判定路径上仍有
     * 未包裹的递归列举（启动恢复 / 归位对账 / 云下载兜底），
     * 详见 {@code OpenList判定回退方案.md} §12.6。
     */
    private DoctorCheck checkOpenListOutcome(Config config) {
        String key = "openListOutcome";
        String label = "离线下载判定";
        long start = System.currentTimeMillis();
        if (!RssTask.isOpenListTool(config)) {
            return timed(DoctorCheck.skip(key, label, "当前下载器不是 OpenList / Alist，无离线三态判定"), start);
        }
        String evidence = outcomeEvidence(
                OpenList.getOutcomeSuccess(), OpenList.getOutcomeUncertain(), OpenList.getOutcomeFailed(),
                OpenList.getUncertainRelocate(), OpenList.getUncertainWaitExpired(),
                OpenList.getUncertainReadFailure(), OpenList.getUncertainTaskState(),
                OpenList.getPlanEarlyChecks(), OpenListApi.getJudgementDirectListing(),
                OpenListApi.getJudgementRecursiveListing());
        if (OpenListApi.getJudgementRecursiveListing() > 0L) {
            return timed(DoctorCheck.warn(key, label, evidence,
                    "判定路径出现递归列举：请求数会随集数放大，一次网盘抖动就能拖垮整条判定链；"
                            + "请把该调用点改为单层列举（OpenListApi.listDirectChildrenStrict）"), start);
        }
        if (OpenList.getOutcomeUncertain() > 0L) {
            return timed(DoctorCheck.warn(key, label, evidence,
                    "存疑不删任何东西（pending / 计划快照 / 临时目录 / 网盘文件都保留），下一轮会自动重做归位；"
                            + "若长期只增不减，看上面的成因分布区分「网盘抖动」与「归位参数对不上」"), start);
        }
        return timed(DoctorCheck.ok(key, label, evidence), start);
    }

    /**
     * 三态判定文案。抽成 package-private 纯函数：这些字串本身就是可排查性——
     * 「存疑」与「失败」必须是两个独立的数，合并回一句话就分不清"网盘在抖"与"真下不动"。
     */
    static String outcomeEvidence(long success, long uncertain, long failed,
                                  long relocate, long waitExpired, long readFailure, long taskState,
                                  long planEarlyChecks, long directListing, long recursiveListing) {
        return StrUtil.format(
                "成功 {} 次，存疑 {} 次（成因：归位未通过 {}、等待期无信号 {}、读失败 {}、"
                        + "任务状态/10008/重试耗尽 {}），失败 {} 次；"
                        + "判定路径单层列举 {} 次、递归列举 {} 次（应恒为 0；"
                        + "仅覆盖已包守卫的调用点，未包裹的判定链不计入），完成前复核 {} 次",
                success, uncertain, relocate, waitExpired, readFailure, taskState,
                failed, directListing, recursiveListing, planEarlyChecks);
    }

    /**
     * 上游失败类别 → 可执行建议。
     * <p>
     * 抽成纯函数是为了能直接固化：建议本身就是本项的价值，
     * 把"TLS 握手超时"笼统地说成"检查网络"等于什么都没说。
     */
    static String upstreamSuggestion(String category) {
        if (StrUtil.isBlank(category)) {
            return "看 OpenList 自己的日志确认上游错误的完整原因";
        }
        if (category.startsWith("TLS 握手")) {
            return "在 OpenList 所在机器/容器里测该域名：换 DNS（223.5.5.5）对比、确认容器 MTU 与宿主一致、"
                    + "分别用 curl -4 / -6 试、检查宿主代理是否透传给容器";
        }
        if (category.contains("DNS")) {
            return "在 OpenList 容器内 getent hosts 该域名，并与换 DNS 后的结果对比";
        }
        if (category.contains("限流")) {
            return "调低「网盘 API 限流」速率、减少同时扫描的订阅、必要时重新登录上游网盘账号";
        }
        if (category.contains("连接")) {
            return "检查 OpenList 到上游的代理 / 防火墙 / 出口是否被拦截";
        }
        if (category.contains("超时")) {
            return "同 TLS 握手超时：优先查 DNS / MTU / IPv6 / 代理这四项";
        }
        return "看 OpenList 自己的日志确认上游错误的完整原因";
    }

    /**
     * F2：订阅级本地状态快照缓存自检。
     * <p>
     * 这个缓存的作用是"同一轮内同一订阅只列举一次网盘"。命中率低不一定有病
     * （刚重启、订阅刚改过都会导致冷启动），但如果长期接近 0，
     * 说明失效钩子在频繁触发，缓存实际没起到作用。
     */
    private DoctorCheck checkLocalStateCache(Config config) {
        String key = "localStateCache";
        String label = "本地状态快照缓存";
        long start = System.currentTimeMillis();
        return timed(DoctorCheck.ok(key, label, localStateCacheEvidence()), start);
    }

    /**
     * 自检页「本地状态快照缓存」的展示文案。
     * <p>
     * 抽成 package-private 纯函数是为了能测：{@link LocalStateCache} 的每个计数器都必须在
     * 这里露面，否则等于"埋了指标没人看"。曾漏过「增量追加」——而增量追加正是网盘 TTL 敢用
     * 24h 的前提，看不见它就无法判断追加链路是否真在工作（若它一直是 0，说明每集仍在走
     * 整份失效 + 重列，网盘 API 消耗会悄悄回到改造前的量级）。
     */
    static String localStateCacheEvidence() {
        long hit = LocalStateCache.getHit();
        long miss = LocalStateCache.getMiss();
        return StrUtil.format(
                "条目 {}（上限 {}），命中 {}/{}（{}%），实际构建 {} 次，请求合并 {} 次，过期 {} 次，"
                        + "构建中被失效丢弃 {} 次，增量追加 {} 次；本地 TTL {}s / 网盘 TTL {}s",
                LocalStateCache.size(), LocalStateCache.resolveCapacity(),
                hit, hit + miss, Math.round(LocalStateCache.getHitRate() * 100.0),
                LocalStateCache.getBuildCount(),
                LocalStateCache.getCoalesced(),
                LocalStateCache.getExpiredCount(),
                LocalStateCache.getInvalidatedDropCount(),
                LocalStateCache.getAppendedCount(),
                LocalStateCache.resolveTtlMs(LocalStateCache.Source.LOCAL_DISK) / 1000L,
                LocalStateCache.resolveTtlMs(LocalStateCache.Source.CLOUD_API) / 1000L);
    }

    private static DoctorCheck timed(DoctorCheck check, long start) {
        return check.setElapsedMs(System.currentTimeMillis() - start);
    }
}