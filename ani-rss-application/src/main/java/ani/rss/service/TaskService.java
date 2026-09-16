package ani.rss.service;

import ani.rss.task.BaseTask;
import ani.rss.task.BgmTask;
import ani.rss.task.DiskTask;
import ani.rss.task.RenameTask;
import ani.rss.task.RssTask;
import ani.rss.task.WeeklyReportTask;
import cn.hutool.core.text.NamingCase;
import cn.hutool.extra.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 后台任务线程的生命周期管理。
 *
 * <h2>为什么要「代际」而不是一个全局旗标</h2>
 * 原先全部任务共享一个 {@code static final AtomicBoolean LOOP}，{@code stop()} 置 false、
 * {@code start()} 置 true。这个模型在"停止超时"这个分支上是有缺陷的：
 * <ol>
 *   <li>{@code stop()} 等 30 秒后若线程仍未退出（卡在不可中断的 socket 读 / 磁盘 IO /
 *       SQLite 锁上），会记一条 WARN 并把它从 {@code THREADS} 里丢掉；</li>
 *   <li>随后的 {@code restart()} 调 {@code start()}，把那个<b>共享旗标重新置为 true</b>；</li>
 *   <li>被丢掉的那个旧线程最终从 {@code accept()} 返回，回到 {@code while (loop.get())}，
 *       此时旗标已经是 true —— 它<b>复活了</b>。</li>
 * </ol>
 * 结果是出现两个 {@code rss-task} 并发执行 RSS 轮次：同一集被提交两次下载、网盘 API 调用翻倍、
 * {@code ani.v2.json} 落盘翻倍。而订阅级读写锁是<b>可重入</b>的、且 RSS 主判定路径并不持有它，
 * 所以两个轮次之间没有任何互斥。
 *
 * <p>改为代际之后：每次 {@code start()} 都换一个<b>新的</b> {@link AtomicBoolean} 实例，
 * 线程只持有自己那一代的旗标。被放弃的旧线程手里的旗标永远是 false，<b>不可能被后续的
 * start() 复活</b>——这是结构性修复，不依赖"线程一定能被 interrupt 打断"这个假设。
 *
 * <h2>可观测</h2>
 * 被放弃的线程不再静默丢弃，而是记入 {@link #ABANDONED} 并由自检项展示，
 * 让"任务线程没退干净"这件事从"看不见"变成"看得见"。
 */
@Slf4j
@Service
public class TaskService {

    /**
     * 停止时等待任务线程退出的总上限。超过即放弃等待并记录，
     * 避免把 restart HTTP 接口无限期挂住。
     */
    static final int STOP_TIMEOUT_SECONDS = 30;

    /**
     * 当前代际的运行旗标。每次 {@link #start()} 换新实例；旧实例只被旧线程持有，
     * 永远不会被再次置为 true。
     */
    private static final AtomicReference<AtomicBoolean> CURRENT_LOOP =
            new AtomicReference<>(new AtomicBoolean(false));

    /** 当前代际的任务线程 */
    public static final List<Thread> THREADS = new Vector<>();

    /**
     * 历次停止中"30 秒内未退出、被放弃等待"的线程。
     * 它们持有的是<b>更早代际</b>的旗标，不会再跑新一轮，但仍在占用资源，需要被看见。
     */
    private static final List<Thread> ABANDONED = new Vector<>();

    /**
     * 任务循环是否处于运行状态（当前代际）。
     */
    public static boolean isRunning() {
        return CURRENT_LOOP.get().get();
    }

    /**
     * 被放弃等待的任务线程名（自检展示用）。已退出的会被顺带清理。
     */
    public static List<String> abandonedThreadNames() {
        pruneAbandoned();
        return ABANDONED.stream()
                .map(Thread::getName)
                .toList();
    }

    /**
     * 开始新的一代并返回其旗标（供测试直接验证"新一代不得复活旧一代"）。
     */
    static AtomicBoolean newGeneration() {
        AtomicBoolean loop = new AtomicBoolean(true);
        CURRENT_LOOP.set(loop);
        return loop;
    }

    /**
     * 结束当前代际（供测试使用）。
     */
    static void stopCurrentGeneration() {
        CURRENT_LOOP.get().set(false);
    }

    public synchronized void stop() {
        // 只关掉**当前**代际。更早代际的线程（含被放弃的）持有的旗标早已是 false，
        // 且不会再被任何 start() 置回 true。
        stopCurrentGeneration();

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(STOP_TIMEOUT_SECONDS);
        List<String> abandonedNames = new ArrayList<>();
        for (Thread thread : THREADS) {
            if (!thread.isAlive()) {
                continue;
            }
            try {
                // 等待现有任务结束：join(timeout) 循环，期间持续中断促使任务尽快退出
                while (thread.isAlive()) {
                    thread.interrupt();
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        break;
                    }
                    thread.join(Math.min(remaining, 1000));
                }
                if (thread.isAlive()) {
                    // 超时放弃等待：**记入名单**而不是像原先那样直接丢掉，
                    // 否则既看不到"没退干净"，又会在下一轮 start() 时被误认为已清理
                    abandonedNames.add(thread.getName());
                    ABANDONED.add(thread);
                }
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
        if (!abandonedNames.isEmpty()) {
            log.warn("以下任务线程在 {} 秒内未退出，已放弃等待（它们持有的是上一代旗标，重启后不会复活）: {}",
                    STOP_TIMEOUT_SECONDS, String.join(", ", abandonedNames));
        }
        THREADS.clear();
    }

    public synchronized void restart() {
        stop();
        start();
    }

    public synchronized void start() {
        if (isRunning() && !THREADS.isEmpty()) {
            log.warn("任务已经在运行中");
            return;
        }

        pruneAbandoned();

        // 新代际：旧线程（含被放弃的）手里的旗标不会被这一代置回 true
        AtomicBoolean loop = newGeneration();

        List<Class<? extends BaseTask>> classList = List.of(
                RenameTask.class, RssTask.class, BgmTask.class, DiskTask.class, WeeklyReportTask.class);

        List<Thread> created = new ArrayList<>(classList.size());
        try {
            for (Class<? extends BaseTask> aClass : classList) {
                BaseTask task = SpringUtil.getBean(aClass);
                String threadName = NamingCase.toKebabCase(aClass.getSimpleName());
                Thread thread = new Thread(() -> task.run(threadName, loop), threadName);
                created.add(thread);
            }
        } catch (Exception e) {
            // 回滚：否则旗标已置位、线程却未启动，后续任何 start() 都会被
            // "任务已经在运行中"永久拒绝，任务再也不会启动
            log.error("任务线程创建失败，本次启动已回滚: {}", e.getMessage(), e);
            loop.set(false);
            return;
        }

        THREADS.addAll(created);
        for (Thread thread : created) {
            thread.start();
        }
    }

    private static void pruneAbandoned() {
        synchronized (ABANDONED) {
            ABANDONED.removeIf(thread -> !thread.isAlive());
        }
    }
}
