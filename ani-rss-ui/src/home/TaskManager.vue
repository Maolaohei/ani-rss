<template>
  <el-dialog v-model="dialogVisible" center title="任务管理器" width="760px">
    <div class="job-body">
      <div class="job-summary">
        <div class="job-row">
          <span class="job-label">总览</span>
          <el-tag :type="status.running ? 'warning' : 'info'">
            {{ status.running ? '运行中' : '空闲' }}
          </el-tag>
          <el-tag v-if="status.cancelRequested" type="danger" style="margin-left: 8px;">
            取消中
          </el-tag>
          <el-tag v-if="status.pending" type="success" style="margin-left: 8px;">
            有待执行
          </el-tag>
          <el-tag v-if="status.openListBusy" type="warning" style="margin-left: 8px;">
            OpenList 处理中
          </el-tag>
        </div>
        <div class="job-row">
          <span class="job-label">消息</span>
          <span class="job-value">{{ status.message || '-' }}</span>
        </div>
        <div v-if="status.running && status.subscriptionTotal > 0" class="job-row">
          <span class="job-label">订阅进度</span>
          <span class="job-value progress-summary">
            已处理 {{ status.subscriptionCompleted }}/{{ status.subscriptionTotal }}
            <span>运行中 {{ status.subscriptionActive }}</span>
            <span v-if="status.subscriptionFailed > 0" class="progress-failed">失败 {{ status.subscriptionFailed }}</span>
          </span>
        </div>
        <div class="job-row">
          <span class="job-label">已处理时间</span>
          <span>{{ lastProcessedText }}</span>
        </div>
      </div>

      <div class="job-divider"></div>

      <div v-if="taskList.length" class="task-list">
        <div v-for="item in taskList" :key="item.id" class="task-card">
          <div class="task-head">
            <div class="task-title-wrap">
              <el-tag size="small" :type="statusTagType(item.status)">{{ statusText(item.status) }}</el-tag>
              <span class="task-title">{{ item.title || kindText(item.kind) }}</span>
            </div>
            <el-button
                type="danger"
                bg
                text
                size="small"
                :disabled="!item.cancellable || item.status === 'canceling'"
                :loading="cancelingId === item.id"
                @click="cancelItem(item)"
            >
              取消
            </el-button>
          </div>
          <div class="task-meta">
            <span>类型：{{ kindText(item.kind) }}</span>
            <span>来源：{{ sourceText(item.source) }}</span>
            <span>范围：{{ scopeText(item.scope) }}</span>
          </div>
          <div class="task-message">{{ item.message || '-' }}</div>
          <div v-if="item.progress != null" class="task-progress">
            <el-progress :percentage="Number(item.progress) || 0" :stroke-width="10"/>
            <span v-if="item.etaMs != null" class="muted eta">ETA {{ formatDuration(item.etaMs, true) }}</span>
          </div>
          <div class="task-grid">
            <div><span class="muted">已运行</span> {{ formatDuration(item.elapsedMs, item.status === 'running' || item.status === 'busy' || item.status === 'canceling') }}</div>
            <div><span class="muted">已处理时间</span> {{ formatTime(item.processedAt) }}</div>
            <div v-if="item.durationMs != null"><span class="muted">耗时</span> {{ formatDuration(item.durationMs, true) }}</div>
            <div v-if="item.hash" class="mono"><span class="muted">Hash</span> {{ item.hash }}</div>
          </div>
        </div>
      </div>
      <el-empty v-else description="当前没有可观察任务" :image-size="80"/>

      <div v-if="Number(status.failedQueueCount || 0) > 0" class="failed-queue">
        <div class="residual-preview-head">
          <span class="residual-preview-title">失败队列 {{ status.failedQueueCount }}</span>
          <div class="failed-queue-actions">
            <el-button size="small" bg text :loading="failedLoading" @click="loadFailedQueue">刷新</el-button>
            <el-button size="small" bg text type="danger" :loading="failedClearing" @click="clearFailedQueue">清空</el-button>
          </div>
        </div>
        <div v-if="!failedItems.length" class="muted">点击刷新加载明细</div>
        <div v-for="row in failedItems" :key="row.id" class="residual-row">
          <div class="residual-row-main">
            <el-tag size="small" type="danger">{{ row.errorCode || 'FAIL' }}</el-tag>
            <span class="residual-name" :title="row.reName || row.title">{{ row.reName || row.title || row.id }}</span>
            <el-button size="small" bg text type="primary" :loading="failedActingId === row.id" @click="retryFailed(row)">重试</el-button>
            <el-button size="small" bg text @click="removeFailed(row)">移除</el-button>
          </div>
          <div class="residual-row-meta">
            <span>{{ row.message || '-' }}</span>
            <span v-if="row.suggestion">{{ row.suggestion }}</span>
            <span v-if="row.attempts">×{{ row.attempts }}</span>
            <span>{{ formatTime(row.failedAt) }}</span>
          </div>
        </div>
      </div>

      <div v-if="residualPreview.length" class="residual-preview">
        <div class="residual-preview-head">
          <span class="residual-preview-title">离线残留预览</span>
          <span class="muted">共 {{ residualCountText }}，展示 {{ residualPreview.length }} 条</span>
        </div>
        <div v-for="row in residualPreview" :key="row.id || row.name" class="residual-row">
          <div class="residual-row-main">
            <el-tag size="small" :type="residualKindTag(row)">{{ residualKindText(row) }}</el-tag>
            <el-tag v-if="row.protectedCurrent" size="small" type="success" style="margin-left: 6px;">保护中</el-tag>
            <span class="residual-name" :title="row.name">{{ row.name || row.id || '-' }}</span>
          </div>
          <div class="residual-row-meta">
            <span>{{ row.state || '-' }}</span>
            <span v-if="row.progress != null">进度 {{ row.progress }}%</span>
            <span class="residual-action">{{ row.action || residualDefaultAction(row) }}</span>
          </div>
          <div v-if="row.error" class="residual-error">{{ row.error }}</div>
        </div>
      </div>

      <div v-if="tempDirPreview.length" class="residual-preview">
        <div class="residual-preview-head">
          <span class="residual-preview-title">临时目录残留</span>
          <span class="muted">共 {{ tempDirCountText }}，展示 {{ tempDirPreview.length }} 条</span>
        </div>
        <div v-for="row in tempDirPreview" :key="row.id || row.name" class="residual-row">
          <div class="residual-row-main">
            <el-tag size="small" :type="tempDirKindTag(row)">{{ row.state || 'TEMP' }}</el-tag>
            <el-tag v-if="row.protectedCurrent" size="small" type="success" style="margin-left: 6px;">保护中</el-tag>
            <span class="residual-name" :title="row.name">{{ row.name || row.id || '-' }}</span>
          </div>
          <div class="residual-row-meta">
            <span class="residual-action">{{ row.action || '-' }}</span>
          </div>
          <div v-if="row.error" class="residual-error">{{ row.error }}</div>
        </div>
      </div>

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
    <template #footer>
      <div class="job-footer">
        <el-button bg text @click="refreshNow" :loading="loading">刷新</el-button>
        <el-button
            v-if="status.residualSupported"
            bg
            text
            :loading="scanning"
            @click="scanResidual"
        >
          扫描离线残留
        </el-button>
        <el-button
            v-if="status.residualSupported"
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
            v-if="status.residualSupported"
            bg
            text
            :loading="scanningTemp"
            @click="scanTempDir"
        >
          扫描临时目录
        </el-button>
        <el-button
            v-if="status.residualSupported"
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
    </template>
  </el-dialog>
</template>

<script setup>
import {computed, ref} from "vue";
import {ElMessage, ElMessageBox} from "element-plus";
import * as http from "@/js/http.js";

const dialogVisible = ref(false)
let pollToken = 0
let requestSeq = 0
let appliedSeq = 0
let actionInFlight = 0
const loading = ref(false)
const cancelingAll = ref(false)
const cancelingId = ref('')
const scanning = ref(false)
const cleaning = ref(false)
const scanningTemp = ref(false)
const cleaningTemp = ref(false)
const failedLoading = ref(false)
const failedClearing = ref(false)
const failedActingId = ref('')
const failedItems = ref([])
const status = ref(emptyStatus())

function emptyStatus() {
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
    tasks: []
  }
}

const residualPreview = computed(() => {
  const items = status.value.residualItems
  return Array.isArray(items) ? items : []
})

const tempDirPreview = computed(() => {
  const items = status.value.tempDirResidualItems
  return Array.isArray(items) ? items : []
})

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

const formatDuration = (ms, force = false) => {
  const n = Number(ms || 0)
  if (!force && n <= 0) return '-'
  if (!n && n !== 0) return '-'
  const totalSec = Math.floor(Math.max(0, n) / 1000)
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  if (h > 0) return `${h}小时${m}分${s}秒`
  if (m > 0) return `${m}分${s}秒`
  return `${s}秒`
}

const formatTime = (ts) => {
  const n = Number(ts || 0)
  if (!n) return '-'
  try {
    return new Date(n).toLocaleString()
  } catch (_) {
    return String(n)
  }
}

const applyStatus = (data) => {
  status.value = {
    ...emptyStatus(),
    ...(data || {})
  }
  // 兼容旧字段：没有 canCancel 时前端推断
  if (status.value.canCancel == null) {
    status.value.canCancel = !!(status.value.running || status.value.pending || status.value.openListBusy || status.value.currentHash)
  }
}

const nextRequestSeq = () => ++requestSeq

const applyResponseStatus = (seq, data) => {
  if (!data || seq < appliedSeq) return
  appliedSeq = seq
  applyStatus(data)
}

const show = () => {
  dialogVisible.value = true
  pollStatus()
  if (Number(status.value.failedQueueCount || 0) > 0 || failedItems.value.length) {
    loadFailedQueue()
  }
}

const refreshNow = async () => {
  loading.value = true
  try {
    await fetchStatus()
  } finally {
    loading.value = false
  }
}

const fetchStatus = async () => {
  if (actionInFlight > 0) return
  const seq = nextRequestSeq()
  try {
    const res = await http.rssJobStatus()
    applyResponseStatus(seq, res?.data)
  } catch (_) {
  }
}

const pollStatus = async () => {
  const token = ++pollToken
  while (dialogVisible.value && token === pollToken) {
    await fetchStatus()
    if (!dialogVisible.value || token !== pollToken) break
    const busy = status.value.running
        || status.value.pending
        || status.value.openListBusy
        || status.value.residualCleaning
        || status.value.tempDirResidualCleaning
        || status.value.cancelRequested
        // residual 终态/空闲不触发高频轮询；pending 仅指 RSS 排队
        || taskList.value.some(t => {
          if (t.kind === 'residual' || t.kind === 'tempdir_residual' || t.kind === 'last_finished') return false
          return t.status === 'running' || t.status === 'busy' || t.status === 'canceling' || t.status === 'pending'
        })
    await sleep(busy ? 2000 : 4000)
  }
}

const cancelAll = async () => {
  const seq = nextRequestSeq()
  actionInFlight++
  cancelingAll.value = true
  try {
    const res = await http.rssJobCancel()
    const msg = res.message || '已请求取消'
    if (msg.includes('没有') || msg.includes('不可取消') || msg.includes('不存在')) {
      ElMessage.warning(msg)
    } else {
      ElMessage.success(msg)
    }
    applyResponseStatus(seq, res?.data)
  } catch (_) {
  } finally {
    actionInFlight--
    cancelingAll.value = false
  }
}

const cancelItem = async (item) => {
  if (!item?.id || !item.cancellable) return
  const seq = nextRequestSeq()
  actionInFlight++
  cancelingId.value = item.id
  try {
    const res = await http.rssJobCancelItem(item.id)
    const msg = res.message || '已请求取消'
    // 后端无任务时仍 200，用文案判断
    if (msg.includes('没有') || msg.includes('不可取消') || msg.includes('不存在')) {
      ElMessage.warning(msg)
    } else {
      ElMessage.success(msg)
    }
    applyResponseStatus(seq, res?.data)
  } catch (_) {
  } finally {
    actionInFlight--
    cancelingId.value = ''
  }
}

const scanResidual = async () => {
  const seq = nextRequestSeq()
  actionInFlight++
  scanning.value = true
  try {
    const res = await http.rssJobResidualScan()
    ElMessage.success(res.message || '扫描完成')
    applyResponseStatus(seq, res?.data)
  } catch (_) {
  } finally {
    actionInFlight--
    scanning.value = false
  }
}

const cleanResidual = async () => {
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
  actionInFlight++
  cleaning.value = true
  try {
    const res = await http.rssJobResidualClean()
    ElMessage.success(res.message || '清理完成')
    applyResponseStatus(seq, res?.data)
  } catch (_) {
  } finally {
    actionInFlight--
    cleaning.value = false
  }
}

const scanTempDir = async () => {
  const seq = nextRequestSeq()
  actionInFlight++
  scanningTemp.value = true
  try {
    const res = await http.rssJobTempDirResidualScan()
    ElMessage.success(res.message || '临时目录扫描完成')
    applyResponseStatus(seq, res?.data)
  } catch (_) {
  } finally {
    actionInFlight--
    scanningTemp.value = false
  }
}

const cleanTempDir = async () => {
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
  actionInFlight++
  cleaningTemp.value = true
  try {
    const res = await http.rssJobTempDirResidualClean()
    ElMessage.success(res.message || '临时目录清理完成')
    applyResponseStatus(seq, res?.data)
  } catch (_) {
  } finally {
    actionInFlight--
    cleaningTemp.value = false
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

const removeFailed = async (row) => {
  if (!row?.id) return
  try {
    const res = await http.failedDownloadQueueRemove(row.id)
    ElMessage.success(res.message || '已移除')
    failedItems.value = failedItems.value.filter(i => i.id !== row.id)
    await fetchStatus()
  } catch (_) {
  }
}

const retryFailed = async (row) => {
  if (!row?.id) return
  failedActingId.value = row.id
  try {
    const res = await http.failedDownloadQueueRetry(row.id)
    ElMessage.success(res.message || '已提交精确重下')
    // 后台成功后才会从队列移除；失败仍保留，刷新列表即可
    await fetchStatus()
  } catch (_) {
  } finally {
    failedActingId.value = ''
  }
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

defineExpose({show})
</script>

<style scoped>
.job-body {
  min-height: 260px;
}

.job-summary {
  margin-bottom: 4px;
}

.job-row {
  display: flex;
  align-items: flex-start;
  margin-bottom: 10px;
  line-height: 1.5;
}

.job-label {
  width: 88px;
  flex-shrink: 0;
  color: var(--el-text-color-secondary);
}

.job-value {
  flex: 1;
  word-break: break-all;
}

.progress-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
}

.progress-failed {
  color: var(--el-color-danger);
}

.job-divider {
  height: 1px;
  background: var(--el-border-color-lighter);
  margin: 8px 0 12px;
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.task-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px;
  background: var(--el-fill-color-blank);
}

.task-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.task-title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.task-title {
  font-weight: 600;
  word-break: break-all;
}

.task-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-bottom: 6px;
}

.task-message {
  margin-bottom: 8px;
  word-break: break-all;
}

.task-progress {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.task-progress .el-progress {
  flex: 1;
}

.eta {
  white-space: nowrap;
  font-size: 12px;
}

.failed-queue {
  margin-top: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--el-fill-color-blank);
  max-height: 240px;
  overflow: auto;
}

.failed-queue-actions {
  display: flex;
  gap: 6px;
}

.task-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 12px;
  font-size: 13px;
}

.muted {
  color: var(--el-text-color-secondary);
  margin-right: 6px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
  font-size: 12px;
  word-break: break-all;
}

.job-footer {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}

.residual-preview {
  margin-top: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--el-fill-color-light);
  max-height: 280px;
  overflow: auto;
}

.residual-preview-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}

.residual-preview-title {
  font-weight: 600;
}

.residual-row {
  padding: 8px 0;
  border-top: 1px dashed var(--el-border-color-lighter);
}

.residual-row:first-of-type {
  border-top: none;
}

.residual-row-main {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.residual-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.residual-row-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.residual-action {
  color: var(--el-color-warning);
}

.residual-error {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-color-danger);
  word-break: break-all;
}

@media (max-width: 640px) {
  .task-grid {
    grid-template-columns: 1fr;
  }
}
</style>
