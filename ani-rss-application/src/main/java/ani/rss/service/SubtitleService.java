package ani.rss.service;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.controller.PlayController;
import ani.rss.download.OpenListApi;
import ani.rss.entity.Ani;
import ani.rss.entity.OpenListFileInfo;
import ani.rss.entity.PlayItem;
import ani.rss.service.subtitle.AssrtSubtitleItem;
import ani.rss.service.subtitle.AssrtSubtitleProvider;
import ani.rss.service.subtitle.SubtitleCandidate;
import ani.rss.service.subtitle.SubtitleMatchLog;
import ani.rss.service.subtitle.SubtitleMatchLogEntry;
import ani.rss.service.subtitle.SubtitlePick;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 字幕匹配与补全。
 * <p>
 * 播放侧此前已支持内封字幕（EBML 解析）与外挂字幕（ass/srt），但缺两件事：
 * <ol>
 *   <li><b>可发现性</b>——不知道哪几集还没字幕，只能一集集点开看；</li>
 *   <li><b>可补性</b>——拿到字幕后要自己 SSH 上去拷文件。</li>
 * </ol>
 * 本服务提供"缺字幕清单 + 就地附加字幕"，并统一承载<b>手动</b>字幕管理：
 * <ul>
 *   <li>导入用户本地上传的字幕（{@link #importLocalSubtitles}）；</li>
 *   <li>从射手网（ASSRT）获取字幕（{@link #searchAssrt} / {@link #planFetchFromAssrt} / {@link #applyFetchPlan}）。</li>
 * </ul>
 * <b>关于在线字幕源</b>：为避免自动匹配到错误字幕，下载完成后<b>不再</b>自动抓取。
 * 射手网获取拆成三步，每一步都不写盘：
 * <ol>
 *   <li>{@link #searchAssrt}——以番剧<b>英文标题</b>发起<b>单次</b>搜索，返回候选条目供用户挑选
 *       （ASSRT 配额紧，默认 5 次/分钟，因此不按视频/集数拆分请求）；</li>
 *   <li>{@link #planFetchFromAssrt}——用户选中某条候选后才下载其内容，生成匹配计划（只读）；</li>
 *   <li>{@link #applyFetchPlan}——用户二次确认后落盘。</li>
 * </ol>
 * 搜索结果与匹配计划都在内存中短期缓存，避免重复请求字幕源。
 */
@Slf4j
@Service
public class SubtitleService {

    /**
     * 单次扫描的视频数上限，防止超大库拖垮接口
     */
    private static final int MAX_SCAN_VIDEOS = 2000;

    /**
     * 字幕内容上限 20MiB，与播放侧内封字幕限制保持一致
     */
    private static final long MAX_SUBTITLE_BYTES = 20L * 1024 * 1024;

    /**
     * 允许的字幕扩展名
     */
    private static final List<String> SUBTITLE_EXT = List.of("ass", "srt", "ssa", "vtt", "sub");

    /**
     * 字幕覆盖前的备份目录名（视频同目录下），不存在时自动新建。
     * <p>
     * 单独收进一个目录，避免 {@code 视频.ass.bak} 散落在视频目录里污染列表、
     * 也避免被播放侧误当成有效外挂字幕。
     */
    private static final String SUBTITLE_BACKUP_DIR = "sub_bak";

    /**
     * 匹配计划有效期：预览与确认导入之间允许的最大间隔
     */
    private static final long PLAN_TTL_MS = 30 * 60 * 1000L;

    /**
     * 计划缓存条数上限（LRU），防止长期占用内存
     */
    private static final int MAX_PLAN_CACHE = 4;

    /**
     * 射手网匹配计划缓存：planId → 计划。LRU + TTL，确认导入时消费。
     */
    private static final Map<String, FetchPlan> PLAN_CACHE =
            new LinkedHashMap<>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, FetchPlan> eldest) {
                    return size() > MAX_PLAN_CACHE;
                }
            };

    /**
     * 射手网搜索结果缓存：searchId → 候选条目。
     * <p>
     * 搜索与「用户选中后的下载」之间可能间隔较久（用户要逐条比较），故缓存候选条目本身，
     * 而不是把 ASSRT 的下载直链透给前端——直链带时效，且重新搜索会白白消耗配额。
     * 同样 LRU + TTL。
     */
    private static final Map<String, CachedSearch> SEARCH_CACHE =
            new LinkedHashMap<>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedSearch> eldest) {
                    return size() > MAX_PLAN_CACHE;
                }
            };

    /**
     * 搜索结果缓存的有效期，与匹配计划一致
     */
    private static final long SEARCH_TTL_MS = 30 * 60 * 1000L;

    @Resource
    private PlayController playController;

    @Resource
    private DownloadService downloadService;

    @Resource
    private AssrtSubtitleProvider assrtSubtitleProvider;

    /**
     * 是否启用了手动字幕获取能力（射手网/ASSRT）。
     * <p>
     * 对应设置项「字幕手动获取」——历史字段名为 {@code subtitleAutoFetch}，
     * 因下载完成后不再自动抓取而更名为手动语义。
     */
    public boolean isManualFetchEnabled() {
        return Boolean.TRUE.equals(ConfigUtil.CONFIG.getSubtitleManualFetch());
    }

    /**
     * 缺字幕检查结果
     */
    public static class SubtitleReport {
        private int videoCount;
        private int withSubtitle;
        private int missing;
        private List<Map<String, Object>> missingItems = new ArrayList<>();

        public int getVideoCount() {
            return videoCount;
        }

        public SubtitleReport setVideoCount(int videoCount) {
            this.videoCount = videoCount;
            return this;
        }

        public int getWithSubtitle() {
            return withSubtitle;
        }

        public SubtitleReport setWithSubtitle(int withSubtitle) {
            this.withSubtitle = withSubtitle;
            return this;
        }

        public int getMissing() {
            return missing;
        }

        public SubtitleReport setMissing(int missing) {
            this.missing = missing;
            return this;
        }

        public List<Map<String, Object>> getMissingItems() {
            return missingItems;
        }

        public SubtitleReport setMissingItems(List<Map<String, Object>> missingItems) {
            this.missingItems = missingItems;
            return this;
        }
    }

    /**
     * 待导入的本地字幕（原始文件名 + 原始字节）。
     * <p>
     * 与 Spring 的 {@code MultipartFile} 解耦，便于单测直接构造。
     */
    public static class LocalSubtitleFile {
        private final String filename;
        private final byte[] content;

        public LocalSubtitleFile(String filename, byte[] content) {
            this.filename = filename;
            this.content = content;
        }

        public String getFilename() {
            return filename;
        }

        public byte[] getContent() {
            return content;
        }
    }

    /**
     * 批量导入结果：逐文件的匹配/写入明细 + 汇总计数。
     * <p>
     * 预览（dryRun）与正式导入共用同一结构，前端可用同一张表渲染
     * 「改名前 / 改名后 / 对应的视频 / 语言 / 状态 / 说明」。
     */
    public static class ImportResult {
        /** 参与处理的文件总数 */
        private int total;
        /** 成功匹配并写入的数量 */
        private int success;
        /** 失败数量（格式不支持 / 解析不出集数 / 找不到视频 / 写入异常） */
        private int failed;
        /** 逐文件明细 */
        private List<Map<String, Object>> items = new ArrayList<>();

        public int getTotal() {
            return total;
        }

        public ImportResult setTotal(int total) {
            this.total = total;
            return this;
        }

        public int getSuccess() {
            return success;
        }

        public ImportResult setSuccess(int success) {
            this.success = success;
            return this;
        }

        public int getFailed() {
            return failed;
        }

        public ImportResult setFailed(int failed) {
            this.failed = failed;
            return this;
        }

        public List<Map<String, Object>> getItems() {
            return items;
        }

        public ImportResult setItems(List<Map<String, Object>> items) {
            this.items = items;
            return this;
        }
    }

    /**
     * 目录内视频的索引：用于把字幕按「季 + 集」定位到唯一视频。
     * <p>
     * 泛型参数是「视频的载体」：本地导入是 {@link File}，云端（OpenList/AList）导入是
     * {@link CloudVideo}（网盘文件没有本地路径，只有名字与所在目录）。
     * 匹配规则必须本地/云端共用同一份实现——否则「集数不唯一时宁可失败」这类歧义判定
     * 迟早会在两边分叉，变成"本地拒绝、云端误配"。
     */
    private static class VideoIndex<T> {
        /**
         * 主键：规范化 {@code SxxExx}（小写，如 {@code s03e15}）→ 视频
         */
        private final Map<String, T> bySeasonEpisode = new LinkedHashMap<>();
        /**
         * 兜底键：集数 → 视频列表（字幕未带季数、或视频名未带季标记时使用）
         */
        private final Map<Integer, List<T>> byEpisode = new LinkedHashMap<>();
    }

    /**
     * 网盘上的一集视频：名字 + 所在目录。
     * <p>
     * {@link OpenListFileInfo#getPath()} 是<b>父目录</b>而非完整路径（见 fs/list 的填充逻辑），
     * 所以写入字幕时用 {@code dir + "/" + 目标名} 拼完整路径。
     *
     * @param name 视频文件名
     * @param dir  视频所在网盘目录
     */
    private record CloudVideo(String name, String dir) {
    }

    /**
     * 射手网获取计划中的单条记录：预览信息 + 确认后写入所需的全部上下文。
     */
    @Getter
    public static class FetchPlanItem {
        /**
         * 预览行（originalName / renamedName / videoName / lang / status / reason）
         */
        private final Map<String, Object> preview;
        /**
         * 已下载的字幕内容；未命中时为 null
         */
        private final byte[] content;
        private final String videoName;
        private final String originalName;
        private final String renamedName;
        private final String langTag;
        private final String ext;
        private final Integer season;
        /**
         * 本地视频绝对路径（云端场景为空）
         */
        private final String localVideoPath;
        /**
         * 云端目录（本地场景为空）
         */
        private final String cloudDir;

        FetchPlanItem(Map<String, Object> preview, byte[] content, String videoName, String originalName,
                      String renamedName, String langTag, String ext, Integer season,
                      String localVideoPath, String cloudDir) {
            this.preview = preview;
            this.content = content;
            this.videoName = videoName;
            this.originalName = originalName;
            this.renamedName = renamedName;
            this.langTag = langTag;
            this.ext = ext;
            this.season = season;
            this.localVideoPath = localVideoPath;
            this.cloudDir = cloudDir;
        }

        /**
         * 是否命中（命中才可写入）
         */
        public boolean isMatched() {
            return content != null && content.length > 0;
        }
    }

    /**
     * 射手网匹配计划：预览与确认导入之间的桥梁。
     */
    @Getter
    public static class FetchPlan {
        private final String planId;
        private final String aniId;
        private final String aniTitle;
        /**
         * 计划<b>构建完成</b>的时刻，用于 TTL 判定。
         * <p>
         * 由 {@code cachePlan} 在入缓存前重新打点，而不是在对象构造时取扫描开始时间：
         * 射手网限流默认 5 次/分钟，长季扫描本身就可能超过 30 分钟的 TTL，
         * 从扫描开始计时会让用户拿到预览时计划已经过期、确认时白跑一轮限流配额。
         */
        private long createdAt = System.currentTimeMillis();
        private final List<FetchPlanItem> items = new ArrayList<>();

        FetchPlan(String planId, String aniId, String aniTitle) {
            this.planId = planId;
            this.aniId = aniId;
            this.aniTitle = aniTitle;
        }

        /**
         * 以当前时刻重新打点 TTL 起点（计划构建完成时调用）
         */
        void stampCreatedAt() {
            this.createdAt = System.currentTimeMillis();
        }

        /**
         * 预览行列表（前端二次确认弹窗直接渲染）
         */
        public List<Map<String, Object>> previewItems() {
            List<Map<String, Object>> previews = new ArrayList<>(items.size());
            for (FetchPlanItem item : items) {
                previews.add(item.getPreview());
            }
            return previews;
        }

        /**
         * 命中数量
         */
        public int matchedCount() {
            int matched = 0;
            for (FetchPlanItem item : items) {
                if (item.isMatched()) {
                    matched++;
                }
            }
            return matched;
        }
    }

    /**
     * 射手网搜索结果：单次搜索得到的候选条目 + 后续选择所需的 {@code searchId}。
     * <p>
     * 前端据此渲染候选列表（字幕名 / 语言 / 类型 / 文件数），用户选中某一条后再回传
     * {@code searchId + index} 进入下载与写入流程。
     */
    @Getter
    public static class AssrtSearchResult {
        /**
         * 搜索结果缓存 id，用于「选中某条候选后生成计划」时取回候选
         */
        private final String searchId;
        /**
         * 实际使用的搜索关键词（便于用户判断为什么搜不到，通常是番剧英文标题）
         */
        private final String keyword;
        /**
         * 候选列表（前端表格数据）
         */
        private final List<Map<String, Object>> candidates;

        AssrtSearchResult(String searchId, String keyword, List<Map<String, Object>> candidates) {
            this.searchId = searchId;
            this.keyword = keyword;
            this.candidates = candidates;
        }
    }

    /**
     * 搜索结果缓存条目：候选条目 + 所属订阅 + 构建时刻（TTL 判定）。
     */
    private static class CachedSearch {
        private final String aniId;
        private final String keyword;
        private final List<AssrtSubtitleItem> items;
        private final long createdAt = System.currentTimeMillis();

        CachedSearch(String aniId, String keyword, List<AssrtSubtitleItem> items) {
            this.aniId = aniId;
            this.keyword = keyword;
            this.items = items;
        }
    }

    /**
     * 扫描订阅目录，列出没有字幕的视频
     */
    public SubtitleReport scanMissing(Ani ani, String downloadPath) {
        SubtitleReport report = new SubtitleReport();
        File dir = new File(downloadPath);
        if (!dir.exists()) {
            return report;
        }
        List<PlayItem> items = playController.getPlayItem(dir, new HashSet<>(), 0);
        items.sort(Comparator.comparingDouble(PlayItem::getEpisode));

        int videoCount = 0;
        int withSubtitle = 0;
        List<Map<String, Object>> missingItems = new ArrayList<>();
        for (PlayItem item : items) {
            if (videoCount >= MAX_SCAN_VIDEOS) {
                break;
            }
            videoCount++;
            List<PlayItem.Subtitles> subtitles = item.getSubtitles();
            if (subtitles != null && !subtitles.isEmpty()) {
                withSubtitle++;
                continue;
            }
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("name", item.getName());
            missing.put("filename", item.getFilename());
            missing.put("episode", item.getEpisode());
            missing.put("formatSize", item.getFormatSize());
            missingItems.add(missing);
        }
        return report.setVideoCount(videoCount)
                .setWithSubtitle(withSubtitle)
                .setMissing(missingItems.size())
                .setMissingItems(missingItems);
    }

    /**
     * 就地附加字幕文件（文本内容）。
     * <p>
     * 与 {@link #attachSubtitleBytes(File, byte[], String, String)} 的区别仅在于入参形态：
     * 本方法把文本按 UTF-8 编码后落盘，适用于已在内存中解码为字符串的场景（如在线源抓取）。
     *
     * @param videoFile       视频文件
     * @param subtitleContent 字幕内容（ass/srt 文本）
     * @param ext             字幕扩展名（ass/srt）
     * @param languageTag     语言标签（如 chs/cht，可空），会追加到文件名
     * @return 写入的字幕文件
     */
    public File attachSubtitle(File videoFile, String subtitleContent, String ext, String languageTag) {
        if (StrUtil.isBlank(subtitleContent)) {
            throw new IllegalArgumentException("字幕内容为空");
        }
        return attachSubtitleBytes(videoFile, subtitleContent.getBytes(StandardCharsets.UTF_8), ext, languageTag);
    }

    /**
     * 就地附加字幕文件（原始字节，不做字符串编解码）。
     * <p>
     * 本地导入的字幕可能是 GBK 等非 UTF-8 编码，若先解码为字符串再按 UTF-8 重编码会破坏内容，
     * 因此这里直接写原始字节。
     * <p>
     * 字幕命名与视频主文件名保持一致（{@code 剧名 SxxExx[.{lang}].{ext}}），这样播放侧的
     * {@code getSubtitlesByVideo} 能按"主文件名前缀"匹配到它。
     *
     * @param videoFile   视频文件
     * @param content     字幕原始字节
     * @param ext         字幕扩展名（ass/srt/ssa/vtt/sub）
     * @param languageTag 语言标签（如 chs/cht，可空），会追加到文件名
     * @return 写入的字幕文件
     */
    public File attachSubtitleBytes(File videoFile, byte[] content, String ext, String languageTag) {
        if (videoFile == null || !videoFile.exists() || !videoFile.isFile()) {
            throw new IllegalArgumentException("视频文件不存在");
        }
        String normalizedExt = StrUtil.blankToDefault(ext, "ass").toLowerCase().replace(".", "");
        if (!SUBTITLE_EXT.contains(normalizedExt)) {
            throw new IllegalArgumentException("不支持的字幕格式: " + ext + "（支持 " + String.join("/", SUBTITLE_EXT) + "）");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("字幕内容为空");
        }
        if (content.length > MAX_SUBTITLE_BYTES) {
            throw new IllegalArgumentException("字幕内容超过 20MiB 上限");
        }

        File target = new File(videoFile.getParentFile(),
                expectedSubtitleName(FileUtil.mainName(videoFile), normalizedExt, languageTag));
        if (target.exists()) {
            // 覆盖前备份，避免误覆盖用户已有的字幕。
            // 备份统一收进视频同目录下的 sub_bak/（不存在则新建），
            // 而不是散落成 video.ass.bak 污染视频目录——那样既难辨认又容易被误当成有效字幕。
            File backupDir = new File(videoFile.getParentFile(), SUBTITLE_BACKUP_DIR);
            try {
                FileUtil.mkdir(backupDir);
                File backup = new File(backupDir, target.getName());
                FileUtil.copy(target, backup, true);
                log.info("已存在同名字幕, 覆盖前备份至 {}", backup.getAbsolutePath());
            } catch (Exception e) {
                log.warn("备份已有字幕失败: {}", ExceptionUtils.getMessage(e));
            }
        }
        // 原子写：先写临时文件再移动，避免写一半留下损坏字幕
        File temp = new File(videoFile.getParentFile(), target.getName() + ".temp");
        FileUtil.writeBytes(content, temp);
        FileUtil.move(temp, target, true);
        log.info("字幕已附加: {} ({} 字节)", target.getName(), content.length);
        return target;
    }

    /**
     * 规范化语言标签：只保留字母/数字/&/./-/_（如 chs、jpsc、chs&eng.simplified），
     * 剔除路径分隔符并折叠连续点，防止经接口传入的标签造成目录穿越。
     */
    private static String normalizeLangTag(String languageTag) {
        return StrUtil.blankToDefault(languageTag, "").trim()
                .replaceAll("[^A-Za-z0-9&._-]", "")
                .replaceAll("\\.{2,}", ".")
                .replaceAll("^\\.+|\\.+$", "");
    }

    /**
     * 计算字幕目标文件名：{@code 主名[.{lang}].{ext}}。
     * <p>
     * 预览与正式写入必须共用本方法，否则确认弹窗里展示的「改名后」会和实际落盘不一致。
     */
    private static String expectedSubtitleName(String mainName, String ext, String languageTag) {
        String tag = normalizeLangTag(languageTag);
        String suffix = StrUtil.isBlank(tag) ? "" : "." + tag;
        String normalizedExt = StrUtil.blankToDefault(ext, "ass").toLowerCase().replace(".", "");
        return mainName + suffix + "." + normalizedExt;
    }

    /**
     * 批量导入本地字幕。
     * <p>
     * 上传的字幕按「季 + 集」与订阅下载目录下<b>已重命名</b>的视频文件匹配，匹配成功后以
     * {@code 剧名 SxxExx[.{lang}].{ext}} 命名就地写入视频同目录，从而被播放侧的外挂字幕识别。
     * <p>
     * 匹配优先级：
     * <ol>
     *   <li>规范化 {@code SxxExx} 精确命中（如字幕 {@code 碧蓝之海 S03E15.cht.ass} → 视频 {@code 碧蓝之海 S03E15.mkv}）；</li>
     *   <li>仅解析出集数时，回落到「集数 → 视频」，<b>仅当该集数在目录内唯一</b>时才采用，避免跨季误匹配。</li>
     * </ol>
     * 语言后缀取字幕文件名中的语言标识（sc/tc/chs/cht/jp/jpsc/jptc 等，见
     * {@link FileUtils#extractSubtitleLangSuffix(String)}），无则命名为 {@code 剧名 SxxExx.ext}。
     * <p>
     * 本方法按当前下载器分派：OpenList/AList 离线订阅走网盘（
     * {@link #importCloudSubtitles(String, List, boolean)}），其余走本地目录；
     * 本地目录不存在时全部文件以统一原因失败返回，不抛异常。
     * <p>
     * 注意：<b>被显式传入目录的 File 重载仍只处理本地目录</b>，方便单测与自定义路径；
     * 网盘分支只在「按订阅下载路径分派」时生效。
     *
     * @param ani   订阅（其下载目录为导入目标）
     * @param files 上传的字幕文件
     * @return 逐文件明细与汇总计数
     */
    public ImportResult importLocalSubtitles(Ani ani, List<LocalSubtitleFile> files) {
        return importLocalSubtitles(ani, files, false);
    }

    /**
     * 批量导入本地字幕。
     *
     * @param dryRun 为 {@code true} 时只做匹配预览、<b>不写盘</b>，供导入前二次确认使用
     */
    public ImportResult importLocalSubtitles(Ani ani, List<LocalSubtitleFile> files, boolean dryRun) {
        if (ani == null) {
            return new ImportResult();
        }
        String downloadPath = downloadService.getDownloadPath(ani);
        if (isCloudDownloadTool()) {
            // 网盘订阅（OpenList/AList）：视频在云端，本地文件系统里根本没有这个目录，
            // 若照旧走 new File(path) 会一律得到「订阅下载目录不存在」——纯云端订阅的手动上传
            // 因此 100% 失败。这里改走网盘列举 + fs/put。
            return importCloudSubtitles(downloadPath, files, dryRun);
        }
        return importLocalSubtitles(new File(downloadPath), files, dryRun);
    }

    /**
     * 批量导入本地字幕（以目录为入口，便于复用与单测）。
     *
     * @param dir   视频所在目录（订阅下载目录）
     * @param files 上传的字幕文件
     */
    ImportResult importLocalSubtitles(File dir, List<LocalSubtitleFile> files) {
        return importLocalSubtitles(dir, files, false);
    }

    /**
     * 批量导入本地字幕（以目录为入口）。
     *
     * @param dryRun 为 {@code true} 时只做匹配预览、不写盘
     */
    ImportResult importLocalSubtitles(File dir, List<LocalSubtitleFile> files, boolean dryRun) {
        ImportResult result = new ImportResult();
        if (dir == null || files == null || files.isEmpty()) {
            return result;
        }
        result.setTotal(files.size());

        String path = dir.getPath();
        if (!dir.exists() || !dir.isDirectory()) {
            for (LocalSubtitleFile f : files) {
                result.getItems().add(importItem(f.getFilename(), null, null, null, "失败",
                        "订阅下载目录不存在: " + path));
            }
            return result.setFailed(files.size());
        }

        VideoIndex<File> index = buildVideoIndex(dir);
        int success = 0;
        for (LocalSubtitleFile f : files) {
            String originalName = StrUtil.blankToDefault(f.getFilename(), "");
            String ext = FileUtil.extName(originalName).toLowerCase(Locale.ROOT);

            // 1) 扩展名白名单
            if (!SUBTITLE_EXT.contains(ext)) {
                result.getItems().add(importItem(originalName, null, null, null, "失败",
                        "不支持的字幕格式: " + (StrUtil.isBlank(ext) ? "(无扩展名)" : ext)));
                continue;
            }
            // 2) 内容与大小
            byte[] content = f.getContent();
            if (content == null || content.length == 0) {
                result.getItems().add(importItem(originalName, null, null, null, "失败", "字幕内容为空"));
                continue;
            }
            if (content.length > MAX_SUBTITLE_BYTES) {
                result.getItems().add(importItem(originalName, null, null, null, "失败", "字幕超过 20MiB 上限"));
                continue;
            }

            // 3) 解析语言后缀 + 季集
            String langTag = StrUtil.blankToDefault(FileUtils.extractSubtitleLangSuffix(originalName), "");
            int[] se = AssrtSubtitleProvider.extractSeasonEpisode(originalName);
            File video = matchVideo(index, se);
            if (video == null) {
                result.getItems().add(importItem(originalName, null, null, langTag, "失败",
                        unmatchedReason(index, se)));
                if (!dryRun) {
                    SubtitleMatchLog.record(new SubtitleMatchLogEntry(
                            System.currentTimeMillis(), "", originalName, "",
                            se[0] >= 0 ? se[0] : null, "未命中", langTag));
                }
                continue;
            }

            // 4) 预览：只算出目标文件名，不落盘
            String targetName = expectedSubtitleName(FileUtil.mainName(video), ext, langTag);
            if (dryRun) {
                success++;
                File target = new File(video.getParentFile(), targetName);
                result.getItems().add(importItem(originalName, targetName, video.getName(), langTag, "已匹配",
                        target.exists() ? "同名文件已存在，导入时会先备份到 sub_bak/" : ""));
                continue;
            }

            // 5) 按标准命名写入
            try {
                File target = attachSubtitleBytes(video, content, ext, langTag);
                success++;
                result.getItems().add(importItem(originalName, target.getName(), video.getName(), langTag, "已匹配", ""));
                SubtitleMatchLog.record(new SubtitleMatchLogEntry(
                        System.currentTimeMillis(), video.getName(), originalName, target.getName(),
                        se[0] >= 0 ? se[0] : null, "已匹配", langTag));
            } catch (Exception e) {
                result.getItems().add(importItem(originalName, null, video.getName(), langTag, "失败",
                        ExceptionUtils.getMessage(e)));
            }
        }
        return result.setSuccess(success).setFailed(result.getTotal() - success);
    }

    /* ==================== 网盘（OpenList/AList）手动导入 ==================== */

    /**
     * 批量导入字幕到<b>网盘</b>订阅目录（OpenList/AList 离线下载的订阅）。
     * <p>
     * 为什么不能复用本地实现：网盘上的「目录」在本地文件系统里不存在，
     * {@code new File(path).exists()} 恒为 {@code false}，于是每个文件都被判成
     * 「订阅下载目录不存在」——纯云端订阅手动上传字幕因此 100% 失败。
     * <p>
     * 匹配规则与本地<b>完全一致</b>（同一份索引结构、同一个 {@link #matchVideo} 歧义判定、
     * 同一个 {@link #expectedSubtitleName} 命名），区别只在两侧 IO：
     * <ul>
     *   <li>列举：{@code fs/list}，只读<b>一层</b>（与射手网计划一致，避免一次导入把
     *       目录树整个递归一遍）；顶层没有视频但有子目录时才递归兜底一次；</li>
     *   <li>写入：{@code fs/put}；同名文件存在时先 {@code fs/move} 到 {@code sub_bak/}，
     *       与本地「覆盖前备份」的语义对齐——搬不动就直接失败，绝不无备份覆盖。</li>
     * </ul>
     *
     * @param cloudDir 网盘目录（订阅下载目录）
     * @param files    上传的字幕
     * @param dryRun   为 {@code true} 时只做匹配预览、不写网盘
     */
    ImportResult importCloudSubtitles(String cloudDir, List<LocalSubtitleFile> files, boolean dryRun) {
        ImportResult result = new ImportResult();
        if (files == null || files.isEmpty()) {
            return result;
        }
        result.setTotal(files.size());

        String dir = normalizeCloudDir(cloudDir);
        if (StrUtil.isBlank(dir)) {
            return failAllImport(result, files, "订阅下载目录为空（网盘路径未配置）");
        }

        OpenListApi api = new OpenListApi();
        api.setConfig(ConfigUtil.CONFIG);

        List<OpenListFileInfo> entries;
        try {
            entries = api.fsListStrict(dir, true);
        } catch (Exception e) {
            return failAllImport(result, files, cloudListFailureReason(api, dir, e));
        }
        if (listCloudVideos(entries).isEmpty() && hasSubDirectory(entries)) {
            // 少数人把每集放进独立子目录：顶层没有视频时递归兜底一次。
            // 顶层压根没有子目录就不发这次请求。
            entries = mergeCloudEntries(entries, safeFindFiles(api, dir));
        }
        if (listCloudVideos(entries).isEmpty()) {
            // 目录读到了、也确实存在，就是没有视频：不能报「目录不存在」误导排查方向
            return failAllImport(result, files,
                    "网盘目录下没有视频文件（目录可读取）: " + dir + "（共 " + entries.size() + " 个条目）");
        }
        return importCloudSubtitles(dir, entries, files, dryRun, api);
    }

    /**
     * 云端导入的核心：给定目录已列举的条目做匹配（纯逻辑，便于单测直接喂数据）。
     *
     * @param cloudDir 网盘目录（已规范化）
     * @param entries  该目录下的条目（{@code getPath()} 为其父目录）
     * @param files    上传的字幕
     * @param dryRun   只预览不写
     * @param api      网盘客户端（写入时才真正用到）
     */
    ImportResult importCloudSubtitles(String cloudDir, List<OpenListFileInfo> entries,
                                      List<LocalSubtitleFile> files, boolean dryRun, OpenListApi api) {
        ImportResult result = new ImportResult();
        if (files == null || files.isEmpty()) {
            return result;
        }
        result.setTotal(files.size());

        VideoIndex<CloudVideo> index = buildCloudVideoIndex(entries);
        Set<String> existing = cloudFileNames(entries);
        int success = 0;
        for (LocalSubtitleFile f : files) {
            String originalName = StrUtil.blankToDefault(f.getFilename(), "");
            String ext = FileUtil.extName(originalName).toLowerCase(Locale.ROOT);

            // 1) 扩展名白名单
            if (!SUBTITLE_EXT.contains(ext)) {
                result.getItems().add(importItem(originalName, null, null, null, "失败",
                        "不支持的字幕格式: " + (StrUtil.isBlank(ext) ? "(无扩展名)" : ext)));
                continue;
            }
            // 2) 内容与大小
            byte[] content = f.getContent();
            if (content == null || content.length == 0) {
                result.getItems().add(importItem(originalName, null, null, null, "失败", "字幕内容为空"));
                continue;
            }
            if (content.length > MAX_SUBTITLE_BYTES) {
                result.getItems().add(importItem(originalName, null, null, null, "失败", "字幕超过 20MiB 上限"));
                continue;
            }

            // 3) 解析语言后缀 + 季集，定位到唯一视频
            String langTag = StrUtil.blankToDefault(FileUtils.extractSubtitleLangSuffix(originalName), "");
            int[] se = AssrtSubtitleProvider.extractSeasonEpisode(originalName);
            CloudVideo video = matchVideo(index, se);
            if (video == null) {
                result.getItems().add(importItem(originalName, null, null, langTag, "失败",
                        unmatchedReason(index, se)));
                if (!dryRun) {
                    SubtitleMatchLog.record(new SubtitleMatchLogEntry(
                            System.currentTimeMillis(), "", originalName, "",
                            se[0] >= 0 ? se[0] : null, "未命中", langTag));
                }
                continue;
            }

            // 4) 预览：只算目标名，不写网盘
            String targetName = expectedSubtitleName(FileUtil.mainName(video.name()), ext, langTag);
            if (dryRun) {
                success++;
                result.getItems().add(importItem(originalName, targetName, video.name(), langTag, "已匹配",
                        existing.contains(targetName) ? "同名文件已存在，导入时会先备份到 sub_bak/" : ""));
                continue;
            }

            // 5) 写入：先备份同名旧文件，再上传
            try {
                if (existing.contains(targetName)) {
                    backupCloudSubtitle(api, video.dir(), targetName);
                }
                api.fsPut(video.dir(), targetName, content);
                success++;
                existing.add(targetName);
                result.getItems().add(importItem(originalName, targetName, video.name(), langTag, "已匹配", ""));
                SubtitleMatchLog.record(new SubtitleMatchLogEntry(
                        System.currentTimeMillis(), video.name(), originalName, targetName,
                        se[0] >= 0 ? se[0] : null, "已匹配", langTag));
                log.info("云端字幕已写入: {}/{} ({} 字节)", video.dir(), targetName, content.length);
            } catch (Exception e) {
                result.getItems().add(importItem(originalName, null, video.name(), langTag, "失败",
                        ExceptionUtils.getMessage(e)));
            }
        }
        return result.setSuccess(success).setFailed(result.getTotal() - success);
    }

    /**
     * 建立云端视频索引（与 {@link #buildVideoIndex} 同一套键，保证歧义判定一致）
     */
    private VideoIndex<CloudVideo> buildCloudVideoIndex(List<OpenListFileInfo> entries) {
        VideoIndex<CloudVideo> index = new VideoIndex<>();
        for (CloudVideo video : listCloudVideos(entries)) {
            String key = canonicalSeasonEpisode(video.name());
            if (key != null) {
                index.bySeasonEpisode.putIfAbsent(key, video);
            }
            int[] se = AssrtSubtitleProvider.extractSeasonEpisode(video.name());
            if (se[1] >= 0) {
                index.byEpisode.computeIfAbsent(se[1], k -> new ArrayList<>()).add(video);
            }
        }
        return index;
    }

    private static List<CloudVideo> listCloudVideos(List<OpenListFileInfo> entries) {
        List<CloudVideo> videos = new ArrayList<>();
        if (entries == null) {
            return videos;
        }
        for (OpenListFileInfo f : entries) {
            if (Boolean.TRUE.equals(f.getIsDir()) || StrUtil.isBlank(f.getName())) {
                continue;
            }
            if (FileUtils.isVideoFormat(f.getName())) {
                videos.add(new CloudVideo(f.getName(), StrUtil.blankToDefault(f.getPath(), "")));
            }
        }
        return videos;
    }

    private static Set<String> cloudFileNames(List<OpenListFileInfo> entries) {
        Set<String> names = new HashSet<>();
        if (entries == null) {
            return names;
        }
        for (OpenListFileInfo f : entries) {
            if (!Boolean.TRUE.equals(f.getIsDir()) && StrUtil.isNotBlank(f.getName())) {
                names.add(f.getName());
            }
        }
        return names;
    }

    private static boolean hasSubDirectory(List<OpenListFileInfo> entries) {
        for (OpenListFileInfo f : entries) {
            if (Boolean.TRUE.equals(f.getIsDir())) {
                return true;
            }
        }
        return false;
    }

    private static List<OpenListFileInfo> safeFindFiles(OpenListApi api, String dir) {
        try {
            return api.findFilesStrict(dir);
        } catch (Exception e) {
            log.warn("递归列举网盘目录失败 {}: {}", dir, ExceptionUtils.getMessage(e));
            return List.of();
        }
    }

    /**
     * 合并"顶层列举"与"递归兜底列举"：同一 {@code 目录/名字} 只保留一份（先到优先）
     */
    private static List<OpenListFileInfo> mergeCloudEntries(List<OpenListFileInfo> first,
                                                            List<OpenListFileInfo> second) {
        Map<String, OpenListFileInfo> merged = new LinkedHashMap<>();
        for (OpenListFileInfo f : first) {
            merged.put(cloudEntryKey(f), f);
        }
        for (OpenListFileInfo f : second) {
            merged.putIfAbsent(cloudEntryKey(f), f);
        }
        return new ArrayList<>(merged.values());
    }

    private static String cloudEntryKey(OpenListFileInfo f) {
        return StrUtil.blankToDefault(f.getPath(), "") + "/" + StrUtil.blankToDefault(f.getName(), "");
    }

    /**
     * 覆盖前把同名旧字幕搬到 {@code 目录/sub_bak/}。
     * <p>
     * 任何一步失败都往上抛：宁可这一条导入失败，也不能在没有备份的情况下覆盖用户字幕。
     */
    private void backupCloudSubtitle(OpenListApi api, String dir, String targetName) {
        String backupDir = dir + "/" + SUBTITLE_BACKUP_DIR;
        if (!cloudDirExists(api, backupDir)) {
            api.mkdir(backupDir);
        }
        for (OpenListFileInfo b : api.fsList(backupDir, true)) {
            if (!Boolean.TRUE.equals(b.getIsDir()) && targetName.equals(b.getName())) {
                // 上一轮遗留的同名备份：先删掉，否则 fs/move 会因目标已存在而失败
                api.fsRemove(backupDir, List.of(targetName));
                break;
            }
        }
        api.fsMove(dir, backupDir, List.of(targetName));
        log.info("云端已存在同名字幕, 覆盖前备份至 {}/{}", backupDir, targetName);
    }

    /**
     * 网盘目录是否存在（严格版：目录不存在是<b>确认没有</b>，其它失败按"存在"处理）
     */
    private boolean cloudDirExists(OpenListApi api, String dir) {
        try {
            api.fsListStrict(dir, false);
            return true;
        } catch (OpenListApi.OpenListDirNotFoundException e) {
            return false;
        } catch (Exception e) {
            // 判定不了就不 mkdir：多跑一次 move 顶多失败，误建目录反而会留下垃圾
            return true;
        }
    }

    /**
     * 网盘列举失败的可行动原因：区分「目录确实不存在」与「网盘本身读不了」。
     * <p>
     * 目录不存在时顺带列一次父目录，把<b>实际存在的子目录名</b>回给用户——
     * 季目录名差一个空格/全角括号这种问题，看报错就能对上，不用再去别处翻。
     */
    private String cloudListFailureReason(OpenListApi api, String dir, Exception e) {
        if (e instanceof OpenListApi.OpenListDirNotFoundException) {
            return "网盘订阅下载目录不存在: " + dir + cloudSiblingHint(api, dir);
        }
        String message = ExceptionUtils.getMessage(e);
        return "网盘目录读取失败: " + dir + "（" + StrUtil.blankToDefault(message, "未知错误") + "）";
    }

    private String cloudSiblingHint(OpenListApi api, String dir) {
        int idx = dir.lastIndexOf('/');
        if (idx <= 0) {
            return "";
        }
        String parent = dir.substring(0, idx);
        try {
            List<String> names = new ArrayList<>();
            for (OpenListFileInfo f : api.fsListStrict(parent, false)) {
                if (Boolean.TRUE.equals(f.getIsDir()) && StrUtil.isNotBlank(f.getName())) {
                    names.add(f.getName());
                    if (names.size() >= 8) {
                        break;
                    }
                }
            }
            if (names.isEmpty()) {
                return "";
            }
            return "（父目录 " + parent + " 下实际有: " + String.join(" / ", names)
                    + "，请核对订阅下载路径 / 季目录名）";
        } catch (Exception ignore) {
            return "";
        }
    }

    private ImportResult failAllImport(ImportResult result, List<LocalSubtitleFile> files, String reason) {
        for (LocalSubtitleFile f : files) {
            result.getItems().add(importItem(f.getFilename(), null, null, null, "失败", reason));
        }
        return result.setFailed(files.size());
    }

    /**
     * 当前下载器是否为网盘（OpenList/AList）。
     * <p>
     * 用能力查询而非配置字符串比较（见 {@link TorrentUtil#isOfflineTool()}）：
     * 配置里既可能是 {@code OpenList} 也可能是 {@code Alist}，写死一个字符串就会漏判另一半。
     */
    private static boolean isCloudDownloadTool() {
        try {
            return TorrentUtil.isOfflineTool();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 规范化网盘目录：反斜杠转正斜杠、折叠重复分隔符、去掉末尾斜杠（保留根目录的 "/"）。
     */
    static String normalizeCloudDir(String path) {
        String p = StrUtil.blankToDefault(path, "").replace('\\', '/').trim();
        p = p.replaceAll("/{2,}", "/");
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /* ==================== 射手网（ASSRT）手动获取 ==================== */

    /**
     * 射手网字幕<b>搜索</b>（只读，不写盘、不下载）。
     * <p>
     * 只发<b>一次</b> {@code sub/search} 请求，关键词优先取番剧<b>英文标题</b>
     * （见 {@link #searchKeyword}）——ASSRT 的条目名以英文/原文为主，用中文标题常常搜不到。
     * 不再按「每个视频 × 精确/宽泛两档」拆分请求：那会在一次批量匹配里打满配额
     * （默认 5 次/分钟）并触发 {@code 30900}。
     * <p>
     * 候选条目原样返回给用户自行挑选，后端不做任何自动挑选——避免自动匹配到错误字幕。
     * 结果写入短期缓存，用户选中后由
     * {@link #planFetchFromAssrt(Ani, String, int)} 取回并进入下载流程。
     *
     * @param ani 订阅
     * @return 搜索结果（含 searchId 与候选列表）；未配置 Token 或搜索失败时候选为空
     */
    public AssrtSearchResult searchAssrt(Ani ani) {
        if (ani == null) {
            return new AssrtSearchResult("", "", List.of());
        }
        String token = StrUtil.blankToDefault(ConfigUtil.CONFIG.getAssrtToken(), "");
        String lang = StrUtil.blankToDefault(ConfigUtil.CONFIG.getSubtitleLang(), "chs");
        String keyword = searchKeyword(ani);
        if (StrUtil.isBlank(token) || StrUtil.isBlank(keyword)) {
            return new AssrtSearchResult("", keyword, List.of());
        }

        List<AssrtSubtitleItem> items = assrtSubtitleProvider.searchItems(token, keyword, lang);
        List<Map<String, Object>> candidates = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            AssrtSubtitleItem item = items.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("title", item.getTitle());
            row.put("lang", item.getLang());
            row.put("archive", item.isArchive());
            // 未知文件数（需选中后调 detail 补全）用 -1 表示，前端据此显示「需解析」
            row.put("fileCount", item.getFileCount());
            candidates.add(row);
        }

        String searchId = UUID.randomUUID().toString();
        cacheSearch(searchId, new CachedSearch(ani.getId(), keyword, items));
        return new AssrtSearchResult(searchId, keyword, candidates);
    }

    /**
     * 按用户选中的候选条目生成射手网字幕获取计划（<b>只读，不写盘</b>）。
     * <p>
     * 流程：取回搜索结果 → 解析选中条目为具体字幕文件（内联文件直接用，否则调一次
     * {@code sub/detail}）→ 遍历订阅目录内<b>尚无字幕</b>的视频，逐个从候选中挑选最匹配的一条
     * 并下载内容暂存内存，返回「改名前 / 改名后 / 对应的视频」预览供用户二次确认。
     *
     * @param ani       订阅
     * @param searchId  {@link #searchAssrt} 返回的搜索结果 id
     * @param itemIndex 用户选中的候选下标
     * @return 匹配计划（可能为空计划）
     */
    public FetchPlan planFetchFromAssrt(Ani ani, String searchId, int itemIndex) {
        FetchPlan plan = new FetchPlan(UUID.randomUUID().toString(), ani == null ? null : ani.getId(),
                ani == null ? null : ani.getTitle());
        if (ani == null) {
            return plan;
        }
        CachedSearch cached = takeSearch(searchId);
        if (cached == null) {
            throw new IllegalStateException("搜索结果已过期，请重新搜索");
        }
        if (itemIndex < 0 || itemIndex >= cached.items.size()) {
            throw new IllegalArgumentException("候选序号超出范围");
        }

        String token = StrUtil.blankToDefault(ConfigUtil.CONFIG.getAssrtToken(), "");
        String lang = StrUtil.blankToDefault(ConfigUtil.CONFIG.getSubtitleLang(), "chs");
        AssrtSubtitleItem selected = cached.items.get(itemIndex);
        List<SubtitleCandidate> candidates = assrtSubtitleProvider.resolveCandidates(token, selected, lang);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("所选字幕条目没有可下载的文件，请换一个候选");
        }

        // (P1-17) 计划内字节缓存：合集压缩包候选会在下面的逐视频循环里被反复取用，
        // 不缓存的话每个视频都要把整包重新下载一遍（一季 12~24 集 = 同样一次下载做 12~24 次）。
        // 生命周期绑定"本次计划构建"，构建结束即释放，不会跨计划驻留大对象。
        Map<String, byte[]> bytesCache = new HashMap<>();
        if (isCloudDownloadTool()) {
            // 网盘离线下载（OpenList/AList）：视频落在云端，字幕需上传到云端同目录
            planCloudFetch(ani, plan, candidates, lang, bytesCache);
        } else {
            // 其余下载器（qBittorrent / Transmission / aria2 / 本地路径等）均视为本地下载
            planLocalFetch(ani, plan, candidates, lang, bytesCache);
        }
        cachePlan(plan);
        return plan;
    }

    /**
     * 消费计划并落盘。
     * <p>
     * 仅写入命中项；未命中项原样保留在结果中，便于用户核对失败原因。
     *
     * @param plan 由 {@link #takePlan(String)} 取出的计划
     */
    public ImportResult applyFetchPlan(FetchPlan plan) {
        ImportResult result = new ImportResult();
        if (plan == null || plan.getItems().isEmpty()) {
            return result;
        }
        List<FetchPlanItem> items = plan.getItems();
        result.setTotal(items.size());
        OpenListApi api = null;
        int success = 0;
        for (FetchPlanItem item : items) {
            if (!item.isMatched()) {
                result.getItems().add(item.getPreview());
                continue;
            }
            try {
                if (StrUtil.isNotBlank(item.getCloudDir())) {
                    if (api == null) {
                        api = new OpenListApi();
                        api.setConfig(ConfigUtil.CONFIG);
                    }
                    api.fsPut(item.getCloudDir(), item.getRenamedName(), item.getContent());
                } else {
                    attachSubtitleBytes(new File(item.getLocalVideoPath()), item.getContent(),
                            item.getExt(), item.getLangTag());
                }
                success++;
                result.getItems().add(item.getPreview());
                SubtitleMatchLog.record(new SubtitleMatchLogEntry(
                        System.currentTimeMillis(), item.getVideoName(), item.getOriginalName(),
                        item.getRenamedName(), item.getSeason(), "已匹配", item.getLangTag()));
                log.info("射手网字幕已写入: {} -> {}", item.getVideoName(), item.getRenamedName());
            } catch (Exception e) {
                result.getItems().add(importItem(item.getOriginalName(), null, item.getVideoName(),
                        item.getLangTag(), "失败", ExceptionUtils.getMessage(e)));
            }
        }
        return result.setSuccess(success).setFailed(items.size() - success);
    }

    /**
     * 取出并移除计划（一次性消费）。计划不存在或已过期返回 {@code null}。
     */
    public static synchronized FetchPlan takePlan(String planId) {
        if (StrUtil.isBlank(planId)) {
            return null;
        }
        purgeExpiredPlans();
        FetchPlan plan = PLAN_CACHE.remove(planId);
        if (plan == null || System.currentTimeMillis() - plan.getCreatedAt() > PLAN_TTL_MS) {
            return null;
        }
        return plan;
    }

    private static synchronized void cachePlan(FetchPlan plan) {
        // TTL 从"计划构建完成"开始算, 而不是扫描开始(见 FetchPlan.createdAt 注释)
        plan.stampCreatedAt();
        purgeExpiredPlans();
        PLAN_CACHE.put(plan.getPlanId(), plan);
    }

    private static void purgeExpiredPlans() {
        long now = System.currentTimeMillis();
        PLAN_CACHE.entrySet().removeIf(e -> now - e.getValue().getCreatedAt() > PLAN_TTL_MS);
    }

    private static synchronized void cacheSearch(String searchId, CachedSearch search) {
        purgeExpiredSearches();
        SEARCH_CACHE.put(searchId, search);
    }

    /**
     * 取出搜索结果（可重复取用：用户可能反复比较不同候选后才生成计划）。
     * 不存在或已过期返回 {@code null}。
     */
    private static synchronized CachedSearch takeSearch(String searchId) {
        if (StrUtil.isBlank(searchId)) {
            return null;
        }
        purgeExpiredSearches();
        CachedSearch search = SEARCH_CACHE.get(searchId);
        if (search == null || System.currentTimeMillis() - search.createdAt > SEARCH_TTL_MS) {
            return null;
        }
        return search;
    }

    private static void purgeExpiredSearches() {
        long now = System.currentTimeMillis();
        SEARCH_CACHE.entrySet().removeIf(e -> now - e.getValue().createdAt > SEARCH_TTL_MS);
    }

    /**
     * 选择射手网搜索关键词：<b>优先英文标题</b>。
     * <p>
     * ASSRT 的条目名以英文/原文为主，用中文标题常常搜不到，故按以下优先级取第一个可用者：
     * <ol>
     *   <li>TMDB 名称（含拉丁字母，通常是英文标题）；</li>
     *   <li>订阅标题（含拉丁字母，如 RSS 里的英文原名）；</li>
     *   <li>日文原名——ASSRT 对日文名收录较好，是中文标题之外的最佳兜底；</li>
     *   <li>TMDB 名称 / 订阅标题（无拉丁字母时的兜底）。</li>
     * </ol>
     * 关键词会先剔除 TMDB 附加的年份与 id 后缀（如 {@code High School DxD (2018) {tmdb-12345}}），
     * 否则会把搜索范围收得过窄。
     */
    static String searchKeyword(Ani ani) {
        if (ani == null) {
            return "";
        }
        String tmdb = cleanKeyword(ani.getThemoviedbName());
        String title = cleanKeyword(ani.getTitle());
        String jp = cleanKeyword(ani.getJpTitle());
        if (containsLatin(tmdb)) {
            return tmdb;
        }
        if (containsLatin(title)) {
            return title;
        }
        if (StrUtil.isNotBlank(jp)) {
            return jp;
        }
        if (StrUtil.isNotBlank(tmdb)) {
            return tmdb;
        }
        return title;
    }

    /**
     * 清洗搜索关键词：去掉 TMDB 附加的 {@code {tmdb-12345}} 与年份括号，折叠多余空白。
     */
    private static String cleanKeyword(String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return "";
        }
        return keyword.replaceAll("\\{tmdb-\\d+\\}", " ")
                .replaceAll("\\[\\s*\\d{4}\\s*\\]", " ")
                .replaceAll("\\(\\s*\\d{4}\\s*\\)", " ")
                .replaceAll("[_\\-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean containsLatin(String s) {
        if (StrUtil.isBlank(s)) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return true;
            }
        }
        return false;
    }

    private void planLocalFetch(Ani ani, FetchPlan plan, List<SubtitleCandidate> candidates, String lang,
                               Map<String, byte[]> bytesCache) {
        File dir = new File(downloadService.getDownloadPath(ani));
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }
        for (File video : listVideoFiles(dir)) {
            if (hasLocalSubtitle(video)) {
                log.info("本地已有字幕，跳过: {}", video.getName());
                continue;
            }
            plan.getItems().add(planOne(ani, video.getName(), FileUtil.mainName(video), null, video, null,
                    candidates, lang, bytesCache));
        }
    }

    private void planCloudFetch(Ani ani, FetchPlan plan, List<SubtitleCandidate> candidates, String lang,
                                Map<String, byte[]> bytesCache) {
        OpenListApi api = new OpenListApi();
        api.setConfig(ConfigUtil.CONFIG);
        String cloudDir = downloadService.getDownloadPath(ani);
        List<OpenListFileInfo> files = api.fsList(cloudDir, true);
        for (OpenListFileInfo f : files) {
            if (Boolean.TRUE.equals(f.getIsDir())) {
                continue;
            }
            if (!FileUtils.isVideoFormat(f.getName())) {
                continue;
            }
            String mainName = FileUtil.mainName(f.getName());
            if (hasCloudSubtitle(f.getPath(), mainName, api)) {
                log.info("云端已有字幕，跳过: {}", f.getName());
                continue;
            }
            plan.getItems().add(planOne(ani, f.getName(), mainName, f.getPath(), null, api, candidates, lang, bytesCache));
        }
    }

    /**
     * 为单个视频生成计划条目：从<b>用户选中的候选</b>里挑出匹配该视频的一条并下载内容
     * （暂存内存，不落盘）。
     * <p>
     * 单文件候选先做季/集门槛校验（{@link AssrtSubtitleProvider#matchesEpisode}），
     * 避免把第 3 集的字幕挂到第 5 集；压缩包候选交给解包阶段按目标集数挑选。
     * 候选全部不匹配时返回未命中条目，让用户换一个候选或改用手动上传。
     */
    private FetchPlanItem planOne(Ani ani, String videoName, String mainName, String cloudDir,
                                  File localVideo, OpenListApi api,
                                  List<SubtitleCandidate> candidates, String lang,
                                  Map<String, byte[]> bytesCache) {
        if (candidates == null || candidates.isEmpty()) {
            return notMatched(videoName, "所选字幕没有可下载的文件，建议重新搜索或手动上传");
        }
        int[] se = AssrtSubtitleProvider.extractSeasonEpisode(videoName);
        Integer targetSeason = se[0] >= 0 ? se[0] : null;
        Integer targetEp = se[1] >= 0 ? se[1] : null;

        for (SubtitleCandidate c : candidates) {
            if (!c.isArchive() && !AssrtSubtitleProvider.matchesEpisode(c.getFileName(), "", targetSeason, targetEp)) {
                // 单文件候选与目标视频的季/集不符，跳过（压缩包不在此判定）
                continue;
            }
            try {
                SubtitlePick pick = assrtSubtitleProvider.download(c, lang, targetSeason, targetEp, videoName, ani,
                        bytesCache);
                if (pick == null || pick.getContent() == null || pick.getContent().length == 0) {
                    continue;
                }
                byte[] data = pick.getContent();
                String originalName = StrUtil.blankToDefault(pick.getOriginalName(), c.getFileName());
                // 扩展名必须取自"真正会被写出的那个文件"。
                // 压缩包候选(zip/rar)的 c.getExt() 是压缩包后缀，直接用会写出 剧名 SxxExx.zip；
                // pick.getOriginalName() 是解压后挑中的内层字幕名，从这里取才是 ass/sub 等真实后缀。
                String ext = StrUtil.blankToDefault(FileUtil.extName(originalName), "ass").toLowerCase();
                String langTag = resolveLangTag(originalName, c);
                String renamedName = expectedSubtitleName(mainName, ext, langTag);
                Map<String, Object> preview = importItem(originalName, renamedName, videoName, langTag, "已匹配", "");
                return new FetchPlanItem(preview, data, videoName, originalName, renamedName, langTag, ext,
                        pick.getResolvedSeason(),
                        localVideo == null ? null : localVideo.getAbsolutePath(), cloudDir);
            } catch (Exception ex) {
                log.warn("射手网候选写入准备失败 {}: {}", c.getFileName(), ExceptionUtils.getMessage(ex));
            }
        }
        return notMatched(videoName, "所选字幕无法匹配该视频（集数/季数不符），建议换一个候选或手动上传");
    }

    /**
     * 未命中条目：保留视频名与原因，便于用户核对并改用手动上传。
     */
    private static FetchPlanItem notMatched(String videoName, String reason) {
        Map<String, Object> preview = importItem("", null, videoName, "", "未命中", reason);
        return new FetchPlanItem(preview, null, videoName, "", null, "", "", null, null, null);
    }

    /**
     * 组装单条导入/预览明细（字段与前端表格一一对应）。
     * <p>
     * originalName = 改名前，renamedName = 改名后，videoName = 对应的视频。
     */
    private static Map<String, Object> importItem(String originalName, String renamedName, String videoName,
                                                  String lang, String status, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("originalName", originalName);
        item.put("renamedName", renamedName);
        item.put("videoName", videoName);
        item.put("lang", lang);
        item.put("status", status);
        item.put("reason", reason);
        return item;
    }

    /**
     * 未命中时给出可行动的原因说明
     */
    private static String unmatchedReason(VideoIndex<?> index, int[] se) {
        if (se[1] < 0) {
            return "无法从字幕文件名解析集数（可改名为「剧名 SxxExx.ass」后重试）";
        }
        if (se[0] >= 0) {
            return "未找到对应视频 S" + se[0] + "E" + se[1];
        }
        List<?> candidates = index.byEpisode.get(se[1]);
        if (candidates != null && candidates.size() > 1) {
            return "第 " + se[1] + " 集存在多个候选视频，集数不唯一（请在字幕文件名中补上 Sxx 季数）";
        }
        return "未找到第 " + se[1] + " 集视频";
    }

    /**
     * 建立目录内视频索引（递归、限量，与扫描逻辑一致）
     */
    private VideoIndex<File> buildVideoIndex(File dir) {
        VideoIndex<File> index = new VideoIndex<>();
        for (File video : listVideoFiles(dir)) {
            String name = video.getName();
            String key = canonicalSeasonEpisode(name);
            if (key != null) {
                index.bySeasonEpisode.putIfAbsent(key, video);
            }
            int[] se = AssrtSubtitleProvider.extractSeasonEpisode(name);
            if (se[1] >= 0) {
                index.byEpisode.computeIfAbsent(se[1], k -> new ArrayList<>()).add(video);
            }
        }
        return index;
    }

    /**
     * 规范化季集键（{@code s3e15}）。
     * <p>
     * 与 {@link #matchVideo} 必须共用同一套解析（{@link AssrtSubtitleProvider#extractSeasonEpisode}），
     * 否则 {@code S02E05} 会被建索引成 {@code s02e05} 却按 {@code s2e5} 查找，永远匹配不上。
     * 季、集都解析不出时返回 {@code null}（由「集数兜底」处理）。
     */
    private static String canonicalSeasonEpisode(String name) {
        int[] se = AssrtSubtitleProvider.extractSeasonEpisode(name);
        if (se[0] >= 0 && se[1] >= 0) {
            return "s" + se[0] + "e" + se[1];
        }
        return null;
    }

    /**
     * 把字幕定位到唯一视频：
     * <ol>
     *   <li>季集键精确命中；</li>
     *   <li>回落「集数 → 视频」，仅当该集数在目录内唯一时采用。</li>
     * </ol>
     * 返回 {@code null} 表示未命中或存在歧义（歧义时宁可失败也不跨季误配）。
     */
    private static <T> T matchVideo(VideoIndex<T> index, int[] se) {
        int episode = se[1];
        if (episode < 0) {
            return null;
        }
        if (se[0] >= 0) {
            T exact = index.bySeasonEpisode.get("s" + se[0] + "e" + episode);
            if (exact != null) {
                return exact;
            }
        }
        List<T> candidates = index.byEpisode.get(episode);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * 解析字幕语言后缀。
     * <p>
     * 优先取「字幕源文件名」里的语言标识（sc / tc / chs / cht / jp / jpsc / jptc 等），
     * 保留原始标识（统一小写）——例如源文件 {@code ...碧蓝之海 3 - 15.cht.ass} 得到 {@code cht}；
     * 源文件名未带语言标识时，回落 ASSRT 接口返回的 lang 字段；二者皆无则返回空串，
     * 此时字幕命名为 {@code 剧名 SxxExx.ext}（无语言后缀）。
     *
     * @param originalName 字幕源文件名（压缩包内条目名或候选文件名）
     * @param c            ASSRT 候选（提供接口 lang 字段兜底）
     */
    String resolveLangTag(String originalName, SubtitleCandidate c) {
        String fromName = FileUtils.extractSubtitleLangSuffix(originalName);
        if (StrUtil.isNotBlank(fromName)) {
            return fromName;
        }
        return StrUtil.blankToDefault(c.getLang(), "");
    }

    private boolean hasLocalSubtitle(File videoFile) {
        File dir = videoFile.getParentFile();
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        String mainName = FileUtil.mainName(videoFile);
        File[] list = dir.listFiles();
        if (list == null) {
            return false;
        }
        for (File f : list) {
            if (f.isFile() && subtitleMatches(mainName, f.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCloudSubtitle(String cloudDir, String mainName, OpenListApi api) {
        List<OpenListFileInfo> files = api.fsList(cloudDir, false);
        for (OpenListFileInfo f : files) {
            if (Boolean.TRUE.equals(f.getIsDir())) {
                continue;
            }
            if (subtitleMatches(mainName, f.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断文件名是否为指定视频的字幕：以 "主名." 开头且为字幕格式。
     * 兼容 mainName.ass 与 mainName.chs.ass（带语言标签）两种命名，
     * 因为 FileUtil.mainName 只会剥离最后一个扩展名，无法直接用于含语言标签的命名。
     */
    private boolean subtitleMatches(String mainName, String fileName) {
        if (!FileUtils.isSubtitleFormat(fileName)) {
            return false;
        }
        String base = mainName.toLowerCase();
        String lower = fileName.toLowerCase();
        return lower.startsWith(base + ".");
    }

    /**
     * 递归收集目录下的视频文件（限制数量，避免超大库卡顿）
     */
    private List<File> listVideoFiles(File dir) {
        List<File> result = new ArrayList<>();
        collectVideoFiles(dir, result, 0);
        return result;
    }

    private void collectVideoFiles(File dir, List<File> result, int depth) {
        if (result.size() >= MAX_SCAN_VIDEOS || depth > 4) {
            return;
        }
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (File f : list) {
            if (result.size() >= MAX_SCAN_VIDEOS) {
                return;
            }
            if (f.isDirectory()) {
                collectVideoFiles(f, result, depth + 1);
            } else if (FileUtils.isVideoFormat(f.getName())) {
                result.add(f);
            }
        }
    }

    /**
     * 按视频文件路径解析出可附加字幕的目标目录（供控制器校验路径合法性后使用）
     */
    public File resolveVideo(String filename) {
        if (StrUtil.isBlank(filename)) {
            return null;
        }
        File file = new File(filename);
        if (!file.exists() || !file.isFile()) {
            return null;
        }
        if (!FileUtils.isVideoFormat(file.getName())) {
            return null;
        }
        return file;
    }
}
