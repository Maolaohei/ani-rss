package ani.rss.service;

import ani.rss.commons.FileUtils;
import ani.rss.download.BaseDownload;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentsInfo;
import ani.rss.enums.TorrentsTags;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 备用RSS洗版清扫（{@code deleteStandbyRss}）在「先提交主RSS、成功后才清理备用」时序下的安全边界。
 * <p>
 * 事故原型：主/备用同一模板重命名后文件名可能完全一致（模板里没有 ${subgroup} 时就是一致），
 * 于是"提交成功后按 SxxExx 清扫"既可能删掉刚提交的主RSS任务，也可能删掉刚落地的主版本文件。
 * 本测试覆盖三道闸门：
 * <ul>
 *   <li>{@code excludeHash}：主RSS自己的任务永不被删（含 hash 大小写不一致）；</li>
 *   <li>「备用RSS」标签：无标签任务不再被顺手删掉；</li>
 *   <li>提交前文件快照：只清提交前就存在的文件，无快照则一个都不碰；</li>
 *   <li>主条目闸门：备用条目下载时不得反向清理。</li>
 * </ul>
 */
class StandbySweepTest {

    private final DownloadService downloadService = new DownloadService();

    @TempDir
    File tempDir;

    /**
     * 删除行为可配置的下载器桩。
     * <p>
     * {@code deleteStandbyRss} 内部会自己查询任务列表（不接受调用方传入），
     * 所以这里必须可注入 {@code torrents}，否则用例永远看不到待删任务。
     */
    private static class StubDownloader implements BaseDownload {
        final AtomicReference<String> deletedName = new AtomicReference<>();
        final AtomicReference<Boolean> deletedWithFiles = new AtomicReference<>();
        final AtomicReference<TorrentsInfo> lastDeleted = new AtomicReference<>();
        volatile List<TorrentsInfo> torrents = List.of();

        @Override
        public Boolean login(Boolean test, Config config) {
            return true;
        }

        @Override
        public List<TorrentsInfo> getTorrentsInfos() {
            return new ArrayList<>(torrents);
        }

        @Override
        public Boolean download(Ani ani, Item item, String savePath, File torrentFile) {
            return true;
        }

        @Override
        public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
            deletedName.set(torrentsInfo.getName());
            deletedWithFiles.set(deleteFiles);
            lastDeleted.set(torrentsInfo);
            return true;
        }

        @Override
        public Boolean rename(TorrentsInfo torrentsInfo) {
            return true;
        }

        @Override
        public Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
            return true;
        }

        @Override
        public void updateTrackers(Set<String> trackers) {
        }

        @Override
        public void setSavePath(TorrentsInfo torrentsInfo, String path) {
        }
    }

    private StubDownloader stub;

    @BeforeEach
    void setUp() {
        stub = new StubDownloader();
        TorrentUtil.DOWNLOAD = stub;
        ConfigUtil.CONFIG.setRenameTemplate(null)
                .setOvaRenameTemplate(null)
                .setDownloadPathTemplate(tempDir.getAbsolutePath())
                .setOvaDownloadPathTemplate(tempDir.getAbsolutePath())
                .setRename(true).setFileExist(true)
                .setRenameDelYear(false).setRenameDelTmdbId(false)
                .setDelete(true).setStandbyRss(true).setCoexist(false)
                .setAwaitStalledUP(false);
    }

    @AfterEach
    void tearDown() {
        ConfigUtil.CONFIG.setDelete(null).setStandbyRss(null).setCoexist(null).setAwaitStalledUP(null)
                .setRename(null).setFileExist(null)
                .setDownloadPathTemplate(null).setOvaDownloadPathTemplate(null);
        TorrentUtil.DOWNLOAD = null;
    }

    private Ani ani() {
        Ani ani = new Ani();
        ani.setTitle("测试番剧").setOva(false).setMediaType(null)
                .setSeason(1).setOffset(0).setBgmUrl("https://bgm.tv/subject/123")
                .setThemoviedbName("").setNamingVersion(2)
                .setCustomRenameTemplateEnable(false).setCustomEpisode(false)
                .setReleaseDate(new Date(116, 9, 21))
                .setCustomDownloadPath(true)
                .setDownloadPath(tempDir.getAbsolutePath())
                .setSubgroup("测试字幕组");
        return ani;
    }

    private Item item(boolean master) {
        Item item = new Item();
        item.setTitle("测试番剧 S01E05").setEpisode(5.0).setReName("测试番剧 S01E05")
                .setTorrent("magnet:?xt=urn:btih:bbbbbbbbbbbbbbbb")
                .setInfoHash("bbbbbbbbbbbbbbbb")
                .setMaster(master).setSubgroup("测试字幕组");
        return item;
    }

    private TorrentsInfo torrent(String name, String hash, List<String> tags) {
        TorrentsInfo torrentsInfo = new TorrentsInfo();
        torrentsInfo.setName(name)
                .setHash(hash)
                .setState(TorrentsInfo.State.stalledUP)
                .setTags(new ArrayList<>(tags))
                // 与 getDownloadPath 一致: 归一化分隔符(Windows \ → /)
                .setDownloadDir(FileUtils.getAbsolutePath(tempDir));
        return torrentsInfo;
    }

    private List<String> standbyTags() {
        return List.of(
                TorrentsTags.ANI_RSS.getValue(),
                TorrentsTags.BACK_RSS.getValue(),
                TorrentsTags.RENAME.getValue(),
                "未知字幕组");
    }

    /** 主RSS自己的任务：带自己的字幕组标签、不带「备用RSS」标签 */
    private List<String> masterTags() {
        return List.of(
                TorrentsTags.ANI_RSS.getValue(),
                TorrentsTags.RENAME.getValue(),
                "测试字幕组");
    }

    private static final String MASTER_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    /**
     * 注入"下载器当前任务列表"。
     * <p>
     * 必须同时刷新 {@code TorrentUtil} 的 5 秒任务缓存：否则同一 JVM 内上一个用例留下的
     * 缓存会被本用例读到（"不删"类断言会因此以错误的理由通过）。
     */
    private void givenTorrents(TorrentsInfo... torrents) {
        stub.torrents = List.of(torrents);
        TorrentUtil.refreshTorrentsCache();
    }

    @BeforeEach
    void resetTorrentsCache() {
        // 两个 @BeforeEach 的执行顺序不保证，这里自己兜住 DOWNLOAD 未赋值的情况
        TorrentUtil.DOWNLOAD = stub;
        stub.torrents = List.of();
        TorrentUtil.refreshTorrentsCache();
    }

    // ------------------------------------------------------------ hash 判定

    @Test
    void isSameHash_ignores_case_and_null() {
        assertTrue(DownloadService.isSameHash(torrent("x", "AAAABBBB", masterTags()), "aaaabbbb"),
                "下载器回的 hash 大小写不保证，必须忽略大小写比对");
        assertFalse(DownloadService.isSameHash(torrent("x", "AAAABBBB", masterTags()), "cccc"),
                "不同 hash 不得视为同一任务");
        assertFalse(DownloadService.isSameHash(torrent("x", null, masterTags()), "aaaabbbb"),
                "hash 为 null 不得抛异常，也不得视为同一任务");
        assertFalse(DownloadService.isSameHash(null, "aaaabbbb"));
    }

    // ------------------------------------------------------ excludeHash 闸门

    @Test
    void sweep_never_deletes_just_submitted_master_task() {
        // 列表里主RSS刚提交的任务排在最前（findFirst 会先命中它）：必须靠 excludeHash 跳过，
        // 转而删掉同集的备用任务。
        TorrentsInfo masterTask = torrent("测试番剧 S01E05", MASTER_HASH, masterTags());
        TorrentsInfo standbyTask = torrent("测试番剧 S01E05", "bbbbbbbbbbbbbbbb", standbyTags());
        givenTorrents(masterTask, standbyTask);

        downloadService.deleteStandbyRss(ani(), item(true), MASTER_HASH, new HashSet<>());

        assertNotNull(stub.deletedName.get(), "应删除备用任务");
        assertSame(standbyTask, stub.lastDeleted.get(), "被删的必须是备用任务，绝不能是刚提交的主RSS任务");
        assertEquals(Boolean.TRUE, stub.deletedWithFiles.get());
    }

    @Test
    void sweep_matches_master_hash_case_insensitively() {
        // 主RSS任务回大写 hash：仍必须被识别为"自己"，不能删
        TorrentsInfo masterTask = torrent("测试番剧 S01E05", "AAAABBBBCCCCDDDDEEEE", masterTags());
        givenTorrents(masterTask);

        downloadService.deleteStandbyRss(ani(), item(true), "aaaabbbbccccddddeeee", new HashSet<>());

        assertNull(stub.deletedName.get(), "hash 大小写不同也必须认出主RSS任务并跳过");
    }

    @Test
    void sweep_ignores_untagged_task() {
        // 无「备用RSS」标签的历史任务：不再被顺手删掉（它很可能就是主RSS自己的任务）
        TorrentsInfo untagged = torrent("测试番剧 S01E05", "ffffffffffffffff", masterTags());
        givenTorrents(untagged);

        downloadService.deleteStandbyRss(ani(), item(true), null, new HashSet<>());

        assertNull(stub.deletedName.get(), "无备用标签的任务不应被删除");
    }

    // ---------------------------------------------------------- 文件快照闸门

    @Test
    void sweep_deletes_only_files_seen_before_submit() {
        File before = FileUtil.writeUtf8String("old", new File(tempDir, "测试番剧 S01E05.mkv"));
        File after = FileUtil.writeUtf8String("new", new File(tempDir, "测试番剧 S01E05 v2.mkv"));
        Set<String> snapshot = new HashSet<>();
        snapshot.add(before.getName());

        downloadService.deleteStandbyRss(ani(), item(true), null, snapshot);

        assertFalse(before.exists(), "提交前就存在的备用文件应被清理");
        assertTrue(after.exists(), "提交后才落地的文件属于主RSS版本，绝不能删");
    }

    @Test
    void sweep_without_snapshot_keeps_files() {
        File file = FileUtil.writeUtf8String("old", new File(tempDir, "测试番剧 S01E05.mkv"));

        downloadService.deleteStandbyRss(ani(), item(true), null, null);

        assertTrue(file.exists(), "没有提交前快照时必须一个文件都不碰");
    }

    // ------------------------------------------------------------ 主条目闸门

    @Test
    void sweep_skipped_for_standby_item() {
        File file = FileUtil.writeUtf8String("old", new File(tempDir, "测试番剧 S01E05.mkv"));
        TorrentsInfo standbyTask = torrent("测试番剧 S01E05", "bbbbbbbbbbbbbbbb", standbyTags());
        givenTorrents(standbyTask);
        Set<String> snapshot = new HashSet<>();
        snapshot.add(file.getName());

        downloadService.deleteStandbyRss(ani(), item(false), null, snapshot);

        assertNull(stub.deletedName.get(), "备用条目下载时不得反向清理任务");
        assertTrue(file.exists(), "备用条目下载时不得反向清理文件");
    }
}