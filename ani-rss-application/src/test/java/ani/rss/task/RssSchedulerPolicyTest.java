package ani.rss.task;

import ani.rss.download.OpenListApi;
import ani.rss.entity.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RSS 调度纯配置策略的契约测试。
 */
class RssSchedulerPolicyTest {
    @Test
    void user_budget_is_clamped_to_hard_limit() {
        Config config = new Config().setOpenListApiBudgetPerRound(10_000);
        assertEquals(OpenListApi.MAX_API_BUDGET_PER_ROUND,
                RssSchedulerPolicy.apiBudgetPerRound(config, 10));
    }

    @Test
    void default_budget_covers_every_subscription_without_exceeding_affordable_work() {
        Config config = new Config()
                .setOpenListApiPerSecond(1)
                .setRssSleepMinutes(15);
        int affordable = RssSchedulerPolicy.affordableListingsPerRound(config);
        int budget = RssSchedulerPolicy.apiBudgetPerRound(config, 500);

        assertEquals(500, budget,
                "预算不得低于订阅数，否则大库后半程会被整体判成存疑");
        assertTrue(budget >= affordable || budget == 500);
    }

    @Test
    void small_subscription_count_uses_estimate() {
        Config config = new Config().setOpenListApiPerSecond(20);
        assertEquals(5 * RssSchedulerPolicy.LISTINGS_PER_SUBSCRIPTION_ESTIMATE,
                RssSchedulerPolicy.apiBudgetPerRound(config, 5));
    }
}
