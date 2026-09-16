package ani.rss.service;

import ani.rss.commons.CacheUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.GroupRegexUtils;
import ani.rss.entity.*;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.BgmUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.thread.NamedThreadFactory;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MikanService {

    @Resource
    private CacheService cacheService;

    /**
     * Mikan 阻塞 IO 专用有界线程池：服务于 {@link #list} 里的「列表 + 评分双源并行」。
     * <p>
     * 原先走 {@code ForkJoinPool.commonPool()}（并行度 = CPU 核数 - 1），任务体是阻塞式 socket 读，
     * FJP 的阻塞补偿对普通 {@code CompletableFuture} 不生效 —— 小主机（2~4 核）上"并行"名不副实，
     * 还会把 commonPool 占满、饿死其它使用 commonPool 的代码。
     * <p>
     * 池大小固定 2：扇出恰好是 2，且这两个任务体不会再向本池提交（无自等待），故不会死锁。
     */
    private static final ExecutorService IO_POOL = ExecutorBuilder.create()
            .setCorePoolSize(2)
            .setMaxPoolSize(2)
            .setWorkQueue(new LinkedBlockingQueue<>(64))
            .setThreadFactory(new NamedThreadFactory("mikan-io", true))
            .build();

    public static String getMikanHost() {
        Config config = ConfigUtil.CONFIG;
        String mikanHost = config.getMikanHost();
        mikanHost = StrUtil.blankToDefault(mikanHost, "https://mikanani.me");
        return mikanHost;
    }

    /**
     * 搜索mikan番剧列表
     *
     * @param text
     * @param season
     * @return
     */
    public Mikan list(String text, Mikan.Season season) {
        AtomicReference<Map<String, MikanBgm>> mikanBgmAtomicReference = new AtomicReference<>(new HashMap<>());
        AtomicReference<Mikan> mikanAtomicReference = new AtomicReference<>();

        // 并行获取 mikan 番剧列表及其评分（走本类专用 IO 池，不再占用 ForkJoinPool.commonPool）
        CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> {
                    Map<String, MikanBgm> mikanBgm = cacheService.getMikanBgm();
                    mikanBgmAtomicReference.set(mikanBgm);
                }, IO_POOL),
                CompletableFuture.runAsync(() -> {
                    Mikan mikan = search(text, season);
                    mikanAtomicReference.set(mikan);
                }, IO_POOL)
        ).join();

        Map<String, MikanBgm> mikanBgmMap = mikanBgmAtomicReference.get();
        Mikan mikan = mikanAtomicReference.get();

        List<String> bgmIdList = AniUtil.getAniList()
                .stream()
                .map(Ani::getBgmUrl)
                .filter(StrUtil::isNotBlank)
                .map(BgmUtil::getSubjectId)
                .distinct()
                .toList();

        List<Mikan.Week> weeks = mikan.getWeeks();
        for (Mikan.Week week : weeks) {
            List<MikanInfo> mikanInfos = week.getItems();
            for (MikanInfo mikanInfo : mikanInfos) {
                String url = mikanInfo.getUrl();
                String mikanId = ReUtil.get("\\d+(/)?$", url, 0);
                if (StrUtil.isBlank(mikanId)) {
                    continue;
                }
                if (!mikanBgmMap.containsKey(mikanId)) {
                    continue;
                }

                MikanBgm mikanBgm = mikanBgmMap.get(mikanId);
                // score 可能为 null, 兜底 0.0, 避免下方 comparingDouble 拆箱 NPE
                Double score = ObjectUtil.defaultIfNull(mikanBgm.getScore(), 0.0);
                String bgmId = mikanBgm.getBgmId();
                mikanInfo.setScore(score)
                        .setBgmId(bgmId);

                if (bgmIdList.contains(bgmId)) {
                    mikanInfo.setExists(true);
                }
            }
            ListUtil.sort(mikanInfos, Comparator.comparingDouble(MikanInfo::getScore).reversed());
        }

        return mikan;
    }

    public Mikan search(String text, Mikan.Season season) {
        Set<String> bangumiIdSet = AniUtil.getAniList().stream()
                .map(AniUtil::getBangumiId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());

        Mikan mikan = new Mikan();
        List<Mikan.Week> weeks = new ArrayList<>();
        List<Mikan.Season> seasons = new ArrayList<>();

        String regex = "^id: (\\d+)$";

        if (ReUtil.contains(regex, text)) {
            String mikanId = ReUtil.get(regex, text, 1);

            MikanInfo mikanInfo = getMikanInfo(mikanId);

            weeks.add(
                    new Mikan.Week()
                            .setWeekLabel("Search")
                            .setItems(Collections.singletonList(mikanInfo))
            );

            return mikan
                    .setTotalItem(1)
                    .setWeeks(weeks)
                    .setSeasons(seasons);
        }

        String url = getMikanHost();
        if (StrUtil.isNotBlank(text)) {
            // 标题需完整 URL 编码, 防止 / & # 等特殊字符截断或改变 query
            url = url + "/Home/Search?searchstr=" + URLUtil.encodeAll(text);
        } else {
            Integer year = season.getYear();
            String seasonStr = season.getSeason();
            if (Objects.nonNull(year) && StrUtil.isNotBlank(seasonStr)) {
                url = StrUtil.format(
                        "{}/Home/BangumiCoverFlowByDayOfWeek?year={}&seasonStr={}",
                        url, year, seasonStr
                );
            }
        }

        // url 上面被重新赋值, 非 effectively final, lambda 里需用拷贝
        final String reqUrl = url;
        withMikanRetry(reqUrl, () -> HttpReq.get(reqUrl)
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    assertNotChallengePage(res);
                    Document document = Jsoup.parse(res.body());
                    Elements dateSelects = document.select(".date-select");
                    if (!dateSelects.isEmpty()) {
                        String dateText = dateSelects.get(0).select(".date-text").text().trim();
                        Element dropdownMenu = dateSelects.get(0).selectFirst(".dropdown-menu");
                        if (Objects.isNull(dropdownMenu)) {
                            // 下拉菜单缺失, 跳过季列表解析
                            log.warn("Mikan 季列表结构异常, 未找到 .dropdown-menu");
                        } else {
                            for (Element child : dropdownMenu.children()) {
                                Elements seasonItems = child.select("li");
                                // 第一个 li 是标题项, 需剔除
                                List<Element> items = seasonItems.size() > 1
                                        ? seasonItems.subList(1, seasonItems.size())
                                        : Collections.emptyList();
                                for (Element seasonItem : items) {
                                    Element a = seasonItem.selectFirst("a");
                                    if (Objects.isNull(a)) {
                                        continue;
                                    }
                                    String dataYear = a.attr("data-year");
                                    String dataSeason = a.attr("data-season");
                                    if (!NumberUtil.isInteger(dataYear)) {
                                        // 年份非数字, 脏数据跳过
                                        continue;
                                    }
                                    String selectLabel = StrUtil.format("{} {}", dataYear, dataSeason);
                                    seasons.add(
                                            new Mikan.Season()
                                                    .setYear(Integer.parseInt(dataYear))
                                                    .setSeason(dataSeason)
                                                    .setSeasonLabel(selectLabel)
                                                    .setSelect(dateText.startsWith(selectLabel))
                                    );
                                }
                            }
                        }
                    }

                    Function<Element, List<MikanInfo>> get = (el) -> {
                        List<MikanInfo> mikanInfos = new ArrayList<>();
                        if (Objects.isNull(el)) {
                            return mikanInfos;
                        }
                        Elements lis = el.select("li");
                        for (Element li : lis) {
                            Element span = li.selectFirst("span");
                            if (Objects.isNull(span)) {
                                continue;
                            }
                            String img = getMikanHost() + span.attr("data-src");
                            Elements aa = li.select("a");
                            if (aa.isEmpty()) {
                                continue;
                            }
                            String href = getMikanHost() + aa.get(0).attr("href");
                            String title = aa.get(0).text();

                            String id = ReUtil.get("\\d+(/)?$", href, 0);
                            id = StrUtil.blankToDefault(id, "");
                            mikanInfos.add(
                                    new MikanInfo()
                                            .setCover(img)
                                            .setTitle(title)
                                            .setUrl(href)
                                            .setExists(bangumiIdSet.contains(id))
                                            .setScore(0.0)
                            );
                        }
                        return mikanInfos;
                    };

                    Elements skBangumis = document.select(".sk-bangumi");

                    if (skBangumis.isEmpty()) {
                        List<MikanInfo> mikanInfos = get.apply(document.selectFirst(".an-ul"));

                        Mikan.Week item = new Mikan.Week();
                        item.setItems(mikanInfos)
                                .setWeekLabel("Search");

                        weeks.add(item);
                    } else {
                        for (Element skBangumi : skBangumis) {
                            List<MikanInfo> mikanInfos = get.apply(skBangumi);
                            if (mikanInfos.isEmpty()) {
                                // 番剧为空
                                continue;
                            }

                            Elements children = skBangumi.children();
                            if (children.isEmpty()) {
                                // 星期标签缺失, 脏数据跳过
                                continue;
                            }

                            // 星期
                            String label = children.get(0).text().trim();

                            Mikan.Week week = new Mikan.Week();
                            week.setWeekLabel(label)
                                    .setItems(mikanInfos);
                            weeks.add(week);
                        }
                    }
                    return null;
                }));

        int totalItems = weeks
                .stream()
                .mapToInt(it -> it.getItems().size())
                .sum();

        return mikan
                .setWeeks(weeks)
                .setTotalItem(totalItems)
                .setSeasons(seasons);
    }

    /**
     * 获取番剧字幕组
     *
     * @param url
     * @return
     */
    public List<Mikan.Group> getGroups(String url) {
        List<Mikan.Group> groupList = withMikanRetry(url, () -> HttpReq.get(url)
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    assertNotChallengePage(res);
                    Document document = Jsoup.parse(res.body());
                    List<Mikan.Group> groups = new ArrayList<>();

                    String bgmUrl = "";
                    Elements bangumiInfos = document.select(".bangumi-info");
                    for (Element bangumiInfo : bangumiInfos) {
                        String string = bangumiInfo.ownText();
                        if (string.equals("Bangumi番组计划链接：")) {
                            Element a = bangumiInfo.selectFirst("a");
                            if (Objects.isNull(a)) {
                                continue;
                            }
                            bgmUrl = a.attr("href");
                        }
                    }

                    Elements subgroupTitles = document.select(".leftbar-item");

                    for (Element subgroupText : subgroupTitles) {
                        Mikan.Group group = new Mikan.Group();
                        List<Mikan.Item> items = new ArrayList<>();
                        group.setItems(items)
                                .setBgmUrl(bgmUrl);
                        String label = subgroupText.select("a.subgroup-name").text().trim();
                        // id锚点，例如 #213
                        String id = subgroupText.select("a.subgroup-name").attr("data-anchor");
                        if (StrUtil.isBlank(id)) {
                            // 锚点为空, 跳过防止空选择器异常
                            continue;
                        }
                        Element anchor = document.selectFirst(id);
                        if (Objects.isNull(anchor)) {
                            // 锚点缺失, 该字幕组结构异常, 跳过
                            log.warn("Mikan 字幕组锚点缺失, 跳过: {}", label);
                            continue;
                        }
                        Element rssElement = anchor.selectFirst(".mikan-rss");
                        if (Objects.isNull(rssElement)) {
                            log.warn("Mikan 字幕组 rss 链接缺失, 跳过: {}", label);
                            continue;
                        }
                        String attr = rssElement.attr("href");
                        group.setLabel(label)
                                .setRss(getMikanHost() + attr);
                        groups.add(group);
                        // 字幕组更新日期
                        String day = subgroupText.select(".date").text().trim();
                        group.setUpdateDay(day);

                        Element table = anchor.nextElementSibling();
                        Element tbody = Objects.isNull(table) ? null : table.selectFirst("tbody");
                        parseEpisodeTable(tbody, items);
                    }

                    return groups;
                }));


        for (Mikan.Group group : groupList) {
            List<Mikan.Item> items = group.getItems();
            GroupRegex groupRegx = GroupRegexUtils.toGroupRegx(items, Mikan.Item::getTitle);

            group.setGroupRegex(groupRegx);
        }

        return groupList;
    }

    /**
     * Mikan 番剧详情（含字幕组与各字幕组的剧集列表）的进程内缓存 TTL。
     * <p>
     * (P2-13) 此前完全没有缓存：同一番剧在「添加订阅」「BGM 轮次」等 per-ani 路径上会被反复抓取 + Jsoup 解析
     * 整页（一页含全部字幕组的剧集表，解析不便宜）。字幕组/剧集变动以天计，2 分钟足够覆盖"同一轮 / 同一请求内"
     * 的重复调用。
     * <p>
     * TTL 取得比 BGM 剧集列表短：单条 MikanInfo 可能达百 KB 级（多个字幕组 × 每组的完整剧集表），
     * 200 个订阅全量驻留会明显吃内存，短 TTL 可以限制驻留窗口。
     */
    private static final long MIKAN_INFO_TTL_MS = TimeUnit.MINUTES.toMillis(2);

    /**
     * Mikan 番剧详情缓存键（抽出来供测试复用，避免测试里手写格式串而与实现脱节）。
     */
    static String mikanInfoCacheKey(String bangumiId) {
        return "MikanService_getMikanInfo:" + bangumiId;
    }

    public static MikanInfo getMikanInfo(String bangumiId) {
        // 存 JSON 快照而非对象本身：MikanService.list 会就地改 item 的 score/bgmId/exists，
        // 直接共享实例会把"上一轮的展示状态"带进下一轮。
        String cacheKey = mikanInfoCacheKey(bangumiId);
        String cached = CacheUtils.get(cacheKey);
        if (StrUtil.isNotBlank(cached)) {
            // 快照解析失败必须当作未命中继续抓取：缓存不能变成故障点
            try {
                MikanInfo cachedInfo = GsonStatic.fromJson(cached, MikanInfo.class);
                if (Objects.nonNull(cachedInfo)) {
                    return cachedInfo;
                }
            } catch (Exception e) {
                log.warn("Mikan 详情缓存快照解析失败, 重新抓取: {} ({})", bangumiId, e.getMessage());
            }
        }

        URI host = URLUtil.getHost(URLUtil.url(getMikanHost()));
        String url = host + "/Home/Bangumi/" + bangumiId;
        MikanInfo info = withMikanRetry(url, () -> HttpReq.get(url)
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    assertNotChallengePage(res);
                    MikanInfo mikanInfo = new MikanInfo();

                    mikanInfo.setUrl(url);

                    Document html = Jsoup.parse(res.body());

                    Element cover = html.selectFirst(".content > img");
                    if (Objects.nonNull(cover)) {
                        mikanInfo.setCover(host + cover.attr("src"));
                    }

                    Element bangumiTitle = html.selectFirst(".bangumi-title");
                    if (Objects.nonNull(bangumiTitle)) {
                        mikanInfo.setTitle(bangumiTitle.text().trim());
                    }

                    Elements bangumiInfos = html.select(".bangumi-info");
                    for (Element bangumiInfo : bangumiInfos) {
                        String string = bangumiInfo.ownText();
                        if (string.equals("Bangumi番组计划链接：")) {
                            Element a = bangumiInfo.selectFirst("a");
                            if (Objects.isNull(a)) {
                                continue;
                            }
                            mikanInfo.setBgmUrl(a.attr("href"));
                        }
                    }

                    // 获取字幕组
                    List<Mikan.Group> groups = new ArrayList<>();

                    Elements subgroupTitles = html.select(".leftbar-item");

                    for (Element subgroupText : subgroupTitles) {
                        Mikan.Group group = new Mikan.Group();

                        List<Mikan.Item> items = new ArrayList<>();
                        group.setItems(items);

                        String label = subgroupText.select("a.subgroup-name").text().trim();

                        // id锚点，例如 #213
                        String id = subgroupText.select("a.subgroup-name").attr("data-anchor");
                        if (StrUtil.isBlank(id)) {
                            // 锚点为空, 跳过防止空选择器异常
                            continue;
                        }

                        Element anchor = html.selectFirst(id);
                        if (Objects.isNull(anchor)) {
                            // 锚点缺失, 该字幕组结构异常, 跳过
                            log.warn("Mikan 字幕组锚点缺失, 跳过: {}", label);
                            continue;
                        }
                        Element rssElement = anchor.selectFirst(".mikan-rss");
                        if (Objects.isNull(rssElement)) {
                            log.warn("Mikan 字幕组 rss 链接缺失, 跳过: {}", label);
                            continue;
                        }
                        String attr = rssElement.attr("href");

                        group.setLabel(label)
                                .setSubgroupId(id.replace("#", "").trim())
                                .setRss(getMikanHost() + attr);

                        groups.add(group);

                        // 字幕组更新日期
                        String day = subgroupText.select(".date").text().trim();

                        group.setUpdateDay(day);

                        Element table = anchor.nextElementSibling();
                        Element tbody = Objects.isNull(table) ? null : table.selectFirst("tbody");
                        parseEpisodeTable(tbody, items);
                    }

                    mikanInfo.setGroups(groups);
                    return mikanInfo;
                }));

        CacheUtils.put(cacheKey, GsonStatic.toJson(info), MIKAN_INFO_TTL_MS);
        return info;
    }

    public static void getMikanInfo(Ani ani, String subgroupId) {
        String bangumiId = AniUtil.getBangumiId(ani);
        if (StrUtil.isBlank(bangumiId)) {
            return;
        }

        MikanInfo mikanInfo = getMikanInfo(bangumiId);
        Assert.notNull(mikanInfo, "未获取到 Mikan 信息");

        String title = mikanInfo.getTitle();
        String bgmUrl = mikanInfo.getBgmUrl();
        List<Mikan.Group> groups = mikanInfo.getGroups();

        ani
                .setMikanTitle(title)
                .setBgmUrl(bgmUrl);

        for (Mikan.Group group : groups) {
            String id = group.getSubgroupId();
            String label = group.getLabel();
            if (subgroupId.equals(id)) {
                ani.setSubgroup(label);
            }
        }
    }

    /**
     * 检测 Mikan 是否返回 Cloudflare 挑战页, 命中则抛出带语义的异常
     */
    private static void assertNotChallengePage(HttpResponse res) {
        String body = res.body();
        if (StrUtil.isNotBlank(body)
                && (body.contains("Just a moment") || body.contains("cf-chl"))) {
            throw new IllegalStateException("Mikan 被 Cloudflare 拦截, 请配置代理");
        }
    }

    /**
     * Mikan 页面抓取统一入口: 响应体读取偶发超时（Cloudflare/直连慢）自动重试一次,
     * 重试仍超时则抛出人话化异常, 不再把裸的 SocketTimeoutException 弹给用户。
     * 非超时类异常原样抛出（Cloudflare 拦截/状态码异常等有自己的语义提示）。
     */
    private static <T> T withMikanRetry(String url, Supplier<T> attempt) {
        try {
            return attempt.get();
        } catch (RuntimeException e) {
            if (!isReadTimeout(e)) {
                throw e;
            }
            log.warn("Mikan 响应读取超时, 自动重试一次: {}", url);
            ThreadUtil.sleep(1500);
            try {
                return attempt.get();
            } catch (RuntimeException retryEx) {
                if (isReadTimeout(retryEx)) {
                    throw new IllegalStateException("Mikan 连接超时, 请检查网络或代理设置");
                }
                throw retryEx;
            }
        }
    }

    /**
     * 异常链中是否为 SocketTimeoutException（hutool HttpException/IORuntimeException 均可能包装）
     */
    private static boolean isReadTimeout(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof SocketTimeoutException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 解析字幕组条目表格 (getGroups 与 getMikanInfo 共用)
     * 逐格判空取值, 单行脏数据跳过而非整体失败
     */
    private static void parseEpisodeTable(Element tbody, List<Mikan.Item> items) {
        if (Objects.isNull(tbody)) {
            // 表格缺失, 无条目
            return;
        }
        for (Element tr : tbody.children()) {
            Elements as = tr.select("a");
            Elements tds = tr.select("td");
            // 需要: a[0] 标题, a[1] 磁力, a[2] 种子链接, td[2] 大小, td[3] 时间
            if (as.size() < 3 || tds.size() < 4) {
                continue;
            }
            String title = as.get(0).ownText();
            String magnet = as.get(1).attr("data-clipboard-text");
            String formatSize = tds.get(2).text().trim();
            String dateStr = tds.get(3).text().trim();
            String torrent = as.get(2).attr("href");

            String mikanHost = getMikanHost();

            items.add(
                    new Mikan.Item()
                            .setTitle(title)
                            .setMagnet(magnet)
                            .setFormatSize(formatSize)
                            .setCreatedAt(parseDateQuietly(dateStr))
                            .setTorrent(mikanHost + torrent)
            );
        }
    }

    /**
     * 安全解析时间字符串, 失败时返回 null 并记录日志
     */
    private static Date parseDateQuietly(String dateStr) {
        if (StrUtil.isBlank(dateStr)) {
            return null;
        }
        try {
            return DateUtil.parse(dateStr);
        } catch (Exception e) {
            log.warn("时间解析失败: {}", dateStr);
            return null;
        }
    }

    /**
     * 从rss中获得字幕组id
     *
     * @param url
     * @return
     */
    public static String getSubgroupId(String url) {
        Map<String, String> decodeParamMap = HttpUtil.decodeParamMap(url, StandardCharsets.UTF_8);

        for (String k : decodeParamMap.keySet()) {
            String v = decodeParamMap.get(k);
            if (k.equalsIgnoreCase("subgroupid")) {
                return v;
            }
        }
        return "";
    }

}
