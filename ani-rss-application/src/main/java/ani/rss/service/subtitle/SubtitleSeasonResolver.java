package ani.rss.service.subtitle;

import ani.rss.entity.Ani;
import ani.rss.entity.BgmInfo;
import ani.rss.util.other.BgmUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TmdbUtils;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import wushuo.tmdb.api.entity.Tmdb;

import java.io.File;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 字幕季数解析器
 *
 * <p>背景：ASSRT 上的多季合集包里，很多番剧条目只写名字（如 {@code High School DxD NEW}、
 * {@code High School DxD BorN}），并不带 {@code S1/S2} 这类季数标记。直接按文件名相似度匹配时，
 * 容易把第 1 季的字幕误挂到第 2 季的订阅视频上。</p>
 *
 * <p>解析策略（与订阅对比，绝不臆造季数）：
 * <ol>
 *   <li>字幕系列名本身带有显式季数标记（S2 / 第2季 / Season 2 / 2nd Season）→ 直接采用；</li>
 *   <li>否则查元数据（TMDB 为主、Bangumi 兜底），把字幕对应的番剧与<strong>订阅</strong>做对比：
 *       仅当 Bangumi 条目 id 与订阅的 Bangumi 条目 id 完全一致（每个动画季度在 Bangumi 是独立条目，
 *       可精确区分 NEW/BorN 与第 1 季）时，才采用<strong>订阅已知的季数</strong>；</li>
 *   <li>以上都无法确定 → 返回 {@code null}，回落到原有的文件名相似度模糊匹配，绝不把未知番剧臆造成第 1 季。</li>
 * </ol>
 * </p>
 *
 * <p>相同番剧名只查一次：解析结果（含"查过但无结论"的负缓存）按完整系列名精确缓存到本地
 * {@code subtitle_season_cache.json}，重启后依然有效。</p>
 */
@Slf4j
public class SubtitleSeasonResolver {

    /**
     * 负缓存哨兵：已查询但无法确定季数，回落模糊匹配；写入磁盘避免重复查询。
     */
    private static final int NEGATIVE = -1;

    private static final String CACHE_FILE_NAME = "subtitle_season_cache.json";

    private static final Map<String, Integer> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;
    private static final ReentrantLock IO_LOCK = new ReentrantLock();

    private static final Type CACHE_TYPE = new TypeToken<Map<String, Integer>>() {
    }.getType();

    /**
     * 解析字幕系列名对应的季数。
     *
     * @param ani         订阅（提供已知季数与 Bangumi/TMDB 对照信息）；可为 null（此时仅走显式标记快路径）
     * @param seriesName  已从字幕文件名中清洗出的"番剧系列名"（不含集数/编码等技术词）
     * @return 季数（>=1），或 null 表示无法确定（回落模糊匹配）
     */
    public static Integer resolve(Ani ani, String seriesName) {
        if (!Boolean.TRUE.equals(ConfigUtil.CONFIG.getSubtitleMetaEnabled())) {
            return null;
        }
        if (StrUtil.isBlank(seriesName)) {
            return null;
        }
        ensureLoaded();

        // 1. 显式季数标记：快路径，无需任何网络查询
        Integer explicit = BgmUtil.getSeasonByName(seriesName);
        if (explicit > 1) {
            putCache(seriesName, explicit);
            return explicit;
        }

        // 2. 命中本地缓存（含负缓存）
        if (CACHE.containsKey(seriesName)) {
            Integer v = CACHE.get(seriesName);
            return v == NEGATIVE ? null : v;
        }

        // 3. 元数据解析（与订阅对比）
        Integer resolved;
        try {
            resolved = resolveByMeta(ani, seriesName);
        } catch (Exception e) {
            log.warn("字幕季数解析异常, 回落模糊匹配: {} -> {}", seriesName, e.getMessage());
            resolved = null;
        }
        putCache(seriesName, resolved == null ? NEGATIVE : resolved);
        return resolved;
    }

    private static Integer resolveByMeta(Ani ani, String seriesName) {
        // 3a. TMDB：若返回的条目名本身带季数标记则采用（动画季通常以"第N季"形式出现在别名里的情况较少，
        //     主要作为 Bangumi 的辅助信号，避免对单一系列做 id 相等匹配导致的跨季误判）。
        try {
            Optional<Tmdb> sub = TmdbUtils.getTmdbTv(seriesName);
            if (sub.isPresent()) {
                Tmdb subTmdb = sub.get();
                Integer ex = BgmUtil.getSeasonByName(subTmdb.getName());
                if (ex > 1) {
                    return ex;
                }
            }
        } catch (Exception e) {
            log.warn("字幕季数解析 TMDB 查询失败: {} -> {}", seriesName, e.getMessage());
        }

        // 3b. Bangumi 兜底：每个季度是独立条目，id 精确对应某一季。
        //     仅当候选条目 id 与订阅的 Bangumi 条目 id 完全一致时，采用订阅已知季数。
        try {
            List<BgmInfo> list = BgmUtil.search(seriesName);
            if (list != null && !list.isEmpty()) {
                String subBgmId = subscriptionBgmId(ani);
                Integer subSeason = subscriptionSeason(ani);
                if (StrUtil.isNotBlank(subBgmId) && subSeason != null && subSeason >= 1) {
                    for (BgmInfo b : list) {
                        if (StrUtil.equals(b.getId(), subBgmId)) {
                            log.info("字幕季数解析命中订阅: 系列名 [{}] 经 Bangumi 对照订阅(季{}) 判定为第 {} 季",
                                    seriesName, subSeason, subSeason);
                            return subSeason;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("字幕季数解析 Bangumi 查询失败: {} -> {}", seriesName, e.getMessage());
        }

        return null;
    }

    /**
     * 订阅已知的季数（null 表示未配置）
     */
    private static Integer subscriptionSeason(Ani ani) {
        if (ani == null) {
            return null;
        }
        Integer s = ani.getSeason();
        if (s == null || s < 1) {
            return null;
        }
        return s;
    }

    /**
     * 从订阅的 bgmUrl 中解析 Bangumi 条目 id（不触发任何网络请求）。
     */
    private static String subscriptionBgmId(Ani ani) {
        if (ani == null) {
            return null;
        }
        String bgmUrl = ani.getBgmUrl();
        if (StrUtil.isBlank(bgmUrl)) {
            return null;
        }
        // 形如 https://bgm.tv/subject/12345 或 https://bangumi.tv/subject/12345
        String reg = "^https?://.+?/subject/(\\d+)(/.*)?$";
        if (cn.hutool.core.util.ReUtil.contains(reg, bgmUrl)) {
            return cn.hutool.core.util.ReUtil.get(reg, bgmUrl, 1);
        }
        return null;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        IO_LOCK.lock();
        try {
            if (loaded) {
                return;
            }
            File file = cacheFile();
            if (file.exists()) {
                try {
                    String text = FileUtil.readUtf8String(file);
                    if (StrUtil.isNotBlank(text)) {
                        Map<String, Integer> map = new Gson().fromJson(text, CACHE_TYPE);
                        if (map != null) {
                            CACHE.putAll(map);
                        }
                    }
                } catch (Exception e) {
                    log.warn("读取字幕季数缓存失败, 从头开始: {}", e.getMessage());
                }
            }
            loaded = true;
        } finally {
            IO_LOCK.unlock();
        }
    }

    private static void putCache(String seriesName, int value) {
        CACHE.put(seriesName, value);
        persist();
    }

    private static void persist() {
        IO_LOCK.lock();
        try {
            File file = cacheFile();
            FileUtil.writeUtf8String(new Gson().toJson(CACHE), file);
        } catch (Exception e) {
            log.warn("写入字幕季数缓存失败: {}", e.getMessage());
        } finally {
            IO_LOCK.unlock();
        }
    }

    private static File cacheFile() {
        File dir = ConfigUtil.getConfigDir();
        FileUtil.mkdir(dir);
        return new File(dir, CACHE_FILE_NAME);
    }
}
