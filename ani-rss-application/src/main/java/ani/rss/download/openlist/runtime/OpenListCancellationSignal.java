package ani.rss.download.openlist.runtime;

import java.util.function.BooleanSupplier;

/**
 * OpenList 离线等待所需的取消信号。
 * <p>
 * 本接口只回答“本轮 RSS 是否已请求取消”，不把调度器的状态对象暴露给下载模块。
 * 默认实现表示没有外部取消请求，便于纯单元测试直接构造下载器。
 */
@FunctionalInterface
public interface OpenListCancellationSignal {
    OpenListCancellationSignal NONE = () -> false;

    boolean isCancellationRequested();

    /**
     * 本次取消是否只是「让路」——手动刷新要插队，让周期任务尽快退出。
     * <p>
     * 让路不是用户取消：在途的离线任务、pending 标记、临时目录都必须原样保留，
     * 否则一次「添加订阅并立即下载」就会把别的订阅正在下（甚至刚下完）的离线任务删掉。
     * 默认 false，便于纯单元测试与既有 lambda 实现。
     */
    default boolean isYieldRequested() {
        return false;
    }
}
