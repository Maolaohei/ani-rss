package ani.rss.download;

import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentsInfo;
import ani.rss.enums.TorrentsTags;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public interface BaseDownload {
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
            String s = FileUtil.extName(FileUtil.mainName(name));
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
        String ext = FileUtil.extName(name);
        if (StrUtil.isBlank(ext)) {
            return name;
        }

        // 从原始文件名尝试提取集数
        String originalEpisode = extractEpisodeFromFileName(name);

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
            String s = FileUtil.extName(FileUtil.mainName(name));
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
     * 从文件名中提取集数 (EP01, - 01, [01] 等格式), 过滤年份/日期
     */
    default String extractEpisodeFromFileName(String name) {
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
        return null;
    }

    /**
     * 过滤年份(1900-2100)与日期(yyMMdd / yyyyMMdd), 避免 [160226] 等被当集数
     */
    default String filterNonEpisodeNumber(String num) {
        if (num == null) return null;
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
}
