package ani.rss.service.subtitle;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * ASSRT {@code sub/search} 返回的候选条目（一次「字幕发布」）。
 * <p>
 * 与 {@link SubtitleCandidate}（单个可下载字幕文件）不同，本类表示搜索结果里的<b>一条记录</b>，
 * 它可能内联多个字幕文件，也可能只给一个整包直链（合集）。用户在候选列表里挑选一条后，
 * 才会由 provider 解析出具体文件并进入下载流程——这样搜索阶段只消耗<b>一次</b>接口配额，
 * 不会因为逐条补全 {@code sub/detail} 而触发 30900 限流。
 */
@Data
public class AssrtSubtitleItem {

    /**
     * ASSRT 条目 id；0 表示接口未返回。仅在需要补全文件列表时用于调 {@code sub/detail}
     */
    private long id;

    /**
     * 展示标题：优先 videoname / native_name / release，供用户辨认字幕版本
     */
    private String title;

    /**
     * 接口返回的语言字段（chs / cht / eng / 空）
     */
    private String lang;

    /**
     * 是否整包：接口只给了压缩包直链，需下载解包后按集挑选
     */
    private boolean archive;

    /**
     * 整包直链（{@link #archive} 为 true 时有效）
     */
    private String url;

    /**
     * 内联的字幕文件列表；为空表示需要按需调 {@code sub/detail} 补全
     */
    private List<FileEntry> files = new ArrayList<>();

    /**
     * 排序评分（语言偏好 + 标题相似度 + 格式），仅用于展示排序，不做过滤
     */
    private double score;

    /**
     * 已知文件数量；{@code -1} 表示未知（需 detail 补全）
     */
    private int fileCount = -1;

    /**
     * 内联字幕文件条目。
     */
    @Data
    public static class FileEntry {
        /**
         * 字幕文件名（如 {@code S01E01.chs.ass}）
         */
        private String name;

        /**
         * 下载直链
         */
        private String url;

        /**
         * 扩展名（ass / srt / ssa / vtt / sub / zip / rar / 7z）
         */
        private String ext;

        /**
         * 识别出的语言（chs / cht / eng / 空）
         */
        private String lang;

        /**
         * 是否为压缩包
         */
        private boolean archive;
    }
}
