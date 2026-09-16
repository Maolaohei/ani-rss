package ani.rss.commons;

import ani.rss.entity.Ani;
import org.junit.jupiter.api.Test;
import wushuo.tmdb.api.entity.Tmdb;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GsonStatic#GSON} 并发（反）序列化 {@link Tmdb} 的正确性。
 *
 * <h2>背景</h2>
 * {@code Tmdb.date} 上的 {@code @JsonAdapter(wushuo.tmdb.api.common.DateAdapter.class)}
 * 把 {@code java.text.DateFormat} 放在<b>实例字段</b>上，而 Gson 对每个 Gson 实例只创建
 * 一个适配器并复用、{@link GsonStatic#GSON} 又是全进程共享的静态单例 —— 于是所有线程
 * 共用同一个非线程安全的 {@code SimpleDateFormat}。
 * <p>
 * 影响面：{@link GsonStatic#GSON} 被 {@code WebMvcConfig} 直接设为 Spring MVC 消息转换器，
 * 而 {@code Ani} 里有 {@code Tmdb} 字段，所以订阅列表接口、{@code ani.v2.json} 落盘、
 * 订阅深拷贝都会走到这条路径；日期写坏会体现为 NFO 年份 / {@code releasedate} 以及
 * 「标题 (年份)」改名结果出错。
 * <p>
 * 修复方式见 {@link GsonStatic}：把 {@code Tmdb} 的（反）序列化转交给线程独占的 Gson 实例。
 * 本用例在修复前<b>必然失败</b>（实测约 36% 的日期被写错 + 偶发
 * {@code ArrayIndexOutOfBoundsException}），修复后稳定通过。
 */
class GsonStaticTmdbConcurrencyTest {

    private static final int THREADS = 4;
    private static final int LOOPS = 2000;
    private static final Pattern DATE_FIELD = Pattern.compile("\"date\"\\s*:\\s*\"([^\"]*)\"");

    /** 每线程用不同基准日期 + 逐次递增，放大对共享 DateFormat 的竞争 */
    private static long millisOf(int tid, int j) {
        return 1_600_000_000_000L + (long) tid * 30L * 86_400_000L + (long) j * 86_400_000L;
    }

    /** 线程本地的参照实现，用来算"正确答案" */
    private static String expectedDate(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd").format(new Date(millis));
    }

    private static Tmdb tmdb(long millis) {
        return new Tmdb()
                .setId("1")
                .setName("n")
                .setDate(new Date(millis));
    }

    /* ==================== 并发执行框架 ==================== */

    private interface Worker {
        void run(int tid, int j);
    }

    /**
     * 并发跑 {@code THREADS × LOOPS} 次；把断言失败（错值）与异常分别计数并留证。
     */
    private static void runConcurrently(String what, Worker worker) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wrongValues = new AtomicInteger();
        AtomicInteger exceptions = new AtomicInteger();
        List<String> samples = Collections.synchronizedList(new ArrayList<>());

        List<Thread> pool = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            int tid = i;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < LOOPS; j++) {
                    try {
                        worker.run(tid, j);
                    } catch (AssertionError e) {
                        if (samples.size() < 3) {
                            samples.add("tid=" + tid + " " + e.getMessage());
                        }
                        wrongValues.incrementAndGet();
                    } catch (Throwable e) {
                        if (samples.size() < 3) {
                            samples.add("tid=" + tid + " 抛异常 " + e);
                        }
                        exceptions.incrementAndGet();
                    }
                }
            });
            thread.start();
            pool.add(thread);
        }
        start.countDown();
        for (Thread thread : pool) {
            thread.join();
        }

        String detail = " —— " + what + "：错值 " + wrongValues.get() + " 次、异常 "
                + exceptions.get() + " 次（共 " + (THREADS * LOOPS) + " 次）"
                + (samples.isEmpty() ? "" : "，样例：" + samples);
        assertEquals(0, exceptions.get(), "并发（反）序列化不得抛异常" + detail);
        assertEquals(0, wrongValues.get(), "并发（反）序列化不得写错日期" + detail);
    }

    private static String dateOf(String json) {
        Matcher m = DATE_FIELD.matcher(json);
        assertTrue(m.find(), "序列化结果里应当有 date 字段: " + json);
        return m.group(1);
    }

    /* ==================== 用例 ==================== */

    @Test
    void concurrent_serialization_of_tmdb_keeps_dates_correct() throws InterruptedException {
        runConcurrently("序列化 Tmdb", (tid, j) -> {
            long millis = millisOf(tid, j);
            assertEquals(expectedDate(millis), dateOf(GsonStatic.toJson(tmdb(millis))));
        });
    }

    @Test
    void concurrent_round_trip_of_tmdb_keeps_dates_correct() throws InterruptedException {
        runConcurrently("Tmdb 往返", (tid, j) -> {
            long millis = millisOf(tid, j);
            Tmdb back = GsonStatic.fromJson(GsonStatic.toJson(tmdb(millis)), Tmdb.class);
            assertNotNull(back);
            assertNotNull(back.getDate(), "反序列化后 date 不应为 null");
            // 该库的适配器只保留到"天"，因此按天比对
            assertEquals(expectedDate(millis), new SimpleDateFormat("yyyy-MM-dd").format(back.getDate()));
        });
    }

    /**
     * 覆盖真正会出问题的形态：{@code Ani} 里挂着 {@code Tmdb}。
     * 这正是 Spring MVC 消息转换器与 {@code AniUtil} 落盘所走的结构。
     * <p>
     * 顺带锁定对照项：{@code Ani.releaseDate} 用的是本项目自己写的线程安全
     * {@link DateAdapter}，它与 {@code Tmdb.date} 在同一份 JSON 里，两者都必须正确。
     */
    @Test
    void concurrent_round_trip_of_ani_containing_tmdb_keeps_dates_correct() throws InterruptedException {
        runConcurrently("Ani(含 Tmdb) 往返", (tid, j) -> {
            long millis = millisOf(tid, j);
            Ani ani = new Ani()
                    .setId("ani-" + tid + "-" + j)
                    .setTitle("标题")
                    .setReleaseDate(new Date(millis))
                    .setTmdb(tmdb(millis));

            Ani back = GsonStatic.fromJson(GsonStatic.toJson(ani), Ani.class);

            assertNotNull(back);
            assertNotNull(back.getTmdb(), "tmdb 字段不应丢失");
            assertNotNull(back.getTmdb().getDate(), "tmdb.date 不应为 null");
            assertEquals(expectedDate(millis),
                    new SimpleDateFormat("yyyy-MM-dd").format(back.getTmdb().getDate()),
                    "tmdb.date 被写坏");
            assertNotNull(back.getReleaseDate(), "releaseDate 不应为 null");
            assertEquals(expectedDate(millis),
                    new SimpleDateFormat("yyyy-MM-dd").format(back.getReleaseDate()),
                    "releaseDate 被写坏");
        });
    }

    /**
     * 格式守卫：隔离适配器只是换了承载它的 Gson 实例，不得改变 JSON 形态。
     * <p>
     * {@code Tmdb.date} 的线上格式是 {@code yyyy-MM-dd}（由库内适配器决定，无法覆盖），
     * 而 {@code GsonStatic} 自身配的是 {@code yyyy-MM-dd HH:mm:ss}——若哪天有人"顺手"
     * 把这个字段的格式改了，历史 {@code ani.v2.json} 的解析行为会跟着变。
     */
    @Test
    void tmdb_json_shape_is_unchanged() {
        String json = GsonStatic.toJson(tmdb(1_700_000_000_000L));

        assertEquals("2023-11-15", dateOf(json), "Tmdb.date 的格式应保持 yyyy-MM-dd");
        assertTrue(json.contains("\"id\""), "字段集合不应变化: " + json);
        assertTrue(json.contains("\"name\""), "字段集合不应变化: " + json);
        assertTrue(json.contains("\"otherMap\""), "字段集合不应变化: " + json);
    }
}
