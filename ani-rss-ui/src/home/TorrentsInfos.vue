<template>
  <el-dialog v-model="dialogVisible" center title="下载">
    <div class="torrents-container">
      <div class="torrents-header">
        <el-radio-group v-model="sortType" class="dsh-segmented">
          <el-radio-button
              v-for="item in sortTypeList"
              :value="item.value"
              @click="changeSort(item.value)">
            <div class="flex-center">
              {{ item.label }}
              <el-icon
                  v-if="sortType === item.value"
                  class="el-icon--right"
              >
                <Top v-if="sortOrder === 'asc'"/>
                <Bottom v-else/>
              </el-icon>
            </div>
          </el-radio-button>
        </el-radio-group>
        <div class="torrents-header-actions">
          <el-tooltip content="暂停自动刷新（避免列表顺序持续跳动）" placement="top">
            <el-switch v-model="autoRefresh" active-text="自动刷新" inline-prompt/>
          </el-tooltip>
          <el-button bg text size="small" :loading="refreshing" @click="refresh">刷新</el-button>
        </div>
      </div>

      <!-- 查询失败与“真的没有任务”必须区分：此前下载器连不上会渲染成“当前无下载任务” -->
      <el-result
          v-if="loadError"
          class="torrents-empty"
          icon="warning"
          title="无法获取下载列表"
          :sub-title="loadError">
        <template #extra>
          <el-button type="primary" @click="refresh">重试</el-button>
        </template>
      </el-result>
      <el-empty v-else-if="!torrentsInfos.length" description="当前无下载任务" class="torrents-empty"/>
      <el-scrollbar v-else class="torrents-scrollbar">
        <div v-if="staleNotice" class="torrents-stale">
          <el-alert :title="staleNotice" type="warning" :closable="false" show-icon/>
        </div>
        <el-card v-for="torrentsInfo in torrentsInfos"
                 :key="torrentsInfo.hash || torrentsInfo.name"
                 shadow="never"
                 class="torrents-card">
          <p>{{ torrentsInfo.name }}</p>
          <el-progress :percentage="torrentsInfo['progress']"/>
          <div class="torrents-metrics">
            <span v-if="showMetric(torrentsInfo['formatDownloadSpeed'])">速度 {{ torrentsInfo['formatDownloadSpeed'] }}</span>
            <span v-if="showMetric(torrentsInfo['formatEta'])">剩余 {{ torrentsInfo['formatEta'] }}</span>
            <span v-if="torrentsInfo['formatCompleted']">已下载 {{ torrentsInfo['formatCompleted'] }}</span>
            <span v-if="torrentsInfo['formatSize']">总大小 {{ torrentsInfo['formatSize'] }}</span>
            <span v-if="torrentsInfo['numSeeds'] != null">做种 {{ torrentsInfo['numSeeds'] }}</span>
          </div>
          <template #footer>
            <div class="flex torrents-footer">
              <div>
                <el-tag v-for="tag in torrentsInfo['tags']" class="torrents-tag-spacer" type="info">
                  {{ tag }}
                </el-tag>
              </div>
              <div>
                <el-tag class="torrents-tag-right" type="info">
                  {{ torrentsInfo['formatSize'] }}
                </el-tag>
                <el-tag :type="stateTagType(torrentsInfo['state'])">
                  {{ stateText(torrentsInfo['state']) }}
                </el-tag>
              </div>
            </div>
          </template>
        </el-card>
      </el-scrollbar>
    </div>
  </el-dialog>
</template>

<script setup>
import {ref} from "vue";
import * as http from "@/js/http.js";
import {Bottom, Top} from "@element-plus/icons-vue";

// 记录排序方式
let sortType = ref('name')
// 记录排序顺序 asc=正序, desc=倒序
let sortOrder = ref('asc')
// 自动刷新开关（列表顺序按进度排序时每 3 秒重排会打断阅读）
let autoRefresh = ref(true)
let refreshing = ref(false)
// 查询失败原因与“数据已过期”提示
let loadError = ref('')
let staleNotice = ref('')

let sortTypeList = [
  {
    label: "按名称排序",
    value: "name",
    // 统一比较器语义：始终升序，由调用方按需 reverse
    fun: (value) => [...value].sort((a, b) => (a.name || '').localeCompare(b.name || ''))
  },
  {
    label: "按进度排序",
    value: "progress",
    fun: (value) => [...value].sort((a, b) => (a.progress || 0) - (b.progress || 0))
  }
]

let dialogVisible = ref(false)

let show = () => {
  dialogVisible.value = true
  loadError.value = ''
  staleNotice.value = ''
  // 轮询令牌：避免上一轮 while 未退出时重开导致双循环
  getTorrentsInfos()
}

let torrentsInfos = ref([])

let changeSort = (type) => {
  if (sortType.value === type) {
    // 相同排序方式，切换正序/倒序
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortType.value = type
    sortOrder.value = 'asc'
  }
  torrentsInfos.value = sortInfos(torrentsInfos.value)
}

let sortInfos = (infos) => {
  for (let sortTypeItem of sortTypeList) {
    let {value, fun} = sortTypeItem;
    if (value !== sortType.value) {
      continue
    }
    let sorted = fun(infos)
    return sortOrder.value === 'asc' ? sorted : sorted.reverse()
  }
  return infos;
}

/** 下载器状态 → 中文标签（此前直接渲染 stalledDL/checkingResumeData 等英文枚举） */
const stateMap = {
  downloading: {text: '下载中', type: 'primary'},
  uploading: {text: '做种中', type: 'success'},
  stalledDL: {text: '下载停滞', type: 'warning'},
  stalledUP: {text: '做种停滞', type: 'info'},
  pausedDL: {text: '已暂停', type: 'info'},
  pausedUP: {text: '已完成', type: 'success'},
  queuedDL: {text: '排队中', type: 'info'},
  queuedUP: {text: '排队做种', type: 'info'},
  checkingDL: {text: '校验中', type: 'warning'},
  checkingUP: {text: '校验中', type: 'warning'},
  checkingResumeData: {text: '校验中', type: 'warning'},
  metaDL: {text: '获取元数据', type: 'warning'},
  forcedDL: {text: '强制下载', type: 'primary'},
  forcedUP: {text: '强制做种', type: 'success'},
  error: {text: '错误', type: 'danger'},
  missingFiles: {text: '文件缺失', type: 'danger'},
  unknown: {text: '未知', type: 'info'}
}

const stateText = state => {
  if (!state) {
    return '未知'
  }
  return stateMap[state]?.text || String(state)
}

const stateTagType = state => stateMap[state]?.type || 'info'

/** 下载器未提供该字段时后端给 "-"，此时隐藏该行避免噪音 */
const showMetric = value => Boolean(value) && value !== '-'

let pollToken = 0

let getTorrentsInfos = async () => {
  const token = ++pollToken
  while (dialogVisible.value && token === pollToken) {
    await fetchOnce()
    if (!dialogVisible.value || token !== pollToken) {
      break
    }
    if (!autoRefresh.value) {
      // 暂停时不做轮询，仅等待开关恢复
      await sleep(1000)
      continue
    }
    await sleep(3000)
  }
}

let fetchOnce = async () => {
  try {
    const res = await http.torrentsInfos({silent: true})
    torrentsInfos.value = sortInfos(res.data || [])
    loadError.value = ''
    staleNotice.value = ''
  } catch (err) {
    // 保住上次成功的数据但明确标注已过期，避免把“查不到”显示成“没有任务”
    const reason = err?.message || '请检查下载器地址与账号配置'
    if (torrentsInfos.value.length) {
      staleNotice.value = `下载列表刷新失败：${reason}。以下为最近一次成功获取的数据`
    } else {
      loadError.value = `${reason}。请到「设置 → 下载设置」检查下载器地址与账号`
    }
  }
}

let refresh = async () => {
  refreshing.value = true
  try {
    await fetchOnce()
  } finally {
    refreshing.value = false
  }
}

let sleep = ms => {
  return new Promise(resolve => setTimeout(resolve, ms));
}

defineExpose({show})
</script>

<style scoped>
.torrents-container {
  height: 500px;
  display: flex;
  flex-direction: column;
}

.torrents-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
  flex-shrink: 0;
}

.torrents-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.torrents-scrollbar {
  flex: 1;
  overflow: hidden;
}

.torrents-empty {
  flex: 1;
}

.torrents-stale {
  margin-bottom: 6px;
}

.torrents-card {
  margin-bottom: 4px;
}

.torrents-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.torrents-footer {
  width: 100%;
  justify-content: space-between;
}

.torrents-tag-spacer {
  margin-left: 4px;
}

.torrents-tag-right {
  margin-right: 4px;
}
</style>
