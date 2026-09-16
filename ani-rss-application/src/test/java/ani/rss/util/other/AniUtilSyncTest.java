package ani.rss.util.other;

import ani.rss.entity.Ani;
import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订阅落盘的写放大治理（P2-1）。
 * <p>
 * {@code doSync()} 每次都会把<b>整个订阅列表</b>序列化 + 写临时文件 + 原子 move。
 * 200 订阅一轮里，每个状态变化的订阅各触发一次就是 200 次全量写（约 200KB × 200）。
 * 本轮加了两道闸门：
 * <ol>
 *   <li><b>内容未变则跳过</b>：序列化无法避免（要算出 JSON 才知道有没有变），
 *       但"写临时文件 + 原子 move"可以省；</li>
 *   <li><b>运行时状态回写节流 500ms</b>：窗口内的多次 {@code syncStateOnly()} 合并成
 *       一次<b>尾部落盘</b>——数据只延后、不丢失。结构性变更走 {@code sync()}，不节流。</li>
 * </ol>
 */
class AniUtilSyncTest {

    @TempDir
    Path tempDir;

    private List<Ani> backup;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("CONFIG", tempDir.toString());

        backup = new ArrayList<>(AniUtil.getAniList());
        AniUtil.getAniList().clear();
        AniUtil.invalidateIdIndex();

        // 节流状态是 JVM 级静态量：先复位，再等一个窗口让上一个用例排队的尾部落盘跑完
        // （此时列表已清空，它落到本用例的临时目录里也只是个空数组，不影响断言）
        AniUtil.resetSyncThrottleForTest();
        Thread.sleep(700);
        AniUtil.resetSyncThrottleForTest();
    }

    @AfterEach
    void tearDown() {
        List<Ani> live = AniUtil.getAniList();
        live.clear();
        live.addAll(backup);
        AniUtil.invalidateIdIndex();
        System.clearProperty("CONFIG");
    }

    private static Ani ani(String id, String title) {
        return new Ani().setId(id).setTitle(title).setSeason(1);
    }

    private static String readConfig() {
        File file = AniUtil.getAniFile();
        return file.exists() ? FileUtil.readUtf8String(file) : "";
    }

    // ---------------- 内容未变则跳过 ----------------

    @Test
    void first_sync_writes_the_file() {
        AniUtil.getAniList().add(ani("w1", "标题A"));

        assertFalse(readConfig().contains("标题A"));
        AniUtil.sync();
        assertTrue(readConfig().contains("标题A"));
    }

    @Test
    void unchanged_content_is_not_written_again() {
        AniUtil.getAniList().add(ani("w2", "标题A"));
        AniUtil.sync();

        File file = AniUtil.getAniFile();
        String content = FileUtil.readUtf8String(file);
        long modified = file.lastModified();

        // 内容未变：应跳过"写临时文件 + 原子 move"，因此文件不该被再次触碰
        AniUtil.sync();

        assertEquals(content, FileUtil.readUtf8String(file));
        assertEquals(modified, file.lastModified(), "内容未变时不应再次落盘（P2-1）");
    }

    @Test
    void changed_content_is_written() {
        Ani ani = ani("w3", "标题A");
        AniUtil.getAniList().add(ani);
        AniUtil.sync();
        assertFalse(readConfig().contains("标题B"));

        ani.setTitle("标题B");
        AniUtil.sync();
        assertTrue(readConfig().contains("标题B"), "内容变了就必须落盘");
    }

    /**
     * 文件被外部删除后不能因为"内容没变"而跳过——否则配置恢复/手工清理之后
     * 订阅文件会一直不存在，重启就变成空列表。
     */
    @Test
    void missing_file_is_recreated_even_when_content_is_unchanged() {
        AniUtil.getAniList().add(ani("w4", "标题A"));
        AniUtil.sync();

        File file = AniUtil.getAniFile();
        assertTrue(file.exists());
        assertTrue(FileUtil.del(file));
        assertFalse(file.exists());

        AniUtil.sync();

        assertTrue(file.exists(), "文件不存在时必须重新写出来");
        assertTrue(FileUtil.readUtf8String(file).contains("标题A"));
    }

    // ---------------- 运行时状态回写节流 ----------------

    /**
     * 节流窗口内的回写必须由<b>尾部落盘</b>补上（只延后、不丢）。
     * <p>
     * 这里用轮询等待而不是固定 sleep：尾部落盘在独立线程上、500ms 后才跑，
     * 断言"最终一定写出来"才是真正要守住的不变量。
     */
    @Test
    void throttled_state_write_is_flushed_shortly_after() throws Exception {
        Ani ani = ani("t1", "节流A");
        AniUtil.getAniList().add(ani);
        // 结构性变更不节流：这一次一定立即落盘
        AniUtil.sync();
        assertTrue(readConfig().contains("节流A"));

        ani.setOmitCount(7).setTitle("节流B");
        // 距上一次落盘不足 500ms，这两次都只排队
        AniUtil.syncStateOnly();
        AniUtil.syncStateOnly();

        File file = AniUtil.getAniFile();
        String content = "";
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            content = FileUtil.readUtf8String(file);
            if (content.contains("节流B")) {
                break;
            }
            Thread.sleep(50);
        }

        assertTrue(content.contains("节流B"), "节流窗口内的回写必须由尾部落盘补上，实际内容: " + content);
        assertTrue(content.contains("\"omitCount\":7"), "尾部落盘写的是当时的最新状态: " + content);
    }

    /**
     * 首次回写不做节流（还没有任何一次落盘），否则"只改状态就再也不写盘"。
     */
    @Test
    void first_state_write_is_not_throttled() {
        Ani ani = ani("t2", "首次A");
        AniUtil.getAniList().add(ani);

        ani.setOmitCount(3);
        AniUtil.syncStateOnly();

        assertTrue(readConfig().contains("\"omitCount\":3"), "首次回写必须立即落盘");
    }
}
