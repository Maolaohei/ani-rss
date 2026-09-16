package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.FileUtils;
import ani.rss.download.OpenList;
import ani.rss.download.qBittorrent;
import ani.rss.entity.*;
import ani.rss.entity.web.Result;
import ani.rss.enums.StringEnum;
import ani.rss.exception.ResultException;
import ani.rss.service.DownloadService;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.ItemsUtil;
import ani.rss.util.other.RenameUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.thread.NamedThreadFactory;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.bittorrent.TorrentFile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@RestController
public class CollectionController extends BaseController {

    /**
     * 合集下载的「等待元数据 + 逐文件重命名」后处理池。
     * <p>
     * 这段逻辑最坏要跑 ~32.5 秒（5×500ms 等元数据 + 30×1000ms 等重命名），
     * 原实现直接在 Tomcat 请求线程里跑：并发几个合集就能吃掉一批 worker（上限 100），
     * 且中途没有任何进度反馈（P1-8）。这里挪到独立线程，请求立即返回。
     * <p>
     * 2 个线程 + 有界队列：合集下载本身是低频人工操作，够用且不会无限堆积。
     */
    private static final ExecutorService COLLECTION_POST_POOL = ExecutorBuilder.create()
            .setCorePoolSize(2)
            .setMaxPoolSize(2)
            .setWorkQueue(new LinkedBlockingQueue<>(32))
            .setThreadFactory(new NamedThreadFactory("collection-post", true))
            .setHandler(new ThreadPoolExecutor.CallerRunsPolicy())
            .build();

    @Resource
    private DownloadService downloadService;

    @Auth
    @Operation(summary = "开始下载合集")
    @PostMapping("/startCollection")
    public Result<Void> startCollection(@RequestBody CollectionInfo collectionInfo) throws IOException {
        String torrent = collectionInfo.getTorrent();
        Assert.notBlank(torrent, "种子内容为空, 请重新上传种子文件");
        File tempFile = FileUtil.createTempFile();
        Base64.decodeToFile(torrent, tempFile);
        TorrentFile torrentFile;
        try {
            torrentFile = new TorrentFile(tempFile);
        } catch (Exception e) {
            throw ResultException.exception("种子文件解析失败, 请确认是有效的 .torrent 文件");
        }
        Ani ani = collectionInfo.getAni();
        // 尽早校验: 标题会进下载路径、季数会被拆箱, 有问题要在动手下载之前就拒掉
        AniUtil.verifyCollectionAni(ani);
        String title = ani.getTitle();
        String subgroup = ani.getSubgroup();
        String downloadPath = ani.getDownloadPath();

        String name = StrFormatter.format("[{}] {} 第{}季", subgroup, title, ani.getSeason());

        Config config = ConfigUtil.CONFIG;
        String downloadTool = config.getDownloadToolType();

        // 预览计划: 一次解析, qB 与 OpenList 共用
        List<Item> plan = preview(collectionInfo);
        Assert.notEmpty(plan, "预览结果为空, 请检查匹配/排除规则");

        // OpenList/Alist: 走离线下载全链路(提交/等待/重命名/归位/清理/通知), 与订阅体验一致
        if ("OpenList".equalsIgnoreCase(downloadTool) || "Alist".equalsIgnoreCase(downloadTool)) {
            Result<Void> result = startCollectionByOpenList(ani, plan, tempFile, torrentFile.getName());
            // 合集关联的番剧也写入订阅列表(仅入列、不轮询、去重), 使其在「订阅」里可见可管理
            AniUtil.addCollectionAni(ani);
            return result;
        }

        if (!"qBittorrent".equalsIgnoreCase(downloadTool)) {
            throw ResultException.exception(StrFormatter.format(
                    "合集下载暂时只支持 qBittorrent / OpenList, 当前: {}", downloadTool));
        }

        download(name, tempFile, downloadPath, List.of("ANI-RSS合集下载", subgroup));

        TorrentsInfo torrentsInfo = new TorrentsInfo()
                .setHash(torrentFile.getHexHash());

        // 合集关联的番剧也写入订阅列表(仅入列、不轮询、去重), 使其在「订阅」里可见可管理。
        // 放在提交后立即执行：后处理已异步化，若等它跑完再入列，用户要 ~32.5s 后才看得到订阅
        AniUtil.addCollectionAni(ani);

        // 等待元数据 + 逐文件重命名放后台：最坏 ~32.5s，不能占用 Tomcat 请求线程（P1-8）
        COLLECTION_POST_POOL.execute(() -> finishQbCollection(torrentsInfo, plan, config));

        return Result.success("已经开始下载合集, 正在后台等待元数据并重命名, 进度可在日志中查看");
    }

    /**
     * qBittorrent 合集后处理：等元数据 → 逐文件重命名 → 启动任务。
     * <p>
     * 已移到 {@link #COLLECTION_POST_POOL}，不再阻塞请求线程。相比原实现还做了两件事：
     * <ul>
     *   <li>记录「已经成功发过重命名」的旧路径，避免后续轮次对同一文件重发 {@code renameFile}；</li>
     *   <li>每 5 轮打一行进度日志——原实现全程无任何输出，用户只能干等。</li>
     * </ul>
     */
    private static void finishQbCollection(TorrentsInfo torrentsInfo, List<Item> plan, Config config) {
        try {
            List<qBittorrent.FileEntity> files = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ThreadUtil.sleep(500);
                try {
                    files.addAll(qBittorrent.files(torrentsInfo, false, config));
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
                if (!files.isEmpty()) {
                    // 添加下载完成
                    break;
                }
            }

            Map<String, String> reNameMap = plan
                    .stream()
                    .map(item -> {
                        Optional<qBittorrent.FileEntity> fileEntity = files.stream()
                                .filter(f -> new File(f.getName()).getName().equals(new File(item.getTitle()).getName()))
                                .filter(f -> f.getSize().longValue() == item.getLength())
                                .findFirst();
                        if (fileEntity.isEmpty()) {
                            return null;
                        }
                        String oldPath = fileEntity.get().getName();
                        return item.setTitle(oldPath);
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(
                            Item::getTitle,
                            Item::getReName
                    ));

            if (reNameMap.isEmpty()) {
                log.warn("合集后处理: 未匹配到任何待重命名文件, 直接启动任务");
                qBittorrent.start(torrentsInfo, config);
                return;
            }

            String host = config.getDownloadToolHost();
            // 已经发过重命名的旧路径：避免下一轮刷新后重复 POST
            Set<String> renamedPaths = new HashSet<>();

            for (int i = 0; i < 30; i++) {
                for (qBittorrent.FileEntity file : files) {
                    String oldPath = file.getName();
                    String newPath = reNameMap.get(oldPath);

                    if (!reNameMap.containsKey(oldPath)) {
                        if (!reNameMap.containsValue(oldPath) && file.getPriority() > 0) {
                            HttpReq.post(host + "/api/v2/torrents/filePrio")
                                    .form("hash", torrentsInfo.getHash())
                                    .form("id", file.getIndex())
                                    .form("priority", 0)
                                    .thenFunction(HttpResponse::isOk);
                        }
                        continue;
                    }
                    if (!renamedPaths.add(oldPath)) {
                        // 本轮已经发过, 等下一轮刷新结果
                        continue;
                    }
                    log.info("重命名 {} ==> {}", oldPath, newPath);
                    HttpReq.post(host + "/api/v2/torrents/renameFile")
                            .form("hash", torrentsInfo.getHash())
                            .form("oldPath", oldPath)
                            .form("newPath", newPath)
                            .thenFunction(HttpResponse::isOk);
                }
                files.clear();
                files.addAll(qBittorrent.files(torrentsInfo, false, config));

                if (CollUtil.containsAll(files.stream()
                        .map(qBittorrent.FileEntity::getName)
                        .toList(), reNameMap.values())) {
                    // 所有命名已完成
                    log.info("合集重命名完成, 共 {} 个文件", reNameMap.size());
                    break;
                }
                // 命名有遗漏 继续
                if (i % 5 == 0) {
                    log.info("合集重命名进行中: 第 {} 轮, 已完成 {}/{}",
                            i + 1, countRenamed(files, reNameMap), reNameMap.size());
                }
                ThreadUtil.sleep(1000);
            }

            qBittorrent.start(torrentsInfo, config);
        } catch (Exception e) {
            log.error("合集后处理失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 统计已按计划重命名完成的文件数（按当前文件列表的实际名称比对）
     */
    private static int countRenamed(List<qBittorrent.FileEntity> files, Map<String, String> reNameMap) {
        Set<String> names = files.stream()
                .map(qBittorrent.FileEntity::getName)
                .collect(Collectors.toSet());
        return (int) reNameMap.values().stream().filter(names::contains).count();
    }

    /**
     * 合集走 OpenList 离线下载: 复用订阅离线全链路(提交/等待/10008/卡住重提/超时/进度),
     * 完成后按预览计划自动重命名并归位到下载目录顶层, 未匹配文件随临时目录清理。
     * 提交即受理, 等待在后台进行, 进度可在任务管理器中查看。
     */
    private Result<Void> startCollectionByOpenList(Ani ani, List<Item> plan, File torrentFile, String torrentName) {
        Assert.isTrue(TorrentUtil.login(), "下载器登录失败");
        if (!(TorrentUtil.DOWNLOAD instanceof OpenList openList)) {
            throw ResultException.exception("当前离线下载器不支持合集下载");
        }
        // 下载位置与订阅口径一致(尊重自定义下载位置/模板), OpenList 为网盘虚拟路径
        String savePath = downloadService.getDownloadPath(ani);
        Boolean ok = openList.downloadCollection(ani, plan, savePath, torrentFile, torrentName);
        Assert.isTrue(Boolean.TRUE.equals(ok), "合集离线下载提交失败, 请查看日志");
        return Result.success("合集已提交离线下载, 完成后将自动重命名并归位, 进度可在任务管理器中查看");
    }

    @Auth
    @Operation(summary = "合集预览")
    @PostMapping("/previewCollection")
    public Result<List<Item>> previewCollection(@RequestBody CollectionInfo collectionInfo) {
        List<Item> preview = preview(collectionInfo);
        preview = CollUtil.sort(new ArrayList<>(preview), Comparator.comparingDouble(it -> {
            Double episode = it.getEpisode();
            return ObjectUtil.defaultIfNull(episode, 0.0);
        }));
        return Result.success(preview);
    }

    @Auth
    @Operation(summary = "获取合集字幕组")
    @PostMapping("/getCollectionSubgroup")
    public Result<String> getCollectionSubgroup(@RequestBody CollectionInfo collectionInfo) {
        List<Item> preview = preview(collectionInfo);
        preview = CollUtil.sort(new ArrayList<>(preview), Comparator.comparingDouble(it -> {
            Double episode = it.getEpisode();
            return ObjectUtil.defaultIfNull(episode, 0.0);
        }));
        String subgroup = ItemsUtil.getSubgroup(preview);

        Result<String> result = Result.success();
        result.setData(subgroup);
        return result;
    }


    public static synchronized List<Item> preview(CollectionInfo collectionInfo) {
        String torrent = collectionInfo.getTorrent();
        Assert.notBlank(torrent, "种子内容为空, 请重新上传种子文件");
        File tempFile = FileUtil.createTempFile();
        Base64.decodeToFile(torrent, tempFile);
        TorrentFile torrentFile;
        try {
            torrentFile = new TorrentFile(tempFile);
        } catch (Exception e) {
            throw ResultException.exception("种子文件解析失败, 请确认是有效的 .torrent 文件");
        }

        Ani ani = collectionInfo.getAni();
        long[] lengths = torrentFile.getLengths();
        AtomicInteger index = new AtomicInteger(0);

        List<String> match = ani.getMatch();
        List<String> exclude = ani.getExclude();
        Boolean globalExclude = ani.getGlobalExclude();
        Config config = ConfigUtil.CONFIG;
        List<String> globalExcludeList = config.getExclude();

        Function<String, String> map = s -> {
            String subgroup = ReUtil.get(StringEnum.SUBGROUP_REG_STR, s, 1);
            if (StrUtil.isBlank(subgroup)) {
                return s;
            }
            if (subgroup.equals(ani.getSubgroup())) {
                return ReUtil.get(StringEnum.SUBGROUP_REG_STR, s, 2);
            }
            return "";
        };

        return Arrays.stream(torrentFile.getFilenames())
                .map(name -> {
                    name = CharsetUtil.convert(name, "ISO-8859-1", CharsetUtil.UTF_8);
                    name = ReUtil.replaceAll(name, "[\\\\/]$", "");
                    name = name.replace("\\", "/");
                    return name;
                })
                .filter(name -> name.contains("."))
                .toList()
                .stream()
                .map(name -> {
                    int idx = index.getAndIncrement();
                    Item item = new Item();
                    return item.setTitle(name)
                            .setLength(lengths[idx]);
                })
                .filter(item -> {
                    String name = item.getTitle();

                    if (name.startsWith("_____padding_file_") && name.contains("BitComet")) {
                        return false;
                    }

                    // 排除
                    if (!exclude.isEmpty()) {
                        if (exclude.stream().map(map).filter(StrUtil::isNotBlank).anyMatch(s -> ReUtil.contains(s, name))) {
                            return false;
                        }
                    }

                    // 匹配
                    if (!match.isEmpty()) {
                        if (match.stream().map(map).filter(StrUtil::isNotBlank).anyMatch(s -> !ReUtil.contains(s, name))) {
                            return false;
                        }
                    }

                    // 全局排除
                    if (globalExclude) {
                        return globalExcludeList.stream().map(map).filter(StrUtil::isNotBlank).noneMatch(s -> ReUtil.contains(s, name));
                    }
                    return true;
                })
                .map(item -> {
                    long length = item.getLength();

                    String formatSize = FileUtils.formatSize(length, true);

                    item
                            .setFormatSize(formatSize)
                            .setSubgroup(ani.getSubgroup());

                    RenameUtil.rename(ani, item);

                    String reName = item.getReName();

                    if (StrUtil.isBlank(reName)) {
                        return null;
                    }

                    String title = item.getTitle();

                    String extName = FileUtil.extName(title);

                    if (FileUtils.isSubtitleFormat(extName)) {
                        // 语言后缀与下载器重命名口径一致（含 jpsc/jptc 等双语标识，统一小写）；
                        // 原实现取 mainName 的最后一段，会把 "xxx.1080p.ass" 的 1080p 误当语言
                        String lang = FileUtils.extractSubtitleLangSuffix(title);
                        if (StrUtil.isNotBlank(lang)) {
                            reName += "." + lang;
                        }
                    }

                    reName = reName + "." + extName;

                    return item.setReName(reName)
                            .setLength(length);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    public static synchronized void download(String name, File torrentFile, String savePath, List<String> tags) {
        Config config = ConfigUtil.CONFIG;
        String host = config.getDownloadToolHost();
        String download = config.getDownloadToolType();
        Assert.isTrue("qBittorrent".equals(download), "合集下载暂时只支持 qBittorrent");

        Assert.isTrue(TorrentUtil.login(), "下载器登录失败");

        Integer ratioLimit = config.getRatioLimit();
        Integer seedingTimeLimit = config.getSeedingTimeLimit();
        Integer inactiveSeedingTimeLimit = config.getInactiveSeedingTimeLimit();

        Long upLimit = config.getUpLimit() * 1024;
        Long dlLimit = config.getDlLimit() * 1024;

        Boolean qbUseDownloadPath = config.getQbUseDownloadPath();

        String qbContentLayout = config.getQbContentLayout();

        HttpReq.post(host + "/api/v2/torrents/add")
                .form("torrents", torrentFile)
                .form("addToTopOfQueue", false)
                .form("autoTMM", false)
                .form("category", "")
                .form("contentLayout", qbContentLayout)
                .form("dlLimit", dlLimit)
                .form("firstLastPiecePrio", false)
                .form("paused", true)
                .form("stopped", true)
                .form("rename", name)
                .form("savepath", savePath)
                .form("sequentialDownload", false)
                .form("skip_checking", false)
                .form("stopCondition", "None")
                .form("upLimit", upLimit)
                .form("useDownloadPath", qbUseDownloadPath)
                .form("tags", CollUtil.join(tags, ","))
                .form("ratioLimit", ratioLimit)
                .form("seedingTimeLimit", seedingTimeLimit)
                .form("inactiveSeedingTimeLimit", inactiveSeedingTimeLimit)
                .then(HttpReq::assertStatus);
    }

}
