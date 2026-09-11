package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.entity.Global;
import ani.rss.entity.web.Result;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.crypto.SecureUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

@Slf4j
@RestController
public class UploadController extends BaseController {

    /**
     * 单请求大小上限: 10 MiB
     */
    private static final long MAX_UPLOAD_SIZE = 10L * 1024 * 1024;

    /**
     * 允许上传的扩展名白名单 (图片/字幕/视频), 显式排除 svg/html 等可执行内容
     */
    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "webp");
    private static final Set<String> SUBTITLE_EXT = Set.of("ass", "ssa", "sub", "srt", "lyc", "sup", "pgs", "mks");
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "mkv", "avi", "wmv");

    @Auth
    @Operation(summary = "上传文件")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Object> upload(@RequestParam("file") MultipartFile file) throws Exception {
        Assert.notNull(file, "文件为空");

        // 单请求大小上限
        Assert.isTrue(file.getSize() <= MAX_UPLOAD_SIZE, "文件超过大小限制 (10MiB)");

        HttpServletRequest request = Global.REQUEST.get();
        String type = request.getParameter("type");

        if ("getBase64".equals(type)) {
            // Base64 分支需整包读入内存, 同样受大小上限约束
            try (InputStream inputStream = file.getInputStream()) {
                byte[] fileContent = inputStream.readAllBytes();
                return Result.success(r ->
                        r.setData(Base64.encode(fileContent))
                );
            }
        }

        String fileName = file.getOriginalFilename();
        String extName = FileUtil.extName(fileName).toLowerCase(Locale.ROOT);

        // 扩展名白名单: 仅允许图片/字幕/视频, 且显式拒绝 svg/html/htm/js 等可执行内容 (防存储型 XSS)
        Assert.isTrue(isAllowedExt(extName), "不支持的文件格式: {}", extName);

        String saveDir = ConfigUtil.getConfigDir() + "/files/";

        // 流式计算 MD5 与落盘, 不再将整个文件读入内存
        String s;
        try (InputStream inputStream = file.getInputStream()) {
            s = SecureUtil.md5(inputStream);
        }

        String saveName = s + "." + extName;
        File targetFile = new File(saveDir, s.charAt(0) + "/" + saveName);

        FileUtil.mkdir(targetFile.getParentFile());
        try (InputStream inputStream = file.getInputStream()) {
            FileUtil.writeFromStream(inputStream, targetFile);
        }

        return new Result<>()
                .setMessage("上传完成")
                .setData(s.charAt(0) + "/" + saveName);
    }

    /**
     * 扩展名白名单判定
     */
    private static boolean isAllowedExt(String extName) {
        return IMAGE_EXT.contains(extName) || SUBTITLE_EXT.contains(extName) || VIDEO_EXT.contains(extName);
    }

    /**
     * 上传并读取为 base64 (合集种子上传使用)
     * 仅回传内容不落盘, 无存储型 XSS 风险, 故不受扩展名白名单约束, 仅限制大小
     */
    @Auth
    @Operation(summary = "上传并读取为 base64")
    @PostMapping(value = "/uploadAndReadToBase64", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<String> uploadAndReadToBase64(@RequestParam("file") MultipartFile file) throws Exception {
        Assert.notNull(file, "文件为空");
        Assert.isTrue(file.getSize() <= MAX_UPLOAD_SIZE, "文件超过大小限制 (10MiB)");

        try (InputStream inputStream = file.getInputStream()) {
            byte[] fileContent = inputStream.readAllBytes();
            return Result.success(r ->
                    r.setData(Base64.encode(fileContent))
            );
        }
    }

    /**
     * 上传并读取为 UTF-8 文本 (订阅导入使用)
     */
    @Auth
    @Operation(summary = "上传并读取")
    @PostMapping(value = "/uploadAndRead", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<String> uploadAndRead(@RequestParam("file") MultipartFile file) throws Exception {
        Assert.notNull(file, "文件为空");
        Assert.isTrue(file.getSize() <= MAX_UPLOAD_SIZE, "文件超过大小限制 (10MiB)");

        try (InputStream inputStream = file.getInputStream()) {
            String content = IoUtil.readUtf8(inputStream);
            return Result.success(r ->
                    r.setData(content)
            );
        }
    }

}
