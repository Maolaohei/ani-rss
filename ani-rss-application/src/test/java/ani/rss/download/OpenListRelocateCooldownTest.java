package ani.rss.download;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.util.other.ConfigUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归位对账在「查不到」时绝不能回报「未找到」。
 * <p>
 * 2026-09-18 线上日志把这条链条完整地演了一遍：
 * <pre>
 * 14:27:33 WARN  递归列出网盘目录失败 …: fs/list 失败 code=500 … TLS handshake timeout
 * 14:29:23 WARN  归位对账未找到且兜底检查无文件，清理过期种子记录并重新下载 …
 * 14:29:23 WARN  清理过期种子记录(无对应任务/文件) …
 * 14:29:27 INFO  本地已存在 …（同一集，四分钟后又说存在）
 * </pre>
 * 前一秒说"没有"、后一秒说"存在"，说明 14:29:23 的判定根本不是事实判断，而是查询失败被读成了
 * "确认没有"。代价是整季种子记录被删并重新下单，重下的产物又和原文件撞名，
 * 进入「重命名冲突 → 判失败 → 清 pending → 下一轮再重下」的死循环。
 * <p>
 * 本用例用真实 HTTP 500 复现该场景，钉住两条契约：
 * <ol>
 *   <li>列举失败 → {@link OfflineDownloader.RelocateResult#UNVERIFIABLE}（不是 NOT_FOUND）；</li>
 *   <li>熔断冷却期内 → 同样回报 UNVERIFIABLE，且<b>一个请求都不发</b>。</li>
 * </ol>
 */
class OpenListRelocateCooldownTest {

    private HttpServer server;
    private final AtomicInteger fsListRequests = new AtomicInteger();

    private Integer prevFailThreshold;
    private Integer prevCooldownSeconds;

    @BeforeEach
    void setUp() throws IOException {
        fsListRequests.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 复现线上：fs/list 返回 500（TLS 握手超时在 OpenList 侧就是 code=500）。
        // 注意用 500 而不是 502/503/504 —— 后者会被判成"瞬时故障"并重试，
        // 用例会慢上十几秒；线上那次是 code=500，正好也走不重试的分支。
        server.createContext("/api/fs/list", exchange -> {
            fsListRequests.incrementAndGet();
            respond(exchange, 500, "{\"code\":500,\"message\":\"failed to list objs\"}");
        });
        server.createContext("/api/fs/get", exchange -> {
            fsListRequests.incrementAndGet();
            respond(exchange, 500, "{\"code\":500,\"message\":\"failed to list objs\"}");
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        prevFailThreshold = ConfigUtil.CONFIG.getOpenListFailThreshold();
        prevCooldownSeconds = ConfigUtil.CONFIG.getOpenListCooldownSeconds();
        OpenListApi.resetRateLimitState();
    }

    @AfterEach
    void tearDown() {
        ConfigUtil.CONFIG.setOpenListFailThreshold(prevFailThreshold)
                .setOpenListCooldownSeconds(prevCooldownSeconds);
        OpenListApi.resetRateLimitState();
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private OpenList openList() {
        OpenList openList = new OpenList();
        // login 会先把 config 落到实例上再发请求；mock 没有 /api/me，返回值无所谓
        openList.login(true, new Config()
                .setDownloadToolHost("http://127.0.0.1:" + server.getAddress().getPort())
                .setDownloadToolPassword("mock-token")
                .setProvider("115 Cloud")
                .setDownloadPathTemplate("/追番"));
        return openList;
    }

    private static Item item(String reName) {
        return new Item().setReName(reName).setInfoHash("hash-" + reName);
    }

    private static Ani ani(String id) {
        return new Ani().setId(id).setTitle("某番").setSeason(1);
    }

    private static final String SAVE_PATH = "/115/动漫/转存/追番/某番 (2026) [tmdbid=1]/Season 1";

    // ---------------- 契约 1：列举失败不得退化成「未找到」 ----------------

    @Test
    @DisplayName("fs/list 500：归位对账回报「无法判断」，不是「未找到」")
    void listing_failure_is_unverifiable() {
        assertEquals(OfflineDownloader.RelocateResult.UNVERIFIABLE,
                openList().relocateEpisodeFiles(ani("ani-1"), item("某番 S01E01"), SAVE_PATH),
                "列举失败只能说明没查成，不能说明文件不存在");
        assertTrue(fsListRequests.get() > 0, "前置条件：确实发起过列举请求");
    }

    // ---------------- 契约 2：冷却期一个请求都不发 ----------------

    /**
     * 冷却只拦请求是不够的 —— 上游必须拿到一个不会触发破坏性动作的结论。
     * 这里同时断言"没有发请求"，把"冷却期不再发起网盘请求"这条也钉住。
     */
    @Test
    @DisplayName("熔断冷却中：回报「无法判断」，且不再发起任何网盘请求")
    void cooldown_is_unverifiable_without_any_request() {
        ConfigUtil.CONFIG.setOpenListFailThreshold(3).setOpenListCooldownSeconds(60);
        Exception boom = new RuntimeException("connection reset by peer");
        OpenListApi.onListingFailure(boom);
        OpenListApi.onListingFailure(boom);
        OpenListApi.onListingFailure(boom);
        assertTrue(OpenListApi.isListingCoolingDown(), "前置条件：应已进入冷却");

        OpenList openList = openList();
        int before = fsListRequests.get();

        assertEquals(OfflineDownloader.RelocateResult.UNVERIFIABLE,
                openList.relocateEpisodeFiles(ani("ani-2"), item("某番 S01E01"), SAVE_PATH));

        assertEquals(before, fsListRequests.get(), "冷却期内不应再发起网盘请求");
    }

    // ---------------- 边界：这不是查询失败 ----------------

    /**
     * reName 为空是"这条记录本身没有目标名"，属于业务事实而非查询失败，
     * 保持 NOT_FOUND 才不会把正常场景拖进"存疑"。
     */
    @Test
    @DisplayName("reName 为空仍回报「未找到」：这不是查询失败")
    void blank_rename_stays_not_found() {
        assertEquals(OfflineDownloader.RelocateResult.NOT_FOUND,
                openList().relocateEpisodeFiles(ani("ani-3"), item(""), SAVE_PATH));
    }
}
