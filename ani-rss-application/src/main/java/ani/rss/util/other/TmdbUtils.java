package ani.rss.util.other;

import ani.rss.commons.CacheUtils;
import ani.rss.commons.ExceptionUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.CustomTmdbConfig;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import wushuo.tmdb.api.TmdbUtil;
import wushuo.tmdb.api.entity.*;
import wushuo.tmdb.api.enums.TmdbTypeEnum;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * tmdb封装
 */
@Slf4j
public class TmdbUtils {
    public final static TmdbConfig config = new CustomTmdbConfig();
    public final static TmdbUtil TMDB_UTIL = new TmdbUtil(config);

    /**
     * 获取番剧在tmdb的名称
     *
     * @param ani 订阅
     * @return
     */
    public synchronized static String getFinalName(Ani ani) {
        Boolean ova = ani.getOva();
        String name = ani.getTitle();
        name = RenameUtil.renameDel(name, false);
        if (StrUtil.isBlank(name)) {
            return "";
        }

        Optional<Tmdb> tmdb;
        try {
            if (ova) {
                tmdb = getTmdbMovie(name);
                if (tmdb.isEmpty()) {
                    // 中文标题匹配不到时，用日文原名兜底（剧场版/OVA 常见）
                    tmdb = getTmdbByJpTitle(ani, TmdbTypeEnum.MOVIE);
                }
            } else {
                tmdb = getTmdbTv(name);
                if (tmdb.isEmpty()) {
                    // 普通番剧中文标题匹配不到时，同样用日文原名兜底
                    tmdb = getTmdbByJpTitle(ani, TmdbTypeEnum.TV);
                }
            }
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
            return "";
        }

        tmdb.ifPresent(ani::setTmdb);

        if (tmdb.isEmpty()) {
            return "";
        }
        return getFinalName(tmdb.get());
    }

    /**
     * 中文标题匹配不到时，用 BGM 日文原名（jpTitle）兜底搜索 TMDB
     *
     * @param ani      订阅
     * @param tmdbType 类型
     * @return
     */
    private static Optional<Tmdb> getTmdbByJpTitle(Ani ani, TmdbTypeEnum tmdbType) {
        String jpTitle = ani.getJpTitle();
        if (StrUtil.isBlank(jpTitle)) {
            return Optional.empty();
        }
        // 日文原名可能与中文标题相同（无日文名时 BGM 回退），避免重复无效搜索
        String title = RenameUtil.renameDel(ani.getTitle(), false);
        String jp = RenameUtil.renameDel(jpTitle, false);
        if (StrUtil.isBlank(jp) || StrUtil.equals(title, jp)) {
            return Optional.empty();
        }
        log.info("TMDB 中文标题匹配失败，尝试日文原名: {}", jp);
        try {
            if (tmdbType == TmdbTypeEnum.MOVIE) {
                return getTmdbMovie(jp);
            }
            return getTmdbTv(jp);
        } catch (Exception e) {
            log.error("TMDB 日文原名兜底搜索失败: {}", ExceptionUtils.getMessage(e));
            return Optional.empty();
        }
    }

    /**
     * 获取添加tmdbid与年份后的名称
     *
     * @param tmdb tmdb
     * @return 名称
     */
    public static String getFinalName(Tmdb tmdb) {
        boolean tmdbOriginalNameEnable = Boolean.TRUE.equals(ConfigUtil.CONFIG.getTmdbOriginalName());

        String themoviedbName = tmdbOriginalNameEnable ?
                tmdb.getOriginalName() :
                tmdb.getName();
        return getFinalName(themoviedbName, tmdb);
    }

    /**
     * 获取添加tmdbid与年份后的名称
     *
     * @param title 标题
     * @param tmdb  tmdb
     * @return
     */
    public static String getFinalName(String title, Tmdb tmdb) {
        if (Objects.isNull(tmdb)) {
            return title;
        }
        Config config = ConfigUtil.CONFIG;

        boolean titleYear = config.getTitleYear();
        if (titleYear) {
            title = RenameUtil.renameDel(title, false);
            title = StrFormatter.format("{} ({})", title, DateUtil.year(tmdb.getDate()));
        }

        boolean tmdbId = config.getTmdbId();
        boolean tmdbIdPlexMode = config.getTmdbIdPlexMode();
        if (tmdbId) {
            if (tmdbIdPlexMode) {
                title = StrFormatter.format("{} {tmdb-{}}", title, tmdb.getId());
            } else {
                title = StrFormatter.format("{} [tmdbid={}]", title, tmdb.getId());
            }
        }
        return RenameUtil.getName(title);
    }

    /**
     * 获取所有标题
     *
     * @param tmdb     tmdb
     * @param tmdbType 类型
     * @return
     */
    public static List<TmdbTitle> getTitles(Tmdb tmdb, TmdbTypeEnum tmdbType) {
        return TMDB_UTIL.getTitles(tmdb, tmdbType);
    }

    /**
     * 获取罗马音
     *
     * @param tmdb     tmdb
     * @param tmdbType 类型
     */
    public static void getRomaji(Tmdb tmdb, TmdbTypeEnum tmdbType) {
        if (Objects.isNull(tmdb)) {
            return;
        }

        Config config = ConfigUtil.CONFIG;
        Boolean tmdbRomaji = config.getTmdbRomaji();
        if (!tmdbRomaji) {
            // 未开启罗马音
            return;
        }

        List<TmdbTitle> titles = getTitles(tmdb, tmdbType);

        for (TmdbTitle tmdbTitle : titles) {
            String iso31661 = tmdbTitle.getIso31661();
            String type = tmdbTitle.getType();
            String title = tmdbTitle.getTitle();
            if (!iso31661.equals("JP")) {
                continue;
            }
            if (List.of("romaji", "romanization").contains(type.toLowerCase())) {
                title = RenameUtil.getName(title);
                // 判断为罗马音
                tmdb.setName(title);
                return;
            }
        }

        String romaji = "";
        try {
            romaji = AniListUtil.getRomaji(tmdb.getName());
            romaji = RenameUtil.getName(romaji);
        } catch (Exception e) {
            log.error("通过AniList获取罗马音失败");
            log.error(e.getMessage(), e);
        }
        if (StrUtil.isNotBlank(romaji)) {
            tmdb.setName(romaji);
        }
    }

    /**
     * 根据标题获得tmdb
     *
     * @param titleName 标题名
     * @return
     */
    public static Optional<Tmdb> getTmdbMovie(String titleName) {
        Optional<Tmdb> tmdb = getTmdb(titleName, TmdbTypeEnum.MOVIE);
        tmdb.ifPresent(it -> getRomaji(it, TmdbTypeEnum.MOVIE));
        return tmdb;
    }

    /**
     * 根据标题获得tmdb
     *
     * @param titleName 标题名
     * @return
     */
    public static Optional<Tmdb> getTmdbTv(String titleName) {
        Optional<Tmdb> tmdb = getTmdb(titleName, TmdbTypeEnum.TV);
        tmdb.ifPresent(it -> getRomaji(it, TmdbTypeEnum.TV));
        return tmdb;
    }

    /**
     * 根据名称获取tmdb信息
     *
     * @param titleName 标题名
     * @param tmdbType  类型
     * @return
     */
    public static Optional<Tmdb> getTmdb(String titleName, TmdbTypeEnum tmdbType) {
        Optional<Tmdb> tmdb = TMDB_UTIL.getTmdb(titleName, tmdbType);
        if (tmdb.isEmpty()) {
            return tmdb;
        }
        // 相关性校验：TMDB 库在无精确匹配时会取日期最新的任意结果，
        // 中文标题搜索失败时可能误匹配到完全无关的电影（如剧场版标题带"剧场版"
        // 关键字，匹配到另一部最新的剧场版）。名称与搜索词无任何字符交集时视为误匹配。
        if (isChineseSearch(titleName) && !isRelated(tmdb.get(), titleName)) {
            Tmdb t = tmdb.get();
            log.warn("TMDB 匹配结果与标题无关，已忽略: {} -> {} (id={})",
                    titleName, t.getName(), t.getId());
            return Optional.empty();
        }
        return tmdb;
    }

    /**
     * 判断搜索词是否为中文（含日文）标题：非纯 ASCII 即视为需要相关性校验
     *
     * @param titleName 标题名
     * @return
     */
    private static boolean isChineseSearch(String titleName) {
        if (StrUtil.isBlank(titleName)) {
            return false;
        }
        for (char c : titleName.toCharArray()) {
            if (c > 127) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验 TMDB 结果与搜索词是否相关（忽略大小写）
     * 判定：一方包含另一方（子串），或共同字符占比 ≥ 50%
     * 防止「剧场版 我心里危险的东西」误匹配「完美世界剧场版 九劫焚天」
     * （共同字符仅"剧场版"占比 27%，低于阈值被拒绝）
     *
     * @param tmdb      tmdb
     * @param titleName 搜索词
     * @return
     */
    private static boolean isRelated(Tmdb tmdb, String titleName) {
        String tmdbName = tmdb.getName();
        String originalName = tmdb.getOriginalName();
        return isTextRelated(titleName, tmdbName)
                || isTextRelated(titleName, originalName);
    }

    /**
     * 两个标题文本是否相关：子串包含，或共同字符占比 ≥ 50%
     *
     * @param a 文本a
     * @param b 文本b
     * @return
     */
    private static boolean isTextRelated(String a, String b) {
        if (StrUtil.isBlank(a) || StrUtil.isBlank(b)) {
            return false;
        }
        String la = a.toLowerCase(Locale.ROOT);
        String lb = b.toLowerCase(Locale.ROOT);
        // 子串包含：同系列 / 同标题（如「我心里危险的东西 第二季」vs「我心里危险的东西」）
        if (la.contains(lb) || lb.contains(la)) {
            return true;
        }
        // 共同字符占比
        String shorter = la.length() <= lb.length() ? la : lb;
        String longer = la.length() <= lb.length() ? lb : la;
        int overlap = 0;
        for (char c : shorter.toCharArray()) {
            if (longer.indexOf(c) >= 0) {
                overlap++;
            }
        }
        return (double) overlap / longer.length() >= 0.5;
    }

    /**
     * 获取季信息
     *
     * @param tmdb   tmdb
     * @param season 季
     * @return
     */
    public static Optional<TmdbSeason> getTmdbSeason(Tmdb tmdb, Integer season) {
        return TMDB_UTIL.getTmdbSeason(tmdb, season);
    }

    /**
     * 获取每集的标题
     *
     * @param ani 订阅
     * @return
     */
    public static synchronized Map<Integer, TmdbEpisode> getEpisodeTitleMap(Ani ani) {
        Map<Integer, TmdbEpisode> episodeTitleMap = new HashMap<>();

        if (Objects.isNull(ani)) {
            return episodeTitleMap;
        }

        Tmdb tmdb = ani.getTmdb();
        Integer season = ani.getSeason();
        Boolean ova = ani.getOva();

        if (ova) {
            return episodeTitleMap;
        }

        if (Objects.isNull(tmdb)) {
            return episodeTitleMap;
        }

        String tmdbId = tmdb.getId();
        String tmdbGroupId = tmdb.getTmdbGroupId();

        String key = StrFormatter.format("TMDB_getEpisodeTitleMap:{}:{}:{}", tmdbId, tmdbGroupId, season);

        Map<Integer, TmdbEpisode> cacheMap = CacheUtils.get(key);
        if (Objects.nonNull(cacheMap)) {
            return cacheMap;
        }

        episodeTitleMap = getEpisodeTitleMap(tmdb, season);
        if (episodeTitleMap.isEmpty()) {
            CacheUtils.put(key, episodeTitleMap, 1000 * 10);
        } else {
            CacheUtils.put(key, episodeTitleMap, TimeUnit.MINUTES.toMillis(5));
        }
        return episodeTitleMap;
    }

    /**
     * 获取每集的标题
     *
     * @param tmdb   tmdb
     * @param season 季
     * @return
     */
    public static Map<Integer, TmdbEpisode> getEpisodeTitleMap(Tmdb tmdb, Integer season) {
        return TMDB_UTIL.getEpisodeTitleMap(tmdb, season);
    }

    /**
     * 获取剧集组
     *
     * @param tmdb tmdb
     * @return
     */
    public static List<TmdbGroup> getTmdbGroup(Tmdb tmdb) {
        return TMDB_UTIL.getTmdbGroup(tmdb);
    }

    /**
     * 获取图片
     *
     * @param tmdb     tmdb
     * @param tmdbType 类型
     * @return
     */
    public static TmdbImages getTmdbImages(Tmdb tmdb, TmdbTypeEnum tmdbType) {
        return TMDB_UTIL.getTmdbImages(tmdb, tmdbType);
    }

    public static Optional<Tmdb> getTmdb(Tmdb tmdb, TmdbTypeEnum tmdbTypeEnum) {
        return TMDB_UTIL.getTmdb(tmdb, tmdbTypeEnum);
    }
}
