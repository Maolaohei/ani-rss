package ani.rss.e2e;

import ani.rss.AniRssApplication;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.entity.Login;
import ani.rss.testsupport.FakeOpenListServer;
import ani.rss.testsupport.TestTorrent;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 端到端测试：把<b>整个应用</b>跑起来，用真实 HTTP 接口驱动它走完「加订阅 → 拉 RSS →
 * 提交离线下载 → 重命名归位 → 清理临时目录 → 落盘订阅状态」这条链路。
 * <p>
 * <h2>被替换的只有"应用之外的世界"</h2>
 * <ul>
 *   <li>RSS 源 / 种子下载站：{@link FakeOriginServer}（通过应用自身的代理配置接入，
 *       见该类注释解释为什么不能直接用 127.0.0.1）；</li>
 *   <li>网盘（OpenList/AList + 115）：{@link FakeOpenListServer}（真实 API 形状）；</li>
 * </ul>
 * 其它一切都是真的：Spring 上下文、Tomcat、鉴权、RSS 解析与命名、下载决策、目录归位、
 * 并发锁、订阅状态落盘、配置读写。<b>没有一处 mock 应用自己的类</b>。
 * <p>
 * <h2>隔离</h2>
 * 配置目录指向临时目录（{@code -DCONFIG}），因此不会碰到真实用户数据；
 * 该类带 {@code @Tag("e2e")}，由 surefire 的独立执行（单独 JVM、不复用 fork）运行，
 * 避免与单测共享静态状态。
 * <p>
 * <h2>工件</h2>
 * 结束后在 {@code target/e2e-artifact} 生成 report.json / report.md / inputs/：
 * 输入（RSS XML、种子、脱敏配置）带 SHA-256，结论带证据（云盘最终目录树、任务状态、请求明细），
 * 任何人拿这些输入重跑都应得到相同结论。
 */
@Tag("e2e")
@SpringBootTest(classes = AniRssApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DownloadJourneyE2ETest {

    // ==================== 场景输入（全部进工件） ====================

    /** 订阅标题 / 字幕组 / 种子文件名 —— 决定最终文件名，是断言与工件的锚点 */
    static final String ANI_ID = "e2e-ani-0001";
    static final String ANI_TITLE = "Show";
    static final String SUBGROUP = "LoliHouse";
    static final String SEED_FILE = "[LoliHouse] Show - 01 [1080p].mkv";
    static final long SEED_SIZE = 1000L;
    static final String FEED_PATH = "/feed/show.xml";
    static final String TORRENT_PATH = "/torrents/show-01.torrent";
    static final String REMOTE_ROOT = "/115/动漫/追番";
    static final String SAVE_PATH = REMOTE_ROOT + "/" + ANI_TITLE + "/Season 1";
    static final String EXPECTED_FILE_PREFIX = ANI_TITLE + " S01E01";
    /** 重命名模板为 {@code ${title} S${seasonFormat}E${episodeFormat}}，因此终态文件名可精确断言 */
    static final String EXPECTED_FILE = ANI_TITLE + " S01E01.mkv";
    /**
     * 离线任务的落点目录名 = {@code item.reName}（最终名去掉扩展名）。
     * 网盘离线先落到 {@code savePath/tempDirName}，归位成功后才搬到 {@code savePath} 顶层，
     * 临时目录随后被清理 —— 这是既有设计，不是测试的期望偏差。
     */
    static final String TEMP_DIR_NAME = ANI_TITLE + " S01E01";
    static final String LOGIN_USER = "admin";
    static final String LOGIN_PASSWORD = "ani-rss-e2e";
    /** BGM 条目 id：订阅的 bgmUrl 末尾就是这个数字（AniUtil 用正则从 URL 里取） */
    static final String BGM_SUBJECT_ID = "12345";
    /** BgmUtil 里硬编码的第三方镜像主机，必须一并代理，否则 e2e 仍会真的出网 */
    static final String MIRROR_HOST = "cache.wushuo.top";

    // ==================== 外部世界与隔离环境 ====================

    static final FakeOriginServer ORIGIN = new FakeOriginServer();
    static final FakeOpenListServer CLOUD = new FakeOpenListServer();
    static final E2EArtifact ARTIFACT = E2EArtifact.create("DownloadJourneyE2ETest");

    static Path configDir;
    static File torrentFile;
    static String infoHash;
    static Path torrentSourceDir;

    static {
        try {
            bootstrap();
        } catch (Exception e) {
            throw new IllegalStateException("端到端测试环境启动失败", e);
        }
    }

    @LocalServerPort
    int port;

    /**
     * 应用自己的 HTTP 入口。
     * <p>
     * <b>必须带 {@code /api} 前缀</b>：{@code WebMvcConfig.configurePathMatch} 给所有
     * {@code @RestController} 统一加了 {@code /api} 前缀（前端也是这么调的），
     * 少这一层会以 {@code {"code":404,...}} 收场——看着像"接口不存在"，其实是路径前缀不对。
     */
    String baseUrl() {
        return "http://127.0.0.1:" + port + "/api";
    }

    private static void bootstrap() throws Exception {
        CLOUD.seedFileName = SEED_FILE;
        CLOUD.start();

        ORIGIN.start();
        torrentSourceDir = Files.createTempDirectory("ani-rss-e2e-seed-");
        torrentFile = TestTorrent.write(torrentSourceDir.resolve("show-01.torrent").toFile(),
                SEED_FILE, List.of(TestTorrent.file(SEED_FILE, SEED_SIZE)));
        infoHash = TestTorrent.infoHash(torrentFile);
        String feed = feedXml();
        ORIGIN.serveText(FEED_PATH, "application/rss+xml", feed);
        ORIGIN.serve(TORRENT_PATH, "application/x-bittorrent", Files.readAllBytes(torrentFile.toPath()));
        // BGM 假端点：/addAni 会强制查一次 BGM（AniUtil 先断言 bgmUrl 非空，再 getBgmInfo），
        // 而 getBgmInfo 内部是"官方 API + cache.wushuo.top 镜像"两路并行。
        // 必须把两路都指到假公网，否则这条链路会真的出网 —— 结论就不再可复现了。
        ORIGIN.serveText("/bgm/v0/subjects/" + BGM_SUBJECT_ID, "application/json", bgmSubjectJson());
        ORIGIN.serveText("/bgm/subjects/" + BGM_SUBJECT_ID, "application/json", bgmSubjectJson());

        // 隔离配置目录：必须在 Spring 上下文创建之前设置（ConfigUtil.getConfigDir 读的就是它）
        configDir = Files.createTempDirectory("ani-rss-e2e-config-");
        System.setProperty("CONFIG", configDir.toAbsolutePath().toString());
        writeConfig();

        ARTIFACT.input("aniId", ANI_ID);
        ARTIFACT.input("aniTitle", ANI_TITLE);
        ARTIFACT.input("subgroup", SUBGROUP);
        ARTIFACT.input("feedUrl", ORIGIN.url(FEED_PATH));
        ARTIFACT.input("torrentUrl", ORIGIN.url(TORRENT_PATH));
        ARTIFACT.input("infoHash", infoHash);
        ARTIFACT.input("remoteRoot", REMOTE_ROOT);
        ARTIFACT.input("expectedFilePrefix", EXPECTED_FILE_PREFIX);
        ARTIFACT.inputFile("feed.xml", feed);
        ARTIFACT.inputFile("show-01.torrent", Files.readAllBytes(torrentFile.toPath()));
        ARTIFACT.inputFile("config.v2.json", Files.readAllBytes(ConfigUtil.getConfigFile().toPath()));
    }

    /**
     * 隔离配置：只把"必须指向假环境"和"会让时序不确定"的项改掉，其余全部保留应用默认值，
     * 这样被测的是产品默认行为，而不是一份为测试特调的配置。
     */
    private static void writeConfig() {
        Config c = ConfigUtil.CONFIG;
        c.setProxy(true)
                .setProxyHost("127.0.0.1")
                .setProxyPort(ORIGIN.port())
                // 只有假公网主机走代理：其它请求（本机 OpenList）仍直连。
                // cache.wushuo.top 是 BgmUtil 里硬编码的第三方镜像，不代理它就会真的出网。
                .setProxyList(FakeOriginServer.HOST + "\n" + MIRROR_HOST)
                .setProxyUsername("")
                .setProxyPassword("")
                // BGM API 指到假公网：/addAni 会强制查一次（见 bootstrap 里的假端点）
                .setBgmApi("http://" + FakeOriginServer.HOST + "/bgm")
                // 必须与 UI 下拉框写入的值完全一致（TorrentUtil.load 直接拼
                // "ani.rss.download." + downloadToolType 去反射加载类，大小写不匹配会
                // 以 NoClassDefFoundError: ani/rss/download/OPENLIST 收场）
                .setDownloadToolType("OpenList")
                .setDownloadToolHost(CLOUD.host())
                .setDownloadToolUsername("")
                .setDownloadToolPassword("e2e-token")
                .setProvider("115 Cloud")
                .setDownloadPathTemplate(REMOTE_ROOT + "/${title}/Season ${season}")
                .setOvaDownloadPathTemplate(REMOTE_ROOT + "/剧场版/${title}")
                .setRename(true)
                .setRenameTemplate("${title} S${seasonFormat}E${episodeFormat}")
                .setOvaRenameTemplate("${title} (${year})")
                .setDelete(true)
                .setDeleteStandbyRSSOnly(false)
                .setStandbyRss(false)
                .setCoexist(false)
                .setFileExist(false)
                .setAutoDisabled(false)
                // 时间相关的闸门全部归零：否则"新种子等 2 小时""延迟下载"会把首轮直接跳过，
                // 测试就变成在等定时器而不是在测链路
                .setDelayedDownload(0)
                .setNewTorrentWaitHours(0)
                .setDownloadCount(0)
                .setAlistDownloadTimeout(1)
                .setAlistDownloadRetryNumber(2L)
                .setRssTimeout(5)
                .setRssRetry(1)
                .setRssConcurrency(1)
                .setStaggeredUpdateEnable(false)
                // 关掉所有会出网 / 会自动改状态的功能，保证结论只由被测链路决定
                .setAutoTrackersUpdate(false)
                .setAutoUpdate(false)
                .setDisableUpdate(true)
                .setScrape(false)
                .setTmdb(false)
                .setTmdbId(false)
                .setTmdbAnime(false)
                .setTmdbApi("")
                .setTmdbApiKey("")
                .setBgmToken("")
                .setAssrtToken("")
                .setNotificationConfigList(new ArrayList<>())
                .setLogin(new Login().setUsername(LOGIN_USER)
                        // 单层摘要，与首启随机口令（ConfigUtil.load）/ 配置页首次保存
                        // （ConfigView.saveConfig）产生的形态一致；登录页发的也是 sha256(明文)。
                        // 这正是 c9d23565 之后**完全登录不了**的那个形态，e2e 必须覆盖它。
                        // 历史双层哈希由 LoginPasswordCheckTest 单独覆盖。
                        .setPassword(SecureUtil.sha256(LOGIN_PASSWORD)));

        File file = ConfigUtil.getConfigFile();
        assertNotNull(file, "配置文件路径");
        FileUtil.writeUtf8String(GsonStatic.toJson(c), file);
    }

    /**
     * 假 RSS 源：单条 E01。
     * <p>
     * {@code guid} 用种子的真实 infoHash（16 进制）：应用据此建立"种子缓存文件名"，
     * 不再走磁力元数据解析（那需要真实 DHT 网络，在测试里必然超时）。
     */
    private static String feedXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>ani-rss e2e</title>
                    <link>%s</link>
                    <description>ani-rss 端到端测试源</description>
                    <item>
                      <title>%s</title>
                      <guid>%s</guid>
                      <enclosure url="%s" length="%d" type="application/x-bittorrent" />
                      <pubDate>Mon, 01 Jan 2024 12:00:00 +0800</pubDate>
                    </item>
                  </channel>
                </rss>
                """.formatted(ORIGIN.url(FEED_PATH), SEED_FILE, infoHash,
                ORIGIN.url(TORRENT_PATH), SEED_SIZE);
    }

    // ==================== 被测行为 ====================

    @Test
    @Order(1)
    @DisplayName("E2E-1 加订阅 → 抓 RSS/种子 → 网盘离线 → 重命名归位 → 清理临时目录 → 状态落盘")
    void journey_downloads_and_relocates() throws Exception {
        long start = System.currentTimeMillis();
        String token = login();
        long baseline = lastFinishedAt(token);

        JsonObject add = postResult("/addAni", token, aniJson());
        assertEquals("添加订阅成功", add.get("message").getAsString());

        JsonObject status = awaitRound(token, baseline, 180_000);
        // 再等离线下载收尾：RSS 轮次结束 ≠ 网盘侧下载完成（见 awaitOfflineDone 的说明）
        status = awaitOfflineDone(token, 180_000);

        List<String> top = CLOUD.topLevel(SAVE_PATH);
        String tree = CLOUD.tree();
        String aniFile = readAniFile();
        List<String> records = torrentRecordNames();

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("savePath", SAVE_PATH);
        evidence.put("savePathTopLevel", top);
        evidence.put("cloudTree", tree);
        evidence.put("offlineSubmissions", offlineSubmissionText());
        evidence.put("jobStatus", status.toString());
        evidence.put("originRequests", ORIGIN.requests());
        evidence.put("aniV2Json", aniFile);
        evidence.put("torrentRecords", records);

        boolean tempDirLeft = CLOUD.exists(SAVE_PATH + "/" + TEMP_DIR_NAME);
        boolean recordSaved = records.stream().anyMatch(n -> n.contains(infoHash));
        boolean ok = List.of(EXPECTED_FILE).equals(top) && !tempDirLeft && recordSaved;
        String detail = ok
                ? "终态唯一文件=" + EXPECTED_FILE + "，临时目录已清理，种子记录已落盘"
                : "终态=" + top + "（期望 " + EXPECTED_FILE + "）临时目录残留=" + tempDirLeft
                + " 种子记录=" + records;
        ARTIFACT.scenario("E2E-1 加订阅→下载→归位→清理", ok ? "PASS" : "FAIL",
                System.currentTimeMillis() - start, detail, evidence);

        // 逐条断言：失败信息自带上下文，报告里也有同一份证据
        assertEquals(0, status.get("subscriptionFailed").getAsInt(),
                "本轮不应有失败订阅: " + status);
        assertEquals(List.of(EXPECTED_FILE), top,
                "下载目录顶层应只有重命名后的文件；云盘全树=" + tree);
        assertFalse(tempDirLeft,
                "归位后临时目录应被清理；云盘全树=" + tree);
        assertEquals(1, CLOUD.offlineSubmissions.size(),
                "应只提交 1 次离线任务，实际=" + offlineSubmissionText());
        // 离线先落到 savePath 下的临时目录（名=最终名去扩展名），归位成功后才搬到 savePath 顶层。
        // 这是既有设计（submitOffline 里 `path = savePath + "/" + tempDirName`），不是本测试的期望偏差。
        assertEquals(SAVE_PATH + "/" + TEMP_DIR_NAME, CLOUD.offlineSubmissions.get(0).path(),
                "离线任务应先落到临时目录");
        assertTrue(ORIGIN.requests().stream().anyMatch(r -> r.contains(FEED_PATH)),
                "应用应真的抓取过 RSS，实际请求=" + ORIGIN.requests());
        assertTrue(ORIGIN.requests().stream().anyMatch(r -> r.contains(TORRENT_PATH)),
                "应用应真的下载过种子，实际请求=" + ORIGIN.requests());
        // 种子记录落在 configDir/torrents 下、文件名就是 infoHash（TorrentUtil.getTorrent）。
        // ani.v2.json 里没有 infoHash 字段，拿它来断言永远不成立。
        assertTrue(recordSaved,
                "torrents 目录应留下以 infoHash 命名的记录；实际=" + records);
    }

    @Test
    @Order(2)
    @DisplayName("E2E-2 第二轮刷新：不得重复下载，终态不变")
    void journey_second_round_is_idempotent() throws Exception {
        long start = System.currentTimeMillis();
        String token = login();

        int submissionsBefore = CLOUD.offlineSubmissions.size();
        long baseline = lastFinishedAt(token);
        postResult("/refreshAni", token, "{\"id\":\"" + ANI_ID + "\"}");
        JsonObject status = awaitRound(token, baseline, 180_000);
        // 同样要等离线侧收尾：否则可能在"本轮刚提交完"的瞬间就断言终态
        status = awaitOfflineDone(token, 180_000);

        List<String> top = CLOUD.topLevel(SAVE_PATH);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("offlineSubmissionsBefore", submissionsBefore);
        evidence.put("offlineSubmissionsAfter", CLOUD.offlineSubmissions.size());
        evidence.put("savePathTopLevel", top);
        evidence.put("jobStatus", status.toString());

        boolean ok = submissionsBefore == CLOUD.offlineSubmissions.size()
                && List.of(EXPECTED_FILE).equals(top);
        String detail = ok
                ? "刷新后未新增离线提交，终态仍为 " + EXPECTED_FILE
                : "新增离线提交 " + (CLOUD.offlineSubmissions.size() - submissionsBefore) + " 次，终态=" + top;
        ARTIFACT.scenario("E2E-2 第二轮幂等（不重复下载）", ok ? "PASS" : "FAIL",
                System.currentTimeMillis() - start, detail, evidence);

        assertEquals(0, status.get("subscriptionFailed").getAsInt(),
                "第二轮不应有失败订阅: " + status);
        assertEquals(submissionsBefore, CLOUD.offlineSubmissions.size(),
                "已下载过的集数不得重复提交离线任务；提交记录=" + offlineSubmissionText());
        assertEquals(List.of(EXPECTED_FILE), top, "第二轮不得改变云盘终态");
    }

    @Test
    @Order(3)
    @DisplayName("E2E-3 保存设置：密码留空不改层数、不踢下线")
    void saving_config_must_not_touch_stored_password() throws Exception {
        long start = System.currentTimeMillis();
        String token = login();
        String storedBefore = storedPassword();

        // ① /config 必须脱敏：响应里的 login.password 是空串。
        //    这是"前端不会把摘要当明文再哈希一次"的前提 ——
        //    ConfigView.saveConfig 只在字段非空时才 SHA256，字段空则原样发回。
        JsonObject config = postResult("/config", token, "{}").getAsJsonObject("data");
        String masked = config.getAsJsonObject("login").get("password").getAsString();
        assertEquals("", masked,
                "读取设置必须脱敏密码，否则前端会把摘要再哈希一次变成双层");

        // ② 照 UI 的做法原样保存（密码字段留空）
        JsonObject save = postResult("/setConfig", token, config.toString());
        assertEquals(200, save.get("code").getAsInt(), "保存设置应成功: " + save);

        // ③ 核心不变量：密码的哈希层数不得变化
        String storedAfter = storedPassword();
        assertEquals(storedBefore, storedAfter, "保存设置不得改动密码");
        assertEquals(SecureUtil.sha256(LOGIN_PASSWORD), storedAfter,
                "存盘密码应是单层摘要 sha256(明文)");
        assertNotEquals(SecureUtil.sha256(SecureUtil.sha256(LOGIN_PASSWORD)), storedAfter,
                "不得变成双层摘要：那样登录页发的单层摘要就永远对不上了");

        // ④ 已签发的 token 必须仍然有效。
        //    因为 getAuth 是"实时配置"的纯函数（AuthUtil.getLogin 克隆 ConfigUtil.CONFIG.login）：
        //    config.login 一变，所有已签发 token 立刻失效 —— 用户会在"保存成功"之后被踢回登录页。
        JsonObject status = postResult("/rssJobStatus", token, "{}");
        assertEquals(200, status.get("code").getAsInt(),
                "保存设置不应把已登录用户踢下线: " + status);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("storedPasswordBefore", storedBefore);
        evidence.put("storedPasswordAfter", storedAfter);
        evidence.put("expectedSingleLayer", SecureUtil.sha256(LOGIN_PASSWORD));
        evidence.put("configResponseMasked", masked);
        evidence.put("rssJobStatusCodeAfterSave", status.get("code").getAsInt());

        ARTIFACT.scenario("E2E-3 保存设置不改变密码层数、不踢下线", "PASS",
                System.currentTimeMillis() - start,
                "密码仍是单层摘要，且保存后旧 token 依然可用", evidence);
    }

    /** 读盘上 config.v2.json 里的 login.password（应用自己写出来的真相） */
    private String storedPassword() {
        JsonObject file = JsonParser.parseString(FileUtil.readUtf8String(ConfigUtil.getConfigFile()))
                .getAsJsonObject();
        return file.getAsJsonObject("login").get("password").getAsString();
    }

    @Test
    @Order(4)
    @DisplayName("E2E-4 工件自证：report.json / report.md / inputs 落盘且内容与断言一致")
    void journey_artifact_is_self_verifiable() throws Exception {
        long start = System.currentTimeMillis();
        Path dir = ARTIFACT.write();
        Path json = dir.resolve("report.json");
        Path md = dir.resolve("report.md");
        assertTrue(Files.exists(json), "应生成 report.json: " + dir);
        assertTrue(Files.exists(md), "应生成 report.md: " + dir);
        assertTrue(Files.exists(dir.resolve("inputs").resolve("feed.xml")), "应留存原始输入 feed.xml");
        assertTrue(Files.exists(dir.resolve("inputs").resolve("show-01.torrent")), "应留存原始输入 show-01.torrent");

        JsonObject report = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject inputs = report.getAsJsonObject("inputs");
        JsonArray scenarios = report.getAsJsonArray("scenarios");
        String raw = report.toString();

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("artifactDir", dir.toString());
        evidence.put("inputHashes", inputs.toString());
        evidence.put("scenarioCount", scenarios.size());

        // 此刻工件里只可能有 E2E-1 / E2E-2 / E2E-3 三条<b>业务</b>场景：E2E-4 的结论要等它自己读完
        // report.json 才记录，而 @AfterAll 会再写一次把 E2E-4 补齐。所以断言"业务场景都在"，
        // 不写死总数（写死 4 永远不成立，写死 3 又会随场景增减而脆）。
        List<String> scenarioNames = new ArrayList<>();
        for (int i = 0; i < scenarios.size(); i++) {
            scenarioNames.add(scenarios.get(i).getAsJsonObject().get("name").getAsString());
        }
        evidence.put("scenarioNames", scenarioNames);

        // 断言全部放在 ARTIFACT.scenario 之前：只有这样，工件里出现 "PASS"
        // 才真的代表下面这几条断言都过了，而不是"先盖合格章再体检"。
        assertEquals(infoHash, inputs.get("infoHash").getAsString(), "工件应记录种子 infoHash");
        assertTrue(inputs.has("feed.xml.sha256"), "工件应记录输入 RSS 的哈希");
        assertTrue(inputs.has("show-01.torrent.sha256"), "工件应记录输入种子的哈希");
        assertEquals(64, inputs.get("config.v2.json.sha256").getAsString().length(),
                "配置哈希应为 SHA-256");
        assertTrue(scenarioNames.stream().anyMatch(n -> n.startsWith("E2E-1")),
                "工件应含 E2E-1 结论，实际=" + scenarioNames);
        assertTrue(scenarioNames.stream().anyMatch(n -> n.startsWith("E2E-2")),
                "工件应含 E2E-2 结论，实际=" + scenarioNames);
        assertTrue(scenarioNames.stream().anyMatch(n -> n.startsWith("E2E-3")),
                "工件应含 E2E-3 结论，实际=" + scenarioNames);
        assertTrue(raw.contains(EXPECTED_FILE), "工件应包含云盘终态证据");
        assertTrue(raw.contains(FEED_PATH), "工件应包含假 RSS 请求证据");
        assertEquals(0, ARTIFACT.failureCount(), "不应有失败场景: " + scenarios);

        ARTIFACT.scenario("E2E-4 工件自证", "PASS", System.currentTimeMillis() - start,
                "工件目录=" + dir, evidence);
    }

    // ==================== HTTP 驱动（只走真实接口，不碰内部类） ====================

    private String login() {
        JsonObject body = new JsonObject();
        body.addProperty("username", LOGIN_USER);
        // 必须发 SHA-256 摘要（与前端 CryptoJS.SHA256 一致）：
        // 服务端签发的 token 是 sha256(json(Login))，而校验时用的是<b>配置里那份</b> Login；
        // 若这里发明文，登录照样会成功（服务端拿 sha256(明文) 去比对摘要），
        // 但签发的 token 里带着明文，与校验时的摘要对不上 → 后续所有接口 403「登录已失效」。
        body.addProperty("password", SecureUtil.sha256(LOGIN_PASSWORD));
        String token = postResult("/login", null, body.toString()).get("data").getAsString();
        assertNotNull(token, "登录应返回 token");
        return token;
    }

    private JsonObject postResult(String path, String token, String json) {
        return postResult(path, token, json, true);
    }

    private JsonObject postResult(String path, String token, String json, boolean assertOk) {
        HttpRequest req = HttpRequest.post(baseUrl() + path).timeout(30_000);
        if (token != null) {
            req.header("Authorization", token);
        }
        if (json != null) {
            req.body(json, "application/json");
        }
        String raw = req.execute().body();
        assertNotNull(raw, path + " 应返回响应体");
        JsonObject result = JsonParser.parseString(raw).getAsJsonObject();
        if (assertOk) {
            assertEquals(200, result.get("code").getAsInt(), path + " 调用失败: " + raw);
        }
        return result;
    }

    /** 取"上一次轮次完成时间"作为基线：只有 > 基线才算本轮真的跑完了 */
    private long lastFinishedAt(String token) {
        JsonObject data = postResult("/rssJobStatus", token, "{}").getAsJsonObject("data");
        return data.has("lastFinishedAt") && !data.get("lastFinishedAt").isJsonNull()
                ? data.get("lastFinishedAt").getAsLong() : 0L;
    }

    /**
     * 等本轮 RSS/下载任务真正结束。
     * <p>
     * 只等"连续两次 idle"是不够的 —— 手工刷新是异步提交的，可能在它启动前就先看到两个 idle
     * 而提前返回。这里以 {@code lastFinishedAt} 增长为准：既不会提前，也天然排除上一轮。
     */
    private JsonObject awaitRound(String token, long baselineFinishedAt, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonObject data = null;
        while (System.currentTimeMillis() < deadline) {
            data = postResult("/rssJobStatus", token, "{}").getAsJsonObject("data");
            boolean running = data.get("running").getAsBoolean();
            long finished = lastFinishedAt(token);
            if (!running && finished > baselineFinishedAt) {
                return data;
            }
            Thread.sleep(500);
        }
        fail("等待 RSS 轮次结束超时（" + timeoutMs + "ms），最后状态=" + data);
        return data;
    }

    /**
     * 等离线下载真正收尾（网盘侧不再有在途离线任务）。
     * <p>
     * <b>为什么不能只等 RSS 轮次</b>：OpenList 是"提交即受理"——RSS 轮次把离线任务交给
     * {@code OFFLINE_WAIT_POOL} 之后立刻就结束了（实测 4.3s），此时网盘侧还是 {@code Pending}
     * （{@code offlineEtaMs} 还有 55s）。只等 RSS 就去断言终态，等于在任务刚提交时检查结果，
     * 必然拿到"文件还在临时目录里"。
     */
    private JsonObject awaitOfflineDone(String token, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonObject data = null;
        while (System.currentTimeMillis() < deadline) {
            data = postResult("/rssJobStatus", token, "{}").getAsJsonObject("data");
            if (!data.get("openListBusy").getAsBoolean()) {
                return data;
            }
            Thread.sleep(500);
        }
        fail("等待离线下载收尾超时（" + timeoutMs + "ms），最后状态=" + data);
        return data;
    }

    /** 订阅对象：字段全部显式给出，避免 null 走进各条默认分支导致结论不可复现 */
    private static String aniJson() {
        JsonObject ani = new JsonObject();
        ani.addProperty("id", ANI_ID);
        ani.addProperty("title", ANI_TITLE);
        ani.addProperty("url", ORIGIN.url(FEED_PATH));
        // /addAni 强制要求 bgmUrl 非空，且要能从中取出 subjectId（正则 ^http(s)?://.+/(\d+)/?$）
        ani.addProperty("bgmUrl", "https://bgm.tv/subject/" + BGM_SUBJECT_ID);
        ani.addProperty("season", 1);
        ani.addProperty("subgroup", SUBGROUP);
        ani.addProperty("offset", 0);
        ani.addProperty("enable", true);
        ani.addProperty("ova", false);
        ani.addProperty("globalExclude", false);
        ani.addProperty("namingVersion", 1);
        ani.addProperty("downloadNew", false);
        // 遗漏检测：字段可空，而 ItemsUtil 里是 `if (!ani.getOmit())` 直接拆箱 —— 不给值就会 NPE，
        // 表现为"订阅加上了但每轮都失败"。这里显式关掉，也让本轮的下载行为可预期。
        ani.addProperty("omit", false);
        ani.addProperty("procrastinating", false);
        ani.addProperty("upload", false);
        ani.addProperty("message", false);
        ani.add("match", new JsonArray());
        ani.add("exclude", new JsonArray());
        ani.add("notDownload", new JsonArray());
        return ani.toString();
    }

    /**
     * BGM 条目的最小可用响应。
     * <p>
     * 字段取真 API 的形态（{@code name_cn} 走 {@code @SerializedName} 的 alternate），
     * 但刻意**不写任何季/别名标记**：这样 {@code getSeasonByBgmInfo} 会落到默认值 1，
     * 与断言里的 {@code Season 1} 一致。{@code date} 给真格式，交给项目的 DateAdapter 解析。
     */
    private static String bgmSubjectJson() {
        return """
                {
                  "id": "%s",
                  "name": "%s",
                  "name_cn": "%s",
                  "date": "2026-01-01",
                  "eps": 12,
                  "platform": "TV",
                  "tags": [{"name": "TV", "count": "1"}],
                  "infobox": [],
                  "rating": {"rank": 100, "score": 7.5, "total": 100}
                }
                """.formatted(BGM_SUBJECT_ID, ANI_TITLE, ANI_TITLE);
    }

    // ==================== 收尾：生成工件并拆掉假环境 ====================

    @AfterAll
    static void tearDown() {
        try {
            ARTIFACT.section("云盘最终目录树", CLOUD.tree());
            ARTIFACT.section("假公网收到的请求", String.join("\n", ORIGIN.requests()));
            ARTIFACT.section("离线提交记录", String.join("\n", offlineSubmissionText()));
            ARTIFACT.section("订阅落盘 ani.v2.json", readAniFile());
            ARTIFACT.write();
        } finally {
            ORIGIN.stop();
            CLOUD.stop();
            System.clearProperty("CONFIG");
        }
    }

    static List<String> offlineSubmissionText() {
        return CLOUD.offlineSubmissions.stream()
                .map(s -> s.path() + " <= " + s.magnet() + " tid=" + s.tid()
                        + (s.duplicateRejected() ? " (重复被拒)" : ""))
                .toList();
    }

    /**
     * 正式种子记录的文件名列表（递归扫描 {@code configDir/torrents}，返回相对路径）。
     * <p>
     * 记录落在 {@code TorrentUtil.getTorrentDir(ani)} 下，而那个目录是<b>分层</b>构造的
     * （{@code torrents/<title>/Season N} 或 {@code torrents/<season>/<title>}），
     * 所以只看顶层会永远扫不到，必须递归。
     * <p>
     * 刻意跳过 {@code .pending}：那是「在途账本」（L2），记录只出现在 pending 里
     * 说明它还没被提升为正式记录（L1）。本用例要守的正是这个区别 ——
     * 所以这个辅助方法不能图省事把整棵树都算上。
     */
    static List<String> torrentRecordNames() {
        File root = new File(configDir.toFile(), "torrents");
        if (!root.isDirectory()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        collectRecords(root, root, names);
        names.sort(String::compareTo);
        return names;
    }

    private static void collectRecords(File root, File dir, List<String> names) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (".pending".equals(f.getName())) {
                    continue;
                }
                collectRecords(root, f, names);
            } else {
                names.add(root.toPath().relativize(f.toPath()).toString().replace('\\', '/'));
            }
        }
    }

    /** 读订阅落盘文件（应用自己写出来的真相，而不是内存里的对象） */
    static String readAniFile() {
        try {
            File file = new File(configDir.toFile(), "ani.v2.json");
            return file.exists() ? FileUtil.readUtf8String(file) : "(ani.v2.json 不存在)";
        } catch (Exception e) {
            return "(读取失败: " + e.getMessage() + ")";
        }
    }
}
