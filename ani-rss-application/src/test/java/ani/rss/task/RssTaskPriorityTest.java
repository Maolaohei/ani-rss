package ani.rss.task;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订阅优先级与并发度解析。
 * <p>
 * RSS 调度是本仓库最敏感的代码（3.2.31 刚修完全局锁挂死与手动刷新误杀），
 * 因此这里固化两条底线：
 * <ol>
 *   <li>排序必须是<b>稳定</b>的——同级订阅保持原顺序，不打乱用户习惯；</li>
 *   <li>并发度<b>默认值不变</b>（3），非法配置一律回落，绝不出现 0 或超大并发。</li>
 * </ol>
 */
class RssTaskPriorityTest {

    private static Ani ani(String title, Integer priority) {
        return new Ani().setId(title).setTitle(title).setPriority(priority);
    }

    @Test
    void priorityOf_defaults_to_normal() {
        assertEquals(1, RssTask.priorityOf(null));
        assertEquals(1, RssTask.priorityOf(new Ani()));
        assertEquals(1, RssTask.priorityOf(ani("x", null)));
    }

    @Test
    void priorityOf_clamps_out_of_range() {
        assertEquals(0, RssTask.priorityOf(ani("x", 0)));
        assertEquals(1, RssTask.priorityOf(ani("x", 1)));
        assertEquals(2, RssTask.priorityOf(ani("x", 2)));
        // 越界值收敛到 [0,2]，避免脏数据把订阅排到最前或最后
        assertEquals(0, RssTask.priorityOf(ani("x", -5)));
        assertEquals(2, RssTask.priorityOf(ani("x", 99)));
    }

    @Test
    void sortByPriority_orders_high_first() {
        List<Ani> input = new ArrayList<>(List.of(
                ani("low", 2),
                ani("normal", 1),
                ani("high", 0)
        ));
        List<Ani> sorted = RssTask.sortByPriority(input);
        assertEquals(List.of("high", "normal", "low"),
                sorted.stream().map(Ani::getTitle).toList());
    }

    @Test
    void sortByPriority_is_stable_within_same_priority() {
        List<Ani> input = new ArrayList<>(List.of(
                ani("a", 1),
                ani("b", 0),
                ani("c", 1),
                ani("d", 0),
                ani("e", 1)
        ));
        List<Ani> sorted = RssTask.sortByPriority(input);
        // 高优先级内部保持 a 之前 b 的原始相对顺序 → b, d
        // 普通优先级内部保持 a, c, e
        assertEquals(List.of("b", "d", "a", "c", "e"),
                sorted.stream().map(Ani::getTitle).toList());
    }

    @Test
    void sortByPriority_does_not_mutate_input() {
        List<Ani> input = new ArrayList<>(List.of(ani("low", 2), ani("high", 0)));
        RssTask.sortByPriority(input);
        assertEquals(List.of("low", "high"), input.stream().map(Ani::getTitle).toList(),
                "排序不应改动调用方传入的列表");
    }

    @Test
    void resolveParallelism_keeps_default_when_unset() {
        assertEquals(3, RssTask.resolveParallelism(new Config(), 10));
        assertEquals(3, RssTask.resolveParallelism(null, 10));
    }

    @Test
    void resolveParallelism_clamps_and_respects_subscription_count() {
        // 上限 8
        assertEquals(8, RssTask.resolveParallelism(new Config().setRssConcurrency(100), 100));
        assertEquals(8, RssTask.resolveParallelism(new Config().setRssConcurrency(8), 100));
        // 下限 1：绝不能出现 0 线程池
        assertEquals(1, RssTask.resolveParallelism(new Config().setRssConcurrency(0), 100));
        assertEquals(1, RssTask.resolveParallelism(new Config().setRssConcurrency(-3), 100));
        // 不超过订阅数
        assertEquals(2, RssTask.resolveParallelism(new Config().setRssConcurrency(6), 2));
        assertEquals(1, RssTask.resolveParallelism(new Config().setRssConcurrency(6), 0));
    }

    @Test
    void max_parallelism_is_bounded() {
        assertEquals(8, RssTask.MAX_ANI_PARALLELISM,
                "并发上限变化需要重新评估下载器与源站压力");
    }
}
