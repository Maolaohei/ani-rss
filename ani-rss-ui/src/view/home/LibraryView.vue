<template>
  <div class="library-page app-page-layout">
    <PageHeaderView title="媒体库" :subtitle="subtitle">
      <template #actions>
        <el-button :loading="loading" icon="Refresh" @click="load">刷新</el-button>
        <el-button icon="RefreshRight" @click="rescan">重新扫描</el-button>
      </template>
    </PageHeaderView>
    <div class="library-body app-page-content app-page-padding">
      <el-scrollbar class="library-scrollbar">
        <div class="library-content">
          <div class="metric-grid">
            <div class="metric-item">
              <el-text size="small" type="info">番剧</el-text>
              <div class="metric-value">{{ data.total || 0 }}</div>
            </div>
            <div class="metric-item">
              <el-text size="small" type="info">视频文件</el-text>
              <div class="metric-value">{{ data.totalVideos || 0 }}</div>
            </div>
            <div class="metric-item">
              <el-text size="small" type="info">占用空间</el-text>
              <div class="metric-value">{{ data.formatTotalSize || '-' }}</div>
            </div>
          </div>

          <section class="library-section">
            <div class="toolbar">
              <el-input v-model="keyword" clearable placeholder="搜索标题 / 字幕组 / 路径"
                        class="library-search" @keyup.enter="load"/>
              <el-checkbox v-model="onlyExisting" label="只看本地已有" @change="load"/>
              <el-button :loading="loading" type="primary" icon="Search" @click="load">搜索</el-button>
            </div>

            <el-alert v-if="error" :closable="false" :title="error" type="error" show-icon/>
            <el-empty v-else-if="!loading && !items.length" description="没有匹配的番剧"/>
            <el-table v-else :data="items" class="library-table" size="small">
              <el-table-column label="封面" width="70">
                <template #default="{row}">
                  <div class="lib-cover">
                    <img v-if="coverOf(row)" :src="coverOf(row)" :alt="row.title" loading="lazy"
                         @error="onCoverError($event)"/>
                    <div v-else class="cover-fallback">{{ (row.title || '?').slice(0, 1) }}</div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="标题" min-width="200">
                <template #default="{row}">
                  <div class="lib-title">{{ row.title }}</div>
                  <div class="lib-meta">
                    <span v-if="row.season">第 {{ row.season }} 季</span>
                    <span v-if="row.subgroup">{{ row.subgroup }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="集数" width="90">
                <template #default="{row}">
                  <el-tag :type="row.videoCount > 0 ? 'success' : 'info'" size="small">
                    {{ row.videoCount }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="占用" width="110" prop="formatSize"/>
              <el-table-column label="最近更新" width="150">
                <template #default="{row}">{{ row.lastModify ? formatTime(row.lastModify) : '-' }}</template>
              </el-table-column>
              <el-table-column label="路径" min-width="220" prop="downloadPath" show-overflow-tooltip/>
              <el-table-column label="操作" width="90" fixed="right">
                <template #default="{row}">
                  <el-button link type="primary" :disabled="!row.exists" @click="openDetail(row)">
                    查看剧集
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </div>
      </el-scrollbar>
    </div>

    <el-dialog v-model="detailVisible" :title="detailTitle" width="720px" class="library-dialog">
      <el-empty v-if="!detailItems.length" description="没有找到视频文件"/>
      <el-table v-else :data="detailItems" size="small" max-height="420">
        <el-table-column label="集" width="70">
          <template #default="{row}">{{ row.episode }}</template>
        </el-table-column>
        <el-table-column label="文件" min-width="260" prop="name" show-overflow-tooltip/>
        <el-table-column label="大小" width="110" prop="formatSize"/>
        <el-table-column label="字幕" width="80">
          <template #default="{row}">
            <el-tag :type="(row.subtitles || []).length ? 'success' : 'info'" size="small">
              {{ (row.subtitles || []).length }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="修改时间" width="150">
          <template #default="{row}">{{ row.lastModify ? formatTime(row.lastModify) : '-' }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import {computed, onMounted, ref} from 'vue'
import {ElMessage} from 'element-plus'
import * as http from '@/js/http.js'
import {formatTime} from '@/js/format.js'
import PageHeaderView from '@/view/custom/PageHeaderView.vue'

const loading = ref(false)
const error = ref('')
const data = ref({total: 0, totalVideos: 0, formatTotalSize: '-'})
const items = ref([])
const keyword = ref('')
const onlyExisting = ref(false)

const detailVisible = ref(false)
const detailTitle = ref('')
const detailItems = ref([])

const subtitle = computed(() => {
  if (loading.value) {
    return '正在扫描…'
  }
  return data.value.total ? `共 ${data.value.total} 部 · ${data.value.formatTotalSize}` : '还没有可浏览的内容'
})

const coverOf = row => row.image || ''

const onCoverError = event => {
  if (event?.target) {
    event.target.style.display = 'none'
  }
}

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const res = await http.library({
      keyword: keyword.value.trim() || undefined,
      onlyExisting: onlyExisting.value
    })
    data.value = res.data || data.value
    items.value = res.data.items || []
  } catch (e) {
    error.value = e?.message || '加载媒体库失败'
    items.value = []
  } finally {
    loading.value = false
  }
}

const rescan = async () => {
  loading.value = true
  try {
    await http.libraryRefresh()
    ElMessage.success('已重新扫描')
    await load()
  } catch (e) {
    // 错误提示由 api 层统一弹出
  } finally {
    loading.value = false
  }
}

const openDetail = async row => {
  detailTitle.value = `${row.title} · 本地剧集`
  detailItems.value = []
  detailVisible.value = true
  try {
    const res = await http.libraryDetail(row.aniId)
    detailItems.value = res.data || []
  } catch (e) {
    detailItems.value = []
  }
}

onMounted(load)
</script>

<style scoped>
.library-scrollbar {
  flex: 1;
  min-height: 0;
}

.library-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-bottom: 12px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
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

.library-section {
  padding: 12px;
  border-radius: 8px;
  background-color: var(--el-bg-color);
}

.toolbar {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
  margin-bottom: 10px;
}

.library-search {
  width: 260px;
}

.library-table {
  width: 100%;
}

.lib-cover {
  width: 40px;
  height: 56px;
  border-radius: 4px;
  overflow: hidden;
  background-color: var(--el-fill-color);
  display: flex;
  align-items: center;
  justify-content: center;
}

.lib-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-fallback {
  font-size: 18px;
  color: var(--el-text-color-placeholder);
}

.lib-title {
  font-weight: 600;
  font-size: 13px;
}

.lib-meta {
  display: flex;
  gap: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

@media (max-width: 900px) {
  .metric-grid {
    grid-template-columns: 1fr 1fr;
  }

  .library-search {
    width: 100%;
  }
}
</style>
