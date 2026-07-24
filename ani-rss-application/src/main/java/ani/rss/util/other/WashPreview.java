package ani.rss.util.other;

import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import ani.rss.enums.StringEnum;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 洗版/备用 RSS 删除预览（纯函数）。
 */
public final class WashPreview {
    private WashPreview() {
    }

    public record Candidate(String name, String kind, String reason) {
    }

    /**
     * @param reName          即将下载的重命名（含 SxxExx）
     * @param torrentNames    同下载目录下的种子/任务名
     * @param fileNames       同目录下的文件/文件夹名
     * @param standbyRss      是否开启备用 RSS
     * @param delete          是否开启自动删除
     * @param coexist         是否多字幕组共存
     */
    public static List<Candidate> preview(String reName,
                                          List<String> torrentNames,
                                          List<String> fileNames,
                                          boolean standbyRss,
                                          boolean delete,
                                          boolean coexist) {
        List<Candidate> out = new ArrayList<>();
        if (!delete || !standbyRss || coexist) {
            return out;
        }
        if (StrUtil.isBlank(reName) || !ReUtil.contains(StringEnum.SEASON_REG, reName)) {
            return out;
        }
        String episode = ReUtil.get(StringEnum.SEASON_REG, reName, 0);
        if (StrUtil.isBlank(episode)) {
            return out;
        }
        String epLower = episode.toLowerCase(Locale.ROOT);

        if (torrentNames != null) {
            for (String name : torrentNames) {
                if (StrUtil.isBlank(name) || !ReUtil.contains(StringEnum.SEASON_REG, name)) {
                    continue;
                }
                String s = ReUtil.get(StringEnum.SEASON_REG, name, 0);
                if (s != null && s.equalsIgnoreCase(episode)) {
                    out.add(new Candidate(name, "torrent", "同集种子将被洗版删除: " + episode));
                }
            }
        }
        if (fileNames != null) {
            for (String name : fileNames) {
                if (StrUtil.isBlank(name)) {
                    continue;
                }
                String main = name;
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    main = name.substring(0, dot);
                }
                if (!ReUtil.contains(StringEnum.SEASON_REG, main)) {
                    // 目录名直接含 SxxExx
                    if (name.toLowerCase(Locale.ROOT).contains(epLower)) {
                        out.add(new Candidate(name, "file", "同集文件/目录可能被删除: " + episode));
                    }
                    continue;
                }
                String s = ReUtil.get(StringEnum.SEASON_REG, main, 0);
                if (s != null && s.equalsIgnoreCase(episode)) {
                    out.add(new Candidate(name, "file", "同集文件将被洗版删除: " + episode));
                }
            }
        }
        return out;
    }
}
