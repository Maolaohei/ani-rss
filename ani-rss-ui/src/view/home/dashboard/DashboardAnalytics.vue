<template>
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
          <div class="bar success" :style="{height: heightOf(day.success) + 'px'}"></div>
          <div class="bar failed" :style="{height: heightOf(day.failed) + 'px'}"></div>
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
</template>

<script setup>
import {computed} from "vue";
import {formatDate, fromNow} from "@/js/format.js";
import {barHeight, shortDate} from "./format.js";

const props = defineProps({
  historyDays: {type: Array, default: () => []},
  historySummary: {type: Object, default: () => ({success: 0, failed: 0})},
  successRateText: {type: String, default: '0%'},
  enabledTotal: {type: Number, default: 0},
  healthBuckets: {type: Array, default: () => []},
  omitTop: {type: Array, default: () => []},
  procrastinatingList: {type: Array, default: () => []}
})

const maxHistoryBar = computed(() => {
  const max = Math.max(1, ...props.historyDays.map(d => Math.max(d.success, d.failed)))
  return max
})

const heightOf = value => barHeight(value, maxHistoryBar.value)
</script>

<style scoped src="./dashboard.css"></style>
