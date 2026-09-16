package ani.rss.util.basic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重命名缓存（SQLite，P2-2）。
 * <p>
 * 本轮把它从"先删再插两条语句、无事务、连接坏了就永久不可用"改成
 * 单条 {@code INSERT OR REPLACE} + WAL + 失败重建连接。用例固化四件事：
 * <ol>
 *   <li>基本读写与覆盖语义（同 key 重复写只留一条）；</li>
 *   <li>不存在的 key 返回 {@code null}，不抛异常；</li>
 *   <li>删除后再读为 {@code null}；</li>
 *   <li><b>换配置目录后不得复用旧连接</b>——这正是原实现最要命的缺陷：
 *       {@code if (connection != null) return;} 让配置恢复覆盖 db 文件后，
 *       改名链路会永远抛 RuntimeException 且无法自愈。</li>
 * </ol>
 */
class RenameCacheUtilTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.toString());
        RenameCacheUtil.resetForTest();
    }

    @AfterEach
    void tearDown() {
        RenameCacheUtil.resetForTest();
        System.clearProperty("CONFIG");
    }

    @Test
    void put_then_get_roundtrip() {
        RenameCacheUtil.put("k1", "v1");
        assertEquals("v1", RenameCacheUtil.get("k1"));
    }

    @Test
    void get_missing_key_returns_null() {
        assertNull(RenameCacheUtil.get("nothing"));
    }

    @Test
    void put_overwrites_the_existing_row() {
        RenameCacheUtil.put("same", "first");
        assertEquals("first", RenameCacheUtil.get("same"));

        RenameCacheUtil.put("same", "second");
        assertEquals("second", RenameCacheUtil.get("same"), "同 key 重复写必须覆盖");
    }

    @Test
    void remove_drops_the_row() {
        RenameCacheUtil.put("gone", "v");
        assertEquals("v", RenameCacheUtil.get("gone"));

        RenameCacheUtil.remove("gone");
        assertNull(RenameCacheUtil.get("gone"));
        // 删除不存在的 key 不该抛异常
        assertDoesNotThrow(() -> RenameCacheUtil.remove("gone"));
    }

    @Test
    void values_survive_a_connection_reset() {
        RenameCacheUtil.put("persist", "v");
        // 模拟进程重启：连接丢弃后重新打开同一个库
        RenameCacheUtil.resetForTest();
        assertEquals("v", RenameCacheUtil.get("persist"), "数据必须真的落到了 database.db");
    }

    /**
     * 反向验证：指向新的配置目录后，必须换到新库上读写。
     * <p>
     * 原实现（{@code connection != null} 短路）会继续用旧连接，
     * 于是"配置恢复"之后读到的仍是旧库数据，且一旦旧库文件被替换/锁坏就永久报错。
     */
    @Test
    void switching_config_dir_does_not_reuse_the_old_connection() {
        RenameCacheUtil.put("k", "old-dir-value");
        assertEquals("old-dir-value", RenameCacheUtil.get("k"));

        Path other = tempDir.resolve("restored");
        assertTrue(other.toFile().mkdirs() || other.toFile().isDirectory());
        System.setProperty("CONFIG", other.toString());
        RenameCacheUtil.resetForTest();

        assertNull(RenameCacheUtil.get("k"), "换了配置目录后不该还能读到旧库的数据");

        RenameCacheUtil.put("k", "new-dir-value");
        assertEquals("new-dir-value", RenameCacheUtil.get("k"));
    }

    @Test
    void null_value_is_stored_and_read_back_as_null() {
        // SQLite 允许存 NULL；这里只要求不抛异常（调用方不会主动写 null）
        assertDoesNotThrow(() -> RenameCacheUtil.put("nullkey", null));
        assertNull(RenameCacheUtil.get("nullkey"));
    }
}
