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
import ani.rss.util.other.TorrentMetadata;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.CollectionAnalyzer;
import ani.rss.util.other.ItemsUtil;
import ani.rss.util.other.MagnetTorrentUtil;
import ani.rss.util.other.RenameUtil;
import ani.rss.util.other.TorrentPlanUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.bean.BeanUtil;
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
import java.util.regex.Pattern;
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
        Assert.notBlank(torrent, "种子内容为空, 请重新上传种子文件或填入磁力链接");
        // 磁力链接返回的是缓存文件（不可删），上传的种子才是本次请求独占的临时文件
        boolean magnet = MagnetTorrentUtil.isMagnet(torrent);
        File tempFile = getTorrentFile(torrent);
        try {
            TorrentMetadata torrentFile;
            try {
                torrentFile = TorrentMetadata.from(tempFile);
            } catch (Exception e) {
                throw ResultException.exception("种子文件解析失败, 请确认是有效的 .torrent 文件或磁力链接");
            }
            Ani ani = collectionInfo.getAni();
            // 尽早校验: 标题会进下载路径、季数会被拆箱, 有问题要在动手下载之前就拒掉
            AniUtil.verifyCollectionAni(ani);

            // 拆分模式: 每段一份独立计划/独立落盘目录/独立订阅; 单段模式与旧行为完全一致
            List<SegmentPlan> plans = buildSegmentPlans(collectionInfo, torrentFile);
            Assert.notEmpty(plans, "预览结果为空, 请检查匹配/排除规则");

            Config config = ConfigUtil.CONFIG;
            String downloadTool = config.getDownloadToolType();

            // OpenList/Alist: 整份种子只提交一次离线任务（按段重复提交 = 重复下载同一份种子），
            // 归位阶段再按每条的 targetDir 分目录落盘
            if ("OpenList".equalsIgnoreCase(downloadTool) || "Alist".equalsIgnoreCase(downloadTool)) {
                List<Item> plan = plans.stream().flatMap(p -> p.items().stream()).toList();
                String savePath = commonRoot(plans.stream().map(SegmentPlan::downloadPath).toList());
                Result<Void> result = startCollectionByOpenList(
                        plans.get(0).ani(), plan, tempFile, torrentFile.getName(), savePath);
                // 合集关联的番剧也写入订阅列表(仅入列、不轮询、去重), 使其在「订阅」里可见可管理
                plans.forEach(p -> AniUtil.addCollectionAni(p.ani()));
                return result;
            }

            if (!"qBittorrent".equalsIgnoreCase(downloadTool)) {
                throw ResultException.exception(StrFormatter.format(
                        "合集下载暂时只支持 qBittorrent / OpenList, 当前: {}", downloadTool));
            }

            return startCollectionByQb(ani, plans, tempFile, torrentFile, config);
        } finally {
            // 磁力链接指向的是解析缓存，删了下次还得重新抓一次元数据
            if (!magnet) {
                FileUtil.del(tempFile);
            }
        }
    }

    /**
     * 一个分段的执行计划：独立 Ani（标题/季/偏移/模板）+ 独立落盘目录 + 该段命中的文件。
     *
     * @param segment      分段描述；单段模式为 null
     * @param ani          该段实际使用的订阅信息（模板/季/偏移已按分段覆盖）
     * @param items        该段的预览计划（reName 已按段重命名）
     * @param downloadPath 该段最终落盘目录（绝对路径 / 网盘路径）
     */
    public record SegmentPlan(CollectionSegment segment, Ani ani, List<Item> items, String downloadPath) {
    }

    /**
     * 按合集方案构建执行计划。
     * <p>
     * 单段（{@code mode != SPLIT}）：与旧实现完全一致，一份种子一个计划一个目录。
     * 多段：每段复制一份基础 Ani，按分段覆盖季/偏移/标题/剧场版标记，并把该段目录
     * 作为额外匹配条件（match 是 AND 语义，见 {@link TorrentPlanUtil#build}），
     * 因此同一份种子解析一次即可得到多段互不重叠的计划。
     */
    public List<SegmentPlan> buildSegmentPlans(CollectionInfo collectionInfo, TorrentMetadata torrentFile) {
        return buildSegmentPlans(collectionInfo, torrentFile, true);
    }

    /**
     * @param verify 是否校验分段后的订阅信息（执行时必须校验，预览时放宽以便边改边看）
     */
    public List<SegmentPlan> buildSegmentPlans(CollectionInfo collectionInfo, TorrentMetadata torrentFile,
                                               boolean verify) {
        Ani base = collectionInfo.getAni();
        List<CollectionSegment> segments = collectionInfo.getSegments();
        boolean split = "SPLIT".equalsIgnoreCase(collectionInfo.getMode())
                || (StrUtil.isBlank(collectionInfo.getMode()) && CollUtil.isNotEmpty(segments));
        if (!split || CollUtil.isEmpty(segments)) {
            List<Item> items = TorrentPlanUtil.build(torrentFile, base);
            String path = downloadService.getDownloadPath(base);
            for (Item item : items) {
                item.setTargetDir(path);
            }
            return List.of(new SegmentPlan(null, base, items, path));
        }

        List<SegmentPlan> plans = new ArrayList<>();
        for (CollectionSegment segment : segments) {
            Ani ani = segmentAni(base, segment);
            if (verify) {
                AniUtil.verifyCollectionAni(ani);
            }
            List<Item> items = TorrentPlanUtil.build(torrentFile, ani);
            if (items.isEmpty()) {
                log.warn("合集分段「{}」未匹配到文件，已跳过", segment.getName());
                continue;
            }
            String path = StrUtil.isNotBlank(segment.getDownloadPath())
                    ? segment.getDownloadPath()
                    : downloadService.getDownloadPath(ani);
            for (Item item : items) {
                item.setSegmentId(segment.getId());
                item.setTargetDir(path);
            }
            plans.add(new SegmentPlan(segment, ani, items, path));
        }
        return plans;
    }

    /**
     * 由基础 Ani + 分段描述派生该段实际使用的 Ani。
     * <p>
     * 只覆盖会影响「匹配 / 命名 / 落盘」的字段：标题、季、集数偏移、剧场版标记、目录约束。
     * 其它字段（字幕组、模板、评分、洗版策略）沿用弹窗里的基础设置。
     */
    public static Ani segmentAni(Ani base, CollectionSegment segment) {
        Ani ani = new Ani();
        BeanUtil.copyProperties(base, ani);
        // 列表字段会被下游原地改写，这里必须重建
        ani.setMatch(scopeMatch(base.getMatch(), segment.getScope()));
        ani.setExclude(base.getExclude() == null ? new ArrayList<>() : new ArrayList<>(base.getExclude()));

        // 分段各自继承「自定义下载位置」会让所有段落进同一个目录（剧场版进番剧库、
        // S2 也进 Season 1），这里必须放开，让每段按自己的标题 / 季 / 剧场版标记重新算目录。
        // 用户在分段卡片里改过的目录会写回 segment.downloadPath，优先级仍高于模板。
        ani.setCustomDownloadPath(false);

        ani.setTitle(StrUtil.blankToDefault(segment.getTitle(), base.getTitle()));
        ani.setSeason(segment.getSeason() == null ? base.getSeason() : segment.getSeason());
        // 未显式指定偏移时沿用基础设置，避免单段/自定义偏移被静默清零
        ani.setOffset(segment.getOffset() == null ? base.getOffset() : segment.getOffset());

        boolean movie = Boolean.TRUE.equals(segment.getOva())
                || "MOVIE".equalsIgnoreCase(segment.getKind());
        ani.setOva(movie);
        if (movie) {
            ani.setMediaType("movie");
        } else if ("OVA".equalsIgnoreCase(segment.getKind())) {
            // OVA 段按 OVA 归类，但命名仍走 TV 模板（ova=true 在历史数据里等同于剧场版）
            ani.setMediaType("ova");
        } else {
            ani.setMediaType("tv");
        }
        return ani;
    }

    /**
     * 把分段命中的种子目录拼进匹配规则（AND 语义，不会放宽原有规则）。
     *
     * @param match 基础匹配规则
     * @param scope 该段的种子内根目录，多行分隔；空表示整份种子
     */
    static List<String> scopeMatch(List<String> match, String scope) {
        List<String> list = match == null ? new ArrayList<>() : new ArrayList<>(match);
        if (StrUtil.isBlank(scope)) {
            return list;
        }
        String pattern = Arrays.stream(scope.split("\n"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .map(s -> Pattern.quote(s.replace("\\", "/")) + "/")
                .collect(Collectors.joining("|", "^(?:", ")"));
        list.add(pattern);
        return list;
    }

    /**
     * qBittorrent 合集提交：一份种子只提交一次，重命名时按分段目录写相对子路径。
     * <p>
     * qB 的 {@code renameFile} 的 newPath 是相对 savepath 的路径，因此把「该段目录相对
     * 公共祖先的路径」拼在文件名前面，就能让同一种子的文件分别落到 S1 / S2 / 剧场版目录。
     * 单段时相对路径为空，行为与旧实现一致。
     */
    private Result<Void> startCollectionByQb(Ani ani, List<SegmentPlan> plans, File tempFile,
                                             TorrentMetadata torrentFile, Config config) {
        String root = commonRoot(plans.stream().map(SegmentPlan::downloadPath).toList());
        Assert.notBlank(root, "无法确定合集下载目录, 请为每个部分设置下载位置");

        for (SegmentPlan plan : plans) {
            String rel = relativize(root, plan.downloadPath());
            if (StrUtil.isBlank(rel) && !trimSlash(plan.downloadPath().replace("\\", "/"))
                    .equalsIgnoreCase(trimSlash(root.replace("\\", "/")))) {
                // 目标不在公共保存根之下（例如跨盘/跨挂载点）：qB 的 renameFile 只能写
                // 相对 savepath 的路径，这里无法安全表达，明确告警而不是静默落回根目录。
                log.warn("分段目录不在合集保存根下, 该段文件将落在打包目录顶层 {} -> {}",
                        plan.downloadPath(), root);
            }
            for (Item item : plan.items()) {
                item.setTargetDir(plan.downloadPath());
                if (StrUtil.isNotBlank(rel)) {
                    item.setReName(rel + "/" + item.getReName());
                }
            }
        }

        String name = StrFormatter.format("[{}] {} 合集", StrUtil.nullToEmpty(ani.getSubgroup()), ani.getTitle());
        download(name, tempFile, root, List.of("ANI-RSS合集下载", StrUtil.nullToEmpty(ani.getSubgroup())));

        TorrentsInfo torrentsInfo = new TorrentsInfo()
                .setHash(torrentFile.getHash());

        // 合集关联的番剧也写入订阅列表(仅入列、不轮询、去重), 使其在「订阅」里可见可管理。
        // 放在提交后立即执行：后处理已异步化，若等它跑完再入列，用户要 ~32.5s 后才看得到订阅
        plans.forEach(p -> AniUtil.addCollectionAni(p.ani()));

        // 等待元数据 + 逐文件重命名放后台：最坏 ~32.5s，不能占用 Tomcat 请求线程（P1-8）
        List<Item> all = plans.stream().flatMap(p -> p.items().stream()).toList();
        COLLECTION_POST_POOL.execute(() -> finishQbCollection(torrentsInfo, all, config));

        if (plans.size() > 1) {
            return Result.success(StrFormatter.format(
                    "已经开始下载合集: 共 {} 个部分, 将分别落盘到各自目录, 进度可在日志中查看", plans.size()));
        }
        return Result.success("已经开始下载合集, 正在后台等待元数据并重命名, 进度可在日志中查看");
    }

    /**
     * 多个目标目录的公共祖先（按路径段比较）。
     * <p>
     * 用于 qB 只允许一个 savepath 的场景：先落到公共祖先，再靠 {@code renameFile} 的相对
     * 子路径分发到各段目录。仅保留盘符/根时说明各段不在同一媒体库，此时仍以公共祖先提交，
     * 由 {@link #relativize} 生成完整相对路径。
     */
    static String commonRoot(List<String> paths) {
        List<String> valid = paths.stream().filter(StrUtil::isNotBlank).toList();
        if (valid.isEmpty()) {
            return "";
        }
        // 绝对路径必须保留前导 '/'，否则 /115/动画 会退化成相对路径 115/动画，
        // qB/OpenList 会把它当成 savepath 下的子目录，文件直接落错位置
        boolean absolute = valid.get(0).replace("\\", "/").startsWith("/");
        String[] base = splitPath(valid.get(0));
        int depth = base.length;
        for (String p : valid) {
            String[] cur = splitPath(p);
            int i = 0;
            while (i < depth && i < cur.length && base[i].equalsIgnoreCase(cur[i])) {
                i++;
            }
            depth = i;
        }
        if (depth <= 0) {
            return base[0] + "/";
        }
        String joined = String.join("/", Arrays.copyOf(base, depth));
        return absolute ? "/" + joined : joined;
    }

    /**
     * 目标目录相对公共祖先的子路径；不在公共祖先下时返回空串（调用方回退为扁平重命名）。
     */
    static String relativize(String root, String path) {
        if (StrUtil.isBlank(root) || StrUtil.isBlank(path)) {
            return "";
        }
        String r = trimSlash(root.replace("\\", "/"));
        String p = trimSlash(path.replace("\\", "/"));
        if (p.equalsIgnoreCase(r)) {
            return "";
        }
        if (!p.toLowerCase(Locale.ROOT).startsWith(r.toLowerCase(Locale.ROOT) + "/")) {
            return "";
        }
        return p.substring(r.length() + 1);
    }

    private static String trimSlash(String s) {
        return s.replaceAll("/+$", "");
    }

    private static String[] splitPath(String path) {
        String normalized = path.replace("\\", "/").replaceAll("/+$", "");
        return Arrays.stream(normalized.split("/"))
                .filter(StrUtil::isNotBlank)
                .toArray(String[]::new);
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
    private Result<Void> startCollectionByOpenList(Ani ani, List<Item> plan, File torrentFile,
                                                   String torrentName, String savePath) {
        Assert.isTrue(TorrentUtil.login(), "下载器登录失败");
        if (!(TorrentUtil.DOWNLOAD instanceof OpenList openList)) {
            throw ResultException.exception("当前离线下载器不支持合集下载");
        }
        // 下载位置与订阅口径一致(尊重自定义下载位置/模板), OpenList 为网盘虚拟路径；
        // 多段时使用各段目录的公共祖先，归位阶段再按 item.targetDir 分发
        String path = StrUtil.isNotBlank(savePath) ? savePath : downloadService.getDownloadPath(ani);
        Boolean ok = openList.downloadCollection(ani, plan, path, torrentFile, torrentName);
        Assert.isTrue(Boolean.TRUE.equals(ok), "合集离线下载提交失败, 请查看日志");
        return Result.success("合集已提交离线下载, 完成后将自动重命名并归位, 进度可在任务管理器中查看");
    }

    @Auth
    @Operation(summary = "合集结构分析")
    @PostMapping("/analyzeCollection")
    public Result<CollectionInfo> analyzeCollection(@RequestBody CollectionInfo collectionInfo) {
        String torrent = collectionInfo.getTorrent();
        Assert.notBlank(torrent, "种子内容为空, 请重新上传种子文件或填入磁力链接");
        boolean magnet = MagnetTorrentUtil.isMagnet(torrent);
        File tempFile = getTorrentFile(torrent);
        try {
            TorrentMetadata torrentFile;
            try {
                torrentFile = TorrentMetadata.from(tempFile);
            } catch (Exception e) {
                throw ResultException.exception("种子文件解析失败, 请确认是有效的 .torrent 文件或磁力链接");
            }
            Ani base = collectionInfo.getAni();
            CollectionAnalyzer.Analysis analysis = CollectionAnalyzer.analyze(torrentFile, base);
            List<CollectionSegment> segments = new ArrayList<>(analysis.segments());
            for (CollectionSegment segment : segments) {
                // 把「该段落盘到哪」提前算出来给用户看，用户也可以直接改
                segment.setDownloadPath(downloadService.getDownloadPath(segmentAni(base, segment)));
            }
            // 顺带把每段命中的条目算出来：弹窗里能直接看到条目数/集数，不必再请求一次预览
            CollectionInfo planInfo = new CollectionInfo();
            planInfo.setAni(base);
            planInfo.setMode("SPLIT");
            planInfo.setSegments(segments);
            for (SegmentPlan plan : buildSegmentPlans(planInfo, torrentFile, false)) {
                if (plan.segment() != null) {
                    plan.segment().setItems(plan.items());
                }
            }
            CollectionInfo result = new CollectionInfo();
            result.setTorrent(torrent);
            result.setAni(base);
            result.setSegments(segments);
            result.setWarnings(analysis.warnings());
            result.setMode(CollectionAnalyzer.isMixed(segments) ? "SPLIT" : "SINGLE");
            return Result.success(result);
        } finally {
            if (!magnet) {
                FileUtil.del(tempFile);
            }
        }
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


    public synchronized List<Item> preview(CollectionInfo collectionInfo) {
        String torrent = collectionInfo.getTorrent();
        Assert.notBlank(torrent, "种子内容为空, 请重新上传种子文件或填入磁力链接");
        boolean magnet = MagnetTorrentUtil.isMagnet(torrent);
        File tempFile = getTorrentFile(torrent);
        try {
            TorrentMetadata torrentFile;
            try {
                torrentFile = TorrentMetadata.from(tempFile);
            } catch (Exception e) {
                throw ResultException.exception("种子文件解析失败, 请确认是有效的 .torrent 文件或磁力链接");
            }
            // 计划构建与订阅离线下载共用同一口径（TorrentPlanUtil）；拆分模式按段展开后合并
            return buildSegmentPlans(collectionInfo, torrentFile, false)
                    .stream()
                    .flatMap(plan -> plan.items().stream())
                    .toList();
        } finally {
            // 解析已在内存（TorrentMetadata），临时文件不再需要，必须清理防 /tmp 堆积；
            // 磁力链接的缓存文件例外（下次还要复用，删了要重新抓元数据）
            if (!magnet) {
                FileUtil.del(tempFile);
            }
        }
    }

    /**
     * 把「合集来源」统一成一份可解析的种子文件。
     * <p>
     * 磁力链接只有 infoHash，没有文件列表，无法预览/匹配/改名，因此先由
     * {@link MagnetTorrentUtil} 抓取元数据落成 {@code .torrent}；之后整条链路
     * （预览、qBittorrent 提交、OpenList 离线下载、种子记录）与上传种子方式完全一致。
     * <p>
     * 返回的文件：磁力链接是<b>缓存文件</b>（调用方不得删除），上传种子是本次请求
     * 独占的临时文件（调用方必须删除）。
     *
     * @param torrent Base64 种子内容或磁力链接
     * @return 本地种子文件
     */
    private static File getTorrentFile(String torrent) {
        if (MagnetTorrentUtil.isMagnet(torrent)) {
            return MagnetTorrentUtil.resolve(torrent);
        }
        File tempFile = FileUtil.createTempFile();
        Base64.decodeToFile(torrent, tempFile);
        return tempFile;
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
