package ani.rss.service;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.controller.PlayController;
import ani.rss.download.OpenListApi;
import ani.rss.entity.Ani;
import ani.rss.entity.OpenListFileInfo;
import ani.rss.entity.PlayItem;
import ani.rss.entity.TorrentsInfo;
import ani.rss.service.DownloadService;
import ani.rss.service.subtitle.AssrtSubtitleProvider;
import ani.rss.service.subtitle.SubtitleCandidate;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字幕匹配与补全。
 * <p>
 * 播放侧此前已支持内封字幕（EBML 解析）与外挂字幕（ass/srt），但缺两件事：
 * <ol>
 *   <li><b>可发现性</b>——不知道哪几集还没字幕，只能一集集点开看；</li>
 *   <li><b>可补性</b>——拿到字幕后要自己 SSH 上去拷文件。</li>
 * </ol>
 * 本服务提供"缺字幕清单 + 就地附加字幕"。
 * <p>
 * <b>关于在线字幕源</b>：第三方字幕站需要账号/API Key，且可用性与合规性随站点策略变化，
 * 因此不做默认开启的自动抓取；{@link #isAutoFetchEnabled()} 作为显式开关保留，
 * 由调用方在用户明确开启后再接入具体源。默认路径是"检测 + 手动补"。
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

    @Resource
    private PlayController playController;

    @Resource
    private DownloadService downloadService;

    @Resource
    private AssrtSubtitleProvider assrtSubtitleProvider;

    /**
     * 是否开启了自动获取（当前仅作为开关位，具体源由部署方接入）
     */
    public boolean isAutoFetchEnabled() {
        return Boolean.TRUE.equals(ConfigUtil.CONFIG.getSubtitleAutoFetch());
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
     * 就地附加字幕文件。
     * <p>
     * 字幕命名与视频主文件名保持一致，这样播放侧的
     * {@code getSubtitlesByVideo} 能按"主文件名前缀"匹配到它。
     *
     * @param videoFile       视频文件
     * @param subtitleContent 字幕内容（ass/srt 文本）
     * @param ext             字幕扩展名（ass/srt）
     * @param languageTag     语言标签（如 chs/cht，可空），会追加到文件名
     * @return 写入的字幕文件
     */
    public File attachSubtitle(File videoFile, String subtitleContent, String ext, String languageTag) {
        if (videoFile == null || !videoFile.exists() || !videoFile.isFile()) {
            throw new IllegalArgumentException("视频文件不存在");
        }
        String normalizedExt = StrUtil.blankToDefault(ext, "ass").toLowerCase().replace(".", "");
        if (!SUBTITLE_EXT.contains(normalizedExt)) {
            throw new IllegalArgumentException("不支持的字幕格式: " + ext + "（支持 " + String.join("/", SUBTITLE_EXT) + "）");
        }
        if (StrUtil.isBlank(subtitleContent)) {
            throw new IllegalArgumentException("字幕内容为空");
        }
        byte[] bytes = subtitleContent.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SUBTITLE_BYTES) {
            throw new IllegalArgumentException("字幕内容超过 20MiB 上限");
        }

        String mainName = FileUtil.mainName(videoFile);
        String suffix = StrUtil.isBlank(languageTag) ? "" : "." + languageTag.trim();
        File target = new File(videoFile.getParentFile(), mainName + suffix + "." + normalizedExt);
        if (target.exists()) {
            // 覆盖前备份，避免误覆盖用户已有的字幕
            File backup = new File(videoFile.getParentFile(), target.getName() + ".bak");
            try {
                FileUtil.copy(target, backup, true);
                log.info("已存在同名字幕, 覆盖前备份至 {}", backup.getName());
            } catch (Exception e) {
                log.warn("备份已有字幕失败: {}", ExceptionUtils.getMessage(e));
            }
        }
        // 原子写：先写临时文件再移动，避免写一半留下损坏字幕
        File temp = new File(videoFile.getParentFile(), target.getName() + ".temp");
        FileUtil.writeBytes(bytes, temp);
        FileUtil.move(temp, target, true);
        log.info("字幕已附加: {} ({} 字节)", target.getName(), bytes.length);
        return target;
    }

    /**
     * 下载完成后自动匹配并附加/上传字幕（ASSRT）。
     * <p>
     * 在 RenameTask 的 rename 之后、OpenList 上传之前调用：本地下载器走 {@link #attachSubtitle}
     * 就地写入，OpenList 离线下载走 {@link OpenListApi#fsPut} 写入云端，二者都会被既有的
     * 上传/播放逻辑识别。仅对缺字幕的视频抓取；已存在字幕则跳过。
     *
     * @param ani           订阅
     * @param torrentsInfo  完成的任务（仅用于判定下载器类型）
     */
    public void fetchAndAttach(Ani ani, TorrentsInfo torrentsInfo) {
        if (!isAutoFetchEnabled()) {
            return;
        }
        String token = ConfigUtil.CONFIG.getAssrtToken();
        if (StrUtil.isBlank(token)) {
            log.warn("未配置 ASSRT Token，跳过字幕自动获取");
            return;
        }
        String toolType = StrUtil.blankToDefault(ConfigUtil.CONFIG.getDownloadToolType(), "");
        if ("OpenList".equals(toolType)) {
            // OpenList 离线下载：视频落在云端，字幕经 fsPut 上传到云端同目录
            fetchAndAttachCloud(ani);
        } else {
            // 其余下载器（qBittorrent / Transmission / aria2 / 本地路径等）均视为本地下载，
            // 字幕就地写入视频所在目录
            fetchAndAttachLocal(ani);
        }
    }

    private void fetchAndAttachLocal(Ani ani) {
        String path = downloadService.getDownloadPath(ani);
        File dir = new File(path);
        if (!dir.exists()) {
            return;
        }
        for (File video : listVideoFiles(dir)) {
            fetchForLocalVideo(ani, video);
        }
    }

    private void fetchAndAttachCloud(Ani ani) {
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
            fetchForCloudVideo(ani, f, api);
        }
    }

    private void fetchForLocalVideo(Ani ani, File videoFile) {
        if (hasLocalSubtitle(videoFile)) {
            log.info("本地已有字幕，跳过: {}", videoFile.getName());
            return;
        }
        fetchCore(ani, videoFile.getName(), FileUtil.mainName(videoFile), null, videoFile, null);
    }

    private void fetchForCloudVideo(Ani ani, OpenListFileInfo f, OpenListApi api) {
        String mainName = FileUtil.mainName(f.getName());
        if (hasCloudSubtitle(f.getPath(), mainName, api)) {
            log.info("云端已有字幕，跳过: {}", f.getName());
            return;
        }
        fetchCore(ani, f.getName(), mainName, f.getPath(), null, api);
    }

    private void fetchCore(Ani ani, String videoName, String mainName, String cloudDir,
                           File localVideo, OpenListApi api) {
        String token = ConfigUtil.CONFIG.getAssrtToken();
        String lang = StrUtil.blankToDefault(ConfigUtil.CONFIG.getSubtitleLang(), "chs");
        if (StrUtil.isBlank(token)) {
            return;
        }
        String keyword = StrUtil.blankToDefault(ani.getTitle(), "").trim();
        if (StrUtil.isBlank(keyword)) {
            keyword = mainName;
        }
        int[] se = AssrtSubtitleProvider.extractSeasonEpisode(videoName);
        Integer targetSeason = se[0] >= 0 ? se[0] : null;
        Integer targetEp = se[1] >= 0 ? se[1] : null;
        List<SubtitleCandidate> candidates = assrtSubtitleProvider.search(token, keyword, videoName, lang);
        if (candidates.isEmpty()) {
            log.info("ASSRT 无匹配字幕: {} (关键词: {})", videoName, keyword);
            return;
        }
        for (SubtitleCandidate c : candidates) {
            try {
                byte[] data = assrtSubtitleProvider.download(c, lang, targetSeason, targetEp, videoName);
                if (data == null || data.length == 0) {
                    continue;
                }
                String ext = StrUtil.blankToDefault(c.getExt(), "ass").toLowerCase();
                String langTag = StrUtil.blankToDefault(c.getLang(), "");
                if (cloudDir == null) {
                    attachSubtitle(localVideo, new String(data, StandardCharsets.UTF_8), ext, langTag);
                } else {
                    String name = mainName + (StrUtil.isBlank(langTag) ? "" : "." + langTag) + "." + ext;
                    api.fsPut(cloudDir, name, data);
                }
                log.info("字幕已{}: {} -> {}",
                        cloudDir == null ? "附加(本地)" : "上传(云端)", videoName, ext);
                return;
            } catch (Exception ex) {
                log.warn("字幕候选写入失败 {}: {}", c.getFileName(), ExceptionUtils.getMessage(ex));
            }
        }
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
