package ani.rss.service.subtitle;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
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
 * 使用官方 API（api.assrt.net），以 token 为查询参数；先 {@code sub/search} 列出候选，
 * 必要时 {@code sub/detail} 拉取直链，再按「集数命中 + 语言偏好 + 文件名相似度」打分，
 * 由调用方挑选最优并下载。
 * <p>
 * 搜索分两档：先以视频文件名 + {@code no_muxer=1} 精确命中单集字幕；无果再以番剧标题宽泛搜索，
 * 此时可能命中横跨多季的<b>完整合集包</b>——合集包不在候选阶段按集数剔除，而是在
 * {@link #extractBestFromZip} 中按目标集/季挑选并重命名。解包仅支持 zip（rar/7z 跳过，交由下一个候选）。
 */
@Slf4j
@Service
public class AssrtSubtitleProvider {

    private static final String SEARCH_API = "https://api.assrt.net/v1/sub/search";
    private static final String DETAIL_API = "https://api.assrt.net/v1/sub/detail";

    private static final List<String> SUB_EXT = List.of("ass", "srt", "ssa", "vtt", "sub");
    private static final List<String> ARCHIVE_EXT = List.of("zip", "rar", "7z");

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

    /**
     * 搜索字幕候选。
     *
     * @param token          ASSRT token（空则直接返回空）
     * @param keyword        搜索关键词（通常为番剧标题）
     * @param videoName      目标视频文件名，用于集数与文件名匹配
     * @param preferredLang  偏好语言 chs / cht
     * @return 按评分降序的候选列表
     */
    /**
     * 搜索字幕候选。
     * <p>
     * 采用两档策略：
     * <ol>
     *   <li><b>精确优先</b>——以视频文件名 + {@code no_muxer=1}（隐含 is_file=1）搜索，优先命中单集字幕；</li>
     *   <li><b>宽泛兜底</b>——精确无果时以番剧标题搜索，可能命中<b>横跨多季的完整合集包</b>，
     *       交由解包阶段按目标集数挑选（见 {@link #extractBestFromZip}）。</li>
     * </ol>
     *
     * @param token          ASSRT token（空则直接返回空）
     * @param keyword        搜索关键词（通常为番剧标题），兜底搜索使用
     * @param videoName      目标视频文件名，用于集数匹配与精确搜索
     * @param preferredLang  偏好语言 chs / cht
     * @return 按评分降序的候选列表
     */
    public List<SubtitleCandidate> search(String token, String keyword, String videoName, String preferredLang) {
        List<SubtitleCandidate> candidates = new ArrayList<>();
        if (StrUtil.isBlank(token)) {
            return candidates;
        }
        int[] se = extractSeasonEpisode(videoName);
        Integer videoSeason = se[0] >= 0 ? se[0] : null;
        Integer videoEp = se[1] >= 0 ? se[1] : null;

        // 1) 精确搜索：以视频文件名 + no_muxer 命中单集字幕
        if (StrUtil.isNotBlank(videoName)) {
            candidates.addAll(doSearch(token, videoName, true, videoName, preferredLang, videoSeason, videoEp));
        }
        // 2) 宽泛搜索：以番剧标题兜底，可能命中完整合集包
        if (candidates.isEmpty() && StrUtil.isNotBlank(keyword)) {
            candidates.addAll(doSearch(token, keyword, false, videoName, preferredLang, videoSeason, videoEp));
        }

        candidates.sort(Comparator.comparingDouble(SubtitleCandidate::getScore).reversed());
        return candidates;
    }

    /**
     * 执行一次 {@code sub/search} 并把结果解析为候选列表。
     *
     * @param noMuxer 是否附加 {@code no_muxer=1}（以视频文件名精匹配）
     */
    private List<SubtitleCandidate> doSearch(String token, String query, boolean noMuxer,
                                             String videoName, String preferredLang,
                                             Integer videoSeason, Integer videoEp) {
        List<SubtitleCandidate> candidates = new ArrayList<>();
        try {
            String q = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
            String url = SEARCH_API + "?token=" + token + "&q=" + q + "&cnt=15&pos=0"
                    + (noMuxer ? "&no_muxer=1" : "");
            throttle();
            String body;
            try (HttpResponse res = HttpReq.get(url).execute()) {
                HttpReq.assertStatus(res);
                body = res.body();
            }
            JsonObject root = GsonStatic.fromJson(body, JsonObject.class);
            if (root == null) {
                return candidates;
            }
            JsonArray subs = resolveSubs(root);
            if (subs == null) {
                return candidates;
            }

            for (JsonElement e : subs) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject s = e.getAsJsonObject();
                long id = s.has("id") ? s.get("id").getAsLong() : 0L;
                String release = optString(s, "videoname", "name", "release", "native_name");
                String langField = optString(s, "lang", "language", "langchi");

                List<JsonObject> files = resolveFiles(s, "files");
                if (files.isEmpty() && id > 0) {
                    files = fetchDetailFiles(token, id);
                }
                if (files.isEmpty()) {
                    // 仅有压缩包直链的情况（合集包常走这里）
                    String pkgUrl = optString(s, "url");
                    if (StrUtil.isNotBlank(pkgUrl)) {
                        SubtitleCandidate arc = archiveCandidate(pkgUrl, release);
                        if (arc != null) {
                            scoreAndGate(arc, videoName, release, preferredLang, videoSeason, videoEp);
                            if (arc.getScore() >= 0) {
                                candidates.add(arc);
                            }
                        }
                    }
                    continue;
                }

                for (JsonObject f : files) {
                    String fName = optString(f, "f", "name", "filename");
                    String fUrl = optString(f, "url");
                    if (StrUtil.isBlank(fUrl) || StrUtil.isBlank(fName)) {
                        continue;
                    }
                    String ext = FileUtil.extName(fName).toLowerCase();
                    boolean archive = ARCHIVE_EXT.contains(ext);
                    if (!archive && !SUB_EXT.contains(ext)) {
                        continue;
                    }
                    SubtitleCandidate c = new SubtitleCandidate();
                    c.setUrl(fUrl);
                    c.setFileName(fName);
                    c.setExt(ext);
                    c.setArchive(archive);
                    c.setLang(detectLang(fName, langField, preferredLang));
                    scoreAndGate(c, videoName, release, preferredLang, videoSeason, videoEp);
                    if (c.getScore() >= 0) {
                        candidates.add(c);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("ASSRT 搜索失败 {}: {}", query, ExceptionUtils.getMessage(ex));
        }
        return candidates;
    }

    /**
     * 下载候选字幕内容。
     *
     * @param c             候选
     * @param preferredLang 偏好语言（用于从压缩包里挑文件）
     * @param targetSeason  目标季（可空）；合集包解包时用于按季过滤
     * @param targetEp      目标集（可空）；合集包解包时用于按集过滤
     * @param videoName     目标视频文件名（用于压缩包内按相似度挑选）
     * @return 字幕字节；无法获取返回 null
     */
    public byte[] download(SubtitleCandidate c, String preferredLang,
                           Integer targetSeason, Integer targetEp, String videoName) {
        try {
            byte[] data = fetchBytes(c.getUrl());
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
            if (c.isArchive()) {
                return extractBestFromZip(data, c.getFileName(), videoName, preferredLang, targetSeason, targetEp);
            }
            return data;
        } catch (Exception ex) {
            log.warn("ASSRT 字幕下载失败 {}: {}", c.getFileName(), ExceptionUtils.getMessage(ex));
            return null;
        }
    }

    /**
     * 评分并按集/季门槛过滤（返回 score<0 表示被门槛剔除）。
     * <p>
     * 压缩包（完整合集包）单个文件名无法对应目标集数，<b>不在候选阶段按集数剔除</b>，
     * 只做降级处理，真正按目标集数挑选交给 {@link #extractBestFromZip}。
     *
     * @param videoSeason 目标季（可空）
     * @param videoEp     目标集（可空）
     */
    private void scoreAndGate(SubtitleCandidate c, String videoName, String release, String preferredLang,
                              Integer videoSeason, Integer videoEp) {
        double score = 0;
        String lang = StrUtil.blankToDefault(c.getLang(), "");
        if (preferredLang != null && preferredLang.equalsIgnoreCase(lang)) {
            score += 100;
        } else if (lang.isEmpty()) {
            score += 10;
        } else {
            score -= 40;
        }
        score += similarity(videoName, c.getFileName()) * 60;
        if (StrUtil.isNotBlank(release)) {
            score += similarity(videoName, release) * 20;
        }
        if ("ass".equals(c.getExt()) || "ssa".equals(c.getExt())) {
            score += 8;
        }

        if (c.isArchive()) {
            // 合集包：候选阶段不按集数剔除，降级后留给解包阶段挑选
            score -= 25;
            c.setScore(score);
            return;
        }

        // 集数/季数门槛：双方都能解析且不一致才剔除（避免误伤）
        int[] ce = extractSeasonEpisode(c.getFileName() + " " + release);
        Integer cSeason = ce[0] >= 0 ? ce[0] : null;
        Integer cEp = ce[1] >= 0 ? ce[1] : null;
        if (videoEp != null && cEp != null && !videoEp.equals(cEp)) {
            c.setScore(-1);
            return;
        }
        if (videoSeason != null && cSeason != null && !videoSeason.equals(cSeason)) {
            c.setScore(-1);
            return;
        }
        c.setScore(score);
    }

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

    private List<JsonObject> fetchDetailFiles(String token, long id) {
        try {
            String url = DETAIL_API + "?token=" + token + "&id=" + id;
            throttle();
            String body;
            try (HttpResponse res = HttpReq.get(url).execute()) {
                HttpReq.assertStatus(res);
                body = res.body();
            }
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
                    wrap.addProperty("isArchive", true);
                    files.add(wrap);
                }
            }
            ThreadUtil.sleep(500);
            return files;
        } catch (Exception ex) {
            log.warn("ASSRT 详情获取失败 id={}: {}", id, ExceptionUtils.getMessage(ex));
            return List.of();
        }
    }

    private SubtitleCandidate archiveCandidate(String pkgUrl, String release) {
        String name = release;
        if (StrUtil.isBlank(name)) {
            int slash = pkgUrl.lastIndexOf('/');
            name = slash >= 0 ? pkgUrl.substring(slash + 1) : "package";
        }
        String ext = FileUtil.extName(name).toLowerCase();
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
        try {
            Integer cfg = ConfigUtil.CONFIG.getAssrtRateLimitPerMinute();
            if (cfg != null && cfg > 0) {
                rpm = cfg;
            }
        } catch (Exception ignored) {
            // 配置未就绪时回落默认
        }
        if (rpm > 120) {
            rpm = 120;
        }
        return 60_000L / rpm;
    }

    private byte[] fetchBytes(String url) {
        String safeUrl = url;
        if (safeUrl.startsWith("//")) {
            safeUrl = "https:" + safeUrl;
        }
        byte[] data;
        try (HttpResponse res = HttpReq.get(safeUrl).execute()) {
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
     */
    private byte[] extractBestFromZip(byte[] data, String archiveName, String videoName,
                                     String preferredLang, Integer targetSeason, Integer targetEp) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            byte[] best = null;
            double bestScore = -1;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                String ext = FileUtil.extName(name).toLowerCase();
                if (!SUB_EXT.contains(ext)) {
                    continue;
                }
                // 已知目标集数时按集/季过滤
                if (targetEp != null) {
                    int[] se = extractSeasonEpisode(name);
                    int s = se[0];
                    int e = se[1];
                    if (e < 0) {
                        continue; // 无法确认集数，跳过
                    }
                    if (e != targetEp) {
                        continue; // 集数不符，跳过
                    }
                    if (targetSeason != null && s >= 0 && s != targetSeason) {
                        continue; // 季数不符，跳过
                    }
                }
                byte[] content = zis.readAllBytes();
                double sc = similarity(videoName, name) * 50;
                if (preferredLang.equalsIgnoreCase(detectLang(name, "", preferredLang))) {
                    sc += 100;
                }
                if (sc > bestScore) {
                    bestScore = sc;
                    best = content;
                }
            }
            return best;
        } catch (Exception ex) {
            log.warn("ASSRT 压缩包解包失败 {}: {}", archiveName, ExceptionUtils.getMessage(ex));
            return null;
        }
    }

    private String detectLang(String fileName, String langField, String preferredLang) {
        String lower = StrUtil.blankToDefault(fileName, "").toLowerCase();
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
            String lf = langField.toLowerCase();
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
        for (String t : s.toLowerCase().split("[^\\w\\u4e00-\\u9fa5]+")) {
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
