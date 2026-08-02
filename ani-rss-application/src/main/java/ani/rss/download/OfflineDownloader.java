package ani.rss.download;

import java.util.List;

/**
 * 离线网盘型下载器能力接口（OpenList/Alist 等）。
 * <p>
 * 与 {@link BaseDownload}（本地型 qB/Transmission/Aria2 与离线型共有的提交/删除/重命名）
 * 不同，本接口表达「下载目标是网盘虚拟路径，本地文件系统不可见，需经 API 操作文件」的能力。
 * 业务方按能力分派（instanceof OfflineDownloader），而非逐个下载器特判。
 */
public interface OfflineDownloader {

    /**
     * 是否为离线长等待型（离线网盘工具提交后需长时间等待，不应占用 RSS 主线程池）。
     * 默认 false，实现类显式声明，避免误用。
     */
    default boolean isOffline() {
        return false;
    }

    /**
     * 强制下载用: 删除网盘目录下与 reName 匹配的已有文件/目录(主名相等或包含)。
     */
    void forceDeleteFiles(String dirPath, String reName);

    /**
     * 列出网盘目录下文件路径(递归, 带缓存), 供"本地已下载"判断使用。
     */
    List<String> listFileNames(String dirPath);
}
