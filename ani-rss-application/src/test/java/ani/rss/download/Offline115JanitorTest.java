package ani.rss.download;

import ani.rss.entity.Config;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 115 离线任务看门狗的纯逻辑测试。
 * <p>
 * 只测"不碰网络"的部分：卡死判定、配额百分比、两个解析器。
 * 网络与真删除属于现场行为，见 {@code docs/TESTING.md} 的分层约定。
 */
class Offline115JanitorTest {

    private static final long NOW = 1793_500_000_000L;

    private static Offline115Janitor.Task task(int status, int percentDone, long addTimeHoursAgo) {
        return new Offline115Janitor.Task("abc123", "demo", status, percentDone,
                NOW / 1000L - addTimeHoursAgo * 3600L);
    }

    /**
     * 卡在 99% 且超过阈值：这才是 10008 死锁的来源，必须判卡死
     */
    @Test
    void stuckWhen99PercentAndOlderThanThreshold() {
        assertTrue(Offline115Janitor.isStuck(task(1, 99, 18), NOW, 180));
        assertTrue(Offline115Janitor.isStuck(task(1, 100, 5), NOW, 180));
    }

    /**
     * 没到阈值就不能删：那可能只是刚开始跑，删了等于丢掉正在下的成果
     */
    @Test
    void notStuckBeforeThreshold() {
        assertFalse(Offline115Janitor.isStuck(task(1, 99, 1), NOW, 180));
        assertFalse(Offline115Janitor.isStuck(task(1, 99, 0), NOW, 180));
    }

    /**
     * 进度低不算卡死：115 的低进度任务通常几分钟内就会推进
     */
    @Test
    void lowProgressIsNotStuckEvenIfOld() {
        assertFalse(Offline115Janitor.isStuck(task(1, 40, 48), NOW, 180));
        assertFalse(Offline115Janitor.isStuck(task(1, 98, 48), NOW, 180));
    }

    /**
     * 已完成的任务不是卡死；status 2 即使写了 99% 也不能动
     */
    @Test
    void finishedTaskIsNeverStuck() {
        assertFalse(Offline115Janitor.isStuck(task(2, 99, 200), NOW, 180));
    }

    @Test
    void blankHashIsNeverStuck() {
        Offline115Janitor.Task blank = new Offline115Janitor.Task("  ", "demo", 1, 99, 0L);
        assertFalse(Offline115Janitor.isStuck(blank, NOW, 180));
        assertFalse(Offline115Janitor.isStuck(null, NOW, 180));
    }

    @Test
    void quotaPercentIsClampedAndSafe() {
        assertEquals(83, Offline115Janitor.quotaPercent(1250, 1500));
        assertEquals(0, Offline115Janitor.quotaPercent(10, 0));
        assertEquals(100, Offline115Janitor.quotaPercent(2000, 1500));
        assertEquals(0, Offline115Janitor.quotaPercent(-5, 1500));
    }

    /**
     * 真实响应形态：tasks[] 里的 info_hash 要小写归一（115 返回大写时删除会静默失败）
     */
    @Test
    void parseTasksNormalizesHash() {
        String body = """
                {"count":2,"quota":1250,"total":1500,"tasks":[
                  {"info_hash":"BF88BAB629A94C7D6487E00034CBB81DFA4F4E4A","name":"[ANi] A - 17","status":1,"percentDone":99,"add_time":1793383708},
                  {"info_hash":"f5c92d0892ad3c6550c6bd5e7684f4fda16b419a","name":"[ANi] B - 18","status":2,"percentDone":100,"add_time":1793300000}
                ]}
                """;
        List<Offline115Janitor.Task> tasks = Offline115Janitor.parseTasks(body);
        assertEquals(2, tasks.size());
        assertEquals("bf88bab629a94c7d6487e00034cbb81dfa4f4e4a", tasks.get(0).infoHash());
        assertEquals(99, tasks.get(0).percentDone());
        assertTrue(tasks.get(1).finished());
        int[] quota = Offline115Janitor.parseQuota(body);
        assertEquals(1250, quota[0]);
        assertEquals(1500, quota[1]);
    }

    @Test
    void parseTasksToleratesGarbage() {
        assertTrue(Offline115Janitor.parseTasks("").isEmpty());
        assertTrue(Offline115Janitor.parseTasks("not json").isEmpty());
        assertTrue(Offline115Janitor.parseTasks("{\"code\":200,\"data\":[]}").isEmpty());
    }

    /**
     * AList 的 addition 是<b>字符串</b>而不是对象——按对象取会拿到 null，
     * 这是本功能最容易踩的坑，必须钉住
     */
    @Test
    void parseAlistCookieFromStringAddition() {
        String body = """
                {"code":200,"data":{"content":[
                  {"mount_path":"/117","driver":"115 Open","addition":"{\\"refresh_token\\":\\"r\\"}"},
                  {"mount_path":"/115","driver":"115 Cloud","addition":"{\\"cookie\\":\\"UID=1_A1; CID=2; SEID=3\\",\\"root_folder_id\\":\\"\\"}"}
                ]}}
                """;
        assertEquals("UID=1_A1; CID=2; SEID=3",
                Offline115Janitor.parseAlistCookie(body, "/115"));
    }

    @Test
    void parseAlistCookieFromObjectAddition() {
        String body = """
                {"code":200,"data":{"content":[
                  {"mount_path":"/115","driver":"115 Cloud","addition":{"cookie":"UID=9_A9; SEID=9"}}
                ]}}
                """;
        assertEquals("UID=9_A9; SEID=9", Offline115Janitor.parseAlistCookie(body, "/115"));
    }

    /**
     * 挂载对不上 / driver 不是 115 时必须返回 null，不能拿别的存储的 cookie 去删 115 任务
     */
    @Test
    void parseAlistCookieReturnsNullOnMismatch() {
        String body = """
                {"code":200,"data":{"content":[
                  {"mount_path":"/other","driver":"115 Cloud","addition":"{\\"cookie\\":\\"x\\"}"},
                  {"mount_path":"/115","driver":"Local","addition":"{}"}
                ]}}
                """;
        assertNull(Offline115Janitor.parseAlistCookie(body, "/115"));
        assertNull(Offline115Janitor.parseAlistCookie("{}", "/115"));
        assertNull(Offline115Janitor.parseAlistCookie("", "/115"));
    }

    /**
     * 删除接口的返回值：只有 state=true 才算受理（此时仍需重新列举确认）
     */
    @Test
    void parseDeleteResult() {
        assertTrue(Offline115Janitor.parseDeleteResult(
                "{\"state\":true,\"errtype\":\"suc\",\"errcode\":0}"));
        assertFalse(Offline115Janitor.parseDeleteResult("{\"state\":false}"));
        assertFalse(Offline115Janitor.parseDeleteResult("<html>login</html>"));
    }

    @Test
    void normalizeHash() {
        assertEquals("abc", Offline115Janitor.normalizeHash("  ABC "));
        assertEquals("", Offline115Janitor.normalizeHash(null));
    }

    /**
     * 只在下载工具是 OpenList 时生效：换成 qBittorrent 等下载器后，开关打开也不再动作
     */
    @Test
    void enabledRequiresOpenList() {
        Config openList = new Config();
        openList.setDownloadToolType("OpenList");
        openList.setOffline115Janitor(true);
        assertTrue(Offline115Janitor.openListTool(openList));
        assertTrue(Offline115Janitor.enabled(openList));

        Config qb = new Config();
        qb.setDownloadToolType("qBittorrent");
        qb.setOffline115Janitor(true);
        assertFalse(Offline115Janitor.openListTool(qb));
        assertFalse(Offline115Janitor.enabled(qb), "非 OpenList 下载器下看门狗必须视为关闭");

        Config off = new Config();
        off.setDownloadToolType("OpenList");
        off.setOffline115Janitor(false);
        assertFalse(Offline115Janitor.enabled(off));

        assertFalse(Offline115Janitor.enabled(null));
        assertFalse(Offline115Janitor.openListTool(null));
    }
}
