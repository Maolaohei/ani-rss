package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.ExceptionUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.web.Result;
import ani.rss.service.DownloadService;
import ani.rss.service.SubtitleService;
import ani.rss.util.other.AniUtil;
import cn.hutool.core.util.StrUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 字幕匹配与补全。
 * <p>
 * 路径安全与 {@code FileController} 同规则：只允许下载根 / 自定义下载路径内的文件，
 * 防止通过字幕接口读写任意路径。
 */
@Slf4j
@RestController
public class SubtitleController extends BaseController {

    @Resource
    private SubtitleService subtitleService;

    @Resource
    private DownloadService downloadService;

    @Auth
    @Operation(summary = "扫描缺失字幕")
    @PostMapping("/subtitleScan")
    public Result<SubtitleService.SubtitleReport> subtitleScan(@RequestBody Map<String, Object> body) {
        String aniId = body == null || body.get("aniId") == null ? null : String.valueOf(body.get("aniId"));
        if (StrUtil.isBlank(aniId)) {
            return Result.error("参数缺失: aniId");
        }
        Optional<Ani> aniOpt = AniUtil.getAniList().stream()
                .filter(a -> Objects.equals(a.getId(), aniId))
                .findFirst();
        if (aniOpt.isEmpty()) {
            return Result.error("订阅不存在");
        }
        Ani ani = aniOpt.get();
        try {
            String downloadPath = downloadService.getDownloadPath(ani);
            return Result.success(subtitleService.scanMissing(ani, downloadPath));
        } catch (Exception e) {
            log.warn("字幕扫描失败 {}: {}", ani.getTitle(), ExceptionUtils.getMessage(e));
            return Result.error("扫描失败: " + ExceptionUtils.getMessage(e));
        }
    }

    @Auth
    @Operation(summary = "就地附加字幕")
    @PostMapping("/subtitleAttach")
    public Result<Map<String, Object>> subtitleAttach(@RequestBody Map<String, Object> body) {
        if (body == null || body.get("filename") == null) {
            return Result.error("参数缺失: filename");
        }
        String filename = String.valueOf(body.get("filename"));
        // 与 /file 同规则：防止任意路径读写
        try {
            FileController.verifyFilePath(filename);
        } catch (Exception e) {
            return Result.error("不允许访问该路径");
        }

        File videoFile = subtitleService.resolveVideo(filename);
        if (videoFile == null) {
            return Result.error("视频文件不存在或不是受支持的视频格式");
        }

        String content = body.get("content") == null ? null : String.valueOf(body.get("content"));
        String ext = body.get("ext") == null ? null : String.valueOf(body.get("ext"));
        String languageTag = body.get("languageTag") == null ? null : String.valueOf(body.get("languageTag"));

        try {
            File target = subtitleService.attachSubtitle(videoFile, content, ext, languageTag);
            return Result.success(Map.of(
                    "name", target.getName(),
                    "path", target.getAbsolutePath(),
                    "size", target.length()
            ));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("附加字幕失败 {}: {}", filename, ExceptionUtils.getMessage(e));
            return Result.error("附加失败: " + ExceptionUtils.getMessage(e));
        }
    }

    @Auth
    @Operation(summary = "字幕自动获取开关状态")
    @PostMapping("/subtitleStatus")
    public Result<Map<String, Object>> subtitleStatus() {
        return Result.success(Map.of(
                "autoFetch", subtitleService.isAutoFetchEnabled(),
                "supportedExt", java.util.List.of("ass", "srt", "ssa", "vtt", "sub")
        ));
    }
}
