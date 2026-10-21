package ani.rss.task;

import ani.rss.entity.vo.RssJobItem;
import ani.rss.entity.vo.RssJobStatus;
import ani.rss.entity.vo.ResidualPreviewItem;

import java.util.List;
import java.util.Map;

/**
 * 构造任务管理器状态响应的输入快照。
 */
public record RssJobStatusProjection(
        boolean running,
        boolean canceling,
        long startedAt,
        String scope,
        String title,
        String aniId,
        String message,
        Object pending,
        String source,
        int total,
        int active,
        int completed,
        int failed,
        int currentBatch,
        int totalBatch,
        long nextBatchAt,
        boolean quiescentForced,
        int quiescentSkipped,
        long finishedAt,
        long lastDuration,
        String lastMsg,
        String lastJobTitle,
        String lastJobSource,
        String lastJobScope,
        long elapsed,
        String currentHash,
        String offlineTitle,
        Integer offlineProgress,
        String offlineState,
        Long offlineDeadlineMs,
        Long offlineEtaMs,
        boolean openListBusy,
        int failedQueueCount,
        boolean residualSupported,
        Integer residualActive,
        Integer residualTerminal,
        Integer residualTotal,
        Long residualScannedAt,
        Boolean residualCleaning,
        String residualMessage,
        List<String> residualSamples,
        Integer tempDirTotal,
        Integer tempDirCleanable,
        Boolean tempDirCleaning,
        String tempDirMessage,
        List<RssJobItem> tasks,
        Map<String, Integer> roundLocalState,
        Map<String, Integer> unknownReasons,
        List<RssJobStatus.FailedSubscription> failedSubscriptions,
        List<ResidualPreviewItem> residualItems,
        List<ResidualPreviewItem> tempDirItems,
        Integer tempDirProtected,
        Integer tempDirKeep,
        Long tempDirScannedAt
) {
    public RssJobStatus toStatus() {
        boolean canCancel = tasks != null && tasks.stream()
                .anyMatch(item -> Boolean.TRUE.equals(item.getCancellable()));
        return new RssJobStatus()
                .setRunning(running)
                .setCancelRequested(canceling)
                .setCanCancel(canCancel)
                .setScope(scope)
                .setTitle(title)
                .setAniId(aniId)
                .setStartedAt(startedAt > 0 ? startedAt : null)
                .setElapsedMs(elapsed)
                .setSubscriptionTotal(total)
                .setSubscriptionActive(active)
                .setSubscriptionCompleted(completed)
                .setSubscriptionFailed(failed)
                .setCurrentBatch(totalBatch > 1 ? currentBatch : null)
                .setTotalBatch(totalBatch > 0 ? totalBatch : null)
                .setNextBatchAt(nextBatchAt > 0L ? nextBatchAt : null)
                .setQuiescentForced(running ? quiescentForced : null)
                .setQuiescentSkipped(running && quiescentSkipped > 0 ? quiescentSkipped : null)
                .setRoundLocalState(roundLocalState)
                .setUnknownReasons(unknownReasons)
                .setFailedSubscriptions(failedSubscriptions)
                .setLastFinishedAt(finishedAt > 0 ? finishedAt : null)
                .setLastDurationMs(finishedAt > 0 ? lastDuration : null)
                .setLastResultMessage(lastMsg)
                .setLastTitle(lastJobTitle)
                .setLastSource(lastJobSource)
                .setLastScope(lastJobScope)
                .setMessage(message)
                .setCurrentHash(currentHash)
                .setOfflineTitle(offlineTitle)
                .setOfflineProgress(offlineProgress)
                .setOfflineState(offlineState)
                .setOfflineDeadlineMs(offlineDeadlineMs)
                .setOfflineEtaMs(offlineEtaMs)
                .setFailedQueueCount(failedQueueCount)
                .setOpenListBusy(openListBusy)
                .setSource(source)
                .setPending(pending != null)
                .setPendingTitle(pending == null ? null : pendingTitle())
                .setPendingScope(pending == null ? null : pendingScope())
                .setResidualSupported(residualSupported)
                .setResidualActiveCount(residualActive)
                .setResidualTerminalCount(residualTerminal)
                .setResidualTotalCount(residualTotal)
                .setResidualScannedAt(residualScannedAt)
                .setResidualCleaning(residualCleaning)
                .setResidualMessage(residualMessage)
                .setResidualSamples(residualSamples)
                .setResidualItems(residualItems)
                .setTempDirResidualTotalCount(tempDirTotal)
                .setTempDirResidualCleanableCount(tempDirCleanable)
                .setTempDirResidualProtectedCount(tempDirProtected)
                .setTempDirResidualKeepCount(tempDirKeep)
                .setTempDirResidualScannedAt(tempDirScannedAt)
                .setTempDirResidualCleaning(tempDirCleaning)
                .setTempDirResidualMessage(tempDirMessage)
                .setTempDirResidualItems(tempDirItems)
                .setTasks(tasks);
    }

    private String pendingTitle() {
        if (pending instanceof RssTask.PendingManual manual) {
            return manual.title;
        }
        return null;
    }

    private String pendingScope() {
        if (pending instanceof RssTask.PendingManual manual) {
            return manual.scope;
        }
        return null;
    }
}
