package ani.rss.task;

import ani.rss.download.OpenListApi;
import ani.rss.entity.Config;

import java.util.concurrent.TimeUnit;

/**
 * RSS 调度中的纯配置策略。
 * <p>
 * 只做数值换算与钳制，不读写任务状态、不访问下载器或 Spring 容器。
 */
public final class RssSchedulerPolicy {
    static final int LISTINGS_PER_SUBSCRIPTION_ESTIMATE = 4;

    private RssSchedulerPolicy() {
    }

    /**
     * 单轮网盘 API 预算。用户显式配置优先，并受硬上限约束。
     */
    public static int apiBudgetPerRound(Config config, int enabledCount) {
        int subscriptions = Math.max(1, enabledCount);
        Integer configured = config == null ? null : config.getOpenListApiBudgetPerRound();
        if (configured != null) {
            return Math.max(1, Math.min(configured, OpenListApi.MAX_API_BUDGET_PER_ROUND));
        }
        int estimate = subscriptions * LISTINGS_PER_SUBSCRIPTION_ESTIMATE;
        int affordable = affordableListingsPerRound(config);
        return Math.max(subscriptions, Math.min(estimate, affordable));
    }

    /**
     * 周期内可负担的列举次数 = 令牌桶速率 × 周期秒数 ÷ 4。
     */
    public static int affordableListingsPerRound(Config config) {
        int perSecond = OpenListApi.effectiveApiPerSecond(config);
        int sleepMinutes = config == null || config.getRssSleepMinutes() == null
                ? 15
                : Math.max(1, config.getRssSleepMinutes());
        long affordable = (long) (perSecond * TimeUnit.MINUTES.toSeconds(sleepMinutes) / 4.0);
        return (int) Math.max(1L, Math.min(affordable, Integer.MAX_VALUE));
    }
}
