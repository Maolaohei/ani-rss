package ani.rss.util.other;

import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.enums.StringEnum;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import org.eclipse.bittorrent.TorrentFile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 「期望文件计划」：把种子里的文件清单，按订阅的匹配/排除规则翻译成
 * <b>我们要的哪些文件、各自最终该叫什么名字</b>。
 * <p>
 * 计划条目复用 {@link Item}：{@code title}=种子内相对路径，{@code length}=字节数，
 * {@code reName}=最终文件名（已含扩展名与字幕语言后缀），{@code episode}=解析出的集数。
 * 这正是「添加合集」预览用的结构，因此合集与订阅两条链路共用同一套口径。
 * <p>
 * 为什么需要它（对比"下载完再扫网盘目录反推"）：
 * <ul>
 *   <li><b>完成判定有了硬证据</b>：文件字节数与种子里的 {@code length} 一致，
 *       就是"这一份确实下全了"。不用依赖下载器自报状态（115 常出现"任务一直 Running /
 *       报部分成功，其实文件全在"），也不用等离线超时后的"两次列举大小稳定"启发式。</li>
 *   <li><b>字幕配对不再靠猜</b>：种子里的目录结构与主名是确切的，
 *       不必用"主名相似 / 集数相同"的启发式去配对视频与字幕。</li>
 *   <li><b>清理有依据</b>：计划外的文件（特典、排除项、padding）可以放心随临时目录清理。</li>
 * </ul>
 * 构建纯本地、无网络请求（磁力链接要先用 {@link MagnetTorrentUtil} 换成种子文件）。
 */
public final class TorrentPlanUtil {

    private TorrentPlanUtil() {
    }

    /**
     * 从种子构建计划：过滤 padding → 排除 → 匹配 → 全局排除 → 套用重命名模板。
     * <p>
     * 与「添加合集」预览同口径（原先这段逻辑内联在 {@code CollectionController.preview}，
     * 现抽到这里供两条链路共用）。文件字节数按<b>种子内的原始下标</b>配对：
     * 先按"是否含扩展名"过滤再递增下标会让长度整体错位，而长度是后面匹配网盘产物的主键。
     *
     * @param torrentFile 已解析的种子
     * @param ani         订阅信息（匹配/排除/模板/季数）
     * @return 计划条目；入参缺失或全部被过滤时返回空列表
     */
    public static List<Item> build(TorrentFile torrentFile, Ani ani) {
        if (torrentFile == null || ani == null) {
            return List.of();
        }
        String[] filenames = torrentFile.getFilenames();
        if (filenames == null || filenames.length == 0) {
            return List.of();
        }
        long[] lengths = torrentFile.getLengths();

        List<String> match = orEmpty(ani.getMatch());
        List<String> exclude = orEmpty(ani.getExclude());
        Boolean globalExclude = ani.getGlobalExclude();
        Config config = ConfigUtil.CONFIG;
        List<String> globalExcludeList = config == null ? List.of() : orEmpty(config.getExclude());

        Function<String, String> map = s -> {
            String subgroup = ReUtil.get(StringEnum.SUBGROUP_REG_STR, s, 1);
            if (StrUtil.isBlank(subgroup)) {
                return s;
            }
            if (subgroup.equals(ani.getSubgroup())) {
                return ReUtil.get(StringEnum.SUBGROUP_REG_STR, s, 2);
            }
            return "";
        };

        List<Item> plan = new ArrayList<>();
        for (int i = 0; i < filenames.length; i++) {
            String raw = CharsetUtil.convert(filenames[i], "ISO-8859-1", CharsetUtil.UTF_8);
            raw = ReUtil.replaceAll(raw, "[\\\\/]$", "");
            raw = raw.replace("\\", "/");
            if (!raw.contains(".")) {
                continue;
            }
            final String name = raw;

            long length = i < lengths.length ? lengths[i] : 0L;
            Item item = new Item().setTitle(name).setLength(length);

            if (name.startsWith("_____padding_file_") && name.contains("BitComet")) {
                continue;
            }
            // 排除
            if (!exclude.isEmpty()
                    && exclude.stream().map(map).filter(StrUtil::isNotBlank).anyMatch(s -> ReUtil.contains(s, name))) {
                continue;
            }
            // 匹配
            if (!match.isEmpty()
                    && match.stream().map(map).filter(StrUtil::isNotBlank).anyMatch(s -> !ReUtil.contains(s, name))) {
                continue;
            }
            // 全局排除
            if (Boolean.TRUE.equals(globalExclude)
                    && globalExcludeList.stream().map(map).filter(StrUtil::isNotBlank)
                    .anyMatch(s -> ReUtil.contains(s, name))) {
                continue;
            }

            item.setFormatSize(FileUtils.formatSize(length, true))
                    .setSubgroup(ani.getSubgroup());

            RenameUtil.rename(ani, item);

            String reName = item.getReName();
            if (StrUtil.isBlank(reName)) {
                continue;
            }

            String extName = FileUtil.extName(name);

            if (Boolean.TRUE.equals(FileUtils.isSubtitleFormat(extName))) {
                // 语言后缀与下载器重命名口径一致（含 jpsc/jptc 等双语标识，统一小写）；
                // 原实现取 mainName 的最后一段，会把 "xxx.1080p.ass" 的 1080p 误当语言
                String lang = FileUtils.extractSubtitleLangSuffix(name);
                if (StrUtil.isNotBlank(lang)) {
                    reName += "." + lang;
                }
            }

            plan.add(item.setReName(reName + "." + extName).setLength(length));
        }
        return List.copyOf(plan);
    }

    /**
     * 订阅链路用：把计划收窄到本次真正要的这一/几集。
     * <p>
     * 规则（对整季包尤其重要——单集订阅从整季包里只该拿走自己那一集）：
     * <ul>
     *   <li>{@code expectedEpisodes} 为空（剧场版/OVA/无法解析集数）→ 整份计划保留；</li>
     *   <li>计划里只有一个视频 → 整份计划保留（单文件种子，整份就是这一集，字幕跟着走）；</li>
     *   <li>否则 → 只保留集数命中期望的条目，其余（含解析不出集数的条目）丢弃，
     *       避免把特典/别的集搬进本集目录。丢弃后计划为空时调用方自动回退旧启发式。</li>
     * </ul>
     *
     * @param plan             完整计划
     * @param expectedEpisodes 期望集数（{@code episodeRange} 优先，其次 {@code episode}）
     */
    public static List<Item> filterEpisodes(List<Item> plan, List<Double> expectedEpisodes) {
        if (plan == null || plan.isEmpty()) {
            return List.of();
        }
        Set<Double> expected = expectedEpisodes == null
                ? Set.of()
                : expectedEpisodes.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (expected.isEmpty()) {
            return plan;
        }
        if (videosOf(plan).size() <= 1) {
            return plan;
        }
        return plan.stream()
                .filter(it -> it.getEpisode() != null && expected.contains(it.getEpisode()))
                .toList();
    }

    /**
     * 本次要搬走的文件对应的「本地状态快照」键（供精确标记完成用）。
     * <p>
     * 存在的意义：合集条目一旦被判完成，旧实现会把 {@code episodeRange} 里<b>全部</b>集数
     * 都标记成已下载——哪怕这次只归位了其中一部分（"下载器报部分成功"的典型后果），
     * 剩下的集会被缓存伪装成已下载而永久漏下。改为按"真正落位的计划条目"逐条取键。
     */
    public static Set<String> episodeIndexKeys(Ani ani, List<Item> resolvedItems) {
        Set<String> keys = new LinkedHashSet<>();
        if (resolvedItems == null) {
            return keys;
        }
        for (Item item : resolvedItems) {
            if (item == null || !isVideo(item)) {
                continue;
            }
            keys.addAll(RenameUtil.episodeIndexKeys(ani, item));
        }
        return keys;
    }

    /**
     * 期望集数：{@code episodeRange} 优先，其次单个 {@code episode}。
     * <p>
     * 必须回退到 {@code List.of(episode)}：单集条目的 {@code episodeRange} 为空，
     * 否则"期望集数"会变成空集，下游的"空集放行"分支会让同目录任意其它集冒充本集
     * （实测：骸骨骑士 S02E06 被同目录 E01~E05/E07 冒充）。
     */
    public static List<Double> expectedEpisodesOf(Item item) {
        if (item == null) {
            return List.of();
        }
        if (item.getEpisodeRange() != null && !item.getEpisodeRange().isEmpty()) {
            return item.getEpisodeRange();
        }
        return item.getEpisode() != null ? List.of(item.getEpisode()) : List.of();
    }

    public static List<Item> videosOf(List<Item> plan) {
        if (plan == null) {
            return List.of();
        }
        return plan.stream().filter(TorrentPlanUtil::isVideo).toList();
    }

    public static List<Item> subtitlesOf(List<Item> plan) {
        if (plan == null) {
            return List.of();
        }
        return plan.stream().filter(TorrentPlanUtil::isSubtitle).toList();
    }

    public static boolean isVideo(Item item) {
        return item != null && Boolean.TRUE.equals(FileUtils.isVideoFormat(FileUtil.extName(item.getTitle())));
    }

    public static boolean isSubtitle(Item item) {
        return item != null && Boolean.TRUE.equals(FileUtils.isSubtitleFormat(FileUtil.extName(item.getTitle())));
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
