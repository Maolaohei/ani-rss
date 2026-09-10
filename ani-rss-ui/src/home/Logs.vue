<template>
  <el-dialog v-model="dialogVisible" center class="logs-dialog" title="日志" @close="close">
    <div class="header auto-flex">
      <el-checkbox-group v-model:model-value="selectLevels" @change="renderLogs">
        <el-checkbox v-for="item in levels" :key="item" :label="item" :value="item" size="large"/>
      </el-checkbox-group>
      <div class="header-actions">
        <el-input
            v-model="keyword"
            class="logs-keyword"
            clearable
            placeholder="搜索关键词（标题 / 错误 / 类名）"
            prefix-icon="Search"
            @input="renderLogs"
            @clear="renderLogs"/>
        <el-select
            v-model="selectLoggerNames"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="类名"
            style="width: 220px;"
            @change="renderLogs"
        >
          <el-option
              v-for="item in loggerNames"
              :key="item"
              :label="item"
              :value="item"
          />
        </el-select>
        <el-tooltip content="自动滚动到最新日志；手动向上翻阅时请关闭" placement="top">
          <el-switch v-model="followTail" active-text="跟随" inline-prompt/>
        </el-tooltip>
        <el-tooltip content="下载完整日志（含历史文件）" placement="top">
          <el-button icon="Download" bg text @click="downloadLogs" :loading="downloadLoading"/>
        </el-tooltip>
        <el-tooltip content="重新拉取当前进程日志" placement="top">
          <el-button icon="Refresh" bg text @click="getLogs" :loading="getLogsLoading"/>
        </el-tooltip>
        <el-tooltip content="清空界面日志缓存（不影响磁盘日志）" placement="top">
          <el-button icon="Delete" bg text @click="clear" :loading="clearLoading"/>
        </el-tooltip>
      </div>
    </div>
    <div class="log-meta">
      <span>共 {{ logs.length }} 条，当前显示 {{ visibleLogs.length }} 条</span>
      <span v-if="logs.length >= logLimit" class="log-meta-warn">
        已达内存上限 {{ logLimit }} 条，更早的日志请下载完整日志查看
      </span>
      <span v-if="oldestText">最早一条：{{ oldestText }}</span>
    </div>
    <div class="content" v-loading="loading">
      <el-empty v-if="!loading && !visibleLogs.length" :image-size="80"
                :description="logs.length ? '没有符合筛选条件的日志' : '当前进程暂无日志，可下载完整日志查看历史记录'"/>
      <el-scrollbar v-else ref="scrollbarRef" height="450">
        <div ref="innerRef" class="log-body">
          <div v-for="(row, index) in visibleLogs" :key="index" class="log-line">
            <span class="log-time">{{ row.timeText }}</span>
            <span class="log-level" :class="`log-level-${row.levelKey}`">{{ row.level }}</span>
            <span class="log-logger">{{ row.loggerName }}</span>
            <span class="log-message">{{ row.message }}</span>
          </div>
        </div>
      </el-scrollbar>
    </div>
  </el-dialog>
</template>

<script setup>
import {computed, nextTick, onMounted, ref} from "vue";
import {ElMessage, ElMessageBox} from "element-plus";

import {authorization} from "@/js/global.js";
import * as http from "@/js/http.js";

const dialogVisible = ref(false)
const loading = ref(true)
const logs = ref([])
const scrollbarRef = ref()
const innerRef = ref()

const levels = ['DEBUG', 'INFO', 'WARN', 'ERROR']
const selectLevels = ref([])
const loggerNames = ref([])
const selectLoggerNames = ref([])
const keyword = ref('')
const followTail = ref(true)
const logLimit = ref(0)

/** 过滤后的日志；过滤与格式化都在前端完成（数据已全量下发） */
const visibleLogs = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return logs.value
      .filter(it => !selectLevels.value.length || selectLevels.value.includes(it.level))
      .filter(it => !selectLoggerNames.value.length || selectLoggerNames.value.includes(it.loggerName))
      .filter(it => !kw || (it.message || '').toLowerCase().includes(kw) || (it.loggerName || '').toLowerCase().includes(kw))
      .map(it => ({
        timeText: formatTimestamp(it.timestamp),
        level: it.level || '-',
        levelKey: (it.level || '').toLowerCase(),
        loggerName: shortenLogger(it.loggerName),
        message: it.message || ''
      }))
})

const oldestText = computed(() => {
  const first = logs.value.find(it => it.timestamp)
  return first ? formatTimestamp(first.timestamp) : ''
})

const formatTimestamp = ts => {
  const n = Number(ts)
  if (!n) {
    return '--:--:--'
  }
  const d = new Date(n)
  const pad = v => String(v).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

const shortenLogger = name => {
  if (!name) {
    return '-'
  }
  const parts = String(name).split('.')
  return parts.length > 1 ? parts[parts.length - 1] : name
}

const renderLogs = () => {
  if (!followTail.value) {
    return
  }
  nextTick(() => {
    scrollbarRef.value?.setScrollTop(innerRef.value?.clientHeight || 0)
  })
}

const show = () => {
  logs.value = []
  dialogVisible.value = true
  loading.value = true
  keyword.value = ''
  getLogs()
  selectLevels.value = levels
}

const getLogsLoading = ref(false)
const clearLoading = ref(false)
const downloadLoading = ref(false)

const clear = async () => {
  try {
    await ElMessageBox.confirm(
        '将清空界面日志缓存（磁盘日志不受影响，仍可通过下载获取），是否继续？',
        '清理日志',
        {type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消'}
    )
  } catch (e) {
    return
  }
  clearLoading.value = true
  http.clearLogs()
      .then(() => {
        getLogs();
      })
      .finally(() => {
        clearLoading.value = false
      })
}

const getLogs = () => {
  getLogsLoading.value = true
  http.logs()
      .then(async res => {
        logs.value = res.data || []
        loggerNames.value = []
        for (let datum of logs.value) {
          if (loggerNames.value.indexOf(datum['loggerName']) > -1) {
            continue
          }
          loggerNames.value.push(datum['loggerName'])
        }
        renderLogs()
      })
      .catch(() => {
        // api.js 已统一提示；这里保底避免 loading 卡死
      })
      .finally(() => {
        loading.value = false
        getLogsLoading.value = false
      })
}

/**
 * 下载完整日志。
 * 此前用 window.open 把登录令牌拼在 URL 查询串里（会进浏览器历史与反代 access log），
 * 且无法感知失败。改为带 Authorization 头的 fetch + Blob。
 */
let downloadLogs = async () => {
  downloadLoading.value = true
  try {
    const res = await fetch('api/downloadLogs', {
      method: 'GET',
      headers: authorization.value ? {Authorization: authorization.value} : {}
    })
    if (!res.ok) {
      ElMessage.error(`下载日志失败（HTTP ${res.status}）`)
      return
    }
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `ani-rss-logs-${new Date().toISOString().slice(0, 10)}.zip`
    document.body.appendChild(a)
    a.click()
    URL.revokeObjectURL(url)
    document.body.removeChild(a)
  } catch (e) {
    ElMessage.error('下载日志失败，请检查服务是否在运行')
  } finally {
    downloadLoading.value = false
  }
}

let close = () => {
  logs.value = []
  loggerNames.value = []
  selectLoggerNames.value = []
  keyword.value = ''
}

onMounted(() => {
  // 与设置里的 logsMax 对齐，用于提示“已达上限、更早日志需下载”
  http.config()
      .then(res => {
        logLimit.value = Number(res?.data?.logsMax || 0)
      })
      .catch(() => {
      })
})

defineExpose({show})
</script>

<style scoped>
.header {
  width: 100%;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.logs-keyword {
  width: 220px;
}

.log-meta {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  margin: 6px 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.log-meta-warn {
  color: var(--el-color-warning);
}

.content {
  background-color: #2e3440ff;
  color: #d8dee9ff;
  margin-top: 4px;
  padding: 4px;
  border-radius: var(--el-border-radius-base);
}

.log-body {
  min-height: 400px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
  font-size: 12px;
  line-height: 1.7;
}

.log-line {
  display: flex;
  gap: 8px;
  padding: 0 6px;
  word-break: break-all;
}

.log-line:hover {
  background-color: rgba(255, 255, 255, .06);
}

.log-time {
  flex: 0 0 auto;
  color: #8fbcbb;
}

.log-level {
  flex: 0 0 auto;
  width: 42px;
  font-weight: 700;
}

.log-level-info {
  color: #81a1c1;
}

.log-level-debug {
  color: #7d8799;
}

.log-level-warn {
  color: #ebcb8b;
}

.log-level-error {
  color: #bf616a;
}

.log-logger {
  flex: 0 0 auto;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #b48ead;
}

.log-message {
  flex: 1 1 auto;
  white-space: pre-wrap;
}

@media (min-width: 1400px) {
  .logs-dialog {
    width: 1000px;
  }
}
</style>
