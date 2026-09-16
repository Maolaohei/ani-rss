package ani.rss.commons;

import ani.rss.entity.Ani;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code releaseDate} 的序列化格式与解析宽容度。
 *
 * <h2>为什么要单独测这个</h2>
 * {@link DateAdapter} 从 {@code SimpleDateFormat} 换成了 {@code DateTimeFormatter}（修线程安全问题）。
 * 这个替换有两个容易踩的坑，本测试把它们钉住：
 * <ol>
 *   <li><b>输出格式不能变</b>：仍是 {@code yyyy-MM-dd}。若误用输入侧那个更宽松的 pattern 去格式化，
 *       会写出 {@code 2024-1-5}，等于把所有订阅的日期格式改掉；</li>
 *   <li><b>解析宽容度不能变窄</b>：{@code SimpleDateFormat.parse(String)} 允许尾部有未消费内容
 *       （如 {@code 2024-01-15 00:00:00}），也允许 {@code 2024-1-5}。
 *       若改用 {@code LocalDate.parse}（要求整串消费），历史/手改过的数据会突然解析失败。</li>
 * </ol>
 * 注意：本类的单元断言必须<b>直接调用 adapter</b>。裸 {@code Date} 走的是 Gson 内置日期适配器
 * （格式由 {@code setDateFormat} 决定），与 {@code @JsonAdapter} 注册的 {@link DateAdapter} 是两回事；
 * 端到端链路单独用 {@link Ani} 覆盖。
 */
class DateAdapterTest {

    private final DateAdapter adapter = new DateAdapter();

    private static Date dateOf(int year, int month, int day) {
        return Date.from(LocalDate.of(year, month, day)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant());
    }

    private static String format(Date date, String pattern) {
        return new SimpleDateFormat(pattern).format(date);
    }

    @Test
    void serializeKeepsIsoDatePattern() {
        assertEquals("2024-01-05", adapter.serialize(dateOf(2024, 1, 5), Date.class, null).getAsString(),
                "输出格式必须保持 yyyy-MM-dd（补零），否则会改写全部既有数据");
    }

    @Test
    void serializeNullReturnsNull() {
        assertNull(adapter.serialize(null, Date.class, null));
    }

    @Test
    void deserializeAcceptsPaddedDate() {
        Date date = adapter.deserialize(new JsonPrimitive("2024-01-15"), Date.class, null);
        assertNotNull(date);
        assertEquals("2024-01-15", format(date, "yyyy-MM-dd"));
    }

    @Test
    void deserializeAcceptsNonPaddedDate() {
        // SimpleDateFormat 原本就接受 2024-1-5，不能变严
        Date date = adapter.deserialize(new JsonPrimitive("2024-1-5"), Date.class, null);
        assertNotNull(date);
        assertEquals("2024-01-05", format(date, "yyyy-MM-dd"));
    }

    @Test
    void deserializeToleratesTrailingContent() {
        // 历史数据里出现过 "2024-01-15 00:00:00" 这类值：原实现只解析前 10 个字符即成功
        Date date = adapter.deserialize(new JsonPrimitive("2024-01-15 00:00:00"), Date.class, null);
        assertNotNull(date);
        assertEquals("2024-01-15", format(date, "yyyy-MM-dd"));
    }

    @Test
    void deserializeYearOnlyUsesHutoolDateTime() {
        Date date = adapter.deserialize(new JsonPrimitive("2024"), Date.class, null);
        assertNotNull(date);
        assertEquals("2024", format(date, "yyyy"));
    }

    @Test
    void deserializeBlankOrNullReturnsNull() {
        assertNull(adapter.deserialize(null, Date.class, null));
        assertNull(adapter.deserialize(com.google.gson.JsonNull.INSTANCE, Date.class, null));
        assertNull(adapter.deserialize(new JsonPrimitive(""), Date.class, null));
        assertNull(adapter.deserialize(new JsonPrimitive("   "), Date.class, null));
    }

    @Test
    void deserializeUnparsableThrows() {
        assertThrows(Exception.class,
                () -> adapter.deserialize(new JsonPrimitive("不是日期"), Date.class, null));
    }

    /**
     * 走完整实体链路：{@code @JsonAdapter(DateAdapter.class)} 必须仍然挂在 releaseDate 上，
     * 且共享的 {@link GsonStatic#GSON} 能正常往返。
     */
    @Test
    void aniRoundTripThroughSharedGson() {
        Ani ani = new Ani()
                .setId("test-id")
                .setTitle("测试番剧")
                .setReleaseDate(dateOf(2023, 10, 1));

        String json = GsonStatic.toJson(ani);
        // 比对前去掉空白：这里要钉的是"日期格式"，不是"有没有缩进"
        // （共享 GSON 自 P1-12 起输出 compact，格式化能力挪到了 GsonStatic.PRETTY_GSON）
        assertTrue(json.replaceAll("\\s", "").contains("\"releaseDate\":\"2023-10-01\""),
                "releaseDate 应被序列化为 yyyy-MM-dd，实际 JSON: " + json);

        Ani parsed = GsonStatic.fromJson(json, Ani.class);
        assertEquals("2023-10-01", format(parsed.getReleaseDate(), "yyyy-MM-dd"));
    }

    /**
     * 并发往返不得抛异常、不得产出错误日期。
     * 原实现用一个共享的 {@code SimpleDateFormat}，并发下可能抛 {@code NumberFormatException}
     * 或把日期算错 —— 本测试用于守住回归。
     */
    @Test
    void concurrentRoundTripIsSafe() throws Exception {
        int threads = 8;
        int iterations = 300;
        Date fixed = dateOf(2020, 1, 1);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    for (int n = 0; n < iterations; n++) {
                        String json = GsonStatic.toJson(new Ani().setId("id-" + n).setReleaseDate(fixed));
                        // 同样只比对日期格式，不依赖缩进（P1-12 后共享 GSON 输出 compact）
                        if (!json.replaceAll("\\s", "").contains("\"releaseDate\":\"2020-01-01\"")) {
                            return "序列化结果异常: " + json;
                        }
                        Ani parsed = GsonStatic.fromJson(json, Ani.class);
                        String actual = format(parsed.getReleaseDate(), "yyyy-MM-dd");
                        if (!"2020-01-01".equals(actual)) {
                            return "反序列化得到错误日期: " + actual;
                        }
                    }
                    return "OK";
                }));
            }
            for (java.util.concurrent.Future<String> future : futures) {
                assertEquals("OK", future.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
