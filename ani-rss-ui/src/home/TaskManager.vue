<template>
  <el-dialog v-model="dialogVisible" center title="任务管理器" width="560px">
    <div class="job-body">
      <div class="job-row">
        <span class="job-label">状态</span>
        <el-tag :type="status.running ? 'warning' : 'info'">
          {{ status.running ? '运行中' : '空闲' }}
        </el-tag>
        <el-tag v-if="status.cancelRequested" type="danger" style="margin-left: 8px;">
          取消中
        </el-tag>
        <el-tag v-if="status.pending" type="success" style="margin-left: 8px;">
          有待执行
        </el-tag>
      </div>
      <div class="job-row">
        <span class="job-label">来源</span>
        <span>{{ sourceText }}</span>
      </div>
      <div class="job-row">
        <span class="job-label">范围</span>
        <span>{{ scopeText }}</span>
      </div>
      <div class="job-row" v-if="status.title">
        <span class="job-label">标题</span>
        <span class="job-value">{{ status.title }}</span>
      </div>
      <div class="job-row">
        <span class="job-label">消息</span>
        <span class="job-value">{{ status.message || '-' }}</span>
      </div>
      <div class="job-row">
        <span class="job-label">已运行</span>
        <span>{{ elapsedText }}</span>
      </div>
      <div class="job-row" v-if="status.currentHash">
        <span class="job-label">Hash</span>
        <span class="job-value mono">{{ status.currentHash }}</span>
      </div>
      <div class="job-row" v-if="status.pending">
        <span class="job-label">待执行</span>
        <span class="job-value">{{ pendingText }}</span>
      </div>

      <template v-if="status.residualSupported">
        <div class="job-divider"></div>
        <div class="job-row">
          <span class="job-label">离线残留</span>
          <span class="job-value">
            进行中 {{ status.residualActiveCount ?? 0 }} / 终态 {{ status.residualTerminalCount ?? 0 }}
            <el-tag v-if="status.residualCleaning" type="warning" size="small" style="margin-left: 8px;">清理中</el-tag>
          </span>
        </div>
        <div class="job-row" v-if="status.residualMessage">
          <span class="job-label">残留消息</span>
          <span class="job-value">{{ status.residualMessage }}</span>
        </div>
        <div class="job-row" v-if="status.residualSamples?.length">
          <span class="job-label">样例</span>
          <span class="job-value mono residual-samples">{{ status.residualSamples.join(' | ') }}</span>
        </div>
        <div class="job-row" v-if="status.residualScannedAt">
          <span class="job-label">扫描于</span>
          <span>{{ residualScannedText }}</span>
        </div>
      </template>

      <el-alert
          v-if="status.running || status.pending"
          type="info"
          :closable="false"
          show-icon
          title="取消会停止后续订阅推进并清空待执行；OpenList/Alist 会取消进行中的离线种子并删除记录（已成功任务仅删记录）。qB/Aria2/TR 只停 RSS 推进。"
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
      <el-empty v-if="!status.running && !status.pending && !status.residualSupported" description="当前没有 RSS 任务" :image-size="80"/>
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
            :disabled="(!status.running && !status.pending) || status.cancelRequested"
            :loading="canceling"
            @click="cancelJob"
        >
          取消任务
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
const loading = ref(false)
const canceling = ref(false)
const scanning = ref(false)
const cleaning = ref(false)
const status = ref({
  running: false,
  cancelRequested: false,
  scope: 'idle',
  title: '',
  aniId: '',
  startedAt: null,
  elapsedMs: 0,
  message: '空闲',
  currentHash: null,
  source: null,
  pending: false,
  pendingTitle: null,
  pendingScope: null,
  residualSupported: false,
  residualActiveCount: 0,
  residualTerminalCount: 0,
  residualTotalCount: 0,
  residualScannedAt: null,
  residualCleaning: false,
  residualMessage: null,
  residualSamples: []
})

const scopeText = computed(() => {
  const scope = status.value.scope
  if (scope === 'all') return '全部订阅'
  if (scope === 'single') return '单个订阅'
  if (scope === 'partial') return '部分订阅'
  if (scope === 'starting') return '启动中'
  return '空闲'
})

const sourceText = computed(() => {
  const s = status.value.source
  if (s === 'manual') return '手动刷新'
  if (s === 'periodic') return '周期扫描'
  return '-'
})

const pendingText = computed(() => {
  if (!status.value.pending) return '-'
  const scope = status.value.pendingScope
  const title = status.value.pendingTitle || ''
  if (scope === 'all') return '全部启用订阅'
  if (scope === 'single') return title || '单个订阅'
  if (scope === 'partial') return title || '部分订阅'
  return title || '待执行手动刷新'
})

const elapsedText = computed(() => {
  const ms = Number(status.value.elapsedMs || 0)
  if (!status.value.running || ms <= 0) return '-'
  const totalSec = Math.floor(ms / 1000)
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  if (h > 0) return `${h}小时${m}分${s}秒`
  if (m > 0) return `${m}分${s}秒`
  return `${s}秒`
})

const residualScannedText = computed(() => {
  const ts = Number(status.value.residualScannedAt || 0)
  if (!ts) return '-'
  try {
    return new Date(ts).toLocaleString()
  } catch (_) {
    return String(ts)
  }
})

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
      status.value = {
        residualSupported: false,
        residualActiveCount: 0,
        residualTerminalCount: 0,
        residualTotalCount: 0,
        residualSamples: [],
        ...res.data
      }
    }
  } catch (_) {
  }
}

const pollStatus = async () => {
  while (dialogVisible.value) {
    await fetchStatus()
    await sleep((status.value.running || status.value.pending || status.value.residualCleaning) ? 2000 : 4000)
  }
}

const cancelJob = async () => {
  canceling.value = true
  try {
    const res = await http.rssJobCancel()
    ElMessage.success(res.message || '已请求取消')
    if (res?.data) {
      status.value = {
        residualSupported: false,
        residualActiveCount: 0,
        residualTerminalCount: 0,
        residualTotalCount: 0,
        residualSamples: [],
        ...res.data
      }
    }
  } catch (e) {
    ElMessage.error(e?.message || '取消失败')
  } finally {
    canceling.value = false
  }
}

const scanResidual = async () => {
  scanning.value = true
  try {
    const res = await http.rssJobResidualScan()
    ElMessage.success(res.message || '扫描完成')
    if (res?.data) {
      status.value = {
        residualSupported: false,
        residualActiveCount: 0,
        residualTerminalCount: 0,
        residualTotalCount: 0,
        residualSamples: [],
        ...res.data
      }
    }
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
    if (res?.data) {
      status.value = {
        residualSupported: false,
        residualActiveCount: 0,
        residualTerminalCount: 0,
        residualTotalCount: 0,
        residualSamples: [],
        ...res.data
      }
    }
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
  min-height: 220px;
}

.job-row {
  display: flex;
  align-items: flex-start;
  margin-bottom: 10px;
  line-height: 1.5;
}

.job-label {
  width: 72px;
  flex-shrink: 0;
  color: var(--el-text-color-secondary);
}

.job-value {
  flex: 1;
  word-break: break-all;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
  font-size: 12px;
}

.residual-samples {
  color: var(--el-text-color-secondary);
}

.job-divider {
  height: 1px;
  background: var(--el-border-color-lighter);
  margin: 8px 0 12px;
}

.job-footer {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
