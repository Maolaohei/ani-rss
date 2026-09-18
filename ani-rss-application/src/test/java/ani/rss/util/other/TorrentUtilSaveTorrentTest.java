package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 种子记录写入的「幂等 + 日志诚实」契约。
 * <p>
 * 背景：{@code TorrentUtil.saveTorrent} 原先把 info 级「下载种子 {}」打在下方的幂等检查
 * <i>之前</i>，而 {@code writeTorrentFile} 在记录已存在时直接 return。于是
 * <pre>
 * INFO TorrentUtil     下载种子 某番 S01E15
 * INFO DownloadService 本地已存在 某番 S01E15
 * </pre>
 * 这一对日志会被读成"又下载了一次"，实际零请求零写入 —— 它出自
 * {@code DownloadService.itemDownloaded} 的"文件在本地"分支，RSS <b>每轮 × 每集</b>都会打。
 * 拿「下载种子」计数会严重虚高。
 * <p>
 * 本用例钉住三件事：
 * <ol>
 *   <li>记录已存在 → 复用，<b>不打</b>「下载种子」；</li>
 *   <li>记录不存在 → 打「下载种子」并写入内容（不能为了消音把真日志也弄丢）；</li>
 *   <li>正式记录与待完成标记的解析都要能扛住"磁力链 ↔ .torrent 直链"的表示切换。</li>
 * </ol>
 * 顺带断言记录不被删除：若哪天有人把幂等短路拆掉，这条 URL 会真的被请求、失败后
 * {@code writeTorrentFile} 的 catch 会 {@code FileUtil.del} 掉记录 —— 那是数据丢失。
 */
class TorrentUtilSaveTorrentTest {

    /** 用真实磁力链：走"直接写字符串"分支，不需要网络。 */
    private static final String MAGNET = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567";

    /** 指向必然连不上的端口：一旦被请求就会失败。 */
    private static final String UNREACHABLE = "http://127.0.0.1:1/should-not-be-fetched.torrent";

    @TempDir
    Path tempDir;

    private Logger torrentUtilLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.toString());
        torrentUtilLogger = (Logger) LoggerFactory.getLogger(TorrentUtil.class);
        appender = new ListAppender<>();
        appender.start();
        torrentUtilLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        torrentUtilLogger.detachAppender(appender);
        appender.stop();
        System.clearProperty("CONFIG");
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void clearLog() {
        appender.list.clear();
    }

    private boolean loggedDownload() {
        return messages().stream().anyMatch(m -> m.startsWith("下载种子"));
    }

    private static Ani ani() {
        return new Ani().setId("ani-1").setTitle("测试番剧").setSeason(1).setOva(false);
    }

    private static Item item(String torrent) {
        return new Item().setReName("测试番剧 S01E01")
                .setInfoHash("0123456789ABCDEF0123456789ABCDEF01234567")
                .setTorrent(torrent);
    }

    // ---------------- 记录已存在：复用，不吵 ----------------

    @Test
    @DisplayName("记录已存在：复用记录，不打「下载种子」，也不改写/删除记录")
    void existing_record_is_reused_without_download_log() {
        Ani ani = ani();
        Item item = item(MAGNET);

        File record = TorrentUtil.saveTorrent(ani, item);
        assertTrue(record.exists(), "前置条件：首次应写出记录");
        assertEquals(MAGNET, FileUtil.readUtf8String(record), "前置条件：内容应是磁力链");

        // 换成不可达地址：若代码真的去下载，就会失败并删掉记录
        item.setTorrent(UNREACHABLE);
        clearLog();

        File again = TorrentUtil.saveTorrent(ani, item);

        assertEquals(record.getAbsolutePath(), again.getAbsolutePath(), "应复用同一份记录");
        assertTrue(again.exists(), "记录不得被删除");
        assertEquals(MAGNET, FileUtil.readUtf8String(again), "记录内容不得被改写");
        assertFalse(loggedDownload(), "记录已存在时不应打「下载种子」，实际日志: " + messages());
    }

    @Test
    @DisplayName("待完成标记已存在：同样不打「下载种子(待完成标记)」")
    void existing_pending_is_reused_without_download_log() {
        Ani ani = ani();
        Item item = item(MAGNET);

        File pending = TorrentUtil.saveTorrentPending(ani, item);
        assertTrue(pending.exists(), "前置条件：首次应写出待完成标记");

        clearLog();

        // 保持同一表示再调一次：这正是 RSS 每轮都会走的路径
        File again = TorrentUtil.saveTorrentPending(ani, item);

        assertEquals(pending.getAbsolutePath(), again.getAbsolutePath(), "应复用同一份标记");
        assertEquals(MAGNET, FileUtil.readUtf8String(again), "标记内容不得被改写");
        assertFalse(loggedDownload(), "标记已存在时不应打日志，实际日志: " + messages());
    }

    /**
     * 订阅源在磁力链与 .torrent 直链之间切换是常事。若标记的文件名按"当前表示"重算，
     * 切换后就会"找不到"已经写下的标记 —— 表现是重复提交离线任务，且
     * {@code promoteTorrent} 因找不到标记而跳过提升，记录永远不落盘、下轮再提交。
     * <p>
     * {@code getTorrent} 早已做了这个容错（上游 3.2.30 的 port），{@code getPendingTorrent} 漏了。
     */
    @Test
    @DisplayName("磁力链 ↔ .torrent 直链切换：待完成标记仍能被找到，不重复提交")
    void pending_marker_survives_torrent_representation_switch() {
        Ani ani = ani();
        Item item = item(MAGNET);

        File pending = TorrentUtil.saveTorrentPending(ani, item);
        assertTrue(pending.exists(), "前置条件：首次应写出待完成标记");
        assertTrue(pending.getName().endsWith(".txt"), "前置条件：磁力链表示应落到 .txt");

        // 同一 infoHash，换成 .torrent 直链表示
        item.setTorrent(UNREACHABLE);
        clearLog();

        File resolved = TorrentUtil.getPendingTorrent(ani, item);
        assertEquals(pending.getAbsolutePath(), resolved.getAbsolutePath(),
                "切换表示后仍应解析到同一份标记（否则等于标记丢失）");

        File again = TorrentUtil.saveTorrentPending(ani, item);
        assertTrue(again.exists(), "标记不得因表示切换而消失");
        assertEquals(MAGNET, FileUtil.readUtf8String(again), "标记内容不得被改写");
        assertFalse(loggedDownload(),
                "标记已存在（只是表示变了）时不应重新提交，实际日志: " + messages());
    }

    // ---------------- 记录不存在：该打的日志不能丢 ----------------

    @Test
    @DisplayName("记录不存在：打「下载种子」并写入内容")
    void missing_record_is_logged_and_written() {
        File record = TorrentUtil.saveTorrent(ani(), item(MAGNET));

        assertTrue(record.exists(), "应写出记录");
        assertEquals(MAGNET, FileUtil.readUtf8String(record), "内容应是磁力链");
        assertTrue(loggedDownload(), "首次写入应有「下载种子」日志，实际日志: " + messages());
    }

    @Test
    @DisplayName("待完成标记不存在：打「下载种子(待完成标记)」")
    void missing_pending_is_logged_and_written() {
        File pending = TorrentUtil.saveTorrentPending(ani(), item(MAGNET));

        assertTrue(pending.exists(), "应写出待完成标记");
        assertTrue(messages().stream().anyMatch(m -> m.startsWith("下载种子(待完成标记)")),
                "首次提交应有待完成标记日志，实际日志: " + messages());
    }

    // ---------------- 两条路径互不串味 ----------------

    @Test
    @DisplayName("正式记录与待完成标记是两份文件，互不遮蔽")
    void record_and_pending_are_distinct_files() {
        Ani ani = ani();
        Item item = item(MAGNET);

        File record = TorrentUtil.saveTorrent(ani, item);
        File pending = TorrentUtil.saveTorrentPending(ani, item);

        assertTrue(record.exists() && pending.exists());
        assertFalse(record.getAbsolutePath().equals(pending.getAbsolutePath()),
                "正式记录与待完成标记不得指向同一文件，否则待完成语义会被绕过");
    }
}
