package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pending 标记（离线在途账本）的生命周期。
 * <p>
 * v3 把 pending 从"提交与完成之间的临时文件"升级为<b>在途账本</b>：
 * 存疑时一律保留（含冷却、跨重启），删它只允许三种情况——闭环成功、用户取消、用户显式重试。
 * 但"永久保留"会撞上 {@code DownloadService} 的闸门（pending 存在 → 本轮跳过），
 * 于是该集永远不再进入判定、也不再重提交。所以在途有效期是必须补上的一环：
 * <pre>
 *   pending 年龄 ≤ 有效期 → 视为在途，跳过重提交（但允许重做归位 + 确认）
 *   pending 年龄 &gt; 有效期 → 放行复核；复核不过才允许重新提交（过期 ≠ 重下）
 * </pre>
 * 取值跟随 {@code alistDownloadTimeout × 2}（默认 30min → 60min，用户已确认的选项 A）：
 * 不新增配置概念、自动联动，且与既有的 62 分钟"跨时间双次确认"节奏接近，两个时间尺度不互相打架。
 */
class TorrentUtilPendingLifecycleTest {

    private Path configDir;
    private String prevConfig;

    @BeforeEach
    void setUp() throws IOException {
        configDir = Files.createTempDirectory("ani-rss-pending-");
        prevConfig = System.getProperty("CONFIG");
        System.setProperty("CONFIG", configDir.toString());
    }

    @AfterEach
    void tearDown() {
        if (prevConfig == null) {
            System.clearProperty("CONFIG");
        } else {
            System.setProperty("CONFIG", prevConfig);
        }
        FileUtil.del(configDir.toFile());
    }

    private static Ani ani() {
        return new Ani().setId("ani-pending").setTitle("在途测试").setSeason(1).setOva(false);
    }

    private static Item item() {
        return new Item().setReName("在途测试 S01E01").setEpisode(1.0)
                .setInfoHash("pendinghash")
                .setTorrent("magnet:?xt=urn:btih:pendinghash").setMaster(true);
    }

    /** 写下一个真实的 pending 标记，返回它。 */
    private static File writePending(Ani ani, Item item) {
        File pending = TorrentUtil.getPendingTorrent(ani, item);
        FileUtil.mkParentDirs(pending);
        FileUtil.writeUtf8String("magnet:?xt=urn:btih:pendinghash", pending);
        return pending;
    }

    // ---------------- 在途有效期取值 ----------------

    @Test
    @DisplayName("在途有效期 = alistDownloadTimeout × 2（选项 A）")
    void pending_ttl_follows_alist_timeout_times_two() {
        assertEquals(60L, TorrentUtil.pendingTtlMs(new Config().setAlistDownloadTimeout(30)) / 60_000L,
                "默认 30 分钟 × 2 = 60 分钟");
        assertEquals(4L, TorrentUtil.pendingTtlMs(new Config().setAlistDownloadTimeout(2)) / 60_000L);
        assertEquals(240L, TorrentUtil.pendingTtlMs(new Config().setAlistDownloadTimeout(120)) / 60_000L);
    }

    @Test
    @DisplayName("配置或超时缺失时兜底，不 NPE")
    void pending_ttl_falls_back_when_config_or_timeout_missing() {
        assertEquals(60L, TorrentUtil.pendingTtlMs(null) / 60_000L,
                "config 为空（启动早期）也要有兜底");
        assertEquals(60L, TorrentUtil.pendingTtlMs(new Config()) / 60_000L,
                "alistDownloadTimeout 是可空字段（旧配置可能缺）");
        assertEquals(60L, TorrentUtil.pendingTtlMs(new Config().setAlistDownloadTimeout(null)) / 60_000L);
    }

    @Test
    @DisplayName("非法超时绝不产生 0/负数有效期：那会让每一轮都判「过期」并放行重提交")
    void illegal_timeout_never_yields_zero_or_negative_ttl() {
        assertTrue(TorrentUtil.pendingTtlMs(new Config().setAlistDownloadTimeout(0)) > 0L);
        assertTrue(TorrentUtil.pendingTtlMs(new Config().setAlistDownloadTimeout(-5)) > 0L);
        assertEquals(2L, TorrentUtil.pendingTtlMs(new Config().setAlistDownloadTimeout(0)) / 60_000L,
                "非法值回落为下限 1 分钟，×2 = 2 分钟");
    }

    // ---------------- isPendingExpired ----------------

    @Test
    @DisplayName("刚写下的标记属于在途，不是过期")
    void fresh_pending_is_still_in_flight() {
        Ani ani = ani();
        Item item = item();
        writePending(ani, item);
        assertFalse(TorrentUtil.isPendingExpired(ani, item));
    }

    @Test
    @DisplayName("超过有效期 → 放行复核（过期 ≠ 重下）")
    void old_pending_is_expired() {
        Ani ani = ani();
        Item item = item();
        File pending = writePending(ani, item);
        long ttl = TorrentUtil.pendingTtlMs(ConfigUtil.CONFIG);
        assertTrue(pending.setLastModified(System.currentTimeMillis() - ttl - 60_000L),
                "前置条件：应能回拨修改时间");

        assertTrue(TorrentUtil.isPendingExpired(ani, item),
                "超过 alistDownloadTimeout × 2 后应放行复核，而不是永久卡住");
    }

    @Test
    @DisplayName("没有标记谈不上过期：否则「从未提交过的集」会被当成过期条目重新提交")
    void missing_pending_is_never_expired() {
        assertFalse(TorrentUtil.isPendingExpired(ani(), item()));
        assertFalse(TorrentUtil.isPendingExpired(null, item()));
        assertFalse(TorrentUtil.isPendingExpired(ani(), null));
    }

    // ---------------- cleanupOrphanPending：孤儿保留、闭环残留清理 ----------------

    @Test
    @DisplayName("孤儿 pending 必须保留为在途账本")
    void cleanup_keeps_orphan_pending_as_in_flight_ledger() {
        Ani ani = ani();
        Item item = item();
        File pending = writePending(ani, item);

        TorrentUtil.cleanupOrphanPending();

        assertTrue(pending.exists(),
                "删掉孤儿 pending 会让下一轮当成「没下过」重新提交，在网盘堆出第二份产物");
    }

    @Test
    @DisplayName("正式记录已存在 → pending 闭环完成，可以清掉；正式记录本身绝不能被删")
    void cleanup_removes_pending_only_when_formal_record_exists() {
        Ani ani = ani();
        Item item = item();
        File pending = writePending(ani, item);

        Path pendingRoot = new File(ConfigUtil.getConfigDir(), "torrents/.pending").toPath();
        File formal = new File(new File(ConfigUtil.getConfigDir(), "torrents"),
                pendingRoot.relativize(pending.toPath()).toString());
        FileUtil.mkParentDirs(formal);
        FileUtil.writeUtf8String("magnet:?xt=urn:btih:pendinghash", formal);

        TorrentUtil.cleanupOrphanPending();

        assertFalse(pending.exists(), "正式记录已存在 → 这条 pending 已完成闭环，可以清掉");
        assertTrue(formal.exists(), "正式种子记录只增不删（D1）");
    }
}
