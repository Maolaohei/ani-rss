package ani.rss.service.subtitle;

import lombok.Data;

/**
 * 字幕候选：单个可被下载的字幕文件（来自某个字幕源的一次搜索结果）。
 */
@Data
public class SubtitleCandidate {

    /**
     * 下载地址（直链；也可能是指向 /sub/download 的重定向，provider 会自动二次解析）
     */
    private String url;

    /**
     * 字幕文件名（如 Movie.chs.ass）
     */
    private String fileName;

    /**
     * 扩展名（ass / srt / ssa / vtt / sub）
     */
    private String ext;

    /**
     * 识别出的语言标签（chs / cht / eng / 空）
     */
    private String lang;

    /**
     * 是否为压缩包（zip / rar / 7z），需解包后挑选
     */
    private boolean archive;

    /**
     * 匹配评分，越高越优
     */
    private double score;
}
