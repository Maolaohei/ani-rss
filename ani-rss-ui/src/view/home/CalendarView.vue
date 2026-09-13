<template>
  <div class="calendar-page app-page-layout">
    <PageHeaderView title="追番日历" :subtitle="subtitle">
      <template #actions>
        <el-radio-group v-model="mode" size="small">
          <el-radio-button label="week" value="week">周视图</el-radio-button>
          <el-radio-button label="month" value="month">月视图</el-radio-button>
        </el-radio-group>
        <el-button :loading="loading" icon="Refresh" @click="load">刷新</el-button>
      </template>
    </PageHeaderView>
    <div class="calendar-body app-page-content app-page-padding">
      <el-scrollbar class="calendar-scrollbar">
        <el-alert v-if="error" :closable="false" :title="error" type="error" show-icon/>
        <el-empty v-else-if="!loading && !hasAny" description="还没有订阅"/>

        <div v-else-if="mode === 'week'" class="week-grid">
          <section v-for="week in orderedWeeks" :key="week.weekLabel"
                   class="week-col" :class="{'is-today': week.weekLabel === todayLabel}">
            <header class="week-head">
              <span class="week-name">{{ week.weekLabel }}</span>
              <el-tag v-if="week.weekLabel === todayLabel" size="small" type="primary">今天</el-tag>
              <el-tag size="small" type="info">{{ (week.items || []).length }}</el-tag>
            </header>
            <div class="week-items">
              <el-empty v-if="!(week.items || []).length" description="无" :image-size="40"/>
              <div v-for="ani in week.items" :key="ani.id" class="ani-row" :class="{'is-disabled': !ani.enable}">
                <div class="ani-cover">
                  <img v-if="coverOf(ani)" :src="coverOf(ani)" :alt="ani.title" loading="lazy"
                       @error="onCoverError($event)"/>
                  <div v-else class="cover-fallback">{{ (ani.title || '?').slice(0, 1) }}</div>
                </div>
                <div class="ani-info">
                  <div class="ani-title" :title="ani.title">{{ ani.title }}</div>
                  <div class="ani-meta">
                    <span>{{ ani.currentEpisodeNumber ?? 0 }} / {{ ani.totalEpisodeNumber || '*' }}</span>
                    <span v-if="ani.subgroup" class="ani-subgroup" :title="ani.subgroup">{{ ani.subgroup }}</span>
                  </div>
                  <el-progress
                      v-if="ani.totalEpisodeNumber"
                      :percentage="progressOf(ani)"
                      :show-text="false"
                      :stroke-width="4"/>
                </div>
                <el-tag v-if="ani.healthLevel" :type="healthType(ani.healthLevel)" size="small">
                  {{ ani.healthScore ?? '-' }}
                </el-tag>
              </div>
            </div>
          </section>
        </div>

        <div v-else class="month-wrap">
          <div class="month-head">
            <el-button aria-label="上个月" text @click="shiftMonth(-1)">←</el-button>
            <span class="month-label">{{ monthLabel }}</span>
            <el-button aria-label="下个月" text @click="shiftMonth(1)">→</el-button>
          </div>
          <div class="month-grid">
            <div v-for="d in monthCells" :key="d.key" class="month-cell"
                 :class="{'is-out': !d.inMonth, 'is-today': d.isToday}">
              <span class="month-day">{{ d.day }}</span>
              <div class="month-dots">
                <span v-for="ani in d.items.slice(0, 6)" :key="ani.id" class="month-dot"
                      :title="ani.title" :class="healthClass(ani)"></span>
                <span v-if="d.items.length > 6" class="month-more">+{{ d.items.length - 6 }}</span>
              </div>
            </div>
          </div>
          <div class="month-legend">
            <span v-for="day in orderedWeeks" :key="day.weekLabel" class="month-legend-item">
              {{ day.weekLabel }}：{{ (day.items || []).length }} 部
            </span>
          </div>
        </div>
      </el-scrollbar>
    </div>
  </div>
</template>

<script setup>
import {computed, onMounted, ref} from 'vue'
import dayjs from 'dayjs'
import * as http from '@/js/http.js'
import PageHeaderView from '@/view/custom/PageHeaderView.vue'

const loading = ref(false)
const error = ref('')
const weekList = ref([])
const mode = ref('week')
const cursor = ref(dayjs())

const weekOrder = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日']
const todayLabel = computed(() => weekOrder[(new Date().getDay() + 6) % 7])

const orderedWeeks = computed(() => {
  const map = new Map(weekList.value.map(w => [w.weekLabel, w]))
  return weekOrder.map(label => map.get(label) || {weekLabel: label, items: []})
})

const hasAny = computed(() => orderedWeeks.value.some(w => (w.items || []).length > 0))
const totalCount = computed(() => orderedWeeks.value.reduce((sum, w) => sum + (w.items || []).length, 0))
const subtitle = computed(() => `共 ${totalCount.value} 部订阅`)

const monthLabel = computed(() => cursor.value.format('YYYY 年 MM 月'))

const monthCells = computed(() => {
  const start = cursor.value.startOf('month')
  const startWeekday = (start.day() + 6) % 7
  const daysInMonth = cursor.value.daysInMonth()
  const cells = []
  for (let i = 0; i < startWeekday; i++) {
    cells.push({key: `prev-${i}`, day: '', inMonth: false, isToday: false, items: []})
  }
  for (let d = 1; d <= daysInMonth; d++) {
    const date = start.date(d)
    const label = weekOrder[(date.day() + 6) % 7]
    const week = orderedWeeks.value.find(w => w.weekLabel === label)
    cells.push({
      key: `d-${d}`,
      day: d,
      inMonth: true,
      isToday: date.isSame(dayjs(), 'day'),
      items: week ? (week.items || []) : []
    })
  }
  return cells
})

const shiftMonth = delta => {
  cursor.value = cursor.value.add(delta, 'month')
}

const coverOf = ani => ani.cover || ani.image || ''

const onCoverError = event => {
  if (event?.target) {
    event.target.style.display = 'none'
  }
}

const progressOf = ani => {
  const total = Number(ani.totalEpisodeNumber || 0)
  const current = Number(ani.currentEpisodeNumber || 0)
  if (!total) {
    return 0
  }
  return Math.min(100, Math.round((current / total) * 100))
}

const healthType = level => ({
  good: 'success',
  warn: 'warning',
  bad: 'danger',
  paused: 'info',
  completed: 'info'
}[level] || 'info')

const healthClass = ani => {
  const level = ani.healthLevel
  if (level === 'bad') return 'is-bad'
  if (level === 'warn') return 'is-warn'
  if (!ani.enable) return 'is-paused'
  return 'is-good'
}

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const res = await http.listAni()
    weekList.value = res.data.weekList || []
  } catch (e) {
    error.value = e?.message || '加载订阅失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.calendar-scrollbar {
  flex: 1;
  min-height: 0;
}

.week-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(150px, 1fr));
  gap: 8px;
  padding-bottom: 12px;
  overflow-x: auto;
}

.week-col {
  min-width: 150px;
  padding: 10px;
  border-radius: 8px;
  background-color: var(--el-bg-color);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.week-col.is-today {
  outline: 1.5px solid var(--el-color-primary);
}

.week-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.week-name {
  font-size: 14px;
  font-weight: 600;
}

.week-items {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ani-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px;
  border-radius: 6px;
  background-color: var(--el-fill-color-light);
}

.ani-row.is-disabled {
  opacity: .55;
}

.ani-cover {
  width: 34px;
  height: 48px;
  flex-shrink: 0;
  border-radius: 4px;
  overflow: hidden;
  background-color: var(--el-fill-color);
  display: flex;
  align-items: center;
  justify-content: center;
}

.ani-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-fallback {
  font-size: 16px;
  color: var(--el-text-color-placeholder);
}

.ani-info {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ani-title {
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ani-meta {
  display: flex;
  gap: 6px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.ani-subgroup {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 70px;
}

.month-wrap {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-bottom: 12px;
}

.month-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.month-label {
  font-size: 15px;
  font-weight: 600;
}

.month-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 4px;
}

.month-cell {
  min-height: 72px;
  padding: 6px;
  border-radius: 6px;
  background-color: var(--el-bg-color);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.month-cell.is-out {
  opacity: .4;
}

.month-cell.is-today {
  outline: 1.5px solid var(--el-color-primary);
}

.month-day {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.month-dots {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
}

.month-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.month-dot.is-good {
  background: var(--el-color-success);
}

.month-dot.is-warn {
  background: var(--el-color-warning);
}

.month-dot.is-bad {
  background: var(--el-color-danger);
}

.month-dot.is-paused {
  background: var(--el-text-color-placeholder);
}

.month-more {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}

.month-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

@media (max-width: 900px) {
  .week-grid {
    grid-template-columns: repeat(7, minmax(130px, 1fr));
  }
}
</style>
