package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.StandbyRss;
import ani.rss.entity.web.Result;
import ani.rss.util.other.AniUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.ZipUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * 订阅分享 / 一键导入。
 * <p>
 * 与既有的「导出配置」不同：导出是<b>整机配置</b>（含密钥），分享是<b>仅订阅定义</b>。
 * 因此这里采用<b>白名单复制</b>而非黑名单剔除——只把明确安全的字段写进分享码，
 * 其余（apiKey / bgmToken / 下载器密码 / 通知密钥 / 本地路径 / 进度 / 评分）一律不带。
 * 白名单的好处是将来 Ani 新增字段时默认不泄漏，不需要每次补黑名单。
 */
@Slf4j
@RestController
public class ShareController extends BaseController {

    /**
     * 分享码载荷上限（解码后字节），防止超大 payload 打爆内存
     */
    private static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;

    /**
     * 分享码字符串长度上限（Base64 字符数）。
     * <p>
     * 在 Base64 解码<b>之前</b>拦截：否则一个几百 MB 的请求体光是解码就会先吃掉大量堆。
     * 正常分享码只有几 KB，4M 字符留了足够余量。
     */
    private static final int MAX_CODE_LENGTH = 4 * 1024 * 1024;

    /**
     * 单次分享的订阅数上限
     */
    private static final int MAX_SHARE_COUNT = 500;

    public static class ShareRequest {
        private List<String> ids;
        private Boolean all;

        public List<String> getIds() {
            return ids;
        }

        public void setIds(List<String> ids) {
            this.ids = ids;
        }

        public Boolean getAll() {
            return all;
        }

        public void setAll(Boolean all) {
            this.all = all;
        }
    }

    public static class ImportCodeRequest {
        private String code;
        private String conflict;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getConflict() {
            return conflict;
        }

        public void setConflict(String conflict) {
            this.conflict = conflict;
        }
    }

    @Auth
    @Operation(summary = "生成订阅分享码")
    @PostMapping("/shareAni")
    public Result<Map<String, Object>> shareAni(@RequestBody ShareRequest request) {
        boolean all = request != null && Boolean.TRUE.equals(request.getAll());
        List<String> ids = request == null ? null : request.getIds();

        List<Ani> source = AniUtil.getAniList();
        List<Ani> selected;
        if (all) {
            selected = source;
        } else {
            if (ids == null || ids.isEmpty()) {
                return Result.error("未选择订阅");
            }
            selected = source.stream().filter(a -> ids.contains(a.getId())).toList();
        }
        if (selected.isEmpty()) {
            return Result.error("未找到可分享的订阅");
        }
        if (selected.size() > MAX_SHARE_COUNT) {
            return Result.error("单次最多分享 " + MAX_SHARE_COUNT + " 个订阅");
        }

        List<Ani> sanitized = new ArrayList<>();
        for (Ani ani : selected) {
            Ani safe = sanitize(ani);
            if (safe != null) {
                sanitized.add(safe);
            }
        }

        String json = GsonStatic.toJson(sanitized);
        String code = Base64.encodeUrlSafe(ZipUtil.gzip(json.getBytes(StandardCharsets.UTF_8)));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("count", sanitized.size());
        data.put("length", code.length());
        data.put("subscriptions", sanitized);
        return Result.success(data);
    }

    @Auth
    @Operation(summary = "按分享码导入订阅")
    @PostMapping("/importAniByCode")
    public Result<Map<String, Object>> importAniByCode(@RequestBody ImportCodeRequest request) {
        String code = request == null ? null : StrUtil.trimToNull(request.getCode());
        if (code == null) {
            return Result.error("分享码不能为空");
        }
        List<Ani> aniList;
        try {
            aniList = decode(code);
        } catch (Exception e) {
            log.warn("分享码解析失败: {}", ExceptionUtils.getMessage(e));
            return Result.error("分享码无效或已损坏");
        }
        if (aniList.isEmpty()) {
            return Result.error("分享码中没有任何订阅");
        }

        boolean skip = "SKIP".equalsIgnoreCase(request.getConflict());
        int added = 0;
        int skipped = 0;
        int replaced = 0;
        List<String> titles = new ArrayList<>();

        for (Ani ani : aniList) {
            try {
                AniUtil.verify(ani);
            } catch (Exception e) {
                skipped++;
                continue;
            }
            String title = ani.getTitle();
            Integer season = ani.getSeason();
            Optional<Ani> exists = AniUtil.getAniList().stream()
                    .filter(it -> Objects.equals(it.getTitle(), title) && Objects.equals(it.getSeason(), season))
                    .findFirst();
            if (exists.isPresent()) {
                if (skip) {
                    skipped++;
                    continue;
                }
                Ani target = exists.get();
                target.setUrl(ani.getUrl())
                        .setSubgroup(ani.getSubgroup())
                        .setMatch(ani.getMatch())
                        .setExclude(ani.getExclude())
                        .setOva(ani.getOva())
                        .setMediaType(ani.getMediaType())
                        .setType(ani.getType())
                        .setBgmUrl(ani.getBgmUrl())
                        .setOffset(ani.getOffset())
                        .setStandbyRssList(ani.getStandbyRssList())
                        .setPriority(ani.getPriority())
                        .setGroup(ani.getGroup())
                        .setCustomQualityProfileEnable(ani.getCustomQualityProfileEnable())
                        .setCustomQualityProfile(ani.getCustomQualityProfile());
                replaced++;
                titles.add(title);
                continue;
            }
            ani.setId(UUID.fastUUID().toString());
            AniUtil.getAniList().add(ani);
            AniUtil.invalidateIdIndex();
            added++;
            titles.add(title);
        }

        AniUtil.sync();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("added", added);
        data.put("replaced", replaced);
        data.put("skipped", skipped);
        data.put("titles", titles);
        Result<Map<String, Object>> result = Result.success(data);
        result.setMessage(StrUtil.format("导入完成：新增 {} / 替换 {} / 跳过 {}", added, replaced, skipped));
        return result;
    }

    static List<Ani> decode(String code) {
        if (StrUtil.isBlank(code) || code.length() > MAX_CODE_LENGTH) {
            throw new IllegalArgumentException("分享码不是合法的 Base64");
        }
        byte[] gzipped = Base64.decode(code);
        if (gzipped == null) {
            throw new IllegalArgumentException("分享码不是合法的 Base64");
        }
        // 流式解压并边读边限长。不能先 ZipUtil.unGzip 再校验长度：
        // 那是一次性把整个结果读进内存，gzip 压缩比可达千倍，几 MB 分享码就能展开成 GB 级
        // 数组把堆打爆，事后再判 raw.length 已经太晚。
        byte[] raw = ungzipLimited(gzipped, MAX_PAYLOAD_BYTES);
        List<Ani> list = GsonStatic.fromJsonList(new String(raw, StandardCharsets.UTF_8), Ani.class);
        return list == null ? new ArrayList<>() : list;
    }

    /**
     * 流式解压 gzip，累计输出超过上限立即中止（防 zip bomb）。
     */
    private static byte[] ungzipLimited(byte[] gzipped, int maxBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            int len;
            while ((len = in.read(buffer)) != -1) {
                if (out.size() + len > maxBytes) {
                    throw new IllegalArgumentException("分享码内容过大");
                }
                out.write(buffer, 0, len);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalArgumentException("分享码不是合法的压缩数据");
        }
        return out.toByteArray();
    }

    /**
     * 白名单复制：只保留"订阅定义"必需且无敏感信息的字段。
     */
    static Ani sanitize(Ani ani) {
        if (ani == null || StrUtil.isBlank(ani.getUrl())) {
            return null;
        }
        List<StandbyRss> standby = new ArrayList<>();
        if (ani.getStandbyRssList() != null) {
            for (StandbyRss s : ani.getStandbyRssList()) {
                if (s == null || StrUtil.isBlank(s.getUrl())) {
                    continue;
                }
                standby.add(new StandbyRss()
                        .setLabel(s.getLabel())
                        .setUrl(s.getUrl())
                        .setOffset(s.getOffset()));
            }
        }
        return new Ani()
                .setTitle(ani.getTitle())
                .setUrl(ani.getUrl())
                .setSeason(ani.getSeason())
                .setSubgroup(ani.getSubgroup())
                .setMatch(ani.getMatch() == null ? new ArrayList<>() : new ArrayList<>(ani.getMatch()))
                .setExclude(ani.getExclude() == null ? new ArrayList<>() : new ArrayList<>(ani.getExclude()))
                .setGlobalExclude(ani.getGlobalExclude())
                .setOva(ani.getOva())
                .setMediaType(ani.getMediaType())
                .setType(ani.getType())
                .setBgmUrl(ani.getBgmUrl())
                .setImage(ani.getImage())
                .setEnable(ani.getEnable())
                .setOffset(ani.getOffset())
                .setOmit(ani.getOmit())
                .setDownloadNew(ani.getDownloadNew())
                .setPriority(ani.getPriority())
                .setGroup(ani.getGroup())
                .setNamingVersion(ani.getNamingVersion())
                .setCustomEpisode(ani.getCustomEpisode())
                .setCustomEpisodeStr(ani.getCustomEpisodeStr())
                .setCustomEpisodeGroupIndex(ani.getCustomEpisodeGroupIndex())
                .setCustomRenameTemplateEnable(ani.getCustomRenameTemplateEnable())
                .setCustomRenameTemplate(ani.getCustomRenameTemplate())
                .setCustomQualityProfileEnable(ani.getCustomQualityProfileEnable())
                .setCustomQualityProfile(ani.getCustomQualityProfile())
                .setCustomTagsEnable(ani.getCustomTagsEnable())
                .setCustomTags(ani.getCustomTags())
                .setStandbyRssList(standby);
    }
}
