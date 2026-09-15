package ani.rss.service;

import ani.rss.download.OpenList;
import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentsInfo;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「本地存在」判定（预览 / 手动搜索共用）。
 * <p>
 * 本测试固化 {@link DownloadService#applyLocalStates} 的三种情形与四条硬约束：
 * <ul>
 *   <li>未开启重命名 → 退回旧逻辑：<b>有种子记录即视为存在</b>（不标存疑）；</li>
 *   <li>开启重命名但列举失败 → 有记录标<b>存疑</b>，无记录仍是「否」；</li>
 *   <li>开启重命名且列举成功 → 一律以<b>真实文件</b>为准，有记录但文件不在就是「否」；</li>
 *   <li>整批只构建一次集数索引（逐条重建会让网盘模式下 300 条结果变成 300 次 API 调用）。</li>
 * </ul>
 */
class LocalStateResolutionTest {

    @TempDir
    Path tempDir;

    private final DownloadService downloadService = new DownloadService();

    private String downloadPath;

    private OpenList listing(List<String> fileNames) {
        return listing(new AtomicInteger(), fileNames);
    }

    private OpenList listing(AtomicInteger calls, List<String> fileNames) {
        return new OpenList() {
            @Override
            public List<String> listFileNames(String dirPath) {
                calls.incrementAndGet();
                return fileNames;
            }

            @Override
            public List<String> listFileNamesStrict(String dirPath) {
                calls.incrementAndGet();
                return fileNames;
            }
        };
    }

    private OpenList failingListing(AtomicInteger calls) {
        return new OpenList() {
            @Override
            public List<String> listFileNames(String dirPath) {
                calls.incrementAndGet();
                return List.of();
            }

            @Override
            public List<String> listFileNamesStrict(String dirPath) {
                calls.incrementAndGet();
                throw new IllegalStateException("网盘 API 502");
            }
        };
    }

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.resolve("config").toString());
        downloadPath = tempDir.resolve("downloads").toString().replace('\\', '/');
        ConfigUtil.CONFIG.setRenameTemplate(null)
                .setOvaRenameTemplate(null)
                .setDownloadPathTemplate(downloadPath)
                .setOvaDownloadPathTemplate(downloadPath)
                .setDownloadToolType("qBittorrent")
                .setRename(true).setFileExist(true)
                .setRenameDelYear(false).setRenameDelTmdbId(false);
        // 任务列表有 5 秒缓存，跨用例会串味
        TorrentUtil.refreshTorrentsCache();
    }

    @AfterEach
    void tearDown() {
        ConfigUtil.CONFIG.setDownloadToolType(null).setRename(null).setFileExist(null)
                .setDownloadPathTemplate(null).setOvaDownloadPathTemplate(null);
        TorrentUtil.DOWNLOAD = null;
        TorrentUtil.refreshTorrentsCache();
        System.clearProperty("CONFIG");
    }

    private Ani ani(String title, int season) {
        Ani ani = new Ani();
        ani.setTitle(title).setOva(false).setSeason(season).setOffset(0)
                .setBgmUrl("https://bgm.tv/subject/123")
                .setThemoviedbName("").setNamingVersion(2)
                .setCustomRenameTemplateEnable(false).setCustomEpisode(false)
                .setReleaseDate(new Date(116, 9, 21));
        return ani;
    }

    private Item item(String reName, Double episode, String infoHash) {
        Item item = new Item();
        item.setTitle(reName).setEpisode(episode).setReName(reName)
                .setTorrent("magnet:?xt=urn:btih:" + infoHash)
                .setInfoHash(infoHash).setMaster(true).setSubgroup("测试字幕组");
        return item;
    }

    // ---------------------------------------------------------------- 情形一：未开启重命名

    @Test
    void rename_disabled_falls_back_to_record_existence() {
        AtomicInteger calls = new AtomicInteger();
        TorrentUtil.DOWNLOAD = listing(calls, List.of("乡下大叔成了剑圣 S02E01.mkv"));
        ConfigUtil.CONFIG.setRename(false);

        Ani ani = ani("乡下大叔成了剑圣", 2);
        Item withRecord = item("乡下大叔成了剑圣 S02E01", 1.0, "hash1");
        TorrentUtil.saveTorrent(ani, withRecord);
        Item withoutRecord = item("乡下大叔成了剑圣 S02E02", 2.0, "hash2");

        List<DownloadService.LocalState> states =
                downloadService.applyLocalStates(ani, List.of(withRecord, withoutRecord));

        assertEquals(DownloadService.LocalState.EXISTS, states.get(0),
                "未开启重命名时文件名不可预测，退回旧逻辑：有种子记录即视为存在");
        assertTrue(withRecord.getHasDownloaded());
        assertFalse(withRecord.getHasDownloadedUnknown(),
                "旧逻辑的判定结果不应标存疑——用户明确要求「记录存在即显示是」");
        assertTrue(withRecord.getHasTorrentRecord());

        assertEquals(DownloadService.LocalState.ABSENT, states.get(1));
        assertFalse(withoutRecord.getHasDownloaded());
        assertEquals(0, calls.get(),
                "未开启重命名时不具备校验能力，不应发起任何网盘列举");
    }

    // ---------------------------------------------------------------- 情形二：列举失败 → 存疑

    @Test
    void listing_failure_marks_unknown_only_when_record_exists() {
        TorrentUtil.DOWNLOAD = failingListing(new AtomicInteger());
        Ani ani = ani("乡下大叔成了剑圣", 2);

        Item withRecord = item("乡下大叔成了剑圣 S02E01", 1.0, "hash1");
        TorrentUtil.saveTorrent(ani, withRecord);
        Item withoutRecord = item("乡下大叔成了剑圣 S02E02", 2.0, "hash2");

        List<DownloadService.LocalState> states =
                downloadService.applyLocalStates(ani, List.of(withRecord, withoutRecord));

        assertEquals(DownloadService.LocalState.UNKNOWN, states.get(0),
                "列举失败 != 目录为空：只能标存疑，不能谎报是、也不能降级成否");
        assertTrue(withRecord.getHasDownloadedUnknown());
        assertFalse(withRecord.getHasDownloaded(),
                "存疑条目不得同时标 hasDownloaded，否则前端会把「是」优先显示出来");

        assertEquals(DownloadService.LocalState.ABSENT, states.get(1),
                "查询失败且本地连记录都没有：没有任何存在证据，仍是「否」");
        assertFalse(withoutRecord.getHasDownloadedUnknown());
    }

    // ---------------------------------------------------------------- 情形三：真实文件为准

    @Test
    void verified_missing_file_is_absent_even_with_record() {
        TorrentUtil.DOWNLOAD = listing(List.of("乡下大叔成了剑圣 S02E01.mkv"));
        Ani ani = ani("乡下大叔成了剑圣", 2);

        Item item = item("乡下大叔成了剑圣 S02E05", 5.0, "hash5");
        File record = TorrentUtil.getTorrent(ani, item);
        TorrentUtil.saveTorrent(ani, item);

        List<DownloadService.LocalState> states =
                downloadService.applyLocalStates(ani, List.of(item));

        assertEquals(DownloadService.LocalState.ABSENT, states.get(0),
                "网盘里确实没有 E05：本地有记录也不能判定存在（旧实现正是这样谎报的）");
        assertFalse(item.getHasDownloaded());
        assertFalse(item.getHasDownloadedUnknown());
        assertTrue(item.getHasTorrentRecord(), "记录仍在，供「删除种子」与「可清理」提示使用");
        assertTrue(record.exists(), "判出「否」不得顺手删除种子记录（可能是网盘临时抖动）");
    }

    @Test
    void verified_present_file_is_exists_and_restores_record() {
        TorrentUtil.DOWNLOAD = listing(List.of("乡下大叔成了剑圣 S02E01.mkv"));
        Ani ani = ani("乡下大叔成了剑圣", 2);

        Item item = item("乡下大叔成了剑圣 S02E01", 1.0, "hash1");
        File record = TorrentUtil.getTorrent(ani, item);
        assertFalse(record.exists(), "前置条件：文件在但种子记录缺失");

        List<DownloadService.LocalState> states =
                downloadService.applyLocalStates(ani, List.of(item));

        assertEquals(DownloadService.LocalState.EXISTS, states.get(0));
        assertTrue(item.getHasDownloaded());
        assertFalse(item.getHasDownloadedUnknown());
        assertTrue(record.exists(), "文件确实存在时顺手补回记录，否则 RSS 主流程会把这集当未下载而重下");
    }

    @Test
    void resolve_alone_produces_no_write_actions() {
        TorrentUtil.DOWNLOAD = listing(List.of("乡下大叔成了剑圣 S02E01.mkv"));
        Ani ani = ani("乡下大叔成了剑圣", 2);
        Item item = item("乡下大叔成了剑圣 S02E01", 1.0, "hash1");
        File record = TorrentUtil.getTorrent(ani, item);

        DownloadService.LocalStateContext ctx = downloadService.prepareLocalState(ani);
        assertEquals(DownloadService.LocalState.EXISTS,
                downloadService.resolveLocalState(ani, item, ctx));
        assertFalse(record.exists(), "纯判定不得产生写动作（补记录只发生在 applyLocalStates）");
    }

    // ---------------------------------------------------------------- 性能：整批只列一次

    @Test
    void episode_index_is_built_once_per_batch() {
        AtomicInteger calls = new AtomicInteger();
        TorrentUtil.DOWNLOAD = listing(calls, List.of("乡下大叔成了剑圣 S02E01.mkv"));
        Ani ani = ani("乡下大叔成了剑圣", 2);

        List<Item> items = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            items.add(item("乡下大叔成了剑圣 S02E0" + i, (double) i, "hash" + i));
        }

        List<DownloadService.LocalState> states = downloadService.applyLocalStates(ani, items);

        assertEquals(5, states.size());
        assertEquals(1, calls.get(),
                "整批只应列举一次：逐条重建索引会让网盘模式下 300 条结果变成 300 次 API 调用"
                        + "（全局限流 300ms/次 ≈ 90 秒），首屏会被拖死");
        assertEquals(DownloadService.LocalState.EXISTS, states.get(0));
        assertEquals(DownloadService.LocalState.ABSENT, states.get(1));
    }

    // ---------------------------------------------------------------- 下载中优先于否 / 存疑

    @Test
    void pending_marker_reports_downloading() {
        TorrentUtil.DOWNLOAD = listing(List.of());
        Ani ani = ani("乡下大叔成了剑圣", 2);
        Item item = item("乡下大叔成了剑圣 S02E01", 1.0, "hash1");
        TorrentUtil.saveTorrentPending(ani, item);

        List<DownloadService.LocalState> states =
                downloadService.applyLocalStates(ani, List.of(item));

        assertEquals(DownloadService.LocalState.ABSENT, states.get(0), "文件尚未落地，事实就是「否」");
        assertTrue(item.getDownloading(),
                "已提交离线任务但未落地时必须标「下载中」，否则用户会误判为没在下而重复点强制下载");
        assertNotNull(item.getDownloadingState());
    }

    @Test
    void unknown_still_reports_downloading() {
        TorrentUtil.DOWNLOAD = failingListing(new AtomicInteger());
        Ani ani = ani("乡下大叔成了剑圣", 2);
        Item item = item("乡下大叔成了剑圣 S02E01", 1.0, "hash1");
        TorrentUtil.saveTorrent(ani, item);
        TorrentUtil.saveTorrentPending(ani, item);

        List<DownloadService.LocalState> states =
                downloadService.applyLocalStates(ani, List.of(item));

        assertEquals(DownloadService.LocalState.UNKNOWN, states.get(0));
        assertTrue(item.getHasDownloadedUnknown());
        assertTrue(item.getDownloading(),
                "「下载中」是更具体的事实，列举失败也不应把它盖掉（前端优先级 downloading > unknown）");
    }

    // ---------------------------------------------------------------- 改名前的窗口（最关键的回归点）

    @Test
    void downloaded_but_not_yet_renamed_is_unknown_not_absent() {
        Ani ani = ani("乡下大叔成了剑圣", 2);
        String dir = downloadService.getDownloadPath(ani);
        // 下载目录里还没有这一集的视频：任务刚提交、文件还没改名落地，
        // 此时文件名不含 SxxExx，真实文件校验必然失败。
        TorrentUtil.DOWNLOAD = new OpenList() {
            @Override
            public List<String> listFileNames(String dirPath) {
                return List.of();
            }

            @Override
            public List<String> listFileNamesStrict(String dirPath) {
                return List.of();
            }

            @Override
            public List<TorrentsInfo> getTorrentsInfos() {
                return List.of(new TorrentsInfo()
                        .setName("乡下大叔成了剑圣 S02E01")
                        .setDownloadDir(dir));
            }
        };
        TorrentUtil.refreshTorrentsCache();

        Item item = item("乡下大叔成了剑圣 S02E01", 1.0, "hash1");

        List<DownloadService.LocalState> states =
                downloadService.applyLocalStates(ani, List.of(item));

        assertEquals(DownloadService.LocalState.UNKNOWN, states.get(0),
                "下载器里已有同名任务 → 只是还没改名落地，不能判「否」");
        assertTrue(item.getHasDownloadedUnknown());
        assertFalse(item.getHasDownloaded());
    }

    @Test
    void absent_when_downloader_has_no_matching_task() {
        Ani ani = ani("乡下大叔成了剑圣", 2);
        String dir = downloadService.getDownloadPath(ani);
        TorrentUtil.DOWNLOAD = new OpenList() {
            @Override
            public List<String> listFileNames(String dirPath) {
                return List.of();
            }

            @Override
            public List<String> listFileNamesStrict(String dirPath) {
                return List.of();
            }

            @Override
            public List<TorrentsInfo> getTorrentsInfos() {
                // 同订阅的另一集，不是本条
                return List.of(new TorrentsInfo()
                        .setName("乡下大叔成了剑圣 S02E09")
                        .setDownloadDir(dir));
            }
        };
        TorrentUtil.refreshTorrentsCache();

        Item item = item("乡下大叔成了剑圣 S02E01", 1.0, "hash1");

        List<DownloadService.LocalState> states =
                downloadService.applyLocalStates(ani, List.of(item));

        assertEquals(DownloadService.LocalState.ABSENT, states.get(0),
                "下载器里没有对应任务，目录里也没有文件 → 确认不存在");
        assertFalse(item.getHasDownloadedUnknown());
    }

    // ---------------------------------------------------------------- 策略：存疑归入「已有」

    @Test
    void unknown_counts_as_present_absent_does_not() {
        assertTrue(DownloadService.LocalState.EXISTS.present());
        assertTrue(DownloadService.LocalState.UNKNOWN.present(),
                "存疑必须算作「已有」：无法校验时不能断言用户没有，"
                        + "否则「只看未下载」会把很可能已存在的条目推出来，用户一不留神就重复下单");
        assertFalse(DownloadService.LocalState.ABSENT.present());
    }

    @Test
    void listing_failure_does_not_push_items_into_missing_filter() {
        // 「只看未下载」的过滤依据：present() 为 true 的条目必须被排除
        TorrentUtil.DOWNLOAD = failingListing(new AtomicInteger());
        Ani ani = ani("乡下大叔成了剑圣", 2);
        Item withRecord = item("乡下大叔成了剑圣 S02E01", 1.0, "hash1");
        TorrentUtil.saveTorrent(ani, withRecord);

        DownloadService.LocalStateContext ctx = downloadService.prepareLocalState(ani);

        assertTrue(downloadService.resolveLocalState(ani, withRecord, ctx).present(),
                "列举失败 + 有记录 → 存疑，应被「只看未下载」排除");
        assertFalse(downloadService.resolveLocalState(
                ani, item("乡下大叔成了剑圣 S02E02", 2.0, "hash2"), ctx).present(),
                "列举失败 + 无记录 → 否，应出现在「只看未下载」里");
    }
}
