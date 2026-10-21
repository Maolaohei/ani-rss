<template>
  <div class="task-manager-panel">
    <div class="job-body">
      <JobStatusPanel
          :status="status"
          :status-error="statusError"
          :stale-text="staleText"
          :loading="loading"
          :last-processed-text="lastProcessedText"
          :next-batch-countdown-text="nextBatchCountdownText"
          :unknown-reason-text="unknownReasonText"
          @refresh="refreshNow"
      />

      <!-- 订阅级失败明细：此前只有一个"失败 N"计数，用户不知道是哪几个订阅、失败在哪一步 -->
      <div v-if="failedSubscriptions.length" class="failed-subscriptions">
        <div class="residual-preview-head">
          <span class="residual-preview-title">失败的订阅 {{ failedSubscriptions.length }}</span>
          <span class="muted">按错误归因给出下一步建议</span>
        </div>
        <div v-for="(row, index) in failedSubscriptions" :key="`${row.aniId}-${index}`" class="residual-row">
          <div class="residual-row-main">
            <el-tag size="small" type="danger">{{ row.humanizedMessage || '任务异常' }}</el-tag>
            <span class="residual-name" :title="row.title">{{ row.title || row.aniId || '-' }}</span>
            <el-button size="small" bg text type="primary" @click="focusSubscription(row)">定位订阅</el-button>
          </div>
          <div class="residual-row-meta">
            <span v-if="row.suggestion">{{ row.suggestion }}</span>
            <span v-if="row.rawMessage" class="mono" :title="row.rawMessage">{{ shortRaw(row.rawMessage) }}</span>
            <span>{{ formatTime(row.at) }}</span>
          </div>
        </div>
      </div>

      <div class="job-divider"></div>

      <RssTaskTable
          v-if="taskList.length"
          :items="taskList"
          :canceling-id="cancelingId"
          :status-tag-type="statusTagType"
          :status-text="statusText"
          :kind-text="kindText"
          :source-text="sourceText"
          :scope-text="scopeText"
          @cancel="cancelItem"
      />
      <el-empty v-else description="当前没有可观察任务" :image-size="80"/>

      <FailedQueuePanel
          :visible="Number(status.failedQueueCount || 0) > 0"
          :count="Number(status.failedQueueCount || 0)"
          :items="failedItems"
          :loading="failedLoading"
          :clearing="failedClearing"
          :acting-id="failedActingId"
          @load="loadFailedQueue"
          @clear="clearFailedQueue"
          @retry="retryFailed"
          @remove="removeFailed"
      />

      <ResidualPanel
          :visible="residualPreview.length > 0"
          title="离线残留预览"
          :summary="residualCountText"
          :items="residualPreview"
          :tag-type="residualKindTag"
          :kind-text="residualKindText"
          :secondary-text="row => row.state || '-'"
          :default-action="residualDefaultAction"
      />

      <ResidualPanel
          :visible="tempDirPreview.length > 0"
          title="临时目录残留"
          :summary="tempDirCountText"
          :items="tempDirPreview"
          :tag-type="tempDirKindTag"
          :kind-text="row => row.state || 'TEMP'"
          :secondary-text="() => ''"
      />

      <el-alert
          type="info"
          :closable="false"
          show-icon
          title="可同时看到：运行中 RSS、待执行手动刷新、OpenList 当前离线占用、离线/临时目录残留、失败队列、上一轮已处理记录。临时目录仅清理 FORCE/JUNK，保护活动下载。"
          style="margin-top: 12px;"
      />
      <el-alert
          v-if="status.residualSupported"
          type="warning"
          :closable="false"
          show-icon
          title="清理离线残留会取消进行中离线任务并删除记录；已成功任务仅删记录。当前正在等待的 hash 会受保护。启动回扫只清终态。"
          style="margin-top: 12px;"
      />
    </div>
    <div class="job-footer">
      <div class="job-footer-group">
        <el-button bg text @click="refreshNow" :loading="loading">刷新状态</el-button>
        <el-button
            type="success"
            bg
            text
            :loading="rechecking"
            @click="recheckDownloaded"
        >
          强制识别已下载
        </el-button>
      </div>
      <el-divider v-if="status.residualSupported" direction="vertical"/>
      <div v-if="status.residualSupported" class="job-footer-group">
        <el-button
            type="warning"
            bg
            text
            :disabled="status.residualCleaning"
            :loading="cleaning"
            @click="cleanResidual"
        >
          清理离线残留
        </el-button>
        <el-button
            type="warning"
            bg
            text
            :disabled="status.tempDirResidualCleaning"
            :loading="cleaningTemp"
            @click="cleanTempDir"
        >
          清理临时目录
        </el-button>
        <el-button
            bg
            text
            :loading="repairingLegacy"
            @click="repairLegacy"
        >
          遗留问题修复
        </el-button>
      </div>
      <div class="job-footer-spacer"></div>
      <div class="job-footer-group">
        <el-button
            type="danger"
            bg
            text
            :disabled="(!status.canCancel && !taskList.some(t => t.cancellable)) || status.cancelRequested"
            :loading="cancelingAll"
            @click="cancelAll"
        >
          全部取消
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import {computed} from "vue";
import {useRouter} from "vue-router";
import {useRssJobStatus} from "./task-manager/useRssJobStatus.js";
import {formatDuration, formatTime} from "./task-manager/format.js";
import FailedQueuePanel from "./task-manager/FailedQueuePanel.vue";
import ResidualPanel from "./task-manager/ResidualPanel.vue";
import RssTaskTable from "./task-manager/RssTaskTable.vue";
import JobStatusPanel from "./task-manager/JobStatusPanel.vue";
import {useRssJobActions} from "./task-manager/useRssJobActions.js";

/**
 * 任务管理器面板（原独立弹窗，现为任务中心「追番流水线」Tab 的内容）。
 * 生命周期随挂载启停：挂载即轮询，卸载即停止，无需外部开关。
 */
const router = useRouter()

const {
  status,
  statusError,
  loading,
  staleText,
  fetchStatus,
  applyResponseStatus,
  nextRequestSeq,
  refreshNow,
  beginAction,
  endAction
} = useRssJobStatus(() => loadFailedQueue())

const residualPreview = computed(() => {
  const items = status.value.residualItems
  return Array.isArray(items) ? items : []
})

const tempDirPreview = computed(() => {
  const items = status.value.tempDirResidualItems
  return Array.isArray(items) ? items : []
})

const {
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
} = useRssJobActions({
  status,
  applyResponseStatus,
  nextRequestSeq,
  beginAction,
  endAction,
  fetchStatus,
  residualPreview
})

/** 本轮订阅级失败明细（后端 RssJobStatus.failedSubscriptions） */
const failedSubscriptions = computed(() => {
  const items = status.value.failedSubscriptions
  return Array.isArray(items) ? items : []
})

/** 原始错误只在 title 里保留全文，列表内截断显示 */
const shortRaw = raw => {
  const text = String(raw || '')
  return text.length > 60 ? `${text.slice(0, 60)}…` : text
}

/** 定位订阅：跨页跳回订阅列表并高亮对应卡片（列表页消费 route.query.focusAni） */
const focusSubscription = row => {
  if (!row?.aniId) {
    return
  }
  router.push({path: '/subscriptions', query: {focusAni: row.aniId}})
}

const residualCountText = computed(() => {
  const total = Number(status.value.residualTotalCount || 0)
  const active = Number(status.value.residualActiveCount || 0)
  const terminal = Number(status.value.residualTerminalCount || 0)
  const count = total > 0 ? total : (active + terminal)
  return `进行中 ${active} / 终态 ${terminal} / 合计 ${count}`
})

const tempDirCountText = computed(() => {
  const total = Number(status.value.tempDirResidualTotalCount || 0)
  const cleanable = Number(status.value.tempDirResidualCleanableCount || 0)
  const protect = Number(status.value.tempDirResidualProtectedCount || 0)
  const keep = Number(status.value.tempDirResidualKeepCount || 0)
  return `可清理 ${cleanable} / 保护 ${protect} / 保留 ${keep} / 合计 ${total}`
})

const residualKindText = (row) => {
  if (!row) return '-'
  if (row.kind === 'ACTIVE') return '进行中'
  if (row.kind === 'TERMINAL') return '终态'
  if (row.kind === 'TEMP_DIR') return '临时目录'
  return row.kind || '-'
}

const residualKindTag = (row) => {
  if (row?.kind === 'ACTIVE') return 'warning'
  if (row?.protectedCurrent) return 'success'
  return 'info'
}

const tempDirKindTag = (row) => {
  if (row?.state === 'FORCE_CLEAN' || row?.state === 'JUNK_CLEAN') return 'warning'
  if (row?.state === 'PROTECT_ACTIVE' || row?.protectedCurrent) return 'success'
  return 'info'
}

const residualDefaultAction = (row) => {
  if (row?.protectedCurrent) return '保护中（清理时跳过）'
  if (row?.kind === 'ACTIVE') return '取消并删除记录'
  return '删除记录'
}

const taskList = computed(() => {
  if (Array.isArray(status.value.tasks) && status.value.tasks.length) {
    return status.value.tasks
  }
  // 兼容旧后端：从扁平字段拼列表
  const list = []
  if (status.value.running) {
    list.push({
      id: 'rss-running',
      kind: 'rss_running',
      status: status.value.cancelRequested ? 'canceling' : 'running',
      title: status.value.title || scopeText(status.value.scope),
      message: status.value.message,
      source: status.value.source,
      scope: status.value.scope,
      hash: null,
      startedAt: status.value.startedAt,
      elapsedMs: status.value.elapsedMs,
      processedAt: null,
      durationMs: null,
      cancellable: true
    })
  }
  if (status.value.pending) {
    list.push({
      id: 'rss-pending',
      kind: 'rss_pending',
      status: 'pending',
      title: status.value.pendingTitle || '待执行手动刷新',
      message: '等待当前任务结束后执行',
      source: 'manual',
      scope: status.value.pendingScope,
      hash: null,
      cancellable: true
    })
  }
  if (status.value.openListBusy || status.value.currentHash) {
    list.push({
      id: 'openlist-current',
      kind: 'openlist_current',
      status: 'busy',
      title: 'OpenList 离线任务',
      message: status.value.running ? '当前 RSS 关联的离线下载进行中' : 'OpenList 离线处理中',
      source: 'openlist',
      scope: 'offline',
      hash: status.value.currentHash,
      cancellable: true
    })
  }
  const residualTotal = Number(status.value.residualTotalCount || 0)
  const residualActive = Number(status.value.residualActiveCount || 0)
  const residualTerminal = Number(status.value.residualTerminalCount || 0)
  const residualCount = residualTotal > 0 ? residualTotal : (residualActive + residualTerminal)
  const residualMsg = status.value.residualMessage || ''
  const hasResidualError = !!residualMsg
      && !residualMsg.includes('无离线残留')
      && residualCount === 0
      && !status.value.residualCleaning
  if (status.value.residualSupported && (residualCount > 0 || status.value.residualCleaning || hasResidualError)) {
    list.push({
      id: 'residual-summary',
      kind: 'residual',
      status: (status.value.residualCleaning || residualActive > 0) ? 'busy' : 'idle',
      title: 'OpenList 离线残留',
      message: residualMsg || `进行中 ${residualActive} / 终态 ${residualTerminal}`,
      source: 'residual',
      scope: 'residual',
      processedAt: status.value.residualScannedAt,
      cancellable: false
    })
  }
  if (status.value.lastFinishedAt) {
    list.push({
      id: 'last-finished',
      kind: 'last_finished',
      status: 'done',
      title: status.value.lastTitle || '上一轮任务',
      message: status.value.lastResultMessage || '已完成',
      source: status.value.lastSource,
      scope: status.value.lastScope,
      processedAt: status.value.lastFinishedAt,
      durationMs: status.value.lastDurationMs,
      cancellable: false
    })
  }
  return list
})

const lastProcessedText = computed(() => {
  if (status.value.lastFinishedAt) {
    const t = formatTime(status.value.lastFinishedAt)
    const d = status.value.lastDurationMs != null ? `（耗时 ${formatDuration(status.value.lastDurationMs, true)}）` : ''
    const msg = status.value.lastResultMessage ? ` · ${status.value.lastResultMessage}` : ''
    return `${t}${d}${msg}`
  }
  return '-'
})

// 错峰更新的批间等待倒计时。轮询本身 2s 一次，此处按当前时刻重算即可；
// 已经过期的 nextBatchAt（提交瞬间的竞态）不显示，避免出现 "0s 后下一批"。
const nextBatchCountdownText = computed(() => {
  const at = status.value.nextBatchAt
  if (!at) return ''
  const remain = at - Date.now()
  if (remain <= 0) return ''
  return `${Math.max(1, Math.ceil(remain / 1000))}s 后下一批`
})

// 存疑成因：全部为 0 时不显示这一行（"本地状态"那行已经说了总数）
const unknownReasonText = computed(() => {
  const r = status.value.unknownReasons
  if (!r) return ''
  return [r.cooldown, r.verifyFailed, r.budgetExhausted, r.indexIncomplete, r.downloading, r.absenceUnconfirmed]
    .some((n) => n > 0) ? 'has' : ''
})

const scopeText = (scope) => {
  if (scope === 'all') return '全部订阅'
  if (scope === 'single') return '单个订阅'
  if (scope === 'partial') return '部分订阅'
  if (scope === 'starting') return '启动中'
  if (scope === 'offline') return '离线'
  if (scope === 'residual') return '残留'
  return scope || '-'
}

const sourceText = (s) => {
  if (s === 'manual') return '手动刷新'
  if (s === 'periodic') return '周期扫描'
  if (s === 'openlist') return 'OpenList'
  if (s === 'residual') return '残留'
  return s || '-'
}

const kindText = (kind) => {
  if (kind === 'rss_running') return 'RSS 运行中'
  if (kind === 'rss_pending') return 'RSS 待执行'
  if (kind === 'openlist_current') return 'OpenList 当前'
  if (kind === 'residual') return '残留摘要'
  if (kind === 'tempdir_residual') return '临时目录残留'
  if (kind === 'last_finished') return '上一轮已处理'
  return kind || '-'
}

const statusText = (st) => {
  if (st === 'running') return '运行中'
  if (st === 'pending') return '待执行'
  if (st === 'busy') return '处理中'
  if (st === 'canceling') return '取消中'
  if (st === 'done') return '已完成'
  if (st === 'idle') return '空闲'
  return st || '-'
}

const statusTagType = (st) => {
  if (st === 'running' || st === 'busy') return 'warning'
  if (st === 'pending') return 'success'
  if (st === 'canceling') return 'danger'
  if (st === 'done') return 'info'
  return 'info'
}

</script>

<style scoped src="./task-manager/task-manager.css"></style>
