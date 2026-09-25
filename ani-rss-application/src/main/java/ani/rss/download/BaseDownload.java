package ani.rss.download;

import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentsInfo;
import ani.rss.enums.TorrentsTags;
import ani.rss.util.basic.RenameCacheUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public interface BaseDownload {
    /**
     * 是否为离线长等待型下载器（OpenList/Alist 等网盘离线工具）。
     * 离线型下载器的 download() 提交后需长时间等待离线完成，不应长期占用 RSS 主线程池。
     */
    default boolean isOffline() {
        return false;
    }

    /**
     * 登录
     *
     * @param config 设置
     * @return 登录状态
     */
    default Boolean login(Config config) {
        return login(false, config);
    }

    /**
     * 登录
     *
     * @param test   测试登录
     * @param config 设置
     * @return 登录状态
     */
    Boolean login(Boolean test, Config config);

    /**
     * 获取任务列表
     *
     * @return 任务列表
     */
    List<TorrentsInfo> getTorrentsInfos();

    /**
     * 下载
     *
     * @param ani         订阅
     * @param item        下载项
     * @param savePath    保存位置
     * @param torrentFile 种子文件
     * @return 下载状态
     */
    Boolean download(Ani ani, Item item, String savePath, File torrentFile);

    /**
     * 删除已完成任务
     *
     * @param torrentsInfo 任务
     * @param deleteFiles  删除本地文件
     * @return 删除状态
     */
    Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles);

    /**
     * 重命名
     *
     * @param torrentsInfo 任务
     */
    Boolean rename(TorrentsInfo torrentsInfo);

    /**
     * 为任务添加标签
     *
     * @param torrentsInfo 任务
     * @param tags         标签
     * @return 状态
     */
    Boolean addTags(TorrentsInfo torrentsInfo, String tags);

    /**
     * 自动更新 Trackers
     *
     * @param trackers trackers 列表
     */
    void updateTrackers(Set<String> trackers);

    /**
     * 修改保存位置
     *
     * @param torrentsInfo 任务
     * @param path         位置
     */
    void setSavePath(TorrentsInfo torrentsInfo, String path);

    /**
     * 获取重命名结果
     *
     * @param name   文件名
     * @param reName 重命名
     * @return 最终命名
     */
    default String getFileReName(String name, String reName) {
        String ext = FileUtil.extName(name);
        if (StrUtil.isBlank(ext)) {
            return name;
        }
        String newPath = reName;
        if (FileUtils.isVideoFormat(ext)) {
            newPath = newPath + "." + ext;
        } else if (FileUtils.isSubtitleFormat(ext)) {
            String s = extractSubtitleLangSuffix(name);
            if (StrUtil.isNotBlank(s)) {
                newPath = newPath + "." + s;
            }
            newPath = newPath + "." + ext;
        } else {
            return name;
        }

        if (name.equals(newPath)) {
            return name;
        }
        return newPath;
    }

    /**
     * 多文件合集重命名：从原始文件名提取集数，替换到重命名模板中
     *
     * @param name       原始文件名
     * @param reName     重命名模板 (含 SxxExx)
     * @param isSubtitle 是否为字幕文件
     * @return 最终命名
     */
    default String getFileReNameMulti(String name, String reName, boolean isSubtitle) {
        return getFileReNameMulti(name, reName, isSubtitle, 0);
    }

    /**
     * 多文件合集重命名（带来源偏移）。
     * <p>
     * 模板（种子名 = {@code item.reName}）是<b>目标口径</b>，而种子内文件是<b>源命名</b>：
     * RSS 配了集数偏移时两者相差一个偏移量（如源 E96 应落到 S04E24），
     * 必须先把文件名集数按来源偏移平移再替换进模板，否则产出 E96 的错误命名。
     * 偏移取 {@code item.rssOffset}（下载提交时按任务 id/hash 缓存，见各实现）。
     *
     * @param episodeOffset 文件名集数 → 最终集数的平移量（来源 RSS 的集数偏移），0 保持旧行为
     */
    default String getFileReNameMulti(String name, String reName, boolean isSubtitle, int episodeOffset) {
        String ext = FileUtil.extName(name);
        if (StrUtil.isBlank(ext)) {
            return name;
        }

        // 从原始文件名尝试提取集数（源命名 → 按来源偏移平移成目标口径）
        String originalEpisode = shiftEpisode(extractEpisodeFromFileName(name), episodeOffset);

        String newPath;
        if (originalEpisode != null) {
            // 支持 .E 和 E 两种模板格式
            if (reName.contains(".E")) {
                newPath = reName.replaceAll("\\.E\\d+(\\.5)?", ".E" + originalEpisode);
            } else if (reName.contains("E") && reName.matches(".*[Ss]\\d+.*E\\d+.*")) {
                newPath = reName.replaceAll("E\\d+(\\.5)?", "E" + originalEpisode);
            } else {
                newPath = reName;
            }
        } else {
            newPath = reName;
        }

        if (isSubtitle) {
            String s = extractSubtitleLangSuffix(name);
            if (StrUtil.isNotBlank(s)) {
                newPath = newPath + "." + s;
            }
            newPath = newPath + "." + ext;
        } else if (FileUtils.isVideoFormat(ext)) {
            newPath = newPath + "." + ext;
        } else {
            return name;
        }

        return name.equals(newPath) ? name : newPath;
    }

    /**
     * 从文件名中提取集数 (EP01, - 01, [01], 第01话, Vol.1, BD01, 裸数字 等格式), 过滤年份/日期/分辨率。
     * 与 RenameUtil.REG_LOOSE 的能力对齐，避免「RSS 标题算出 A 集、文件落成 B 集」的两套口径脱节。
     */
    default String extractEpisodeFromFileName(String name) {
        if (StrUtil.isBlank(name)) {
            return null;
        }
        String mainName = FileUtil.mainName(name);
        // 特典/菜单/CM/PV/OP/ED 等不参与集数提取
        String upper = mainName.toUpperCase();
        if (upper.contains("[MENU") || upper.contains("[CM]") || upper.contains("[PV")
                || upper.contains("NCOP") || upper.contains("NCED")
                || upper.contains("[SP") || upper.contains("[OP") || upper.contains("[ED")
                || upper.contains("AUDIO GUIDE") || upper.contains("/SPS/")) {
            return null;
        }
        // EP01, EP 01, e01
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:[Ee][Pp]?)\\s*(\\d+(?:\\.5)?)").matcher(mainName);
        if (m.find()) return filterNonEpisodeNumber(m.group(1));
        // - 01, -01 (排除日期 2015-05-30 中的 -05/-30)
        m = java.util.regex.Pattern.compile("(?<!\\d)-\\s*(\\d+(?:\\.5)?)").matcher(mainName);
        if (m.find()) return filterNonEpisodeNumber(m.group(1));
        // _710, _01 (排除 _1080p/_720x480 分辨率)
        m = java.util.regex.Pattern.compile("_(\\d+(?:\\.5)?)(?![PpXx\\d])").matcher(mainName);
        if (m.find()) return filterNonEpisodeNumber(m.group(1));
        // [01], 【01】, [710-711], [01-02] (排除 [160226] 日期、[20221208] 日期、[1080P] 分辨率)
        m = java.util.regex.Pattern.compile("[\\[【](\\d+(?:\\.5)?)(?:-\\d+)?[\\]】]").matcher(mainName);
        if (m.find()) return filterNonEpisodeNumber(m.group(1));
        // 第01话/第01話/第01集/第1-2话（取起始集）
        m = java.util.regex.Pattern.compile("第\\s*(\\d+(?:\\.5)?)[话話集]").matcher(mainName);
        if (m.find()) return filterNonEpisodeNumber(m.group(1));
        // Vol.1 / Vol 1 / vol1
        m = java.util.regex.Pattern.compile("(?:vol\\.?|Vol)\\s*(\\d{1,3}(?:\\.5)?)(?![\\dPpXx])",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(mainName);
        if (m.find()) return filterNonEpisodeNumber(m.group(1));
        // BD-01 / BD01 / BD 01
        m = java.util.regex.Pattern.compile("BD[- ]?(\\d{1,3}(?:\\.5)?)(?![\\dPpXx])",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(mainName);
        if (m.find()) return filterNonEpisodeNumber(m.group(1));
        // OVA2 / OAD 2
        m = java.util.regex.Pattern.compile("(?:OVA|OAD)[- ]?(\\d{1,3}(?:\\.5)?)(?![\\dPpXx])",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(mainName);
        if (m.find()) return filterNonEpisodeNumber(m.group(1));
        // 裸数字（边界约束）: "标题 05.mkv"、"01.zh.ass"、柯南 "1049" 等长番。
        // 排除: 紧邻字母(H264/x265/SP01)、后跟 P/p/X/x(分辨率)、日期/年份由 filterNonEpisodeNumber 兜底。
        // 特典词 + 空格 + 数字（"Character PV 01"、"Menu 01"、"Teaser 2" 等）不是集数：
        // VCB 整季包特典编号会被误判成集数并与真集数冲突（PV 01 → 覆盖正片第 1 集）
        m = java.util.regex.Pattern.compile("(?<![A-Za-z0-9])(?<!Season\\s)(\\d{1,4}(?:\\.5)?)(?![\\dPpXx])").matcher(mainName);
        while (m.find()) {
            // 命中的裸数字前面若紧邻特典词（允许空格/下划线分隔），视为特典编号，继续找下一个候选
            String before = mainName.substring(Math.max(0, m.start() - 20), m.start())
                    .toUpperCase(java.util.Locale.ROOT);
            if (before.matches(".*(?:PV|CM|MENU|NCOP|NCED|TEASER|ANNOUNCEMENT|SPECIAL|SP|OVA|OAD|OP|ED)[\\s_]+$")) {
                continue;
            }
            return filterNonEpisodeNumber(m.group(1));
        }
        return null;
    }

    /**
     * 判断一组文件是否构成「多集」。
     * <p>
     * 以「可识别出的<b>不同集数</b>」为准，而不是视频文件数量：单集种子常附带
     * NCOP/PV/菜单/预告等多个视频文件，按文件数判断会把<b>只有 1 集</b>的条目
     * 误当合集，进而走 {@link #getFileReNameMulti} 逐文件提取集数。
     * <p>
     * 只有在确认存在 2 个以上不同集数时才判为多集；提取不到集数、或只提取到
     * 1 个集数时按单集处理（与单文件路径一致，重名文件由调用方去重跳过）。
     *
     * @param fileNames 文件名列表（可含字幕等非视频文件，非视频名通常提取不到集数）
     */
    default boolean isMultiEpisode(List<String> fileNames) {
        if (CollectionUtil.isEmpty(fileNames)) {
            return false;
        }
        Set<Double> episodes = new HashSet<>();
        for (String name : fileNames) {
            String episode = extractEpisodeFromFileName(name);
            if (StrUtil.isBlank(episode)) {
                continue;
            }
            try {
                // 归一化为数值，避免 "01" 与 "1" 被当成两个不同集数
                episodes.add(Double.parseDouble(episode));
            } catch (NumberFormatException ignored) {
                // 非数字形态的集数标记不参与判定
            }
            if (episodes.size() > 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提取字幕语言后缀（可多级，如 xx.chs&eng.simplified.ass → "chs&eng.simplified"）。
     * 仅识别已知语言/轨道 token，避免把文件主名中的数字段当语言（如 Vol.1.ass → 1）。
     * <p>
     * 实现已上移到 {@link ani.rss.commons.FileUtils#extractSubtitleLangSuffix(String)}，
     * 与 ASSRT 字幕补全、合集预览共用同一套 token 与命名口径（含 jpsc / jptc，统一小写）。
     */
    default String extractSubtitleLangSuffix(String name) {
        return FileUtils.extractSubtitleLangSuffix(name);
    }

    /**
     * 是否为已知字幕语言/轨道 token
     */
    default boolean isKnownSubtitleLangToken(String token) {
        return FileUtils.isKnownSubtitleLangToken(token);
    }

    /**
     * 过滤年份(1900-2100)与日期(yyMMdd / yyyyMMdd), 避免 [160226] 等被当集数
     */
    default String filterNonEpisodeNumber(String num) {
        if (num == null) return null;
        // 常见分辨率（"1080 x265" 这类「数字+空格+编码」场景下的裸 1080/720 等）
        switch (num) {
            case "360": case "480": case "720":
            case "1080": case "1440": case "2160": case "4320":
                return null;
            default:
        }
        if (num.length() == 4) {
            try {
                int v = Integer.parseInt(num);
                if (v >= 1900 && v <= 2100) return null;
            } catch (NumberFormatException ignored) {
            }
        } else if (num.length() == 6 && num.matches("\\d{6}")) {
            return null; // yyMMdd
        } else if (num.length() == 8 && num.matches("\\d{8}")) {
            return null; // yyyyMMdd
        }
        return num;
    }

    /**
     * 获取新任务的tag
     *
     * @param ani
     * @param item
     * @return
     */
    default List<String> newTags(Ani ani, Item item) {
        Boolean master = item.getMaster();
        String subgroup = item.getSubgroup();
        subgroup = StrUtil.blankToDefault(subgroup, "未知字幕组");

        Config config = ConfigUtil.CONFIG;

        List<String> tags = new ArrayList<>();

        tags.add(TorrentsTags.ANI_RSS.getValue());
        tags.add(subgroup);
        if (!master) {
            tags.add(TorrentsTags.BACK_RSS.getValue());
        }

        Boolean customTagsEnable = ani.getCustomTagsEnable();

        if (customTagsEnable) {
            // 获取订阅自定义标签
            List<String> aniCustomTags = ani.getCustomTags();
            if (CollectionUtil.isNotEmpty(aniCustomTags)) {
                tags.addAll(aniCustomTags);
            }
            return tags;
        }

        // 获取全局自定义标签
        List<String> globalCustomTags = config.getCustomTags();
        if (CollectionUtil.isNotEmpty(globalCustomTags)) {
            tags.addAll(globalCustomTags);
        }

        return tags;
    }

    /**
     * {@link RenameCacheUtil} 里「任务 → 来源集数偏移」缓存键前缀。
     * 下载提交时由各实现写入（qB 按 infoHash、Aria2 按 gid），rename 时读回；
     * 值为整数字符串，缺失/不可解析按 0 处理（= 改造前行为）。
     */
    String RSS_OFFSET_CACHE_PREFIX = "rssOffset:";

    /**
     * 条目的来源集数偏移：主 RSS 用订阅偏移，备用 RSS 用该源自己的偏移；
     * 未携带（老数据 / 非 RSS 入口）回退 0。
     */
    static int rssOffsetOf(Item item) {
        Integer sourceOffset = item == null ? null : item.getRssOffset();
        return sourceOffset == null ? 0 : sourceOffset;
    }

    /**
     * 读取 rename 缓存里的来源偏移值，缺失/不可解析回退 0。
     */
    static int cachedRssOffset(String raw) {
        if (StrUtil.isBlank(raw)) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 文件名里的集数 → 最终集数：加上「来源 RSS 的集数偏移」。
     * <p>
     * 平移量必须来自<b>产生该条目的那条 RSS</b>，而不是订阅的 {@code ani.offset}：
     * 备用 RSS 各自带偏移时两者不同（实测 96 应归到 24，用订阅偏移则仍是 96）。
     * 偏移为 0 或集数不是数字时原样返回，行为与改造前一致。
     */
    static String shiftEpisode(String episode, int episodeOffset) {
        if (episodeOffset == 0 || StrUtil.isBlank(episode)) {
            return episode;
        }
        try {
            double shifted = Double.parseDouble(episode) + episodeOffset;
            return shifted == Math.floor(shifted)
                    ? String.valueOf((long) shifted)
                    : String.valueOf(shifted);
        } catch (NumberFormatException e) {
            return episode;
        }
    }
}
