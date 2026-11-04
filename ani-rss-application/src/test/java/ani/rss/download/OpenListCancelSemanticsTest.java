package ani.rss.download;

import ani.rss.download.OpenList.CancelAction;
import ani.rss.download.OpenList.Outcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 取消 / 让路的分流测试。
 * <p>
 * 锁死线上事故的根因：手动刷新插队（让路）曾按"用户取消"处理，
 * 结果删掉了别的订阅在途、甚至已经下完的远端离线任务。
 */
class OpenListCancelSemanticsTest {

    /**
     * 没有取消信号：一切照常
     */
    @Test
    void noSignalProceeds() {
        assertEquals(CancelAction.PROCEED, OpenList.resolveCancelAction(false, false, false, Outcome.UNCERTAIN));
        assertEquals(CancelAction.PROCEED, OpenList.resolveCancelAction(false, false, true, Outcome.FAILED));
    }

    /**
     * 产物已就位：任何取消都不能把它判成"未完成"
     */
    @Test
    void successAlwaysProceeds() {
        assertEquals(CancelAction.PROCEED, OpenList.resolveCancelAction(true, true, false, Outcome.SUCCESS));
        assertEquals(CancelAction.PROCEED, OpenList.resolveCancelAction(false, true, true, Outcome.SUCCESS));
    }

    /**
     * 只是让路：保留 pending 与远端任务，下一轮继续
     */
    @Test
    void yieldHoldsInsteadOfAborting() {
        assertEquals(CancelAction.HOLD, OpenList.resolveCancelAction(false, true, true, Outcome.UNCERTAIN));
        assertEquals(CancelAction.HOLD, OpenList.resolveCancelAction(false, true, true, Outcome.FAILED));
    }

    /**
     * 真正的用户取消：清 pending
     */
    @Test
    void userCancelAborts() {
        assertEquals(CancelAction.ABORT, OpenList.resolveCancelAction(false, true, false, Outcome.UNCERTAIN));
        assertEquals(CancelAction.ABORT, OpenList.resolveCancelAction(false, true, false, Outcome.FAILED));
    }

    /**
     * 该 hash 被显式点名取消（用户取消 / 清理远端任务）时，让路标记不再享有豁免
     */
    @Test
    void explicitCancelOverridesYield() {
        assertEquals(CancelAction.ABORT, OpenList.resolveCancelAction(true, true, true, Outcome.UNCERTAIN));
        assertEquals(CancelAction.ABORT, OpenList.resolveCancelAction(true, false, false, Outcome.FAILED));
    }
}
