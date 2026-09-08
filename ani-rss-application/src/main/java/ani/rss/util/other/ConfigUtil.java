package ani.rss.util.other;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.URLUtils;
import ani.rss.entity.Config;
import ani.rss.entity.Login;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.BgmTokenTypeEnum;
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
                .setBgmImage("large")
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
                .setBgmApi("https://api.bgm.tv")
                .setAutoStart(false)
                .setAllowCors(false)
                .setCorsOrigins("")
                .setNetworkPrefer("")
                .setUuid(UUID.randomUUID().toString());
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
        LogUtil.loadLogback();
        log.debug("加载配置文件 {}", configFile);
        TorrentUtil.load();
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
        try {
            ConfigUtil.format(CONFIG);
            String json = GsonStatic.toJson(CONFIG);
            File temp = new File(configFile + ".temp");
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
        }
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
    public static void backup(OutputStream outputStream) {
        File stagingDir = null;
        List<File> backupFiles = List.of();

        synchronized (ConfigUtil.class) {
            // 清理残余封面
            ClearService clearService = SpringUtil.getBean(ClearService.class);
            clearService.clearCover();

            File configDir = getConfigDir();
            String ts = DateUtil.format(new Date(), "yyyyMMddHHmmss");
            stagingDir = new File(new File(configDir, "backup"), ".staging-" + ts + "-" + RandomUtil.randomString(6));

            try {
                FileUtil.mkdir(stagingDir);
                List<File> staged = new ArrayList<>();
                for (String name : List.of(
                        "files", "torrents", "database.db",
                        AniUtil.FILE_NAME, ConfigUtil.FILE_NAME
                )) {
                    File src = new File(configDir, name);
                    if (!src.exists()) {
                        continue;
                    }
                    File dst = new File(stagingDir, name);
                    // 文件与目录统一快照到暂存目录, 锁内避免读到被并发修改的半截文件
                    FileUtil.copy(src, dst, true);
                    staged.add(dst);
                }
                backupFiles = staged;
            } catch (Exception e) {
                // 快照失败: 清理暂存目录后上抛, 不产出半截备份
                FileUtil.del(stagingDir);
                throw new RuntimeException("备份快照失败: " + e.getMessage(), e);
            }
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
     * 处理设置内的url与文件路径标准
     *
     * @param config
     */
    public static void format(Config config) {
        formatPath(config);
        formatUrl(config);

        String messageTemplate = config.getNotificationTemplate();
        config.setNotificationTemplate(messageTemplate.trim());

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
