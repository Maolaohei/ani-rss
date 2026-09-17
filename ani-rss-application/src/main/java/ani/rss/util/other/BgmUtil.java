package ani.rss.util.other;

import ani.rss.commons.CacheUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.BgmInfo;
import ani.rss.entity.BgmMe;
import ani.rss.entity.Config;
import ani.rss.entity.web.ContentType;
import ani.rss.entity.web.Header;
import ani.rss.enums.BgmTokenTypeEnum;
import ani.rss.service.DownloadService;
import ani.rss.service.MikanService;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.net.url.UrlBuilder;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.thread.NamedThreadFactory;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.*;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import wushuo.tmdb.api.entity.Tmdb;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * BGM
 */
@Slf4j
public class BgmUtil {
    private static final Config config = ConfigUtil.CONFIG;

    /**
     * BGM 阻塞 IO 专用有界线程池：仅服务于「双源并行获取」这类 socket 阻塞任务。
     * <p>
     * 原先走 {@code ForkJoinPool.commonPool()}，其并行度是 {@code CPU 核数 - 1}；而任务体是阻塞式
     * socket 读，FJP 的阻塞补偿只对 {@code ManagedBlocker}/{@code ForkJoinTask} 生效，对普通
     * {@code CompletableFuture} 里的阻塞<b>不生效</b>。后果是：小主机（2~4 核）上"并行双源"其实
     * 根本没并行，且会把 commonPool 占满，饿死其它使用 commonPool 的代码（含 {@code parallelStream}）。
     * <p>
     * 池大小固定 2：本类的扇出恰好是 2，且这两个任务体不会再向本池提交（无自等待），
     * 因此 2 线程既够用、也不会因为"池内线程等池内任务"而死锁。
     */
    private static final ExecutorService IO_POOL = ExecutorBuilder.create()
            .setCorePoolSize(2)
            .setMaxPoolSize(2)
            .setWorkQueue(new LinkedBlockingQueue<>(64))
            .setThreadFactory(new NamedThreadFactory("bgm-io", true))
            .build();

    // ---- BGM API 限流：令牌桶 ----
    // 原先把节流藏在 setToken（加 Authorization 头的方法）里，每个请求无条件睡 500~1000ms。
    // 三个副作用：① 重试会重复付一次固定成本；② 只想加个头的调用点也被迫等待；
    // ③ getEpisodes/getSubjectId 在这之外还各睡 500/1000ms，单次元数据获取纯睡眠就有 1.5~2.5s。
    // 现在改为在"真正发请求"前取令牌，速率与原实现均值一致（不增加对 bgm.tv 的压力），
    // 但空闲/慢响应之后不会再白等。

    /**
     * 速率（次/秒）：1 / 750ms，与原先 {@code randomInt(500, 1000)} 的均值一致
     */
    private static final double BGM_PER_SECOND = 1000.0 / 750.0;

    /**
     * 突发容量：允许连续 2 个请求不等待（覆盖"双源并行"这种成对发出的场景）
     */
    private static final double BGM_BURST = 2.0;

    private static final Object BGM_RATE_LOCK = new Object();
    private static double bgmAvailableTokens = BGM_BURST;
    private static long bgmLastRefillNanos = System.nanoTime();

    /**
     * 取一个 BGM API 令牌，保证全局速率不超过 {@link #BGM_PER_SECOND}。
     * <p>
     * <b>必须在真正发请求之前调用</b>（即 {@code thenFunction}/{@code then} 之前）。
     * <p>
     * P1-7：锁内只算 waitMs，锁外 sleep 再循环，避免持锁睡眠阻塞其他线程。
     */
    public static void throttleBgmApi() {
        while (true) {
            long waitMs;
            synchronized (BGM_RATE_LOCK) {
                long now = System.nanoTime();
                double elapsedSec = (now - bgmLastRefillNanos) / 1_000_000_000.0;
                bgmLastRefillNanos = now;
                bgmAvailableTokens = Math.min(BGM_BURST, bgmAvailableTokens + elapsedSec * BGM_PER_SECOND);

                if (bgmAvailableTokens >= 1.0) {
                    bgmAvailableTokens -= 1.0;
                    return;
                }
                waitMs = Math.max(1L, resolveTokenWaitMs(bgmAvailableTokens, BGM_PER_SECOND));
            }
            ThreadUtil.sleep(waitMs);
        }
    }

    /**
     * 「当前令牌数下，再取一个令牌需要等多久（毫秒）」——令牌够用时为 0。
     * <p>
     * 抽成纯函数以便单测覆盖边界（令牌刚好 1 个 / 0 个 / 半个），不必靠 sleep 计时来验证。
     *
     * @param availableTokens 当前可用令牌数
     * @param perSecond       速率（次/秒）
     * @return 需要等待的毫秒数，0 表示无需等待
     */
    static long resolveTokenWaitMs(double availableTokens, double perSecond) {
        if (availableTokens >= 1.0) {
            return 0L;
        }
        return (long) Math.ceil((1.0 - availableTokens) / perSecond * 1000.0);
    }

    /**
     * 仅供测试：复位 BGM 令牌桶到"满桶 + 刚补充过"的初始状态。
     * <p>
     * 令牌桶是 static 全局状态，不复位会让用例之间互相影响（前一个用例耗掉的令牌会变成
     * 后一个用例的等待时间）。
     */
    static void resetBgmRateLimiterForTest() {
        synchronized (BGM_RATE_LOCK) {
            bgmAvailableTokens = BGM_BURST;
            bgmLastRefillNanos = System.nanoTime();
        }
    }

    /**
     * 剧集列表缓存键（抽出来供测试复用，避免测试里手写格式串而与实现脱节）。
     */
    static String episodesCacheKey(String subjectId, Integer type) {
        return "BGM_getEpisodes:" + subjectId + ":" + type;
    }

    /**
     * 发一个 BGM 请求：先取令牌限流，再加 Authorization 头，最后真正发出。
     * <p>
     * 这是本类<b>唯一的出网点</b>——限流与鉴权都在这里完成，调用点不需要（也不应该）自己 sleep。
     *
     * @param httpRequest 已构造完毕的请求（query/form/body 都应在此之前设置好）
     * @param fun         响应处理函数
     * @return 响应处理结果
     */
    public static <T> T send(HttpRequest httpRequest, Function<HttpResponse, T> fun) {
        throttleBgmApi();
        return setToken(httpRequest).thenFunction(fun);
    }

    /**
     * {@link #send(HttpRequest, Function)} 的 void 版（响应只需断言/忽略时使用）。
     * <p>
     * 单独取名而不是重载 {@code send}：{@code HttpResponse::isOk} 这类方法引用对
     * {@code Function} 与 {@code Consumer} 都兼容，重载会产生歧义。名字对齐 Hutool 的 {@code .then(Consumer)}。
     */
    public static void sendThen(HttpRequest httpRequest, Consumer<HttpResponse> consumer) {
        throttleBgmApi();
        setToken(httpRequest).then(consumer);
    }

    /**
     * 获取bgm名称
     *
     * @param bgmInfo
     * @return
     */
    public static synchronized String getFinalName(BgmInfo bgmInfo) {
        Boolean bgmJpName = config.getBgmJpName();

        String name = bgmInfo.getName();
        String nameCn = bgmInfo.getNameCn();
        String title = StrUtil.blankToDefault(nameCn, name);

        if (bgmJpName) {
            title = name;
        }

        if (StrUtil.isBlank(title)) {
            title = "无标题";
        }

        return title.trim();
    }

    /**
     * 获取bgm名称
     *
     * @param bgmInfo
     * @param tmdb
     * @return
     */
    public static synchronized String getFinalName(BgmInfo bgmInfo, Tmdb tmdb) {
        Boolean titleYear = config.getTitleYear();

        String title = getFinalName(bgmInfo);

        Date date = bgmInfo.getDate();

        if (titleYear) {
            title = StrFormatter.format("{} ({})", title, DateUtil.year(date));
        }

        return TmdbUtils.getFinalName(title, tmdb);
    }

    /**
     * 搜索番剧
     *
     * @param name
     * @return
     */
    public static List<BgmInfo> search(String name) {
        if (StrUtil.isBlank(name)) {
            return new ArrayList<>();
        }

        name = name.replace("1/2", "½");

        String bgmApi = config.getBgmApi();

        // 路径段需完整编码, 防止标题中 / & # 等特殊字符改变路径或截断 query (空格转 %20)
        String url = UrlBuilder.of(bgmApi + "/search/subject/" + URLUtil.encodeAll(name))
                .addQuery("type", 2)
                .addQuery("max_results", 25)
                .addQuery("responseGroup", "small")
                .toString();

        HttpRequest httpRequest = HttpReq.get(url);

        return send(httpRequest, res -> {
                    if (!res.isOk()) {
                        return new ArrayList<>();
                    }
                    String body = res.body();
                    if (!JSONUtil.isTypeJSON(body)) {
                        return new ArrayList<>();
                    }

                    JsonObject jsonObject = GsonStatic.fromJson(body, JsonObject.class);
                    JsonElement code = jsonObject.get("code");
                    if (Objects.nonNull(code)) {
                        if (code.getAsInt() == 404) {
                            return new ArrayList<>();
                        }
                    }
                    JsonArray list = jsonObject.getAsJsonArray("list");
                    if (list == null) {
                        return new ArrayList<>();
                    }
                    List<BgmInfo> bgmInfos = GsonStatic.fromJsonList(list, BgmInfo.class);
                    for (BgmInfo bgmInfo : bgmInfos) {
                        Integer season = getSeasonByBgmInfo(bgmInfo);
                        bgmInfo.setSeason(season);
                    }
                    return bgmInfos;
                });
    }

    /**
     * 查找番剧id
     * <p>
     * (A9) 不再加 synchronized: 缓存走线程安全的 CacheUtils, 并发同名词汇最多重复一次搜索
     * (结果一致, put 后写覆盖无实际影响); 原实现持类锁做网络搜索+睡眠 1s, 会拖长整个 RSS 轮次。
     *
     * @param bgmName 名称
     * @param s       季度
     * @return 番剧id
     */
    public static String getSubjectId(String bgmName, Integer s) {
        if (StrUtil.isBlank(bgmName)) {
            return "";
        }

        String key = "BGM_getSubjectId:" + bgmName;

        if (CacheUtils.containsKey(key)) {
            return CacheUtils.get(key);
        }
        List<BgmInfo> list = search(bgmName);

        // 仅保留季数一致
        list = list.stream()
                .filter(bgmInfo -> bgmInfo.getSeason() == s.intValue())
                .toList();

        if (list.isEmpty()) {
            return "";
        }

        String id = "";
        // 优先使用名称与季完全匹配的
        for (BgmInfo bgmInfo : list) {
            String name = bgmInfo.getName();
            String nameCn = bgmInfo.getNameCn();

            if (List.of(name, nameCn).contains(bgmName)) {
                id = bgmInfo.getId();
                break;
            }
        }
        // 次之使用第一个
        if (StrUtil.isBlank(id)) {
            id = list.get(0).getId();
        }
        // (第三批) 原先此处还额外睡 1s 作为"调用节奏限制"——限流已在 send() 的出网点统一做，
        // 这里再睡一次只会让每次 BGM 搜索多付 1 秒，已移除。
        CacheUtils.put(key, id, TimeUnit.MINUTES.toMillis(10));
        return id;
    }

    /**
     * 获取番剧id
     *
     * @param ani
     * @return
     */
    public static String getSubjectId(Ani ani) {
        String bgmUrl = ani.getBgmUrl();
        if (StrUtil.isBlank(bgmUrl) && "mikan".equals(ani.getType())) {
            String bangumiId = AniUtil.getBangumiId(ani);
            Assert.notBlank(bangumiId, "无法取得 bangumiId, {}", ani.getTitle());
            MikanService.getMikanInfo(ani, "");
            bgmUrl = ani.getUrl();
        }
        return getSubjectId(bgmUrl);
    }

    /**
     * 获取番剧id
     *
     * @param bgmUrl
     * @return
     */
    public static String getSubjectId(String bgmUrl) {
        Assert.notBlank(bgmUrl, "bgmUrl 不能为空");
        String regStr = "^http(s)?://.+\\/(\\d+)(\\/)?$";
        Assert.isTrue(ReUtil.contains(regStr, bgmUrl));
        return ReUtil.get(regStr, bgmUrl, 2);
    }

    /**
     * 获取视频列表
     *
     * @param subjectId 番剧id
     * @param type      0正常 1番外
     * @return
     */
    public static List<JsonObject> getEpisodes(String subjectId, Integer type) {
        // (第三批) 原先此处额外睡 500ms；限流已统一在 send() 出网点完成，不再重复睡眠
        Objects.requireNonNull(subjectId);

        // (P2-13) 进程内缓存：此前完全没有缓存，同一番剧在同一轮里会被
        // getEpisodeId / getEpisodeTitleMap / getEps 各抓一次。剧集列表变动以天计，5 分钟足够。
        // 存 JSON 快照而非 List 本身：反序列化天然给出独立实例，某个调用点就地改 JsonObject
        // 不会污染其它调用点（与 me() 同一套写法）。
        String key = episodesCacheKey(subjectId, type);
        String cached = CacheUtils.get(key);
        if (StrUtil.isNotBlank(cached)) {
            // 快照解析失败必须当作未命中继续请求：缓存不能变成故障点
            try {
                JsonArray cachedArray = GsonStatic.fromJson(cached, JsonArray.class);
                if (Objects.nonNull(cachedArray)) {
                    return GsonStatic.fromJsonList(cachedArray, JsonObject.class);
                }
            } catch (Exception e) {
                log.warn("BGM 剧集列表缓存快照解析失败, 重新请求: {} ({})", subjectId, e.getMessage());
            }
        }

        String bgmApi = config.getBgmApi();
        HttpRequest httpRequest = HttpReq.get(bgmApi + "/v0/episodes");

        List<JsonObject> episodes = send(httpRequest
                .form("subject_id", subjectId)
                .form("type", 0)
                .form("limit", 1000)
                .form("offset", 0), res -> {
                    if (!res.isOk()) {
                        return List.of();
                    }

                    String body = res.body();
                    if (!JSONUtil.isTypeJSON(body)) {
                        return List.of();
                    }

                    JsonObject jsonObject = GsonStatic.fromJson(body, JsonObject.class);
                    if (Objects.isNull(jsonObject)) {
                        // 响应为 JSON null, 视为无集数数据
                        return List.of();
                    }

                    JsonElement dataElement = jsonObject.get("data");
                    if (Objects.isNull(dataElement) || dataElement.isJsonNull() || !dataElement.isJsonArray()) {
                        // data 缺失、null 或形态异常, 视为无集数数据
                        log.warn("BGM episodes 响应缺少 data: subjectId={}", subjectId);
                        return List.of();
                    }

                    return dataElement
                            .getAsJsonArray()
                            .asList()
                            .stream()
                            .map(JsonElement::getAsJsonObject)
                            .filter(itemObject -> {
                                if (Objects.nonNull(type)) {
                                    JsonElement typeElement = itemObject.get("type");
                                    if (Objects.isNull(typeElement) || typeElement.isJsonNull()) {
                                        // type 缺失, 无法匹配, 跳过该条
                                        return false;
                                    }
                                    return type == typeElement.getAsInt();
                                }
                                return true;
                            })
                            .toList();
                });

        // 只缓存成功的非空结果：一次网络抖动不该被固化成"这个番剧没有剧集"
        if (!episodes.isEmpty()) {
            CacheUtils.put(key, GsonStatic.toJson(episodes), TimeUnit.MINUTES.toMillis(5));
        }
        return episodes;
    }

    public static BgmMe me() {
        String bgmToken = config.getBgmToken();
        Assert.notBlank(bgmToken, "BgmToken 未填写");

        String key = "BGM_me:" + bgmToken;

        String me = CacheUtils.get(key);
        if (StrUtil.isNotBlank(me)) {
            return GsonStatic.fromJson(me, BgmMe.class);
        }

        String bgmApi = config.getBgmApi();
        BgmMe bgmMe = send(HttpReq.get(bgmApi + "/v0/me"), res -> {
            HttpReq.assertStatus(res);
            return GsonStatic.fromJson(res.body(), BgmMe.class);
        });

        CacheUtils.put(key, GsonStatic.toJson(bgmMe), TimeUnit.MINUTES.toMillis(10));
        return bgmMe;
    }

    /**
     * 获取用户名
     *
     * @return
     */
    public static String username() {
        BgmMe me = me();
        return Opt.of(me)
                .map(BgmMe::getUsername)
                .filter(Objects::nonNull)
                .filter(StrUtil::isNotBlank)
                .orElse(String.valueOf(me.getId()));
    }

    /**
     * 对番剧进行评分
     */
    public static Integer rate(String subjectId, Integer rate) {
        if (Objects.isNull(rate)) {
            // 获取评分
            String username = username();
            String bgmApi = config.getBgmApi();
            return send(HttpReq.get(bgmApi + "/v0/users/" + username + "/collections/" + subjectId), res -> {
                if (res.getStatus() == 404) {
                    return 0;
                }
                HttpReq.assertStatus(res);
                JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                if (jsonObject == null) {
                    return 0;
                }
                JsonElement rateElement = jsonObject.get("rate");
                if (rateElement == null || rateElement.isJsonNull()) {
                    return 0;
                }
                return rateElement.getAsInt();
            });
        }

        String bgmApi = config.getBgmApi();
        sendThen(HttpReq.post(bgmApi + "/v0/users/-/collections/" + subjectId)
                .contentType(ContentType.JSON)
                .body(GsonStatic.toJson(Map.of(
                        "type", 3,
                        "rate", rate
                ))), HttpReq::assertStatus);
        return rate;
    }

    /**
     * 收藏番剧
     *
     * @param subjectId 番剧id
     */
    public static void collections(String subjectId) {
        Assert.notBlank(subjectId, "subjectId 不能为空");

        String key = "BGM_collections:" + subjectId;
        if (CacheUtils.containsKey(key)) {
            return;
        }
        CacheUtils.put(key, subjectId, TimeUnit.MINUTES.toMillis(5));

        String username = username();

        // 如果已经订阅，则不再订阅
        String bgmApi = config.getBgmApi();
        Boolean ok = send(HttpReq.get(bgmApi + "/v0/users/" + username + "/collections/" + subjectId),
                HttpResponse::isOk);

        if (ok) {
            // 已经收藏
            log.info("已收藏番剧: {}", subjectId);
            return;
        }

        send(HttpReq.post(bgmApi + "/v0/users/-/collections/" + subjectId)
                .contentType(ContentType.JSON)
                .body(GsonStatic.toJson(Map.of("type", 3))), HttpResponse::isOk);
    }

    /**
     * 获取 EpisodeId
     *
     * @param subjectId 番剧id
     * @param e         集数
     * @return 集id
     */
    public static String getEpisodeId(String subjectId, Double e) {
        String epId = "";
        String sortId = "";

        if (Objects.isNull(e)) {
            return epId;
        }

        String key = "BGM_getEpisodeId:" + subjectId;

        List<JsonObject> episodes = CacheUtils.get(key);
        if (Objects.isNull(episodes)) {
            episodes = getEpisodes(subjectId, 0);
            CacheUtils.put(key, episodes, TimeUnit.MINUTES.toMillis(10));
        }
        for (JsonObject itemObject : episodes) {
            JsonElement epElement = itemObject.get("ep");
            JsonElement sortElement = itemObject.get("sort");
            JsonElement idElement = itemObject.get("id");

            if (Objects.isNull(idElement) || idElement.isJsonNull()) {
                // id 缺失, 跳过该条
                continue;
            }

            if (Objects.nonNull(epElement) && !epElement.isJsonNull() && epElement.getAsDouble() == e) {
                epId = idElement.getAsString();
                break;
            }
            if (Objects.nonNull(sortElement) && !sortElement.isJsonNull() && sortElement.getAsDouble() == e) {
                sortId = idElement.getAsString();
                break;
            }
        }

        return StrUtil.blankToDefault(epId, sortId);
    }

    /**
     * 标记
     *
     * @param episodeId 集id
     * @param type      0 未看过, 1 想看, 2 看过
     */
    public static void collectionsEpisodes(String episodeId, Integer type) {
        // (第三批) 原先此处睡 500ms；限流已统一在 send() 出网点完成，不再重复睡眠
        Objects.requireNonNull(episodeId);

        // bgm点格子前先判断状态，防止刷屏 #142
        String bgmApi = config.getBgmApi();
        JsonObject jsonObject = send(
                HttpReq.get(bgmApi + "/v0/users/-/collections/-/episodes/" + episodeId)
                        .contentType(ContentType.JSON),
                res -> {
                    if (res.getStatus() == 404) {
                        // 未收藏, 视为 type=0 继续后续标记
                        return null;
                    }
                    HttpReq.assertStatus(res);
                    return GsonStatic.fromJson(res.body(), JsonObject.class);
                });

        if (Objects.isNull(jsonObject)) {
            // 404 未收藏, typeNow 视为 0
            if (Objects.equals(type, 0)) {
                return;
            }
        } else {
            JsonElement typeElement = jsonObject.get("type");
            if (Objects.nonNull(typeElement) && !typeElement.isJsonNull() && Objects.equals(typeElement.getAsInt(), type)) {
                // 状态一致, 无需标记
                return;
            }
        }

        // (第三批) 原先此处再睡 500ms 作为"两次请求的间隔, 防止流控"。
        // 令牌桶保证相邻请求间隔 ≥750ms（且是全局速率，不依赖某个调用点自觉 sleep），已移除。
        send(HttpReq.put(bgmApi + "/v0/users/-/collections/-/episodes/" + episodeId)
                .contentType(ContentType.JSON)
                .body(GsonStatic.toJson(Map.of("type", type))), HttpResponse::isOk);
    }

    /**
     * 获取对应的bgm信息
     *
     * @param ani
     * @param isCache
     * @return
     */
    public static BgmInfo getBgmInfo(Ani ani, Boolean isCache) {
        String subjectId = getSubjectId(ani);
        Assert.notBlank(subjectId, "无法取得 subjectId, {}", ani.getTitle());
        return getBgmInfo(subjectId, isCache);
    }

    /**
     * 获取对应的bgm信息
     *
     * @param ani
     * @return
     */
    public static BgmInfo getBgmInfo(Ani ani) {
        String subjectId = getSubjectId(ani);
        return getBgmInfo(subjectId);
    }

    /**
     * 获取对应的bgm信息
     *
     * @param subjectId
     * @return
     */
    public static BgmInfo getBgmInfo(String subjectId) {
        return getBgmInfo(subjectId, false);
    }

    /**
     * 获取对应的bgm信息
     *
     * @param subjectId
     * @param isCache
     * @return
     */
    public static BgmInfo getBgmInfo(String subjectId, Boolean isCache) {
        String bgmApi = config.getBgmApi();

        Function<HttpResponse, BgmInfo> fun = res -> {
            HttpReq.assertStatus(res);
            String body = res.body();
            Assert.isTrue(JSONUtil.isTypeJSON(body), "no json");
            BgmInfo bgmInfo = GsonStatic.fromJson(body, BgmInfo.class);

            String name = bgmInfo.getName();
            String nameCn = bgmInfo.getNameCn();

            name = RenameUtil.getName(name);
            nameCn = RenameUtil.getName(nameCn);

            int season = getSeasonByBgmInfo(bgmInfo);

            Date date = bgmInfo.getDate();
            date = ObjectUtil.defaultIfNull(date, new Date());

            return bgmInfo
                    .setName(name)
                    .setNameCn(nameCn)
                    .setSeason(season)
                    .setDate(date);
        };

        if (!isCache) {
            // 不使用缓存
            HttpRequest httpRequest = HttpReq.get(bgmApi + "/v0/subjects/" + subjectId);
            return send(httpRequest, fun);
        }

        AtomicReference<BgmInfo> bgmInfoAR = new AtomicReference<>();
        AtomicReference<BgmInfo> bgmInfoCacheAR = new AtomicReference<>();

        // 并行获取bgm信息（走本类专用 IO 池，不再占用 ForkJoinPool.commonPool）
        CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> {
                    // 不使用缓存
                    HttpRequest httpRequest = HttpReq.get(bgmApi + "/v0/subjects/" + subjectId);
                    try {
                        BgmInfo bgmInfo = send(httpRequest, fun);
                        bgmInfoAR.set(bgmInfo);
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                    }
                }, IO_POOL),
                CompletableFuture.runAsync(() -> {
                    // cache.wushuo.top 是第三方镜像，不是 bgm.tv，因此不走 BGM 令牌桶
                    HttpRequest httpRequest = HttpReq
                            .get("https://cache.wushuo.top/bgm/subjects/" + subjectId);
                    try {
                        BgmInfo bgmInfo = httpRequest
                                .thenFunction(fun);
                        bgmInfoCacheAR.set(bgmInfo);
                    } catch (Exception ignored) {
                    }
                }, IO_POOL)
        ).join();

        BgmInfo bgmInfo = bgmInfoAR.get();

        bgmInfo = ObjectUtil.defaultIfNull(bgmInfo, bgmInfoCacheAR.get());

        Assert.notNull(bgmInfo, "获取 bgmInfo 失败!");

        return bgmInfo;
    }

    public static Integer getSeasonByBgmInfo(BgmInfo bgmInfo) {
        String name = bgmInfo.getName();
        String nameCn = bgmInfo.getNameCn();
        List<BgmInfo.Tag> tags = bgmInfo.getTags();
        tags = ObjectUtil.defaultIfNull(tags, new ArrayList<>());
        List<JsonObject> infobox = bgmInfo.getInfobox();
        infobox = ObjectUtil.defaultIfNull(infobox, new ArrayList<>());

        // 从标签获取季
        for (BgmInfo.Tag tag : tags) {
            String tagName = tag.getName();
            int season = getSeasonByName(tagName);
            if (season > 1) {
                return season;
            }
        }

        // 从中文标题获取季
        if (StrUtil.isNotBlank(nameCn)) {
            int season = getSeasonByName(nameCn);
            if (season > 1) {
                return season;
            }
        }

        // 从原标题获取季
        if (StrUtil.isNotBlank(name)) {
            int season = getSeasonByName(name);
            if (season > 1) {
                return season;
            }
        }

        // 从别名获取
        for (JsonObject jsonObject : infobox) {
            JsonElement keyElement = jsonObject.get("key");
            if (Objects.isNull(keyElement) || keyElement.isJsonNull()) {
                continue;
            }
            if (!keyElement.getAsString().equals("别名")) {
                continue;
            }
            JsonElement value = jsonObject.get("value");
            if (Objects.isNull(value) || value.isJsonNull()) {
                // 别名 value 缺失, 跳过
                continue;
            }

            if (value.isJsonPrimitive()) {
                // 字符串形态: "value": "剧场版 第一季"
                int season = getSeasonByName(value.getAsString());
                if (season > 1) {
                    return season;
                }
            } else if (value.isJsonArray()) {
                // 数组形态: 元素可为对象 {"v": "..."} 或纯字符串
                for (JsonElement jsonElement : value.getAsJsonArray().asList()) {
                    if (Objects.isNull(jsonElement) || jsonElement.isJsonNull()) {
                        continue;
                    }
                    String v = null;
                    if (jsonElement.isJsonObject()) {
                        JsonElement vElement = jsonElement.getAsJsonObject().get("v");
                        if (Objects.nonNull(vElement) && vElement.isJsonPrimitive()) {
                            v = vElement.getAsString();
                        }
                    } else if (jsonElement.isJsonPrimitive()) {
                        v = jsonElement.getAsString();
                    }
                    if (StrUtil.isBlank(v)) {
                        continue;
                    }
                    int season = getSeasonByName(v);
                    if (season > 1) {
                        return season;
                    }
                }
            }
            // 其他形态(嵌套对象等)跳过
        }

        // 都未匹配到 返回季度1
        return 1;
    }

    public static Integer getSeasonByName(String name) {
        int season = 1;

        if (StrUtil.isBlank(name)) {
            return season;
        }

        List<String> regexList = List.of(
                // 第一季 第一期
                "第 ?([一二三四五六七八九十百千]+) ?[季期]",
                // Season 1
                "[Ss]eason ?(\\d+)",
                // 1st Season
                "(\\d+)(st|nd|rd|th) ?[Ss]eason",
                // S1 S01
                "[Ss](\\d+)$"
        );

        for (String regex : regexList) {
            if (!ReUtil.contains(regex, name)) {
                continue;
            }

            try {
                String s = ReUtil.get(regex, name, 1);
                if (NumberUtil.isInteger(s)) {
                    season = Integer.parseInt(s);
                } else {
                    season = Convert.chineseToNumber(s);
                }
            } catch (Exception ignored) {
            }
        }
        return season;
    }

    /**
     * 设置token（<b>只加请求头，不做任何限流</b>）
     * <p>
     * (A9) 不再加 synchronized: 方法内无共享可变状态(仅读 volatile CONFIG + 设置请求头)。
     * <p>
     * (第三批) 原先这里还睡 500~1000ms 作为"调用节奏限制"，属于**放错位置**的限流：
     * 它让"只是想加个 Authorization 头"的调用点也被迫等待，重试时还会再睡一遍。
     * 节流已移到真正发请求的 {@link #throttleBgmApi()}，本方法现在是纯函数。
     *
     * @param httpRequest
     * @return
     */
    public static HttpRequest setToken(HttpRequest httpRequest) {
        String bgmToken = config.getBgmToken();

        if (StrUtil.isNotBlank(bgmToken)) {
            httpRequest.header(Header.AUTHORIZATION, "Bearer " + bgmToken);
        }

        return httpRequest;
    }

    /**
     * 获取剩余过期时间 单位: 天
     *
     * @return
     */
    public static Integer getExpiresDays() {
        String bgmToken = config.getBgmToken();
        if (StrUtil.isBlank(bgmToken)) {
            return 0;
        }
        long expires = HttpReq.post("https://bgm.tv/oauth/token_status")
                .form("access_token", bgmToken)
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonElement expiresElement = jsonObject == null ? null : jsonObject.get("expires");
                    if (expiresElement == null || expiresElement.isJsonNull()) {
                        log.warn("BGM token_status 响应缺少 expires 字段");
                        return 0L;
                    }
                    return expiresElement.getAsLong() * 1000L;
                });

        long currentTimeMillis = System.currentTimeMillis();

        int days = 0;

        if (expires > currentTimeMillis) {
            days = Math.toIntExact(TimeUnit.MILLISECONDS.toDays(expires - currentTimeMillis));
        }
        return days;
    }

    /**
     * 刷新token
     */
    public static synchronized void refreshToken() {
        BgmTokenTypeEnum bgmTokenType = config.getBgmTokenType();
        if (bgmTokenType != BgmTokenTypeEnum.AUTO) {
            return;
        }

        String bgmToken = config.getBgmToken();
        if (StrUtil.isBlank(bgmToken)) {
            return;
        }

        long days = getExpiresDays();

        if (days >= 3) {
            return;
        }

        String bgmAppID = config.getBgmAppID();
        String bgmAppSecret = config.getBgmAppSecret();
        String bgmRefreshToken = config.getBgmRefreshToken();
        String bgmRedirectUri = config.getBgmRedirectUri();

        if (StrUtil.isBlank(bgmAppID)) {
            return;
        }
        if (StrUtil.isBlank(bgmAppSecret)) {
            return;
        }
        if (StrUtil.isBlank(bgmRefreshToken)) {
            return;
        }
        if (StrUtil.isBlank(bgmRedirectUri)) {
            return;
        }

        HttpReq.post("https://bgm.tv/oauth/access_token")
                .body(GsonStatic.toJson(Map.of(
                        "grant_type", "refresh_token",
                        "client_id", bgmAppID,
                        "client_secret", bgmAppSecret,
                        "refresh_token", bgmRefreshToken,
                        "redirect_uri", bgmRedirectUri
                )))
                .then(res -> {
                    HttpReq.assertStatus(res);
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonElement accessTokenElement = jsonObject == null ? null : jsonObject.get("access_token");
                    JsonElement refreshTokenElement = jsonObject == null ? null : jsonObject.get("refresh_token");
                    if (accessTokenElement == null || accessTokenElement.isJsonNull()
                            || refreshTokenElement == null || refreshTokenElement.isJsonNull()
                            || StrUtil.isBlank(accessTokenElement.getAsString())
                            || StrUtil.isBlank(refreshTokenElement.getAsString())) {
                        throw new IllegalStateException("BGM 刷新 token 响应缺少 access_token/refresh_token");
                    }
                    String accessToken = accessTokenElement.getAsString();
                    String refreshToken = refreshTokenElement.getAsString();
                    config.setBgmToken(accessToken)
                            .setBgmRefreshToken(refreshToken);
                });

        ConfigUtil.sync();
        log.info("BgmToken 已自动刷新");
    }

    /**
     * 获取每集的标题
     * <p>
     * (第三批) 不再加 {@code synchronized}：缓存未命中时本方法会发起网络（getSubjectId / getEpisodes），
     * 持类锁做网络会把全站 BGM 路径串起来。方法内无共享可变状态：缓存走线程安全的 CacheUtils，
     * 并发未命中最多重复一次请求（结果一致，put 后写覆盖）。
     *
     * @param ani
     * @return
     */
    public static Map<Integer, Function<Boolean, String>> getEpisodeTitleMap(Ani ani) {
        Map<Integer, Function<Boolean, String>> episodeTitleMap = new HashMap<>();

        if (Objects.isNull(ani)) {
            return episodeTitleMap;
        }

        String subjectId = getSubjectId(ani);

        if (StrUtil.isBlank(subjectId)) {
            return episodeTitleMap;
        }

        if (Boolean.TRUE.equals(ani.getOva())) {
            return episodeTitleMap;
        }

        String key = "BGM_getEpisodeTitleMap:" + subjectId;

        Map<Integer, Function<Boolean, String>> cacheMap = CacheUtils.get(key);
        if (Objects.nonNull(cacheMap)) {
            return cacheMap;
        }

        try {
            List<JsonObject> data = getEpisodes(subjectId, 0);
            for (JsonObject it : data) {
                JsonElement epElement = it.get("ep");
                JsonElement nameElement = it.get("name");
                if (Objects.isNull(epElement) || epElement.isJsonNull() || Objects.isNull(nameElement) || nameElement.isJsonNull()) {
                    // ep 或 name 缺失, 跳过该条
                    continue;
                }
                int ep = epElement.getAsInt();
                String jpTitle = nameElement.getAsString();

                JsonElement nameCnElement = it.get("name_cn");
                String title = Objects.nonNull(nameCnElement) && !nameCnElement.isJsonNull()
                        ? nameCnElement.getAsString()
                        : "";
                title = StrUtil.blankToDefault(title, jpTitle);

                title = RenameUtil.getName(title);
                jpTitle = RenameUtil.getName(jpTitle);

                String defaultEpisodeTitle = "第" + ep + "集";

                title = StrUtil.blankToDefault(title, defaultEpisodeTitle);
                jpTitle = StrUtil.blankToDefault(jpTitle, defaultEpisodeTitle);

                AtomicReference<String> titleRef = new AtomicReference<>(title);
                AtomicReference<String> jpTitleRef = new AtomicReference<>(jpTitle);

                episodeTitleMap.put(ep, jp -> jp ? jpTitleRef.get() : titleRef.get());
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        CacheUtils.put(key, episodeTitleMap, TimeUnit.MINUTES.toMillis(5));
        return episodeTitleMap;
    }

    /**
     * 获取集数 排除ova
     *
     * @param bgmInfo
     * @return
     */
    public static Integer getEps(BgmInfo bgmInfo) {
        Integer epsNum = bgmInfo.getEps();
        if (Objects.isNull(epsNum)) {
            // eps 缺失 (旧数据或响应缺少字段), 视为 0, 防止拆箱 NPE
            log.warn("BgmInfo eps 为空: id={}", bgmInfo.getId());
            epsNum = 0;
        }
        int eps = epsNum;
        String subjectId = bgmInfo.getId();
        if (eps < 1) {
            return 0;
        }
        try {
            int size = BgmUtil.getEpisodes(subjectId, 0).size();
            if (size > 0) {
                // 获取集数不为零
                eps = size;
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return eps;
    }

    /**
     * bgm转ani
     *
     * @param bgmInfo
     * @param ani
     * @return
     */
    public static Ani toAni(BgmInfo bgmInfo, Ani ani) {
        // port upstream 3.2.26: 配置键更名 bgmImage -> bgmImageSize，默认 medium；
        // 存量配置无该键时回落 medium，避免 ReflectUtil 拿到 null 字段名
        String bgmImageSize = StrUtil.blankToDefault(config.getBgmImageSize(), "medium");
        // 使用tmdb标题
        Boolean tmdb = config.getTmdb();

        String title = BgmUtil.getFinalName(bgmInfo);

        int eps = getEps(bgmInfo);

        BgmInfo.Images images = bgmInfo.getImages();

        String image = (String) ReflectUtil.getFieldValue(images, bgmImageSize);

        double score = Optional.ofNullable(bgmInfo.getRating())
                .map(BgmInfo.Rating::getScore)
                .orElse(0.0);

        String platform = bgmInfo.getPlatform();

        // P0-6：platform 可空（部分条目无 platform），直接 toUpperCase 必 NPE
        String platformUpper = platform == null ? "" : platform.toUpperCase();
        boolean ova = List.of("OVA", "剧场版").contains(platformUpper);

        Date date = bgmInfo.getDate();

        ani
                .setBgmUrl("https://bgm.tv/subject/" + bgmInfo.getId())
                // 标题
                .setTitle(title)
                .setJpTitle(bgmInfo.getName())
                // 季
                .setSeason(bgmInfo.getSeason())
                // 总集数
                .setTotalEpisodeNumber(eps)
                // 剧场版
                .setOva(ova)
                // 评分
                .setScore(score)
                // 发布日期"
                .setReleaseDate(date)
                // 图片http地址
                .setImage(image)
                // 本地图片地址
                .setCover(AniUtil.saveCover(image));

        // 媒体类型: 仅来源明确时设置, 避免覆盖用户手动选择的剧场版/OVA
        if ("剧场版".equals(platform)) {
            ani.setMediaType("movie");
        } else if ("OVA".equals(platform)) {
            ani.setMediaType("ova");
        }

        // 获取tmdb标题
        String themoviedbName = TmdbUtils.getFinalName(ani);

        // 是否使用tmdb标题
        if (StrUtil.isNotBlank(themoviedbName) && tmdb) {
            ani
                    .setTitle(themoviedbName);
        } else {
            title = BgmUtil.getFinalName(bgmInfo, ani.getTmdb());
            ani.setTitle(title);
        }

        // 下载位置
        String downloadPath = SpringUtil.getBean(DownloadService.class).getDownloadPath(ani);

        String completedPathTemplate = config.getCompletedPathTemplate();

        if (ova) {
            // 剧场版默认不开启摸鱼检测
            ani.setProcrastinating(false);
        }

        return ani
                // tmdb 标题
                .setThemoviedbName(themoviedbName)
                .setDownloadPath(downloadPath)
                .setCustomCompletedPathTemplate(completedPathTemplate);
    }


}
