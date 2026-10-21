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
}
