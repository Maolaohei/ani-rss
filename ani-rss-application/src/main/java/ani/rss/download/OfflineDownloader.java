package ani.rss.download;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.entity.OpenListFileInfo;

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
        ALREADY_AT_TOP,
        /**
         * 无法判断：网盘列举失败/熔断冷却中/对账过程中抛异常。
         * <p>
         * 必须与 {@link #NOT_FOUND} 严格区分——{@code NOT_FOUND} 是"查过了，确实没有"，
         * 而本值只是"这次没查成"。调用方<b>不得</b>据此删除种子记录或重新下载，
         * 否则一次网盘抖动就会把整季的记录清掉并重新下单。
         */
        UNVERIFIABLE
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
     * 严格版列举：网盘 API 查询失败时抛出，而非静默返回空列表。
     * <p>
     * 用于必须区分"目录确实为空"与"查询失败"的场景（如预览的「本地存在」列，
     * 查不到时应显示"存疑"而不是谎报"不存在"）。
     * 默认委托 {@link #listFileNames}（无法区分失败）——实现类应覆盖以真正支持该语义。
     */
    default List<String> listFileNamesStrict(String dirPath) {
        return listFileNames(dirPath);
    }

    /**
     * 严格版文件列举（含大小/修改时间），供媒体库等需要文件属性的场景使用。
     * 查询失败时抛出；默认不支持（抛异常而非返回空列表，避免把"查不到"静默当成"没有"）。
     */
    default List<OpenListFileInfo> listFilesStrict(String dirPath) {
        throw new UnsupportedOperationException("当前下载器不支持列出网盘文件信息");
    }

    /**
     * 只读探测：列出该目录的<b>直接</b>子项名（不递归）。
     * <p>
     * 供自检这类"只看一眼目录在不在"的场景：{@link #listFilesStrict} 是递归的，
     * 对一个根目录用它等于把整棵目录树都列一遍。
     *
     * @param dirPath 网盘目录
     * @return 直接子项名（目录确实为空时是空列表）
     * @throws OpenListApi.OpenListDirNotFoundException 目录不存在。这是<b>业务结果</b>（确认没有），
     *                                                  不是故障，调用方据此区分"挂载名配错"与"尚未创建"
     * @throws RuntimeException                          其它查询失败（超时/5xx/冷却中）
     */
    default List<String> probeDirectChildren(String dirPath) {
        throw new UnsupportedOperationException("当前下载器不支持目录探测");
    }

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
