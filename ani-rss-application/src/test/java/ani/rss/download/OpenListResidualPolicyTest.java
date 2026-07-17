package ani.rss.download;

import ani.rss.entity.OpenListTaskInfo;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenList residual policy / 10008 boundary tests
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
        String seasonKey = null;
        String fileName = "Show.S01E05v2.mkv";
        boolean shouldDelete = seasonKey != null && !seasonKey.isBlank() && fileName.contains(seasonKey);
        assertFalse(shouldDelete);

        seasonKey = "S01E05";
        shouldDelete = seasonKey != null && !seasonKey.isBlank() && fileName.contains(seasonKey);
        assertTrue(shouldDelete);
    }

    @Test
    void isDuplicateOfflineError_detects_10008_and_cn_msg() {
        String cn = "任务已存在，请勿输入重复的链接地址";
        String embedded = "failed to add offline download task: {\"data\":\"xxx\",\"errcode\":10008,\"error_msg\":\"" + cn + "\",\"state\":false}: unexpected error";
        assertTrue(OpenList.isDuplicateOfflineError(embedded));
        assertTrue(OpenList.isDuplicateOfflineError("{\"errcode\":10008}"));
        assertTrue(OpenList.isDuplicateOfflineError(cn));
        assertTrue(OpenList.isDuplicateOfflineError("duplicate task link already exists"));
        assertFalse(OpenList.isDuplicateOfflineError(null));
        assertFalse(OpenList.isDuplicateOfflineError(""));
        assertFalse(OpenList.isDuplicateOfflineError("magnet parse failed"));
        assertFalse(OpenList.isDuplicateOfflineError("network timeout"));
    }

    @Test
    void isDuplicateMagnetCooling_window() {
        ConcurrentHashMap<String, Long> table = new ConcurrentHashMap<>();
        long now = 1_000_000L;
        assertFalse(OpenList.isDuplicateMagnetCooling("abc", now, table));
        table.put("abc", now + 10);
        assertTrue(OpenList.isDuplicateMagnetCooling("ABC", now, table));
        assertFalse(OpenList.isDuplicateMagnetCooling("abc", now + 10, table));
        assertFalse(OpenList.isDuplicateMagnetCooling("abc", now + 11, table));
        assertFalse(OpenList.isDuplicateMagnetCooling(null, now, table));
        assertFalse(OpenList.isDuplicateMagnetCooling("abc", now, null));
    }

    @Test
    void isDuplicateOfflineError_not_confused_with_other_codes() {
        assertFalse(OpenList.isDuplicateOfflineError("{\"errcode\":10007,\"error_msg\":\"other\"}"));
        assertFalse(OpenList.isDuplicateOfflineError("failed to upload"));
    }

    @Test
    void isEpisodeFileName_matches_season_token() {
        String re = "Show Title S01E03";
        assertTrue(OpenList.isEpisodeFileName(re + ".mkv", re));
        assertTrue(OpenList.isEpisodeFileName("foo.S01E03.1080p.mkv", re));
        assertTrue(OpenList.isEpisodeFileName("foo.s01e03.mkv", re));
        assertFalse(OpenList.isEpisodeFileName("foo.S01E01.mkv", re));
        assertFalse(OpenList.isEpisodeFileName("foo.S01E02.mkv", re));
        assertFalse(OpenList.isEpisodeFileName(null, re));
        assertFalse(OpenList.isEpisodeFileName("x.mkv", null));
    }
}