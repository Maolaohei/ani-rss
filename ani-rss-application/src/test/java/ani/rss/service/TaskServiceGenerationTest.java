package ani.rss.service;

import ani.rss.task.BaseTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务生命周期的「代际」语义。
 *
 * <h2>为什么需要这组测试</h2>
 * 原实现让所有任务共享一个 {@code static final AtomicBoolean LOOP}。当 {@code stop()} 在
 * 30 秒内等不到线程退出（线程卡在不可中断的 socket 读 / 磁盘 IO / SQLite 锁上）时，
 * 它会记一条 WARN 并把线程从名单里丢掉；随后 {@code restart()} → {@code start()} 把那个
 * <b>共享旗标重新置为 true</b>，被丢掉的旧线程从 {@code accept()} 返回后就<b>复活了</b>，
 * 于是出现两个 {@code rss-task} 并发跑轮次 —— 同一集被提交两次下载、网盘 API 调用翻倍。
 *
 * <p>代际化之后，每次 {@code start()} 换一个新的 {@code AtomicBoolean}，线程只持有自己那一代。
 * 下面第 1 条就是钉住这个不变量的回归测试：<b>开启新一代不得复活旧一代</b>。
 * 这条断言在旧实现下必然失败（旧实现只有一个实例，置位就是置位）。
 */
class TaskServiceGenerationTest {

    @AfterEach
    void tearDown() {
        // 不把"运行中"的状态留给其它用例
        TaskService.stopCurrentGeneration();
    }

    /**
     * 核心回归：新一代置位不得把旧一代的旗标带回 true。
     * 旧实现下这条会失败，因为根本没有"两代"，只有一个共享实例。
     */
    @Test
    void newGenerationMustNotResurrectPreviousGeneration() {
        AtomicBoolean first = TaskService.newGeneration();
        assertTrue(first.get(), "新代际应为运行中");
        assertTrue(TaskService.isRunning());

        // 模拟 stop()：只关掉当前代际
        TaskService.stopCurrentGeneration();
        assertFalse(first.get(), "停止后当前代际应为 false");
        assertFalse(TaskService.isRunning());

        // 模拟紧随其后的 restart() → start()
        AtomicBoolean second = TaskService.newGeneration();

        assertNotSame(first, second, "新一代必须是新实例，否则旧线程会被复活");
        assertTrue(second.get(), "新一代应为运行中");
        assertFalse(first.get(),
                "上一代旗标被复活会导致「被放弃的线程」继续跑轮次 → 重复下载");
    }

    /**
     * 停止必须幂等：{@code stop()} 会被 {@code restart()}、配置保存、关停钩子多次调用，
     * 重复调用不能抛异常，也不能把状态改回"运行中"。
     */
    @Test
    void stopIsIdempotent() {
        TaskService.newGeneration();
        assertTrue(TaskService.isRunning());

        assertDoesNotThrow(() -> {
            TaskService.stopCurrentGeneration();
            TaskService.stopCurrentGeneration();
            TaskService.stopCurrentGeneration();
        });

        assertFalse(TaskService.isRunning());
    }

    /**
     * 自检读到的"是否运行"必须跟随当前代际，而不是某个残留状态。
     */
    @Test
    void isRunningFollowsCurrentGeneration() {
        TaskService.stopCurrentGeneration();
        assertFalse(TaskService.isRunning());

        TaskService.newGeneration();
        assertTrue(TaskService.isRunning());

        TaskService.stopCurrentGeneration();
        assertFalse(TaskService.isRunning());
    }

    /**
     * 被放弃的线程名单不应把"从未放弃过"的情况报成有残留。
     */
    @Test
    void abandonedThreadNamesIsEmptyWhenNothingWasAbandoned() {
        assertTrue(TaskService.abandonedThreadNames().isEmpty());
    }

    // ---------------- BaseTask 的循环与周期等待 ----------------

    /**
     * 任务循环只认自己那一代的旗标：旗标清掉后线程必须自行退出。
     */
    @Test
    void baseTaskLoopExitsOnItsOwnFlag() throws Exception {
        AtomicBoolean loop = new AtomicBoolean(true);
        CountDownLatch accepted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        BaseTask task = ignored -> {
            accepted.countDown();
            // 卡住一会儿，模拟"正在跑一轮"
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Thread thread = new Thread(() -> {
            try {
                task.run("test-task", loop);
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        thread.start();

        assertTrue(accepted.await(5, TimeUnit.SECONDS), "任务应已开始执行");
        loop.set(false);
        thread.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(thread.isAlive(), "旗标清掉后任务线程必须退出");
        assertTrue(failure.get() == null, "任务线程不应抛出异常: " + failure.get());
    }

    /**
     * 可中断的周期等待：停止请求应在 500ms 量级生效，
     * 而不是像原来那样只能等满整个周期（12 小时 / 15 分钟）或依赖 interrupt。
     */
    @Test
    void sleepInterruptiblyWakesUpOnStopRequest() {
        BaseTask task = ignored -> {
        };
        AtomicBoolean loop = new AtomicBoolean(true);

        Thread stopper = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            loop.set(false);
        });
        stopper.start();

        long begin = System.currentTimeMillis();
        boolean sleptFull = task.sleepInterruptibly(loop, TimeUnit.SECONDS.toMillis(30));
        long cost = System.currentTimeMillis() - begin;

        assertFalse(sleptFull, "旗标被清掉时应返回 false（表示本轮应尽快结束）");
        assertTrue(cost < TimeUnit.SECONDS.toMillis(5),
                "停止响应应在 500ms 量级生效，实际耗时 " + cost + "ms");
    }

    /**
     * 没有停止请求时必须睡满，不能被"分片"变成忙等或提前返回。
     */
    @Test
    void sleepInterruptiblySleepsFullDurationWhenNotStopped() {
        BaseTask task = ignored -> {
        };
        AtomicBoolean loop = new AtomicBoolean(true);

        long begin = System.currentTimeMillis();
        boolean sleptFull = task.sleepInterruptibly(loop, 600);
        long cost = System.currentTimeMillis() - begin;

        assertTrue(sleptFull, "未收到停止请求应睡满并返回 true");
        assertTrue(cost >= 550, "不应提前返回，实际 " + cost + "ms");
    }

    /**
     * 零/负时长不应把分片循环变成异常路径。
     */
    @Test
    void sleepInterruptiblyHandlesNonPositiveDuration() {
        BaseTask task = ignored -> {
        };
        assertDoesNotThrow(() -> {
            assertTrue(task.sleepInterruptibly(new AtomicBoolean(true), 0));
            assertTrue(task.sleepInterruptibly(new AtomicBoolean(true), -1));
            assertFalse(task.sleepInterruptibly(new AtomicBoolean(false), 1000));
        });
    }

    /**
     * 异常退避也走可中断等待：任务抛错后如果马上被要求停止，
     * 不应该再干等 60 秒。
     */
    @Test
    void backoffAfterFailureIsInterruptible() throws Exception {
        AtomicBoolean loop = new AtomicBoolean(true);
        AtomicInteger invocations = new AtomicInteger();

        BaseTask failing = ignored -> {
            invocations.incrementAndGet();
            throw new IllegalStateException("boom");
        };

        Thread thread = new Thread(() -> failing.run("test-failing-task", loop));
        thread.start();

        // 等到确实进了退避（说明异常被兜住、没有静默死亡）
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (invocations.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        loop.set(false);
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(thread.isAlive(),
                "异常退避必须可被停止请求打断，否则每次停止都要干等 60 秒");
        assertTrue(invocations.get() >= 1, "兜底逻辑应至少执行过一次");
    }
}
