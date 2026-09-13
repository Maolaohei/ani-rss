package ani.rss.util.other;

import ani.rss.commons.FileUtils;
import ani.rss.entity.Config;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 磁盘空间探测。
 * <p>
 * 自托管场景下"下载盘写满"是全线故障的典型诱因，但此前只有事后日志里的一句
 * "保存失败,请检查磁盘/权限"。本类把下载根、剧场版根、完结迁移根与配置目录的
 * 可用空间探测出来，供周期任务做阈值预警。
 * <p>
 * 路径解析规则与 {@code FileController.allowedFileRoots} 保持一致：
 * 取模板中 {@code ${} 之前的静态前缀作为根。
 */
@Slf4j
public final class DiskMonitorUtil {

    private DiskMonitorUtil() {
    }

    /**
     * 单个挂载点探测结果
     *
     * @param label       展示名（如"下载目录"）
     * @param path        实际探测路径
     * @param total       总容量（字节），不可测时为 -1
     * @param usable      可用容量（字节），不可测时为 -1
     * @param usedPercent 已用百分比（0-100），不可测时为 -1
     * @param measurable  是否可测量（网络盘/未挂载返回 false）
     */
    public record Mount(String label, String path, long total, long usable,
                        double usedPercent, boolean measurable) {
    }

    /**
     * 探测所有需要关注的路径
     */
    public static List<Mount> probe() {
        Config config = ConfigUtil.CONFIG;
        Map<String, String> candidates = new LinkedHashMap<>();
        addIfPresent(candidates, "下载目录", staticRoot(config.getDownloadPathTemplate()));
        addIfPresent(candidates, "剧场版目录", staticRoot(config.getOvaDownloadPathTemplate()));
        addIfPresent(candidates, "完结迁移目录", staticRoot(config.getCompletedPathTemplate()));
        addIfPresent(candidates, "配置目录", ConfigUtil.getConfigDir().toString());

        List<Mount> mounts = new ArrayList<>();
        for (Map.Entry<String, String> entry : candidates.entrySet()) {
            mounts.add(probeOne(entry.getKey(), entry.getValue()));
        }
        return mounts;
    }

    /**
     * 探测单个路径
     */
    public static Mount probeOne(String label, String path) {
        if (StrUtil.isBlank(path)) {
            return new Mount(label, path, -1, -1, -1, false);
        }
        try {
            File file = new File(path);
            // 路径可能尚不存在（首次启动），向上回溯到最近的已存在祖先再探测
            File probe = file;
            int depth = 0;
            while (probe != null && !probe.exists() && depth < 32) {
                probe = probe.getParentFile();
                depth++;
            }
            if (probe == null || !probe.exists()) {
                return new Mount(label, path, -1, -1, -1, false);
            }
            long total = probe.getTotalSpace();
            long usable = probe.getUsableSpace();
            if (total <= 0 || usable < 0) {
                // 网络盘 / 未挂载：getTotalSpace 常返回 0
                return new Mount(label, path, total, usable, -1, false);
            }
            double used = (double) (total - usable) / total * 100.0;
            used = Math.round(used * 10.0) / 10.0;
            return new Mount(label, FileUtils.getAbsolutePath(probe), total, usable, used, true);
        } catch (Exception e) {
            log.debug("探测磁盘空间失败 {} {}: {}", label, path, e.getMessage());
            return new Mount(label, path, -1, -1, -1, false);
        }
    }

    /**
     * 取模板的静态前缀作为根（无静态前缀时返回 null）
     */
    public static String staticRoot(String template) {
        if (StrUtil.isBlank(template)) {
            return null;
        }
        String firstLine = StrUtil.split(template, "\n", true, true)
                .stream()
                .findFirst()
                .orElse("");
        if (StrUtil.isBlank(firstLine)) {
            return null;
        }
        String staticRoot = firstLine.split("\\$\\{")[0];
        if (StrUtil.isBlank(staticRoot)) {
            // 形如 ${title}/... 无静态前缀，无法确定根
            return null;
        }
        // 目录模板常以分隔符结尾，去掉尾部分隔符让 File 正确解析
        return StrUtil.removeSuffix(staticRoot, "/");
    }

    /**
     * 人类可读的容量
     */
    public static String format(long bytes) {
        return bytes < 0 ? "不可测" : FileUtil.readableFileSize(bytes);
    }

    private static void addIfPresent(Map<String, String> map, String label, String path) {
        if (StrUtil.isNotBlank(path)) {
            map.put(label, path);
        }
    }
}
