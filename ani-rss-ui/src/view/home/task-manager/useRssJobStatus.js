import {computed, onActivated, onDeactivated, onMounted, onUnmounted, ref} from "vue";
import {useIntervalFn} from "@vueuse/core";
import * as http from "@/js/http.js";

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

export function emptyRssJobStatus() {
  return {
    running: false,
    cancelRequested: false,
    canCancel: false,
    scope: 'idle',
    title: '',
    aniId: '',
    startedAt: null,
    elapsedMs: 0,
    subscriptionTotal: 0,
    subscriptionActive: 0,
    subscriptionCompleted: 0,
    subscriptionFailed: 0,
    currentBatch: null,
    totalBatch: null,
    nextBatchAt: null,
    quiescentForced: null,
    quiescentSkipped: null,
    roundLocalState: null,
    unknownReasons: null,
    lastFinishedAt: null,
    lastDurationMs: null,
    lastResultMessage: null,
    lastTitle: null,
    lastSource: null,
    lastScope: null,
    message: '空闲',
    currentHash: null,
    offlineTitle: null,
    offlineProgress: null,
    offlineState: null,
    offlineDeadlineMs: null,
    offlineEtaMs: null,
    failedQueueCount: 0,
    source: null,
    pending: false,
    pendingTitle: null,
    pendingScope: null,
    openListBusy: false,
    residualSupported: false,
    residualActiveCount: 0,
    residualTerminalCount: 0,
    residualTotalCount: 0,
    residualScannedAt: null,
    residualCleaning: false,
    residualMessage: null,
    residualSamples: [],
    residualItems: [],
    tempDirResidualTotalCount: 0,
    tempDirResidualCleanableCount: 0,
    tempDirResidualProtectedCount: 0,
    tempDirResidualKeepCount: 0,
    tempDirResidualScannedAt: null,
    tempDirResidualCleaning: false,
    tempDirResidualMessage: null,
    tempDirResidualItems: [],
    failedSubscriptions: [],
    tasks: []
  }
}

/**
 * 任务状态轮询与动作序列保护。
 * <p>
 * 生命周期仍由页面控制：show() 在 mounted/activated 时启动，stopPolling()
 * 在 deactivated/unmounted 时停止。
 */
export function useRssJobStatus(onFirstLoaded) {
  let disposed = false
  let pollToken = 0
  let requestSeq = 0
  let appliedSeq = 0
  let actionInFlight = 0

  const status = ref(emptyRssJobStatus())
  const statusError = ref('')
  const lastSuccessAt = ref(0)
  const consecutiveFailures = ref(0)
  const loading = ref(false)
  const tick = ref(0)

  const staleText = computed(() => {
    void tick.value
    if (!lastSuccessAt.value) {
      return '尚未成功获取过状态'
    }
    const seconds = Math.max(0, Math.round((Date.now() - lastSuccessAt.value) / 1000))
    if (seconds < 60) {
      return `以下数据来自 ${seconds} 秒前`
    }
    return `以下数据来自 ${Math.round(seconds / 60)} 分钟前，可能已过期`
  })

  useIntervalFn(() => {
    if (statusError.value) {
      tick.value++
    }
  }, 1000)

  const applyStatus = data => {
    status.value = {
      ...emptyRssJobStatus(),
      ...(data || {})
    }
    if (status.value.canCancel == null) {
      status.value.canCancel = !!(status.value.running || status.value.pending
          || status.value.openListBusy || status.value.currentHash)
    }
  }

  const nextRequestSeq = () => ++requestSeq

  const applyResponseStatus = (seq, data) => {
    if (!data || seq < appliedSeq) return
    appliedSeq = seq
    applyStatus(data)
  }

  const fetchStatus = async () => {
    if (actionInFlight > 0) return
    const seq = nextRequestSeq()
    try {
      const res = await http.rssJobStatus({silent: true})
      applyResponseStatus(seq, res?.data)
      if (statusError.value) {
        statusError.value = ''
      }
      lastSuccessAt.value = Date.now()
      consecutiveFailures.value = 0
    } catch (err) {
      consecutiveFailures.value++
      if (!statusError.value) {
        statusError.value = err?.message || '无法获取任务状态'
      }
    }
  }

  const isBusy = () => status.value.running
      || status.value.pending
      || status.value.openListBusy
      || status.value.residualCleaning
      || status.value.tempDirResidualCleaning
      || status.value.cancelRequested
      || (Array.isArray(status.value.tasks) && status.value.tasks.some(t => {
        if (t.kind === 'residual' || t.kind === 'tempdir_residual' || t.kind === 'last_finished') {
          return false
        }
        return t.status === 'running' || t.status === 'busy'
            || t.status === 'canceling' || t.status === 'pending'
      }))

  const pollStatus = async () => {
    const token = ++pollToken
    while (!disposed && token === pollToken) {
      await fetchStatus()
      if (disposed || token !== pollToken) break
      const failures = consecutiveFailures.value
      const backoff = failures > 0
          ? Math.min(30000, 2000 * Math.pow(2, Math.min(failures, 4)))
          : 0
      await sleep(Math.max(isBusy() ? 2000 : 4000, backoff))
    }
  }

  const show = ({ loadFailedQueue } = {}) => {
    disposed = false
    pollToken++
    statusError.value = ''
    pollStatus()
    fetchStatus().then(() => {
      if (Number(status.value.failedQueueCount || 0) > 0 && loadFailedQueue) {
        loadFailedQueue()
      }
    })
  }

  const stopPolling = () => {
    disposed = true
    pollToken++
  }

  const refreshNow = async () => {
    loading.value = true
    try {
      await fetchStatus()
    } finally {
      loading.value = false
    }
  }

  const beginAction = () => {
    actionInFlight++
  }

  const endAction = () => {
    actionInFlight--
  }

  onMounted(() => show())
  onActivated(() => show())
  onDeactivated(stopPolling)
  onUnmounted(stopPolling)

  return {
    status,
    statusError,
    lastSuccessAt,
    consecutiveFailures,
    loading,
    staleText,
    show,
    stopPolling,
    fetchStatus,
    applyStatus,
    applyResponseStatus,
    nextRequestSeq,
    refreshNow,
    beginAction,
    endAction
  }
}
