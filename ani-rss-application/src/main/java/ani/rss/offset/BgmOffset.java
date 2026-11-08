package ani.rss.offset;

import ani.rss.entity.Ani;
import ani.rss.util.other.BgmUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 跟随 Bangumi 正篇编号的偏移策略，使用对应条目最小正篇集数作为目标首集。
 * <p>
 * 例如 Bangumi 正篇从第 13 集开始时：RSS 从第 1 集开始则偏移 +12，
 * RSS 从第 13 集开始则保持编号不变。
 * <p>
 * 缓存按 subjectId 存放：一次自动偏移过程中主 RSS 与备用 RSS 会各算一次，
 * 命中缓存可避免重复请求 Bangumi。
 */
@Slf4j
public class BgmOffset implements BaseOffset {
    /**
     * 当前策略实例内的目标首集缓存，key 为 Bangumi subjectId
     */
    private final Map<String, Integer> cache = new HashMap<>();

    /**
     * 根据订阅的 Bangumi 链接获取最小正篇集数，优先读取当前实例的缓存。
     * <p>
     * 正篇列表为空或无法解析时以第 1 集为起点，等价于原有自动偏移行为。
     *
     * @param ani 包含 Bangumi 作品链接的订阅
     * @return Bangumi 正篇最小集数，无法获取时返回 1
     */
    @Override
    public Integer getMinEpisode(Ani ani) {
        String subjectId;
        try {
            subjectId = BgmUtil.getSubjectId(ani);
        } catch (Exception e) {
            // 没有 bgmUrl（例如 mikan 订阅取不到 bangumiId）时退回原有行为
            log.debug("无法获取 Bangumi subjectId, 按第 1 集处理: {}", e.getMessage());
            return 1;
        }

        if (StrUtil.isBlank(subjectId)) {
            return 1;
        }

        Integer cached = cache.get(subjectId);
        if (Objects.nonNull(cached)) {
            return cached;
        }

        int minEpisode = 1;
        try {
            // Bangumi 的剧集类型 0 表示正篇，避免把特别篇等其他类型作为起始集
            List<JsonObject> episodes = BgmUtil.getEpisodes(subjectId, 0);
            minEpisode = episodes.stream()
                    .map(it -> readSort(it))
                    .filter(Objects::nonNull)
                    .mapToInt(Double::intValue)
                    .min()
                    .orElse(1);
        } catch (Exception e) {
            log.warn("获取 Bangumi 正篇起始集失败 {}: {}", subjectId, e.getMessage());
            minEpisode = 1;
        }

        cache.put(subjectId, minEpisode);
        return minEpisode;
    }

    /**
     * 读取剧集的 sort 字段。
     *
     * @param episode Bangumi 剧集
     * @return sort 值，缺失时返回 null
     */
    private Double readSort(JsonObject episode) {
        if (Objects.isNull(episode)) {
            return null;
        }
        JsonElement sortElement = episode.get("sort");
        if (Objects.isNull(sortElement) || sortElement.isJsonNull()) {
            return null;
        }
        try {
            return sortElement.getAsDouble();
        } catch (Exception e) {
            return null;
        }
    }
}
