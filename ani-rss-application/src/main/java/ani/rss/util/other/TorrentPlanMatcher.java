package ani.rss.util.other;

import ani.rss.entity.Item;
import ani.rss.entity.OpenListFileInfo;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 把「期望文件计划」（{@link TorrentPlanUtil}）与网盘上真实存在的文件对上号。
 * <p>
 * <b>为什么以字节数为主键</b>：115 会按文件名建同名目录、给重名文件加 {@code (1)} 后缀、
 * 对 URL 编码过的名字重新解码——<b>名字完全不可靠</b>；而 {@code length} 来自种子元数据，
 * 是内容本身的长度，只要文件没被重新封装/转码就不会变。名字只用来在多个同长度候选之间
 * 做消歧（以及最坏情况下的兜底认领）。
 * <p>
 * 三条硬约束：
 * <ol>
 *   <li>一个网盘文件只能被认领一次（防"同长度的两个字幕都算命中同一个计划条目"）；</li>
 *   <li>候选不唯一时<b>不猜</b>：宁可不认领（走旧启发式），也不认错（会把别的集搬进来）；</li>
 *   <li>只认文件不认目录（115 会用 {@code xxx.mkv} 建同名目录，目录名同样带扩展名）。</li>
 * </ol>
 */
@Slf4j
public final class TorrentPlanMatcher {

    private TorrentPlanMatcher() {
    }

    /**
     * 一条命中：计划条目 ↔ 网盘上真实文件
     */
    public record Match(Item plan, OpenListFileInfo file) {
    }

    /**
     * 匹配结果
     */
    public static final class Result {
        private final List<Match> matches;
        private final List<Item> missing;
        private final List<Item> missingVideos;
        private final List<OpenListFileInfo> unmatched;

        Result(List<Match> matches, List<Item> missing, List<Item> missingVideos,
               List<OpenListFileInfo> unmatched) {
            this.matches = matches;
            this.missing = missing;
            this.missingVideos = missingVideos;
            this.unmatched = unmatched;
        }

        public List<Match> matches() {
            return matches;
        }

        /**
         * 计划里没找到的条目（含字幕/其他文件）
         */
        public List<Item> missing() {
            return missing;
        }

        /**
         * 计划里没找到的<b>视频</b>：这是"有没有下全"的判据
         */
        public List<Item> missingVideos() {
            return missingVideos;
        }

        /**
         * 网盘上存在但不在计划内的文件（特典/排除项/重复副本），随临时目录清理
         */
        public List<OpenListFileInfo> unmatched() {
            return unmatched;
        }

        /**
         * 计划中的视频是否已全部就位
         */
        public boolean videosComplete() {
            return !matches.isEmpty() && missingVideos.isEmpty();
        }

        /**
         * 计划条目是否已全部就位
         */
        public boolean complete() {
            return !matches.isEmpty() && missing.isEmpty();
        }

        public List<Match> videoMatches() {
            return matches.stream().filter(m -> TorrentPlanUtil.isVideo(m.plan())).toList();
        }

        public List<Match> subtitleMatches() {
            return matches.stream().filter(m -> TorrentPlanUtil.isSubtitle(m.plan())).toList();
        }
    }

    /**
     * 逐条认领计划条目。
     * <p>
     * 处理顺序：视频优先（避免同长度的字幕先抢走视频的候选），其次字幕，最后其他；
     * 同类型内按字节数倒序、再按路径，保证结果可复现。
     */
    public static Result match(List<Item> plan, List<OpenListFileInfo> files) {
        List<OpenListFileInfo> candidates = files == null
                ? List.of()
                : files.stream()
                .filter(Objects::nonNull)
                .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                .toList();

        List<Match> matches = new ArrayList<>();
        List<Item> missing = new ArrayList<>();
        List<Item> missingVideos = new ArrayList<>();
        if (plan == null || plan.isEmpty()) {
            return new Result(matches, missing, missingVideos, candidates);
        }

        Set<OpenListFileInfo> used = new HashSet<>();
        List<Item> ordered = new ArrayList<>(plan);
        ordered.sort(Comparator
                .comparingInt((Item it) -> kindRank(it))
                .thenComparing(it -> it.getLength() == null ? 0L : -it.getLength())
                .thenComparing(it -> StrUtil.nullToEmpty(it.getTitle())));

        for (Item entry : ordered) {
            Optional<OpenListFileInfo> hit = pick(entry, candidates, used);
            if (hit.isPresent()) {
                used.add(hit.get());
                matches.add(new Match(entry, hit.get()));
                continue;
            }
            missing.add(entry);
            if (TorrentPlanUtil.isVideo(entry)) {
                missingVideos.add(entry);
            }
        }

        List<OpenListFileInfo> unmatched = candidates.stream().filter(f -> !used.contains(f)).toList();
        return new Result(List.copyOf(matches), List.copyOf(missing),
                List.copyOf(missingVideos), unmatched);
    }

    private static int kindRank(Item item) {
        if (TorrentPlanUtil.isVideo(item)) {
            return 0;
        }
        if (TorrentPlanUtil.isSubtitle(item)) {
            return 1;
        }
        return 2;
    }

    /**
     * 为一个计划条目挑文件：先"字节数+名字"，再"仅字节数"，最后"仅名字"；每档都要求候选唯一
     * （多个候选时用父目录名消歧，仍不唯一就放弃）。
     */
    private static Optional<OpenListFileInfo> pick(Item entry, List<OpenListFileInfo> candidates,
                                                   Set<OpenListFileInfo> used) {
        List<OpenListFileInfo> free = candidates.stream().filter(f -> !used.contains(f)).toList();
        if (free.isEmpty()) {
            return Optional.empty();
        }
        Long length = entry.getLength();

        List<OpenListFileInfo> bySizeAndName = free.stream()
                .filter(f -> sameSize(f, length) && sameName(f, entry))
                .toList();
        Optional<OpenListFileInfo> hit = unique(bySizeAndName, entry, "字节数+文件名");
        if (hit.isPresent()) {
            return hit;
        }

        List<OpenListFileInfo> bySize = free.stream().filter(f -> sameSize(f, length)).toList();
        if (length != null && length > 0) {
            hit = unique(bySize, entry, "字节数");
            if (hit.isPresent()) {
                return hit;
            }
        }

        List<OpenListFileInfo> byName = free.stream().filter(f -> sameName(f, entry)).toList();
        return unique(byName, entry, "文件名");
    }

    private static Optional<OpenListFileInfo> unique(List<OpenListFileInfo> candidates, Item entry, String tier) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));
        }
        // 多个候选：用"父目录名与种子内相对目录一致"消歧
        String dir = planDir(entry.getTitle());
        if (StrUtil.isNotBlank(dir)) {
            List<OpenListFileInfo> sameDir = candidates.stream()
                    .filter(f -> Objects.equals(dir, dirName(f.getPath())))
                    .toList();
            if (sameDir.size() == 1) {
                return Optional.of(sameDir.get(0));
            }
        }
        log.warn("计划条目按 {} 匹配到 {} 个候选，无法唯一确定，交由旧逻辑处理: {} {}",
                tier, candidates.size(), entry.getTitle(),
                candidates.stream().map(OpenListFileInfo::getName).toList());
        return Optional.empty();
    }

    private static boolean sameSize(OpenListFileInfo file, Long length) {
        if (length == null || length <= 0 || file.getSize() == null) {
            return false;
        }
        return Objects.equals(file.getSize(), length);
    }

    /**
     * 名字比较：只比最后一段（计划里是种子内相对路径），大小写不敏感。
     */
    private static boolean sameName(OpenListFileInfo file, Item entry) {
        String fileName = file.getName();
        if (StrUtil.isBlank(fileName)) {
            return false;
        }
        String planName = FileUtil.getName(StrUtil.nullToEmpty(entry.getTitle()).replace("\\", "/"));
        return fileName.equalsIgnoreCase(planName);
    }

    /**
     * 种子内相对目录（{@code A/B/x.mkv} → {@code B}）
     */
    private static String planDir(String title) {
        String path = StrUtil.nullToEmpty(title).replace("\\", "/");
        int idx = path.lastIndexOf('/');
        return idx <= 0 ? null : path.substring(0, idx);
    }

    /**
     * 文件所在目录的名称。
     * <p>
     * 注意 {@code OpenListFileInfo.path} 已经是"<b>所在目录</b>"（不是文件全路径），
     * 所以取最后一段即为目录名（{@code /115/x/Show S01 → Show S01}）。
     */
    private static String dirName(String path) {
        String normalized = StrUtil.nullToEmpty(path).replace("\\", "/");
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int idx = normalized.lastIndexOf('/');
        return idx < 0 ? normalized : normalized.substring(idx + 1);
    }
}
