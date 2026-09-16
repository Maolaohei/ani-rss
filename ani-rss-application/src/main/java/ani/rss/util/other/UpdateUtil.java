package ani.rss.util.other;

import ani.rss.commons.*;
import ani.rss.entity.About;
import ani.rss.entity.Config;
import ani.rss.entity.Github;
import ani.rss.exception.ResultException;
import ani.rss.update.BaseUpdate;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.comparator.VersionComparator;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class UpdateUtil {

    /**
     * 只保护「拉取远端信息 + 写缓存」这段短逻辑的锁。
     * <p>
     * 原实现四个方法共用类锁，而 {@code update()} 会在锁内下载完整的更新包，
     * 于是下载期间 {@code /about}、{@code /forkUpdate} 全部被堵住（每个被堵的请求占一个 Tomcat worker）。
     * 这里把「读远端信息」与「下载更新包」拆成两把独立的同步手段。
     */
    private static final Object FETCH_LOCK = new Object();

    /**
     * 更新流程（下载 + 应用）的互斥门闩：同一时刻只允许一次更新，
     * 但不再用锁去阻塞其它只读接口。
     */
    private static final AtomicBoolean UPDATE_IN_FLIGHT = new AtomicBoolean(false);

    public static About about() {
        synchronized (FETCH_LOCK) {
            return aboutLocked();
        }
    }

    private static About aboutLocked() {
        Config config = ConfigUtil.CONFIG;
        String key = "github#releases-latest";

        About cacheAbout = CacheUtils.get(key);

        if (Objects.nonNull(cacheAbout)) {
            return cacheAbout;
        }

        String version = MavenUtils.getVersion();

        About about = new About()
                .setVersion(version)
                .setUpdate(false)
                .setAutoUpdate(false)
                .setLatest("")
                .setMarkdownBody("");

        // Fork版本禁止检查更新
        if (Boolean.TRUE.equals(config.getDisableUpdate())) {
            log.info("已禁用更新检查（Fork版本）");
            CacheUtils.put(key, about, 1000 * 60);
            return about;
        }

        try {
            HttpRequest request = HttpReq.get("https://api.github.com/repos/wushuo894/ani-rss/releases/latest")
                    .timeout(3000);

            String githubToken = config.getGithubToken();
            if (StrUtil.isNotBlank(githubToken)) {
                request.header(Header.AUTHORIZATION, "Bearer " + githubToken);
            }

            request.then(response -> {
                int status = response.getStatus();
                if (status == 404) {
                    return;
                }
                HttpReq.assertStatus(response);

                Github.Release release = GsonStatic.fromJson(response.body(), Github.Release.class);

                String message = release.getMessage();
                if (StrUtil.isNotBlank(message)) {
                    log.error(message);
                    return;
                }

                String latest = release.getTagName().replace("v", "");

                /*
                禁止非跨小版本的更新
                取前两位版本号判断是允许自动更新
                */
                String reg = "^[Vv]?(\\d+\\.\\d+)";
                String latestMinor = StrUtil.nullToEmpty(ReUtil.get(reg, latest, 1));
                String currentMinor = StrUtil.nullToEmpty(ReUtil.get(reg, version, 1));
                /*
                任一侧解析不出小版本号时一律不允许自动更新。
                原实现直接 .equals()：上游 tag 不含 "x.y" 形态时 ReUtil.get 返回 null，
                会 NPE 并被外层 catch 吞成"检测更新失败"（P2-5）。
                */
                boolean autoUpdate = StrUtil.isNotBlank(latestMinor) && latestMinor.equals(currentMinor);

                about
                        .setDate(release.getPublishedAt())
                        .setAutoUpdate(autoUpdate)
                        .setUpdate(VersionComparator.INSTANCE.compare(latest, version) > 0)
                        .setLatest(latest)
                        .setMarkdownBody(release.getBody());

                MavenUtils.CurrentFile currentFile = MavenUtils.getCurrentFile();

                String filename = currentFile.isJar() ? "ani-rss.jar" : "ani-rss.exe";

                List<Github.Assets> assets = release.getAssets();
                for (Github.Assets asset : assets) {
                    String name = asset.getName();
                    if (!filename.equals(name)) {
                        continue;
                    }

                    Long size = asset.getSize();
                    String formatSize = FileUtils.formatSize(size, true);

                    String sha256 = asset.getDigest()
                            .replace("sha256:", "");

                    about.setDownloadUrl(asset.getBrowserDownloadUrl())
                            .setSha256(sha256)
                            .setSize(size)
                            .setFormatSize(formatSize);
                }
            });
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error("检测更新失败 {}", message);
            log.error(message, e);
        }
        // 缓存一分钟
        CacheUtils.put(key, about, 1000 * 60);
        return about;
    }

    /**
     * 获取 Fork 最新版本信息（强制更新，不检查当前版本）
     * <p>
     * 与 {@link #about()} 一样带 1 分钟缓存：原实现完全没有缓存，
     * 首页每次刷新都会打一次 GitHub（超时 10s），属纯浪费（P1-7）。
     */
    public static About forkAbout() {
        synchronized (FETCH_LOCK) {
            return forkAboutLocked();
        }
    }

    private static About forkAboutLocked() {
        String key = "github#fork-releases-latest";

        About cacheAbout = CacheUtils.get(key);

        if (Objects.nonNull(cacheAbout)) {
            return cacheAbout;
        }

        String version = MavenUtils.getVersion();
        About about = new About()
                .setVersion(version)
                .setUpdate(true)
                .setAutoUpdate(false)
                .setLatest("")
                .setMarkdownBody("");

        try {
            HttpRequest request = HttpReq.get("https://api.github.com/repos/Maolaohei/ani-rss/releases/latest")
                    .timeout(10000);

            String githubToken = ConfigUtil.CONFIG.getGithubToken();
            if (StrUtil.isNotBlank(githubToken)) {
                request.header(Header.AUTHORIZATION, "Bearer " + githubToken);
            }

            request.then(response -> {
                HttpReq.assertStatus(response);

                Github.Release release = GsonStatic.fromJson(response.body(), Github.Release.class);

                String latest = release.getTagName().replace("v", "");

                about
                        .setDate(release.getPublishedAt())
                        .setUpdate(true)
                        .setLatest(latest)
                        .setMarkdownBody(release.getBody());

                MavenUtils.CurrentFile currentFile = MavenUtils.getCurrentFile();
                String filename = currentFile.isJar() ? "ani-rss.jar" : "ani-rss.exe";

                List<Github.Assets> assets = release.getAssets();
                for (Github.Assets asset : assets) {
                    if (!filename.equals(asset.getName())) {
                        continue;
                    }
                    about.setDownloadUrl(asset.getBrowserDownloadUrl())
                            .setSha256(asset.getDigest().replace("sha256:", ""))
                            .setSize(asset.getSize())
                            .setFormatSize(FileUtils.formatSize(asset.getSize(), true));
                }
            });
        } catch (Exception e) {
            log.error("获取 Fork 版本信息失败: {}", e.getMessage());
        }
        // 缓存一分钟, 与 about() 口径一致
        CacheUtils.put(key, about, 1000 * 60);
        return about;
    }

    /**
     * 执行 Fork 更新（强制覆盖，不检查版本差异）
     */
    public static void forkUpdate(About about) {
        Assert.isTrue(StrUtil.isNotBlank(about.getDownloadUrl()), "未获取到下载地址");

        MavenUtils.CurrentFile currentFile = MavenUtils.getCurrentFile();
        Assert.isTrue(currentFile.isFile(), "不支持更新");

        BaseUpdate baseUpdate = BaseUpdate.getInstance();

        downloadAndApply(baseUpdate, about, "Fork 更新失败: {}");
    }

    /**
     * 下载更新包并异步应用。
     * <p>
     * <b>不在任何锁内做下载</b>：完整更新包可能几十 MB，慢链路下可达数分钟，
     * 持锁会让 {@code /about} 这类只读接口一起阻塞（P1-7）。
     * 并发保护改由 {@link #UPDATE_IN_FLIGHT} 门闩承担——同一时刻只允许一次更新流程，
     * 重复触发直接报错，而不是排队等待。
     */
    private static void downloadAndApply(BaseUpdate baseUpdate, About about, String errorLogPattern) {
        if (!UPDATE_IN_FLIGHT.compareAndSet(false, true)) {
            throw ResultException.exception("已有更新任务正在进行, 请稍后再试");
        }

        File updateFile;
        try {
            updateFile = baseUpdate.downloadUpdateFile(about);
        } catch (Exception e) {
            // 下载失败要放开门闩, 否则之后再也无法更新
            UPDATE_IN_FLIGHT.set(false);
            throw e;
        }

        ThreadUtil.execute(() -> {
            try {
                baseUpdate.update(updateFile);
                // 走到这里通常即将进程重启, 门闩保持占用即可
            } catch (Exception e) {
                log.error(errorLogPattern, e.getMessage(), e);
                UPDATE_IN_FLIGHT.set(false);
            }
        });
    }

    public static void update(About about) {
        // 用 Boolean.TRUE.equals 而非 !update: about 为 null 字段时原写法会 NPE
        if (!Boolean.TRUE.equals(about.getUpdate())) {
            return;
        }

        MavenUtils.CurrentFile currentFile = MavenUtils.getCurrentFile();

        Assert.isTrue(currentFile.isFile(), "不支持更新");

        BaseUpdate baseUpdate = BaseUpdate.getInstance();

        downloadAndApply(baseUpdate, about, "更新时遇到错误: {}");
    }

}
