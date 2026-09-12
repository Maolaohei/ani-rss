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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@RestController
public class CollectionController extends BaseController {

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

        String host = config.getDownloadToolHost();

        for (int i = 0; i < 30; i++) {
            for (qBittorrent.FileEntity file : files) {
                String oldPath = file.getName();
                String newPath = reNameMap.get(oldPath);

                if (!reNameMap.containsKey(oldPath)) {
                    if (!reNameMap.containsValue(oldPath) && file.getPriority() > 0) {
                        HttpReq.post(host + "/api/v2/torrents/filePrio")
                                .form("hash", torrentFile.getHexHash())
                                .form("id", file.getIndex())
                                .form("priority", 0)
                                .thenFunction(HttpResponse::isOk);
                    }
                    continue;
                }
                log.info("重命名 {} ==> {}", oldPath, newPath);
                HttpReq.post(host + "/api/v2/torrents/renameFile")
                        .form("hash", torrentFile.getHexHash())
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
                break;
            }
            // 命名有遗漏 继续
            ThreadUtil.sleep(1000);
        }

        qBittorrent.start(torrentsInfo, config);
        // 合集关联的番剧也写入订阅列表(仅入列、不轮询、去重), 使其在「订阅」里可见可管理
        AniUtil.addCollectionAni(ani);
        return Result.success("已经开始下载合集");
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
                        String lang = FileUtil.extName(FileUtil.mainName(title));
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
