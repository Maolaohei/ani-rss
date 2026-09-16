package ani.rss.util.basic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * URL 规范化（P2-12）。
 * <p>
 * 原实现每次构造请求都执行一次
 * {@code url.replaceAll("(?<!https?:?)//", "/")} —— 带后顾断言的正则会被
 * <b>重新编译</b>，而所有 HTTP 请求都走这里，属纯浪费。现在提为
 * {@code static final Pattern}。
 * <p>
 * 这次改动是"只换实现、不改行为"，因此用例的核心是<b>与原来的内联正则逐条对齐</b>：
 * 只要某条 URL 上两者结果不同，就说明重构改变了语义。
 */
class HttpRequestPlusNormalizeTest {

    /**
     * 覆盖各类形态：正常的、带重复斜杠的、协议相对地址、query 里也带 // 的、
     * 以及大小写不同的 scheme。
     */
    private static final List<String> SAMPLES = List.of(
            "https://example.com/a/b",
            "http://example.com/a/b",
            "https://example.com//a//b",
            "http://example.com//a//b",
            "https://example.com/a//b///c",
            "https://example.com/",
            "https://example.com",
            "HTTPS://example.com//a",
            "https://example.com/a?url=https://other.com//x",
            "https://example.com//a?x=1//2",
            "//example.com/a",
            "/a//b",
            ""
    );

    @Test
    void normalize_matches_the_inline_regex_it_replaced() {
        for (String url : SAMPLES) {
            String expected = url.replaceAll("(?<!https?:?)//", "/");
            assertEquals(expected, HttpRequestPlus.normalize(url),
                    "规范化结果必须与原来的内联正则一致: " + url);
        }
    }

    @Test
    void collapses_duplicate_slashes_in_the_path() {
        assertEquals("https://example.com/a/b", HttpRequestPlus.normalize("https://example.com//a//b"));
        /*
        注意：连续 3 个以上斜杠不会被一次折叠干净 —— replaceAll 是从左到右的非重叠匹配，
        a//b///c 会得到 a/b//c（第三、四个斜杠被当成下一组的"前两个"以外还多留了一个）。
        这是原实现就有的行为，本次只做"预编译正则"，刻意不改语义。
        */
        assertEquals("https://example.com/a/b//c", HttpRequestPlus.normalize("https://example.com/a//b///c"));
    }

    /**
     * 把上面那条 quirk 固定下来：对 3 个以上连续斜杠，规范化<b>不是</b>一次到位的。
     * <p>
     * 写这条不是为了"支持"它，而是防止后人以为它已经幂等、并据此写出依赖该假设的代码。
     */
    @Test
    void normalize_is_not_fully_idempotent_for_three_or_more_slashes() {
        String once = HttpRequestPlus.normalize("https://example.com/a//b///c");
        assertEquals("https://example.com/a/b//c", once);
        assertEquals("https://example.com/a/b/c", HttpRequestPlus.normalize(once),
                "再规范化一次会继续收敛");
    }

    @Test
    void keeps_the_scheme_separator() {
        // 关键：不能把 https:// 里的 // 也折叠掉，否则整个 URL 就废了
        assertEquals("https://example.com/a", HttpRequestPlus.normalize("https://example.com//a"));
        assertEquals("http://example.com/a", HttpRequestPlus.normalize("http://example.com//a"));
    }

    @Test
    void leaves_clean_urls_untouched() {
        assertEquals("https://example.com/a/b", HttpRequestPlus.normalize("https://example.com/a/b"));
        assertEquals("http://example.com", HttpRequestPlus.normalize("http://example.com"));
    }

    @Test
    void repeated_calls_are_stable_for_double_slashes() {
        // 两个连续斜杠是唯一会实际出现的形态（UrlBuilder 拼接/配置里多打一个斜杠），
        // 对它规范化是幂等的；3 个以上的 quirk 见 normalize_is_not_fully_idempotent_for_three_or_more_slashes
        String once = HttpRequestPlus.normalize("https://example.com//a//b");
        assertEquals("https://example.com/a/b", once);
        assertEquals(once, HttpRequestPlus.normalize(once));
    }
}
