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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 备用RSS占位替换测试。
 * <p>
 * 修复场景: 备用RSS先下载的集, 主RSS出种后曾会被 itemDownloaded 的
 * "本地文件已存在/已存在下载任务"判定(按集数/重命名匹配, 不区分来源)永久拦截,
 * 替换(洗版)永远无法发生。现在可凭下载器任务上的「备用RSS」标签识别占位,
 * 删除备用任务与文件后由主RSS版本替换。
 */
class StandbyPlaceholderTest {

    private final DownloadService downloadService = new DownloadService();

    @TempDir
    File tempDir;

    /**
     * 删除行为可配置的下载器桩
     */
    private static class StubDownloader implements BaseDownload {
        final AtomicReference<String> deletedName = new AtomicReference<>();
        final AtomicReference<Boolean> deletedWithFiles = new AtomicReference<>();
        final AtomicBoolean allowDelete = new AtomicBoolean(true);

        @Override
        public Boolean login(Boolean test, Config config) {
            return true;
        }

        @Override
        public List<TorrentsInfo> getTorrentsInfos() {
            return List.of();
        }

        @Override
        public Boolean download(Ani ani, Item item, String savePath, File torrentFile) {
            return true;
        }

        @Override
        public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
            if (!allowDelete.get()) {
                return false;
            }
            deletedName.set(torrentsInfo.getName());
            deletedWithFiles.set(deleteFiles);
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

    private TorrentsInfo torrent(String name, List<String> tags) {
        TorrentsInfo torrentsInfo = new TorrentsInfo();
        torrentsInfo.setName(name)
                .setHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
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

    @Test
    void placeholder_removed_master_replaces() {
        // 备用RSS占位(同名任务+备用标签+已重命名): 删除任务与文件, 返回 true 放行主RSS
        TorrentsInfo standbyTorrent = torrent("测试番剧 S01E05", standbyTags());
        List<TorrentsInfo> torrentsInfos = new ArrayList<>(List.of(standbyTorrent));
        Set<String> localEpisodeIndex = new HashSet<>(List.of("1:5.0", "2:3.0"));

        boolean removed = downloadService.removeStandbyPlaceholder(ani(), item(true), torrentsInfos, localEpisodeIndex);

        assertTrue(removed, "备用占位应被识别并清除");
        assertEquals("测试番剧 S01E05", stub.deletedName.get(), "应删除备用占位任务");
        assertEquals(Boolean.TRUE, stub.deletedWithFiles.get(), "删除应包含文件(替换语义)");
        assertFalse(torrentsInfos.contains(standbyTorrent), "任务应从本轮任务列表移除");
        assertFalse(localEpisodeIndex.contains("1:5.0"), "被删文件对应的集数索引应同步移除");
        assertTrue(localEpisodeIndex.contains("2:3.0"), "无关集数索引不应被误删");
    }

    @Test
    void master_torrent_without_back_rss_tag_not_touched() {
        // 主RSS自己的任务(无备用标签): 不识别为占位, 不删除
        TorrentsInfo masterTorrent = torrent("测试番剧 S01E05",
                List.of(TorrentsTags.ANI_RSS.getValue(), TorrentsTags.RENAME.getValue(), "测试字幕组"));
        List<TorrentsInfo> torrentsInfos = new ArrayList<>(List.of(masterTorrent));
        Set<String> localEpisodeIndex = new HashSet<>(List.of("1:5.0"));

        boolean removed = downloadService.removeStandbyPlaceholder(ani(), item(true), torrentsInfos, localEpisodeIndex);

        assertFalse(removed, "主RSS自身任务不应被当作备用占位");
        assertNull(stub.deletedName.get(), "不应发生删除");
        assertTrue(torrentsInfos.contains(masterTorrent));
        assertTrue(localEpisodeIndex.contains("1:5.0"));
    }

    @Test
    void standby_item_itself_not_replaced() {
        // 备用条目自身不触发替换(只有主RSS条目才洗版)
        List<TorrentsInfo> torrentsInfos = new ArrayList<>(List.of(torrent("测试番剧 S01E05", standbyTags())));

        boolean removed = downloadService.removeStandbyPlaceholder(ani(), item(false), torrentsInfos, new HashSet<>());

        assertFalse(removed, "备用条目不应触发占位替换");
        assertNull(stub.deletedName.get());
    }

    @Test
    void coexist_disables_replacement() {
        // 多字幕组共存模式不洗版
        ConfigUtil.CONFIG.setCoexist(true);
        List<TorrentsInfo> torrentsInfos = new ArrayList<>(List.of(torrent("测试番剧 S01E05", standbyTags())));

        boolean removed = downloadService.removeStandbyPlaceholder(ani(), item(true), torrentsInfos, new HashSet<>());

        assertFalse(removed, "共存模式不进行替换");
        assertNull(stub.deletedName.get());
    }

    @Test
    void delete_disabled_no_replacement() {
        // 未开启自动删除(洗版)时不替换
        ConfigUtil.CONFIG.setDelete(false);
        List<TorrentsInfo> torrentsInfos = new ArrayList<>(List.of(torrent("测试番剧 S01E05", standbyTags())));

        boolean removed = downloadService.removeStandbyPlaceholder(ani(), item(true), torrentsInfos, new HashSet<>());

        assertFalse(removed, "洗版关闭时不替换");
        assertNull(stub.deletedName.get());
    }

    @Test
    void not_renamed_placeholder_waits() {
        // 备用任务尚未完成重命名(可能仍在下载): 等待下轮, 不删除
        List<String> tagsWithoutRename = new ArrayList<>(standbyTags());
        tagsWithoutRename.remove(TorrentsTags.RENAME.getValue());
        TorrentsInfo standbyTorrent = torrent("测试番剧 S01E05", tagsWithoutRename);
        List<TorrentsInfo> torrentsInfos = new ArrayList<>(List.of(standbyTorrent));

        boolean removed = downloadService.removeStandbyPlaceholder(ani(), item(true), torrentsInfos, new HashSet<>());

        assertFalse(removed, "未完成重命名的占位应等待下轮");
        assertNull(stub.deletedName.get());
        assertTrue(torrentsInfos.contains(standbyTorrent));
    }

    @Test
    void delete_rejection_waits_next_round() {
        // 下载器拒绝删除(如做种限制): 等待下轮
        stub.allowDelete.set(false);
        TorrentsInfo standbyTorrent = torrent("测试番剧 S01E05", standbyTags());
        List<TorrentsInfo> torrentsInfos = new ArrayList<>(List.of(standbyTorrent));

        boolean removed = downloadService.removeStandbyPlaceholder(ani(), item(true), torrentsInfos, new HashSet<>());

        assertFalse(removed, "删除失败应等待下轮");
        assertTrue(torrentsInfos.contains(standbyTorrent));
    }

    @Test
    void different_episode_not_matched() {
        // 任务名/集数不匹配: 不误删其它集的备用任务
        TorrentsInfo otherEpisode = torrent("测试番剧 S01E06", standbyTags());
        List<TorrentsInfo> torrentsInfos = new ArrayList<>(List.of(otherEpisode));

        boolean removed = downloadService.removeStandbyPlaceholder(ani(), item(true), torrentsInfos, new HashSet<>());

        assertFalse(removed, "不同集的备用任务不应被误删");
        assertNull(stub.deletedName.get());
        assertTrue(torrentsInfos.contains(otherEpisode));
    }

    @Test
    void sxxexx_fallback_matches_original_name() {
        // 任务名非模板名(如未走 rename 参数的旧任务)时, 按 SxxExx 集数兜底匹配
        TorrentsInfo standbyTorrent = torrent("[字幕组] 测试番剧 - 05 [1080p][S01E05]", standbyTags());
        List<TorrentsInfo> torrentsInfos = new ArrayList<>(List.of(standbyTorrent));

        boolean removed = downloadService.removeStandbyPlaceholder(ani(), item(true), torrentsInfos, new HashSet<>());

        assertTrue(removed, "按 SxxExx 兜底应命中备用占位");
        assertEquals("[字幕组] 测试番剧 - 05 [1080p][S01E05]", stub.deletedName.get());
    }

    @Test
    void no_torrents_noop() {
        // 下载器无任务(如文件已被手动清理): 保持原跳过行为
        boolean removed = downloadService.removeStandbyPlaceholder(ani(), item(true), new ArrayList<>(), new HashSet<>());

        assertFalse(removed);
        assertNull(stub.deletedName.get());
    }
}
