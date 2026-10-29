package ani.rss.download.openlist.residual;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「删除完成」只能有一个含义：回读确认过。
 * <p>
 * OpenList/115 的 {@code fs/remove} 对非空目录是异步的，{@code code=990009}（"操作尚未执行完成"）
 * 只代表<b>已受理</b>；限流/冷却期请求甚至发不出去。旧实现把这两类一并当成功，
 * 于是日志写着「已删除临时目录」而盘上原样还在。
 */
class TempDirDeleteResultTest {

    @Test
    void only_confirmed_states_count_as_deleted() {
        assertTrue(TempDirDeleteResult.DELETED.confirmed(), "回读确认不存在 = 删掉了");
        assertTrue(TempDirDeleteResult.ALREADY_ABSENT.confirmed(),
                "本来就不存在也是「确认没有」，不能算失败（否则巡检永远还不掉这笔账）");
        assertFalse(TempDirDeleteResult.FAILED_RETRYABLE.confirmed(),
                "限流/冷却/990009 未落地都不算删掉，必须留着债务重试");
        assertFalse(TempDirDeleteResult.MISMATCH_HUMAN.confirmed(),
                "现场不符时没删，绝不能计入已清理");
    }

    @Test
    void retryable_and_human_are_different_channels() {
        assertFalse(TempDirDeleteResult.FAILED_RETRYABLE.needsHuman());
        assertTrue(TempDirDeleteResult.MISMATCH_HUMAN.needsHuman(),
                "同名项是文件、成片不在顶层这类情况，重试多少次都没用，只能转人工");
    }
}
