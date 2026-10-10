package ani.rss.util.other;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.reflect.TypeToken;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「每集已落地版本」持久化 —— 洗版判定的状态源。
 *
 * <h2>为什么必须落盘</h2>
 * 洗版判定原先只看 RSS 标题里的版本号（{@code item.version > 1} 就"准备洗版"）。
 * 这个条件是<b>无状态</b>的，而重命名后的文件名里不含版本号 ——
 * 盘上那份到底是 v1 还是 v2，除了这份记录之外没有任何地方能回答。
 * 于是同一集每轮 RSS 扫描都会被重新判定为"待洗版"，
 * 反复走「下载 → 改名 → 删旧文件 → 再下载」——线上曾观测到
 * 「女友的朋友 S01E01 v2」约每 35 分钟洗一轮，多订阅同时中招。
 *
 * <h2>语义</h2>
 * 记的是"下载器<b>受理</b>时的版本"（受理即记账，见 {@code DownloadService#download}）：
 * <ul>
 *   <li>判定是单调比较：只有 {@code item.version > 已落地版本} 才洗版；</li>
 *   <li>同一版本只洗一次，且跨进程重启依然有效（这正是无状态判定缺的那一半）；</li>
 *   <li>记录缺失（新装/升级）时按 {@code 0} 处理：会洗一次，随后即收敛，不会循环。</li>
 * </ul>
 * 为什么记账点是"受理"而不是"完成"：受理是唯一在所有下载器、所有入口
 * （RSS 主流程 / 失败重试 / 强制下载）都存在的公共落点；而"受理但最终没完成"
 * 的场景由既有的本地存在判定接管（文件不在 → 照常重下），不会把洗版锁死。
 *
 * <h2>落盘口径</h2>
 * 与 {@link FailedDownloadQueue} / {@link DownloadHistory} 一致：temp + rename 原子写、
 * 解析失败改名保留现场、条目数上限裁剪。
 */
@Slf4j
public final class WashState {
    public static final String FILE_NAME = "wash-state.json";

    /**
     * 条目数上限（每条 = 一集）。超出后按时间淘汰最旧的。
     * 一集一条，5 万条足以覆盖长期运行的全部订阅（含历史集数）。
     */
    static final int MAX_ENTRIES = 50_000;

    private static final Map<String, Entry> VERSIONS = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;
    private static volatile String LAST_SAVED_JSON;

    private static final Type STORE_TYPE = new TypeToken<Map<String, Entry>>() {
    }.getType();

    private WashState() {
    }

    /**
     * 一条"某集已落地版本"记录
     */
    @Data
    @Accessors(chain = true)
    public static class Entry implements Serializable {
        private Integer version;
        /** 记账时刻，仅用于超上限时淘汰最旧记录 */
        private Long at;
    }

    /**
     * 该条目在盘上已经落地的版本。合集取范围内<b>最低</b>的那个：
     * 只要还有任何一集停在旧版本，这条合集就仍需洗版。
     *
     * @return 已落地版本；无记录时 0（视为"什么都没下过"，按需洗版）
     */
    public static int versionOf(Ani ani, Item item) {
        if (ani == null || item == null) {
            return 0;
        }
        Set<Double> episodes = episodesOf(item);
        if (episodes.isEmpty()) {
            // 电影/无集数条目：按重命名主名记账（与本地索引的 M: 口径一致）
            return versionOfKey(keyOf(ani, movieKey(item.getReName())));
        }
        int min = Integer.MAX_VALUE;
        for (Double episode : episodes) {
            min = Math.min(min, versionOfKey(keyOf(ani, episodeKey(episode))));
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /**
     * 下载器受理后记账：记录本集（含合集范围）已落地的版本。
     * 只对 v2 命名（洗版特性的宿主）记账；其它命名下版本号不参与任何判定。
     */
    public static void markDownloadAccepted(Ani ani, Item item) {
        if (ani == null || item == null || !RenameUtil.isNamingV2(ani)) {
            return;
        }
        Integer version = item.getVersion();
        if (version == null || version < 1) {
            return;
        }
        Set<Double> episodes = episodesOf(item);
        List<String> keys = new ArrayList<>();
        if (episodes.isEmpty()) {
            keys.add(keyOf(ani, movieKey(item.getReName())));
        } else {
            for (Double episode : episodes) {
                keys.add(keyOf(ani, episodeKey(episode)));
            }
        }
        mark(keys, version);
    }

    /**
     * 订阅删除时回收它的全部记录（订阅 id 是随机串，不回收只会长期占用上限）。
     */
    public static void forget(Ani ani) {
        if (ani == null || StrUtil.isBlank(ani.getId())) {
            return;
        }
        ensureLoaded();
        String prefix = ani.getId() + "|";
        if (VERSIONS.keySet().removeIf(key -> key.startsWith(prefix))) {
            save();
        }
    }

    private static void mark(Collection<String> keys, int version) {
        if (keys.isEmpty()) {
            return;
        }
        ensureLoaded();
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (String key : keys) {
            Entry entry = VERSIONS.get(key);
            if (entry != null && entry.getVersion() != null && entry.getVersion() == version) {
                // 版本没变也要刷新时间：同版本重复记账不该被当成"最旧记录"淘汰
                entry.setAt(now);
                continue;
            }
            VERSIONS.put(key, new Entry().setVersion(version).setAt(now));
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    private static int versionOfKey(String key) {
        if (StrUtil.isBlank(key)) {
            return 0;
        }
        ensureLoaded();
        Entry entry = VERSIONS.get(key);
        return entry == null || entry.getVersion() == null ? 0 : entry.getVersion();
    }

    /**
     * 条目的集数集合：单集 + 合集范围（展开后的 clone 两者都可能带）。
     */
    static Set<Double> episodesOf(Item item) {
        Set<Double> episodes = new LinkedHashSet<>();
        if (item.getEpisode() != null) {
            episodes.add(item.getEpisode());
        }
        if (item.getEpisodeRange() != null) {
            episodes.addAll(item.getEpisodeRange());
        }
        return episodes;
    }

    /**
     * 集数键：整数集落 {@code "1"}、半集落 {@code "1.5"}，两者必须区分开且跨轮次稳定。
     * 之所以不直接用 {@code String.valueOf(double)}：{@code 1.0} 与 {@code 1} 必须落成同一个键。
     */
    static String episodeKey(double episode) {
        if (episode == Math.rint(episode) && !Double.isInfinite(episode)) {
            return String.valueOf((long) episode);
        }
        return String.valueOf(episode);
    }

    /**
     * 无集数（电影式命名）条目的键：与本地索引 {@code M:主名} 同口径。
     */
    static String movieKey(String reName) {
        return "M:" + StrUtil.blankToDefault(reName, "").trim().toUpperCase();
    }

    static String keyOf(Ani ani, String suffix) {
        String aniId = StrUtil.blankToDefault(ani == null ? null : ani.getId(), "");
        Integer season = ani == null ? null : ani.getSeason();
        int s = season == null ? 1 : season;
        return aniId + "|" + s + ":" + suffix;
    }

    private static synchronized void load() {
        File file = file();
        if (!file.exists()) {
            VERSIONS.clear();
            loaded = true;
            return;
        }
        try {
            String json = FileUtil.readString(file, StandardCharsets.UTF_8);
            Map<String, Entry> map = GsonStatic.fromJson(json, STORE_TYPE);
            VERSIONS.clear();
            if (map != null) {
                VERSIONS.putAll(map);
            }
        } catch (Exception e) {
            // 解析失败先把坏文件改名保留现场，避免下一次 save() 用空表覆盖原始记录
            String ts = DateUtil.format(new Date(), "yyyyMMddHHmmss");
            File badFile = new File(file.getParentFile(), FILE_NAME + ".bad-" + ts);
            try {
                FileUtils.move(file.toPath(), badFile.toPath());
                log.error("洗版状态文件解析失败, 已改名为 [{}] 保留现场; 本次以空状态继续", badFile.getName());
            } catch (Exception moveException) {
                log.error("洗版状态文件解析失败, 且改名保留失败(可能被占用): {}", file, moveException);
            }
            VERSIONS.clear();
        }
        // 放在最后：否则并发调用方会在 putAll 还没完成就以为"已加载"，
        // 把"还没读出来"误读成"无记录"（多洗一次的代价，不致命但没必要）。
        loaded = true;
    }

    private static synchronized void save() {
        try {
            File file = file();
            trim();
            String json = GsonStatic.toJson(VERSIONS);
            if (json.equals(LAST_SAVED_JSON) && file.exists()) {
                return;
            }
            File temp = new File(file.getPath() + ".temp");
            FileUtil.writeString(json, temp, StandardCharsets.UTF_8);
            FileUtils.move(temp.toPath(), file.toPath());
            LAST_SAVED_JSON = json;
        } catch (Exception e) {
            log.warn("保存洗版状态失败: {}", e.getMessage());
        }
    }

    /**
     * 超上限时淘汰最旧的记录（内存与落盘同一份口径，不能只裁落盘那份）。
     */
    private static void trim() {
        if (VERSIONS.size() <= MAX_ENTRIES) {
            return;
        }
        List<String> keys = new ArrayList<>(VERSIONS.keySet());
        keys.sort(Comparator.comparingLong(key -> {
            Entry entry = VERSIONS.get(key);
            return entry == null || entry.getAt() == null ? 0L : entry.getAt();
        }));
        int remove = VERSIONS.size() - MAX_ENTRIES;
        for (int i = 0; i < remove; i++) {
            VERSIONS.remove(keys.get(i));
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static File file() {
        return new File(ConfigUtil.getConfigDir(), FILE_NAME);
    }

    /**
     * 测试用：重置内存态，下次读取从当前 CONFIG 目录重新加载
     */
    static synchronized void resetForTest() {
        VERSIONS.clear();
        loaded = false;
        LAST_SAVED_JSON = null;
    }
}
