package ani.rss.commons;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Slf4j
public class FileUtils {
    /**
     * 视频格式
     */
    private static final Set<String> VIDEO_FORMAT = Set.of("mp4", "mkv", "avi", "wmv");

    /**
     * 字幕格式
     */
    private static final Set<String> SUBTITLE_FORMAT = Set.of("ass", "ssa", "sub", "srt", "lyc", "sup", "pgs", "mks");

    /**
     * 图片格式
     */
    private static final Set<String> IMAGE_FORMAT = Set.of("png", "jpg", "jpeg", "webp", "svg");

    /**
     * 已知字幕语言/轨道 token（统一小写比较）。
     * <p>
     * 用于从字幕文件名中提取「语言后缀」，覆盖 SC / TC / JP / CHT / CHS 及其双语组合
     * （jpsc / jptc 等），以及 chs&eng 这类多语言合并写法。仅识别已知 token，
     * 避免把文件主名里的数字段当语言（如 {@code Vol.1.ass} → 1）。
     */
    private static final Set<String> SUBTITLE_LANG_TOKENS = Set.of(
            "zh", "zh-hans", "zh-hant", "zh-cn", "zh-tw", "cn",
            "sc", "tc", "chs", "cht", "chinese", "chinese-simplified", "chinese-traditional",
            "simplified", "traditional",
            "jp", "jpn", "ja", "jpsc", "jptc", "jpchs", "jpcht", "jp-sc", "jp-tc",
            "eng", "en", "english",
            "kor", "kr", "krn", "korean",
            "chs&eng", "cht&eng", "zh&en", "jpsc&eng", "jptc&eng",
            "default", "full", "forced", "sign", "signs", "songs", "comment");

    /**
     * 判断文件名是否为视频
     *
     * @param filename 文件名
     * @return 是/否
     */
    public static Boolean isVideoFormat(String filename) {
        return isFormat(filename, VIDEO_FORMAT);
    }

    /**
     * 判断文件名是否为字幕
     *
     * @param filename 文件名
     * @return 是/否
     */
    public static Boolean isSubtitleFormat(String filename) {
        return isFormat(filename, SUBTITLE_FORMAT);
    }

    /**
     * 判断文件名是否为图片
     *
     * @param filename 文件名
     * @return 是/否
     */
    public static Boolean isImageFormat(String filename) {
        return isFormat(filename, IMAGE_FORMAT);
    }

    public static Boolean isFormat(String filename, Set<String> extNames) {
        if (StrUtil.isBlank(filename)) {
            return false;
        }
        filename = filename.toLowerCase();

        String extName = FileUtil.extName(filename);
        if (StrUtil.isNotBlank(extName)) {
            return extNames.contains(extName);
        }

        return extNames.contains(filename);
    }

    /**
     * 是否为已知字幕语言/轨道 token（大小写不敏感）
     *
     * @param token 待判断的单个文件名段
     */
    public static boolean isKnownSubtitleLangToken(String token) {
        if (StrUtil.isBlank(token)) {
            return false;
        }
        return SUBTITLE_LANG_TOKENS.contains(token.toLowerCase());
    }

    /**
     * 提取字幕语言后缀（可多级，如 {@code xx.chs&eng.simplified.ass → "chs&eng.simplified"}）。
     * <p>
     * 从文件名末尾往前逐段识别已知语言/轨道 token，全部统一小写，便于
     * 「剧名 SxxExx.{lang}.{ext}」命名。无法识别任何语言段时返回 {@code null}，
     * 由调用方退化为「剧名 SxxExx.{ext}」。
     *
     * @param name 字幕文件名（可含目录前缀）
     * @return 语言后缀（不含前导点），无则 {@code null}
     */
    public static String extractSubtitleLangSuffix(String name) {
        if (StrUtil.isBlank(name)) {
            return null;
        }
        // 去掉目录前缀，避免 "Subs/xx.chs.ass" 的 extName 取到空串
        String base = FileUtil.mainName(FileUtil.getName(name));
        List<String> parts = new java.util.ArrayList<>();
        while (StrUtil.isNotBlank(base)) {
            String cur = FileUtil.extName(base);
            if (StrUtil.isBlank(cur) || !isKnownSubtitleLangToken(cur)) {
                break;
            }
            parts.add(0, cur.toLowerCase());
            String next = FileUtil.mainName(base);
            if (next.equals(base)) {
                break;
            }
            base = next;
        }
        return parts.isEmpty() ? null : String.join(".", parts);
    }

    /**
     * 获取绝对路径 并把 windows 狗日的 \ 转换为 /
     *
     * @param file
     * @return
     */
    public static String getAbsolutePath(File file) {
        String absolutePath = file.getPath();
        if (absolutePath.startsWith("/")) {
            // 已是绝对路径
            return normalize(absolutePath);
        }

        if (ReUtil.contains("^[A-z]:", absolutePath)) {
            // 已是绝对路径
            return normalize(absolutePath);
        }

        absolutePath = file.getAbsolutePath();
        return normalize(absolutePath);
    }

    /**
     * 获取绝对路径 并把 windows 狗日的 \ 转换为 /
     *
     * @param absolutePath
     * @return
     */
    public static String getAbsolutePath(String absolutePath) {
        if (absolutePath.startsWith("/")) {
            // 已是绝对路径
            return normalize(absolutePath);
        }

        if (ReUtil.contains("^[A-z]:", absolutePath)) {
            // 已是绝对路径
            return normalize(absolutePath);
        }

        absolutePath = new File(absolutePath).getAbsolutePath();
        return normalize(absolutePath);
    }

    public static String normalize(String path) {
        path = path.trim();
        String s = FileUtil.normalize(path);
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * 获取文件列表 不会存在空指针问题
     *
     * @param path 文件夹位置
     * @return 文件列表
     */
    public static File[] listFiles(String path) {
        return listFiles(new File(path));
    }

    /**
     * 获取文件列表 不会存在空指针问题
     *
     * @param file 文件夹位置
     * @return 文件列表
     */
    public static File[] listFiles(File file) {
        if (Objects.isNull(file)) {
            return new File[0];
        }
        if (!file.exists()) {
            return new File[0];
        }
        if (file.isDirectory()) {
            return ObjectUtil.defaultIfNull(file.listFiles(), new File[0]);
        }
        return new File[0];
    }

    public static List<File> listFileList(String path) {
        return List.of(listFiles(path));
    }

    /**
     * 文件移动 优先尝试原子移动
     *
     * @param source 原位置
     * @param target 目标位置
     */
    public static void move(Path source, Path target) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (Exception e) {
            log.debug(e.getMessage(), e);
        }

        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String formatSize(File file) {
        return formatSize(file.length(), true);
    }

    public static String formatSize(File file, boolean use1024) {
        Assert.isTrue(file.exists(), "文件不存在 {}", file);
        return formatSize(file.length(), use1024);
    }

    public static String formatSize(long size, boolean use1024) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }

        int base = use1024 ? 1024 : 1000;
        double value = size;

        String[] units = use1024
                ? new String[]{"B", "KiB", "MiB", "GiB", "TiB"}
                : new String[]{"B", "KB", "MB", "GB", "TB"};

        int index = 0;
        while (value >= base && index < units.length - 1) {
            value /= base;
            index++;
        }

        // 保留最多两位小数
        return String.format("%.2f %s", value, units[index]);
    }
}
