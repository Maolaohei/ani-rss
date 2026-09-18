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
import ani.rss.service.LocalStateCache;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 管理下载器的调用与种子存取
 */
@Slf4j
public class TorrentUtil {
    public static volatile BaseDownload DOWNLOAD;

    // 种子列表缓存：避免短时间内重复请求下载器
    private static volatile List<TorrentsInfo> cachedTorrents;
    private static volatile long cacheExpireTime = 0;
    private static final long CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(5);

    /**
     * 缓存世代号：{@link #refreshTorrentsCache()} 自增。
     * <p>
     * 在途查询若在刷新<b>之后</b>才拿到结果，不得回填缓存——否则一次"刷新"会被一个更早发起的
     * 慢查询悄悄覆盖掉。与 OpenListApi 的目录列举缓存用世代号解决"失效 / 构建"竞态是同一套办法。
     */
    private static final AtomicLong torrentsEpoch = new AtomicLong(0L);

    /**
     * 在途的下载器查询。并发未命中时只让一个线程去问下载器，其余等它的结果（请求合并）。
     */
    private static final AtomicReference<CompletableFuture<List<TorrentsInfo>>> torrentsInFlight =
            new AtomicReference<>();

    /**
     * 等待同一在途查询的上限：等待方绝不无限期挂起，超时按"查询失败"处理。
     * P0-5：60s 会钉死 Tomcat worker（前端每 3s 轮询），收敛到 10s；超时优先回退
     * 过期缓存（stale-while-revalidate），无缓存才抛异常，避免把等待者钉满。
     */
    private static final long MAX_TORRENTS_COALESCE_WAIT_MS = 10_000L;

    /**
     * 获取任务列表（带缓存，5秒内重复调用直接返回缓存）
     * (E1) 查询异常不再吞掉返回空列表: 向上抛出, 由调用方区分"无任务"与"查询失败",
     * 避免查询失败被当作无任务放行并发上限/误判坏种; 缓存命中路径不受影响。
     * <p>
     * <b>不再使用 {@code static synchronized}</b>：下载器查询的超时是 20s（HttpReq 默认），
     * 而 {@link #login()} / {@link #delete} / {@link #renameOnce} 用的是<b>同一把类锁</b>，
     * 于是一次慢查询会把它们全部按住排队；前端每 5 秒轮询一次任务列表、RSS 轮次里每个 worker
     * 也在调本方法，争抢尤其明显。现在只有"缓存读写 + 在途标记"需要互斥，网络调用一律在锁外。
     * <p>
     * 返回的仍是<b>副本</b>：调用方（如 {@code DownloadService}）会就地 {@code remove} 已删除的任务，
     * 直接返回缓存引用会被调用方污染。
     */
    public static List<TorrentsInfo> getTorrentsInfos() {
        List<TorrentsInfo> snapshot = cachedTorrents;
        if (snapshot != null && System.currentTimeMillis() < cacheExpireTime) {
            // 返回副本，避免调用方原地修改污染缓存
            return new ArrayList<>(snapshot);
        }

        // 未命中：同一时刻只放一个线程去问下载器，其余等它的结果
        CompletableFuture<List<TorrentsInfo>> mine = new CompletableFuture<>();
        if (!torrentsInFlight.compareAndSet(null, mine)) {
            CompletableFuture<List<TorrentsInfo>> existing = torrentsInFlight.get();
            if (existing != null) {
                return awaitTorrentsInfos(existing);
            }
            // 恰好被前一个查询清掉了：当作无人查询，自己发一次
        }
        try {
            long epoch = torrentsEpoch.get();
            List<TorrentsInfo> fetched = DOWNLOAD.getTorrentsInfos();
            if (torrentsEpoch.get() == epoch) {
                cachedTorrents = fetched;
                cacheExpireTime = System.currentTimeMillis() + CACHE_TTL_MS;
            }
            mine.complete(fetched);
            return fetched == null ? new ArrayList<>() : new ArrayList<>(fetched);
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            torrentsInFlight.compareAndSet(mine, null);
        }
    }

    /**
     * 等待同一在途查询的结果。
     * <p>
     * 超时 / 被中断一律抛异常而<b>不返回空列表</b>——空列表会被调用方当成"下载器里没有任务"，
     * 从而放行并发上限或误判坏种，正是 E1 要消灭的误判。
     */
    private static List<TorrentsInfo> awaitTorrentsInfos(CompletableFuture<List<TorrentsInfo>> future) {
        try {
            List<TorrentsInfo> result = future.get(MAX_TORRENTS_COALESCE_WAIT_MS, TimeUnit.MILLISECONDS);
            return result == null ? new ArrayList<>() : new ArrayList<>(result);
        } catch (ExecutionException e) {
            // P0-5：上游失败时有过期缓存先顶上，避免前端/轮询被一次抖动打成 500
            List<TorrentsInfo> stale = cachedTorrents;
            if (stale != null) {
                log.warn("下载器查询失败，回退过期缓存（{} 条）", stale.size());
                return new ArrayList<>(stale);
            }
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("等待下载器任务列表失败", cause);
        } catch (TimeoutException e) {
            List<TorrentsInfo> stale = cachedTorrents;
            if (stale != null) {
                log.warn("等待下载器任务列表超时（{}ms），回退过期缓存（{} 条）",
                        MAX_TORRENTS_COALESCE_WAIT_MS, stale.size());
                return new ArrayList<>(stale);
            }
            throw new IllegalStateException(
                    "等待下载器任务列表超时（" + MAX_TORRENTS_COALESCE_WAIT_MS + "ms）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待下载器任务列表被中断", e);
        }
    }

    /**
     * 强制刷新种子列表缓存。
     * <p>
     * 不再需要类锁：只写 volatile + 递增世代号，因此不会被 {@link #login()} / {@link #delete}
     * 这类持类锁的慢操作堵住。在途查询若在本次刷新之后才拿到结果，会因世代号变化而放弃回填缓存。
     * <p>
     * <b>必须一并丢弃在途查询</b>：否则刷新<b>之后</b>才到达的线程会挂到"刷新之前发起"的那次查询上，
     * 拿到刷新前的旧结果。旧实现靠 {@code static synchronized} 让刷新必然排在在途查询之后执行，
     * 所以没有这个问题；去掉类锁后必须显式处理。已经等在那次在途查询上的调用方仍会收到它的结果，
     * 但不会再有人新加入进来。
     */
    public static void refreshTorrentsCache() {
        torrentsEpoch.incrementAndGet();
        torrentsInFlight.set(null);
        cachedTorrents = null;
        cacheExpireTime = 0;
    }

    /**
     * 已 mkdir 的目录缓存：getTorrentDir/getPendingTorrentDir 是高频调用，
     * FileUtil.mkdir 每次都要 stat+mkdir 系统调用，命中缓存直接跳过。
     */
    private static final Set<String> MKDIR_CACHE = ConcurrentHashMap.newKeySet();

    private static void mkdirCached(File dir) {
        String path = FileUtils.getAbsolutePath(dir);
        if (MKDIR_CACHE.contains(path)) {
            return;
        }
        FileUtil.mkdir(dir);
        MKDIR_CACHE.add(path);
    }

    /**
     * 获取种子存放文件夹
     *
     * @param ani
     * @return
     */
    public static File getTorrentDir(Ani ani) {
        // 标题拼入种子缓存目录，先做路径段清洗防穿越
        String title = RenameUtil.getName(ani.getTitle());
        Boolean ova = ani.getOva();
        Integer season = ani.getSeason();

        File configDir = ConfigUtil.getConfigDir();

        String pinyin = PinyinUtils.getPinyin(title);
        // 标题清洗后可能为空: 空串取首字母会 StringIndexOutOfBounds, 回落 "#"(用 isBlank 同时防 null)
        String s = StrUtil.isBlank(pinyin) ? "#" : pinyin.toUpperCase().substring(0, 1);
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
        mkdirCached(torrents);
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

        // port upstream 3.2.30: 缓存文件已存在时直接复用，
        // 避免同 infoHash 在磁力/种子表示间切换导致缓存文件名漂移、重复下载
        File txtFile = new File(torrents, infoHash + ".txt");
        File torrentFile = new File(torrents, infoHash + ".torrent");

        if (txtFile.exists()) {
            return txtFile;
        }
        if (torrentFile.exists()) {
            return torrentFile;
        }

        if (ReUtil.contains(StringEnum.MAGNET_REG, torrent)
                || ReUtil.contains(StringEnum.ED2K_REG, torrent)) {
            return txtFile;
        }
        return torrentFile;
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
        mkdirCached(dir);
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
     * @param downloadPath 下载目录（网盘路径）。用于把本集<b>增量追加</b>进订阅级快照，
     *                     而不是整份失效后重新列举网盘——列举是最贵的一步，
     *                     而"这一集刚落地"是已知的增量事实。
     */
    public static void promoteTorrent(Ani ani, Item item, String downloadPath) {
        File pending = getPendingTorrent(ani, item);
        if (!pending.exists()) {
            log.debug("无待完成标记, 跳过提升 {}", item.getReName());
            return;
        }
        File target = getTorrent(ani, item);
        if (target.exists()) {
            // 正式记录已存在(如提升过), 清理 pending 即可
            FileUtil.del(pending);
            appendEpisodeToLocalState(ani, item, downloadPath);
            return;
        }
        FileUtil.move(pending, target, true);
        log.info("离线任务完成, 种子记录落盘 {}", item.getReName());
        appendEpisodeToLocalState(ani, item, downloadPath);
    }

    /**
     * 离线归位成功：把本集并入订阅级「本地状态快照」。
     * <p>
     * 快照不存在时 {@link LocalStateCache#appendEpisode} 是 no-op（下次构建会列举出全量），
     * 所以这里不需要兜底失效。真正的结构性变更（删除/洗版/模板变更）仍走 invalidate。
     */
    private static void appendEpisodeToLocalState(Ani ani, Item item, String downloadPath) {
        try {
            Set<String> keys = RenameUtil.episodeIndexKeys(ani, item);
            if (!keys.isEmpty()) {
                LocalStateCache.appendEpisode(ani == null ? null : ani.getId(), downloadPath, keys);
            }
        } catch (Exception e) {
            // 追加失败只影响缓存新鲜度，不影响已落盘的记录，下一轮列举会自然收敛
            log.debug("追加本地状态快照失败 {}: {}", item.getReName(), e.getMessage());
        }
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
     * 启动时清理 .pending 残留(启动瞬间本进程必无在途离线任务, 全部 pending 均可安全清理):
     * - 正式种子记录已存在: 任务已完成(promote 后遗留或历史崩溃残留), 删除标记;
     * - 正式记录不存在: 孤儿 pending —— 崩溃前离线等待未完成, 若保留会被 RSS 轮次
     *   "pending 存在即跳过"永久拦截, 该集静默漏下。因此同样删除,
     *   等待下轮 RSS 重新提交离线任务(远端任务将由 adopt/10008 复用逻辑接管, 不会重复下载)。
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
                FileUtil.del(f);
                if (formal.exists()) {
                    log.info("清理已完成任务的 pending 残留: {}", rel);
                } else {
                    log.warn("孤儿 pending 已清除，等待下轮 RSS 重新提交(远端任务将由 10008/adopt 逻辑接管): {}", rel);
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
     * 登录成功缓存：成功后 60s 内直接返回，避免每次轮询都串行登录下载器。
     * P1-1：login 不再 static synchronized，改双检缓存（简单实现）。
     */
    private static final Object LOGIN_LOCK = new Object();
    private static volatile long loginSuccessExpire = 0L;

    /**
     * 登录 qBittorrent
     *
     * @return
     */
    public static Boolean login() {
        Config config = ConfigUtil.CONFIG;
        String downloadPath = config.getDownloadPathTemplate();
        if (StrUtil.isBlank(downloadPath)) {
            log.warn("下载位置未设置");
            return false;
        }
        // P1-1：双检成功缓存，命中直接返回
        if (System.currentTimeMillis() < loginSuccessExpire) {
            return true;
        }
        synchronized (LOGIN_LOCK) {
            if (System.currentTimeMillis() < loginSuccessExpire) {
                return true;
            }
            try {
                boolean ok = DOWNLOAD.login(ConfigUtil.CONFIG);
                if (ok) {
                    loginSuccessExpire = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60);
                }
                return ok;
            } catch (Exception e) {
                log.error("下载工具登录失败: {}", e.getMessage());
                return false;
            }
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
     * <p>
     * P1-2：去方法级 synchronized，sleep(500)+重试保持在锁外。
     * 下载器侧删除幂等，并发重复删同一任务安全。
     *
     * @param torrentsInfo 任务
     * @param forcedDelete 强制删除
     * @param deleteFiles  删除本地文件
     */
    public static Boolean delete(TorrentsInfo torrentsInfo, Boolean forcedDelete, Boolean deleteFiles) {
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
        // 清理空文件夹(辅助操作: 失败/上下文不可用不视为删除失败)
        try {
            SpringUtil.getBean(ClearService.class)
                    .clearParentFile(new File(torrentsInfo.getDownloadDir(), name));
        } catch (Exception e) {
            log.debug("清理空文件夹失败(忽略): {}", ExceptionUtils.getMessage(e));
        }
        return true;
    }


    /**
     * 删除已完成任务
     * <p>
     * P1-2：配套去同步（委托的三参方法已去 synchronized）。
     *
     * @param torrentsInfo
     */
    public static Boolean delete(TorrentsInfo torrentsInfo) {
        return delete(torrentsInfo, false, false);
    }

    /**
     * 重命名(整体不再 static synchronized, A4)。
     * 仅"第一次 rename 调用 + 状态读取"在类锁短临界区内完成;
     * 失败退避重试循环在锁外执行, 不再把 RSS worker 长时间阻塞在类锁上。
     *
     * @param torrentsInfo
     */
    public static void rename(TorrentsInfo torrentsInfo) {
        Config config = ConfigUtil.CONFIG;
        Boolean rename = config.getRename();
        if (!rename) {
            return;
        }

        List<String> tags = torrentsInfo.getTags();
        if (tags.contains(TorrentsTags.RENAME.getValue())) {
            return;
        }

        // 第一次调用: 同步查 DOWNLOAD 状态并下发 rename, 仅此处持锁
        Boolean renamed = renameOnce(torrentsInfo);
        if (!Boolean.TRUE.equals(renamed)) {
            // 不再固定 sleep；失败后短退避重试一次(锁外, A4)
            ThreadUtil.sleep(500);
            renamed = renameOnce(torrentsInfo);
        }
        if (Boolean.TRUE.equals(renamed)) {
            addTags(torrentsInfo, TorrentsTags.RENAME.getValue());
            refreshTorrentsCache();
            // 改名后文件名才含 SxxExx、才进入集数索引：不失效的话用户改完名刷新仍看到旧结果。
            // 按下载目录失效而非整体失效——一轮里每下完一集都会走这里，整体失效等于把缓存清空。
            LocalStateCache.invalidateByDownloadPath(torrentsInfo.getDownloadDir());
        }
    }

    /**
     * 单次 rename 下发。
     * <p>
     * P1-3：去掉类锁。调用方 RenameTask 已串行，per-hash 改名由下载器侧保证，
     * 无需再用类锁保护 RenameCacheUtil 短临界区之外的网络调用。
     */
    private static Boolean renameOnce(TorrentsInfo torrentsInfo) {
        return DOWNLOAD.rename(torrentsInfo);
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
     * 当前激活的下载器是否为离线长等待型（OpenList/Alist 网盘离线工具）。
     * 能力查询优先于配置字符串比较，新增离线型下载器无需再改各处分支判断。
     */
    public static boolean isOfflineTool() {
        BaseDownload download = DOWNLOAD;
        if (download != null) {
            return download.isOffline();
        }
        // DOWNLOAD 未初始化（启动早期/加载失败）：回退配置字符串判断，保持原语义
        String tool = ConfigUtil.CONFIG.getDownloadToolType();
        return tool != null && ("OpenList".equalsIgnoreCase(tool) || "Alist".equalsIgnoreCase(tool));
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
