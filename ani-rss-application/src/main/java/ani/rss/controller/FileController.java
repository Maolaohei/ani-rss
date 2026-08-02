package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Global;
import ani.rss.entity.web.Header;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
public class FileController extends BaseController {

    @Auth
    @Operation(summary = "获取文件")
    @GetMapping("/file")
    public void file(@RequestParam("filename") String filename) {
        filename = filename.replace(" ", "+");
        filename = Base64.decodeStr(filename);

        verifyFileFormat(filename);
        verifyFilePath(filename);
        doFile(filename);
    }

    /**
     * 校验文件路径，防止目录穿越与任意文件读取
     * <p>
     * 规则：相对路径仅允许位于 configDir/files 下（封面等静态资源）；
     * 绝对路径必须位于下载路径模板静态根（或订阅自定义下载路径）之内。
     *
     * @param filename 原始文件名（可为绝对路径或相对路径）
     */
    public static void verifyFilePath(String filename) {
        Assert.notBlank(filename, "不允许访问");

        String normalized = FileUtils.normalize(filename);
        // 相对路径中的 .. 逃逸（normalize 无法消解到根之上）
        Assert.isFalse(normalized.startsWith(".."), "不允许访问");

        boolean absolute = normalized.startsWith("/")
                || ReUtil.contains("^[A-Za-z]:", normalized);

        String abs;
        if (!absolute) {
            // 相对路径：仅允许 configDir/files 目录内
            File filesDir = new File(ConfigUtil.getConfigDir(), "files");
            abs = FileUtils.getAbsolutePath(Path.of(filesDir.toString(), normalized).toFile());
            String root = FileUtils.getAbsolutePath(filesDir);
            Assert.isTrue(pathEqualsOrUnder(root, abs), "不允许访问");
            return;
        }

        // 绝对路径：必须位于下载根目录内
        abs = FileUtils.getAbsolutePath(normalized);
        boolean allowed = allowedFileRoots().stream()
                .anyMatch(root -> pathEqualsOrUnder(root, abs));
        Assert.isTrue(allowed, "不允许访问");
    }

    /**
     * 允许读取的绝对路径根集合（下载路径模板静态前缀 + 订阅自定义下载路径 + configDir/files）
     */
    private static List<String> allowedFileRoots() {
        Config config = ConfigUtil.CONFIG;
        List<String> roots = new ArrayList<>();
        roots.add(FileUtils.getAbsolutePath(new File(ConfigUtil.getConfigDir(), "files")));

        List<String> templates = List.of(
                config.getDownloadPathTemplate(),
                config.getOvaDownloadPathTemplate(),
                config.getCompletedPathTemplate()
        );
        for (String template : templates) {
            if (StrUtil.isBlank(template)) {
                continue;
            }
            // ${ 之前的静态前缀即下载根；无占位符时整个模板即根；无静态前缀（如 ${title}/...）则跳过
            String staticRoot = template.split("\\$\\{")[0];
            if (StrUtil.isNotBlank(staticRoot)) {
                roots.add(FileUtils.getAbsolutePath(staticRoot));
            }
        }

        // 订阅级自定义下载路径（与 DownloadService.getDownloadPath 同规则取首行）
        for (Ani ani : AniUtil.getAniList()) {
            if (!Boolean.TRUE.equals(ani.getCustomDownloadPath())) {
                continue;
            }
            String aniDownloadPath = ani.getDownloadPath();
            if (StrUtil.isBlank(aniDownloadPath)) {
                continue;
            }
            String firstLine = StrUtil.split(aniDownloadPath, "\n", true, true)
                    .stream()
                    .findFirst()
                    .orElse("");
            if (StrUtil.isNotBlank(firstLine)) {
                roots.add(FileUtils.getAbsolutePath(firstLine));
            }
        }
        return roots;
    }

    /**
     * 路径比较：Path.normalize 消解 .. 后按前缀匹配子树。
     * Windows 下 Path.startsWith 大小写不敏感；直接构造 Path 避免 normalize 剥掉盘符根/文件系统根。
     */
    private static boolean pathEqualsOrUnder(String root, String abs) {
        Path r = Path.of(root).normalize().toAbsolutePath();
        Path a = Path.of(abs).normalize().toAbsolutePath();
        return a.startsWith(r);
    }

    /**
     * 校验文件格式
     */
    private void verifyFileFormat(String filename) {
        Assert.notBlank(filename, "不允许访问");

        String extName = FileUtil.extName(filename);

        Assert.notBlank(extName, "不允许访问");

        boolean b = FileUtils.isImageFormat(filename) ||
                FileUtils.isSubtitleFormat(filename) ||
                FileUtils.isVideoFormat(filename);

        Assert.isTrue(b, "不允许访问");
    }

    /**
     * 处理文件
     *
     * @param filename 文件名
     */
    private void doFile(String filename) {
        HttpServletRequest request = Global.REQUEST.get();
        HttpServletResponse response = Global.RESPONSE.get();

        File file = new File(filename);
        if (!file.exists()) {
            File configDir = ConfigUtil.getConfigDir();
            file = Path.of(configDir.toString(), "files", filename).toFile();
            if (!file.exists()) {
                writeNotFound();
                return;
            }
        }

        boolean hasRange = false;
        long fileLength = file.length();
        long start = 0;
        long end = fileLength - 1;

        String contentType = getContentType(file.getName());

        response.setHeader(Header.CONTENT_DISPOSITION, StrFormatter.format("inline; filename=\"{}\"", URLUtil.encode(file.getName())));
        if (contentType.startsWith("video/")) {
            response.setContentType(contentType);
            response.setHeader(Header.ACCEPT_RANGES, "bytes");
            String rangeHeader = request.getHeader("Range");
            if (StrUtil.isNotBlank(rangeHeader) && rangeHeader.startsWith("bytes=")) {
                try {
                    String[] range = rangeHeader.substring(6).split("-");
                    if (range.length > 0 && StrUtil.isNotBlank(range[0])) {
                        start = Long.parseLong(range[0]);
                    }
                    if (range.length > 1 && StrUtil.isNotBlank(range[1])) {
                        end = Long.parseLong(range[1]);
                    }
                    start = Math.max(start, 0);
                    end = Math.min(end, fileLength - 1);
                    if (start > end) {
                        // 起始大于结束（如 bytes=100-50 或空文件）：回退为全量读取
                        start = 0;
                        end = fileLength - 1;
                    }
                } catch (NumberFormatException ignored) {
                    // 畸形 Range 头：回退为全量读取
                    start = 0;
                    end = fileLength - 1;
                }
            }
            response.setHeader(Header.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileLength);
            hasRange = true;
        } else {
            long maxAge = 0;

            // 小于或者等于 3M 缓存
            if (fileLength <= 1024 * 1024 * 3) {
                // 30 天
                maxAge = 86400 * 30;
            }

            setCacheControl(response, maxAge);
            response.setContentType(contentType);
        }

        try {
            if (hasRange) {
                long length = end - start;
                response.setStatus(206);
                @Cleanup
                OutputStream out = response.getOutputStream();
                @Cleanup
                RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
                randomAccessFile.seek(start);
                @Cleanup
                FileChannel channel = randomAccessFile.getChannel();
                @Cleanup
                InputStream inputStream = Channels.newInputStream(channel);
                IoUtil.copy(inputStream, out, 40960, length, null);
            } else {
                response.setContentLengthLong(file.length());

                @Cleanup
                InputStream inputStream = FileUtil.getInputStream(file);
                @Cleanup
                OutputStream outputStream = response.getOutputStream();
                IoUtil.copy(inputStream, outputStream);
            }
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.debug(message, e);
        }
    }
}
