package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.download.BaseDownload;
import ani.rss.entity.Config;
import ani.rss.entity.NotificationConfig;
import ani.rss.entity.vo.DoctorCheck;
import ani.rss.entity.web.Result;
import ani.rss.service.TaskService;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.DiskMonitorUtil;
import ani.rss.util.other.NotificationUtil;
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
        boolean running = TaskService.LOOP.get();
        List<Thread> threads = TaskService.THREADS;
        long alive = threads.stream().filter(Thread::isAlive).count();
        if (!running) {
            return timed(DoctorCheck.fail(key, label, "任务循环未运行", "尝试重启服务；若仍不恢复请检查启动日志"), start);
        }
        if (threads.isEmpty() || alive < threads.size()) {
            return timed(DoctorCheck.warn(key, label,
                    StrUtil.format("任务循环运行中，但存活线程 {}/{}", alive, threads.size()),
                    "单次异常会自动退避重试，持续不恢复请查看日志"), start);
        }
        return timed(DoctorCheck.ok(key, label, StrUtil.format("{} 个任务线程存活", alive)), start);
    }

    private static DoctorCheck timed(DoctorCheck check, long start) {
        return check.setElapsedMs(System.currentTimeMillis() - start);
    }
}
