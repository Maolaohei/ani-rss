package ani.rss.download;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;

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
     * 归位对账结果
     */
    enum RelocateResult {
        /** 目录下（含子目录）未发现本集文件 */
        NOT_FOUND,
        /** 子目录中存在本集文件且已成功重命名/移动到顶层 */
        RELOCATED,
        /** 本集文件本就完整位于顶层，无需处理 */
        ALREADY_AT_TOP
    }

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

    /**
     * 归位对账：downloadPath 子目录（临时目录/115 云下载残留）中存在本集文件而顶层缺失时，
     * 重命名并移动到顶层。用于：
     * <ul>
     *   <li>downloadAniLocked 信任种子记录前的周期对账（修复「显示已存在但文件不在顶层」）</li>
     *   <li>强制下载前的预检（发现子目录已有文件直接归位并恢复记录，免重复提交+超时等待）</li>
     * </ul>
     */
    default RelocateResult relocateEpisodeFiles(Ani ani, Item item, String downloadPath) {
        return RelocateResult.NOT_FOUND;
    }
}
