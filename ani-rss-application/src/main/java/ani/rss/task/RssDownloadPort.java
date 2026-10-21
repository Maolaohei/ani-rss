package ani.rss.task;

import ani.rss.entity.Ani;
import ani.rss.entity.TorrentsInfo;

import java.util.Optional;

/**
 * RSS 调度所需的下载执行能力。
 * <p>
 * 调度器只关心“执行一轮订阅”和“由下载任务反查订阅”，不依赖具体下载服务实现。
 */
public interface RssDownloadPort {
    void downloadAni(Ani ani);

    Optional<Ani> findAniByDownloadPath(TorrentsInfo torrentsInfo);
}
