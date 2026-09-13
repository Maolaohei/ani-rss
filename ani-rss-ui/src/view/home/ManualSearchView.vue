<template>
  <div class="manual-page app-page-layout">
    <PageHeaderView title="手动补种" subtitle="跨主 RSS / 备用 RSS / 自定义 RSS 搜索条目并直接下单"/>
    <div class="manual-body app-page-content app-page-padding">
      <el-scrollbar class="manual-scrollbar">
        <div class="manual-content">
          <section class="manual-section">
            <div class="search-row">
              <el-select v-model="aniId" clearable filterable placeholder="选择订阅" class="search-ani">
                <el-option v-for="ani in aniOptions" :key="ani.id" :label="ani.title" :value="ani.id"/>
              </el-select>
              <el-input v-model="keyword" clearable placeholder="关键词（标题 / 字幕组），留空返回全部"
                        class="search-keyword" @keyup.enter="search"/>
              <el-checkbox v-model="onlyMissing" label="只看未下载"/>
              <el-button :loading="loading" type="primary" icon="Search" @click="search">搜索</el-button>
            </div>
            <div class="search-row secondary">
              <el-input v-model="rssUrl" clearable placeholder="可选：直接粘贴任意 RSS 地址一起搜"
                        class="search-rss"/>
            </div>
            <el-text size="small" type="info">
              会聚合「主 RSS + 全部备用 RSS + 上面粘贴的地址」，结果标注来源与本地状态。
            </el-text>
          </section>

          <el-alert v-if="error" :closable="false" :title="error" type="error" show-icon/>

          <section v-if="sources.length" class="manual-section">
            <div class="section-title"><h3>搜索源</h3></div>
            <div class="source-list">
              <el-tag v-for="s in sources" :key="s.url" :type="s.error ? 'danger' : 'success'" size="small">
                {{ s.label }} · {{ s.error ? '失败' : s.count + ' 条' }}
              </el-tag>
            </div>
            <el-text v-if="sources.some(s => s.error)" size="small" type="danger">
              {{ sources.filter(s => s.error).map(s => `${s.label}: ${s.error}`).join('；') }}
            </el-text>
          </section>

          <section class="manual-section">
            <div class="section-title">
              <h3>结果</h3>
              <el-tag type="info" size="small">{{ items.length }}</el-tag>
            </div>
            <el-empty v-if="!loading && !items.length" description="没有匹配的条目，换个关键词或换源试试"/>
            <el-table v-else :data="items" class="manual-table" size="small">
              <el-table-column label="来源" width="120" prop="sourceLabel" show-overflow-tooltip/>
              <el-table-column label="集" width="70">
                <template #default="{row}">{{ row.episode == null ? '-' : row.episode }}</template>
              </el-table-column>
              <el-table-column label="条目" min-width="260" prop="reName" show-overflow-tooltip/>
              <el-table-column label="字幕组" width="130" prop="subgroup" show-overflow-tooltip/>
              <el-table-column label="大小" width="100" prop="formatSize"/>
              <el-table-column label="状态" width="90">
                <template #default="{row}">
                  <el-tag v-if="row.hasDownloaded" type="success" size="small">已下载</el-tag>
                  <el-tag v-else type="warning" size="small">未下载</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="90" fixed="right">
                <template #default="{row}">
                  <el-button link type="primary" :loading="downloading === row.infoHash"
                             @click="download(row)">
                    {{ row.hasDownloaded ? '强制重下' : '下载' }}
                  </el-button>
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
import PageHeaderView from '@/view/custom/PageHeaderView.vue'

const loading = ref(false)
const error = ref('')
const aniId = ref('')
const keyword = ref('')
const rssUrl = ref('')
const onlyMissing = ref(false)
const items = ref([])
const sources = ref([])
const aniOptions = ref([])
const downloading = ref('')

const selectedAni = computed(() => aniOptions.value.find(a => a.id === aniId.value) || null)

const loadAniOptions = async () => {
  try {
    const res = await http.listAni()
    aniOptions.value = (res.data.weekList || []).flatMap(w => w.items || [])
  } catch (e) {
    aniOptions.value = []
  }
}

const search = async () => {
  if (!aniId.value && !rssUrl.value.trim()) {
    ElMessage.warning('请先选择订阅或填写 RSS 地址')
    return
  }
  loading.value = true
  error.value = ''
  try {
    const res = await http.manualSearch({
      aniId: aniId.value || undefined,
      rssUrl: rssUrl.value.trim() || undefined,
      keyword: keyword.value.trim() || undefined,
      onlyMissing: onlyMissing.value,
      limit: 300
    })
    items.value = res.data.items || []
    sources.value = res.data.sources || []
  } catch (e) {
    error.value = e?.message || '搜索失败'
    items.value = []
    sources.value = []
  } finally {
    loading.value = false
  }
}

const download = async row => {
  if (!aniId.value) {
    ElMessage.warning('请先选择订阅（下单需要知道下到哪个目录）')
    return
  }
  if (row.hasDownloaded) {
    try {
      await ElMessageBox.confirm(
          '该条目本地已存在。强制下载会先删除已有文件再重新下载，确定继续？',
          '强制重下',
          {type: 'warning', confirmButtonText: '强制下载', cancelButtonText: '取消'})
    } catch (e) {
      return
    }
  }
  downloading.value = row.infoHash
  try {
    const res = await http.manualDownload(aniId.value, row)
    ElMessage.success(res.message || '已提交下载')
    search()
  } catch (e) {
    // 错误提示由 api 层统一弹出
  } finally {
    downloading.value = ''
  }
}

onMounted(loadAniOptions)
</script>

<style scoped>
.manual-scrollbar {
  flex: 1;
  min-height: 0;
}

.manual-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-bottom: 12px;
}

.manual-section {
  padding: 12px;
  border-radius: 8px;
  background-color: var(--el-bg-color);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.search-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}

.search-ani {
  width: 200px;
}

.search-keyword {
  flex: 1;
  min-width: 200px;
}

.search-rss {
  width: 100%;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.section-title h3 {
  font-size: 16px;
  line-height: 1.4;
}

.source-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.manual-table {
  width: 100%;
}

@media (max-width: 700px) {
  .search-ani {
    width: 100%;
  }
}
</style>
