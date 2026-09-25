package ani.rss.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 「假公网」：端到端测试里唯一被替换掉的东西 —— 应用之外的世界。
 * <p>
 * <h2>为什么不能用 127.0.0.1 直接当 RSS 源</h2>
 * 应用对 RSS 地址做了 SSRF 校验（{@code URLUtils.verify}）：回环/内网/链路本地地址一律拒绝，
 * DNS 解析失败也拒绝。所以本地起的服务不可能被直接当成"外网 RSS 源"。
 * <p>
 * <h2>做法</h2>
 * 用一个<b>公网 IP 字面量</b>当假域名（RFC 5737 文档保留段 {@value #HOST}）：
 * <ul>
 *   <li>{@code InetAddress.getByName("203.0.113.10")} 是字面量解析，<b>不需要 DNS</b>，
 *       离线环境同样成立；</li>
 *   <li>它既不是回环也不是内网，因此通过 SSRF 校验；</li>
 *   <li>再把这个 host 写进应用自己的代理白名单（{@code proxy=true} + {@code proxyList}），
 *       应用就<b>用真实的 HTTP 客户端</b>把请求发给本机这个服务（请求行是 absolute-form）。</li>
 * </ul>
 * 结果：RSS 抓取、种子下载、失败重试走的都是应用的生产代码路径，只有"对端是谁"被替换，
 * 而且不需要任何 mock 框架、不碰 socket 层。
 */
public class FakeOriginServer {

    /** 假公网主机（RFC 5737 TEST-NET-3，文档保留地址，不可路由） */
    public static final String HOST = "203.0.113.10";

    /** 访问 - 内容（路由） */
    private record Route(String contentType, byte[] body) {
    }

    private HttpServer server;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    /** 收到过的请求（方法 + 目标），既是证据也是报告内容 */
    private final List<String> requestLog = Collections.synchronizedList(new ArrayList<>());

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 本机监听端口（应用会把它当作 HTTP 代理端口） */
    public int port() {
        return server.getAddress().getPort();
    }

    /** 假公网 URL（应用视角的地址） */
    public String url(String path) {
        return "http://" + HOST + path;
    }

    public void serveText(String path, String contentType, String body) {
        serve(path, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    public void serve(String path, String contentType, byte[] body) {
        routes.put(path, new Route(contentType, body));
    }

    public Map<String, String> requestSummary() {
        Map<String, String> summary = new LinkedHashMap<>();
        synchronized (requestLog) {
            for (String line : requestLog) {
                summary.merge(line, "1", (a, b) -> String.valueOf(Integer.parseInt(a) + 1));
            }
        }
        return summary;
    }

    public List<String> requests() {
        synchronized (requestLog) {
            return new ArrayList<>(requestLog);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        // 代理模式下 getRequestURI() 是 absolute-form（http://host/path），path 依然可用
        String path = uri.getPath();
        String host = uri.getHost();
        requestLog.add(exchange.getRequestMethod() + " " + (host == null ? "" : host) + path);

        Route route = path == null ? null : routes.get(path);
        if (route == null) {
            byte[] body = ("no route: " + path).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", route.contentType());
        exchange.sendResponseHeaders(200, route.body().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(route.body());
        }
    }
}
