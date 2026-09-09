package ani.rss.util.other;

import ani.rss.commons.NumberFormatUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.BgmInfo;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.enums.StringEnum;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.lang.func.Func1;
import cn.hutool.core.lang.func.LambdaUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import wushuo.tmdb.api.entity.Tmdb;
import wushuo.tmdb.api.entity.TmdbEpisode;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class RenameUtil {
    public static final String REG_STR = "(.*|\\[.*])(( - |Vol |[Ee][Pp]?)\\d+(\\.5)?( ?\\(\\d+\\))?|【\\d+(\\.5)?】|\\[\\d+(\\.5)?( ?\\(\\d+\\))?( ?[vV]\\d)?( ?END)?( ?完)?( ?FIN)?]|（\\d+(\\.5)?）|第\\d+(\\.5)?[话話集回]( - END)?|^\\[TOC].* \\d+|^六四位元字幕组.*★\\d+(\\.5)?)";

    // 宽松正则：覆盖老番不规范命名
    public static final String REG_LOOSE =
            "(Vol\\.?\\s*\\d{1,3}(?!\\d)(?:\\.5)?)" +
            "|([Ee][Pp]?\\s*\\d{1,3}(?!\\d)(?:\\.5)?)" +
            "|((?:^|[\\s\\u4e00-\\u9fff【】()（）\\[])(?:SP|OVA|OAD|OVD)\\s*[xX×]?\\s*\\d{1,3}(?!\\d)(?:\\.5)?(?:[vV]\\d{1,2})?(?![A-Za-z]))" +
            "|(\\s[-~～]\\s*\\d{1,3}(?!\\d)(?:\\.5)?)" +
            "|(【\\d+(?:\\.5)?】|\\[\\d+(?:\\.5)?(?:\\s*v\\d)?]|（\\d+(?:\\.5)?）)" +
            "|(第\\d+(?:\\.5)?[话話集])" +
            "|(BD[- ]?\\d{1,3}(?!\\d))" +
            "|(#\\d{1,3}(?!\\d))" +
            "|(_\\d{1,3}(?!\\d)(?:\\.5)?)" +
            "|(★\\d+(?:\\.5)?★)" +
            "|(\\s\\d{2}(?:\\.5)?(?:\\s|\\]|\\[|$))" +
            // 游戏王等裸数字格式: " 151 720P" / " 151 END" / " 139.5 720P" / " 084 V2"(版本号变体先于数字)
            "|(\\s\\d{1,3}(?:\\.5)?(?=\\s*(?:[vV]\\d{1,2}\\b)?\\s*(?:720|1080|2160)[Pp]|\\s*(?:[vV]\\d{1,2}\\b\\s*)?END\\b|\\s*v\\d))" +
            // OAD 数字: [OAD2] [OAD02] [OAD1&2] (【】全角括号由标记类分支处理)
            "|(\\[OAD\\d+(?:&\\d+)?\\])" +
            // 哆啦A梦修复版编号: [467-B] / [490B] (后随 ] 防止 10bit 误匹配)
            "|(\\[\\d{1,3}-[A-Z]\\]|\\b\\d{1,3}[Bb](?=\\]))" +
            // 下划线+版本号集数: " 038_V2 "
            "|(\\s\\d{1,3}_V[12](?=\\s))" +
            // 中文量词"回": 第12回 (允许紧贴 CJK 与"回"量词)
            "|(第\\d{1,3}(?:\\.5)?回)" +
            // Episode 前缀: Episode.0 / episode 01
            "|(episode[.\\s]?\\d{1,3}(?!\\d))" +
            // 分部 P1/P2 (柯南 - P1 等拆分包)
            "|((?<![\\p{L}\\p{N}])P[1-9](?![0-9]))" +
            // Erai-raws 双语标题: "ました03|暴怒千金" / "04|最强"
            "|((?<!\\d)\\d{1,3}(?:\\.5)?\\|)" +
            // 冒号集数: "。44:CLOUDY BEACH"
            "|((?<!\\d)\\d{1,3}:)" +
            // 柯南等超长番裸数字格式: [名侦探柯南 1049 目标毛利小五郎] / 1047&1048
            "|((?<=[\\u4e00-\\u9fff])\\s\\d{3,4}(?:&\\d{3,4})?(?=\\s[\\u4e00-\\u9fff]))";;

    // 集数范围: 01-06, 01~06, 01～06
    public static final String EP_RANGE_REG = "(\\d+(?:\\.5)?)[\\s]*[-~～][\\s]*(\\d+(?:\\.5)?)";

    // Vol范围: Vol.01-Vol.06
    public static final String VOL_RANGE_REG = "(?:Vol\\.?\\s*)(\\d+(?:\\.5)?)[\\s]*[-~～][\\s]*(\\d+(?:\\.5)?)";

    // 中文范围: 第1-6话, 第1～12集
    public static final String CN_RANGE_REG = "第(\\d+)[~～-](\\d+)[话話集]";

    // 分割部分: 上篇/中篇/下篇/Part 1/P1/上/下/Part II/第四部 等
    // 裸 上/下 需边界，避免「下载」「上场」等误识别；上篇/中篇/下篇 等更长词仍优先
    public static final String PART_REG = "(上篇|中篇|下篇|前篇|後篇|前编|后编|前編|後編|第一部|第二部|第三部|第[一二三四五六七八九十]+部|Part\\s?[1-9](?![0-9])|Part\\s?[IVX]+(?![IVX])|(?<![\\p{L}\\p{N}])P[1-9](?![0-9])|(?<![\\p{L}\\p{N}])(?:上|下)(?![\\p{L}\\p{N}篇部编編]))";

    // 分数格式: (1/2), (2/3)
    public static final String FRACTION_REG = "\\((\\d+)/(\\d+)\\)";

    // 年份排除: - 2024, - 10周年
    public static final String YEAR_LIKE_REG = "\\s[-]\\s*\\d{4}(?:\\s|$|\\])";

    // 合集标题: [01-12 合集], [01-02], 01～24 精校合集 等
    public static final Pattern COLLECTION_TITLE_REG = Pattern.compile("\\d+\\s*[-~～]\\s*\\d+");

    // 语义合集标记: 合集/全集/正片+SP/TV+OVA 混合包/整季+特典/特别篇合集 等
    // 命中即视为合集种子(episode 置 1 占位, 无范围数字, 不参与 expandMultiEpisode 展开)
    public static final Pattern SEMANTIC_COLLECTION_REG = Pattern.compile(
            "(合集|全集|特典映像|全\\d+话(?:\\+|含)?|TV动画|TV動畫|TV\\s*\\+\\s*OVA|TV\\s*\\+\\s*\\d*SP|正片\\s*\\+|\\+\\s*SP\\b|SP\\s*全特典|\\+\\s*OAD|\\+\\s*\\d*OVA|OADs|年度\\[|(?i:serie\\s+completa)|(?i:completa\\b)|(?i:completo\\b)|(?i:road\\s*(?:[.\\s]+)?to\\b))");

    // 无编号剧场版/特别放映/总集篇单集: 无集数标记, 落 S00E01(特典季)
    public static final Pattern THEATER_SPECIAL_REG = Pattern.compile(
            "(剧场版|劇場版|Gekijouban|特别放映|特別放映|总集篇|総集篇|剧场总集|无限城篇|無限城篇|Fan Letter|周年纪念|周年紀念|纪念特别|纪念篇)");

    // 无编号特典/单集标记: 纯 SP/[SP]/Special/特别篇/OVA/TVSP(无数字)。
    // OVA/OAD 需排除合集语境(篇/全集/集/复数s/混合+)
    public static final Pattern UNNUMBERED_SPECIAL_REG = Pattern.compile(
            "(?i)(^|[^A-Za-z0-9])(SP|Special|特别篇|特別篇|特别版|特別版|特别演出|特別演出|TVSP|OVA|OAD)(?!s|篇|全集|集|\\+|\\d)([^A-Za-z0-9]|$)");

    // 无编号特典变体: 年份/话数+SP 或 前缀SP ([2024SP] [774SP]) —— 编号是播报期数非集数, S00E01
    // 注意: SP+明确集数形态(SP01 / SP x2 / SP02.5)由 NUMBERED_SPECIAL 更早拦截, 落 S00 + 保留集数
    public static final Pattern SPECIAL_TAG_REG = Pattern.compile(
            "(?i)(\\[\\d{4}SP\\]|\\[\\d{1,4}SP\\]|\\[SP\\d{0,4}\\]|\\[\\d{4}\\s*(?:特別|特别)?SP\\]|\\[SP\\](?=\\s|\\]|$))");

    // 编号特典(SP01 / OVA02 / OAD3.5 / OVD1 / SP x2 / OVA01v3):
    // 是视频, 保留编号为集数, 落 S00 特典季 —— 不与正片集数碰撞, 多个特典互不冲突
    public static final Pattern NUMBERED_SPECIAL_REG = Pattern.compile(
            "(?i)(?:^|[^A-Za-z0-9])(?:SP|OVA|OAD|OVD)\\s*[xX×]?\\s*(\\d{1,3}(?:\\.5)?)(?:[vV]\\d{1,2})?(?![A-Za-z0-9])");

    // 无版权 OP/ED 单曲(NCOP/NCED/NCOPED, 含编号): 非正片内容, 不下载 —— rename 返回 false 丢弃
    public static final Pattern NC_OP_ED_REG = Pattern.compile(
            "(?i)(?:^|[^A-Za-z0-9])NC(?:OP|ED|OPED)(?:\\s*[xX×]?\\s*\\d{1,3}(?:\\.5)?)?(?:[vV]\\d{1,2})?(?![A-Za-z0-9])");

    // 哆啦A梦等无编号单集: [播出日期][标题] 形态, 日期不是集数。
    // 要求日期后紧跟非数字文本(排除 [2010.12.17][1080P] 纯规格标签)
    public static final Pattern DATE_EPISODE_REG = Pattern.compile(
            "\\[\\d{4}\\.\\d{2}\\.\\d{2}\\]\\s*\\[?\\s*[^\\]0-9\\s][^\\]]*[\\u4e00-\\u9fff]");

    // 版本号: v2, v3, V2 等（位于集数之后）; 限 1-2 位, 避免 v2024 等长数字被当版本号
    public static final String VERSION_REG = "[vV](\\d{1,2})(?:[^\\d]|$)";

    /**
     * 提取版本号，默认 v1
     */
    public static int extractVersion(String title) {
        Matcher m = Pattern.compile(VERSION_REG).matcher(title);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return 1;
    }

    /**
     * 判断是否使用新命名逻辑
     */
    public static boolean isNamingV2(Ani ani) {
        Integer v = ani.getNamingVersion();
        return v != null && v == 2;
    }

    /**
     * 是否为剧场版(电影式命名)。
     * ova=true 且 mediaType=movie 时视为剧场版; mediaType 为空(旧数据)时默认按剧场版处理。
     * OVA(mediaType=ova)保持特典式命名。
     */
    public static boolean isMovie(Ani ani) {
        if (ani == null || !Boolean.TRUE.equals(ani.getOva())) {
            return false;
        }
        String mediaType = ani.getMediaType();
        if (StrUtil.isBlank(mediaType)) {
            // 兼容旧数据: 无法区分来源时默认按剧场版(电影式)处理
            return true;
        }
        return "movie".equalsIgnoreCase(mediaType);
    }

    /**
     * 提取集数范围，返回展开后的集数列表
     * @return [start, start+1, ..., end] 或 null（非范围）
     */
    public static List<Double> extractEpisodeRange(String title) {
        // 中文范围: 第1-6话
        Matcher cnM = Pattern.compile(CN_RANGE_REG).matcher(title);
        if (cnM.find()) {
            return expandRange(cnM.group(1), cnM.group(2));
        }

        // Vol范围: Vol.01-Vol.06 → 去掉 Vol 前缀后匹配
        String volStripped = title.replaceAll("(?i)Vol\\.?\\s*", "");
        Matcher volM = Pattern.compile(EP_RANGE_REG).matcher(volStripped);
        if (volM.find()) {
            String vStart = volM.group(1);
            String vEnd = volM.group(2);
            if ((vStart.length() >= 4 && !vStart.contains(".")) || (vEnd.length() >= 4 && !vEnd.contains("."))) {
                // 年份排除
            } else {
                int vRangeStart = volM.start();
                int vScanPos = vRangeStart - 1;
                while (vScanPos >= 0 && Character.isDigit(volStripped.charAt(vScanPos))) {
                    vScanPos--;
                }
                if (vScanPos < 0 || Character.toUpperCase(volStripped.charAt(vScanPos)) != 'S') {
                    return expandRange(vStart, vEnd);
                }
            }
        }

        // 普通范围: 01-06, 01~06 (排除分数 1/2 和年份 2024)
        Matcher rangeM = Pattern.compile(EP_RANGE_REG).matcher(title);
        if (rangeM.find()) {
            String start = rangeM.group(1);
            String end = rangeM.group(2);
            // 排除年份: 4位数不作为范围
            if (start.length() >= 4 || end.length() >= 4) {
                return null;
            }
            // 排除季度号: S04 - 12 不应被视为范围 4-12
            // 向前扫描跳过数字位，检查是否存在 S 前缀 (如 S04、S4)
            int rangeStart = rangeM.start();
            int scanPos = rangeStart - 1;
            while (scanPos >= 0 && Character.isDigit(title.charAt(scanPos))) {
                scanPos--;
            }
            if (scanPos >= 0 && Character.toUpperCase(title.charAt(scanPos)) == 'S') {
                return null;
            }
            return expandRange(start, end);
        }

        return null;
    }

    /**
     * 提取集数列表: 01,02,03 或 01&02
     */
    public static List<Double> extractEpisodeList(String title) {
        List<Double> episodes = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+(?:\\.5)?").matcher(title);
        int lastEnd = -1;
        while (m.find()) {
            int start = m.start();
            if (lastEnd >= 0) {
                String between = title.substring(lastEnd, start).trim();
                if (!between.equals(",") && !between.equals("&") && !between.equals("，")) {
                    break;
                }
            }
            episodes.add(Double.parseDouble(m.group()));
            lastEnd = m.end();
        }
        return episodes.size() > 1 ? episodes : null;
    }

    /**
     * 提取分割部分对应的集数
     * @return 集数，0 表示未识别
     */
    public static int extractPartEpisode(String title) {
        Matcher m = Pattern.compile(PART_REG).matcher(title);
        if (!m.find()) {
            // 检查分数格式 (1/2)
            Matcher fm = Pattern.compile(FRACTION_REG).matcher(title);
            if (fm.find()) {
                return Integer.parseInt(fm.group(1));
            }
            return 0;
        }
        return mapPartToEpisode(m.group(1));
    }

    /**
     * 部分名 -> 集数映射
     */
    public static int mapPartToEpisode(String part) {
        return switch (part) {
            case "上篇", "前篇", "前编", "前編", "第一部", "Part 1", "P1", "Part1", "上" -> 1;
            case "中篇", "下篇", "後篇", "后编", "後編", "第二部", "Part 2", "P2", "Part2", "下" -> 2;
            case "第三部", "Part 3", "P3", "Part3" -> 3;
            default -> parsePartNumber(part);
        };
    }

    /**
     * 解析 Part 4-9 / P4 / Part4 / Part II / 第四部 等
     */
    private static int parsePartNumber(String part) {
        Matcher m = Pattern.compile("(?i)(?:Part|P)\\s*?([1-9])(?![0-9])").matcher(part);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        Matcher rm = Pattern.compile("(?i)(?:Part|P)\\s*([IVX]+)").matcher(part);
        if (rm.find()) {
            return romanToInt(rm.group(1).toUpperCase());
        }
        Matcher cn = Pattern.compile("第([一二三四五六七八九十]+)部").matcher(part);
        if (cn.find()) {
            return chineseToInt(cn.group(1));
        }
        return 0;
    }

    private static int romanToInt(String s) {
        int total = 0;
        int prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                default -> 0;
            };
            total += v < prev ? -v : v;
            prev = v;
        }
        return total;
    }

    private static int chineseToInt(String s) {
        if ("十".equals(s)) {
            return 10;
        }
        int idx = s.indexOf('十');
        if (idx >= 0) {
            int tens = idx == 0 ? 1 : cnDigit(s.charAt(idx - 1));
            int ones = idx == s.length() - 1 ? 0 : cnDigit(s.charAt(idx + 1));
            return tens * 10 + ones;
        }
        return cnDigit(s.charAt(0));
    }

    private static int cnDigit(char c) {
        return switch (c) {
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> 0;
        };
    }

    /**
     * 从 RSS 标题括号中提取番剧名: 跳过常见标签(分辨率/合集/字幕等), 取最长的中文字段
     */
    private static String extractTitleFromBrackets(String itemTitle) {
        Matcher m = Pattern.compile("(?:\\[|【)([^\\]】]+?)(?:\\]|】)").matcher(itemTitle);
        String best = null;
        while (m.find()) {
            String g = m.group(1).trim();
            if (g.matches("(?i).*(1080|720|2160|4k|合集|字幕|END|Fin|BDRip|WEB).*")) {
                continue;
            }
            if (best == null || g.length() > best.length()) {
                best = g;
            }
        }
        return best;
    }

    /**
     * 展开范围: "01" -> "06" => [1,2,3,4,5,6]
     */
    private static List<Double> expandRange(String startStr, String endStr) {
        double start = Double.parseDouble(startStr);
        double end = Double.parseDouble(endStr);
        if (start > end || end - start > 100) {
            return null;
        }
        List<Double> list = new ArrayList<>();
        if (start == (int) start && end == (int) end) {
            for (int i = (int) start; i <= (int) end; i++) {
                list.add((double) i);
            }
        } else {
            for (double ep = start; ep <= end; ep += 0.5) {
                list.add(ep);
                if (ep == (int) ep && ep < end) {
                    list.add(ep + 0.5);
                }
            }
        }
        return list;
    }

    public static Boolean rename(Ani ani, Item item) {
        Config config = ConfigUtil.CONFIG;
        boolean v2 = isNamingV2(ani);

        int offset = ani.getOffset();
        int season = ani.getSeason();
        String title = ani.getTitle();
        Boolean ova = ani.getOva();

        if (ova && !v2) {
            // 旧逻辑：OVA 不解析集数
            title = renameDel(title);
            item.setReName(title);
            return true;
        }

        String itemTitle = item.getTitle();
        itemTitle = itemTitle.replace("+NCOPED", "").trim();
        itemTitle = itemTitle.replace("\n", " ").trim();
        itemTitle = itemTitle.replace("\t", " ").trim();
        itemTitle = itemTitle.replaceAll("\\[([A-Z]|\\d){8}]$", "").trim();

        // 编号特典: [00&SP] / [SP&00] 等 —— 标题自带集数身份, 落 S00 特典季避免与正片集数碰撞
        if (!ova && v2 && ReUtil.contains("\\[\\s*00\\s*&\\s*SP\\s*\\]|\\[\\s*SP\\s*&\\s*00\\s*\\]", itemTitle)) {
            season = 0;
        }

        // OVA v2: 区分剧场版(电影式)与 OVA(特典式)
        int moviePart = 0;
        if (ova && v2) {
            if (isMovie(ani)) {
                // 剧场版: 电影式命名(Emby Movies 库), 不按剧集解析
                title = renameDel(title);
                // 防御：标题为空时从 RSS 标题中提取番剧名（支持中英文括号）
                if (StrUtil.isBlank(title)) {
                    String bracket = extractTitleFromBrackets(itemTitle);
                    if (StrUtil.isNotBlank(bracket)) {
                        title = bracket;
                    }
                }
                // 多部(上/下/Part N): 解析部序号作为 episode(去重与 Part 后缀用)
                moviePart = extractPartEpisode(itemTitle);
                item.setEpisode(moviePart > 0 ? (double) moviePart + offset : 1.0);
            } else {
                // OVA: 特典式命名, 尝试解析集数, 失败则保持 episode=1.0
                String e = tryExtractEpisode(itemTitle, ani);
                if (StrUtil.isNotBlank(e)) {
                    String episodeStr = ReUtil.get("\\d+(\\.5)?", e, 0);
                    if (StrUtil.isNotBlank(episodeStr)) {
                        double episode = Double.parseDouble(episodeStr) + offset;
                        item.setEpisode(episode);
                    }
                }
                // OVA 使用 S00 命名
                season = 0;
                title = renameDel(title);
                // 防御：标题为空时从 RSS 标题中提取番剧名（支持中英文括号）
                if (StrUtil.isBlank(title)) {
                    String bracket = extractTitleFromBrackets(itemTitle);
                    if (StrUtil.isNotBlank(bracket)) {
                        title = bracket;
                    }
                }
            }
        }

        Boolean customEpisode = ani.getCustomEpisode();
        String customEpisodeStr = ani.getCustomEpisodeStr();
        Integer customEpisodeGroupIndex = ani.getCustomEpisodeGroupIndex();
        String renameTemplate = getRenameTemplate(ani);

        String subgroup = item.getSubgroup();
        subgroup = StrUtil.blankToDefault(subgroup, "未知字幕组");

        String e;
        if (ova && v2) {
            // OVA v2 已在上面解析过，这里跳过
            e = null;
        } else if (Boolean.TRUE.equals(customEpisode)) {
            e = ReUtil.get(customEpisodeStr, itemTitle, customEpisodeGroupIndex);
        } else {
            e = ReUtil.get(REG_STR, itemTitle, 2);
            // 排除年份和周年等非集数上下文
            if (StrUtil.isNotBlank(e)) {
                e = filterFalsePositive(e, itemTitle);
            }
            // v2: REG_STR 失败时尝试宽松正则
            if (v2 && StrUtil.isBlank(e)) {
                e = tryExtractEpisode(itemTitle, ani);
                // 编号特典由宽松正则命中(如 "OVA 2 [1080p]"): 编号是特典序非正片集数, 落 S00
                if (StrUtil.isNotBlank(e) && NUMBERED_SPECIAL_REG.matcher(itemTitle).find()) {
                    season = 0;
                }
            }
        }

        if (!ova && StrUtil.isBlank(e)) {
            // 无版权 OP/ED 单曲(NCOP/NCED/NCOPED, 含编号): 非正片内容, 不下载
            // 注: "+NCOPED" 已在上方被剥除, 剩余标题若无集数则按合集/特典正常归类 —— PV+NCOPED 类实为 PV, 亦应丢弃
            if (v2 && NC_OP_ED_REG.matcher(itemTitle).find()) {
                return false;
            }
        }

        if (!ova && StrUtil.isBlank(e)) {
            // v2: 合集种子不依赖单集 episode 提取，交给 expandMultiEpisode 展开
            if (v2 && COLLECTION_TITLE_REG.matcher(itemTitle).find()) {
                e = "1";
            } else if (v2 && SEMANTIC_COLLECTION_REG.matcher(itemTitle).find()) {
                // 语义合集(无范围数字): 全74话+3OVA / 正片+SP / TV+OVA混合包 / 剧场总集篇 等
                // episode 置 1 占位; reName 稳定, 去重按 infoHash
                e = "1";
            } else if (v2 && NUMBERED_SPECIAL_REG.matcher(itemTitle).find()) {
                // 编号特典(SP01 / OVA02 / OAD3.5 / NCOP1 / SP x2 / OVA01v3):
                // 是视频, 保留编号为集数, 落 S00 特典季 —— 不与正片集数碰撞, 多个特典互不冲突
                Matcher spm = NUMBERED_SPECIAL_REG.matcher(itemTitle);
                spm.find();
                e = spm.group(1);
                season = 0;
            } else if (v2 && UNNUMBERED_SPECIAL_REG.matcher(itemTitle).find()) {
                // 无编号特典单集: 纯 SP / Special / 特别篇(不含数字)
                // 映射为 S00E01(特典季), 同标题新条目由下载入口 infoHash 去重, 不落盘重名
                e = "1";
                season = 0;
            } else if (v2 && SPECIAL_TAG_REG.matcher(itemTitle).find()) {
                // 编号特典变体: [2024SP] [774SP] —— 编号是播报期数非集数, 同样按特典处理
                e = "1";
                season = 0;
            } else if (v2 && DATE_EPISODE_REG.matcher(itemTitle).find()
                    && Pattern.compile("\\[\\d{4}\\.\\d{2}\\.\\d{2}\\]\\s*\\[?\\s*[^\\]0-9\\s][^\\]]+\\]").matcher(itemTitle).find()) {
                // 无编号单集: [2010.12.17][哆啦美小剧场！圣诞特别演出] 形态, 日期非集数
                e = "1";
                season = 0;
            } else if (isSeasonPack(itemTitle)) {
                // VCBD 等整季 BDRip 压制包: 无单集集数, [S1 Fin]/[S2-S4 + OADs]/[Reseed Fin] 标记
                // 季号取自标题, episode 置 1(合集由下载端按文件结构处理)
                Matcher sm = Pattern.compile("\\[(S)\\s*\\.?\\s*(\\d+)(?:-S?\\d+)?[^\\]]*\\]").matcher(itemTitle);
                if (sm.find()) {
                    try {
                        season = Integer.parseInt(sm.group(2));
                    } catch (NumberFormatException ignored) {
                    }
                }
                e = "1";
            } else if (v2 && THEATER_SPECIAL_REG.matcher(itemTitle).find()) {
                // 无编号剧场版/总集篇/特别放映(混入 tv 组): 落 S00 特典季, 不丢弃
                e = "1";
                season = 0;
            } else {
                return false;
            }
        }

        if (!ova) {
            String episodeStr = ReUtil.get("\\d+(\\.5)?", e, 0);
            if (StrUtil.isBlank(episodeStr)) {
                return false;
            }

            double episode = Double.parseDouble(episodeStr) + offset;
            item.setEpisode(episode);
        }

        double episode = item.getEpisode();
        String seasonFormat = String.format("%02d", season);
        String episodeFormat = String.format("%02d", (int) episode);

        String episodeStr = String.valueOf((int) episode);

        boolean is5 = ItemsUtil.is5(episode);

        boolean skip5 = config.getSkip5();
        if (skip5 && is5) {
            return false;
        }

        if (is5) {
            episodeFormat = episodeFormat + ".5";
            episodeStr = episodeStr + ".5";
        }

        itemTitle = getName(itemTitle);

        String resolution = getResolution(itemTitle);
        String tmdbId = Optional.ofNullable(ani.getTmdb())
                .map(Tmdb::getId)
                .filter(StrUtil::isNotBlank)
                .orElse("");

        renameTemplate = renameTemplate.replace("${seasonFormat}", seasonFormat);
        renameTemplate = renameTemplate.replace("${episodeFormat}", episodeFormat);
        renameTemplate = renameTemplate.replace("${season}", String.valueOf(season));
        renameTemplate = renameTemplate.replace("${episode}", episodeStr);
        renameTemplate = renameTemplate.replace("${subgroup}", subgroup);
        renameTemplate = renameTemplate.replace("${itemTitle}", itemTitle);
        renameTemplate = renameTemplate.replace("${resolution}", resolution);
        renameTemplate = renameTemplate.replace("${tmdbid}", tmdbId);
        renameTemplate = renameTemplate.replace("${title}", title);

        // 剧场版多部: Part N 序号(仅电影式命名使用; 单部时 ${part} 替换为空)
        if (isMovie(ani)) {
            if (renameTemplate.contains("${part}")) {
                renameTemplate = renameTemplate.replace("${part}",
                        moviePart > 0 ? "Part " + moviePart : "");
            } else if (moviePart > 0) {
                // 紧跟标题插入 Part N(而非追加在模板末尾)
                int idx = StrUtil.isNotBlank(title) ? renameTemplate.indexOf(title) : -1;
                if (idx >= 0) {
                    renameTemplate = renameTemplate.substring(0, idx + title.length())
                            + " Part " + moviePart
                            + renameTemplate.substring(idx + title.length());
                } else {
                    renameTemplate = renameTemplate + " Part " + moviePart;
                }
            }
        } else {
            // 非剧场版模板中的 ${part} 占位符无意义, 替换为空避免字面残留
            renameTemplate = renameTemplate.replace("${part}", "");
        }

        renameTemplate = replaceEpisodeTitle(renameTemplate, episode, ani);

        String bgmId = BgmUtil.getSubjectId(ani);
        renameTemplate = renameTemplate.replace("${bgmId}", bgmId);

        if (renameTemplate.contains("${jpTitle}")) {
            String jpTitle = getJpTitle(ani);
            renameTemplate = renameTemplate.replace("${jpTitle}", jpTitle);
        }

        List<Func1<Ani, Object>> list = List.of(
                Ani::getThemoviedbName
        );

        renameTemplate = replaceField(renameTemplate, ani, list);

        // 兜底：TMDB 刮削失败时 ${themoviedbName} 为空或未替换，用订阅标题顶替
        if (renameTemplate.contains("${themoviedbName}")) {
            renameTemplate = renameTemplate.replace("${themoviedbName}", title);
        }
        // 仅当标题完全为空（只剩 SxxExx）时才补回标题
        if (StrUtil.isNotBlank(title) && renameTemplate.trim().matches("S\\d+E\\d+.*")) {
            renameTemplate = title + " " + renameTemplate.trim();
        }

        renameTemplate = renameDel(renameTemplate);

        // 年份占位符(在 renameDel 之后替换, 避免"剔除年份"把补回的年份一并删掉)
        if (renameTemplate.contains("${year}")) {
            Date releaseDate = ani.getReleaseDate();
            if (releaseDate != null) {
                String year = String.valueOf(releaseDate.getYear() + 1900);
                renameTemplate = renameTemplate.replace("${year}", year);
            } else {
                // releaseDate 为空: 连占位符外层的空括号一起移除
                renameTemplate = renameTemplate.replace("(${year})", "")
                        .replace("${year}", "");
            }
        }

        String reName = getName(renameTemplate);

        Integer maxFileNameLength = config.getMaxFileNameLength();

        if (maxFileNameLength > 0) {
            reName = StrUtil.sub(reName, 0, maxFileNameLength);
        }

        // 提取版本号用于洗版判断
        int version = extractVersion(item.getTitle());
        item.setVersion(version);

        item
                .setReName(reName);
        return true;
    }

    /**
     * 尝试从标题中提取集数（先 REG_STR，再 REG_LOOSE）
     */
    private static String tryExtractEpisode(String itemTitle, Ani ani) {
        Boolean customEpisode = ani.getCustomEpisode();
        String customEpisodeStr = ani.getCustomEpisodeStr();
        Integer customEpisodeGroupIndex = ani.getCustomEpisodeGroupIndex();

        // null 保护: 老 JSON 数据可能缺该字段
        if (Boolean.TRUE.equals(customEpisode)) {
            return ReUtil.get(customEpisodeStr, itemTitle, customEpisodeGroupIndex);
        }

        String e = ReUtil.get(REG_STR, itemTitle, 2);
        if (StrUtil.isNotBlank(e)) {
            // 排除年份/日期: - 2024, [1996], 20221208 等
            if (isYearOrDate(extractEpisodeNumber(e))) {
                e = null;
            }
            // 排除 周年/年/bit 等非集数上下文
            if (e != null && StrUtil.isNotBlank(e)) {
                String after = itemTitle.substring(itemTitle.indexOf(e) + e.length());
                if (after.matches("^\\s*周.*") || after.matches("^\\s*年.*")
                        || after.matches("^\\s*bit.*") || after.matches("^\\s*Bpp.*")
                        || after.matches("^\\s*KB.*") || after.matches("^\\s*MB.*")) {
                    e = null;
                }
            }
        }

        if (StrUtil.isNotBlank(e)) {
            return e;
        }

        // 宽松正则 fallback: 扫描全部候选, 跳过年份/日期伪匹配(如 "[1992] Name_OVA" 应落到 _OVA)
        Matcher m = Pattern.compile(REG_LOOSE, Pattern.CASE_INSENSITIVE).matcher(itemTitle);
        while (m.find()) {
            String loose = m.group();
            // 排除年份/日期: [1996]、[2015]、[20221208] 等标签不应被当集数
            if (isYearOrDate(extractEpisodeNumber(loose))) {
                continue;
            }
            return loose;
        }
        return null;
    }

    /**
     * 判断是否为整季 BDRip/DVDRip 压制包(VCBD 等压制组):
     * 标题含 [S1 Fin]/[Reseed Fin]/[S2-S4 + OADs]/[LIVE] 季/完结标记且无单集集数。
     * 不强制 BDRip 质量标记(部分种子不带)。
     */
    private static boolean isSeasonPack(String itemTitle) {
        return itemTitle.matches(".*\\[[^\\]]*(S\\d{1,2}|Fin|Reseed|LIVE)[^\\]]*\\].*")
                // 无方括号整季标记: "... with You S01v2 [CR WEB-DL]"
                || itemTitle.matches(".*\\b[Ss]\\d{1,2}v\\d\\b.*")
                // 裸季号: "S01 (BD Remux" / "S01.Dual.Audio.2160p.BluRay"
                // 要求季号后紧跟 ( 或 . , 且全串含 BD/Remux/分辨率等质量词, 防止 139.5 等被 S 匹配
                || itemTitle.matches("(?si).*\\b[Ss]\\d{1,2}\\b\\s*[(.].*(bd|bluray|remux|1080|720|2160|dual|flac|hevc|h264|x264|x265).*")
                // 无集数 + 独立蓝光/网络源质量词: 整季/剧场包(单集标题几乎必带集数, 走不到这里)
                || itemTitle.matches("(?si).*[^a-z0-9](bd|bdrip|bluray|remux|web-dl|webrip|uhd-bd)[^a-z0-9].*")
                // 剧场/特别篇常见质量词(35mm/胶片修复): "[35mm 1080p Restore H264]"
                || itemTitle.contains("35mm");
    }

    /**
     * 判断数字是否为年份(1900-2100)或日期(yyyyMMdd)
     */
    private static boolean isYearOrDate(String num) {
        if (num == null || !num.matches("\\d+")) {
            // "10.5" 等非纯数字不是年份(长度恰好 4 会让 parseInt 炸掉)
            return false;
        }
        if (num.length() == 4) {
            int v = Integer.parseInt(num);
            return v >= 1900 && v <= 2100;
        }
        if (num.length() == 8) {
            return num.matches("(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])");
        }
        return false;
    }

    private static String extractEpisodeNumber(String episodePart) {
        if (episodePart == null) return null;
        Matcher m = Pattern.compile("\\d+(?:\\.5)?").matcher(episodePart);
        return m.find() ? m.group() : null;
    }

    /**
     * 过滤误识别: 年份、周年等非集数上下文
     */
    private static String filterFalsePositive(String episodePart, String fullTitle) {
        String num = extractEpisodeNumber(episodePart);
        if (num == null) return episodePart;

        // 排除4位数年份 (1900-2100); 非纯数字(如 "10.5")跳过
        if (num.matches("\\d+")) {
            if (num.length() == 4) {
                int val = Integer.parseInt(num);
                if (val >= 1900 && val <= 2100) return null;
            }
        }

        // 排除 周年/年 等上下文
        int idx = fullTitle.indexOf(episodePart);
        if (idx >= 0) {
            String after = fullTitle.substring(idx + episodePart.length());
            if (after.matches("^\\s*周.*") || after.matches("^\\s*年.*")) return null;
        }

        return episodePart;
    }

    /**
     * 获取重命名模板
     *
     * @param ani
     * @return
     */
    public static String getRenameTemplate(Ani ani) {
        Config config = ConfigUtil.CONFIG;
        String renameTemplate = config.getRenameTemplate();

        // 剧场版(电影式命名): 使用独立电影模板, 未配置时使用内置电影式默认
        // OVA(特典式)继续使用普通模板, 由 rename() 固定 season=0 产生 S00Exx
        if (isMovie(ani)) {
            String movieTemplate = config.getOvaRenameTemplate();
            renameTemplate = StrUtil.isNotBlank(movieTemplate)
                    ? movieTemplate
                    : "${title} (${year}) [${subgroup}]";
        }

        Boolean customRenameTemplateEnable = ani.getCustomRenameTemplateEnable();
        String customRenameTemplate = ani.getCustomRenameTemplate();

        // null 保护: 老 JSON 数据可能缺该字段
        if (Boolean.TRUE.equals(customRenameTemplateEnable)) {
            renameTemplate = customRenameTemplate;
        }

        if (StrUtil.isBlank(renameTemplate)) {
            renameTemplate = "${title} S${seasonFormat}E${episodeFormat}";
        }
        return renameTemplate;
    }

    public static <T> String replaceField(String template, T object, List<Func1<T, Object>> list) {
        if (Objects.isNull(object)) {
            return template;
        }
        for (Func1<T, Object> func1 : list) {
            try {
                String fieldName = LambdaUtil.getFieldName(func1);
                String s = StrFormatter.format("${{}}", fieldName);
                String v = func1.callWithRuntimeException(object).toString();
                // 路径安全: title/themoviedbName 中的 / 与 \ 替换为全角，避免多层目录/转义
                if ("title".equals(fieldName) || "themoviedbName".equals(fieldName)) {
                    v = sanitizePathSegment(v);
                }
                template = template.replace(s, v);
            } catch (Exception ignored) {
            }
        }
        return template;
    }

    /**
     * 替换集标题
     *
     * @param template 模板
     * @param episode  集数
     * @param ani      订阅
     * @return 替换结果
     */
    public static String replaceEpisodeTitle(String template, Double episode, Ani ani) {
        boolean is5 = ItemsUtil.is5(episode);

        Map<Integer, TmdbEpisode> episodeTitleMap = new HashMap<>();
        Map<Integer, Function<Boolean, String>> bgmEpisodeTitleMap = new HashMap<>();

        if (template.contains("${episodeTitle}")) {
            episodeTitleMap = TmdbUtils.getEpisodeTitleMap(ani);
        }

        if (template.contains("${bgmEpisodeTitle}") || template.contains("${bgmJpEpisodeTitle}")) {
            bgmEpisodeTitleMap = BgmUtil.getEpisodeTitleMap(ani);
        }

        String defaultEpisodeTitle = "第" + NumberFormatUtils.format(episode, 1, 0) + "集";

        String episodeTitle = defaultEpisodeTitle,
                bgmEpisodeTitle = defaultEpisodeTitle,
                bgmJpEpisodeTitle = defaultEpisodeTitle;

        if (!is5) {
            int episodeInt = episode.intValue();
            if (episodeTitleMap.containsKey(episodeInt)) {
                String name = episodeTitleMap.get(episodeInt).getName();
                if (StrUtil.isNotBlank(name)) {
                    episodeTitle = name;
                }
            }

            if (bgmEpisodeTitleMap.containsKey(episodeInt)) {
                bgmEpisodeTitle = bgmEpisodeTitleMap.get(episodeInt).apply(false);
                bgmJpEpisodeTitle = bgmEpisodeTitleMap.get(episodeInt).apply(true);
            }
        }

        template = template.replace("${episodeTitle}", episodeTitle);
        template = template.replace("${bgmEpisodeTitle}", bgmEpisodeTitle);
        template = template.replace("${bgmJpEpisodeTitle}", bgmJpEpisodeTitle);

        return template;
    }


    /**
     * 获取bgm日语标题
     *
     * @param ani 订阅
     * @return 日语标题
     */
    public static String getJpTitle(Ani ani) {
        return Opt.ofNullable(ani)
                .map(Ani::getJpTitle)
                .filter(StrUtil::isNotBlank)
                .orElseGet(() -> {
                    BgmInfo bgmInfo = BgmUtil.getBgmInfo(ani, true);
                    String name = bgmInfo.getName();
                    ani.setJpTitle(name);
                    return name;
                });
    }

    /**
     * 获取分辨率
     *
     * @param itemTitle 标题
     * @return 分辨率
     */
    private static String getResolution(String itemTitle) {
        Map<String, String> stringStringMap = Map.of(
                "1920x1080", "1080p",
                "3840x2160", "2160p",
                "1280x720", "720p"
        );
        for (String s : stringStringMap.keySet()) {
            itemTitle = itemTitle.replace(s, stringStringMap.get(s));
        }

        String resolutionReg = "(720|1080|2160)[Pp]";
        String resolution = "none";
        if (ReUtil.contains(resolutionReg, itemTitle)) {
            resolution = ReUtil.get(resolutionReg, itemTitle, 0).toLowerCase();
        }
        return resolution;
    }

    public static String getName(String s) {
        if (StrUtil.isBlank(s)) {
            return "";
        }

        s = s.replace("1/2", "½");

        Map<String, String> map = Map.of(
                "/", " ",
                "\\", " ",
                ":", "：",
                "?", "？",
                "|", "｜",
                "*", " ",
                "<", " ",
                ">", " ",
                "\"", " "
        );

        for (Map.Entry<String, String> entry : map.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            s = s.replace(key, value);
        }
        while (s.contains("  ")) {
            s = s.replace("  ", " ");
        }
        s = sanitizePathSegment(s);
        // Windows 不允许文件名以点/空格结尾
        s = s.replaceAll("[. ]+$", "");
        return s.trim();
    }

    /**
     * 路径段清洗：防目录穿越（..）、处理 Windows 保留名。
     * 用于标题等拼入文件/目录名的字段。
     */
    public static String sanitizePathSegment(String s) {
        if (StrUtil.isBlank(s)) {
            return "";
        }
        // 目录穿越载荷：.. 替换为全角点
        s = s.replace("..", "．");
        // Windows 保留设备名（CON/PRN/AUX/NUL/COM1-9/LPT1-9），加前缀避免建目录/文件失败
        String mainName = s.contains(".") ? s.substring(0, s.lastIndexOf('.')) : s;
        if (mainName.matches("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])$")) {
            s = "_" + s;
        }
        return s;
    }

    /**
     * 重命名剔除tmdbid与年份
     *
     * @param title
     * @return
     */
    public static String renameDel(String title) {
        return renameDel(title, true);
    }

    /**
     * 重命名剔除tmdbid与年份
     *
     * @param title
     * @param isConfig 遵守设置
     * @return
     */
    public static String renameDel(String title, Boolean isConfig) {
        if (StrUtil.isBlank(title)) {
            return "";
        }

        if (!isConfig) {
            title = ReUtil.replaceAll(title, StringEnum.TMDB_ID_REG, "")
                    .trim();
            title = ReUtil.replaceAll(title, StringEnum.YEAR_REG, "")
                    .trim();
            return title;
        }

        Config config = ConfigUtil.CONFIG;
        Boolean renameDelYear = config.getRenameDelYear();
        Boolean renameDelTmdbId = config.getRenameDelTmdbId();

        if (renameDelTmdbId) {
            title = ReUtil.replaceAll(title, StringEnum.TMDB_ID_REG, "")
                    .trim();
        }

        if (renameDelYear) {
            title = ReUtil.replaceAll(title, StringEnum.YEAR_REG, "")
                    .trim();
        }
        return title;
    }

}
