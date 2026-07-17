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
          <div class="task-grid">
            <div><span class="muted">已运行</span> {{ formatDuration(item.elapsedMs, item.status === 'running' || item.status === 'busy' || item.status === 'canceling') }}</div>
            <div><span class="muted">已处理时间</span> {{ formatTime(item.processedAt) }}</div>
            <div v-if="item.durationMs != null"><span class="muted">耗时</span> {{ formatDuration(item.durationMs, true) }}</div>
            <div v-if="item.hash" class="mono"><span class="muted">Hash</span> {{ item.hash }}</div>
          </div>
        </div>
      </div>
      <el-empty v-else description="当前没有可观察任务" :image-size="80"/>

      <el-alert
          type="info"
          :closable="false"
          show-icon
          title="可同时看到：运行中 RSS、待执行手动刷新、OpenList 当前离线占用、残留摘要、上一轮已处理记录。取消只对可取消条目生效；残留请用清理残留。"
          style="margin-top: 12px;"
      />
      <el-alert
          v-if="status.residualSupported"
          type="warning"
          :closable="false"
          show-icon
          title="清理残留会取消进行中离线任务并删除记录；已成功任务仅删记录。当前正在等待的 hash 会受保护。启动回扫只清终态。"
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
          扫描残留
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
          清理残留
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
const loading = ref(false)
const cancelingAll = ref(false)
const cancelingId = ref('')
const scanning = ref(false)
const cleaning = ref(false)
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
    lastFinishedAt: null,
    lastDurationMs: null,
    lastResultMessage: null,
    lastTitle: null,
    lastSource: null,
    lastScope: null,
    message: '空闲',
    currentHash: null,
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
    tasks: []
  }
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
  if (status.value.residualSupported) {
    list.push({
      id: 'residual-summary',
      kind: 'residual',
      status: (status.value.residualCleaning || (status.value.residualActiveCount || 0) > 0) ? 'busy' : 'idle',
      title: 'OpenList 离线残留',
      message: status.value.residualMessage || `进行中 ${status.value.residualActiveCount ?? 0} / 终态 ${status.value.residualTerminalCount ?? 0}`,
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

const show = () => {
  dialogVisible.value = true
  pollStatus()
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
  try {
    const res = await http.rssJobStatus()
    if (res?.data) {
      applyStatus(res.data)
    }
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
        || status.value.cancelRequested
        // residual 终态/空闲不触发高频轮询；pending 仅指 RSS 排队
        || taskList.value.some(t => {
          if (t.kind === 'residual' || t.kind === 'last_finished') return false
          return t.status === 'running' || t.status === 'busy' || t.status === 'canceling' || t.status === 'pending'
        })
    await sleep(busy ? 2000 : 4000)
  }
}

const cancelAll = async () => {
  cancelingAll.value = true
  try {
    const res = await http.rssJobCancel()
    ElMessage.success(res.message || '已请求取消')
    if (res?.data) applyStatus(res.data)
  } catch (e) {
    ElMessage.error(e?.message || '取消失败')
  } finally {
    cancelingAll.value = false
  }
}

const cancelItem = async (item) => {
  if (!item?.id || !item.cancellable) return
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
    if (res?.data) applyStatus(res.data)
  } catch (e) {
    ElMessage.error(e?.message || '取消失败')
  } finally {
    cancelingId.value = ''
  }
}

const scanResidual = async () => {
  scanning.value = true
  try {
    const res = await http.rssJobResidualScan()
    ElMessage.success(res.message || '扫描完成')
    if (res?.data) applyStatus(res.data)
  } catch (e) {
    ElMessage.error(e?.message || '扫描失败')
  } finally {
    scanning.value = false
  }
}

const cleanResidual = async () => {
  try {
    await ElMessageBox.confirm(
        '将取消 OpenList 进行中离线任务并删除记录；已成功任务仅删记录。当前 RSS 正在等待的 hash 会跳过。是否继续？',
        '清理残留',
        {type: 'warning', confirmButtonText: '清理', cancelButtonText: '取消'}
    )
  } catch (_) {
    return
  }
  cleaning.value = true
  try {
    const res = await http.rssJobResidualClean()
    ElMessage.success(res.message || '清理完成')
    if (res?.data) applyStatus(res.data)
  } catch (e) {
    ElMessage.error(e?.message || '清理失败')
  } finally {
    cleaning.value = false
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

@media (max-width: 640px) {
  .task-grid {
    grid-template-columns: 1fr;
  }
}
</style>
