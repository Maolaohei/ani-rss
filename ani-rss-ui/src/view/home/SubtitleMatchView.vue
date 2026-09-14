<template>
  <div class="match-page app-page-layout">
    <PageHeaderView title="字幕匹配" :subtitle="subtitle">
      <template #actions>
        <el-button :loading="loading" icon="Refresh" @click="load">刷新</el-button>
        <el-button :disabled="!records.length" icon="Delete" @click="clearAll">清空</el-button>
      </template>
    </PageHeaderView>
    <div class="match-body app-page-content app-page-padding">
      <el-alert class="match-tip" :closable="false" type="info" show-icon>
        <template #title>
          开启「字幕季数元数据解析」后，字幕番剧名未带 S1/S2 标记时，会自动查 TMDB/Bangumi 与订阅对比推断季数，避免跨季误匹配。
          相同番剧名只查一次并缓存到本地。下方记录每次匹配结果，便于核对。
        </template>
      </el-alert>

      <section class="match-section">
        <div class="section-title">
          <h3>匹配记录</h3>
          <el-select v-model="limit" class="filter-limit" @change="load">
            <el-option label="最近 50 条" :value="50"/>
            <el-option label="最近 100 条" :value="100"/>
            <el-option label="最近 200 条" :value="200"/>
          </el-select>
        </div>

        <el-alert v-if="error" :closable="false" :title="error" type="error" show-icon/>
        <el-empty v-else-if="!loading && !records.length" description="暂无匹配记录"/>
        <el-table v-else :data="records" class="match-table" size="small">
          <el-table-column label="对应视频" min-width="200" prop="videoName" show-overflow-tooltip/>
          <el-table-column label="原文件名" min-width="220" prop="originalName" show-overflow-tooltip>
            <template #default="{row}">
              <span v-if="row.originalName">{{ row.originalName }}</span>
              <el-text v-else type="info">—</el-text>
            </template>
          </el-table-column>
          <el-table-column label="匹配重命名后文件名" min-width="220" prop="renamedName" show-overflow-tooltip>
            <template #default="{row}">
              <span v-if="row.renamedName">{{ row.renamedName }}</span>
              <el-text v-else type="info">—</el-text>
            </template>
          </el-table-column>
          <el-table-column label="季" width="70">
            <template #default="{row}">
              <el-tag v-if="row.season != null" size="small" type="primary">S{{ row.season }}</el-tag>
              <el-text v-else type="info">—</el-text>
            </template>
          </el-table-column>
          <el-table-column label="语言" width="80" prop="lang">
            <template #default="{row}">
              <span v-if="row.lang">{{ row.lang }}</span>
              <el-text v-else type="info">—</el-text>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{row}">
              <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="170">
            <template #default="{row}">{{ formatTime(row.time) }}</template>
          </el-table-column>
        </el-table>
      </section>
    </div>
  </div>
</template>

<script setup>
import {computed, onMounted, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import * as http from '@/js/http.js'
import {formatTime} from '@/js/format.js'
import PageHeaderView from '@/view/custom/PageHeaderView.vue'

const loading = ref(false)
const error = ref('')
const records = ref([])
const limit = ref(100)

const subtitle = computed(() => `${records.value.length} 条记录`)

const statusLabel = status => ({
  '已匹配': '已匹配',
  '未命中': '未命中',
  '无候选': '无候选'
}[status] || status || '-')

const statusType = status => ({
  '已匹配': 'success',
  '未命中': 'warning',
  '无候选': 'info'
}[status] || 'info')

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const res = await http.post('api/subtitleMatchLog', {limit: limit.value})
    records.value = res.data || []
  } catch (e) {
    error.value = e?.message || '加载字幕匹配记录失败'
  } finally {
    loading.value = false
  }
}

const clearAll = async () => {
  try {
    await ElMessageBox.confirm('清空后无法恢复，确定继续？', '清空字幕匹配记录', {
      type: 'warning',
      confirmButtonText: '清空',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  try {
    const res = await http.post('api/subtitleMatchLogClear')
    ElMessage.success(res.message || '已清空')
    load()
  } catch (e) {
    // 错误提示由 api 层统一弹出
  }
}

onMounted(() => {
  load()
})
</script>

<style scoped>
.match-scrollbar {
  flex: 1;
  min-height: 0;
}

.match-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-bottom: 12px;
}

.match-tip {
  margin-bottom: 12px;
}

.match-section {
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

.filter-limit {
  width: 140px;
}

.match-table {
  width: 100%;
}

@media (max-width: 900px) {
  .filter-limit {
    width: 120px;
  }
}
</style>
