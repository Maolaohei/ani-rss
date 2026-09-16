package ani.rss.task;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.log.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@FunctionalInterface
public interface BaseTask extends Consumer<AtomicBoolean> {

    Log log = Log.get();

    /**
     * 分片睡眠的片长（{@link #sleepInterruptibly}）。取 500ms 是在"停止响应速度"与
     * "唤醒次数"之间折中：一小时周期多出 7200 次循环判断，可忽略不计。
     */
    long SLEEP_SLICE_MS = 500L;

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
                sleepInterruptibly(loop, 60_000);
            }
        }
        log.info("{} 任务已停止", threadName);
    }

    /**
     * 周期等待，<b>但会被任务旗标提前结束</b>。
     * <p>
     * 任务尾部的大周期等待（BGM 12 小时、磁盘检查数十分钟、RSS 轮询间隔）原先直接用
     * {@link ThreadUtil#sleep}。这有两个问题：
     * <ol>
     *   <li><b>停止不够快</b>：只能靠 {@code interrupt()} 打断，而线程一旦正卡在不可中断的
     *       socket 读 / 磁盘 IO 上，打断就失效，于是 30 秒的停止窗口被耗光；</li>
     *   <li><b>被放弃的线程会残留</b>：停止超时后线程仍活着，若它持有的旗标又被后续
     *       {@code start()} 置回 true，它就会<b>复活并重复跑轮次</b>。</li>
     * </ol>
     * 这里改成"分片睡眠 + 每片检查旗标"，最长 500ms 就能感知到停止请求，
     * 配合 {@code TaskService} 的代际旗标，被放弃的线程不会再复活。
     *
     * @return true = 正常睡满；false = 期间收到停止请求（应尽快返回，不再继续本轮）
     */
    default boolean sleepInterruptibly(AtomicBoolean loop, long millis) {
        if (millis <= 0) {
            return loop.get();
        }
        long deadline = System.currentTimeMillis() + millis;
        while (loop.get()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return true;
            }
            ThreadUtil.sleep(Math.min(remaining, SLEEP_SLICE_MS));
        }
        return false;
    }

    /**
     * @param loop 原子化布尔 用以控制循环
     */
    @Override
    void accept(AtomicBoolean loop);
}
