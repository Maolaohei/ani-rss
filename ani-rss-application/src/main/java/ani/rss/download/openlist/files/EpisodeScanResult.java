package ani.rss.download.openlist.files;

import ani.rss.entity.OpenListFileInfo;

import java.util.List;
import java.util.Set;

/**
 * 单次本集文件扫描结果。
 * alreadyFinal=true 表示本集文件已全部位于 savePath 顶层，可直接判完成（调用方负责通知/清理）。
 */
public record EpisodeScanResult(List<OpenListFileInfo> videoList,
                                List<OpenListFileInfo> subtitleList,
                                List<OpenListFileInfo> openListFileInfos,
                                Set<String> cloudSourceDirs,
                                boolean alreadyFinal) {
    public static EpisodeScanResult empty(Set<String> cloudSourceDirs) {
        return new EpisodeScanResult(List.of(), List.of(), List.of(), cloudSourceDirs, false);
    }
}
