package ani.rss.util.other;

import ani.rss.commons.FileUtils;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.frostwire.jlibtorrent.SessionManager;
import com.frostwire.jlibtorrent.TorrentInfo;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 磁力链接元数据解析（BEP-9）。
 * <p>
 * 合集下载需要「文件列表 + 每个文件的大小」才能做预览、匹配/排除与改名计划，
 * 而磁力链接本身只有 infoHash。这里用 jlibtorrent（libtorrent 的 JNI 绑定）
 * 接入 DHT/Tracker 抓取元数据，成功后缓存成标准 {@code .torrent} 文件——
 * 之后整条下游链路（预览、qBittorrent 提交、OpenList 离线下载、种子记录）
 * 与「上传种子文件」完全同一条路径，不需要任何分支。
 * <p>
 * 缓存与失败策略：
 * <ul>
 *   <li>缓存按<b>归一化后的磁力链接</b>（补过默认 Tracker）取 SHA-256 命名，
 *       落在 {@code {config}/cache/magnet/}，由 {@code /api/clearCache} 一并清理；</li>
 *   <li>写入用「临时文件 + 原子移动」，缓存里不会出现写了一半的种子；</li>
 *   <li>失败一律抛 {@link IllegalStateException}（已被全局异常处理器转成用户可见的提示），
 *       绝不返回空文件冒充成功——空文件会被下载器判成坏种。</li>
 * </ul>
 * 原生库缺失（32 位 arm、musl 等无对应平台包的环境）时 {@link LinkageError} 会被
 * 转成明确提示，用户改用 {@code .torrent} 文件即可，其它功能不受影响。
 */
@Slf4j
public final class MagnetTorrentUtil {

    /**
     * 单次元数据抓取超时（秒）。
     * <p>
     * 磁力能不能连上 Peer 取决于做种人数与网络（尤其国内直连 DHT 常常不稳），
     * 给够时间，但也不能无限期占住请求线程。
     */
    private static final int FETCH_TIMEOUT_SECONDS = 60;

    /**
     * 默认 Tracker：只补「发现 Peer 的入口」，不覆盖链接里已有的 tr。
     * <p>
     * 很多站的磁力链接不带 tr，或者只带自己那台不稳定的 Tracker；
     * DHT 在部分网络下又不通，补几个公共 Tracker 能显著提高抓取成功率。
     */
    private static final List<String> DEFAULT_TRACKERS = List.of(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce"
    );

    private MagnetTorrentUtil() {
    }

    /**
     * 是否是磁力链接（宽松判定：仅看协议前缀，格式细节交给 jlibtorrent 报错）
     */
    public static boolean isMagnet(String torrent) {
        return StrUtil.isNotBlank(torrent)
                && StrUtil.startWithIgnoreCase(torrent.trim(), "magnet:?");
    }

    /**
     * 解析磁力链接的元数据并落成种子文件（命中缓存时直接返回）。
     * <p>
     * 整个方法 {@code synchronized}：一次解析最长 60s，而并发解析只会让
     * DHT 反复引导、并抢占同一个上下行带宽。合集添加是低频人工操作，串行完全够用。
     *
     * @param torrent 磁力链接
     * @return 可供预览与下载使用的种子文件（位于配置目录缓存下，<b>调用方不要删除</b>）
     * @throws IllegalStateException 格式错误 / 抓取超时 / 原生库不可用 / 缓存写入失败
     */
    public static synchronized File resolve(String torrent) {
        String magnet = normalize(torrent);

        File cached = resolveCached(torrent);
        if (cached != null) {
            log.info("磁力链接元数据命中缓存 {}", cached.getName());
            return cached;
        }

        File cacheDir = cacheDir();
        File torrentFile = new File(cacheDir, SecureUtil.sha256(magnet) + ".torrent");
        Path tempDir = null;
        // 注意：SessionManager 的实例化必须放在 try 内——原生库是在类初始化时加载的，
        // 缺库环境会在这里抛 LinkageError，放在外面就漏掉了降级提示
        SessionManager sessionManager = null;
        try {
            sessionManager = new SessionManager();
            tempDir = Files.createTempDirectory("ani-rss-magnet-");
            sessionManager.start();

            // fetchMagnet 只取元数据，不下载种子里的文件
            byte[] torrentData = sessionManager.fetchMagnet(magnet, FETCH_TIMEOUT_SECONDS, tempDir.toFile());
            if (torrentData == null || torrentData.length == 0) {
                throw new IllegalStateException(StrUtil.format(
                        "获取磁力链接元数据超时（{} 秒），请确认链接有效、做种人数充足或网络可访问 Tracker/DHT",
                        FETCH_TIMEOUT_SECONDS));
            }
            // 落盘前校验返回内容确实是种子元数据，避免把垃圾缓存下来反复复用
            TorrentInfo.bdecode(torrentData);

            File temp = new File(cacheDir, torrentFile.getName() + ".temp");
            FileUtil.writeBytes(torrentData, temp);
            FileUtils.move(temp.toPath(), torrentFile.toPath());
            FileUtil.del(temp);

            log.info("磁力链接元数据解析完成 {}", torrentFile.getName());
            return torrentFile;
        } catch (LinkageError e) {
            // 当前平台没有对应的 jlibtorrent 原生库（32 位 arm / musl 等）
            log.warn("磁力解析原生库不可用: {}", e.toString());
            throw new IllegalStateException(
                    "当前运行环境不支持磁力链接解析（jlibtorrent 原生库加载失败），请改用 .torrent 文件", e);
        } catch (IOException e) {
            throw new IllegalStateException("磁力链接元数据缓存写入失败: " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable e) {
            // jlibtorrent 的失败信息是英文的，补一层中文上下文；原因原样带出便于排查
            log.error("磁力链接解析失败 {}", e.getMessage());
            log.error(e.getMessage(), e);
            throw new IllegalStateException("磁力链接解析失败: " + e.getMessage(), e);
        } finally {
            // SessionManager 持有原生线程，使用结束后必须主动释放；临时目录里是抓取中间产物
            if (sessionManager != null) {
                try {
                    sessionManager.stop();
                } catch (Throwable e) {
                    log.debug("释放磁力会话失败: {}", e.getMessage());
                }
            }
            if (tempDir != null) {
                FileUtil.del(tempDir.toFile());
            }
        }
    }

    /**
     * 只看缓存、不触网的解析。
     * <p>
     * 给"不想为了拿元数据而等最多 60s"的调用方用（如离线等待里的计划重建）：
     * 缓存命中时返回种子文件，未命中返回 null。同样不碰原生库。
     *
     * @param torrent 磁力链接
     * @return 缓存中的种子文件；未缓存/非磁力链时返回 null
     */
    public static File resolveCached(String torrent) {
        if (!isMagnet(torrent)) {
            return null;
        }
        String magnet = normalize(torrent);
        File cached = new File(cacheDir(), SecureUtil.sha256(magnet) + ".torrent");
        // 写入走「临时文件 + 原子移动」，读到的文件必然是完整种子
        return isUsableTorrent(cached) ? cached : null;
    }

    /**
     * 清理磁力元数据缓存，返回释放的字节数（供「清理缓存」展示）
     */
    public static long clearCache() {
        File cacheDir = cacheDir();
        if (!cacheDir.isDirectory()) {
            return 0L;
        }
        long size = FileUtil.size(cacheDir);
        FileUtil.del(cacheDir);
        return size;
    }

    static File cacheDir() {
        File cacheDir = new File(ConfigUtil.getConfigDir(), "cache" + File.separator + "magnet");
        FileUtil.mkdir(cacheDir);
        return cacheDir;
    }

    /**
     * 补默认 Tracker 并 trim，保证「同一个磁力链接」得到同一个缓存键
     */
    static String normalize(String torrent) {
        if (!isMagnet(torrent)) {
            throw new IllegalStateException("磁力链接格式错误，应以 magnet:? 开头");
        }
        String magnet = torrent.trim();
        StringBuilder result = new StringBuilder(magnet);
        for (String tracker : DEFAULT_TRACKERS) {
            String encoded = URLEncoder.encode(tracker, StandardCharsets.UTF_8);
            if (magnet.contains("tr=" + tracker) || magnet.contains("tr=" + encoded)) {
                continue;
            }
            // Tracker 必须作为独立的 tr 查询参数并做 URL 编码
            result.append('&').append("tr=").append(encoded);
        }
        return result.toString();
    }

    /**
     * 缓存文件是否可用：非空 + 首字节是 bencode 字典头。
     * <p>
     * 只看文件头不解码，是为了让<b>缓存命中路径完全不碰原生库</b>——
     * 原生库加载不了的环境里，已有的缓存仍然能用。
     */
    private static boolean isUsableTorrent(File file) {
        if (!file.isFile() || file.length() <= 0) {
            return false;
        }
        try {
            byte[] bytes = FileUtil.readBytes(file);
            return bytes.length > 2 && bytes[0] == 'd';
        } catch (Exception e) {
            log.warn("读取磁力缓存失败，将重新解析 {}: {}", file.getName(), e.getMessage());
            return false;
        }
    }
}
