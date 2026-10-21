package ani.rss.download.openlist.policy;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.entity.OpenListFileInfo;
import ani.rss.enums.StringEnum;
import ani.rss.util.other.RenameUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * OpenList / 115 云下载的纯判定策略。
 * <p>
 * 本类不允许访问网络、Spring、线程池或静态可变状态。路径、集数、标题和临时目录
 * 这类规则集中在这里，调用方只负责提供数据并执行动作。
 */
public final class OpenListPolicy {
    private OpenListPolicy() {
    }

    /**
     * 去掉路径尾部斜杠，用于路径相等比较（兼容 115/OpenList 返回的目录路径格式差异）。
     */
    public static String trimTrailingSlash(String path) {
        if (StrUtil.isBlank(path)) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 归一化 OpenList 虚拟路径：统一斜杠、去掉盘符、合并重复分隔符。
     */
    public static String normalizePath(String savePath) {
        if (StrUtil.isBlank(savePath)) {
            return "";
        }
        String path = savePath.replace('\\', '/');
        path = ReUtil.replaceAll(path, "^[A-z]:", "");
        while (path.contains("//")) {
            path = path.replace("//", "/");
        }
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * 文件是否位于 prefix 目录下（含其自身路径）。
     */
    public static boolean isUnderPath(OpenListFileInfo file, String prefix) {
        if (file == null || StrUtil.isBlank(prefix)) {
            return false;
        }
        String path = StrUtil.blankToDefault(file.getPath(), "").replace('\\', '/');
        String name = StrUtil.blankToDefault(file.getName(), "").replace('\\', '/');
        String full = path.endsWith("/" + name) || path.equals(name)
                ? path
                : (path.isEmpty() ? name : path + "/" + name);
        String normalizedPrefix = trimTrailingSlash(prefix);
        return full.equalsIgnoreCase(normalizedPrefix)
                || full.toLowerCase(Locale.ROOT)
                .startsWith(normalizedPrefix.toLowerCase(Locale.ROOT) + "/");
    }

    /**
     * 文件相对 base 的一级子目录名（文件直接在 base 下时返回 null）。
     */
    public static String firstPathSegmentUnder(OpenListFileInfo file, String base) {
        if (file == null || StrUtil.isBlank(base)) {
            return null;
        }
        String path = StrUtil.blankToDefault(file.getPath(), "").replace('\\', '/');
        String normalizedBase = trimTrailingSlash(base);
        if (StrUtil.isBlank(path) || path.equalsIgnoreCase(normalizedBase)) {
            return null;
        }
        String lowerPath = path.toLowerCase(Locale.ROOT);
        String lowerBase = normalizedBase.toLowerCase(Locale.ROOT);
        if (!lowerPath.startsWith(lowerBase + "/")) {
            return null;
        }
        String relative = path.substring(normalizedBase.length() + 1);
        int slash = relative.indexOf('/');
        return slash < 0 ? relative : relative.substring(0, slash);
    }

    /**
     * 洗版目标判定：提取出的 SxxExx 必须与本次整体相等，且排除当前任务目录。
     */
    public static boolean isWashTarget(String name, String seasonKey, String currentDirName) {
        if (name == null || StrUtil.isBlank(seasonKey) || name.equals(currentDirName)) {
            return false;
        }
        String found = ReUtil.get(StringEnum.SEASON_REG, name, 0);
        return found != null && found.equalsIgnoreCase(seasonKey);
    }

    /**
     * 解析 115 云下载兜底目录。配置优先，否则在根目录里找名称含“云下载”的目录。
     */
    public static String pickCloudDir(String configured, List<String> rootNames) {
        if (StrUtil.isNotBlank(configured)) {
            return configured.replace('\\', '/');
        }
        if (rootNames == null) {
            return null;
        }
        return rootNames.stream()
                .filter(name -> name != null && name.contains("云下载"))
                .findFirst()
                .map(name -> "/" + name.replace('\\', '/'))
                .orElse(null);
    }

    /**
     * 从 reName/标题提取期望季数（SxxExx 的 xx）；无季信息返回 null。
     */
    public static Integer expectedSeasonOf(String reName) {
        if (StrUtil.isBlank(reName)) {
            return null;
        }
        String season = ReUtil.get(StringEnum.SEASON_REG, reName, 1);
        if (StrUtil.isBlank(season)) {
            return null;
        }
        try {
            return Integer.valueOf(season);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 文件名自带 SxxExx 且季数与期望不符 → 视为其它媒体的文件。
     */
    public static boolean mismatchedSeason(String fileName, Integer expectedSeason) {
        if (expectedSeason == null || StrUtil.isBlank(fileName)) {
            return false;
        }
        String season = ReUtil.get(StringEnum.SEASON_REG, fileName, 1);
        if (StrUtil.isBlank(season)) {
            return false;
        }
        try {
            return Integer.parseInt(season) != expectedSeason;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /**
     * 文件名是否属于指定集：包含完整 reName 或同一 SxxExx。
     */
    public static boolean isEpisodeFileName(String fileName, String reName) {
        if (StrUtil.isBlank(fileName) || StrUtil.isBlank(reName)) {
            return false;
        }
        if (fileName.equalsIgnoreCase(reName)
                || fileName.toLowerCase(Locale.ROOT).contains(reName.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String season = ReUtil.get(StringEnum.SEASON_REG, reName, 0);
        return StrUtil.isNotBlank(season)
                && fileName.toUpperCase(Locale.ROOT).contains(season.toUpperCase(Locale.ROOT));
    }

    /**
     * 本次离线下载使用的临时目录名。
     * <p>
     * 非合集 = 最终命名；合集 = 源标题的首段。归位对账依赖这个名字，
     * 因此提交与归位必须共用本实现。
     */
    public static String resolveTempDirName(Item item) {
        if (item == null) {
            return null;
        }
        String finalRenameBase = item.getReName();
        boolean collection = item.getEpisodeRange() != null && !item.getEpisodeRange().isEmpty();
        if (!collection) {
            return finalRenameBase;
        }
        String collectionName = StrUtil.blankToDefault(item.getTitle(), finalRenameBase);
        collectionName = collectionName.replace("\\", "/");
        if (collectionName.contains("/")) {
            collectionName = collectionName.substring(0, collectionName.indexOf("/"));
        }
        collectionName = RenameUtil.getName(collectionName);
        return StrUtil.isNotBlank(collectionName) ? collectionName : finalRenameBase;
    }

    /**
     * 云下载共享目录的标题候选词：订阅标题、TMDB 名、日文名与字幕组原始标题。
     */
    public static List<String> titleTokensOf(Ani ani, Item item) {
        List<String> rawTitles = new ArrayList<>();
        if (ani != null) {
            rawTitles.add(ani.getTitle());
            rawTitles.add(ani.getThemoviedbName());
            rawTitles.add(ani.getJpTitle());
        }
        if (item != null && StrUtil.isNotBlank(item.getTitle())) {
            String raw = item.getTitle().replaceAll("[\\[【][^\\]】]*[\\]】]", " ").trim();
            String mainName = FileUtil.mainName(raw).trim();
            if (StrUtil.isNotBlank(mainName)) {
                rawTitles.add(mainName);
            }
        }
        List<String> tokens = new ArrayList<>();
        for (String rawTitle : rawTitles) {
            if (StrUtil.isBlank(rawTitle)) {
                continue;
            }
            String cleaned = RenameUtil.getName(RenameUtil.renameDel(rawTitle)).trim();
            if (cleaned.length() >= 2) {
                tokens.add(cleaned.toUpperCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    /**
     * 云下载共享目录守卫：文件名不含本订阅任一标题别名 → 视为其它任务的文件。
     */
    public static boolean lacksTitleToken(String fileName, List<String> titleTokens) {
        if (titleTokens == null || titleTokens.isEmpty() || StrUtil.isBlank(fileName)) {
            return false;
        }
        String upper = fileName.toUpperCase(Locale.ROOT);
        return titleTokens.stream().noneMatch(upper::contains);
    }

    /**
     * 云下载守卫扩展判定：文件名不含标题时，目录链含标题别名也可放行。
     */
    public static boolean cloudEntryLacksTitleToken(String fileName, String cloudEntryPath,
                                                    String cloudRoot, List<String> titleTokens) {
        if (!lacksTitleToken(fileName, titleTokens)) {
            return false;
        }
        String relative = relativeUnderCloudRoot(cloudEntryPath, cloudRoot);
        if (StrUtil.isBlank(relative)) {
            return true;
        }
        String upperRelative = relative.toUpperCase(Locale.ROOT);
        return titleTokens.stream().noneMatch(upperRelative::contains);
    }

    /**
     * path 相对 cloudRoot 的目录链；无法判定或不在根下时返回 null。
     */
    public static String relativeUnderCloudRoot(String path, String cloudRoot) {
        if (StrUtil.isBlank(path) || StrUtil.isBlank(cloudRoot)) {
            return null;
        }
        String normalizedPath = trimTrailingSlash(path).toLowerCase(Locale.ROOT);
        String normalizedRoot = trimTrailingSlash(cloudRoot).toLowerCase(Locale.ROOT);
        if (!normalizedPath.startsWith(normalizedRoot + "/")) {
            return null;
        }
        return normalizedPath.substring(normalizedRoot.length() + 1);
    }

}
