package ani.rss.list;

import ani.rss.entity.Config;
import ani.rss.util.other.ConfigUtil;

import java.util.LinkedList;

/**
 * 用于存放日志
 *
 * @param <T>
 */
public class FixedSizeLinkedList<T> extends LinkedList<T> {

    public FixedSizeLinkedList() {
        super();
    }

    @Override
    public boolean add(T t) {
        boolean r = super.add(t);
        Config config = ConfigUtil.CONFIG;
        Integer max = config == null ? null : config.getLogsMax();
        int logsMax = max == null || max <= 0 ? 128 : max;

        // 本地 clamp 上限 512，不再写回全局 CONFIG：
        // add 是高频日志路径，回写会污染用户配置并触发无关语义（且多线程下撕裂）。
        int limit = Math.min(logsMax, 512);

        if (size() > limit) {
            removeRange(0, size() - limit);
        }
        return r;
    }
}
