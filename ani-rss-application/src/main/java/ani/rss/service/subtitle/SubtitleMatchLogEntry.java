package ani.rss.service.subtitle;

import com.google.gson.annotations.SerializedName;

/**
 * 单条字幕匹配记录（供「字幕匹配」面板展示）。
 */
public class SubtitleMatchLogEntry {

    /** 匹配时间（epoch 毫秒） */
    @SerializedName("time")
    private long time;

    /** 对应视频文件名 */
    @SerializedName("videoName")
    private String videoName;

    /** 字幕原始文件名（压缩包内命中条目 / 单文件候选名） */
    @SerializedName("originalName")
    private String originalName;

    /** 匹配重命名后的字幕文件名 */
    @SerializedName("renamedName")
    private String renamedName;

    /** 解析出的季数（>=1）；无法确定为 null */
    @SerializedName("season")
    private Integer season;

    /** 状态：已匹配 / 未命中 / 无候选 等 */
    @SerializedName("status")
    private String status;

    /** 语言标签（chs / cht / 空） */
    @SerializedName("lang")
    private String lang;

    public SubtitleMatchLogEntry() {
    }

    public SubtitleMatchLogEntry(long time, String videoName, String originalName,
                                String renamedName, Integer season, String status, String lang) {
        this.time = time;
        this.videoName = videoName;
        this.originalName = originalName;
        this.renamedName = renamedName;
        this.season = season;
        this.status = status;
        this.lang = lang;
    }

    public long getTime() {
        return time;
    }

    public String getVideoName() {
        return videoName;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getRenamedName() {
        return renamedName;
    }

    public Integer getSeason() {
        return season;
    }

    public String getStatus() {
        return status;
    }

    public String getLang() {
        return lang;
    }
}
