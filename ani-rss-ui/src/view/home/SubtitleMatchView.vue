<template>
  <div class="match-page app-page-layout">
    <PageHeaderView title="字幕匹配" :subtitle="subtitle">
      <template #actions>
        <el-button icon="Files" type="primary" @click="openDialog">字幕管理</el-button>
        <el-button :loading="loading" icon="Refresh" @click="load">刷新</el-button>
        <el-button :disabled="!records.length" icon="Delete" @click="clearAll">清空</el-button>
      </template>
    </PageHeaderView>
    <div class="match-body app-page-content app-page-padding">
      <el-alert class="match-tip" :closable="false" type="info" show-icon>
        <template #title>
          字幕统一在这里手动处理：可<b>上传本地字幕</b>，或<b>从射手网获取字幕</b>。下载完成后不会自动抓取，
          且无论哪种方式，写入前都会弹窗展示「改名前 / 改名后 / 对应的视频」并二次确认，避免自动匹配到错误字幕。
          开启「字幕季数元数据解析」后，字幕番剧名未带 S1/S2 标记时会自动查 TMDB/Bangumi 与订阅对比推断季数，避免跨季误匹配。
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
          <el-table-column label="改名前" min-width="220" prop="originalName" show-overflow-tooltip>
            <template #default="{row}">
              <span v-if="row.originalName">{{ row.originalName }}</span>
              <el-text v-else type="info">—</el-text>
            </template>
          </el-table-column>
          <el-table-column label="改名后" min-width="220" prop="renamedName" show-overflow-tooltip>
            <template #default="{row}">
              <span v-if="row.renamedName">{{ row.renamedName }}</span>
              <el-text v-else type="info">—</el-text>
            </template>
          </el-table-column>
          <el-table-column label="对应的视频" min-width="200" prop="videoName" show-overflow-tooltip>
            <template #default="{row}">
              <span v-if="row.videoName">{{ row.videoName }}</span>
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

    <el-dialog v-model="dialogVisible" title="字幕管理" width="880px" style="max-width: 94vw"
               :close-on-click-modal="false" @closed="resetDialog">
      <el-radio-group v-model="mode" class="mode-switch" :disabled="step !== 'form'">
        <el-radio-button value="upload">手动上传字幕</el-radio-button>
        <el-radio-button value="assrt">获取射手网字幕</el-radio-button>
      </el-radio-group>

      <el-form class="match-form" label-width="90px">
        <el-form-item label="订阅">
          <el-select v-model="aniId" :disabled="step !== 'form'" clearable filterable class="full-width"
                     placeholder="选择字幕对应的订阅（匹配该订阅下载目录内的视频）">
            <el-option v-for="ani in aniOptions" :key="ani.id" :label="ani.title" :value="ani.id"/>
          </el-select>
        </el-form-item>

        <el-form-item v-if="selectedAni" label="剧集信息">
          <div class="ani-meta">
            <el-tag size="small" type="primary">第 {{ selectedAni.season || 1 }} 季</el-tag>
            <el-tag size="small" type="info">
              共 {{ selectedAni.totalEpisodeNumber || '未知' }} 集
            </el-tag>
            <el-tag size="small">已下载 {{ selectedAni.currentEpisodeNumber ?? 0 }} 集</el-tag>
            <el-text class="ani-meta-title" size="small" type="info">{{ selectedAni.title }}</el-text>
          </div>
        </el-form-item>

        <el-form-item v-if="mode === 'upload'" label="字幕文件">
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

        <el-form-item v-else label="获取说明">
          <el-text size="small" type="info">
            先<b>搜索候选</b>：以该订阅的<b>英文标题</b>向射手网(ASSRT)发起<b>单次</b>搜索（不按季集拆分请求），
            列出候选字幕后由你挑选；选中后再下载并预览，确认无误才写入。
            需先在 设置 → 其他设置 中开启「字幕手动获取」并填写 ASSRT Token。
            字幕源有调用频率限制（默认 5 次/分钟），因此搜索阶段只发一次请求。
          </el-text>
        </el-form-item>
      </el-form>

      <el-alert v-if="step === 'form'" class="import-tip" :closable="false" type="info" show-icon>
        <template #title>
          按「季 + 集」自动匹配订阅目录下<b>已重命名</b>的视频：字幕 <b>碧蓝之海 S03E15.cht.ass</b> 对应视频
          <b>碧蓝之海 S03E15.mkv</b>，将重命名为 <b>碧蓝之海 S03E15.cht.ass</b>；无语言标识则命名为
          <b>碧蓝之海 S03E15.ass</b>。同名文件覆盖前会自动备份到视频同目录的 <b>sub_bak/</b>（不存在则新建）。
        </template>
      </el-alert>

      <section v-if="step === 'select'" class="import-result">
        <div class="section-title">
          <h3>选择字幕</h3>
          <div class="result-summary">
            <el-tag size="small" type="primary">共 {{ candidates.length }} 条候选</el-tag>
            <el-tag size="small" type="info">关键词：{{ searchKeyword || '—' }}</el-tag>
          </div>
        </div>

        <el-alert class="import-tip" :closable="false" type="info" show-icon>
          <template #title>
            已用<b>英文标题</b>单次搜索，未按季集拆分请求。请点击选中一条最合适的字幕（合集包会自动按目标集数解包挑选），
            再点「使用选中字幕」下载并预览。
          </template>
        </el-alert>

        <el-empty v-if="!candidates.length" description="未搜索到候选字幕，可换用「手动上传字幕」"/>

        <el-table v-else :data="candidates" class="import-result-table" highlight-current-row max-height="320"
                  size="small" @row-click="onRowClick">
          <el-table-column width="44">
            <template #default="{row}">
              <el-icon v-if="row.index === selectedIndex" color="var(--el-color-primary)">
                <Select/>
              </el-icon>
            </template>
          </el-table-column>
          <el-table-column label="字幕名" min-width="300" prop="title" show-overflow-tooltip>
            <template #default="{row}">
              <span v-if="row.title">{{ row.title }}</span>
              <el-text v-else type="info">（无标题）</el-text>
            </template>
          </el-table-column>
          <el-table-column label="语言" width="80">
            <template #default="{row}">
              <span v-if="row.lang">{{ row.lang }}</span>
              <el-text v-else type="info">—</el-text>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="90">
            <template #default="{row}">
              <el-tag :type="row.archive ? 'warning' : 'success'" size="small">
                {{ row.archive ? '合集包' : '单文件' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="文件数" width="90">
            <template #default="{row}">
              <span v-if="row.fileCount >= 0">{{ row.fileCount }}</span>
              <el-text v-else type="info">需解析</el-text>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section v-if="step === 'preview' || step === 'done'" class="import-result">
        <div class="section-title">
          <h3>{{ step === 'preview' ? '请确认导入内容' : '导入结果' }}</h3>
          <div class="result-summary">
            <el-tag size="small" type="success">
              {{ step === 'preview' ? '可导入' : '成功' }} {{ step === 'preview' ? importableCount : (importResult?.success || 0) }}
            </el-tag>
            <el-tag v-if="failedCount" size="small" type="danger">
              {{ step === 'preview' ? '未命中' : '失败' }} {{ failedCount }}
            </el-tag>
          </div>
        </div>

        <el-alert v-if="step === 'preview'" class="import-tip" :closable="false" type="warning" show-icon>
          <template #title>
            以下为<b>将要写入</b>的变更，确认后才会真正写入；同名文件覆盖前会自动备份到视频同目录的 <b>sub_bak/</b>。
          </template>
        </el-alert>

        <el-table :data="displayItems" class="import-result-table" max-height="300" size="small">
          <el-table-column label="改名前" min-width="200" prop="originalName" show-overflow-tooltip>
            <template #default="{row}">
              <span v-if="row.originalName">{{ row.originalName }}</span>
              <el-text v-else type="info">—</el-text>
            </template>
          </el-table-column>
          <el-table-column label="改名后" min-width="200" show-overflow-tooltip>
            <template #default="{row}">
              <span v-if="row.renamedName">{{ row.renamedName }}</span>
              <el-text v-else type="info">—</el-text>
            </template>
          </el-table-column>
          <el-table-column label="对应的视频" min-width="180" prop="videoName" show-overflow-tooltip>
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
        <el-button v-if="step === 'preview'" @click="backFromPreview">返回</el-button>
        <el-button v-else @click="dialogVisible = false">{{ step === 'done' ? '完成' : '取消' }}</el-button>

        <el-button v-if="step === 'form'" :disabled="!canPreview" :loading="previewing" type="primary"
                   @click="onPrimary">
          {{ mode === 'upload' ? '预览匹配结果' : '搜索候选字幕' }}
        </el-button>
        <el-button v-else-if="step === 'select'" :disabled="selectedIndex < 0" :loading="previewing" type="primary"
                   @click="onPrimary">
          使用选中字幕
        </el-button>
        <el-button v-else-if="step === 'preview'" :disabled="!importableCount" :loading="importing" type="primary"
                   @click="doConfirm">
          确认导入（{{ importableCount }}）
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

/* ==================== 字幕管理（手动上传 / 射手网获取） ==================== */

/** 当前步骤：form 填写 → (射手网)select 选候选 → preview 二次确认 → done 结果 */
const step = ref('form')
const dialogVisible = ref(false)
const mode = ref('upload')
const aniId = ref('')
const aniOptions = ref([])
const pendingFiles = ref([])
const dragOver = ref(false)
const fileInputRef = ref()
const previewing = ref(false)
const importing = ref(false)
/** 预览得到的待写入清单（改名前 / 改名后 / 对应的视频） */
const planItems = ref([])
/** 射手网预览计划 id，确认导入时回传给后端消费 */
const planId = ref('')
/** 射手网搜索结果 id（选中候选后生成计划时回传） */
const searchId = ref('')
/** 射手网实际使用的搜索关键词（通常是英文标题），用于向用户说明搜的是什么 */
const searchKeyword = ref('')
/** 射手网候选条目列表 */
const candidates = ref([])
/** 用户选中的候选下标，-1 表示未选 */
const selectedIndex = ref(-1)
const importResult = ref(null)

const acceptExt = SUBTITLE_EXT.map(ext => `.${ext}`).join(',')
const pendingBytes = computed(() => pendingFiles.value.reduce((sum, f) => sum + (f.size || 0), 0))

const selectedAni = computed(() => aniOptions.value.find(a => a.id === aniId.value) || null)
const displayItems = computed(() => step.value === 'preview' ? planItems.value : (importResult.value?.items || []))
const importableCount = computed(() => planItems.value.filter(item => item.status === '已匹配').length)
const failedCount = computed(() => step.value === 'preview'
    ? planItems.value.filter(item => item.status !== '已匹配').length
    : (importResult.value?.failed || 0))
const canPreview = computed(() => !!aniId.value && (mode.value === 'assrt' || pendingFiles.value.length > 0))

const openDialog = async () => {
  dialogVisible.value = true
  step.value = 'form'
  mode.value = 'upload'
  resetSelection()
  importResult.value = null
  if (!aniOptions.value.length) {
    await loadAniOptions()
  }
}

/**
 * 清空射手网搜索/选择相关的临时状态
 */
const resetSelection = () => {
  planItems.value = []
  planId.value = ''
  searchId.value = ''
  searchKeyword.value = ''
  candidates.value = []
  selectedIndex.value = -1
}

const resetDialog = () => {
  step.value = 'form'
  pendingFiles.value = []
  resetSelection()
  importResult.value = null
  dragOver.value = false
}

const loadAniOptions = async () => {
  try {
    const res = await http.listAni()
    aniOptions.value = (res?.data?.weekList || []).flatMap(w => w.items || [])
  } catch (e) {
    aniOptions.value = []
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

const buildFormData = () => {
  const formData = new FormData()
  formData.append('aniId', aniId.value)
  pendingFiles.value.forEach(file => formData.append('files', file))
  return formData
}

/**
 * 主按钮统一入口，按「当前模式 + 当前步骤」分发：
 * - 上传模式：预览匹配结果
 * - 射手网模式：form 步 → 搜索候选；select 步 → 下载选中候选并预览
 */
const onPrimary = async () => {
  if (mode.value === 'upload') {
    await doPreviewUpload()
  } else if (step.value === 'form') {
    await doSearch()
  } else {
    await doFetchSelected()
  }
}

/**
 * 上传模式第一步：只做匹配预览（不写盘），把「改名前 / 改名后 / 对应的视频」交给用户确认。
 */
const doPreviewUpload = async () => {
  if (!aniId.value) {
    ElMessage.warning('请先选择字幕对应的订阅')
    return
  }
  if (!pendingFiles.value.length) {
    ElMessage.warning('请先选择字幕文件')
    return
  }
  previewing.value = true
  resetSelection()
  importResult.value = null
  try {
    const res = await http.subtitleImportPreview(buildFormData())
    if (res?.code !== 200) {
      ElMessage.error(res?.message || '预览失败')
      return
    }
    planItems.value = res.data?.items || []
    if (!planItems.value.length) {
      ElMessage.warning('没有解析出可导入的字幕')
      return
    }
    step.value = 'preview'
  } catch (e) {
    ElMessage.error(e?.message || '预览失败')
  } finally {
    previewing.value = false
  }
}

/**
 * 射手网第一步：以英文标题<b>单次</b>搜索，返回候选供用户挑选（不下载、不写盘）。
 */
const doSearch = async () => {
  if (!aniId.value) {
    ElMessage.warning('请先选择字幕对应的订阅')
    return
  }
  previewing.value = true
  resetSelection()
  importResult.value = null
  try {
    const res = await http.subtitleAssrtSearch(aniId.value)
    if (res?.code !== 200) {
      ElMessage.error(res?.message || '搜索失败')
      return
    }
    candidates.value = res.data?.candidates || []
    searchId.value = res.data?.searchId || ''
    searchKeyword.value = res.data?.keyword || ''
    if (!candidates.value.length) {
      ElMessage.warning(`未搜索到候选字幕（关键词：${searchKeyword.value || '未知'}），可换用「手动上传字幕」`)
      return
    }
    step.value = 'select'
  } catch (e) {
    ElMessage.error(e?.message || '搜索失败')
  } finally {
    previewing.value = false
  }
}

/**
 * 射手网第二步：下载用户选中的候选并生成写入预览（不写盘）。
 */
const doFetchSelected = async () => {
  if (selectedIndex.value < 0) {
    ElMessage.warning('请先选中一条候选字幕')
    return
  }
  previewing.value = true
  planItems.value = []
  planId.value = ''
  importResult.value = null
  try {
    const res = await http.subtitleFetchPreview(aniId.value, searchId.value, selectedIndex.value)
    if (res?.code !== 200) {
      ElMessage.error(res?.message || '获取字幕失败')
      return
    }
    planItems.value = res.data?.items || []
    planId.value = res.data?.planId || ''
    if (!planItems.value.length) {
      ElMessage.warning('该订阅没有缺失字幕的视频')
      return
    }
    step.value = 'preview'
  } catch (e) {
    ElMessage.error(e?.message || '获取字幕失败')
  } finally {
    previewing.value = false
  }
}

/** 点击候选行即选中 */
const onRowClick = (row) => {
  selectedIndex.value = row.index
}

/**
 * 第二步：用户确认后才真正写入。
 * 先弹一次确认框（二次确认），再执行写入，避免误点直接落盘。
 */
const doConfirm = async () => {
  const source = mode.value === 'assrt' ? '射手网' : '本地上传'
  try {
    await ElMessageBox.confirm(
        `即将写入 ${importableCount.value} 个字幕文件（来源：${source}）。覆盖同名文件前会自动备份到视频同目录的 sub_bak/，确认继续？`,
        '确认导入字幕',
        {type: 'warning', confirmButtonText: '确认导入', cancelButtonText: '再检查一下'}
    )
  } catch (e) {
    return
  }

  importing.value = true
  try {
    let result
    if (mode.value === 'upload') {
      const res = await http.subtitleImport(buildFormData())
      if (res?.code !== 200) {
        ElMessage.error(res?.message || '导入失败')
        return
      }
      result = res.data
    } else {
      const res = await http.subtitleFetch(planId.value)
      result = res.data
    }
    importResult.value = result || {items: [], success: 0, failed: 0}
    step.value = 'done'
    const success = importResult.value.success || 0
    const failed = importResult.value.failed || 0
    if (success && !failed) {
      ElMessage.success(`导入完成，成功 ${success} 个`)
    } else if (success) {
      ElMessage.warning(`导入完成：成功 ${success}，失败 ${failed}`)
    } else {
      ElMessage.error('未导入任何字幕，请查看下方说明')
    }
    if (mode.value === 'upload') {
      pendingFiles.value = []
    }
    load()
  } catch (e) {
    ElMessage.error(e?.message || '导入失败')
  } finally {
    importing.value = false
  }
}

/** 从预览返回：射手网模式回到候选选择（候选还在），上传模式回到表单 */
const backFromPreview = () => {
  step.value = mode.value === 'assrt' && candidates.value.length ? 'select' : 'form'
  planItems.value = []
  planId.value = ''
  importResult.value = null
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

.mode-switch {
  margin-bottom: 16px;
}

.ani-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.ani-meta-title {
  flex: 1 1 100%;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
