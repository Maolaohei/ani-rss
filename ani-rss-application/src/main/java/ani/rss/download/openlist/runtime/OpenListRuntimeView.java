package ani.rss.download.openlist.runtime;

/**
 * 调度层可见的 OpenList 运行态与取消能力。
 * <p>
 * 只暴露任务管理器需要的只读 hash 和取消动作，不把 OpenList 实现类型带入调度器。
 */
public interface OpenListRuntimeView {
    /**
     * 运行中当前离线 hash。
     */
    String currentInfoHash();

    /**
     * 取消当前离线任务。
     */
    void cancelCurrentOffline();

    /**
     * 运行中离线等待快照。返回具体 DTO 类型由模块适配层转换。
     */
    Object offlineWaitSnapshot();

    /**
     * 离线任务残留快照。
     */
    Object residualSnapshot();

    /**
     * 临时目录残留快照。
     */
    Object tempDirResidualSnapshot();

}
