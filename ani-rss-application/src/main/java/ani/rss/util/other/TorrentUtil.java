package ani.rss.util.other;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.PinyinUtils;
import ani.rss.download.BaseDownload;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentsInfo;
import ani.rss.enums.StringEnum;
import ani.rss.enums.TorrentsTags;
import ani.rss.service.ClearService;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ClassUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.bittorrent.TorrentFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 管理下载器的调用与种子存取
 */
@Slf4j
public class TorrentUtil {
    public static BaseDownload DOWNLOAD;

    // 种子列表缓存：避免短时间内重复请求下载器
    private static volatile List<TorrentsInfo> cachedTorrents;
    private static volatile long cacheExpireTime = 0;
    private static final long CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(5);

    /**
     * 获取任务列表（带缓存，5秒内重复调用直接返回缓存）
     */
    public static synchronized List<TorrentsInfo> getTorrentsInfos() {
        long now = System.currentTimeMillis();
        if (cachedTorrents != null && now < cacheExpireTime) {
            // 返回副本，避免调用方原地修改污染缓存
            return new ArrayList<>(cachedTorrents);
        }
        cachedTorrents = DOWNLOAD.getTorrentsInfos();
        cacheExpireTime = now + CACHE_TTL_MS;
        if (cachedTorrents == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(cachedTorrents);
    }

    /**
     * 强制刷新种子列表缓存
     */
    public static synchronized void refreshTorrentsCache() {
        cachedTorrents = null;
        cacheExpireTime = 0;
    }

    /**
     * 获取种子存放文件夹
     *
     * @param ani
     * @return
     */
    public static File getTorrentDir(Ani ani) {
        String title = ani.getTitle();
        Boolean ova = ani.getOva();
        Integer season = ani.getSeason();

        File configDir = ConfigUtil.getConfigDir();

        String pinyin = PinyinUtils.getPinyin(title);
        String s = pinyin.toUpperCase().substring(0, 1);
        if (ReUtil.isMatch("^\\d$", s)) {
            s = "0";
        } else if (!ReUtil.isMatch("^[a-zA-Z]$", s)) {
            s = "#";
        }

        File torrents = new File(StrFormatter.format("{}/torrents/{}/Season {}", configDir, title, season));
        if (!torrents.exists()) {
            torrents = new File(StrFormatter.format("{}/torrents/{}/{}/Season {}", configDir, s, title, season));
        }
        if (ova) {
            torrents = new File(StrFormatter.format("{}/torrents/{}", configDir, title));
            if (!torrents.exists()) {
                torrents = new File(StrFormatter.format("{}/torrents/{}/{}", configDir, s, title));
            }
        }
        FileUtil.mkdir(torrents);
        return torrents;
    }

    /**
     * 获取种子
     *
     * @param ani
     * @param item
     * @return
     */
    public static File getTorrent(Ani ani, Item item) {
        String infoHash = item.getInfoHash();
        File torrents = getTorrentDir(ani);
        String torrent = item.getTorrent();
        if (ReUtil.contains(StringEnum.MAGNET_REG, torrent)
                || ReUtil.contains(StringEnum.ED2K_REG, torrent)) {
            return new File(torrents, infoHash + ".txt");
        }
        return new File(torrents, infoHash + ".torrent");
    }

    /**
     * 获取待完成标记(OpenList 离线提交时写入, 完成前不算已下载)
     *
     * @param ani
     * @param item
     * @return
     */
    public static File getPendingTorrentDir(Ani ani) {
        String title = ani.getTitle();
        Boolean ova = ani.getOva();
        Integer season = ani.getSeason();
        File configDir = ConfigUtil.getConfigDir();

        File dir;
        if (Boolean.TRUE.equals(ova)) {
            dir = new File(StrFormatter.format("{}/torrents/.pending/{}", configDir, title));
        } else {
            dir = new File(StrFormatter.format("{}/torrents/.pending/{}/Season {}", configDir, title, season));
        }
        FileUtil.mkdir(dir);
        return dir;
    }

    /**
     * 获取待完成标记文件
     *
     * @param ani
     * @param item
     * @return
     */
    public static File getPendingTorrent(Ani ani, Item item) {
        String infoHash = item.getInfoHash();
        File pendingDir = getPendingTorrentDir(ani);
        String torrent = item.getTorrent();
        if (ReUtil.contains(StringEnum.MAGNET_REG, torrent)
                || ReUtil.contains(StringEnum.ED2K_REG, torrent)) {
            return new File(pendingDir, infoHash + ".txt");
        }
        return new File(pendingDir, infoHash + ".torrent");
    }

    /**
     * 下载种子文件
     *
     * @param item
     */
    public static File saveTorrent(Ani ani, Item item) {
        log.info("下载种子 {}", item.getReName());
        return writeTorrentFile(getTorrent(ani, item), item);
    }

    /**
     * 下载种子文件到待完成标记位置(OpenList 提交时使用, 完成前不算已下载)
     *
     * @param ani
     * @param item
     */
    public static File saveTorrentPending(Ani ani, Item item) {
        log.info("下载种子(待完成标记) {}", item.getReName());
        return writeTorrentFile(getPendingTorrent(ani, item), item);
    }

    /**
     * OpenList 离线完成后, 将待完成标记提升为正式种子记录。
     * 无待完成标记时(已完成提升/进程重启等)不新建正式记录, 避免误判已下载。
     *
     * @param ani
     * @param item
     */
    public static void promoteTorrent(Ani ani, Item item) {
        File pending = getPendingTorrent(ani, item);
        if (!pending.exists()) {
            log.debug("无待完成标记, 跳过提升 {}", item.getReName());
            return;
        }
        File target = getTorrent(ani, item);
        if (target.exists()) {
            // 正式记录已存在(如提升过), 清理 pending 即可
            FileUtil.del(pending);
            return;
        }
        FileUtil.move(pending, target, true);
        log.info("离线任务完成, 种子记录落盘 {}", item.getReName());
    }

    /**
     * 删除待完成标记(OpenList 离线失败/超时), 预览将不再显示已下载。
     * 仅删标记文件, 不清理目录: 多订阅并行时可能误删他订阅刚创建的空目录,
     * 残留空目录无害, 交由 getPendingTorrentDir 幂等 mkdir。
     *
     * @param ani
     * @param item
     */
    public static void deletePendingTorrent(Ani ani, Item item) {
        File pending = getPendingTorrent(ani, item);
        if (pending.exists()) {
            FileUtil.del(pending);
            log.info("离线任务未完成, 清除待完成标记 {}", item.getReName());
        }
    }

    /**
     * 启动时清理 .pending 残留: 仅删除"对应正式种子记录已存在"的标记
     * (promote 后遗留或历史崩溃残留)。正式记录不存在的不动——可能是进行中的离线任务,
     * 由同 hash 提交时的 adopt/复用逻辑处理。
     */
    public static void cleanupOrphanPending() {
        try {
            File configDir = ConfigUtil.getConfigDir();
            File pendingRoot = new File(configDir, "torrents/.pending");
            if (!pendingRoot.isDirectory()) {
                return;
            }
            FileUtil.walkFiles(pendingRoot, f -> {
                if (!f.isFile()) {
                    return;
                }
                String rel = pendingRoot.toPath().relativize(f.toPath()).toString();
                File formal = new File(new File(configDir, "torrents"), rel);
                if (formal.exists()) {
                    FileUtil.del(f);
                    log.info("清理已完成任务的 pending 残留: {}", rel);
                }
            });
        } catch (Exception e) {
            log.warn("清理 pending 残留失败: {}", ExceptionUtils.getMessage(e));
        }
    }

    /**
     * 下载种子内容并写入目标文件(幂等: 已存在直接返回)
     */
    private static File writeTorrentFile(File saveTorrentFile, Item item) {
        String torrent = item.getTorrent();
        String reName = item.getReName();
        if (saveTorrentFile.exists()) {
            return saveTorrentFile;
        }

        try {
            if (ReUtil.contains(StringEnum.MAGNET_REG, torrent)
                    || ReUtil.contains(StringEnum.ED2K_REG, torrent)) {
                FileUtil.writeUtf8String(torrent, saveTorrentFile);
                log.info("种子下载完成 {}", reName);
                return saveTorrentFile;
            }

            return HttpReq.thenClose(
                    HttpReq.get(torrent),
                    res -> {
                        int status = res.getStatus();
                        if (status == 404) {
                            // 如果为 404 则写入空文件 已在 getMagnet 处理过
                            FileUtil.writeUtf8String("", saveTorrentFile);
                            log.info("种子下载完成 {}", reName);
                            return saveTorrentFile;
                        }
                        HttpReq.assertStatus(res);
                        FileUtil.writeFromStream(res.bodyStream(), saveTorrentFile, true);
                        log.info("种子下载完成 {}", reName);
                        return saveTorrentFile;
                    });
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error("下载种子时出现问题 {}", message);
            log.error(message, e);
            // 种子未下载异常，删除
            FileUtil.del(saveTorrentFile);
        }
        return saveTorrentFile;
    }

    /**
     * 登录 qBittorrent
     *
     * @return
     */
    public static synchronized Boolean login() {
        Config config = ConfigUtil.CONFIG;
        String downloadPath = config.getDownloadPathTemplate();
        if (StrUtil.isBlank(downloadPath)) {
            log.warn("下载位置未设置");
            return false;
        }
        try {
            return DOWNLOAD.login(ConfigUtil.CONFIG);
        } catch (Exception e) {
            log.error("下载工具登录失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 判断种子是否可以删除
     *
     * @param torrentsInfo
     * @return
     */
    public static Boolean isDelete(TorrentsInfo torrentsInfo) {
        Config config = ConfigUtil.CONFIG;
        Boolean awaitStalledUP = config.getAwaitStalledUP();

        TorrentsInfo.State state = torrentsInfo.getState();

        if (Objects.isNull(state)) {
            return false;
        }

        // 是否等待做种完毕
        if (awaitStalledUP) {
            return List.of(
                    TorrentsInfo.State.pausedUP.name(),
                    TorrentsInfo.State.stoppedUP.name()
            ).contains(state.name());
        }

        return List.of(
                TorrentsInfo.State.queuedUP.name(),
                TorrentsInfo.State.uploading.name(),
                TorrentsInfo.State.stalledUP.name(),
                TorrentsInfo.State.pausedUP.name(),
                TorrentsInfo.State.stoppedUP.name()
        ).contains(state.name());
    }


    /**
     * 删除已完成任务
     *
     * @param torrentsInfo 任务
     * @param forcedDelete 强制删除
     * @param deleteFiles  删除本地文件
     */
    public static synchronized Boolean delete(TorrentsInfo torrentsInfo, Boolean forcedDelete, Boolean deleteFiles) {
        Config config = ConfigUtil.CONFIG;
        Boolean delete = config.getDelete();

        String name = torrentsInfo.getName();

        if (forcedDelete) {
            log.info("删除任务 title:{} forcedDelete:{} deleteFiles:{}", name, forcedDelete, deleteFiles);
        } else {
            if (!isDelete(torrentsInfo)) {
                return false;
            }
            if (!delete) {
                return false;
            }
            log.info("删除已完成任务 title:{} deleteFiles:{}", name, deleteFiles);
        }
        // 不再固定 sleep；失败后短退避重试一次
        Boolean b = DOWNLOAD.delete(torrentsInfo, deleteFiles);
        if (!b) {
            ThreadUtil.sleep(500);
            b = DOWNLOAD.delete(torrentsInfo, deleteFiles);
        }
        if (!b) {
            log.error("删除任务失败 {}", name);
            return false;
        }
        refreshTorrentsCache();
        log.info("删除任务成功 {}", name);
        if (!deleteFiles) {
            return true;
        }
        // 清理空文件夹
        SpringUtil.getBean(ClearService.class)
                .clearParentFile(new File(torrentsInfo.getDownloadDir(), name));
        return true;
    }


    /**
     * 删除已完成任务
     *
     * @param torrentsInfo
     */
    public static synchronized Boolean delete(TorrentsInfo torrentsInfo) {
        return delete(torrentsInfo, false, false);
    }

    /**
     * 重命名
     *
     * @param torrentsInfo
     */
    public static synchronized void rename(TorrentsInfo torrentsInfo) {
        Config config = ConfigUtil.CONFIG;
        Boolean rename = config.getRename();
        if (!rename) {
            return;
        }

        List<String> tags = torrentsInfo.getTags();
        if (tags.contains(TorrentsTags.RENAME.getValue())) {
            return;
        }

        // 不再固定 sleep；失败后短退避重试一次
        Boolean renamed = DOWNLOAD.rename(torrentsInfo);
        if (!Boolean.TRUE.equals(renamed)) {
            ThreadUtil.sleep(500);
            renamed = DOWNLOAD.rename(torrentsInfo);
        }
        if (Boolean.TRUE.equals(renamed)) {
            addTags(torrentsInfo, TorrentsTags.RENAME.getValue());
            refreshTorrentsCache();
        }
    }

    /**
     * 添加标签
     *
     * @param torrentsInfo
     * @param tags
     * @return
     */
    public static Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        if (StrUtil.isBlank(tags)) {
            return false;
        }
        String name = torrentsInfo.getName();
        log.debug("添加标签 {} {}", name, tags);
        boolean b = false;
        try {
            b = DOWNLOAD.addTags(torrentsInfo, tags);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return b;
    }


    /**
     * 修改保存位置
     *
     * @param torrentsInfo
     * @param path
     */
    public static void setSavePath(TorrentsInfo torrentsInfo, String path) {
        if (StrUtil.isBlank(path)) {
            return;
        }
        try {
            log.info("修改保存位置 {} ==> {}", torrentsInfo.getName(), path);
            DOWNLOAD.setSavePath(torrentsInfo, path);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public static synchronized void load() {
        Config config = ConfigUtil.CONFIG;
        String download = config.getDownloadToolType();

        if (download.equals("Alist")) {
            download = "OpenList";
            config.setDownloadToolType(download);
            ConfigUtil.sync();
        }

        DOWNLOAD = SpringUtil.getBean(ClassUtil.loadClass("ani.rss.download." + download));
        log.info("下载工具 {}", download);
    }

    /**
     * 通过种子获取到磁力链接
     *
     * @param file
     * @return
     */
    public static String getMagnet(File file) {
        String hexHash = FileUtil.mainName(file);
        if (file.length() < 1) {
            return StrFormatter.format("magnet:?xt=urn:btih:{}", hexHash);
        }
        String extName = FileUtil.extName(file);
        if ("txt".equals(extName)) {
            return FileUtil.readUtf8String(file);
        }
        try {
            TorrentFile torrentFile = new TorrentFile(file);
            hexHash = torrentFile.getHexHash();
        } catch (Exception e) {
            log.error("转换种子为磁力链接时出现错误 {}", FileUtils.getAbsolutePath(file));
            log.error(e.getMessage(), e);
        }
        return StrFormatter.format("magnet:?xt=urn:btih:{}", hexHash);
    }

}
