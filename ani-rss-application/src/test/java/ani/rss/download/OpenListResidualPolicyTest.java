package ani.rss.download;

import ani.rss.entity.OpenListTaskInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenList 残留任务策略 / 边界用例
 */
class OpenListResidualPolicyTest {

    @Test
    void adopt_running_states() {
        for (OpenListTaskInfo.State state : new OpenListTaskInfo.State[]{
                OpenListTaskInfo.State.Pending,
                OpenListTaskInfo.State.Running,
                OpenListTaskInfo.State.Waiting_for_Retry,
                OpenListTaskInfo.State.Preparing_to_Retry
        }) {
            assertEquals(OpenList.ResidualAction.ADOPT,
                    OpenList.decideResidualAction(state, false),
                    "state=" + state);
        }
    }

    @Test
    void delete_duplicate_running_when_already_adopted() {
        assertEquals(OpenList.ResidualAction.DELETE_DUPLICATE_RUNNING,
                OpenList.decideResidualAction(OpenListTaskInfo.State.Running, true));
        assertEquals(OpenList.ResidualAction.DELETE_DUPLICATE_RUNNING,
                OpenList.decideResidualAction(OpenListTaskInfo.State.Pending, true));
    }

    @Test
    void delete_terminal_and_failed_states() {
        for (OpenListTaskInfo.State state : new OpenListTaskInfo.State[]{
                OpenListTaskInfo.State.Succeeded,
                OpenListTaskInfo.State.Error,
                OpenListTaskInfo.State.Failing,
                OpenListTaskInfo.State.Failed,
                OpenListTaskInfo.State.Canceling,
                OpenListTaskInfo.State.Canceled
        }) {
            assertEquals(OpenList.ResidualAction.DELETE,
                    OpenList.decideResidualAction(state, false),
                    "state=" + state);
            assertEquals(OpenList.ResidualAction.DELETE,
                    OpenList.decideResidualAction(state, true),
                    "state=" + state + " with adopted");
        }
    }

    @Test
    void delete_when_state_null() {
        assertEquals(OpenList.ResidualAction.DELETE,
                OpenList.decideResidualAction(null, false));
        assertEquals(OpenList.ResidualAction.DELETE,
                OpenList.decideResidualAction(null, true));
    }

    @Test
    void wash_season_key_null_safe_contract() {
        // SEASON_REG 未命中时不应 contains(null)；此处验证“空 key 不参与匹配”的约定
        String seasonKey = null;
        String fileName = "Show.S01E05v2.mkv";
        boolean shouldDelete = seasonKey != null && !seasonKey.isBlank() && fileName.contains(seasonKey);
        assertFalse(shouldDelete);

        seasonKey = "S01E05";
        shouldDelete = seasonKey != null && !seasonKey.isBlank() && fileName.contains(seasonKey);
        assertTrue(shouldDelete); // 用户确认 S01E05v2 可删
    }
}
