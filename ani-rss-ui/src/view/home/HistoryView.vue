<template>
  <div class="history-page app-page-layout">
    <PageHeaderView title="下载历史" :subtitle="subtitle">
      <template #actions>
        <el-button :loading="loading" icon="Refresh" @click="load">刷新</el-button>
        <el-button :disabled="!records.length" icon="Delete" @click="clearAll">清空</el-button>
      </template>
    </PageHeaderView>
    <div class="history-body app-page-content app-page-padding">
      <el-scrollbar class="history-scrollbar">
        <div class="history-content">
          <div class="metric-grid">
            <div class="metric-item">
              <el-text size="small" type="info">近 {{ days }} 天完成</el-text>
              <div class="metric-value">{{ summary.success }}</div>
            </div>
            <div class="metric-item">
              <el-text size="small" type="info">失败</el-text>
              <div class="metric-value">{{ summary.failed }}</div>
            </div>
            <div class="metric-item">
              <el-text size="small" type="info">成功率</el-text>
              <div class="metric-value">{{ rateText }}</div>
            </div>
            <div class="metric-item">
              <el-text size="small" type="info">下载体积</el-text>
              <div class="metric-value">{{ formatSize(summary.size) }}</div>
            </div>
          </div>

          <section v-if="dayStats.length" class="history-section">
            <div class="section-title">
              <h3>每日趋势</h3>
              <div class="legend">
                <span class="legend-item"><i class="dot success"></i>完成</span>
                <span class="legend-item"><i class="dot failed"></i>失败</span>
              </div>
            </div>
            <div class="trend">
              <div v-for="day in dayStats" :key="day.date" class="trend-col" :title="`${day.date} 完成 ${day.success} / 失败 ${day.failed}`">
                <div class="trend-bars">
                  <div class="bar success" :style="{height: barHeight(day.success) + 'px'}"></div>
                  <div class="bar failed" :style="{height: barHeight(day.failed) + 'px'}"></div>
                </div>
                <span class="trend-label">{{ shortDate(day.date) }}</span>
              </div>
            </div>
          </section>

          <section class="history-section">
            <div class="section-title">
              <h3>记录</h3>
              <div class="filters">
                <el-select v-model="filterAni" clearable filterable placeholder="全部订阅" class="filter-ani" @change="load">
                  <el-option v-for="ani in aniOptions" :key="ani.id" :label="ani.title" :value="ani.id"/>
                </el-select>
                <el-select v-model="filterResult" clearable placeholder="全部结果" class="filter-result" @change="load">
                  <el-option label="完成" value="SUCCESS"/>
                  <el-option label="洗版" value="WASH"/>
                  <el-option label="跳过" value="SKIP"/>
                  <el-option label="失败" value="FAILED"/>
                </el-select>
                <el-select v-model="days" class="filter-days" @change="load">
                  <el-option :label="'近 7 天'" :value="7"/>
                  <el-option :label="'近 30 天'" :value="30"/>
                  <el-option :label="'近 90 天'" :value="90"/>
                  <el-option label="全部" :value="0"/>
                </el-select>
              </div>
            </div>

            <el-alert v-if="error" :closable="false" :title="error" type="error" show-icon/>
            <el-empty v-else-if="!loading && !records.length" description="暂无下载记录"/>
            <el-table v-else :data="records" class="history-table" size="small">
              <el-table-column label="时间" width="150">
                <template #default="{row}">{{ formatTime(row.at) }}</template>
              </el-table-column>
              <el-table-column label="订阅" min-width="160" prop="title" show-overflow-tooltip/>
              <el-table-column label="集" width="70">
                <template #default="{row}">{{ row.episode == null ? '-' : row.episode }}</template>
              </el-table-column>
              <el-table-column label="文件" min-width="220" prop="reName" show-overflow-tooltip/>
              <el-table-column label="大小" width="100">
                <template #default="{row}">{{ formatSize(row.size) }}</template>
              </el-table-column>
              <el-table-column label="来源" width="110" prop="source" show-overflow-tooltip/>
              <el-table-column label="结果" width="90">
                <template #default="{row}">
                  <el-tag :type="resultType(row.result)" size="small">{{ resultLabel(row.result) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="说明" min-width="160" prop="message" show-overflow-tooltip/>
              <el-table-column label="操作" width="70" fixed="right">
                <template #default="{row}">
                  <el-button aria-label="移除记录" link type="danger" @click="removeOne(row)">移除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </div>
      </el-scrollbar>
    </div>
  </div>
</template>

<script setup>
import {computed, onMounted, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import * as http from '@/js/http.js'
import {formatSize, formatTime} from '@/js/format.js'
import PageHeaderView from '@/view/custom/PageHeaderView.vue'

const loading = ref(false)
const error = ref('')
const records = ref([])
const dayStats = ref([])
const summary = ref({success: 0, failed: 0, size: 0, successRate: 1})
const aniOptions = ref([])
const filterAni = ref('')
const filterResult = ref('')
const days = ref(30)

const subtitle = computed(() => `${records.value.length} 条记录`)
const rateText = computed(() => `${Math.round((summary.value.successRate || 0) * 100)}%`)

const maxBar = computed(() => {
  const max = Math.max(1, ...dayStats.value.map(d => Math.max(d.success, d.failed)))
  return max
})

const barHeight = value => Math.max(value > 0 ? 4 : 0, Math.round((value / maxBar.value) * 64))

const shortDate = date => (date || '').slice(5)

const resultLabel = result => ({
  SUCCESS: '完成',
  WASH: '洗版',
  SKIP: '跳过',
  FAILED: '失败'
}[result] || result || '-')

const resultType = result => ({
  SUCCESS: 'success',
  WASH: 'warning',
  SKIP: 'info',
  FAILED: 'danger'
}[result] || 'info')

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const [listRes, statRes] = await Promise.all([
      http.downloadHistory({
        aniId: filterAni.value || undefined,
        result: filterResult.value || undefined,
        days: days.value,
        limit: 300
      }),
      http.downloadHistoryStats({days: days.value <= 0 ? 30 : days.value})
    ])
    records.value = listRes.data || []
    summary.value = statRes.data?.summary || summary.value
    dayStats.value = statRes.data?.days || []
  } catch (e) {
    error.value = e?.message || '加载下载历史失败'
  } finally {
    loading.value = false
  }
}

const loadAniOptions = async () => {
  try {
    const res = await http.listAni()
    aniOptions.value = (res.data.weekList || []).flatMap(w => w.items || [])
  } catch (e) {
    aniOptions.value = []
  }
}

const removeOne = async row => {
  try {
    await http.downloadHistoryRemove(row.id)
    ElMessage.success('已移除')
    load()
  } catch (e) {
    // 错误提示由 api 层统一弹出
  }
}

const clearAll = async () => {
  try {
    await ElMessageBox.confirm('清空后无法恢复，确定继续？', '清空下载历史', {
      type: 'warning',
      confirmButtonText: '清空',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  try {
    const res = await http.downloadHistoryClear()
    ElMessage.success(res.message || '已清空')
    load()
  } catch (e) {
    // 错误提示由 api 层统一弹出
  }
}

onMounted(() => {
  loadAniOptions()
  load()
})
</script>

<style scoped>
.history-scrollbar {
  flex: 1;
  min-height: 0;
}

.history-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-bottom: 12px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.metric-item {
  min-width: 0;
  padding: 12px;
  border-radius: 8px;
  background-color: var(--el-bg-color);
}

.metric-value {
  margin-top: 4px;
  font-size: 22px;
  line-height: 1.2;
  font-weight: 700;
}

.history-section {
  padding: 12px;
  border-radius: 8px;
  background-color: var(--el-bg-color);
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.section-title h3 {
  font-size: 16px;
  line-height: 1.4;
}

.legend {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  display: inline-block;
}

.dot.success {
  background: var(--el-color-success);
}

.dot.failed {
  background: var(--el-color-danger);
}

.trend {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  overflow-x: auto;
  padding-bottom: 4px;
}

.trend-col {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  min-width: 26px;
}

.trend-bars {
  height: 68px;
  display: flex;
  align-items: flex-end;
  gap: 2px;
}

.bar {
  width: 8px;
  border-radius: 2px 2px 0 0;
  transition: height .2s ease;
}

.bar.success {
  background: var(--el-color-success);
}

.bar.failed {
  background: var(--el-color-danger);
}

.trend-label {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}

.filters {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.filter-ani {
  width: 180px;
}

.filter-result {
  width: 120px;
}

.filter-days {
  width: 110px;
}

.history-table {
  width: 100%;
}

@media (max-width: 900px) {
  .metric-grid {
    grid-template-columns: 1fr 1fr;
  }

  .filter-ani {
    width: 140px;
  }
}
</style>
