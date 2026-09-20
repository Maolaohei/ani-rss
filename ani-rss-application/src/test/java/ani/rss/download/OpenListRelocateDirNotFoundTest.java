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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「下载目录在网盘上不存在」是业务事实（= 确认没有），不是网盘故障。
 * <p>
 * 归位对账原先把这里落在外层 {@code catch (Exception)} 里，于是它和"TLS 超时 / 5xx / 冷却"
 * 一样被读成 {@link OfflineDownloader.RelocateResult#UNVERIFIABLE}，报出的日志是
 * 「归位对账无法完成（网盘不可用或异常）」。后果有两个：
 * <ol>
 *   <li>下载路径配错（模板 / provider / 挂载点）时，该目录下每条记录<b>每轮</b>都
 *       报"网盘不可用"，用户去查网络却永远查不出问题；</li>
 *   <li>记录既不会清、文件也不会重下 —— 永不收敛。</li>
 * </ol>
 * 现在「目录不存在」在内层单独摘出并回报 NOT_FOUND，交由调用方的兜底校验按
 * 「目录为空」判定（与 {@code OpenListApi.buildFileNames} 对同一异常的口径一致）。
 */
class OpenListRelocateDirNotFoundTest {

    private HttpServer server;
    private final AtomicInteger fsListRequests = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        fsListRequests.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 真实 Alist/OpenList 对不存在的目录就是这个形状：HTTP 200 + 业务 code != 200
        // + 文案含 "failed to get dir"（见 OpenListApi.isDirNotFoundMessage 的包含匹配）。
        server.createContext("/api/fs/list", exchange -> {
            fsListRequests.incrementAndGet();
            respond(exchange, 200, "{\"code\":500,\"message\":\"failed to get dir: object not found\"}");
        });
        server.createContext("/api/fs/get", exchange -> {
            fsListRequests.incrementAndGet();
            respond(exchange, 200, "{\"code\":500,\"message\":\"failed to get dir: object not found\"}");
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        OpenListApi.resetRateLimitState();
    }

    @AfterEach
    void tearDown() {
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

    @Test
    @DisplayName("目录不存在：归位对账回报「未找到」，不再谎报「网盘不可用」")
    void dir_not_found_is_not_found() {
        assertEquals(OfflineDownloader.RelocateResult.NOT_FOUND,
                openList().relocateEpisodeFiles(ani("ani-1"), item("某番 S01E01"), SAVE_PATH),
                "目录不存在是业务事实（确认没有），不是查询失败");
        assertTrue(fsListRequests.get() > 0, "前置条件：确实发起过列举请求");
    }

    /**
     * 「目录不存在不参与熔断」这条契约在归位对账链路上也要成立。
     * 否则一个配错的下载路径会把整个网盘接口打成冷却，连带其它订阅一起变「存疑」。
     */
    @Test
    @DisplayName("目录不存在不触发熔断，也不该把后续订阅拖进冷却")
    void dir_not_found_does_not_trip_breaker() {
        OpenList openList = openList();
        for (int i = 0; i < 5; i++) {
            assertEquals(OfflineDownloader.RelocateResult.NOT_FOUND,
                    openList.relocateEpisodeFiles(ani("ani-" + i), item("某番 S01E0" + (i + 1)), SAVE_PATH));
        }
        assertFalse(OpenListApi.isListingCoolingDown(), "目录不存在是业务结果，不是故障");
    }

    /**
     * 反向守卫：真正的故障（5xx / 超时）必须仍然是 UNVERIFIABLE。
     * 这条与上面的 NOT_FOUND 是一对——把两者分开才是本次改动的全部意义。
     */
    @Test
    @DisplayName("真正的列举故障仍然回报「无法判断」")
    void real_failure_is_still_unverifiable() {
        server.removeContext("/api/fs/list");
        server.createContext("/api/fs/list", exchange -> {
            fsListRequests.incrementAndGet();
            respond(exchange, 500, "{\"code\":500,\"message\":\"failed to list objs\"}");
        });
        assertEquals(OfflineDownloader.RelocateResult.UNVERIFIABLE,
                openList().relocateEpisodeFiles(ani("ani-9"), item("某番 S01E01"), SAVE_PATH),
                "真故障不能因为改了目录不存在的分支而降级成「未找到」");
    }
}
