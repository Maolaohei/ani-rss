<template>
  <div class="match-page app-page-layout">
    <PageHeaderView title="字幕匹配" :subtitle="subtitle">
      <template #actions>
        <el-button icon="Upload" @click="openImport">导入本地字幕</el-button>
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

    <el-dialog v-model="importVisible" class="import-dialog" title="导入本地字幕" width="780px"
               :close-on-click-modal="false">
      <el-form label-width="80px">
        <el-form-item label="订阅">
          <el-select v-model="importAniId" clearable filterable class="full-width"
                     placeholder="选择字幕对应的订阅（匹配该订阅下载目录内的视频）">
            <el-option v-for="ani in aniOptions" :key="ani.id" :label="ani.title" :value="ani.id"/>
          </el-select>
        </el-form-item>
        <el-form-item label="字幕文件">
          <div class="import-dropzone" :class="{'is-dragover': dragOver}"
               @click="pickFiles"
               @dragenter.prevent="dragOver = true"
               @dragover.prevent
               @dragleave.prevent="dragOver = false"
               @drop.prevent="onDrop">
            <el-icon class="dropzone-icon">
              <UploadFilled/>
            </el-icon>
            <div class="dropzone-text">点击选择，或将字幕文件拖拽到此处（可多选）</div>
            <div class="dropzone-hint">支持 ass / srt / ssa / vtt / sub，单个不超过 20MiB</div>
          </div>
          <input ref="fileInputRef" :accept="acceptExt" hidden multiple type="file" @change="onPick">

          <div v-if="pendingFiles.length" class="pending-list">
            <div class="pending-head">
              <el-text size="small">已选择 {{ pendingFiles.length }} 个文件（{{ formatSize(pendingBytes) }}）</el-text>
              <el-button link size="small" type="primary" @click="clearPending">清空</el-button>
            </div>
            <el-scrollbar max-height="150px">
              <div v-for="(f, i) in pendingFiles" :key="f.name + '-' + i" class="pending-item">
                <el-text class="pending-name" size="small">{{ f.name }}</el-text>
                <el-text size="small" type="info">{{ formatSize(f.size) }}</el-text>
                <el-button link size="small" type="danger" icon="Close" @click="removePending(i)"/>
              </div>
            </el-scrollbar>
          </div>
        </el-form-item>
      </el-form>

      <el-alert class="import-tip" :closable="false" type="info" show-icon>
        <template #title>
          按「季 + 集」自动匹配订阅目录下<b>已重命名</b>的视频：字幕 <b>碧蓝之海 S03E15.cht.ass</b> 对应视频
          <b>碧蓝之海 S03E15.mkv</b>，将重命名为 <b>碧蓝之海 S03E15.cht.ass</b>；无语言标识则命名为
          <b>碧蓝之海 S03E15.ass</b>。同名文件覆盖前会自动备份为 .bak。
        </template>
      </el-alert>

      <section v-if="importResult" class="import-result">
        <div class="section-title">
          <h3>导入结果</h3>
          <div class="result-summary">
            <el-tag size="small" type="success">成功 {{ importResult.success || 0 }}</el-tag>
            <el-tag v-if="importResult.failed" size="small" type="danger">失败 {{ importResult.failed }}</el-tag>
          </div>
        </div>
        <el-table :data="importResult.items || []" class="import-result-table" max-height="260" size="small">
          <el-table-column label="上传文件" min-width="180" prop="originalName" show-overflow-tooltip/>
          <el-table-column label="重命名后" min-width="180" show-overflow-tooltip>
            <template #default="{row}">
              <span v-if="row.renamedName">{{ row.renamedName }}</span>
              <el-text v-else type="info">—</el-text>
            </template>
          </el-table-column>
          <el-table-column label="对应视频" min-width="160" show-overflow-tooltip>
            <template #default="{row}">
              <span v-if="row.videoName">{{ row.videoName }}</span>
              <el-text v-else type="info">—</el-text>
            </template>
          </el-table-column>
          <el-table-column label="语言" width="70">
            <template #default="{row}">
              <span v-if="row.lang">{{ row.lang }}</span>
              <el-text v-else type="info">—</el-text>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{row}">
              <el-tag :type="row.status === '已匹配' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="200" prop="reason" show-overflow-tooltip>
            <template #default="{row}">
              <span v-if="row.reason">{{ row.reason }}</span>
              <el-text v-else type="info">—</el-text>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <template #footer>
        <el-button @click="importVisible = false">关闭</el-button>
        <el-button :disabled="!pendingFiles.length" :loading="importing" type="primary" @click="doImport">
          开始导入
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import {computed, onMounted, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import * as http from '@/js/http.js'
import {formatSize, formatTime} from '@/js/format.js'
import PageHeaderView from '@/view/custom/PageHeaderView.vue'

const SUBTITLE_EXT = ['ass', 'srt', 'ssa', 'vtt', 'sub']
const MAX_FILE_BYTES = 20 * 1024 * 1024

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
    const res = await http.subtitleMatchLog(limit.value)
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
    const res = await http.subtitleMatchLogClear()
    ElMessage.success(res.message || '已清空')
    load()
  } catch (e) {
    // 错误提示由 api 层统一弹出
  }
}

/* ==================== 本地字幕批量导入 ==================== */

const importVisible = ref(false)
const importAniId = ref('')
const aniOptions = ref([])
const aniLoading = ref(false)
const pendingFiles = ref([])
const dragOver = ref(false)
const importing = ref(false)
const importResult = ref(null)
const fileInputRef = ref()

const acceptExt = SUBTITLE_EXT.map(ext => `.${ext}`).join(',')
const pendingBytes = computed(() => pendingFiles.value.reduce((sum, f) => sum + (f.size || 0), 0))

const openImport = async () => {
  importVisible.value = true
  importResult.value = null
  if (!aniOptions.value.length) {
    await loadAniOptions()
  }
}

const loadAniOptions = async () => {
  aniLoading.value = true
  try {
    const res = await http.listAni()
    aniOptions.value = (res?.data?.weekList || []).flatMap(w => w.items || [])
  } catch (e) {
    aniOptions.value = []
  } finally {
    aniLoading.value = false
  }
}

const pickFiles = () => {
  if (!fileInputRef.value) {
    return
  }
  fileInputRef.value.value = ''
  fileInputRef.value.click()
}

/**
 * 统一收口：扩展名白名单 + 单文件大小 + 去重（同名同大小视为同一文件）
 */
const addFiles = (fileList) => {
  const incoming = Array.from(fileList || [])
  if (!incoming.length) {
    return
  }
  const existing = new Set(pendingFiles.value.map(f => `${f.name}#${f.size}`))
  let rejected = 0
  for (const file of incoming) {
    const ext = (file.name.match(/\.([^.]+)$/)?.[1] || '').toLowerCase()
    if (!SUBTITLE_EXT.includes(ext)) {
      rejected++
      continue
    }
    if (file.size > MAX_FILE_BYTES) {
      ElMessage.warning(`${file.name} 超过 20MiB，已跳过`)
      continue
    }
    const key = `${file.name}#${file.size}`
    if (existing.has(key)) {
      continue
    }
    existing.add(key)
    pendingFiles.value.push(file)
  }
  if (rejected) {
    ElMessage.warning(`已忽略 ${rejected} 个非字幕文件`)
  }
}

const onPick = () => {
  addFiles(fileInputRef.value?.files)
}

const onDrop = (e) => {
  dragOver.value = false
  addFiles(e.dataTransfer?.files)
}

const removePending = (index) => {
  pendingFiles.value.splice(index, 1)
}

const clearPending = () => {
  pendingFiles.value = []
}

const doImport = async () => {
  if (!importAniId.value) {
    ElMessage.warning('请先选择字幕对应的订阅')
    return
  }
  if (!pendingFiles.value.length) {
    ElMessage.warning('请先选择字幕文件')
    return
  }

  const formData = new FormData()
  formData.append('aniId', importAniId.value)
  pendingFiles.value.forEach(file => formData.append('files', file))

  importing.value = true
  try {
    const res = await http.subtitleImport(formData)
    if (res?.code !== 200) {
      ElMessage.error(res?.message || '导入失败')
      return
    }
    importResult.value = res.data || null
    const success = res.data?.success || 0
    const failed = res.data?.failed || 0
    if (success && !failed) {
      ElMessage.success(`导入完成，成功 ${success} 个`)
      pendingFiles.value = []
    } else if (success) {
      ElMessage.warning(`导入完成：成功 ${success}，失败 ${failed}`)
      pendingFiles.value = []
    } else {
      ElMessage.error('全部文件未匹配到视频，请查看下方说明')
    }
    load()
  } catch (e) {
    ElMessage.error(e?.message || '导入失败')
  } finally {
    importing.value = false
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

.full-width {
  width: 100%;
}

.import-dropzone {
  width: 100%;
  padding: 18px 12px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.2s, background-color 0.2s;
}

.import-dropzone:hover,
.import-dropzone.is-dragover {
  border-color: var(--el-color-primary);
  background-color: var(--el-color-primary-light-9);
}

.dropzone-icon {
  font-size: 26px;
  color: var(--el-color-primary);
}

.dropzone-text {
  margin-top: 6px;
  font-size: 13px;
}

.dropzone-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.pending-list {
  width: 100%;
  margin-top: 10px;
}

.pending-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.pending-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 0;
}

.pending-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.import-tip {
  margin-bottom: 12px;
}

.import-result {
  padding: 12px;
  border-radius: 8px;
  background-color: var(--el-fill-color-lighter);
}

.import-result-table {
  width: 100%;
}

.result-summary {
  display: flex;
  gap: 8px;
}

@media (max-width: 900px) {
  .filter-limit {
    width: 120px;
  }
}
</style>
