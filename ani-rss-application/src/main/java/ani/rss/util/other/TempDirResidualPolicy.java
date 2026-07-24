package ani.rss.util.other;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;
import java.util.Set;

/**
 * 判定 OpenList 保存目录下「临时目录残留」是否可安全一键清理。
 * <p>
 * 纯策略：不访问网盘。调用方提供目录名、是否存在最终成片、目录内是否有媒体、是否当前活动临时目录。
 */
public final class TempDirResidualPolicy {
    private TempDirResidualPolicy() {
    }

    public enum Action {
        /** 最终成片已在顶层，临时目录可强制整树删除 */
        FORCE_CLEAN,
        /** 仅垃圾/空壳，可清理 */
        JUNK_CLEAN,
        /** 当前下载占用，禁止清理 */
        PROTECT_ACTIVE,
        /** 信息不足，仅预览不自动删 */
        KEEP
    }

    public record Decision(Action action, String reason) {
    }

    /**
     * @param dirName            保存路径下的一级目录名（临时目录）
     * @param hasFinalSibling    同级是否已有匹配的最终视频/字幕（顶层）
     * @param hasProtectedMedia  临时目录内是否仍有视频/字幕
     * @param junkOnly           临时目录内仅有垃圾或为空
     * @param activeTempDirNames 当前正在使用的临时目录名集合（保护）
     */
    public static Decision decide(String dirName,
                                  boolean hasFinalSibling,
                                  boolean hasProtectedMedia,
                                  boolean junkOnly,
                                  Set<String> activeTempDirNames) {
        if (StrUtil.isBlank(dirName)) {
            return new Decision(Action.KEEP, "目录名为空");
        }
        if (activeTempDirNames != null && containsIgnoreCase(activeTempDirNames, dirName)) {
            return new Decision(Action.PROTECT_ACTIVE, "当前下载占用的临时目录");
        }
        if (hasFinalSibling) {
            // 与 cleanupTempDownloadDir(force=true) 对齐：最终文件已确认则可整树删
            return new Decision(Action.FORCE_CLEAN, "最终成片已在顶层，可强制删除临时目录");
        }
        if (junkOnly && !hasProtectedMedia) {
            return new Decision(Action.JUNK_CLEAN, "临时目录仅垃圾或为空");
        }
        if (hasProtectedMedia) {
            return new Decision(Action.KEEP, "临时目录内仍有媒体且未见最终成片，需人工确认");
        }
        return new Decision(Action.KEEP, "无法判断，保留");
    }

    public static boolean looksLikeTempEpisodeDir(String dirName, String seasonKey) {
        if (StrUtil.isBlank(dirName)) {
            return false;
        }
        if (StrUtil.isNotBlank(seasonKey) && dirName.toLowerCase(Locale.ROOT).contains(seasonKey.toLowerCase(Locale.ROOT))) {
            return true;
        }
        // 合集源标题目录：不含扩展名的长标题目录常见
        return !dirName.contains(".") && dirName.length() >= 2;
    }

    private static boolean containsIgnoreCase(Set<String> set, String value) {
        for (String s : set) {
            if (s != null && s.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
