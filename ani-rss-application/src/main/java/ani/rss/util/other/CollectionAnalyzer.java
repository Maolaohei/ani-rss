package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.CollectionSegment;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 合集结构分析：把一份种子的文件清单切成「第N季 / 剧场版 / OVA」若干段。
 * <p>
 * 只依据种子内路径文本推断，纯本地、不联网、不解析 TMDB 集数：
 * <ul>
 *   <li>按种子内第一层目录聚类（同一层目录 = 同一段的第一候选）；</li>
 *   <li>目录名里的 {@code S2 / Season 2 / 第2季 / 2nd Season} 定季；</li>
 *   <li>{@code 剧场版 / Gekijouban / The Movie / 总集篇} 定剧场版；</li>
 *   <li>{@code OVA / OAD / SP / 特典 / NCOP} 定特典；</li>
 *   <li>{@code Disc 1 / DVD 2 / Vol.3} 这类分卷目录合并回同一段，不拆成多段。</li>
 * </ul>
 * 识别结果<b>只是建议</b>：季/偏移/标题/归属都对用户可见可改，改完才会执行。
 * 字幕组编号里 S2 从 1 起还是从 25 起无法从路径确定，因此集数偏移默认 0，
 * 由用户按需调整（见 {@link CollectionSegment#getOffset()}）。
 */
public final class CollectionAnalyzer {

    /** 某某 第2季 / 第二季 之外的阿拉伯数字季 */
    private static final Pattern SEASON_CN = Pattern.compile("第\\s*(\\d{1,2})\\s*季");

    /** S2 / S02 / Season 2 / Season.2 */
    private static final Pattern SEASON_S = Pattern.compile(
            "(?i)(?:^|[\\s._\\[\\-])(?:s|season\\s*)(\\d{1,2})(?=[\\s._\\]\\-]|$)");

    /** 2nd Season / 3rd Season */
    private static final Pattern SEASON_ORDINAL = Pattern.compile(
            "(?i)(\\d{1,2})(?:st|nd|rd|th)\\s*season");

    /** 剧场版关键词（中/日/英/罗马字） */
    private static final Pattern MOVIE = Pattern.compile(
            "(?i)(剧场版|劇場版|gekijou?ban|gekijo|the\\s*movie|\\bmovie\\b|总集篇|總集篇|完结篇|完結篇)");

    /** 特典类关键词 */
    private static final Pattern SPECIAL = Pattern.compile(
            "(?i)(\\bova\\b|\\boad\\b|\\bsp\\b|\\bspecials?\\b|特典|ncop|nced|picture\\s*drama|迷你动画)");

    /** 分卷目录：Disc 1 / DVD 2 / Vol.3 / CD1 */
    private static final Pattern DISC = Pattern.compile("(?i)\\b(disc|disk|dvd|vol|cd)[\\s._-]*\\d+\\b");

    /** 文件名里的 SxxEyy，用于判断同一目录内是否混了多个季 */
    private static final Pattern SEASON_EPISODE = Pattern.compile("(?i)[\\s.\\[_-]s(\\d{1,2})e\\d{1,4}");

    /** 方括号标签：[VCB-Studio] [Ma10p_1080p] [x265_flac] */
    private static final Pattern BRACKET_TAG = Pattern.compile("\\[[^\\]]*]");

    /** 画质/编码/音轨等无意义尾缀 */
    private static final Pattern NOISE = Pattern.compile(
            "(?i)(\\b\\d{3,4}p\\b|\\b[48]k\\b|x26[45]|h\\.?26[45]|hevc|avc|av1|10bit|8bit|flac|aac|"
                    + "ma10p|ma10p_1080p|bdrip|web-?dl|bluray|remux|\\bchs\\b|\\bcht\\b|\\bjpn\\b)");

    private CollectionAnalyzer() {
    }

    /**
     * 分析结果：分段建议 + 给人看的提示
     */
    public record Analysis(List<CollectionSegment> segments, List<String> warnings) {
    }

    /**
     * 分析种子结构。
     *
     * @param torrentFile 已解析的种子
     * @param base        弹窗里填的基础订阅信息（提供标题/季数兜底）
     */
    public static Analysis analyze(TorrentMetadata torrentFile, Ani base) {
        List<String> warnings = new ArrayList<>();
        if (torrentFile == null || torrentFile.getFilenames() == null || torrentFile.getFilenames().length == 0) {
            return new Analysis(List.of(), warnings);
        }

        String baseTitle = base == null ? "" : StrUtil.nullToEmpty(base.getTitle());
        int baseSeason = base == null || base.getSeason() == null ? 1 : base.getSeason();

        // 根目录 → 文件路径；平铺种子（无目录）落 "" 键
        Map<String, List<String>> byRoot = new LinkedHashMap<>();
        String[] filenames = torrentFile.getFilenames();
        for (String filename : filenames) {
            String path = normalize(filename);
            if (StrUtil.isBlank(path) || !path.contains(".")) {
                continue;
            }
            String root = path.contains("/") ? path.substring(0, path.indexOf('/')) : "";
            byRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(path);
        }
        if (byRoot.isEmpty()) {
            return new Analysis(List.of(), warnings);
        }

        Map<String, CollectionSegment> merged = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : byRoot.entrySet()) {
            String root = entry.getKey();
            List<String> files = entry.getValue();
            if (StrUtil.isBlank(root)) {
                // 平铺种子：多季混在文件名里，无法靠目录拆分
                if (countSeasonMarkers(files) > 1) {
                    warnings.add("种子没有顶层目录，且文件名里出现多个季标记：无法按目录自动拆分，"
                            + "请把整份作为一段，或手动整理（已按单段处理）");
                }
                CollectionSegment seg = new CollectionSegment()
                        .setId("seg-1")
                        .setName(StrUtil.blankToDefault(baseTitle, "整份"))
                        .setKind("TV")
                        .setSeason(baseSeason)
                        .setOffset(0)
                        .setTitle(StrUtil.blankToDefault(baseTitle, "整份"))
                        .setOva(false)
                        .setScope("");
                merge(merged, seg);
                continue;
            }
            int seasonsInRoot = countSeasonsInFiles(files);
            if (seasonsInRoot > 1) {
                warnings.add("目录「" + root + "」内的文件名包含 " + seasonsInRoot
                        + " 个季标记（S01E01 / S02E01 这类），已按同一部分处理；"
                        + "如需分开命名请分别设置季或手动整理种子");
            }
            CollectionSegment seg = detect(root, baseTitle, baseSeason, warnings);
            merge(merged, seg);
        }

        List<CollectionSegment> segments = new ArrayList<>(merged.values());
        for (int i = 0; i < segments.size(); i++) {
            segments.get(i).setId("seg-" + (i + 1));
        }

        if (segments.size() <= 1) {
            return new Analysis(segments, warnings);
        }

        boolean hasMovie = segments.stream().anyMatch(s -> "MOVIE".equals(s.getKind()));
        warnings.add(StrFormatter.formatSafe("检测到 {} 个独立部分（{}），每个部分会生成一条订阅与一个目录",
                segments.size(), segments.stream().map(CollectionSegment::getName).toList()));
        if (hasMovie) {
            warnings.add("剧场版按电影式命名（不解析集数），落盘目录使用「剧场版下载位置」模板，"
                    + "如与剧集同库请确认模板不同");
        }
        long tvSeasons = segments.stream().filter(s -> "TV".equals(s.getKind())).count();
        if (tvSeasons > 1) {
            warnings.add("多季已分开命名；字幕组若按累计集数编号（第2季从 25 起），"
                    + "请把该段「集数偏移」设为 0 或按需调整，否则会与第一季撞集");
        }
        return new Analysis(segments, warnings);
    }

    /**
     * 单层目录 → 分段建议
     */
    private static CollectionSegment detect(String root, String baseTitle, int baseSeason, List<String> warnings) {
        String cleaned = clean(root);
        boolean movie = MOVIE.matcher(cleaned).find();
        Integer season = parseSeason(cleaned);
        boolean special = !movie && SPECIAL.matcher(cleaned).find();
        if (special && season == null) {
            // OVA/特典没有季标记时落 S00，避免与正片集数碰撞
            season = 0;
        }

        String kind;
        String name;
        String title;
        boolean ova;
        if (movie) {
            kind = "MOVIE";
            name = "剧场版";
            title = movieTitle(cleaned, baseTitle);
            season = season == null ? baseSeason : season;
            ova = true;
        } else if (special) {
            kind = "SPECIAL";
            name = "OVA/特典";
            title = StrUtil.blankToDefault(baseTitle, cleaned);
            ova = true;
        } else {
            kind = "TV";
            season = season == null ? baseSeason : season;
            name = "第" + season + "季";
            title = StrUtil.blankToDefault(baseTitle, cleaned);
            ova = false;
        }

        // 同一目录内既像正片又有剧场版关键词：提示需人工核对
        if (!movie && MOVIE.matcher(cleaned).find()) {
            warnings.add("目录「" + root + "」疑似同时包含正片与剧场版，已按正片处理，请核对归属");
        }

        return new CollectionSegment()
                .setName(name)
                .setKind(kind)
                .setSeason(season)
                .setOffset(0)
                .setTitle(title)
                .setOva(ova)
                .setScope(root);
    }

    /**
     * 合并分卷目录：Disc1/Disc2、Vol.1/Vol.2 等最终落到同一段
     */
    private static void merge(Map<String, CollectionSegment> merged, CollectionSegment seg) {
        String key = StrUtil.format("{}-{}-{}",
                StrUtil.nullToEmpty(seg.getKind()),
                seg.getSeason() == null ? "" : seg.getSeason(),
                StrUtil.nullToEmpty(seg.getTitle()).toLowerCase(Locale.ROOT));
        CollectionSegment exists = merged.get(key);
        if (exists == null) {
            merged.put(key, seg);
            return;
        }
        String scope = StrUtil.isBlank(exists.getScope())
                ? seg.getScope()
                : exists.getScope() + "\n" + StrUtil.nullToEmpty(seg.getScope());
        exists.setScope(scope);
    }

    /**
     * 目录名 → 季号；无季标记返回 null
     */
    private static Integer parseSeason(String cleaned) {
        Matcher cn = SEASON_CN.matcher(cleaned);
        if (cn.find()) {
            return Integer.parseInt(cn.group(1));
        }
        Matcher ordinal = SEASON_ORDINAL.matcher(cleaned);
        if (ordinal.find()) {
            return Integer.parseInt(ordinal.group(1));
        }
        // "S2" 需要防止把 "S01E03" 这类文件名误判成季（目录名一般不含 E）
        Matcher s = SEASON_S.matcher(cleaned);
        if (s.find()) {
            return Integer.parseInt(s.group(1));
        }
        return null;
    }

    private static int countSeasonMarkers(List<String> files) {
        // 两种写法都要认：S01E01（文件名常见）与 "Show S2"（目录常见）
        return Math.max(countSeasonsInFiles(files), (int) files.stream()
                .map(f -> {
                    Matcher m = SEASON_S.matcher(f);
                    return m.find() ? m.group(1) : null;
                })
                .filter(Objects::nonNull)
                .distinct()
                .count());
    }

    /**
     * 同一目录内文件名里的季标记数量（S01E01 / S02E01），用于提示「一目录混多季」
     */
    private static int countSeasonsInFiles(List<String> files) {
        return (int) files.stream()
                .map(f -> {
                    Matcher m = SEASON_EPISODE.matcher(f);
                    return m.find() ? m.group(1) : null;
                })
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    /**
     * 目录名清洗：去方括号标签、画质/编码、首尾分隔符
     */
    static String clean(String name) {
        if (StrUtil.isBlank(name)) {
            return "";
        }
        String s = name.replace('_', ' ');
        s = BRACKET_TAG.matcher(s).replaceAll(" ");
        s = NOISE.matcher(s).replaceAll(" ");
        // 分卷目录（Disc1 / Vol.2）先剥掉，否则 Disc1/Disc2 会被当成两段不同的合集
        s = DISC.matcher(s).replaceAll(" ");
        s = s.replaceAll("[\\[\\](){}]", " ");
        s = s.replaceAll("\\s{2,}", " ").trim();
        return s.replaceAll("^[\\s.\\-]+|[\\s.\\-]+$", "").trim();
    }

    /**
     * 剧场版标题：优先用目录名（去关键词），否则回退基础标题
     */
    private static String movieTitle(String cleaned, String baseTitle) {
        String title = MOVIE.matcher(cleaned).replaceAll(" ").replaceAll("\\s{2,}", " ").trim();
        if (StrUtil.isBlank(title)) {
            title = StrUtil.blankToDefault(baseTitle, "剧场版");
        }
        return title;
    }

    /**
     * 种子内路径归一化：解码 + 反斜杠转正斜杠 + 去目录尾斜杠
     */
    static String normalize(String filename) {
        String path = CharsetUtil.convert(filename, "ISO-8859-1", CharsetUtil.UTF_8);
        path = path.replace("\\", "/");
        return path.replaceAll("/+$", "");
    }

    /**
     * 极简格式化（避免为此引入 hutool 的 StrFormatter 依赖差异）
     */
    private static final class StrFormatter {
        static String formatSafe(String template, Object... args) {
            String s = template;
            for (Object arg : args) {
                s = s.replaceFirst("\\{}", Matcher.quoteReplacement(String.valueOf(arg)));
            }
            return s;
        }
    }

    /**
     * 是否有可拆分的多个部分（供 controller 判断默认 mode）
     */
    public static boolean isMixed(List<CollectionSegment> segments) {
        if (CollUtil.size(segments) <= 1) {
            return false;
        }
        return segments.stream().map(CollectionSegment::getKind).distinct().count() > 1
                || segments.stream().map(CollectionSegment::getSeason).distinct().count() > 1;
    }
}
