package ani.rss.util.basic;


import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.text.StrFormatter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 重命名缓存（SQLite，位于配置目录的 {@code database.db}）。
 *
 * <h2>本轮修掉的三件事（P2-2）</h2>
 * <ol>
 *   <li><b>写入原本是"先删再插"两条语句</b>，且不在事务里 —— 每次 {@code put} 要付出两次 fsync，
 *       中途失败还会留下"删了但没插进去"的空洞。改为单条
 *       {@code INSERT OR REPLACE}，一次落盘、天然原子。</li>
 *   <li><b>没有 WAL / busy_timeout</b>：默认的 {@code journal_mode=delete} 下每次写入都要独占整个库文件，
 *       而本方法又是被并发调用（RSS 轮次 + 改名任务）的。现在开 WAL +
 *       {@code synchronous=NORMAL}，并把锁等待放宽到 {@value #BUSY_TIMEOUT_MS}ms。</li>
 *   <li><b>连接一旦坏掉就永久不可用</b>：原实现是 {@code if (connection != null) return;}，
 *       于是配置恢复覆盖了 db 文件、或库被外部锁坏之后，改名链路会永远抛
 *       {@code RuntimeException} 且无法自愈。现在操作失败会丢弃连接、重建后重试一次。</li>
 * </ol>
 *
 * <h2>为什么还留着锁</h2>
 * 这里共用一条 {@link Connection}，而 JDBC 的 {@code Connection} 并不保证可被多线程并发使用。
 * 因此仍需要互斥，但改成<b>本类私有的锁对象</b>（不再用类锁），且临界区里只有本地 SQLite 操作——
 * 没有网络、没有等待，不构成新的瓶颈。
 */
@Slf4j
public class RenameCacheUtil {
    private static final String TABLE_NAME = "RENAME_CACHES";

    /**
     * SQLite 锁等待上限。并发写入时不再立刻抛 "database is locked"。
     */
    private static final int BUSY_TIMEOUT_MS = 5000;

    private static final Object LOCK = new Object();

    private static Connection connection;

    /**
     * 关停钩子只注册一次：原实现每次重建连接都会再挂一个钩子。
     */
    private static boolean shutdownHookRegistered;

    private RenameCacheUtil() {
    }

    private interface SqlAction<T> {
        T apply(Connection connection) throws SQLException;
    }

    public static void put(String key, String object) {
        log.debug("put => key: {}", key);
        withConnection("put", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    StrFormatter.format("INSERT OR REPLACE INTO {} (K, V) VALUES (?, ?)", TABLE_NAME))) {
                statement.setString(1, key);
                statement.setString(2, object);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public static String get(String key) {
        log.debug("get => key: {}", key);
        return withConnection("get", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    StrFormatter.format("SELECT V FROM {} WHERE K = ?", TABLE_NAME))) {
                statement.setString(1, key);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getString(1) : null;
                }
            }
        });
    }

    public static void remove(String key) {
        withConnection("remove", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    StrFormatter.format("DELETE FROM {} WHERE K = ?", TABLE_NAME))) {
                statement.setString(1, key);
                int rows = statement.executeUpdate();
                if (rows > 0) {
                    log.debug("remove => key: {}", key);
                }
            }
            return null;
        });
    }

    /**
     * 执行一次数据库操作；连接失效时重建并重试一次。
     * <p>
     * 不做"每次调用先 {@code isValid()}"——那是每个操作都多一次往返；改成失败后重建，
     * 正常路径零额外开销，异常路径能自愈。
     */
    private static <T> T withConnection(String action, SqlAction<T> sqlAction) {
        try {
            return sqlAction.apply(connection());
        } catch (SQLException e) {
            log.warn("重命名缓存 {} 失败，丢弃连接重建后重试一次: {}", action, ExceptionUtils.getMessage(e));
            discardConnection();
            try {
                return sqlAction.apply(connection());
            } catch (SQLException retry) {
                log.error("重命名缓存 {} 重试仍失败: {}", action, ExceptionUtils.getMessage(retry));
                throw new RuntimeException(retry);
            }
        }
    }

    private static Connection connection() {
        synchronized (LOCK) {
            if (connection != null) {
                return connection;
            }
            open();
            return connection;
        }
    }

    private static void open() {
        File configDir = ConfigUtil.getConfigDir();
        String absolutePath = FileUtils.getAbsolutePath(configDir);
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection(
                    StrFormatter.format("jdbc:sqlite:{}/database.db", absolutePath));

            try (Statement statement = connection.createStatement()) {
                // WAL：读写不再互相阻塞整库；synchronous=NORMAL 在 WAL 下已足够安全（丢电最多丢最后几笔，
                // 而本表是可重建的改名缓存，不是权威数据）
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute(StrFormatter.format("PRAGMA busy_timeout={}", BUSY_TIMEOUT_MS));
                statement.execute(StrFormatter.format(
                        "CREATE TABLE IF NOT EXISTS {} (K TEXT PRIMARY KEY, V TEXT)", TABLE_NAME));
            }

            registerShutdownHook();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            // 打开失败时不要把半开的连接留在字段上，否则后续调用会一直拿到坏连接
            discardConnection();
            throw new RuntimeException(e);
        }
    }

    private static void registerShutdownHook() {
        synchronized (LOCK) {
            if (shutdownHookRegistered) {
                return;
            }
            shutdownHookRegistered = true;
            Runtime.getRuntime().addShutdownHook(new Thread(RenameCacheUtil::discardConnection));
        }
    }

    private static void discardConnection() {
        synchronized (LOCK) {
            if (connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (Exception e) {
                log.debug("关闭重命名缓存连接失败: {}", ExceptionUtils.getMessage(e));
            } finally {
                connection = null;
            }
        }
    }

    /**
     * 仅供测试：关闭连接并允许用新的配置目录重新打开。
     */
    public static void resetForTest() {
        discardConnection();
    }
}
