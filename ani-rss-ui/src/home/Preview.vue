<template class="items">
  <el-dialog v-model="dialogVisible" center class="el-dialog-auto-width" title="预览">
    <div class="items-content" v-loading="loading">
      <div class="items-select-container">
        <el-select v-model:model-value="select" class="items-select" @change="selectChange">
          <el-option v-for="item in selectItems"
                     :key="item.label"
                     :label="item.label"
                     :value="item.label"/>
        </el-select>
        <div class="items-spacer"/>
        <el-input v-model:model-value="data.downloadPath" readonly/>
      </div>
      <div class="items-button-container">
        <el-button bg text :disabled="!selectViews.length" @click="allowDownload" icon="Check" type="primary">允许下载
        </el-button>
        <el-button bg text :disabled="!selectViews.length" @click="notDownload" icon="Close">禁止下载</el-button>
        <popconfirm @confirm="delTorrent" :title="`删除${selectViews.filter(it => it['hasDownloaded']).length}个种子缓存?`">
          <template #reference>
            <el-button icon="Remove" bg text type="danger"
                       :disabled="!selectViews.filter(it => it.local).length">
              删除种子
            </el-button>
          </template>
        </popconfirm>
        <popconfirm @confirm="forceDownload" :title="`强制下载${selectViews.length}项? 将删除已有文件后重新下载`">
          <template #reference>
            <el-button icon="RefreshRight" bg text type="warning" :disabled="!selectViews.length"
                       :loading="forceDownloading">
              强制下载
            </el-button>
          </template>
        </popconfirm>
      </div>
      <div class="items-table-container">
        <el-table :data="showItems" height="500"
                  size="small"
                  row-key="infoHash"
                  :tree-props="{children: 'children', hasChildren: 'hasChildren'}"
                  @selection-change="handleSelectionChange"
                  scrollbar-always-on
                  stripe>
          <el-table-column type="selection" width="55" fixed/>
          <el-table-column label="是否下载" min-width="100">
            <template #default="it">
              <el-tag v-if="props.ani['notDownload'].includes(it.row['episode'])" type="info">否</el-tag>
              <el-tag v-else>是</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="本地存在" min-width="100">
            <template #default="it">
              <el-tag v-if="!it.row.local" type="info">否</el-tag>
              <el-tag v-else>是</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="主RSS" min-width="80">
            <template #default="it">
              <el-tag v-if="!it.row['master']" type="info">否</el-tag>
              <el-tag v-else>是</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="字幕组" min-width="100">
            <template #default="it">
              <el-text size="small" truncated>
                {{ it.row.subgroup }}
              </el-text>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="80">
            <template #default="it">
              <el-tag v-if="it.row['children']" type="warning" size="small">合集</el-tag>
              <el-tag v-else-if="it.row['episodeRange']" size="small">子集</el-tag>
              <el-tag v-else type="info" size="small">单集</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="标题" min-width="400">
            <template #default="it">
              <el-text size="small">
                <template v-if="it.row['children']">
                  {{ it.row.title }} (共 {{ it.row['children'].length }} 集)
                </template>
                <template v-else-if="it.row['episodeRange']">
                  子集 {{ String(it.row['episode']).padStart(2, '0') }}
                </template>
                <template v-else>
                  {{ it.row.title }}
                </template>
              </el-text>
            </template>
          </el-table-column>
          <el-table-column label="重命名" min-width="280">
            <template #default="it">
              <el-text size="small">
                {{ it.row['reName'] }}
              </el-text>
            </template>
          </el-table-column>
          <el-table-column label="发布时间" min-width="120">
            <template #default="it">
              <el-text size="small">
                {{ it.row['pubDate'] }}
              </el-text>
            </template>
          </el-table-column>
          <el-table-column label="InfoHash" min-width="200">
            <template #default="it">
              <el-text size="small">
                {{ it.row['infoHash'] }}
              </el-text>
            </template>
          </el-table-column>
          <el-table-column prop="formatSize" label="大小" width="120"/>
          <el-table-column label="种子" width="90">
            <template #default="it">
              <el-button size="small" bg text @click="copy(it.row['torrent'])">复制</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="data['omitList'] && data['omitList'].length">
          <el-alert :title="`缺少集数: ${data['omitList'].slice(0,10).join('、')}`" type="warning" show-icon
                    :closable="false"/>
        </div>
        <div v-if="data.healthScore != null" style="margin-top: 8px;">
          <el-alert
              :type="healthAlertType"
              show-icon
              :closable="false"
              :title="`运维健康 ${data.healthScore}（${healthLevelText}）${healthReasonText}`"
          />
        </div>
        <div v-if="data.washPreview && data.washPreview.length" class="wash-preview">
          <div class="wash-preview-title">洗版预览（将删除）</div>
          <div v-for="(row, idx) in data.washPreview" :key="idx" class="wash-preview-row">
            <el-tag size="small" :type="row.kind === 'torrent' ? 'warning' : 'info'">{{ row.kind === 'torrent' ? '种子' : '文件' }}</el-tag>
            <span class="wash-name" :title="row.name">{{ row.name }}</span>
            <span class="wash-reason">{{ row.reason }}</span>
          </div>
        </div>
      </div>
    </div>
    <div class="flex items-footer">
      <span>共 {{ showItems.length }} 项</span>
      <el-button bg text @click="dialogVisible = false" icon="Close">关闭</el-button>
    </div>
  </el-dialog>
</template>

<script setup>
import {computed, ref} from "vue";
import {ElMessage} from "element-plus";
import Popconfirm from "@/other/Popconfirm.vue";
import * as http from "@/js/http.js";

let selectViews = ref([])
let handleSelectionChange = (selectViewsValue) => {
  selectViews.value = selectViewsValue
}

const select = ref('全部')
const selectItems = ref([
  {
    label: '全部',
    fun: () => true
  },
  {
    label: '本地已存在',
    fun: it => it['hasDownloaded']
  },
  {
    label: '本地不存在',
    fun: it => !it['hasDownloaded']
  }
])
const dialogVisible = ref(false)
const data = ref({
  'downloadPath': '',
  'items': [],
  'omitList': [],
  washPreview: [],
  healthScore: null,
  healthLevel: null,
  healthReasons: []
})
const loading = ref(true)

const healthLevelText = computed(() => {
  const l = data.value.healthLevel
  if (l === 'good') return '健康'
  if (l === 'warn') return '注意'
  if (l === 'bad') return '异常'
  if (l === 'paused') return '停用'
  if (l === 'completed') return '完结'
  return l || '未知'
})
const healthAlertType = computed(() => {
  const l = data.value.healthLevel
  if (l === 'good' || l === 'completed') return 'success'
  if (l === 'warn') return 'warning'
  if (l === 'bad') return 'error'
  return 'info'
})
const healthReasonText = computed(() => {
  const rs = data.value.healthReasons
  if (!Array.isArray(rs) || !rs.length) return ''
  return ' · ' + rs.slice(0, 3).join('；')
})

let copy = (v) => {
  const input = document.createElement('input');
  input.value = v
  document.body.appendChild(input);
  input.select();
  document.execCommand('copy');
  document.body.removeChild(input);
  ElMessage.success('已复制')
}

let show = () => {
  data.value.downloadPath = ''
  data.value.items = []
  select.value = '全部'
  dialogVisible.value = true
  load()
}

let selectChange = () => {
  showItems.value = data.value.items.filter(selectItems.value.filter(it => it.label === select.value)[0].fun)
}

let showItems = ref([])

let load = () => {
  loading.value = true
  http.previewAni(props.ani)
      .then(res => {
        data.value = res.data
        selectChange()
      })
      .finally(() => {
        loading.value = false
      })
}

let delTorrent = () => {
  let infoHash = selectViews.value.filter(it => it['hasDownloaded']).map(it => it['infoHash']).join(",")
  http.deleteTorrent(props.ani.id, infoHash)
      .then(res => {
        ElMessage.success(res.message)
        load()
      })
}

let forceDownloading = ref(false)
let forceDownload = () => {
  let infoHashes = selectViews.value.map(it => it['infoHash']).filter(Boolean)
  if (!infoHashes.length) {
    ElMessage.warning('选中条目缺少 InfoHash，无法强制下载')
    return
  }
  forceDownloading.value = true
  http.forceDownload(props.ani, infoHashes)
      .then(res => {
        ElMessage.success(res.message)
        load()
      })
      .catch(err => {
        ElMessage.error(err?.message || '强制下载失败')
      })
      .finally(() => {
        forceDownloading.value = false
      })
}

let notDownload = () => {
  props.ani['notDownload'].push(...selectViews.value.map(it => it['episode']))
  props.ani['notDownload'] = Array.from(new Set(props.ani['notDownload']))
}

let allowDownload = () => {
  props.ani['notDownload'] = props.ani['notDownload'].filter(episode => !selectViews.value.map(it => it['episode']).includes(episode))
}

defineExpose({show})
let props = defineProps(['ani'])
</script>

<style scoped>
.items-content {
  width: 100%;
}

.wash-preview {
  margin-top: 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--el-fill-color-light);
  max-height: 180px;
  overflow: auto;
}

.wash-preview-title {
  font-weight: 600;
  margin-bottom: 8px;
}

.wash-preview-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 12px;
}

.wash-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wash-reason {
  color: var(--el-text-color-secondary);
}

.items-select-container {
  margin: 4px 0;
  display: flex;
}

.items-select {
  max-width: 120px;
}

.items-spacer {
  width: 4px;
}

.items-button-container {
  width: 100%;
  display: flex;
  justify-content: end;
  margin-top: 8px;
}

.items-table-container {
  padding: 0 12px;
}

.items-footer {
  margin-top: 12px;
  justify-content: space-between;
}
</style>
