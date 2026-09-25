package ani.rss.util.basic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * URL 规范化（{@code HttpRequestPlus.normalize}）：折叠路径里的重复斜杠。
 * <p>
 * 这里只固化<b>当前实现应有的行为</b>。刻意不写"与重构前的内联正则逐条对齐"那类用例：
 * 被替换掉的旧实现不是规格，拿它当期望值只能证明"没改动"，改一次实现就红，
 * 却抓不到任何真正的缺陷；"钉住 quirk 防止后人误以为幂等"同理——注释说清楚就够，
 * 不需要一条永远只会因为重构而失败的用例。见 {@code docs/TESTING.md}。
 */
class HttpRequestPlusNormalizeTest {

    @Test
    void collapses_duplicate_slashes_in_the_path() {
        assertEquals("https://example.com/a/b", HttpRequestPlus.normalize("https://example.com//a//b"));
        /*
        注意：连续 3 个以上斜杠不会被一次折叠干净 —— replaceAll 是从左到右的非重叠匹配，
        a//b///c 会得到 a/b//c。这是原实现就有的行为，重构只做了"预编译正则"，刻意不改语义。
        */
        assertEquals("https://example.com/a/b//c", HttpRequestPlus.normalize("https://example.com/a//b///c"));
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
        // 两个连续斜杠是唯一会实际出现的形态（UrlBuilder 拼接 / 配置里多打一个斜杠），
        // 对它规范化是幂等的
        String once = HttpRequestPlus.normalize("https://example.com//a//b");
        assertEquals("https://example.com/a/b", once);
        assertEquals(once, HttpRequestPlus.normalize(once));
    }
}
