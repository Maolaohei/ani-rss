package ani.rss.service.subtitle;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.basic.HttpRequestPlus;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ASSRT（伪射手网）字幕源。
 * <p>
 * 使用官方 API（api.assrt.net），以 token 为查询参数。调用分两步：
 * <ol>
 *   <li>{@link #searchItems}——以番剧标题（优先英文）<b>单次</b>搜索，返回候选条目供用户挑选；</li>
 *   <li>{@link #resolveCandidates} / {@link #download}——用户选定后再解析具体文件并下载。</li>
 * </ol>
 * 之所以把「搜索」与「下载」拆开，是因为 ASSRT 配额很紧（默认 5 次/分钟）：旧实现按
 * 「每个视频 × 精确/宽泛两档」发请求，还要逐条调 {@code sub/detail} 补全文件列表，
 * 一次批量匹配就能打满配额并触发 {@code 30900}。现在搜索阶段只发一次请求。
 * <p>
 * 网络层：所有 API 调用都走 {@link #getWithRetry}——连接/读取超时分离、瞬时故障指数退避重试、
 * 主域名不可用时回退备用域名 {@code api.makedie.me}。下载直链走 CDN，不在此列。
 * <p>
 * 解包仅支持 zip（rar/7z 跳过，交由下一个候选）。
 */
@Slf4j
@Service
public class AssrtSubtitleProvider {

    private static final String SEARCH_API = "https://api.assrt.net/v1/sub/search";
    private static final String SEARCH_API_FALLBACK = "https://api.makedie.me/v1/sub/search";
    private static final String DETAIL_API = "https://api.assrt.net/v1/sub/detail";
    private static final String DETAIL_API_FALLBACK = "https://api.makedie.me/v1/sub/detail";

    private static final List<String> SUB_EXT = List.of("ass", "srt", "ssa", "vtt", "sub");
    private static final List<String> ARCHIVE_EXT = List.of("zip", "rar", "7z");

    /**
     * 连接（TCP 握手 + TLS）超时默认值。链路握手通常很快，15s 足够，且能较快暴露
     * 「SYN 无响应」这类被防火墙丢包的情况。
     */
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 15_000;

    /**
     * 读取（等待响应体）超时默认值。ASSRT 在库忙时出数据明显偏慢，
     * 旧实现的 20s 共用超时偏紧，这里放宽到 30s。
     */
    private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

    /**
     * 瞬时故障重试次数（不含首次），默认 2，即最多请求 3 次
     */
    private static final int DEFAULT_RETRY_COUNT = 2;

    /**
     * 重试基础退避（毫秒），按 1s / 2s / 4s 指数递增
     */
    private static final long RETRY_BACKOFF_MS = 1_000L;

    /**
     * 语言关键字，用于从文件名/字段里识别 chs / cht
     */
    private static final Map<String, List<String>> LANG_HINTS = new HashMap<>();
    static {
        LANG_HINTS.put("chs", List.of("chs", "简体", "简", "sc", "zh-cn", "cn", "chinese-simplified"));
        LANG_HINTS.put("cht", List.of("cht", "繁体", "繁", "tc", "zh-tw", "traditional"));
        LANG_HINTS.put("eng", List.of("eng", "english", "en"));
    }

    private static final Pattern SEASON_EP = Pattern.compile("[Ss](\\d{1,2})[.\\s_-]?[Ee](\\d{1,3})");
    private static final Pattern S_SEASON = Pattern.compile("[Ss](\\d{1,2})");
    private static final Pattern CN_SEASON = Pattern.compile("第\\s*(\\d{1,2})\\s*季");
    private static final Pattern EN_SEASON = Pattern.compile("Season\\s*(\\d{1,2})");
    private static final Pattern JP_SEASON = Pattern.compile("シーズン\\s*(\\d+)");
    private static final Pattern E_PAT = Pattern.compile("[Ee][Pp]?\\s*[-_ ]?(\\d{1,3})");
    private static final Pattern CN_EP = Pattern.compile("第\\s*([0-9]+|[一二三四五六七八九十百千两廿卅]+)\\s*[话话集期卷話]");
    // 末位独立 1-3 位数字；允许后面跟扩展名的点（如 05.ass），但不允许夹在更长数字里
    private static final Pattern STANDALONE_EP = Pattern.compile("(?<![\\d.])(\\d{1,3})(?!\\d)");

    private static final Map<Character, Integer> CJK_DIGITS = Map.ofEntries(
            Map.entry('零', 0), Map.entry('一', 1), Map.entry('二', 2), Map.entry('三', 3),
            Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6), Map.entry('七', 7),
            Map.entry('八', 8), Map.entry('九', 9), Map.entry('两', 2), Map.entry('廿', 20),
            Map.entry('卅', 30));
    private static final Map<Character, Integer> CJK_UNITS = Map.of('十', 10, '百', 100, '千', 1000);

    /**
     * ASSRT 频率限制默认值（次/分钟）。实际值取自 {@code Config.assrtRateLimitPerMinute}，
     * 与 assrt.net 用户后台配额保持一致；此处仅作兜底，避免未配置时无限流。
     */
    private static final int DEFAULT_QUOTA_PER_MINUTE = 5;
    private static final AtomicLong LAST_API_TS = new AtomicLong(0);

    /* ==================== 搜索 ==================== */

    /**
     * 单次搜索候选条目。
     * <p>
     * 只发<b>一次</b> {@code sub/search} 请求：既不按视频逐个搜索，也不加 {@code no_muxer}
     * 做「精确 + 宽泛」两档，更不在搜索阶段按季集过滤——候选全量返回给用户自行挑选，
     * 既省配额，也避免自动匹配到错误字幕。
     *
     * @param token         ASSRT token（空则返回空列表）
     * @param keyword       搜索关键词（优先番剧英文标题）
     * @param preferredLang 偏好语言 chs / cht，仅用于排序打分
     * @return 按评分降序的候选条目
     */
    public List<AssrtSubtitleItem> searchItems(String token, String keyword, String preferredLang) {
        List<AssrtSubtitleItem> items = new ArrayList<>();
        if (StrUtil.isBlank(token) || StrUtil.isBlank(keyword)) {
            return items;
        }
        try {
            String body = getWithRetry(searchUrl(SEARCH_API, token, keyword),
                    searchUrl(SEARCH_API_FALLBACK, token, keyword));
            JsonObject root = GsonStatic.fromJson(body, JsonObject.class);
            if (root == null) {
                return items;
            }
            JsonArray subs = resolveSubs(root);
            if (subs == null) {
                return items;
            }
            for (JsonElement e : subs) {
                if (!e.isJsonObject()) {
                    continue;
                }
                AssrtSubtitleItem item = toItem(e.getAsJsonObject(), keyword, preferredLang);
                if (item != null) {
                    items.add(item);
                }
            }
            items.sort(Comparator.comparingDouble(AssrtSubtitleItem::getScore).reversed());
        } catch (Exception ex) {
            log.warn("ASSRT 搜索失败 {}: {}", keyword, ExceptionUtils.getMessage(ex));
        }
        return items;
    }

    /**
     * 把搜索结果的一条记录转为候选条目；无可用信息时返回 {@code null}。
     */
    private AssrtSubtitleItem toItem(JsonObject s, String keyword, String preferredLang) {
        long id = s.has("id") ? s.get("id").getAsLong() : 0L;
        String release = optString(s, "videoname", "name", "release", "native_name");
        String langField = optString(s, "lang", "language", "langchi");
        String pkgUrl = optString(s, "url");

        AssrtSubtitleItem item = new AssrtSubtitleItem();
        item.setId(id);
        item.setTitle(release);
        item.setLang(detectLang("", langField, preferredLang));

        List<AssrtSubtitleItem.FileEntry> entries = new ArrayList<>();
        for (JsonObject f : resolveFiles(s, "files")) {
            AssrtSubtitleItem.FileEntry entry = toEntry(f, langField, preferredLang);
            if (entry != null) {
                entries.add(entry);
            }
        }
        item.setFiles(entries);
        item.setFileCount(entries.isEmpty() ? -1 : entries.size());

        if (entries.isEmpty() && StrUtil.isNotBlank(pkgUrl)) {
            // 只有整包直链（合集常见形态）
            item.setArchive(true);
            item.setUrl(pkgUrl);
        }
        if (entries.isEmpty() && StrUtil.isBlank(pkgUrl) && id <= 0) {
            // 既无文件、又无直链、还没有 id 可以补全，整条记录没有价值
            return null;
        }

        // 排序打分：语言偏好 > 标题相似度 > ass/ssa 格式 > 整包降级
        double score = 0;
        String lang = StrUtil.blankToDefault(item.getLang(), "");
        if (preferredLang != null && preferredLang.equalsIgnoreCase(lang)) {
            score += 100;
        } else if (lang.isEmpty()) {
            score += 10;
        } else {
            score -= 40;
        }
        score += similarity(keyword, release) * 20;
        boolean hasAss = entries.stream()
                .anyMatch(en -> "ass".equals(en.getExt()) || "ssa".equals(en.getExt()));
        if (hasAss) {
            score += 8;
        }
        if (item.isArchive()) {
            score -= 25;
        }
        item.setScore(score);
        return item;
    }

    /**
     * 把 {@code files[]} / {@code filelist[]} 里的一项转为文件条目；
     * 非字幕且非压缩包的条目返回 {@code null}。
     */
    private AssrtSubtitleItem.FileEntry toEntry(JsonObject f, String langField, String preferredLang) {
        String name = optString(f, "f", "name", "filename");
        String url = optString(f, "url");
        if (StrUtil.isBlank(url) || StrUtil.isBlank(name)) {
            return null;
        }
        String ext = FileUtil.extName(name).toLowerCase(Locale.ROOT);
        boolean archive = ARCHIVE_EXT.contains(ext);
        if (!archive && !SUB_EXT.contains(ext)) {
            return null;
        }
        AssrtSubtitleItem.FileEntry entry = new AssrtSubtitleItem.FileEntry();
        entry.setName(name);
        entry.setUrl(url);
        entry.setExt(ext);
        entry.setArchive(archive);
        entry.setLang(detectLang(name, langField, preferredLang));
        return entry;
    }

    /* ==================== 解析与下载 ==================== */

    /**
     * 把用户选中的候选条目解析为可下载的具体文件。
     * <p>
     * 条目内联了 {@code files} 时直接展开（<b>不消耗</b>额外配额）；否则按 id 调一次
     * {@code sub/detail} 补全。整包条目（仅有 {@code url}）包装成压缩包候选，
     * 交由 {@link #download} 解包后按目标集数挑选。
     *
     * @param token         ASSRT token
     * @param item          用户选中的候选条目
     * @param preferredLang 偏好语言，用于候选排序
     * @return 按「单文件优先、格式优先、语言匹配优先」排序的候选文件
     */
    public List<SubtitleCandidate> resolveCandidates(String token, AssrtSubtitleItem item,
                                                     String preferredLang) {
        List<SubtitleCandidate> candidates = new ArrayList<>();
        if (item == null) {
            return candidates;
        }
        List<AssrtSubtitleItem.FileEntry> entries = new ArrayList<>(item.getFiles());
        if (entries.isEmpty() && item.getId() > 0) {
            for (JsonObject f : fetchDetailFiles(token, item.getId())) {
                AssrtSubtitleItem.FileEntry entry = toEntry(f, item.getLang(), preferredLang);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        for (AssrtSubtitleItem.FileEntry entry : entries) {
            SubtitleCandidate c = new SubtitleCandidate();
            c.setUrl(entry.getUrl());
            c.setFileName(entry.getName());
            c.setExt(entry.getExt());
            c.setArchive(entry.isArchive());
            c.setLang(StrUtil.blankToDefault(entry.getLang(), item.getLang()));
            candidates.add(c);
        }
        if (candidates.isEmpty() && StrUtil.isNotBlank(item.getUrl())) {
            SubtitleCandidate arc = archiveCandidate(item.getUrl(), item.getTitle());
            if (arc != null) {
                arc.setLang(StrUtil.blankToDefault(item.getLang(), ""));
                candidates.add(arc);
            }
        }
        // 单文件优先（合集包要解包且可能不含目标集）；其次 ass/ssa；最后语言匹配
        candidates.sort(Comparator
                .comparingInt((SubtitleCandidate c) -> c.isArchive() ? 1 : 0)
                .thenComparingInt(c -> ("ass".equals(c.getExt()) || "ssa".equals(c.getExt())) ? 0 : 1)
                .thenComparingInt(c -> preferredLang != null && preferredLang.equalsIgnoreCase(c.getLang()) ? 0 : 1));
        return candidates;
    }

    /**
     * 判断单文件候选是否与目标视频的季/集匹配。
     * <p>
     * 双方都能解析且不一致时返回 {@code false}（剔除）；任一方解析不出则放行，避免误伤。
     * 压缩包候选不适用本判定（合集包内条目在解包阶段逐个判定）。
     *
     * @param candidateName 候选文件名
     * @param release       候选的发布名（辅助解析）
     * @param videoSeason   目标季（可空）
     * @param videoEp       目标集（可空）
     */
    public static boolean matchesEpisode(String candidateName, String release,
                                         Integer videoSeason, Integer videoEp) {
        int[] ce = extractSeasonEpisode(candidateName + " " + release);
        Integer cSeason = ce[0] >= 0 ? ce[0] : null;
        Integer cEp = ce[1] >= 0 ? ce[1] : null;
        if (videoEp != null && cEp != null && !videoEp.equals(cEp)) {
            return false;
        }
        return videoSeason == null || cSeason == null || videoSeason.equals(cSeason);
    }

    /**
     * 下载候选字幕内容。
     *
     * @param c             候选
     * @param preferredLang 偏好语言（用于从压缩包里挑文件）
     * @param targetSeason  目标季（可空）；合集包解包时用于按季过滤
     * @param targetEp      目标集（可空）；合集包解包时用于按集过滤
     * @param videoName     目标视频文件名（用于压缩包内按相似度挑选）
     * @param ani           订阅（提供已知季数，供元数据解析对比；可为 null）
     * @return 字幕挑选结果（含命中原始文件名与解析季数）；无法获取返回 null
     */
    public SubtitlePick download(SubtitleCandidate c, String preferredLang,
                                 Integer targetSeason, Integer targetEp, String videoName, Ani ani) {
        return download(c, preferredLang, targetSeason, targetEp, videoName, ani, null);
    }

    /**
     * 带「计划内字节缓存」的下载。
     * <p>
     * (P1-17) 合集压缩包候选会在 {@code SubtitleService#planOne} 的<b>逐视频</b>循环里被反复取用：
     * 同一 URL 若不缓存，每个视频都要把整包重新下载一遍（一季 12~24 集 = 同样一次下载做 12~24 次）。
     * 缓存由调用方按"一次计划构建"为生命周期传入，构建结束即释放，不会跨计划驻留大对象。
     *
     * @param bytesCache URL → 已下载字节；可为 {@code null}（不缓存，等价于旧行为）
     */
    public SubtitlePick download(SubtitleCandidate c, String preferredLang,
                                 Integer targetSeason, Integer targetEp, String videoName, Ani ani,
                                 Map<String, byte[]> bytesCache) {
        try {
            byte[] data = bytesCache == null ? null : bytesCache.get(c.getUrl());
            if (data == null || data.length == 0) {
                data = fetchBytes(c.getUrl());
                if (data == null || data.length == 0) {
                    return null;
                }
                // 部分 url 返回的是 JSON 重定向（含真实 url），再跳一次
                String asText = new String(data, StandardCharsets.UTF_8).trim();
                if (asText.startsWith("{")) {
                    try {
                        JsonObject j = GsonStatic.fromJson(asText, JsonObject.class);
                        if (j != null && j.has("url") && j.get("url").isJsonPrimitive()) {
                            data = fetchBytes(j.get("url").getAsString());
                        }
                    } catch (Exception ignored) {
                        // 不是 JSON，按字幕正文处理
                    }
                }
                // 只缓存有效结果：失败/空结果绝不入缓存，否则一次抖动会被固化成"这个候选是空的"
                if (bytesCache != null && data != null && data.length > 0) {
                    bytesCache.put(c.getUrl(), data);
                }
            }
            if (c.isArchive()) {
                return extractBestFromZip(data, c.getFileName(), videoName, preferredLang, targetSeason, targetEp, ani);
            }
            Integer resolved = SubtitleSeasonResolver.resolve(ani, seriesNameOf(c.getFileName()));
            return new SubtitlePick(data, c.getFileName(), resolved);
        } catch (Exception ex) {
            log.warn("ASSRT 字幕下载失败 {}: {}", c.getFileName(), ExceptionUtils.getMessage(ex));
            return null;
        }
    }

    /* ==================== 网络层：超时 / 重试 / 域名回退 ==================== */

    /**
     * 带超时与重试的 GET。
     * <p>
     * 瞬时故障（连接/读取超时、连接被重置、DNS 失败、5xx、429 与 ASSRT 的 30900 限流）
     * 按 1s / 2s / 4s 指数退避重试；重试时<b>交替使用主/备域名</b>，主域名持续不可用时
     * 下一次尝试直接换 {@code api.makedie.me}。确定性错误（token 无效 20001、
     * 关键词过短 101 等 4xx）直接抛出，不做无谓重试。
     *
     * @param primaryUrl  主域名请求地址
     * @param fallbackUrl 备用域名请求地址（可空）
     */
    private String getWithRetry(String primaryUrl, String fallbackUrl) {
        String[] urls = StrUtil.isBlank(fallbackUrl) || fallbackUrl.equals(primaryUrl)
                ? new String[]{primaryUrl}
                : new String[]{primaryUrl, fallbackUrl};
        int retry = retryCount();
        RuntimeException last = null;
        for (int attempt = 0; attempt <= retry; attempt++) {
            String url = urls[attempt % urls.length];
            // 抑制底层 ERROR 日志：瞬时故障会在这里重试，由下面带上下文的 WARN 记录
            HttpRequestPlus.setRetryMode(true);
            try {
                return getOnce(url);
            } catch (RuntimeException e) {
                last = e;
                if (!isTransient(e)) {
                    throw e;
                }
                if (attempt >= retry) {
                    break;
                }
                long delay = RETRY_BACKOFF_MS << attempt;
                log.warn("ASSRT 请求临时故障，准备重试 attempt={}/{} url={} delayMs={} error={}",
                        attempt + 1, retry + 1, url, delay, ExceptionUtils.getMessage(e));
                ThreadUtil.sleep(delay);
            } finally {
                HttpRequestPlus.setRetryMode(false);
            }
        }
        throw last == null ? new IllegalStateException("ASSRT 请求失败") : last;
    }

    /**
     * 执行一次 ASSRT API 请求（限流 + 分离超时）。
     * 非 2xx 一律抛异常，交由 {@link #getWithRetry} 判定是否可重试。
     */
    private String getOnce(String url) {
        throttle();
        try (HttpResponse res = HttpReq.get(url, connectTimeoutMs(), readTimeoutMs()).execute()) {
            int status = res.getStatus();
            if (status >= 200 && status < 300) {
                return res.body();
            }
            throw new IllegalStateException("url: " + url + ", status: " + status);
        }
    }

    /**
     * 判断是否为可重试的瞬时故障。
     * <p>
     * 除网络层异常（超时/连接失败/DNS）外，还覆盖服务端 5xx、429，以及 ASSRT 文档中
     * 明确要求「退避重试」的 {@code 30900}（超出接口调用限制）。
     */
    static boolean isTransient(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String message = StrUtil.blankToDefault(current.getMessage(), "").toLowerCase(Locale.ROOT);
            if (className.contains("sockettimeout")
                    || className.contains("connectexception")
                    || className.contains("noroutetohost")
                    || className.contains("unknownhost")
                    || className.contains("socketexception")
                    || message.contains("read timed out")
                    || message.contains("connect timed out")
                    || message.contains("connection reset")
                    || message.contains("connection refused")
                    || message.contains("status: 429")
                    || message.contains("status: 500")
                    || message.contains("status: 502")
                    || message.contains("status: 503")
                    || message.contains("status: 504")
                    || message.contains("30900")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String searchUrl(String api, String token, String query) {
        String q = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        String t = URLEncoder.encode(token.trim(), StandardCharsets.UTF_8);
        return api + "?token=" + t + "&q=" + q + "&cnt=15&pos=0";
    }

    /**
     * ASSRT 连接超时（毫秒）：取自配置，非法/未配置时回落默认
     */
    private static int connectTimeoutMs() {
        return clamp(configInt(c -> c.getAssrtConnectTimeoutMs()), 3_000, 120_000, DEFAULT_CONNECT_TIMEOUT_MS);
    }

    /**
     * ASSRT 读取超时（毫秒）：取自配置，非法/未配置时回落默认
     */
    private static int readTimeoutMs() {
        return clamp(configInt(c -> c.getAssrtReadTimeoutMs()), 5_000, 300_000, DEFAULT_READ_TIMEOUT_MS);
    }

    /**
     * 瞬时故障重试次数：取自配置，非法/未配置时回落默认（上限 5 次，避免长时间阻塞）
     */
    private static int retryCount() {
        return clamp(configInt(c -> c.getAssrtRetryCount()), 0, 5, DEFAULT_RETRY_COUNT);
    }

    private static int configInt(java.util.function.ToIntFunction<Config> getter) {
        try {
            Config config = ConfigUtil.CONFIG;
            return config == null ? Integer.MIN_VALUE : getter.applyAsInt(config);
        } catch (Exception ignored) {
            // 配置未就绪（如单测环境）时回落默认
            return Integer.MIN_VALUE;
        }
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value == Integer.MIN_VALUE) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    /* ==================== 内部工具 ==================== */

    private static JsonArray resolveSubs(JsonObject root) {
        JsonElement subEl = root.get("sub");
        if (subEl == null) {
            JsonElement direct = root.get("subs");
            if (direct != null && direct.isJsonArray()) {
                return direct.getAsJsonArray();
            }
            return null;
        }
        if (subEl.isJsonArray()) {
            return subEl.getAsJsonArray();
        }
        if (subEl.isJsonObject()) {
            JsonElement s = subEl.getAsJsonObject().get("subs");
            if (s != null && s.isJsonArray()) {
                return s.getAsJsonArray();
            }
        }
        return null;
    }

    private static List<JsonObject> resolveFiles(JsonObject sub, String key) {
        List<JsonObject> files = new ArrayList<>();
        JsonElement fe = sub.get(key);
        if (fe != null && fe.isJsonArray()) {
            for (JsonElement x : fe.getAsJsonArray()) {
                if (x.isJsonObject()) {
                    files.add(x.getAsJsonObject());
                }
            }
        }
        return files;
    }

    /**
     * 按 id 调一次 {@code sub/detail} 补全文件列表；失败返回空列表。
     */
    private List<JsonObject> fetchDetailFiles(String token, long id) {
        try {
            String body = getWithRetry(detailUrl(DETAIL_API, token, id), detailUrl(DETAIL_API_FALLBACK, token, id));
            JsonObject root = GsonStatic.fromJson(body, JsonObject.class);
            if (root == null || !root.has("sub")) {
                return List.of();
            }
            JsonElement subEl = root.get("sub");
            if (!subEl.isJsonObject()) {
                return List.of();
            }
            JsonObject sub = subEl.getAsJsonObject();
            List<JsonObject> files = resolveFiles(sub, "filelist");
            if (!files.isEmpty()) {
                return files;
            }
            files = resolveFiles(sub, "files");
            if (!files.isEmpty()) {
                return files;
            }
            // 仅压缩包直链
            String pkgUrl = optString(sub, "url");
            if (StrUtil.isNotBlank(pkgUrl)) {
                SubtitleCandidate arc = archiveCandidate(pkgUrl, "");
                if (arc != null) {
                    JsonObject wrap = new JsonObject();
                    wrap.addProperty("f", arc.getFileName());
                    wrap.addProperty("url", arc.getUrl());
                    return List.of(wrap);
                }
            }
            return List.of();
        } catch (Exception ex) {
            log.warn("ASSRT 详情获取失败 id={}: {}", id, ExceptionUtils.getMessage(ex));
            return List.of();
        }
    }

    private static String detailUrl(String api, String token, long id) {
        String t = URLEncoder.encode(token.trim(), StandardCharsets.UTF_8);
        return api + "?token=" + t + "&id=" + id;
    }

    private SubtitleCandidate archiveCandidate(String pkgUrl, String release) {
        String name = release;
        if (StrUtil.isBlank(name)) {
            int slash = pkgUrl.lastIndexOf('/');
            name = slash >= 0 ? pkgUrl.substring(slash + 1) : "package";
        }
        String ext = FileUtil.extName(name).toLowerCase(Locale.ROOT);
        if (!ARCHIVE_EXT.contains(ext)) {
            ext = "zip";
        }
        SubtitleCandidate c = new SubtitleCandidate();
        c.setUrl(pkgUrl);
        c.setFileName(name);
        c.setExt(ext);
        c.setArchive(true);
        return c;
    }

    /**
     * 全局限流：保证两次 ASSRT API 调用之间至少间隔 {@link #currentMinIntervalMs()}，
     * 该间隔由 {@code Config.assrtRateLimitPerMinute} 决定（默认 5 次/分钟，避免 {@code 30900}）。
     * CAS 自旋确保并发安全。仅作用于 api.assrt.net 的接口调用（search/detail）；下载直链走 CDN，不在此列。
     */
    private static void throttle() {
        while (true) {
            long now = System.currentTimeMillis();
            long last = LAST_API_TS.get();
            long wait = last + currentMinIntervalMs() - now;
            if (wait <= 0) {
                if (LAST_API_TS.compareAndSet(last, now)) {
                    return;
                }
                continue;
            }
            ThreadUtil.sleep(wait);
        }
    }

    /**
     * 当前限流间隔（毫秒）：由 {@code Config.assrtRateLimitPerMinute} 决定，范围 1~120 次/分钟，
     * 未配置或非法时回落默认 {@link #DEFAULT_QUOTA_PER_MINUTE}。
     */
    private static long currentMinIntervalMs() {
        int rpm = DEFAULT_QUOTA_PER_MINUTE;
        int configured = configInt(c -> c.getAssrtRateLimitPerMinute());
        if (configured != Integer.MIN_VALUE && configured > 0) {
            rpm = configured;
        }
        if (rpm > 120) {
            rpm = 120;
        }
        return 60_000L / rpm;
    }

    /**
     * 取字幕文件字节（CDN 直链，<b>不</b>受 ASSRT 接口限流约束）。
     * <p>
     * 包可见（非 private）是刻意留的测试接缝：{@link #download} 的「计划内字节缓存」语义
     * （同一 URL 只下一次）必须能在不起真实网络的前提下被断言，见
     * {@code AssrtSubtitleProviderBytesCacheTest}。
     */
    byte[] fetchBytes(String url) {
        String safeUrl = url;
        if (safeUrl.startsWith("//")) {
            safeUrl = "https:" + safeUrl;
        }
        byte[] data;
        try (HttpResponse res = HttpReq.get(safeUrl, connectTimeoutMs(), readTimeoutMs()).execute()) {
            if (!res.isOk()) {
                data = new byte[0];
            } else {
                data = res.bodyBytes();
            }
        }
        return data;
    }

    /**
     * 从压缩包（zip）中挑选最匹配目标集/季的字幕。
     * <p>
     * 当已知目标集数时，只保留命中的那一条（必要时再按季过滤），再在命中集合里
     * 按「语言匹配 + 文件名相似度」选最优；若整包都没有目标集，返回 null 让调用方尝试下一个候选。
     * 目标集数为空时退化为按相似度 + 语言挑选（旧行为）。
     * <p>
     * 季数解析（{@link SubtitleSeasonResolver}）：当目标季已知（来自视频名标记或订阅季数）时，
     * 优先挑选「季数已确认为目标季」的条目；只有当整包都没有季数确认的条目时，才回落到纯相似度挑选，
     * 避免第 1 季字幕因文件名更短相似度更高而误挂到第 2 季订阅。
     *
     * @param ani 订阅（提供已知季数，供元数据解析对比；可为 null）
     */
    private SubtitlePick extractBestFromZip(byte[] data, String archiveName, String videoName,
                                            String preferredLang, Integer targetSeason, Integer targetEp, Ani ani) {
        Integer subscriptionSeason = (ani != null && ani.getSeason() != null && ani.getSeason() >= 1)
                ? ani.getSeason() : null;
        // 目标季：视频名里的显式标记优先；否则用订阅季数作为对照基准
        Integer effectiveSeason = targetSeason != null ? targetSeason : subscriptionSeason;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            SubtitlePick bestConfirmed = null;
            double bestConfirmedScore = -1;
            SubtitlePick bestFuzzy = null;
            double bestFuzzyScore = -1;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                String ext = FileUtil.extName(name).toLowerCase(Locale.ROOT);
                if (!SUB_EXT.contains(ext)) {
                    continue;
                }
                // 已知目标集数时按集/季过滤
                int[] se = extractSeasonEpisode(name);
                int s = se[0];
                int e = se[1];
                if (targetEp != null) {
                    if (e < 0) {
                        continue; // 无法确认集数，跳过
                    }
                    if (e != targetEp) {
                        continue; // 集数不符，跳过
                    }
                }
                if (effectiveSeason != null && s >= 0 && s != effectiveSeason) {
                    continue; // 显式季标记与目标季不符，跳过
                }

                // 解析条目季数：显式标记优先，否则走元数据解析（与订阅对比）
                Integer entrySeason = s >= 0 ? s : SubtitleSeasonResolver.resolve(ani, seriesNameOf(name));
                boolean seasonConfirmed = effectiveSeason != null
                        && entrySeason != null && entrySeason.equals(effectiveSeason);

                byte[] content = zis.readAllBytes();
                double sc = similarity(videoName, name) * 50;
                if (preferredLang.equalsIgnoreCase(detectLang(name, "", preferredLang))) {
                    sc += 100;
                }
                if (seasonConfirmed) {
                    if (sc > bestConfirmedScore) {
                        bestConfirmedScore = sc;
                        bestConfirmed = new SubtitlePick(content, name, entrySeason);
                    }
                } else {
                    if (sc > bestFuzzyScore) {
                        bestFuzzyScore = sc;
                        bestFuzzy = new SubtitlePick(content, name, entrySeason);
                    }
                }
            }
            return bestConfirmed != null ? bestConfirmed : bestFuzzy;
        } catch (Exception ex) {
            log.warn("ASSRT 压缩包解包失败 {}: {}", archiveName, ExceptionUtils.getMessage(ex));
            return null;
        }
    }

    /**
     * 从文件名中提取「番剧系列名」（用于元数据季数解析的缓存键与对照）。
     * 剔除分辨率/编码等技术词与集数标记，保留番剧名主体，例如
     * {@code High School DxD NEW 第05話} → {@code High School DxD NEW}。
     */
    private static String seriesNameOf(String name) {
        if (StrUtil.isBlank(name)) {
            return "";
        }
        String s = cleanTechnicalTokens(name);
        // 去掉集数标记：第N話/集/期、E/EP\d+、S\d+E\d+、x\d+（如 1080p 已在前一步剔除）
        s = s.replaceAll("第[\\s]*[0-9一二三四五六七八九十百千]+[\\s]*(話|话|集|期)", " ")
                .replaceAll("(?i)\\b[Ee][Pp]?\\s*\\d+", " ")
                .replaceAll("(?i)\\bS\\d+\\s*E\\d+", " ")
                .replaceAll("(?i)\\bx\\s*\\d+", " ")
                .replaceAll("[_\\-\\.\\[\\]()（）]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return s;
    }

    private String detectLang(String fileName, String langField, String preferredLang) {
        String lower = StrUtil.blankToDefault(fileName, "").toLowerCase(Locale.ROOT);
        for (String kw : LANG_HINTS.getOrDefault(preferredLang, List.of())) {
            if (lower.contains(kw)) {
                return preferredLang;
            }
        }
        for (Map.Entry<String, List<String>> e : LANG_HINTS.entrySet()) {
            for (String kw : e.getValue()) {
                if (lower.contains(kw)) {
                    return e.getKey();
                }
            }
        }
        if (StrUtil.isNotBlank(langField)) {
            String lf = langField.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, List<String>> e : LANG_HINTS.entrySet()) {
                for (String kw : e.getValue()) {
                    if (lf.contains(kw)) {
                        return e.getKey();
                    }
                }
            }
        }
        return "";
    }

    private double similarity(String a, String b) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    private Set<String> tokens(String s) {
        Set<String> set = new HashSet<>();
        if (StrUtil.isBlank(s)) {
            return set;
        }
        for (String t : s.toLowerCase(Locale.ROOT).split("[^\\w\\u4e00-\\u9fa5]+")) {
            if (t.isEmpty() || t.matches("\\d+")) {
                continue;
            }
            set.add(t);
        }
        return set;
    }

    /**
     * 从文件名解析「季 + 集」。
     * <p>
     * 先剔除分辨率/编码/帧率/位深/尺寸等技术参数里的数字（否则 {@code YUV420P10}、{@code 1920x1080}
     * 等会被误当成集数），再按优先级识别：{@code SxxExx} → 单独的 {@code E/EPxx} →
     * 「第N话/集/期」(支持阿拉伯与中文数字) → 末位独立 1-3 位数字（允许后面跟扩展名的点）。
     *
     * @return {@code int[]{season, episode}}，未知则为 -1。
     */
    public static int[] extractSeasonEpisode(String name) {
        int season = -1;
        int episode = -1;
        if (StrUtil.isBlank(name)) {
            return new int[]{season, episode};
        }
        String s = cleanTechnicalTokens(name);

        // 季：Sxx / 第N季 / Season N / シーズンN
        Matcher ms;
        if ((ms = S_SEASON.matcher(s)).find()) {
            season = parseIntSafe(ms.group(1));
        } else if ((ms = CN_SEASON.matcher(s)).find()) {
            season = parseIntSafe(ms.group(1));
        } else if ((ms = EN_SEASON.matcher(s)).find()) {
            season = parseIntSafe(ms.group(1));
        } else if ((ms = JP_SEASON.matcher(s)).find()) {
            season = parseIntSafe(ms.group(1));
        }

        // 集（优先级）
        Matcher me = SEASON_EP.matcher(s);
        if (me.find()) {
            episode = parseIntSafe(me.group(2));
        }
        if (episode < 0 && (me = E_PAT.matcher(s)).find()) {
            episode = parseIntSafe(me.group(1));
        }
        if (episode < 0 && (me = CN_EP.matcher(s)).find()) {
            Integer v = parseNumberOrCJK(me.group(1));
            if (v != null) {
                episode = v;
            }
        }
        if (episode < 0) {
            Matcher m2 = STANDALONE_EP.matcher(s);
            Integer last = null;
            while (m2.find()) {
                last = parseIntSafe(m2.group(1));
            }
            if (last != null) {
                episode = last;
            }
        }

        if (season < 0 || season > 99) {
            season = -1;
        }
        if (episode < 0 || episode > 999) {
            episode = -1;
        }
        return new int[]{season, episode};
    }

    private static int parseIntSafe(String v) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 剔除文件名中的技术参数数字（分辨率/编码/帧率/位深/尺寸/无意义词/含数字的圆括号），
     * 避免被误判为季/集数。例：{@code (BD 1920x1080 HEVC-YUV420P10 FLAC)} 整段会被移除。
     */
    private static String cleanTechnicalTokens(String name) {
        String s = name;
        s = s.replaceAll("\\d{3,4}\\s*[x×]\\s*\\d{3,4}", " ");
        s = s.replaceAll("\\b(?:480|576|720|1080|1440|2160)\\s*[ip]?\\b", " ");
        s = s.replaceAll("\\b(?:x|h)?26[45]\\b|\\b(?:hevc|avc|vp9|av1)\\b", " ");
        s = s.replaceAll("(?:yuv)?\\s*4[0-9]{2}\\s*p?\\s*(?:1[0-9])?", " ");
        s = s.replaceAll("\\b(?:8|10|12)\\s*bit\\b", " ");
        s = s.replaceAll("\\d{2}(?:\\.\\d+)?\\s*fps", " ");
        s = s.replaceAll("\\([^()]*\\d[^()]*\\)", " ");
        s = s.replaceAll("\\b(?:BD|BDRip|WEB|WEBRip|HD|HDTV|FLAC|AAC|MP4|MKV|DVD|REMUX|RAW|TC)\\b", " ");
        return s;
    }

    /**
     * 解析阿拉伯数字或中文数字（零~千）为 int；无法解析返回 null。
     */
    private static Integer parseNumberOrCJK(String s) {
        if (StrUtil.isBlank(s)) {
            return null;
        }
        s = s.trim();
        if (s.matches("\\d+")) {
            int v = parseIntSafe(s);
            return v < 0 ? null : v;
        }
        int result = 0;
        int current = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (CJK_DIGITS.containsKey(c)) {
                current = CJK_DIGITS.get(c);
            } else if (CJK_UNITS.containsKey(c)) {
                int unit = CJK_UNITS.get(c);
                if (current == 0) {
                    current = 1;
                }
                result += current * unit;
                current = 0;
            }
        }
        result += current;
        return result > 0 ? result : null;
    }

    private static String optString(JsonObject obj, String... keys) {
        for (String k : keys) {
            JsonElement e = obj.get(k);
            if (e != null && e.isJsonPrimitive() && StrUtil.isNotBlank(e.getAsString())) {
                return e.getAsString();
            }
        }
        return "";
    }
}
