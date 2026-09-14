package ani.rss.service.subtitle;

/**
 * 字幕挑选结果：解包/下载后命中的具体字幕条目。
 * 携带「命中条目的原始文件名」与「解析出的季数」，供字幕匹配日志与预览面板使用。
 */
public class SubtitlePick {

    /**
     * 字幕内容字节
     */
    private final byte[] content;

    /**
     * 命中的原始文件名（压缩包内条目名 / 单文件候选名）
     */
    private final String originalName;

    /**
     * 解析出的季数（>=1）；无法确定时为 null
     */
    private final Integer resolvedSeason;

    public SubtitlePick(byte[] content, String originalName, Integer resolvedSeason) {
        this.content = content;
        this.originalName = originalName;
        this.resolvedSeason = resolvedSeason;
    }

    public byte[] getContent() {
        return content;
    }

    public String getOriginalName() {
        return originalName;
    }

    public Integer getResolvedSeason() {
        return resolvedSeason;
    }
}
