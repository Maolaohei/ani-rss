<template>
  <div class="dashboard-page app-page-layout">
    <EditAniView ref="editAniRef"/>
    <PlayListView ref="playListRef"/>
    <CoverView ref="coverRef"/>
    <DelAniView ref="delAniRef"/>
    <BgmRateView ref="bgmRateRef"/>
    <PageHeaderView title="首页" :subtitle="`${todayLabel} · ${todayText}`">
      <template #actions>
        <el-button :loading="refreshLoading" icon="Refresh" @click="loadAll" class="auto-button">
          刷新
        </el-button>
      </template>
    </PageHeaderView>
    <div class="dashboard-body app-page-content app-page-padding">

      <el-scrollbar class="dashboard-scrollbar">
        <div class="dashboard-content">
          <div class="metric-grid">
            <div class="metric-item">
              <div class="metric-icon subscriptions">
                <el-icon>
                  <List/>
                </el-icon>
              </div>
              <div>
                <el-text size="small" type="info">订阅数量</el-text>
                <div class="metric-value">{{ subscriptionTotal }}</div>
              </div>
            </div>
            <div class="metric-item">
              <div class="metric-icon enabled">
                <el-icon>
                  <CircleCheck/>
                </el-icon>
              </div>
              <div>
                <el-text size="small" type="info">已启用</el-text>
                <div class="metric-value">{{ enabledTotal }}</div>
              </div>
            </div>
            <div class="metric-item">
              <div class="metric-icon downloading">
                <el-icon>
                  <Download/>
                </el-icon>
              </div>
              <div>
                <el-text size="small" type="info">下载中</el-text>
                <div class="metric-value">{{ downloadingList.length }}</div>
              </div>
            </div>
            <div class="metric-item">
              <div class="metric-icon seeding">
                <el-icon>
                  <Upload/>
                </el-icon>
              </div>
              <div>
                <el-text size="small" type="info">做种中</el-text>
                <div class="metric-value">{{ seedingList.length }}</div>
              </div>
            </div>
          </div>

          <section class="dashboard-section today-section">
            <div class="today-section-content">
              <div class="section-title">
                <h3>今天的订阅</h3>
                <div class="today-heading-actions">
                  <el-tag type="info">{{ todayAnis.length }}</el-tag>
                  <div v-if="todayAnis.length" class="today-scroll-actions">
                    <el-button aria-label="向左滚动" bg circle text @click="scrollToday(-1)">
                      <el-icon>
                        <ArrowLeft/>
                      </el-icon>
                    </el-button>
                    <el-button aria-label="向右滚动" bg circle text @click="scrollToday(1)">
                      <el-icon>
                        <ArrowRight/>
                      </el-icon>
                    </el-button>
                  </div>
                </div>
              </div>
              <el-empty v-if="!todayAnis.length" description="今天没有订阅"/>
              <div v-else ref="todayTrack" class="today-track">
                <div v-for="ani in todayAnis"
                     :key="ani.id"
                     class="today-item">
                  <AniCoverView
                      :item="ani"
                      @edit="editAniRef?.show"
                      @playlist="playListRef?.show"
                      @cover="coverRef?.show"
                      @del="delAniRef?.show"
                      @rate="bgmRateRef?.show"/>
                </div>
              </div>
            </div>
          </section>

          <section class="dashboard-section dashboard-section-full">
            <div class="section-title">
              <h3>近 7 天下载</h3>
              <div class="today-heading-actions">
                <el-tag type="success">成功 {{ historySummary.success }}</el-tag>
                <el-tag v-if="historySummary.failed" type="danger">失败 {{ historySummary.failed }}</el-tag>
                <el-tag type="info">成功率 {{ successRateText }}</el-tag>
              </div>
            </div>
            <el-empty v-if="!historyDays.length" description="暂无下载记录"/>
            <div v-else class="trend">
              <div v-for="day in historyDays" :key="day.date" class="trend-col"
                   :title="`${day.date} 完成 ${day.success} / 失败 ${day.failed}`">
                <div class="trend-bars">
                  <div class="bar success" :style="{height: barHeight(day.success) + 'px'}"></div>
                  <div class="bar failed" :style="{height: barHeight(day.failed) + 'px'}"></div>
                </div>
                <span class="trend-label">{{ shortDate(day.date) }}</span>
              </div>
            </div>
          </section>

          <section class="dashboard-section">
            <div class="section-title">
              <h3>健康概览</h3>
              <el-tag type="info">{{ enabledTotal }} 部在追</el-tag>
            </div>
            <div class="health-grid">
              <div v-for="h in healthBuckets" :key="h.level" class="health-item">
                <span class="health-dot" :class="`is-${h.level}`"></span>
                <span class="health-label">{{ h.label }}</span>
                <span class="health-count">{{ h.count }}</span>
              </div>
            </div>
            <el-divider/>
            <div class="section-title">
              <h3>漏集 TOP</h3>
              <el-tag :type="omitTop.length ? 'warning' : 'info'">{{ omitTop.length }}</el-tag>
            </div>
            <el-empty v-if="!omitTop.length" description="暂无漏集" :image-size="60"/>
            <el-table v-else :data="omitTop" class="dashboard-table" size="small">
              <el-table-column label="订阅" min-width="200" prop="title" show-overflow-tooltip/>
              <el-table-column label="漏集" width="80">
                <template #default="{row}">
                  <el-tag type="warning" size="small">{{ row.omitCount }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="进度" width="100">
                <template #default="{row}">
                  {{ row.currentEpisodeNumber }} / {{ row.totalEpisodeNumber || '*' }}
                </template>
              </el-table-column>
            </el-table>
          </section>

          <section class="dashboard-section">
            <div class="section-title">
              <h3>疑似停更列表</h3>
              <el-tag type="warning">{{ procrastinatingList.length }}</el-tag>
            </div>
            <el-empty v-if="!procrastinatingList.length" description="暂无疑似停更订阅"/>
            <el-table v-else :data="procrastinatingList" class="dashboard-table" size="small">
              <el-table-column label="订阅" min-width="220" prop="title" show-overflow-tooltip/>
              <el-table-column label="进度" width="100">
                <template #default="{ row }">
                  {{ row.currentEpisodeNumber }} / {{ row.totalEpisodeNumber || '*' }}
                </template>
              </el-table-column>
              <el-table-column label="最后下载" width="150">
                <template #default="{ row }">
                  {{ row.lastDownloadTime > 0 ? fromNow(row.lastDownloadTime) : formatDate(row.releaseDate) }}
                </template>
              </el-table-column>
              <el-table-column label="间隔" width="90">
                <template #default="{ row }">
                  {{ row.procrastinatingDays }} 天
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
import {computed, onActivated, onDeactivated, onMounted, onUnmounted, ref} from "vue";
import {ArrowLeft, ArrowRight, CircleCheck, Download, List, Upload} from "@element-plus/icons-vue";
import {formatDate, fromNow} from "@/js/format.js";
import * as http from "@/js/http.js";
import AniCoverView from "@/view/home/AniCoverView.vue";
import EditAniView from "@/view/home/EditAniView.vue";
import PlayListView from "@/view/play/PlayListView.vue";
import CoverView from "@/view/home/CoverView.vue";
import DelAniView from "@/view/home/DelAniView.vue";
import BgmRateView from "@/view/home/BgmRateView.vue";
import PageHeaderView from "@/view/custom/PageHeaderView.vue";

const weekLabels = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
const downloadingStates = ['forcedDL', 'downloading', 'forcedMetaDL', 'metaDL', 'stalledDL', 'checkingDL', 'queuedDL', 'allocating', 'moving']
const seedingStates = ['forcedUP', 'uploading', 'stalledUP', 'queuedUP', 'checkingUP']
const dayMs = 24 * 60 * 60 * 1000

const refreshLoading = ref(false)
const weekList = ref([])
const subscriptionTotalValue = ref(0)
const torrentsInfos = ref([])
const todayTrack = ref()
const editAniRef = ref()
const delAniRef = ref()
const coverRef = ref()
const playListRef = ref()
const bgmRateRef = ref()
const config = ref({
  procrastinatingDay: 14
})
const historyDays = ref([])
const historySummary = ref({success: 0, failed: 0, skip: 0, successRate: 1, size: 0})

let timer

const todayLabel = computed(() => weekLabels[new Date().getDay()])
const flatAnis = computed(() => weekList.value.flatMap(week => week.items || []))
const enabledAnis = computed(() => flatAnis.value.filter(item => item.enable))
const subscriptionTotal = computed(() => subscriptionTotalValue.value || flatAnis.value.length)
const enabledTotal = computed(() => enabledAnis.value.length)
const todayAnis = computed(() => {
  const today = weekList.value.find(week => week.weekLabel === todayLabel.value)
  return (today ? today.items || [] : []).filter(item => item.enable)
})
const todayText = computed(() => todayAnis.value.length ? `${todayAnis.value.length} 个订阅` : '没有订阅')
const downloadingList = computed(() => torrentsInfos.value.filter(isDownloading))
const seedingList = computed(() => torrentsInfos.value.filter(isSeeding))
const procrastinatingList = computed(() => {
  const threshold = Number(config.value.procrastinatingDay || 14)
  return enabledAnis.value
      .filter(item => item.procrastinating !== false)
      .filter(item => !item.totalEpisodeNumber || item.currentEpisodeNumber < item.totalEpisodeNumber)
      .map(item => {
        const time = getCompareTime(item)
        const procrastinatingDays = time ? Math.floor((Date.now() - time) / dayMs) : 0
        return {
          ...item,
          procrastinatingDays
        }
      })
      .filter(item => item.procrastinatingDays >= threshold)
      .sort((a, b) => b.procrastinatingDays - a.procrastinatingDays)
})

const getCompareTime = item => {
  if (item.lastDownloadTime > 0) {
    return item.lastDownloadTime
  }
  const releaseTime = new Date(item.releaseDate).getTime()
  return Number.isNaN(releaseTime) ? 0 : releaseTime
}

const successRateText = computed(() => `${Math.round((historySummary.value.successRate || 0) * 100)}%`)

const maxHistoryBar = computed(() => {
  const max = Math.max(1, ...historyDays.value.map(d => Math.max(d.success, d.failed)))
  return max
})

const barHeight = value => Math.max(value > 0 ? 4 : 0, Math.round((value / maxHistoryBar.value) * 56))

const shortDate = date => (date || '').slice(5)

/**
 * 健康分布：按 healthLevel 归桶。健康分由后端在 listAni 时算好，
 * 这里只做聚合，不再额外请求。
 */
const healthBuckets = computed(() => {
  const buckets = [
    {level: 'good', label: '良好', count: 0},
    {level: 'warn', label: '注意', count: 0},
    {level: 'bad', label: '异常', count: 0},
    {level: 'paused', label: '已停用', count: 0},
    {level: 'completed', label: '已完结', count: 0}
  ]
  const map = new Map(buckets.map(b => [b.level, b]))
  for (const ani of enabledAnis.value) {
    const bucket = map.get(ani.healthLevel) || map.get('warn')
    bucket.count += 1
  }
  return buckets
})

/**
 * 漏集 TOP：用 RSS 周期缓存的 omitCount，不额外拉源
 */
const omitTop = computed(() => enabledAnis.value
    .filter(item => Number(item.omitCount || 0) > 0)
    .map(item => ({...item, omitCount: Number(item.omitCount)}))
    .sort((a, b) => b.omitCount - a.omitCount)
    .slice(0, 8))

const isDownloading = item => downloadingStates.includes(item.state)
const isSeeding = item => seedingStates.includes(item.state)

const scrollToday = direction => {
  const track = todayTrack.value
  if (!track) {
    return
  }
  track.scrollBy({
    left: direction * Math.min(track.clientWidth * 0.75, 560),
    behavior: 'smooth'
  })
}

const loadAni = async () => {
  const res = await http.listAni()
  weekList.value = res.data.weekList || []
  subscriptionTotalValue.value = res.data.total || 0
}

const loadConfig = async () => {
  const res = await http.config()
  config.value = res.data || config.value
}

const loadTorrents = async () => {
  const res = await http.torrentsInfos()
  torrentsInfos.value = res.data || []
}

const loadHistory = async () => {
  try {
    const res = await http.downloadHistoryStats({days: 7})
    historySummary.value = res.data?.summary || historySummary.value
    historyDays.value = res.data?.days || []
  } catch (e) {
    historyDays.value = []
  }
}

const loadAll = async () => {
  refreshLoading.value = true
  try {
    await Promise.all([
      loadAni(),
      loadConfig(),
      loadTorrents(),
      loadHistory()
    ])
  } finally {
    refreshLoading.value = false
  }
}

const startPolling = () => {
  if (timer) {
    return
  }
  timer = setInterval(loadTorrents, 5000)
}

const stopPolling = () => {
  clearInterval(timer)
  timer = undefined
}

onMounted(loadAll)
onActivated(startPolling)
onDeactivated(stopPolling)
onUnmounted(stopPolling)
</script>

<style scoped>
.metric-grid {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.metric-item {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border-radius: 8px;
  background-color: var(--el-bg-color);
}

.metric-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.metric-icon.subscriptions {
  color: var(--el-color-primary);
  background-color: var(--el-color-primary-light-9);
}

.metric-icon.enabled {
  color: var(--el-color-success);
  background-color: var(--el-color-success-light-9);
}

.metric-icon.downloading {
  color: var(--el-color-warning);
  background-color: var(--el-color-warning-light-9);
}

.metric-icon.seeding {
  color: var(--el-color-info);
  background-color: var(--el-color-info-light-9);
}

.metric-value {
  margin-top: 2px;
  font-size: 24px;
  line-height: 1.2;
  font-weight: 700;
}

.dashboard-scrollbar {
  flex: 1;
  min-height: 0;
}

.dashboard-content {
  display: grid;
  grid-template-columns: minmax(280px, 0.9fr) minmax(320px, 1.1fr);
  gap: 14px;
  padding-bottom: 8px;
}

/* 「近 7 天下载」独占整行：图表横向跨度更充裕，双柱对比一目了然 */
.dashboard-section-full {
  grid-column: 1 / -1;
}

.dashboard-section {
  min-width: 0;
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
}

.section-title h3 {
  font-size: 16px;
  line-height: 1.4;
}

.today-section {
  position: relative;
  grid-column: 1 / -1;
  overflow: hidden;
}

.today-section-content {
  min-width: 0;
}

.today-heading-actions,
.today-scroll-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.today-scroll-actions .el-button {
  width: 28px;
  height: 28px;
  margin-left: 0;
}

.today-track {
  min-width: 0;
  display: flex;
  gap: 10px;
  overflow-x: auto;
  overflow-y: hidden;
  padding: 2px 2px 8px;
  scroll-behavior: smooth;
  scroll-snap-type: x proximity;
  scrollbar-color: var(--el-border-color) transparent;
  scrollbar-width: thin;
}

.today-track::-webkit-scrollbar {
  height: 6px;
}

.today-track::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background-color: var(--el-border-color);
}

.today-track::-webkit-scrollbar-track {
  background-color: transparent;
}

.today-item {
  flex: 0 0 142px;
  min-width: 0;
  scroll-snap-align: start;
}

.dashboard-table {
  width: 100%;
}

.trend {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  overflow-x: auto;
  padding-bottom: 4px;
}

.trend-col {
  flex: 1 1 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  min-width: 46px;
}

.trend-bars {
  height: 60px;
  display: flex;
  align-items: flex-end;
  gap: 3px;
}

.bar {
  width: 16px;
  border-radius: 2px 2px 0 0;
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

.health-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  gap: 8px;
}

.health-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font-size: 13px;
}

.health-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.health-dot.is-good {
  background: var(--el-color-success);
}

.health-dot.is-warn {
  background: var(--el-color-warning);
}

.health-dot.is-bad {
  background: var(--el-color-danger);
}

.health-dot.is-paused,
.health-dot.is-completed {
  background: var(--el-text-color-placeholder);
}

.health-label {
  flex: 1;
  color: var(--el-text-color-regular);
}

.health-count {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

@media (max-width: 900px) {
  .metric-grid,
  .dashboard-content {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 560px) {
  .metric-grid,
  .dashboard-content {
    grid-template-columns: 1fr;
  }

  .metric-item {
    padding: 10px;
  }

  .today-scroll-actions {
    display: none;
  }

  .today-item {
    flex-basis: 118px;
  }
}
</style>
