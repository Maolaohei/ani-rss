package ani.rss.download.openlist.residual;

import ani.rss.download.openlist.runtime.OpenListDownloadPathResolver;
import ani.rss.entity.OpenListFileInfo;
import ani.rss.entity.OpenListTaskInfo;

import java.util.List;
import java.util.Set;

/**
 * 残留治理所需的宿主能力：网盘列举/删除、离线任务操作与运行态读取。
 * <p>
 * 残留模块只通过这些方法访问宿主（OpenList），不反向依赖调度层、下载服务或容器查询；
 * 后续把 api 客户端与文件服务再拆开时，这里是天然的替换点。
 */
public interface OpenListResidualPort {
    /**
     * 单层列举（宽容版：失败返回空列表，仅用于探测与展示）。
     */
    List<OpenListFileInfo> list(String path, boolean refresh);

    /**
     * 清理路径专用的严格单层列举：查询失败抛出，「目录不存在」返回空列表。
     * <p>
     * 清理是不可逆动作，一次列举抖动就可能整树删掉仍含媒体的目录，
     * 因此清理路径必须能把「查不清」与「确认没有」区分开。
     */
    List<OpenListFileInfo> listForCleanup(String path) throws Exception;

    /**
     * 未完成的离线任务。
     */
    List<OpenListTaskInfo> undoneTasks();

    /**
     * 已完成的离线任务。
     */
    List<OpenListTaskInfo> doneTasks();

    /**
     * 取消任务（运行中任务应先 cancel 再 delete）。
     */
    void cancelTask(String tid);

    /**
     * 删除任务记录。
     */
    void deleteTask(String tid);

    /**
     * 整树删除网盘上的临时目录（force=true，仅在确认成片已归位后使用）。
     * <p>
     * <b>返回确认态</b>：实现必须"删除 + 回读"后才允许返回
     * {@link TempDirDeleteResult#DELETED}。旧签名是 void，调用方因此把
     * "限流失败""990009 已受理"一律当成删掉了，于是清理统计虚高、临时目录长期残留。
     */
    TempDirDeleteResult deleteTempDir(String savePath, String tempDirName);

    /**
     * 网盘列举熔断冷却剩余毫秒数（0=可用）。
     * <p>
     * 巡检必须在冷却期一条请求都不发：否则"清理 → 触发限流 → 更多残留"会互相喂大。
     */
    long listingCooldownRemainingMs();

    /**
     * 是否已登录：未登录一律不访问网盘。
     */
    boolean loggedIn();

    /**
     * 当前正在等待的离线 hash（保护集合；多 hash 并行时需全部）。
     */
    Set<String> currentInfoHashes();

    /**
     * 并行离线中每个任务占用的临时目录名（多槽保护集合）。
     */
    Set<String> activeTempDirNames();

    /**
     * 单槽等待快照里的临时目录名与标题（并行时只记录最后一个任务）。
     */
    List<String> offlineWaitNames();

    /**
     * 下载路径解析（订阅保存路径 → 网盘路径）。
     */
    OpenListDownloadPathResolver pathResolver();

    /**
     * 重启后恢复离线计划（只读比对，文件齐了才归位）。
     */
    void recoverOfflinePlans();
}
