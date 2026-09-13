<template>
  <div class="doctor-page app-page-layout">
    <PageHeaderView title="系统自检" :subtitle="subtitle">
      <template #actions>
        <el-button :loading="loading" icon="Refresh" @click="load">重新检测</el-button>
      </template>
    </PageHeaderView>
    <div class="doctor-body app-page-content app-page-padding">
      <el-scrollbar class="doctor-scrollbar">
        <div class="doctor-content">
          <div v-if="summary" class="metric-grid">
            <div class="metric-item">
              <div class="metric-icon ok"><el-icon><CircleCheck/></el-icon></div>
              <div>
                <el-text size="small" type="info">通过</el-text>
                <div class="metric-value">{{ summary.ok }}</div>
              </div>
            </div>
            <div class="metric-item">
              <div class="metric-icon warn"><el-icon><Warning/></el-icon></div>
              <div>
                <el-text size="small" type="info">注意</el-text>
                <div class="metric-value">{{ summary.warn }}</div>
              </div>
            </div>
            <div class="metric-item">
              <div class="metric-icon fail"><el-icon><CircleClose/></el-icon></div>
              <div>
                <el-text size="small" type="info">不通过</el-text>
                <div class="metric-value">{{ summary.fail }}</div>
              </div>
            </div>
            <div class="metric-item">
              <div class="metric-icon skip"><el-icon><Minus/></el-icon></div>
              <div>
                <el-text size="small" type="info">跳过</el-text>
                <div class="metric-value">{{ summary.skip }}</div>
              </div>
            </div>
          </div>

          <el-alert v-if="error" :closable="false" :title="error" type="error" show-icon/>

          <el-empty v-else-if="!loading && !items.length" description="暂无检查结果"/>

          <div v-else class="check-list">
            <div v-for="item in items" :key="item.key" class="check-card" :class="`is-${item.level}`">
              <div class="check-head">
                <el-icon class="check-icon">
                  <CircleCheck v-if="item.level === 'ok'"/>
                  <Warning v-else-if="item.level === 'warn'"/>
                  <CircleClose v-else-if="item.level === 'fail'"/>
                  <Minus v-else/>
                </el-icon>
                <span class="check-label">{{ item.label }}</span>
                <el-tag :type="tagType(item.level)" size="small">{{ levelLabel(item.level) }}</el-tag>
                <el-text v-if="item.elapsedMs" class="check-elapsed" size="small" type="info">
                  {{ item.elapsedMs }} ms
                </el-text>
              </div>
              <div class="check-detail">{{ item.detail }}</div>
              <div v-if="item.suggestion" class="check-suggestion">
                <span class="check-suggestion-label">建议</span>
                <span>{{ item.suggestion }}</span>
              </div>
            </div>
          </div>

          <el-text v-if="checkedAt" class="doctor-foot" size="small" type="info">
            检测时间：{{ formatTime(checkedAt) }}
          </el-text>
        </div>
      </el-scrollbar>
    </div>
  </div>
</template>

<script setup>
import {computed, onMounted, ref} from 'vue'
import {CircleCheck, CircleClose, Minus, Warning} from '@element-plus/icons-vue'
import * as http from '@/js/http.js'
import {formatTime} from '@/js/format.js'
import PageHeaderView from '@/view/custom/PageHeaderView.vue'

const loading = ref(false)
const error = ref('')
const items = ref([])
const summary = ref(null)
const checkedAt = ref(0)

const subtitle = computed(() => {
  if (loading.value) {
    return '正在检测…'
  }
  if (!summary.value) {
    return '检查配置是否真的通了'
  }
  if (summary.value.fail > 0) {
    return `有 ${summary.value.fail} 项未通过，见下方建议`
  }
  if (summary.value.warn > 0) {
    return `${summary.value.warn} 项需要注意`
  }
  return '全部检查通过'
})

const levelLabel = level => ({
  ok: '通过',
  warn: '注意',
  fail: '不通过',
  skip: '跳过'
}[level] || level)

const tagType = level => ({
  ok: 'success',
  warn: 'warning',
  fail: 'danger',
  skip: 'info'
}[level] || 'info')

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const res = await http.doctor()
    items.value = res.data.items || []
    summary.value = res.data.summary || null
    checkedAt.value = res.data.checkedAt || 0
  } catch (e) {
    error.value = e?.message || '自检失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.doctor-scrollbar {
  flex: 1;
  min-height: 0;
}

.doctor-content {
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

.metric-icon.ok {
  color: var(--el-color-success);
  background-color: var(--el-color-success-light-9);
}

.metric-icon.warn {
  color: var(--el-color-warning);
  background-color: var(--el-color-warning-light-9);
}

.metric-icon.fail {
  color: var(--el-color-danger);
  background-color: var(--el-color-danger-light-9);
}

.metric-icon.skip {
  color: var(--el-color-info);
  background-color: var(--el-color-info-light-9);
}

.metric-value {
  margin-top: 2px;
  font-size: 24px;
  line-height: 1.2;
  font-weight: 700;
}

.check-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.check-card {
  padding: 12px 14px;
  border-radius: 8px;
  background-color: var(--el-bg-color);
  border-left: 3px solid var(--el-border-color);
}

.check-card.is-ok {
  border-left-color: var(--el-color-success);
}

.check-card.is-warn {
  border-left-color: var(--el-color-warning);
}

.check-card.is-fail {
  border-left-color: var(--el-color-danger);
}

.check-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.check-icon {
  font-size: 16px;
}

.check-card.is-ok .check-icon {
  color: var(--el-color-success);
}

.check-card.is-warn .check-icon {
  color: var(--el-color-warning);
}

.check-card.is-fail .check-icon {
  color: var(--el-color-danger);
}

.check-card.is-skip .check-icon {
  color: var(--el-text-color-placeholder);
}

.check-label {
  font-weight: 600;
  font-size: 14px;
}

.check-elapsed {
  margin-left: auto;
}

.check-detail {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  overflow-wrap: anywhere;
}

.check-suggestion {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-color-primary);
  overflow-wrap: anywhere;
}

.check-suggestion-label {
  display: inline-block;
  margin-right: 6px;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 12px;
  background-color: var(--el-color-primary-light-9);
}

.doctor-foot {
  padding-top: 4px;
}

@media (max-width: 900px) {
  .metric-grid {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
