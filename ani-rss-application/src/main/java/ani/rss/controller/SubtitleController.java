package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.ExceptionUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.web.Result;
import ani.rss.service.DownloadService;
import ani.rss.service.SubtitleService;
import ani.rss.service.subtitle.SubtitleMatchLog;
import ani.rss.service.subtitle.SubtitleMatchLogEntry;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.util.StrUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 字幕匹配与补全。
 * <p>
 * 字幕统一由本模块管理：用户可选择「手动上传本地字幕」或「从射手网(ASSRT)获取字幕」，
 * 两条路径都遵循<b>预览 → 二次确认 → 写入</b>，避免自动匹配到错误字幕。
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

    /**
     * 单次导入的文件数上限，防止超大表单拖垮接口
     */
    private static final int MAX_SUBTITLE_UPLOAD_COUNT = 200;

    /**
     * 单文件大小上限 20MiB，与 {@code SubtitleService.MAX_SUBTITLE_BYTES} 一致
     */
    private static final long MAX_SUBTITLE_UPLOAD_SIZE = 20L * 1024 * 1024;

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
    @Operation(summary = "字幕手动获取开关状态")
    @PostMapping("/subtitleStatus")
    public Result<Map<String, Object>> subtitleStatus() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("manualFetch", subtitleService.isManualFetchEnabled());
        data.put("supportedExt", List.of("ass", "srt", "ssa", "vtt", "sub"));
        return Result.success(data);
    }

    @Auth
    @Operation(summary = "批量导入本地字幕")
    @PostMapping(value = "/subtitleImport", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<SubtitleService.ImportResult> subtitleImport(
            @RequestParam("aniId") String aniId,
            @RequestParam("files") List<MultipartFile> files) throws Exception {
        return doImportLocal(aniId, files, false);
    }

    @Auth
    @Operation(summary = "预览本地字幕导入（不写盘，供二次确认）")
    @PostMapping(value = "/subtitleImportPreview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<SubtitleService.ImportResult> subtitleImportPreview(
            @RequestParam("aniId") String aniId,
            @RequestParam("files") List<MultipartFile> files) throws Exception {
        return doImportLocal(aniId, files, true);
    }

    /**
     * 本地字幕导入的公共实现：预览与正式导入共用同一套校验与匹配逻辑，
     * 唯一区别是 {@code dryRun} 下不落盘，保证「预览所见 == 实际写入」。
     */
    private Result<SubtitleService.ImportResult> doImportLocal(String aniId, List<MultipartFile> files,
                                                              boolean dryRun) throws Exception {
        if (StrUtil.isBlank(aniId)) {
            return Result.error("参数缺失: aniId");
        }
        if (files == null || files.isEmpty()) {
            return Result.error("请至少选择一个字幕文件");
        }
        if (files.size() > MAX_SUBTITLE_UPLOAD_COUNT) {
            return Result.error("单次最多导入 " + MAX_SUBTITLE_UPLOAD_COUNT + " 个字幕文件");
        }
        Optional<Ani> aniOpt = AniUtil.getAniList().stream()
                .filter(a -> Objects.equals(a.getId(), aniId))
                .findFirst();
        if (aniOpt.isEmpty()) {
            return Result.error("订阅不存在");
        }

        // 读入内存前逐个做大小校验，避免超大文件撑爆堆
        List<SubtitleService.LocalSubtitleFile> uploads = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            if (file.getSize() > MAX_SUBTITLE_UPLOAD_SIZE) {
                return Result.error("字幕文件超过 20MiB 上限: " + file.getOriginalFilename());
            }
            String name = StrUtil.blankToDefault(file.getOriginalFilename(), "subtitle");
            try (InputStream inputStream = file.getInputStream()) {
                uploads.add(new SubtitleService.LocalSubtitleFile(name, inputStream.readAllBytes()));
            }
        }
        if (uploads.isEmpty()) {
            return Result.error("请至少选择一个字幕文件");
        }

        Ani ani = aniOpt.get();
        try {
            return Result.success(subtitleService.importLocalSubtitles(ani, uploads, dryRun));
        } catch (Exception e) {
            log.error("批量导入字幕失败 {}: {}", ani.getTitle(), ExceptionUtils.getMessage(e));
            return Result.error("导入失败: " + ExceptionUtils.getMessage(e));
        }
    }

    @Auth
    @Operation(summary = "搜索射手网(ASSRT)字幕候选（单次搜索，返回候选供用户挑选）")
    @PostMapping("/subtitleAssrtSearch")
    public Result<Map<String, Object>> subtitleAssrtSearch(@RequestBody Map<String, Object> body) {
        Result<Ani> aniResult = resolveFetchAni(body);
        if (aniResult.getCode() != 200) {
            return Result.error(aniResult.getMessage());
        }
        Ani ani = aniResult.getData();
        try {
            SubtitleService.AssrtSearchResult search = subtitleService.searchAssrt(ani);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("searchId", search.getSearchId());
            data.put("keyword", search.getKeyword());
            data.put("candidates", search.getCandidates());
            data.put("total", search.getCandidates().size());
            return Result.success(data);
        } catch (Exception e) {
            log.warn("射手网字幕搜索失败 {}: {}", ani.getTitle(), ExceptionUtils.getMessage(e));
            return Result.error("搜索失败: " + ExceptionUtils.getMessage(e));
        }
    }

    @Auth
    @Operation(summary = "预览射手网(ASSRT)字幕获取（下载选中候选，不写盘，供二次确认）")
    @PostMapping("/subtitleFetchPreview")
    public Result<Map<String, Object>> subtitleFetchPreview(@RequestBody Map<String, Object> body) {
        Result<Ani> aniResult = resolveFetchAni(body);
        if (aniResult.getCode() != 200) {
            return Result.error(aniResult.getMessage());
        }
        Ani ani = aniResult.getData();
        String searchId = body.get("searchId") == null ? null : String.valueOf(body.get("searchId"));
        if (StrUtil.isBlank(searchId)) {
            return Result.error("参数缺失: searchId（请先搜索候选字幕）");
        }
        int index;
        try {
            index = Integer.parseInt(String.valueOf(body.get("index")));
        } catch (NumberFormatException e) {
            return Result.error("参数缺失或非法: index（候选序号）");
        }
        try {
            SubtitleService.FetchPlan plan = subtitleService.planFetchFromAssrt(ani, searchId, index);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("planId", plan.getPlanId());
            data.put("items", plan.previewItems());
            data.put("total", plan.getItems().size());
            data.put("matched", plan.matchedCount());
            return Result.success(data);
        } catch (Exception e) {
            log.warn("射手网字幕预览失败 {}: {}", ani.getTitle(), ExceptionUtils.getMessage(e));
            return Result.error("获取字幕失败: " + ExceptionUtils.getMessage(e));
        }
    }

    @Auth
    @Operation(summary = "执行射手网(ASSRT)字幕写入（消费预览计划）")
    @PostMapping("/subtitleFetch")
    public Result<SubtitleService.ImportResult> subtitleFetch(@RequestBody Map<String, Object> body) {
        String planId = body == null || body.get("planId") == null ? null : String.valueOf(body.get("planId"));
        if (StrUtil.isBlank(planId)) {
            return Result.error("参数缺失: planId");
        }
        SubtitleService.FetchPlan plan = SubtitleService.takePlan(planId);
        if (plan == null) {
            return Result.error("预览已过期，请重新获取字幕预览后再确认");
        }
        try {
            return Result.success(subtitleService.applyFetchPlan(plan));
        } catch (Exception e) {
            log.error("射手网字幕写入失败: {}", ExceptionUtils.getMessage(e));
            return Result.error("写入失败: " + ExceptionUtils.getMessage(e));
        }
    }

    /**
     * 射手网获取的公共前置校验：订阅存在 + 开关已开启 + Token 已配置。
     * 未通过时返回 {@code code != 200} 的结果，由调用方直接透传 message。
     */
    private Result<Ani> resolveFetchAni(Map<String, Object> body) {
        String aniId = body == null || body.get("aniId") == null ? null : String.valueOf(body.get("aniId"));
        if (StrUtil.isBlank(aniId)) {
            return Result.error("参数缺失: aniId");
        }
        if (!subtitleService.isManualFetchEnabled()) {
            return Result.error("未开启「字幕手动获取」，请先在 设置 → 其他设置 中开启");
        }
        if (StrUtil.isBlank(ConfigUtil.CONFIG.getAssrtToken())) {
            return Result.error("未配置 ASSRT Token，请在 设置 → 其他设置 中填写");
        }
        Optional<Ani> aniOpt = AniUtil.getAniList().stream()
                .filter(a -> Objects.equals(a.getId(), aniId))
                .findFirst();
        if (aniOpt.isEmpty()) {
            return Result.error("订阅不存在");
        }
        return Result.success(aniOpt.get());
    }

    @Auth
    @Operation(summary = "字幕匹配日志")
    @PostMapping("/subtitleMatchLog")
    public Result<List<SubtitleMatchLogEntry>> subtitleMatchLog(@RequestBody(required = false) Map<String, Object> body) {
        int limit = 100;
        if (body != null && body.get("limit") != null) {
            try {
                limit = Integer.parseInt(String.valueOf(body.get("limit")));
            } catch (NumberFormatException ignored) {
            }
        }
        if (limit <= 0 || limit > 200) {
            limit = 200;
        }
        return Result.success(SubtitleMatchLog.list(limit));
    }

    @Auth
    @Operation(summary = "清空字幕匹配日志")
    @PostMapping("/subtitleMatchLogClear")
    public Result<Map<String, Object>> subtitleMatchLogClear() {
        SubtitleMatchLog.clear();
        return Result.success(Map.of("ok", true));
    }
}
