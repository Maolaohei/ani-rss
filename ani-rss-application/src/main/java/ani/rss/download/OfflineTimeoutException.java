package ani.rss.download;

/**
 * OpenList 离线下载超过用户配置的【离线超时】时抛出。
 * 语义：超时清理后主动失败，不是坏种。
 */
public class OfflineTimeoutException extends RuntimeException {
    public OfflineTimeoutException(String message) {
        super(message);
    }
}