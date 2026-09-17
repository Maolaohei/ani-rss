package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.PlayItem;
import ani.rss.entity.web.Result;
import ani.rss.enums.StringEnum;
import ani.rss.service.DownloadService;
import ani.rss.util.other.AniUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import com.matthewn4444.ebml.EBMLReader;
import com.matthewn4444.ebml.subtitles.Subtitles;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
@Slf4j
@RestController
public class PlayController extends BaseController {

    /**
     * 内封字幕累计内容上限 (20MiB), 超出后停止解析
     */
    private static final long MAX_SUBTITLE_BYTES = 20L * 1024 * 1024;

    /**
     * 目录递归扫描深度上限
     */
    private static final int MAX_SCAN_DEPTH = 16;

    @Resource
    private DownloadService downloadService;

    @Auth
    @Operation(summary = "获取内封字幕")
    @PostMapping("/getSubtitles")
    public Result<List<PlayItem.Subtitles>> getSubtitles(@RequestParam("filename") String filename) throws IOException {
        filename = filename.replace(" ", "+");
        filename = Base64.decodeStr(filename);

        List<PlayItem.Subtitles> subtitlesList = new ArrayList<>();

        String extName = FileUtil.extName(filename);
        if (StrUtil.isBlank(extName)) {
            return Result.success(subtitlesList);
        }

        if (!"mkv".equals(extName)) {
            return Result.success(subtitlesList);
        }

        // 与 /file 同规则：防止任意路径读取
        FileController.verifyFilePath(filename);

        Assert.isTrue(FileUtil.exist(filename), "视频文件不存在");

        @Cleanup
        EBMLReader reader = new EBMLReader(filename);
        if (!reader.readHeader()) {
            return Result.success(subtitlesList);
        }
        reader.readTracks();
        reader.readCues();

        for (int i = 0; i < reader.getCuesCount(); i++) {
            reader.readSubtitlesInCueFrame(i);
        }

        List<Subtitles> subtitles = reader.getSubtitles();

        // 累计字幕内容字节, 超出上限停止解析, 防止超大字幕打爆内存
        long totalBytes = 0;
        boolean truncated = false;
        for (Subtitles subtitle : subtitles) {
            String name = subtitle.getName();
            String presentableName = subtitle.getPresentableName();
            String contents = subtitle.getContentsToVTT();

            long contentBytes = contents.getBytes(StandardCharsets.UTF_8).length;
            if (totalBytes + contentBytes > MAX_SUBTITLE_BYTES) {
                truncated = true;
                log.warn("内封字幕累计超过 {} MiB, 剩余字幕已截断: {}", MAX_SUBTITLE_BYTES / 1024 / 1024, filename);
                break;
            }
            totalBytes += contentBytes;

            PlayItem.Subtitles sub = new PlayItem.Subtitles();
            sub.setContent(contents)
                    .setName(name)
                    .setHtml(presentableName)
                    .setUrl("")
                    .setType("vtt");
            subtitlesList.add(sub);
        }

        if (truncated) {
            // 在响应中注明截断, 保持现有返回结构
            PlayItem.Subtitles truncatedSub = new PlayItem.Subtitles();
            truncatedSub.setContent("")
                    .setName("字幕内容过大已截断")
                    .setHtml("TRUNCATED")
                    .setUrl("")
                    .setType("vtt");
            subtitlesList.add(truncatedSub);
        }

        return Result.success(subtitlesList);
    }

    @Auth
    @Operation(summary = "获取视频列表")
    @PostMapping("/playList")
    public Result<List<PlayItem>> playList(@RequestBody Ani ani) {
        String url = ani.getUrl();
        Optional<Ani> first = AniUtil.getAniList()
                .stream()
                .filter(it -> url.equals(it.getUrl()))
                .findFirst();
        if (first.isEmpty()) {
            return Result.error();
        }
        ani = first.get();

        String downloadPath = downloadService.getDownloadPath(ani);
        List<PlayItem> collect = getPlayItem(new File(downloadPath), new HashSet<>(), 0);

        // 按照集数排序
        CollUtil.sort(collect, Comparator.comparingDouble(PlayItem::getEpisode));

        return Result.success(collect);
    }

    /**
     * 获取目录下的视频列表
     *
     * @param file    目录
     * @param visited 已访问目录 (规范路径), 防止软链环导致无限递归
     * @param depth   当前递归深度
     * @return 视频列表
     */
    public List<PlayItem> getPlayItem(File file, Set<String> visited, int depth) {
        List<PlayItem> playItems = new ArrayList<>();

        if (!file.exists()) {
            // 文件或目录不存在
            return playItems;
        }

        if (depth > MAX_SCAN_DEPTH) {
            // 递归深度超限, 停止扫描
            return playItems;
        }

        if (file.isDirectory()) {
            String canonicalPath;
            try {
                canonicalPath = file.getCanonicalPath();
            } catch (IOException e) {
                // 规范路径解析失败, 跳过该目录
                return playItems;
            }
            if (!visited.add(canonicalPath)) {
                // 目录环 (软链循环), 跳过
                return playItems;
            }

            // 进行递归
            File[] files = FileUtils.listFiles(file);
            if (ArrayUtil.isEmpty(files)) {
                return playItems;
            }
            // P1: 单目录一次 listFiles，内存分组。原先每个视频文件都会在
            // getSubtitlesByVideo 内再 listFiles 一次（N 个视频 = N+1 次 IO）。
            // 本目录的视频直接用本次结果在内存里配字幕，只递归子目录。
            List<File> subDirs = new ArrayList<>();
            List<File> videoFiles = new ArrayList<>();
            List<File> subtitleFiles = new ArrayList<>();
            for (File f : files) {
                if (f.isDirectory()) {
                    subDirs.add(f);
                    continue;
                }
                if (isVideoCandidate(f)) {
                    videoFiles.add(f);
                    continue;
                }
                if (isSubtitleCandidate(f)) {
                    subtitleFiles.add(f);
                }
            }
            for (File subDir : subDirs) {
                playItems.addAll(getPlayItem(subDir, visited, depth + 1));
            }
            for (File videoFile : videoFiles) {
                playItems.add(buildPlayItem(videoFile, subtitlesFor(videoFile, subtitleFiles)));
            }
            // 去重复
            playItems = CollUtil.distinct(playItems, PlayItem::getTitle, false);
            return playItems;
        }

        if (!isVideoCandidate(file)) {
            // 非视频文件（过小/无扩展名/非视频格式）：沿用原逻辑直接跳过
            return playItems;
        }

        // 单文件入口（如外部直接传入视频文件）：同层仍需一次 listFiles；目录批量入口已走内存分组
        List<PlayItem.Subtitles> subtitles = getSubtitlesByVideo(file);

        PlayItem playItem = buildPlayItem(file, subtitles);
        playItems.add(playItem);

        // 去重复
        playItems = CollUtil.distinct(playItems, PlayItem::getTitle, false);

        // 按照集数排序
        return playItems;
    }

    /**
     * 视频候选：大小/扩展名/格式三道闸（与原 getPlayItem 叶子分支条件一致）
     */
    private static boolean isVideoCandidate(File file) {
        if (file.length() < 1024 * 1024 * 20) {
            return false;
        }
        String extName = FileUtil.extName(file);
        if (StrUtil.isBlank(extName)) {
            return false;
        }
        return FileUtils.isVideoFormat(extName);
    }

    /**
     * 外挂字幕候选：真实文件 + ass/srt（与 getSubtitlesByVideo 过滤一致，浏览器仅支持这两种）
     */
    private static boolean isSubtitleCandidate(File file) {
        if (!file.isFile()) {
            return false;
        }
        String ext = FileUtil.extName(file);
        if (StrUtil.isBlank(ext)) {
            return false;
        }
        return List.of("ass", "srt").contains(ext);
    }

    /**
     * 内存分组：用已列出的同目录字幕文件为视频配字幕，不做任何 IO
     */
    private static List<PlayItem.Subtitles> subtitlesFor(File videoFile, List<File> subtitleFiles) {
        String videoMainName = FileUtil.mainName(videoFile);
        if (StrUtil.isBlank(videoMainName) || subtitleFiles.isEmpty()) {
            return new ArrayList<>();
        }
        List<PlayItem.Subtitles> subtitles = new ArrayList<>();
        for (File sub : subtitleFiles) {
            String subMainName = FileUtil.mainName(sub);
            if (StrUtil.isBlank(subMainName)) {
                continue;
            }
            if (!subMainName.startsWith(videoMainName)) {
                continue;
            }
            String mainName = FileUtil.mainName(sub.getName());
            String absolutePath = FileUtils.getAbsolutePath(sub);
            subtitles.add(new PlayItem.Subtitles()
                    .setName(mainName)
                    .setHtml(mainName.toUpperCase())
                    .setUrl(absolutePath)
                    .setType(FileUtil.extName(sub)));
        }
        return CollUtil.distinct(subtitles, PlayItem.Subtitles::getName, true);
    }

    /**
     * 由视频文件 + 字幕列表组装 PlayItem（含 S01E01 正则解析，与原逻辑一致）
     */
    private static PlayItem buildPlayItem(File file, List<PlayItem.Subtitles> subtitles) {
        String videoFileName = file.getName();
        String extName = FileUtil.extName(file);
        long lastModified = file.lastModified();
        String formatSize = FileUtils.formatSize(file);
        String absolutePath = FileUtils.getAbsolutePath(file);

        PlayItem playItem = new PlayItem();
        playItem.setFilename(absolutePath)
                .setName(videoFileName)
                .setTitle(videoFileName)
                .setLastModify(lastModified)
                .setEpisode(1.0)
                .setFormatSize(formatSize)
                .setExtName(extName)
                .setSubtitles(subtitles);

        if (ReUtil.contains(StringEnum.SEASON_REG, file.getName())) {
            // 如匹配正则则用正则取 S01E01
            String title = ReUtil.get(StringEnum.SEASON_REG, videoFileName, 0);
            // 集数
            String episode = ReUtil.get(StringEnum.SEASON_REG, videoFileName, 2);
            playItem.setTitle(title)
                    .setEpisode(Double.parseDouble(episode));
        }

        return playItem;
    }

    /**
     * 根据视频文件找到同层级的字幕（单文件入口：同目录一次 listFiles，无二次 IO）。
     * 目录批量入口（getPlayItem 目录分支）已改为内存分组，不走本方法。
     *
     * @param videoFile 视频文件
     * @return 字幕列表
     */
    public List<PlayItem.Subtitles> getSubtitlesByVideo(File videoFile) {
        // 查找同层级的字幕文件
        File[] files = FileUtils.listFiles(videoFile.getParentFile());
        if (ArrayUtil.isEmpty(files)) {
            return new ArrayList<>();
        }
        List<PlayItem.Subtitles> subtitles = Arrays.stream(files)
                .filter(sub -> {
                    // 只认同层级的真实文件：sub_bak/ 等备份目录不能被当成有效外挂字幕
                    if (!sub.isFile()) {
                        return false;
                    }
                    String ext = FileUtil.extName(sub);
                    if (StrUtil.isBlank(ext)) {
                        return false;
                    }
                    // 浏览器仅支持 ass、srt
                    if (!List.of("ass", "srt").contains(ext)) {
                        return false;
                    }
                    // 视频主文件名
                    String videoMainName = FileUtil.mainName(videoFile);
                    // 字幕文件主文件名
                    String subMainName = FileUtil.mainName(sub);

                    // 字幕文件需与视频文件匹配 如 S01E01.chs.ass S01E01.mkv
                    return subMainName.startsWith(videoMainName);
                })
                .map(sub -> {
                    // 主文件名
                    String subMainName = FileUtil.mainName(sub.getName());
                    String absolutePath = FileUtils.getAbsolutePath(sub);
                    return new PlayItem.Subtitles()
                            .setName(subMainName)
                            .setHtml(subMainName.toUpperCase())
                            .setUrl(absolutePath)
                            .setType(FileUtil.extName(sub));
                }).toList();
        // 去重复
        return CollUtil.distinct(subtitles, PlayItem.Subtitles::getName, true);
    }

}
