package ani.rss.service;

import ani.rss.task.BaseTask;
import ani.rss.task.BgmTask;
import ani.rss.task.RenameTask;
import ani.rss.task.RssTask;
import cn.hutool.core.text.NamingCase;
import cn.hutool.extra.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class TaskService {
    public static final AtomicBoolean LOOP = new AtomicBoolean(false);
    public static final List<Thread> THREADS = new Vector<>();

    public synchronized void stop() {
        LOOP.set(false);
        // 总等待上限 30 秒：任务线程卡在不可中断 IO 时不无限阻塞 restart HTTP 接口
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        List<String> abandonedThreads = new ArrayList<>();
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
                    // 超时放弃等待，记录名单
                    abandonedThreads.add(thread.getName());
                }
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
        if (!abandonedThreads.isEmpty()) {
            log.warn("以下任务线程在 30 秒内未退出，已放弃等待: {}", String.join(", ", abandonedThreads));
        }
        THREADS.clear();
    }

    public synchronized void restart() {
        stop();
        start();
    }

    public synchronized void start() {
        if (LOOP.get() && !THREADS.isEmpty()) {
            log.warn("任务已经在运行中");
            return;
        }
        LOOP.set(true);

        List<Class<? extends BaseTask>> classList = List.of(RenameTask.class, RssTask.class, BgmTask.class);

        for (Class<? extends BaseTask> aClass : classList) {
            BaseTask task = SpringUtil.getBean(aClass);
            String name = aClass.getSimpleName();
            String threadName = NamingCase.toKebabCase(name);
            THREADS.add(new Thread(() -> task.run(threadName, LOOP)));
        }
        for (Thread thread : THREADS) {
            thread.start();
        }
    }
}
