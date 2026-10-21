package ani.rss.task;

import ani.rss.entity.Ani;
import ani.rss.entity.TorrentsInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 为静态调度入口提供下载执行能力，避免调度器直接查询容器。
 * <p>
 * 未运行 Spring（单元测试、工具类场景）时返回空实现：反查订阅返回空，
 * 执行下载直接抛错而不是静默跳过——宁可让这一轮失败可见，也不能假装下载成功。
 */
@Component
public final class RssDownloadPortProvider {
    private static final RssDownloadPort UNAVAILABLE = new RssDownloadPort() {
        @Override
        public void downloadAni(Ani ani) {
            throw new IllegalStateException("下载服务尚未就绪");
        }

        @Override
        public Optional<Ani> findAniByDownloadPath(TorrentsInfo torrentsInfo) {
            return Optional.empty();
        }
    };

    private static volatile RssDownloadPort port = UNAVAILABLE;

    public RssDownloadPortProvider(ObjectProvider<RssDownloadPort> provider) {
        port = provider.orderedStream()
                .findFirst()
                .orElse(UNAVAILABLE);
    }

    public static RssDownloadPort get() {
        return port;
    }
}
