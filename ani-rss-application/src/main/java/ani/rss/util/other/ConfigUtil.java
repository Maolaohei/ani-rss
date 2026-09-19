package ani.rss.util.other;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.URLUtils;
import ani.rss.entity.Config;
import ani.rss.entity.Login;
import ani.rss.entity.NotificationConfig;
import ani.rss.entity.QualityProfile;
import ani.rss.enums.BgmTokenTypeEnum;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.SortTypeEnum;
import ani.rss.service.ClearService;
import ani.rss.service.DownloadService;
import ani.rss.util.basic.LogUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.DynaBean;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.lang.func.Func1;
import cn.hutool.core.lang.func.LambdaUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ZipUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.system.OsInfo;
import cn.hutool.system.SystemUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
public class ConfigUtil {

    /**
     * 全局配置快照。
     * volatile + copy-on-write：通过 {@link #updateFromApi(Config)} 原子替换实例，
     * 读取方永远看到完整一致的配置，不会出现撕裂读。
     */
    public static volatile Config CONFIG = new Config();
    public static final String FILE_NAME = "config.v2.json";

    /*
      默认配置
     */
    static {
        String rootPath = "/Media";

        OsInfo osInfo = SystemUtil.getOsInfo();
        if (osInfo.isMac()) {
            rootPath = FileUtil.getUserHomePath() + "/Movies";
        }

        String downloadPath = FileUtils.getAbsolutePath(new File(rootPath, "番剧"));
        String ovaDownloadPath = FileUtils.getAbsolutePath(new File(rootPath, "剧场版"));
        String completedPath = FileUtils.getAbsolutePath(new File(rootPath, "已完结番剧"));

        String downloadPathTemplate = StrFormatter.format("{}/${title}/Season ${season}", downloadPath);
        String ovaDownloadPathTemplate = StrFormatter.format("{}/${title}", ovaDownloadPath);
        String completedPathTemplate = StrFormatter.format("{}/${title}/Season ${season}", completedPath);

        // 不再硬编码默认口令：全新部署时由 load() 生成随机密码并写入启动日志
        String password = SecureUtil.sha256(RandomUtil.randomString(16));

        String notificationTemplate = """
                ${emoji}${emoji}${emoji}
                事件类型: ${action}
                标题: ${title}
                评分: ${score}
                TMDB: ${tmdburl}
                TMDB标题: ${themoviedbName}
                BGM: ${bgmUrl}
                季: ${season}
                集: ${episode}
                字幕组: ${subgroup}
                进度: ${currentEpisodeNumber}/${totalEpisodeNumber}
                首播:  ${year}年${month}月${date}日
                事件: ${text}
                下载位置: ${downloadPath}
                TMDB集标题: ${episodeTitle}
                ${emoji}${emoji}${emoji}
                """;

        String apiKey = RandomUtil.randomString(64).toLowerCase();

        String downloadToolType = SystemUtil.get("DOWNLOAD_TOOL_TYPE", "qBittorrent");
        String downloadToolHost = SystemUtil.get("DOWNLOAD_TOOL_HOST", "");
        String downloadToolUsername = SystemUtil.get("DOWNLOAD_TOOL_USERNAME", "");
        String downloadToolPassword = SystemUtil.get("DOWNLOAD_TOOL_PASSWORD", "");

        String proxyList = """
                mikanani.me
                anibt.net
                animes.garden
                nyaa.si
                acg.rip
                google.com
                tmdb.org
                themoviedb.org
                anilist.co
                wushuo.top
                bgm.tv
                bangumi.tv
                chii.in
                github.com
                raw.githubusercontent.com
                telegram.org
                """;

        CONFIG.setRssSleepMinutes(15)
                .setMikanHost("https://mikanani.me")
                .setTmdbApi("https://api.themoviedb.org")
                .setTmdbApiKey("")
                .setTmdbImage("https://image.tmdb.org")
                .setTmdbAnime(true)
                .setRenameSleepSeconds(10)
                .setRename(true)
                .setRss(true)
                .setRssTimeout(20)
                .setCustomTags(new ArrayList<>())
                .setDelayedDownload(0)
                .setNewTorrentWaitHours(2)
                .setFileExist(false)
                .setAwaitStalledUP(true)
                .setDelete(false)
                .setDeleteStandbyRSSOnly(false)
                .setOffset(false)
                .setTitleYear(true)
                .setAutoDisabled(false)
                .setDownloadPathTemplate(downloadPathTemplate)
                .setOvaDownloadPathTemplate(ovaDownloadPathTemplate)
                .setDownloadToolHost(downloadToolHost)
                .setDownloadToolType(downloadToolType)
                .setDownloadRetry(3)
                .setDownloadToolUsername(downloadToolUsername)
                .setDownloadToolPassword(downloadToolPassword)
                .setQbUseDownloadPath(false)
                .setQbContentLayout("Original")
                .setRatioLimit(-2)
                .setSeedingTimeLimit(-2)
                .setInactiveSeedingTimeLimit(-2)
                .setSkip5(true)
                .setStandbyRss(false)
                .setCoexist(false)
                .setLogsMax(128)
                .setDebug(false)
                .setProcrastinatingMasterOnly(true)
                .setProxy(false)
                .setProxyHost("")
                .setProxyPort(8080)
                .setProxyUsername("")
                .setProxyPassword("")
                .setDownloadCount(0)
                .setLogin(new Login()
                        .setUsername("admin")
                        .setPassword(password)
                )
                .setMultiLoginForbidden(true)
                .setLoginEffectiveHours(3)
                .setExclude(List.of("720[Pp]", "\\d-\\d", "合集", "特别篇"))
                .setImportExclude(false)
                .setEnabledExclude(false)
                .setTmdb(true)
                .setBgmJpName(false)
                .setTmdbId(false)
                .setTmdbLanguage("zh-CN")
                .setTmdbRomaji(false)
                .setTmdbOriginalName(false)
                .setIpWhitelist(false)
                .setIpWhitelistStr("")
                .setOmit(true)
                .setBgmToken("")
                .setBgmTokenType(BgmTokenTypeEnum.INPUT)
                .setBgmAppID("")
                .setBgmAppSecret("")
                .setBgmRefreshToken("")
                .setBgmRedirectUri("")
                .setApiKey("")
                .setDownloadNew(false)
                .setInnerIP(false)
                .setRenameTemplate("[${subgroup}] ${title} S${seasonFormat}.E${episodeFormat} ${episodeTitle}")
                .setOvaRenameTemplate("${title} (${year}) [${subgroup}]")
                .setRenameDelYear(false)
                .setRenameDelTmdbId(false)
                .setPriorityKeywordsEnable(false)
                .setPriorityKeywords(new ArrayList<>())
                .setVerifyLoginIp(true)
                .setAutoTrackersUpdate(false)
                .setTrackersUpdateUrls("https://cf.trackerslist.com/best.txt")
                .setAutoUpdate(false)
                .setVersion("")
                .setBgmImageSize("medium")
                .setCustomCss("")
                .setCustomJs("")
                .setCustomEpisode(false)
                .setCustomEpisodeStr(RenameUtil.REG_STR)
                .setCustomEpisodeGroupIndex(2)
                .setProvider("115 Open")
                .setUpload(true)
                .setUpLimit(0L)
                .setDlLimit(0L)
                .setExpirationTime(0L)
                .setOutTradeNo("")
                .setTryOut(false)
                .setVerifyExpirationTime(false)
                .setProcrastinating(false)
                .setProcrastinatingDay(14)
                .setGithubToken("")
                .setDisableUpdate(true)
                .setAlistRefresh(false)
                .setAlistRefreshDelayed(0L)
                .setUpdateTotalEpisodeNumber(false)
                .setForceUpdateTotalEpisodeNumber(false)
                .setAlistDownloadTimeout(60)
                .setAlistDownloadRetryNumber(5L)
                .setConfigBackup(false)
                .setConfigBackupDay(7)
                .setCompleted(false)
                .setCompletedPathTemplate(completedPathTemplate)
                .setNotificationTemplate(notificationTemplate)
                .setNotificationConfigList(new ArrayList<>())
                .setApiKey(apiKey)
                .setCopyMasterToStandby(false)
                .setSortType(SortTypeEnum.SCORE)
                .setTmdbIdPlexMode(false)
                .setProxyList(proxyList)
                .setScrape(false)
                .setFollowDay(14)
                .setBangumiIniEnabled(false)
                .setReplace(false)
                .setMaxFileNameLength(0)
                .setLimitLoginAttempts(true)
                .setReverseProxyTrustIpList(List.of("127.0.0.1"))
                .setReverseProxyTrustIpListEnabled(false)
                .setSubtitleIndependentFolderEnabled(false)
                .setSubtitleIndependentFolderName("Subs")
                .setSubtitleMetaEnabled(true)
                .setBgmApi("https://api.bgm.tv")
                .setAutoStart(false)
                .setAllowCors(false)
                .setCorsOrigins("")
                .setNetworkPrefer("")
                .setUuid(UUID.randomUUID().toString())
                .setQualityProfile(defaultQualityProfile());
    }

    /**
     * 获取设置文件夹
     *
     * @return
     */
    public static File getConfigDir() {
        String configDir = SystemUtil.get("CONFIG");
        if (StrUtil.isNotBlank(configDir)) {
            return new File(configDir);
        }

        // 若当前目录存在 config 则优先使用
        File file = new File("config").getAbsoluteFile();
        if (file.exists()) {
            return file;
        }

        // macOS / Windows 默认为 用户目录/ani-rss
        OsInfo osInfo = SystemUtil.getOsInfo();
        if (osInfo.isWindows() || osInfo.isMac()) {
            file = new File(FileUtil.getUserHomePath(), "ani-rss");
        }

        return file;
    }

    /**
     * 获取设置文件
     *
     * @return
     */
    public static File getConfigFile() {
        File configDir = getConfigDir();
        return new File(configDir + File.separator + FILE_NAME);
    }

    /**
     * 加载设置
     */
    public static synchronized void load() {
        File configFile = getConfigFile();

        if (!configFile.exists()) {
            // 首次启动：生成随机登录密码，避免默认弱口令 admin/admin
            String randomPassword = RandomUtil.randomString(16);
            CONFIG.getLogin().setPassword(SecureUtil.sha256(randomPassword));
            // 原子写：先写临时文件再 move 替换，避免首启写盘途中断电留下截断的配置文件
            File temp = new File(configFile + ".temp");
            FileUtil.del(temp);
            FileUtil.writeUtf8String(GsonStatic.toJson(CONFIG), temp);
            FileUtils.move(temp.toPath(), configFile.toPath());
            String tip = StrFormatter.format(
                    "首次启动：已生成随机登录密码 [{}]（用户名 {}），请登录后立即修改。",
                    randomPassword, CONFIG.getLogin().getUsername());
            System.out.println("[ani-rss] " + tip);
            log.warn(tip);
        }
        String s = FileUtil.readUtf8String(configFile);

        // 解析失败兜底：损坏文件改名保留现场，用当前默认 CONFIG 继续启动，不再 exit
        Config loaded = null;
        try {
            loaded = GsonStatic.fromJson(s, Config.class);
        } catch (Exception e) {
            log.error("配置文件解析失败: {}", e.getMessage(), e);
        }

        CopyOptions copyOptions = CopyOptions
                .create()
                .setIgnoreNullValue(true);

        if (loaded == null) {
            String ts = DateUtil.format(new Date(), "yyyyMMddHHmmss");
            File corruptFile = new File(configFile + ".corrupt-" + ts);
            try {
                FileUtil.move(configFile, corruptFile, true);
                log.error("配置文件已损坏, 已改名为 [{}] 保留现场; 本次启动将使用默认配置, 请检查磁盘/权限后重新导入配置", corruptFile.getName());
            } catch (Exception moveException) {
                log.error("配置文件已损坏, 且改名保留失败(可能被占用), 请手动处理: {}", configFile);
                log.error(moveException.getMessage(), moveException);
            }
        } else {
            BeanUtil.copyProperties(loaded, CONFIG, copyOptions);
        }

        migrateMikanHost(CONFIG);
        format(CONFIG);
        // 必须在 format 之后：format 会用默认值补齐 null 的 statusList
        migrateNotificationSystem(CONFIG);
        LogUtil.loadLogback();
        log.debug("加载配置文件 {}", configFile);
        TorrentUtil.load();
    }

    /**
     * 一次性迁移：为老配置的通知渠道补上 SYSTEM（系统通知）。
     * <p>
     * 该动作<b>不能</b>放在 {@link #format(Config)} 里 —— 那条路径在启动、每次保存
     * ({@link #syncChecked()}) 和接口更新 ({@link #updateFromApi(Config)}) 时都会执行，
     * 用户取消勾选「系统通知」后会被静默加回，表现为"复选框永远关不掉"。
     * <p>
     * 这里用 {@link Config#getNotificationSystemMigrated()} 区分「尚未迁移」与
     * 「用户主动取消」：只有前者才补，补完置位标记（随下一次保存落盘）。
     */
    private static void migrateNotificationSystem(Config config) {
        if (Boolean.TRUE.equals(config.getNotificationSystemMigrated())) {
            return;
        }
        List<NotificationConfig> notificationConfigList = config.getNotificationConfigList();
        if (notificationConfigList == null) {
            return;
        }
        for (NotificationConfig notificationConfig : notificationConfigList) {
            List<NotificationStatusEnum> statusList = notificationConfig.getStatusList();
            if (statusList == null || statusList.contains(NotificationStatusEnum.SYSTEM)) {
                continue;
            }
            List<NotificationStatusEnum> merged = new ArrayList<>(statusList);
            merged.add(NotificationStatusEnum.SYSTEM);
            notificationConfig.setStatusList(merged);
        }
        config.setNotificationSystemMigrated(true);
        log.info("老配置通知渠道已补入「系统通知」状态");
    }

    /**
     * 迁移已落盘的 Mikan Host：mikanime.tv -> mikanani.me
     * <p>
     * 旧版本曾默认使用 mikanime.tv 并保存到 config.json，该值会覆盖新默认值。
     * mikanime.tv 仅是 mikanani.me 的 301 跳板，启动时统一改写为正式域名，
     * 保证新建订阅生成的 RSS/种子链接都走 mikanani.me。
     */
    private static void migrateMikanHost(Config config) {
        String mikanHost = config.getMikanHost();
        if (StrUtil.isNotBlank(mikanHost) && mikanHost.contains("mikanime.tv")) {
            config.setMikanHost(mikanHost.replace("mikanime.tv", "mikanani.me"));
            log.info("Mikan Host 迁移 mikanime.tv -> mikanani.me");
        }
    }

    /**
     * 将设置保存到磁盘
     * <p>
     * 兼容保留的 void 签名：实际逻辑委托给 {@link #syncChecked()}，保存失败时仅记录错误日志。
     */
    public static void sync() {
        if (!syncChecked()) {
            log.error("配置保存失败, 本次修改可能未持久化, 请检查磁盘空间与写入权限");
        }
    }

    /**
     * 将设置保存到磁盘
     *
     * @return 保存是否成功
     */
    public static synchronized boolean syncChecked() {
        // 配置已变更：下载路径反向索引失效
        DownloadService.invalidateDownloadPathIndex();
        File configFile = getConfigFile();
        log.debug("保存配置 {}", configFile);
        File temp = new File(configFile + ".temp");
        try {
            ConfigUtil.format(CONFIG);
            String json = GsonStatic.toJson(CONFIG);
            FileUtil.del(temp);
            FileUtil.writeUtf8String(json, temp);
            FileUtils.move(temp.toPath(), configFile.toPath());
            LogUtil.loadLogback();
            log.debug("保存成功 {}", configFile);
            return true;
        } catch (Exception e) {
            log.error("保存失败 {}", configFile);
            log.error(e.getMessage(), e);
            return false;
        } finally {
            // 写盘失败残留的半截 temp 必须清理，否则下次 load/sync 可能误读
            try {
                FileUtil.del(temp);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * F6-4 配置变更联动：这些字段决定「本地状态判定」的<b>输入或口径</b>，任一变化都必须
     * 让已缓存的判定结果作废，否则用户改完设置仍会看到旧结果（最长
     * {@code stateCacheTtlDays} = 90 天才自然过期）。
     * <ul>
     *   <li>{@code downloadPathTemplate} / {@code ovaDownloadPathTemplate}：下载目录整体位移，
     *       旧快照指向的路径已不是这个订阅的目录；</li>
     *   <li>{@code rename}：决定「能否按文件名匹配集数」，直接切换判定口径
     *       （真实文件列举 ↔ 种子记录回退），是本组里影响最大的一项；</li>
     *   <li>{@code fileExist}：决定「是否检查文件是否存在」，关掉后判定退化为记录口径；</li>
     *   <li>{@code downloadToolType}：本地磁盘 ↔ 网盘，快照的 Source 与构建代价都不同。</li>
     * </ul>
     * 与"订阅增删改"一样属全局口径变化，因此调用方应做整体失效而非按订阅失效。
     * <p>
     * 抽成纯函数是为了能被单测直接覆盖：{@code setConfig} 依赖 Spring 上下文，
     * 而"哪些字段算口径变化"恰恰是最容易在后续迭代里被漏掉的一条。
     *
     * @param oldConfig 保存前的配置（可能为 null）
     * @param newConfig 保存后的配置（可能为 null）
     * @return 是否需要失效本地状态相关缓存
     */
    public static boolean localStateInputChanged(Config oldConfig, Config newConfig) {
        if (oldConfig == null || newConfig == null) {
            return false;
        }
        return !Objects.equals(oldConfig.getDownloadPathTemplate(), newConfig.getDownloadPathTemplate())
                || !Objects.equals(oldConfig.getOvaDownloadPathTemplate(), newConfig.getOvaDownloadPathTemplate())
                || !Objects.equals(oldConfig.getRename(), newConfig.getRename())
                || !Objects.equals(oldConfig.getFileExist(), newConfig.getFileExist())
                || !Objects.equals(oldConfig.getDownloadToolType(), newConfig.getDownloadToolType());
    }

    /**
     * 接口更新配置（copy-on-write）：锁内合并出新快照后原子交换 CONFIG，
     * 避免 RSS/下载线程读到「半新半旧」的撕裂配置。
     *
     * @param newConfig 接口提交的新配置（允许只传需要修改的字段，null 字段不覆盖）
     * @return 落盘是否成功
     */
    public static synchronized boolean updateFromApi(Config newConfig) {
        Config current = CONFIG;

        // 与原 setConfig 语义一致：这几个字段不允许通过接口修改
        newConfig.setExpirationTime(null)
                .setOutTradeNo(null)
                .setTryOut(null);

        String username = current.getLogin().getUsername();
        String password = current.getLogin().getPassword();

        // 深拷贝当前配置形成新快照，不再在共享实例上逐字段写入
        Config merged = GsonStatic.fromJson(GsonStatic.toJson(current), Config.class);

        CopyOptions copyOptions = CopyOptions
                .create()
                .setIgnoreNullValue(true);
        BeanUtil.copyProperties(newConfig, merged, copyOptions);

        // 用户名/密码留空视为未修改
        String loginPassword = merged.getLogin().getPassword();
        if (StrUtil.isBlank(loginPassword)) {
            merged.getLogin().setPassword(password);
        }
        String loginUsername = merged.getLogin().getUsername();
        if (StrUtil.isBlank(loginUsername)) {
            merged.getLogin().setUsername(username);
        }

        // 代理参数校验（与原 setConfig 一致：基于合并后的值判断）
        Boolean proxy = merged.getProxy();
        if (proxy != null && proxy) {
            String proxyHost = merged.getProxyHost();
            Integer proxyPort = merged.getProxyPort();
            if (StrUtil.isBlank(proxyHost) || proxyPort == null) {
                throw new IllegalArgumentException("代理参数不完整");
            }
        }

        format(merged);
        CONFIG = merged;
        return syncChecked();
    }

    /**
     * 爱发电激活成功后写入到期信息。
     * <p>
     * 与 {@link #updateFromApi(Config)} 共用类锁，锁内基于最新快照合并后原子交换，
     * 避免字段级 setter 直接写到旧引用上、随后被并发交换丢弃。
     *
     * @param outTradeNo     爱发电订单号
     * @param expirationTime 到期时间戳
     * @param tryOut         是否试用
     */
    public static synchronized void updateAfdianInfo(String outTradeNo, Long expirationTime, Boolean tryOut) {
        Config merged = GsonStatic.fromJson(GsonStatic.toJson(CONFIG), Config.class);
        merged.setOutTradeNo(outTradeNo)
                .setExpirationTime(expirationTime)
                .setTryOut(tryOut);
        CONFIG = merged;
        if (!syncChecked()) {
            log.error("爱发电激活信息保存失败, 本次修改可能未持久化, 请检查磁盘空间与写入权限");
        }
    }

    /**
     * 备份
     */
    public static synchronized void backup() {
        Boolean configBackup = CONFIG.getConfigBackup();
        if (!configBackup) {
            return;
        }

        clearBackup();

        File configDir = getConfigDir();
        File backupDir = new File(configDir, "backup");

        String date = DateUtil.format(new Date(), DatePattern.NORM_DATE_PATTERN);
        File backupFile = new File(backupDir, date + ".zip");

        if (backupFile.exists()) {
            return;
        }

        log.info("正在备份设置 {}", backupFile.getName());

        // 先写入临时文件, 成功后原子替换为最终名, 避免打包中途失败留下半截 zip 被当成当日备份
        File tempFile = new File(backupDir, date + ".zip.temp");
        try {
            try (OutputStream outputStream = FileUtil.getOutputStream(tempFile)) {
                backup(outputStream);
            }
            FileUtils.move(tempFile.toPath(), backupFile.toPath());
            log.info("备份设置成功 {}", backupFile.getName());
        } catch (Exception e) {
            log.error("备份失败 {}", backupFile.getName());
            log.error(e.getMessage(), e);
            // 打包/替换失败: 清理临时文件, 避免残留
            FileUtil.del(tempFile);
        }
    }

    /**
     * 备份到输出流
     * <p>
     * 不再加 synchronized：导出先在锁内把待打包文件快照到暂存目录，
     * 再在锁外打包，避免长时间打包阻塞所有 sync/load。
     */
    /**
     * 把暂存目录里的 json 副本重写为格式化（缩进）版本，仅供导出使用。
     * <p>
     * 解析失败或文件缺失时静默跳过：导出不应因为"美化失败"而整个失败，
     * 原样导出 compact 版本也完全可用。
     */
    private static void prettifyStaged(File stagingDir, String fileName) {
        try {
            File staged = new File(stagingDir, fileName);
            if (!staged.exists()) {
                return;
            }
            String json = FileUtil.readUtf8String(staged);
            FileUtil.writeUtf8String(GsonStatic.prettyJson(json), staged);
        } catch (Exception e) {
            log.debug("导出美化 {} 失败, 保持原样: {}", fileName, e.getMessage());
        }
    }

    public static void backup(OutputStream outputStream) {
        // clearCover 是全树扫描的 IO 重操作，且与打包内容无强一致性要求，移出锁外；
        // 内部自带每天一次限频（见 ClearService），这里失败也不影响备份。
        try {
            ClearService clearService = SpringUtil.getBean(ClearService.class);
            clearService.clearCover();
        } catch (Exception e) {
            log.debug("备份前清理封面跳过: {}", e.getMessage());
        }

        // 备份前对 database.db 做 WAL checkpoint，把 WAL 并入主库后再快照，
        // 否则拷贝到的 db + -wal/-shm 不配套，恢复后可能丢最近几笔。失败忽略。
        checkpointDatabase();

        // 调用方容忍并发：导出只是读快照，打包期间 config/ani 被并发修改时，
        // 最坏只是本次 zip 里混入新旧各半（暂存拷贝保证单文件不截断），下次导出即一致；
        // 因此本方法不再整体 synchronized，只在锁内做清单快照。
        final List<String> names = List.of(
                "files", "torrents", "database.db",
                AniUtil.FILE_NAME, ConfigUtil.FILE_NAME
        );
        final File configDir = getConfigDir();
        final List<File> sources;
        synchronized (ConfigUtil.class) {
            // 锁内只做清单快照（存在性检查），拷贝放锁外，避免长时间 IO 阻塞 sync/load
            List<File> list = new ArrayList<>();
            for (String name : names) {
                File src = new File(configDir, name);
                if (src.exists()) {
                    list.add(src);
                }
            }
            sources = List.copyOf(list);
        }

        String ts = DateUtil.format(new Date(), "yyyyMMddHHmmss");
        File stagingDir = new File(new File(configDir, "backup"), ".staging-" + ts + "-" + RandomUtil.randomString(6));

        List<File> backupFiles;
        try {
            FileUtil.mkdir(stagingDir);
            List<File> staged = new ArrayList<>();
            for (File src : sources) {
                File dst = new File(stagingDir, src.getName());
                // 锁外逐个拷贝：单文件拷贝仍可能与写盘并发，但 temp+move 的写方保证源文件要么旧全本要么新全本
                FileUtil.copy(src, dst, true);
                staged.add(dst);
            }
            // 落盘已改 compact（P1-12，体积 -31%），但导出的 json 是用户会直接打开看的，
            // 这里用格式化实例把暂存副本重写一遍，两边的收益都拿到
            prettifyStaged(stagingDir, AniUtil.FILE_NAME);
            prettifyStaged(stagingDir, ConfigUtil.FILE_NAME);
            backupFiles = staged;
        } catch (Exception e) {
            // 快照失败: 清理暂存目录后上抛, 不产出半截备份
            FileUtil.del(stagingDir);
            throw new RuntimeException("备份快照失败: " + e.getMessage(), e);
        }

        try {
            ZipUtil.zip(outputStream, StandardCharsets.UTF_8, true, pathname -> {
                if (pathname.isFile()) {
                    String name = pathname.getName();
                    return !name.startsWith(".");
                }
                File[] files = FileUtils.listFiles(pathname);
                return !ArrayUtil.isEmpty(files);
            }, backupFiles.toArray(new File[0]));
        } finally {
            // 无论打包成功与否都清理暂存目录
            FileUtil.del(stagingDir);
            IoUtil.close(outputStream);
        }
    }

    /**
     * 备份前把 database.db 的 WAL 并入主库，失败忽略（仅备份路径使用）。
     */
    private static void checkpointDatabase() {
        try {
            File db = new File(getConfigDir(), "database.db");
            if (!db.exists()) {
                return;
            }
            Class.forName("org.sqlite.JDBC");
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
                 java.sql.Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
        } catch (Exception e) {
            log.debug("database WAL checkpoint 跳过: {}", e.getMessage());
        }
    }


    /**
     * 清理备份
     */
    public static synchronized void clearBackup() {
        Integer configBackupDay = CONFIG.getConfigBackupDay();

        // 过期时间
        long expirationTime = DateUtil.offsetDay(new Date(), -configBackupDay).getTime();

        File configDir = getConfigDir();
        File backupDir = new File(configDir, "backup");
        if (!backupDir.exists()) {
            return;
        }

        File[] files = FileUtils.listFiles(backupDir);
        if (ArrayUtil.isEmpty(files)) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                continue;
            }
            String extName = FileUtil.extName(file);
            if (!"zip".equals(extName)) {
                continue;
            }
            String mainName = FileUtil.mainName(file);
            try {
                long time = DateUtil.parse(mainName, DatePattern.NORM_DATE_PATTERN).getTime();
                if (time > expirationTime) {
                    continue;
                }
                log.info("{} 备份已过期, 自动删除", file.getName());
                FileUtil.del(file);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    /**
     * 质量择优规则默认配置：关闭、保留既有合集优先行为。
     * <p>
     * 该方法同时用于首次启动与存量配置迁移，避免配置文件没有新增字段时出现 null。
     */
    public static QualityProfile defaultQualityProfile() {
        return new QualityProfile()
                .setEnable(false)
                .setResolutionOrder(new ArrayList<>())
                .setPreferCodecs(new ArrayList<>())
                .setExcludeCodecs(new ArrayList<>())
                .setMinResolution("")
                .setMaxResolution("")
                .setMinSizeMb(0)
                .setMaxSizeMb(0)
                .setMinSeeders(0)
                .setPreferSubgroups(new ArrayList<>())
                .setExcludeSubgroups(new ArrayList<>())
                .setPreferCollection(true);
    }

    /**
     * 处理设置内的url与文件路径标准
     *
     * @param config
     */
    public static void format(Config config) {
        formatPath(config);
        formatUrl(config);

        if (config.getQualityProfile() == null) {
            config.setQualityProfile(defaultQualityProfile());
        } else {
            QualityProfile profile = config.getQualityProfile();
            if (profile.getResolutionOrder() == null) profile.setResolutionOrder(new ArrayList<>());
            if (profile.getPreferCodecs() == null) profile.setPreferCodecs(new ArrayList<>());
            if (profile.getExcludeCodecs() == null) profile.setExcludeCodecs(new ArrayList<>());
            if (profile.getMinResolution() == null) profile.setMinResolution("");
            if (profile.getMaxResolution() == null) profile.setMaxResolution("");
            if (profile.getMinSizeMb() == null) profile.setMinSizeMb(0);
            if (profile.getMaxSizeMb() == null) profile.setMaxSizeMb(0);
            if (profile.getMinSeeders() == null) profile.setMinSeeders(0);
            if (profile.getPreferSubgroups() == null) profile.setPreferSubgroups(new ArrayList<>());
            if (profile.getExcludeSubgroups() == null) profile.setExcludeSubgroups(new ArrayList<>());
            if (profile.getPreferCollection() == null) profile.setPreferCollection(true);
        }

        String messageTemplate = config.getNotificationTemplate();
        config.setNotificationTemplate(messageTemplate.trim());

        // ASSRT 字幕源参数补默认值。
        // 这些字段在 Config 里是可空包装类型，缺失时设置页会显示空白输入框（用户无从判断该填什么）；
        // 在此统一补齐，既让设置页可见真实默认值，也让 /config 返回的配置自洽。
        // 运行期另有兜底（AssrtSubtitleProvider.connectTimeoutMs/readTimeoutMs/retryCount），
        // 因此即使配置被手工改坏也不会失效。
        if (config.getAssrtRateLimitPerMinute() == null || config.getAssrtRateLimitPerMinute() <= 0) {
            config.setAssrtRateLimitPerMinute(5);
        }
        if (StrUtil.isBlank(config.getSubtitleLang())) {
            config.setSubtitleLang("chs");
        }
        if (config.getAssrtConnectTimeoutMs() == null) {
            config.setAssrtConnectTimeoutMs(15000);
        }
        if (config.getAssrtReadTimeoutMs() == null) {
            config.setAssrtReadTimeoutMs(30000);
        }
        if (config.getAssrtRetryCount() == null) {
            config.setAssrtRetryCount(2);
        }

        // 错峰更新与网盘限流参数补默认值（同上：缺省会让设置页渲染出空白输入框）。
        // 运行期另有兜底（RssTask.resolveStaggerBatchIntervalMs / OpenListApi 的令牌桶），
        // 因此即使配置被手工改坏也不会失效。
        if (config.getStaggeredUpdateEnable() == null) {
            config.setStaggeredUpdateEnable(true);
        }
        if (config.getStaggerBatchIntervalMs() == null) {
            config.setStaggerBatchIntervalMs(2000);
        }
        if (config.getOpenListApiPerSecond() == null) {
            config.setOpenListApiPerSecond(3);
        }
        if (config.getOpenListApiBurst() == null) {
            config.setOpenListApiBurst(1);
        }
        if (config.getOpenListFailThreshold() == null) {
            config.setOpenListFailThreshold(3);
        }
        if (config.getOpenListCooldownSeconds() == null) {
            config.setOpenListCooldownSeconds(60);
        }
        if (config.getQuiescentConfirmTimes() == null) {
            config.setQuiescentConfirmTimes(2);
        }
        if (config.getQuiescentTimeoutMinutes() == null) {
            // 默认 = 2 × 轮询周期（与需求文档 F4-7 一致）；rssSleepMinutes 缺失时按 15 分钟算
            int sleepMinutes = config.getRssSleepMinutes() == null ? 15 : Math.max(1, config.getRssSleepMinutes());
            config.setQuiescentTimeoutMinutes(Math.max(5, sleepMinutes * 2));
        }
        // ---- F2 结果缓存 ----
        // 本地磁盘与网盘统一按「天」配置（默认 10 天，1–90），
        // 旧配置 localStateCacheTtlSeconds / cloudStateCacheTtlSeconds 直接废弃不再读取。
        if (config.getStateCacheTtlDays() == null) {
            config.setStateCacheTtlDays(10);
        }
        // ---- F7-5 每轮预算 ----
        // 默认 = 启用订阅数 × 1（每个订阅至少一次列举）。这里拿不到订阅数，
        // 故留 null，由 RssTask.resolveApiBudgetPerRound 在轮次开始时按实际订阅数计算。
        if (config.getCloudListMaxFiles() == null) {
            config.setCloudListMaxFiles(5000);
        }

        NotificationConfig newNotificationConfig = NotificationConfig.createNotificationConfig();

        List<NotificationConfig> notificationConfigList = config.getNotificationConfigList();

        CopyOptions copyOptions = CopyOptions
                .create()
                .setIgnoreNullValue(true)
                // 禁止覆盖模式 仅补全null值
                .setOverride(false);

        for (NotificationConfig notificationConfig : notificationConfigList) {
            BeanUtil.copyProperties(newNotificationConfig, notificationConfig, copyOptions);
        }
    }

    /**
     * 处理url
     *
     * @param config
     */
    public static void formatUrl(Config config) {
        List<Func1<Config, String>> func1List = List.of(
                Config::getDownloadToolHost,
                Config::getMikanHost,
                Config::getTmdbApi
        );

        DynaBean dynaBean = DynaBean.create(config);

        for (Func1<Config, String> func1 : func1List) {
            String fieldName = LambdaUtil.getFieldName(func1);
            String v = func1.callWithRuntimeException(config);
            v = URLUtils.getUrlStr(v);
            dynaBean.set(fieldName, v);
        }
    }

    /**
     * 处理文件路径
     *
     * @param config
     */
    public static void formatPath(Config config) {
        List<Func1<Config, String>> func1List = List.of(
                Config::getDownloadPathTemplate,
                Config::getOvaDownloadPathTemplate,
                Config::getCompletedPathTemplate
        );

        DynaBean dynaBean = DynaBean.create(config);

        for (Func1<Config, String> func1 : func1List) {
            String fieldName = LambdaUtil.getFieldName(func1);
            String v = func1.callWithRuntimeException(config);
            v = FileUtils.getAbsolutePath(v);
            dynaBean.set(fieldName, v);
        }
    }

}
