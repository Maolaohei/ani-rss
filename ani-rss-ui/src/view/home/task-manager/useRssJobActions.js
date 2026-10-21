import {ref} from "vue";
import {ElMessage, ElMessageBox} from "element-plus";
import * as http from "@/js/http.js";

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

/**
 * 任务管理器动作编排。
 * <p>
 * 动作只负责请求、确认框与本地 loading 状态；任务快照由调用方传入，
 * 响应状态通过 applyResponseStatus 回写，避免动作函数持有轮询生命周期。
 */
export function useRssJobActions({
  status,
  applyResponseStatus,
  nextRequestSeq,
  beginAction,
  endAction,
  fetchStatus,
  residualPreview
}) {
  const cancelingAll = ref(false)
  const rechecking = ref(false)
  const cancelingId = ref('')
  const cleaning = ref(false)
  const cleaningTemp = ref(false)
  const repairingLegacy = ref(false)
  const failedLoading = ref(false)
  const failedClearing = ref(false)
  const failedActingId = ref('')
  const failedItems = ref([])

  const hintIfUnavailable = msg => {
    if (msg.includes('没有') || msg.includes('不可取消') || msg.includes('不存在')) {
      ElMessage.warning(msg)
    } else {
      ElMessage.success(msg)
    }
  }

  const cancelAll = async () => {
    const seq = nextRequestSeq()
    beginAction()
    cancelingAll.value = true
    try {
      const res = await http.rssJobCancel()
      hintIfUnavailable(res.message || '已请求取消')
      applyResponseStatus(seq, res?.data)
    } catch (_) {
    } finally {
      endAction()
      cancelingAll.value = false
    }
  }

  const cancelItem = async item => {
    if (!item?.id || !item.cancellable) return
    const seq = nextRequestSeq()
    beginAction()
    cancelingId.value = item.id
    try {
      const res = await http.rssJobCancelItem(item.id)
      hintIfUnavailable(res.message || '已请求取消')
      applyResponseStatus(seq, res?.data)
    } catch (_) {
    } finally {
      endAction()
      cancelingId.value = ''
    }
  }

  const recheckDownloaded = async () => {
    const seq = nextRequestSeq()
    beginAction()
    rechecking.value = true
    try {
      const res = await http.rssJobRecheckDownloaded()
      ElMessage.success(res.message || '识别完成')
      applyResponseStatus(seq, res?.data)
    } catch (_) {
    } finally {
      endAction()
      rechecking.value = false
    }
  }

  const cleanResidual = async () => {
    const seq0 = nextRequestSeq()
    beginAction()
    try {
      const scanRes = await http.rssJobResidualScan()
      applyResponseStatus(seq0, scanRes?.data)
    } catch (_) {
    } finally {
      endAction()
    }
    const active = Number(status.value.residualActiveCount || 0)
    const terminal = Number(status.value.residualTerminalCount || 0)
    const previewN = residualPreview.value.length
    const detail = previewN > 0
        ? `当前扫描：进行中 ${active} / 终态 ${terminal}（预览 ${previewN} 条）。`
        : `当前扫描：进行中 ${active} / 终态 ${terminal}。`
    try {
      await ElMessageBox.confirm(
          `${detail}将取消进行中离线任务并删除记录；已成功任务仅删记录。当前 RSS 正在等待的 hash 会跳过。是否继续？`,
          '一键清理离线残留',
          {type: 'warning', confirmButtonText: '清理', cancelButtonText: '取消'}
      )
    } catch (_) {
      return
    }
    const seq = nextRequestSeq()
    beginAction()
    cleaning.value = true
    try {
      const res = await http.rssJobResidualClean()
      ElMessage.success(res.message || '清理完成')
      applyResponseStatus(seq, res?.data)
    } catch (_) {
    } finally {
      endAction()
      cleaning.value = false
    }
  }

  const cleanTempDir = async () => {
    const seq0 = nextRequestSeq()
    beginAction()
    try {
      const scanRes = await http.rssJobTempDirResidualScan()
      applyResponseStatus(seq0, scanRes?.data)
    } catch (_) {
    } finally {
      endAction()
    }
    const cleanable = Number(status.value.tempDirResidualCleanableCount || 0)
    const total = Number(status.value.tempDirResidualTotalCount || 0)
    try {
      await ElMessageBox.confirm(
          `将仅删除 FORCE/JUNK 临时目录（可清理约 ${cleanable} / 合计 ${total}）。PROTECT/KEEP 不会自动删除。是否继续？`,
          '清理临时目录残留',
          {type: 'warning', confirmButtonText: '清理', cancelButtonText: '取消'}
      )
    } catch (_) {
      return
    }
    const seq = nextRequestSeq()
    beginAction()
    cleaningTemp.value = true
    try {
      const res = await http.rssJobTempDirResidualClean()
      ElMessage.success(res.message || '临时目录清理完成')
      applyResponseStatus(seq, res?.data)
    } catch (_) {
    } finally {
      endAction()
      cleaningTemp.value = false
    }
  }

  const repairLegacy = async () => {
    try {
      await ElMessageBox.confirm(
          '将归位 OpenList/云盘中被错误嵌套的目录并清理空壳，会移动已有文件。是否继续？',
          '遗留问题修复',
          {type: 'warning', confirmButtonText: '开始修复', cancelButtonText: '取消'}
      )
    } catch (_) {
      return
    }
    const seq = nextRequestSeq()
    beginAction()
    repairingLegacy.value = true
    try {
      const res = await http.rssJobLegacyRepair()
      ElMessageBox.alert(res.message || '遗留修复完成', '遗留问题修复结果', {
        confirmButtonText: '知道了',
        customClass: 'legacy-repair-result'
      }).catch(() => {
      })
      applyResponseStatus(seq, res?.data)
    } catch (_) {
    } finally {
      endAction()
      repairingLegacy.value = false
    }
  }

  const loadFailedQueue = async () => {
    failedLoading.value = true
    try {
      const res = await http.failedDownloadQueue()
      failedItems.value = Array.isArray(res?.data) ? res.data : []
    } catch (_) {
    } finally {
      failedLoading.value = false
    }
  }

  const clearFailedQueue = async () => {
    try {
      await ElMessageBox.confirm('清空全部失败队列条目？', '清空失败队列', {type: 'warning'})
    } catch (_) {
      return
    }
    failedClearing.value = true
    try {
      const res = await http.failedDownloadQueueClear()
      ElMessage.success(res.message || '已清空')
      failedItems.value = []
      await fetchStatus()
    } catch (_) {
    } finally {
      failedClearing.value = false
    }
  }

  const removeFailed = async row => {
    if (!row?.id) return
    try {
      const res = await http.failedDownloadQueueRemove(row.id)
      ElMessage.success(res.message || '已移除')
      failedItems.value = failedItems.value.filter(i => i.id !== row.id)
      await fetchStatus()
    } catch (_) {
    }
  }

  const retryFailed = async row => {
    if (!row?.id) return
    failedActingId.value = row.id
    const beforeAttempts = row.attempts
    const beforeFailedAt = row.failedAt
    try {
      const res = await http.failedDownloadQueueRetry(row.id)
      ElMessage.info(res.message || '已提交精确重下，正在确认结果…')
      await sleep(3000)
      await loadFailedQueue()
      await fetchStatus()
      const still = failedItems.value.find(i => i.id === row.id)
      if (still && still.attempts === beforeAttempts && still.failedAt === beforeFailedAt) {
        ElMessage.error(`重下未生效：${still.message || '条目仍留在失败队列中'}${still.suggestion ? `（${still.suggestion}）` : ''}`)
      } else if (!still) {
        ElMessage.success('已从失败队列移除，说明重下已受理')
      }
    } catch (_) {
    } finally {
      failedActingId.value = ''
    }
  }

  return {
    cancelingAll,
    rechecking,
    cancelingId,
    cleaning,
    cleaningTemp,
    repairingLegacy,
    failedLoading,
    failedClearing,
    failedActingId,
    failedItems,
    cancelAll,
    cancelItem,
    recheckDownloaded,
    cleanResidual,
    cleanTempDir,
    repairLegacy,
    loadFailedQueue,
    clearFailedQueue,
    removeFailed,
    retryFailed
  }
}
