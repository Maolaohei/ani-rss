package ani.rss.task;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.log.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@FunctionalInterface
public interface BaseTask extends Consumer<AtomicBoolean> {

    Log log = Log.get();

    default void run(String threadName, AtomicBoolean loop) {
        Thread.currentThread().setName(threadName);

        log.info("{} 任务正在运行", threadName);
        while (loop.get()) {
            try {
                accept(loop);
            } catch (Throwable e) {
                // 兜底：任何未捕获异常/错误都不允许任务线程静默死亡，60 秒后继续下一轮
                String message = threadName + " 任务执行异常，60 秒后继续: " + e.getMessage();
                log.error(message, e);
                ThreadUtil.sleep(60_000);
            }
        }
        log.info("{} 任务已停止", threadName);
    }

    /**
     * @param loop 原子化布尔 用以控制循环
     */
    @Override
    void accept(AtomicBoolean loop);
}
