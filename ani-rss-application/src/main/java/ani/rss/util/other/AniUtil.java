package ani.rss.util.other;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.*;
import ani.rss.entity.dto.RssToAniDTO;
import ani.rss.exception.ResultException;
import ani.rss.service.ClearService;
import ani.rss.service.DownloadService;
import ani.rss.service.MikanService;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import wushuo.tmdb.api.entity.Tmdb;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class AniUtil {

    private static volatile List<Ani> ANI_LIST = new CopyOnWriteArrayList<>();
    public static final String FILE_NAME = "ani.v2.json";

    /**
     * 运行时状态回写的合并窗口（毫秒）。
     * <p>
     * {@code doSync()} 每次都会把<b>整个订阅列表</b>序列化 + 写临时文件 + 原子 move，
     * 200 订阅一轮里每个状态变化的订阅各触发一次就是 200 次全量写（P2-1）。
     * 这里把窗口内的多次回写合并成一次尾部落盘：首次调用仍立即写（结构性变更的可见性不受影响），
     * 之后 500ms 内的回写只排队，不丢数据、只延后。
     */
    private static final long SYNC_THROTTLE_MS = 500L;

    /**
     * 上次真正落盘的时刻。{@link System#nanoTime()} 的绝对值没有意义（且会回绕），
     * 只用于相减判定是否还在节流窗口内。
     */
    private static final AtomicLong LAST_SYNC_NANOS = new AtomicLong(0L);

    /** 是否已排入一次尾部落盘，避免同一窗口内重复排队 */
    private static final AtomicBoolean FLUSH_SCHEDULED = new AtomicBoolean(false);

    /** 上次落盘的内容，用于"内容未变则跳过"，省掉无意义的临时文件 + 原子 move */
    private static volatile String LAST_WRITTEN_JSON;

    /**
     * 订阅 id → 订阅 的正向索引（P2-14）。
     * <p>
     * 全项目有 50+ 处 {@code getAniList().stream().filter(...)}，其中"按 id 反查"是最常见的一类
     * （预览回写、批量操作、外部接口按 id 取订阅）。订阅数上三位数后，这些 O(n) 过滤叠加起来
     * 是实打实的开销。
     * <p>
     * 索引<b>惰性构建</b>、并在所有结构变更点整体失效。失效点有两重保障：
     * <ol>
     *   <li>所有订阅增删点都会调用 {@link #sync()}，因此显式失效收敛在一处；
     *       调用方还应在改动列表后立刻调用 {@link #invalidateIdIndex()}，把"改完到 sync 之间"的窗口也关掉。</li>
     *   <li>索引自带「列表引用 + 元素个数」校验（{@link IdIndex#stillValid()}），
     *       将来若有人绕过 sync() 直接改列表，最坏也只是退化为重建，不会返回陈旧条目。</li>
     * </ol>
     */
    private static volatile IdIndex ANI_ID_INDEX;

    /**
     * 索引快照。{@code source}/{@code size} 只用于判断快照是否还对应当前的订阅列表。
     */
    private record IdIndex(Map<String, Ani> byId, List<Ani> source, int size) {
        boolean stillValid() {
            List<Ani> current = ANI_LIST;
            return source == current && size == current.size();
        }
    }

    /**
     * 订阅列表结构已变更：丢弃 id 索引，下次查询时重建。
     * <p>
     * 只置空引用，代价可忽略，因此在每个增删点直接调用也没问题。
     */
    public static void invalidateIdIndex() {
        ANI_ID_INDEX = null;
    }

    /**
     * 按 id 查订阅（P2-14）。返回的是 {@link #getAniList()} 里的<b>活对象</b>，
     * 调用方若要修改它，请自行确认并发语义（或按需拷贝）。
     * <p>
     * 未命中返回 {@link Optional#empty()}——与 {@code stream().filter(...).findFirst()} 语义一致。
     */
    public static Optional<Ani> findById(String id) {
        if (StrUtil.isBlank(id)) {
            return Optional.empty();
        }
        IdIndex index = ANI_ID_INDEX;
        if (index == null || !index.stillValid()) {
            index = buildIdIndex();
            ANI_ID_INDEX = index;
        }
        return Optional.ofNullable(index.byId().get(id));
    }

    private static IdIndex buildIdIndex() {
        List<Ani> current = ANI_LIST;
        Map<String, Ani> byId = new HashMap<>(Math.max(16, current.size() * 2));
        for (Ani ani : current) {
            if (ani == null || StrUtil.isBlank(ani.getId())) {
                continue;
            }
            byId.put(ani.getId(), ani);
        }
        return new IdIndex(byId, current, current.size());
    }

    /**
     * 订阅增删操作的锁，防止 TOCTOU 竞态（添加订阅 / 删除订阅 / 添加合集订阅 共用）
     */
    public static final Object SUBSCRIPTION_LOCK = new Object();

    /**
     * 获取订阅列表（线程安全读取）
     */
    public static List<Ani> getAniList() {
        return ANI_LIST;
    }

    /**
     * 获取订阅配置文件
     *
     * @return
     */
    public static File getAniFile() {
        File configDir = ConfigUtil.getConfigDir();
        return new File(configDir + File.separator + FILE_NAME);
    }

    /**
     * 加载订阅（原子替换，避免并发读到空列表）
     */
    public static void load() {
        File configFile = getAniFile();

        if (!configFile.exists()) {
            // 原子写：先写临时文件再 move 替换，避免首启写盘途中断电留下截断的订阅文件
            File temp = new File(configFile + ".temp");
            FileUtil.del(temp);
            FileUtil.writeUtf8String(GsonStatic.toJson(ANI_LIST), temp);
            FileUtils.move(temp.toPath(), configFile.toPath());
        }
        String s = FileUtil.readUtf8String(configFile);

        // 解析失败兜底：损坏文件改名保留现场，以空列表继续启动，不再向上抛出导致 exit
        List<Ani> anis = null;
        try {
            anis = GsonStatic.fromJsonList(s, Ani.class);
        } catch (Exception e) {
            log.error("订阅文件解析失败: {}", e.getMessage(), e);
        }

        if (anis == null) {
            String ts = DateUtil.format(new Date(), "yyyyMMddHHmmss");
            File corruptFile = new File(configFile + ".corrupt-" + ts);
            try {
                FileUtil.move(configFile, corruptFile, true);
                log.error("订阅文件已损坏, 已改名为 [{}] 保留现场; 本次启动将以空订阅列表继续, 请检查磁盘/权限后重新添加订阅", corruptFile.getName());
            } catch (Exception moveException) {
                log.error("订阅文件已损坏, 且改名保留失败(可能被占用), 请手动处理: {}", configFile);
                log.error(moveException.getMessage(), moveException);
            }
            anis = new ArrayList<>();
        }

        CopyOptions copyOptions = CopyOptions
                .create()
                .setIgnoreNullValue(true)
                .setOverride(false);

        // 在局部变量中构建新列表，完成后原子替换引用
        List<Ani> newList = new CopyOnWriteArrayList<>();
        for (Ani ani : anis) {
            Date releaseDate = ani.getReleaseDate();
            if (Objects.isNull(releaseDate)) {
                releaseDate = new Date();
                // 处理旧的日期数据
                try {
                    Integer year = ani.getYear();
                    Integer month = ani.getMonth();
                    Integer date = ani.getDate();
                    String format = StrUtil.format("{}-{}-{}", year, month, date);
                    releaseDate = DateUtil.parse(format, DatePattern.NORM_DATE_PATTERN);
                } catch (Exception ignored) {
                }
                ani.setReleaseDate(releaseDate);
            }

            // 存量订阅地址迁移：mikanime.tv 仅是 mikanani.me 的 301 跳板，
            // 直连 .me 可少建一条 TCP 连接（降低连接超时暴露面）
            migrateMikanHost(ani);

            // 自动修补缺失的封面
            String image = ani.getImage();
            // 回填补全后的封面路径（port upstream 2147d525）
            ani.setCover(saveCover(image));

            Ani newAni = AniUtil.createAni();
            BeanUtil.copyProperties(newAni, ani, copyOptions);
            newList.add(ani);
        }
        // 原子替换：读线程永远不会看到中间状态
        ANI_LIST = newList;
        log.debug("加载订阅 共{}项", ANI_LIST.size());
    }

    /**
     * 订阅 RSS 地址迁移：mikanime.tv -> mikanani.me
     * <p>
     * mikanime.tv 是 mikanani.me 的 301 跳转域名（且本项目的默认 Mikan Host 已改为 .me）。
     * 存量订阅的 URL 在添加时写死落盘，此处加载时统一改写主订阅与备用 RSS 地址，
     * 避免每次抓取多付一次 301 往返。非 .tv 地址原样保留。
     */
    private static void migrateMikanHost(Ani ani) {
        if (ani == null) {
            return;
        }
        String url = ani.getUrl();
        if (StrUtil.isNotBlank(url) && url.contains("mikanime.tv")) {
            String migrated = url.replace("mikanime.tv", "mikanani.me");
            ani.setUrl(migrated);
            log.info("订阅地址迁移 mikanime.tv -> mikanani.me: {}", ani.getTitle());
        }

        List<StandbyRss> standbyRssList = ani.getStandbyRssList();
        if (CollUtil.isEmpty(standbyRssList)) {
            return;
        }
        for (StandbyRss standbyRss : standbyRssList) {
            if (standbyRss == null) {
                continue;
            }
            String standbyUrl = standbyRss.getUrl();
            if (StrUtil.isNotBlank(standbyUrl) && standbyUrl.contains("mikanime.tv")) {
                standbyRss.setUrl(standbyUrl.replace("mikanime.tv", "mikanani.me"));
                log.info("备用订阅地址迁移 mikanime.tv -> mikanani.me: {}", ani.getTitle());
            }
        }
    }

    /**
     * 将订阅配置保存到磁盘（<b>结构性变更</b>：订阅增删、标题/季/下载路径模板等）。
     * <p>
     * 会连带失效下载路径反向索引与订阅级本地状态快照——这些缓存都以"订阅 → 下载路径"为键，
     * 路径一变旧数据就全是错的。
     * <p>
     * 只回写运行时状态（漏集数 / 当前集数 / 最近下载时间 / enable）请用
     * {@link #syncStateOnly()}：那些字段不影响下载路径，走本方法会把全局缓存清空，
     * 而预览、每下完一集都会触发回写，等于把缓存废掉。
     */
    public static synchronized void sync() {
        // 订阅已变更：下载路径反向索引 + 本地状态快照 + id 索引失效
        DownloadService.invalidateDownloadPathIndex();
        invalidateIdIndex();
        // 结构性变更不节流：调用方（增删订阅）随后可能立刻读盘
        doSyncNow();
    }

    /**
     * 仅落盘，<b>不做任何缓存失效</b>。
     * <p>
     * 适用场景：回写 {@code omitCount} / {@code currentEpisodeNumber} / {@code lastDownloadTime}
     * / {@code enable} 这类运行时状态。它们不影响下载路径，也不影响"目录里有哪些文件"，
     * 因此失效缓存既无必要、代价又极大（预览是最常用入口，每次都清空等于没有缓存）。
     */
    public static synchronized void syncStateOnly() {
        if (inThrottleWindow()) {
            // 窗口内：不立即写盘，排一次尾部落盘即可（内容不丢，只是延后 ≤ SYNC_THROTTLE_MS）
            scheduleTrailingFlush();
            return;
        }
        doSyncNow();
    }

    /**
     * 是否还在落盘节流窗口内
     */
    private static boolean inThrottleWindow() {
        long last = LAST_SYNC_NANOS.get();
        if (last == 0L) {
            // 从未落盘过，不做节流
            return false;
        }
        return System.nanoTime() - last < TimeUnit.MILLISECONDS.toNanos(SYNC_THROTTLE_MS);
    }

    /**
     * 排队一次尾部落盘（同一窗口内只会排一次）
     */
    private static void scheduleTrailingFlush() {
        if (!FLUSH_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        ThreadUtil.execute(() -> {
            ThreadUtil.sleep(SYNC_THROTTLE_MS);
            FLUSH_SCHEDULED.set(false);
            flushPending();
        });
    }

    /**
     * 尾部落盘：期间若已有人写过（时间戳被刷新）则无需重复写
     */
    private static synchronized void flushPending() {
        if (inThrottleWindow()) {
            return;
        }
        doSyncNow();
    }

    private static void doSyncNow() {
        LAST_SYNC_NANOS.set(System.nanoTime());
        doSync();
    }

    /**
     * 仅供测试：复位落盘节流窗口。
     * <p>
     * 节流状态是 JVM 级静态量，用例之间会互相影响（表现为"单独跑通过、全量跑失败"）。
     * 调用后还需等一个窗口让已排队的尾部落盘跑完，否则它可能落到下一个用例的临时目录里。
     */
    static void resetSyncThrottleForTest() {
        LAST_SYNC_NANOS.set(0L);
        FLUSH_SCHEDULED.set(false);
    }

    private static void doSync() {
        File configFile = getAniFile();
        log.debug("保存订阅 {}", configFile);
        try {
            // 健康分仅为 API 展示字段，落盘前清空，避免污染 ani.v2.json
            for (Ani ani : ANI_LIST) {
                if (ani != null) {
                    ani.setHealthScore(null).setHealthLevel(null).setHealthReasons(null);
                }
            }
            String json = GsonStatic.toJson(ANI_LIST);

            /*
            内容未变则跳过：落盘是「序列化 + 写临时文件 + 原子 move」三件事，
            序列化无法避免（要算出 json 才知道有没有变），但后两件可以省。
            文件不存在时不能跳过（外部删除/配置恢复后必须重新写出来）。
            */
            if (json.equals(LAST_WRITTEN_JSON) && configFile.exists()) {
                log.debug("订阅内容未变化, 跳过落盘 {}", configFile);
                return;
            }

            File temp = new File(configFile + ".temp");
            FileUtil.del(temp);
            FileUtil.writeUtf8String(json, temp);
            FileUtils.move(temp.toPath(), configFile.toPath());
            LAST_WRITTEN_JSON = json;
            log.debug("保存成功 {}", configFile);
        } catch (Exception e) {
            log.error("保存失败 {}", configFile);
            log.error(e.getMessage(), e);
        }
    }

    /**
     * 获取动漫信息
     *
     * @param dto
     * @return
     */
    public static Ani getAni(RssToAniDTO dto) {
        String url = dto.getUrl();
        String type = dto.getType();
        Boolean enable = dto.getEnable();
        enable = ObjectUtil.defaultIfNull(enable, true);

        Assert.notBlank(url, "RSS地址 不能为空");

        type = StrUtil.blankToDefault(type, "mikan");

        Ani ani = AniUtil.createAni();
        ani.setUrl(url);

        Map<String, String> paramMap = HttpUtil.decodeParamMap(url, StandardCharsets.UTF_8);

        switch (type) {
            case "mikan":
                try {
                    String subgroup = dto.getSubgroup();
                    String bgmUrl = dto.getBgmUrl();
                    if (StrUtil.isAllBlank(subgroup, bgmUrl)) {
                        String subgroupId = MikanService.getSubgroupId(url);
                        MikanService.getMikanInfo(ani, subgroupId);
                    } else {
                        ani.setBgmUrl(bgmUrl)
                                .setSubgroup(subgroup);
                    }
                } catch (Exception e) {
                    throw ResultException.exception("获取失败");
                }
                break;
            case "ani-bt":
                if (paramMap.containsKey("bgmId")) {
                    String bgmUrl = "https://bgm.tv/subject/" + paramMap.get("bgmId");
                    ani.setBgmUrl(bgmUrl);
                }

                String subgroup = dto.getSubgroup();
                if (paramMap.containsKey("groupSlug") && StrUtil.isBlank(subgroup)) {
                    subgroup = paramMap.get("groupSlug");
                }
                ani.setSubgroup(subgroup);
                break;
            case "anime-garden":
                if (paramMap.containsKey("subject")) {
                    String bgmUrl = "https://bgm.tv/subject/" + paramMap.get("subject");
                    ani.setBgmUrl(bgmUrl);
                }
                if (paramMap.containsKey("fansub")) {
                    ani.setSubgroup(paramMap.get("fansub"));
                }
                break;
            default:
                String bgmUrl = dto.getBgmUrl();
                ani.setBgmUrl(bgmUrl);
        }

        String bgmUrl = ani.getBgmUrl();
        String subgroup = ani.getSubgroup();

        Assert.notBlank(bgmUrl, "bgmUrl 不能为空");

        BgmInfo bgmInfo = BgmUtil.getBgmInfo(ani, true);

        BgmUtil.toAni(bgmInfo, ani);

        Config config = ConfigUtil.CONFIG;

        // 只下载最新集
        Boolean downloadNew = config.getDownloadNew();
        // 默认启用全局排除
        Boolean enabledExclude = config.getEnabledExclude();
        // 默认导入全局排除
        Boolean importExclude = config.getImportExclude();
        // 全局排除
        List<String> exclude = config.getExclude();

        // 默认导入全局排除
        if (importExclude) {
            exclude = new ArrayList<>(exclude);
            exclude.addAll(ani.getExclude());
            exclude = exclude.stream().distinct().toList();
            ani.setExclude(exclude);
        }

        ani
                // 只下载最新集
                .setDownloadNew(downloadNew)
                // 是否启用全局排除
                .setGlobalExclude(enabledExclude)
                // type mikan or other
                .setType(type)
                .setEnable(enable);

        subgroup = StrUtil.blankToDefault(subgroup, "未知字幕组");

        if (subgroup.equals("未知字幕组")) {
            List<Item> items = ItemsUtil.getItems(ani, url, subgroup);
            subgroup = ItemsUtil.getSubgroup(items);
        }

        ani.setSubgroup(subgroup);

        List<StandbyRss> standbyRssList = ani.getStandbyRssList();

        boolean copyMasterToStandby = config.getCopyMasterToStandby();
        boolean standbyRss = config.getStandbyRss();
        if (copyMasterToStandby && standbyRss) {
            StandbyRss copyStandbyRss = new StandbyRss()
                    .setUrl(url.trim())
                    .setOffset(0)
                    .setLabel(ani.getSubgroup());
            standbyRssList.add(copyStandbyRss);
        }

        log.debug("获取到动漫信息 {}", JSONUtil.formatJsonStr(GsonStatic.toJson(ani)));
        if (ani.getOva()) {
            return ani;
        }

        // 自动推断剧集偏移
        if (config.getOffset()) {
            List<Item> items = ItemsUtil.getItems(ani, url, subgroup);
            if (items.isEmpty()) {
                return ani;
            }
            Double offset = -(items.stream()
                    .map(Item::getEpisode)
                    .min(Comparator.comparingDouble(i -> i))
                    .get() - 1);
            log.debug("自动获取到剧集偏移为 {}", offset);
            ani.setOffset(offset.intValue());

            for (StandbyRss rss : standbyRssList) {
                rss.setOffset(offset.intValue());
            }
        }
        return ani;
    }


    public static String saveCover(String coverUrl) {
        return saveCover(coverUrl, false);
    }

    /**
     * 保存图片
     *
     * @param coverUrl
     * @param isOverride 是否覆盖
     * @return
     */
    public static String saveCover(String coverUrl, Boolean isOverride) {
        File configDir = ConfigUtil.getConfigDir();
        File filesDir = new File(configDir, "files");
        FileUtil.mkdir(filesDir);

        // 默认空图片
        String cover = "cover.png";
        File defaultFile = Path.of(filesDir.toString(), cover).toFile();
        if (!defaultFile.exists()) {
            try (InputStream inputStream = ResourceUtil.getStream("image/cover.png")) {
                FileUtil.writeFromStream(inputStream, defaultFile);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
        if (StrUtil.isBlank(coverUrl)) {
            return cover;
        }

        String extName = FileUtil.extName(URLUtil.getPath(coverUrl));
        // 取url的md5作为文件名, 避免重复下载
        String filename = SecureUtil.md5(coverUrl) + "." + extName;

        File dir = new File(filesDir.toString(), String.valueOf(filename.charAt(0)));

        FileUtil.mkdir(dir);
        File file = new File(dir, filename);
        if (file.exists() && !isOverride) {
            return filename.charAt(0) + "/" + filename;
        }
        FileUtil.del(file);
        try {
            HttpReq.get(coverUrl)
                    .then(res -> FileUtil.writeFromStream(res.bodyStream(), file));
            return filename.charAt(0) + "/" + filename;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return cover;
        }
    }

    /**
     * 校验参数
     *
     * @param ani
     */
    public static void verify(Ani ani) {
        String url = ani.getUrl();
        List<String> exclude = ani.getExclude();
        Integer season = ani.getSeason();
        Integer offset = ani.getOffset();
        String title = ani.getTitle();
        Assert.notBlank(url, "RSS URL 不能为空");
        if (Objects.isNull(exclude)) {
            ani.setExclude(new ArrayList<>());
        }
        Assert.notNull(season, "季不能为空");
        Assert.notBlank(title, "标题不能为空");
        // 标题会拼入种子缓存目录与下载路径，禁止目录穿越载荷
        Assert.isFalse(title.contains(".."), "标题不能包含 \"..\"");
        Assert.isFalse(title.contains("/") || title.contains("\\"), "标题不能包含 / 或 \\");
        Assert.notNull(offset, "集数偏移不能为空");
    }


    /**
     * 获取蜜柑的bangumiId
     *
     * @param ani
     * @return
     */
    public static String getBangumiId(Ani ani) {
        String url = ani.getUrl();
        if (StrUtil.isBlank(url)) {
            return "";
        }
        Map<String, String> decodeParamMap = HttpUtil.decodeParamMap(url, StandardCharsets.UTF_8);
        return decodeParamMap.get("bangumiId");
    }


    /**
     * 订阅完结迁移
     *
     * @param ani
     */
    public static void completed(Ani ani) {
        ani = ObjectUtil.clone(ani);

        String title = ani.getTitle();
        Boolean completed = ani.getCompleted();
        boolean ova = ani.getOva();
        boolean enable = ani.getEnable();
        int currentEpisodeNumber = ani.getCurrentEpisodeNumber();
        int totalEpisodeNumber = ani.getTotalEpisodeNumber();

        if (!completed) {
            // 未开启
            return;
        }

        if (totalEpisodeNumber < 1) {
            // 总集数为空
            return;
        }

        if (currentEpisodeNumber < totalEpisodeNumber) {
            // 未完结
            return;
        }

        if (enable) {
            // 仍是启用的话 主RSS仍未完结
            return;
        }

        if (ova && !RenameUtil.isNamingV2(ani)) {
            // 旧版剧场版不进行迁移，新版 OVA v2 支持迁移
            return;
        }

        Config config = ObjectUtil.clone(ConfigUtil.CONFIG);

        boolean autoDisabled = config.getAutoDisabled();
        if (!autoDisabled) {
            // 未开启自动禁用订阅
            return;
        }

        completed = config.getCompleted();
        if (!completed) {
            // 未开启
            return;
        }

        String completedPathTemplate = config.getCompletedPathTemplate();

        Boolean customCompleted = ani.getCustomCompleted();
        if (customCompleted) {
            // 自定义完结迁移
            completedPathTemplate = ani.getCustomCompletedPathTemplate();
        }

        if (StrUtil.isBlank(completedPathTemplate)) {
            // 路径为空
            return;
        }

        // 旧文件路径
        String oldPath = SpringUtil.getBean(DownloadService.class).getDownloadPath(ani, config);

        config.setDownloadPathTemplate(completedPathTemplate);
        // 因为临时修改下载位置模版以获取对应下载位置, 要关闭自定义下载位置
        ani.setCustomDownloadPath(false);

        // 新文件路径
        String newPath = SpringUtil.getBean(DownloadService.class).getDownloadPath(ani, config);

        if (!FileUtil.exist(oldPath)) {
            // 旧文件不存在
            return;
        }

        FileUtil.mkdir(newPath);

        List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();

        for (TorrentsInfo torrentsInfo : torrentsInfos) {
            String downloadDir = torrentsInfo.getDownloadDir();
            if (!downloadDir.equals(oldPath)) {
                // 旧位置不相同
                continue;
            }
            // 修改保存位置
            TorrentUtil.setSavePath(torrentsInfo, newPath);
        }

        if (!torrentsInfos.isEmpty()) {
            ThreadUtil.sleep(3000);
        }

        File[] files = FileUtils.listFiles(oldPath);

        log.info("订阅已完结 {}, 移动已完结文件共 {} 个", title, files.length);

        for (File file : files) {
            if (!file.exists()) {
                continue;
            }
            // 移动文件
            log.info("移动 {} ==> {}", file, newPath);
            FileUtil.move(file, new File(newPath), true);
            // 清理残留文件夹
            SpringUtil.getBean(ClearService.class).clearParentFile(file);
        }
    }

    public static Ani createAni() {
        Ani newAni = new Ani();
        Config config = ConfigUtil.CONFIG;
        return newAni
                .setId(UUID.randomUUID().toString())
                .setMikanTitle("")
                .setStandbyRssList(new ArrayList<>())
                .setOffset(0)
                .setReleaseDate(new DateTime())
                .setEnable(true)
                .setOva(false)
                .setScore(0.0)
                .setLastDownloadTime(0L)
                .setImage("")
                .setThemoviedbName("")
                .setCustomDownloadPath(false)
                .setDownloadPath("")
                .setGlobalExclude(false)
                .setCurrentEpisodeNumber(0)
                .setTotalEpisodeNumber(0)
                .setMatch(List.of())
                .setExclude(List.of("720[Pp]", "\\d-\\d", "合集", "特别篇"))
                .setBgmUrl("")
                .setSubgroup("")
                .setCustomEpisode(config.getCustomEpisode())
                .setCustomEpisodeStr(config.getCustomEpisodeStr())
                .setCustomEpisodeGroupIndex(config.getCustomEpisodeGroupIndex())
                .setOmit(true)
                .setDownloadNew(false)
                .setNotDownload(new ArrayList<>())
                .setTmdb(
                        new Tmdb()
                                .setId("")
                                .setName("")
                                .setOriginalName("")
                                .setDate(new Date())
                )
                .setUpload(config.getUpload())
                .setProcrastinating(true)
                .setCustomRenameTemplate(config.getRenameTemplate())
                .setCustomRenameTemplateEnable(false)
                .setCustomPriorityKeywordsEnable(false)
                .setCustomPriorityKeywords(new ArrayList<>())
                .setMessage(true)
                .setCustomUploadPathTarget("")
                .setCustomUploadEnable(false)
                .setCompleted(true)
                .setCustomCompleted(false)
                .setCustomCompletedPathTemplate("")
                .setCustomTags(new ArrayList<>())
                .setCustomTagsEnable(false)
                .setNamingVersion(2)
                .setPriority(1)
                .setGroup("")
                .setTags(new ArrayList<>())
                .setCustomQualityProfileEnable(false)
                .setCustomQualityProfile(ConfigUtil.defaultQualityProfile().setEnable(true));
    }

    /**
     * 校验「合集关联订阅」的安全字段。
     * <p>
     * 合集订阅没有 RSS 地址，因此不能直接复用 {@link #verify(Ani)}（它会断言 url 非空）。
     * 但下面两点必须校验：
     * <ul>
     *   <li>标题会拼入种子缓存目录与下载路径，含 {@code ..} / {@code /} / {@code \} 会导致路径越界；</li>
     *   <li>{@code season} 会在 getDownloadPath 里被拆箱成 {@code int}，为 null 时 NPE，
     *       而该异常常被上层 catch 吞成 debug 日志，订阅会静默失效。</li>
     * </ul>
     */
    public static void verifyCollectionAni(Ani ani) {
        Assert.notNull(ani, "订阅不能为空");
        String title = ani.getTitle();
        Assert.notBlank(title, "标题不能为空");
        Assert.isFalse(title.contains(".."), "标题不能包含 \"..\"");
        Assert.isFalse(title.contains("/") || title.contains("\\"), "标题不能包含 / 或 \\");
        Assert.notNull(ani.getSeason(), "季不能为空");
    }

    /**
     * 将「添加合集」关联的番剧(Ani)加入订阅列表。
     * <p>
     * 合集依赖手动上传的种子，没有 RSS 地址，因此只入列、不启用轮询(enable=false)；
     * 若已存在同标题+季的订阅则跳过(去重)，避免重复条目。
     */
    public static void addCollectionAni(Ani ani) {
        if (ani == null || StrUtil.isBlank(ani.getTitle())) {
            return;
        }
        try {
            verifyCollectionAni(ani);
        } catch (IllegalArgumentException e) {
            // 走到这里合集下载已经开始了, 不能因为"入列"失败就把整个请求判失败; 记录后跳过入列
            log.warn("合集关联订阅字段不安全, 跳过加入订阅列表: {} ({})", ani.getTitle(), e.getMessage());
            return;
        }
        // 封面补齐必须放在锁外：saveCover 会发起网络请求，封面 URL 不可达时会一直阻塞到
        // HttpReq 超时，期间所有"添加/删除订阅"都在 SUBSCRIPTION_LOCK 上排队。
        // saveCover 内部按 URL 的 md5 做了文件缓存，重复 URL 不会二次下载。
        String cover = saveCover(ani.getImage());
        synchronized (SUBSCRIPTION_LOCK) {
            boolean exists = ANI_LIST.stream()
                    .anyMatch(it -> ObjectUtil.equals(it.getTitle(), ani.getTitle())
                            && ObjectUtil.equals(it.getSeason(), ani.getSeason()));
            if (exists) {
                log.info("合集关联的订阅已存在, 跳过重复添加: {} 第{}季", ani.getTitle(), ani.getSeason());
                return;
            }
            if (StrUtil.isBlank(ani.getId())) {
                ani.setId(UUID.randomUUID().toString());
            }
            // 合集无 RSS 地址, 不轮询新集
            ani.setEnable(false);
            // 重置添加弹窗里的占位进度/评分, 避免沿用默认值
            ani.setCurrentEpisodeNumber(0)
                    .setTotalEpisodeNumber(0)
                    .setScore(0.0)
                    .setLastDownloadTime(0L);
            // 兜底必要集合字段, 与 createAni 保持一致, 避免列表/持久化异常
            if (ani.getExclude() == null) {
                ani.setExclude(new ArrayList<>());
            }
            if (ani.getOffset() == null) {
                // getDownloadPath / 集数计算会拆箱使用, 不能留 null
                ani.setOffset(0);
            }
            if (ani.getStandbyRssList() == null) {
                ani.setStandbyRssList(new ArrayList<>());
            }
            if (ani.getMatch() == null) {
                ani.setMatch(new ArrayList<>());
            }
            if (ani.getNotDownload() == null) {
                ani.setNotDownload(new ArrayList<>());
            }
            // 封面已在锁外补齐(空 image 走默认封面, 不发起网络请求)
            ani.setCover(cover);
            ANI_LIST.add(ani);
            sync();
        }
        log.info("合集已加入订阅列表: {} 第{}季", ani.getTitle(), ani.getSeason());
    }


}
