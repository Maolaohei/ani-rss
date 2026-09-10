<template>
  <el-dialog v-model="batchAdditionDialogVisible" align-center center
             :title="batchAdding ? '正在批量添加订阅' : '批量添加结果'"
             width="500"
             :close-on-click-modal="!batchAdding"
             :close-on-press-escape="!batchAdding"
             :show-close="!batchAdding">
    <div>
      <el-progress :percentage="batchPercent" :status="batchProgressStatus"/>
    </div>
    <div>
      {{ batchAdditionNum }} / {{ batchTotal }}
    </div>
    <div v-if="!batchAdding && batchResult.length" class="batch-result">
      <el-text type="success" size="small">成功 {{ batchSuccessCount }} 条</el-text>
      <div v-if="batchFailedCount">
        <el-text type="danger" size="small">失败 {{ batchFailedCount }} 条：</el-text>
        <ul class="batch-failed-list">
          <li v-for="(f, i) in batchResult.filter(it => !it.ok)" :key="i">
            <el-text size="small">{{ f.name }}：{{ f.reason }}</el-text>
          </li>
        </ul>
      </div>
      <el-text v-if="batchCanceled" type="warning" size="small">已取消，剩余条目未添加</el-text>
    </div>
    <template #footer>
      <el-button v-if="batchAdding" type="danger" text bg icon="Close" @click="cancelBatch">取消剩余</el-button>
      <el-button v-else type="primary" text bg icon="Check"
                 @click="batchAdditionDialogVisible = false">关闭
      </el-button>
    </template>
  </el-dialog>
  <el-dialog v-model="matchDialogVisible" align-center center title="匹配" width="auto">
    <el-alert
        v-if="matchExistsHint"
        class="match-exists-alert"
        type="warning"
        show-icon
        :closable="false"
        title="该番剧已订阅：继续添加会覆盖同名订阅，如需追加字幕组请到「编辑订阅 → 备用 RSS」"
    />
    <div class="match-content">
      <el-radio-group v-model="addAni.match">
        <div v-for="regexItems in regexList" class="match-item">
          <el-radio :label="JSON.stringify(regexItems)"
                    :value="JSON.stringify(regexItems.map(it => it.regex))">
            <el-tag v-if="regexItems.length" v-for="regexItem in regexItems" class="tag-margin">
              {{ regexItem.label }}
            </el-tag>
            <el-tag v-else type="success">全部</el-tag>
          </el-radio>
        </div>
      </el-radio-group>
    </div>
    <el-text v-if="matchSummary" class="mx-1" size="small">
      {{ matchSummary }}
    </el-text>
    <div class="dialog-footer">
      <el-button icon="Check" @click="confirmMatch" text bg>确定
      </el-button>
    </div>
  </el-dialog>
  <el-dialog v-model="dialogVisible" center title="AniBT">
    <el-checkbox-group v-model="rssList">
      <div class="content-wrapper">
        <div class="search-section">
          <div class="search-header">
            <el-input v-model:model-value="title" clearable placeholder="请输入搜索标题"
                      prefix-icon="Search"
                      @clear="()=>{
            title = ''
            search()
          }"
                      @keyup.enter="search()"></el-input>
            <div class="spacer"></div>
            <el-button :loading="searchLoading" bg icon="Search" text @click="search()">搜索</el-button>
          </div>
          <div class="flex season-selector">
            <el-select v-model="season" class="season-select"
                       :disabled="loading"
                       @change="change">
              <el-option v-for="itemSeason in data.seasons" :key="itemSeason"
                         :label="itemSeason" :value="itemSeason">
              </el-option>
            </el-select>
            <el-button :disabled="rssList.length < 1" bg icon="Plus" text @click="batchAddition">批量添加</el-button>
          </div>
        </div>
        <div v-loading="loading" class="scroll-container">
          <el-scrollbar>
            <el-collapse v-model="activeName">
              <el-collapse-item v-for="item in data.items" :name="item.weekdayLabel">
                <template #title>
                  <span style="margin-left: 4px;font-weight: bold;">
                    {{ item.weekdayLabel }}
                  </span>
                </template>
                <div class="collapse-content">
                  <el-collapse accordion @change="collapseChange">
                    <el-collapse-item v-for="anime in item.animes" :name="anime['bgmId']">
                      <template #title>
                        <div class="flex collapse-title">
                          <img :src="proxyImage(anime['cover'])" class="cover" v-if="anime.cover"
                               @click.stop="open(`https://anibt.net/anime/${anime['bgmId']}`)">
                          <div class="flex collapse-title">
                            <el-text :truncated="false" line-clamp="1" size="small"
                                     class="title-text">
                              {{ anime.title.primary }}
                            </el-text>
                          </div>
                          <div v-if="anime['rating'] > 0" class="score-margin">
                            <h4 class="score-color">
                              {{ anime['rating'].toFixed(1) }}
                            </h4>
                          </div>
                          <el-badge v-if="anime['exists']" class="item badge-margin" type="primary"
                                    value="已订阅"/>
                        </div>
                      </template>
                      <div v-if="selectName === anime.bgmId" v-loading="groupLoading"
                           class="group-content">
                        <el-alert
                            v-if="anime['exists']"
                            class="group-exists-alert"
                            type="info"
                            show-icon
                            :closable="false"
                            title="该番剧已订阅：再加字幕组请到「编辑订阅 → 备用 RSS」管理"
                        />
                        <el-collapse accordion>
                          <el-collapse-item v-for="group in groups[anime.bgmId]">
                            <template #title>
                              <div class="group-title-wrapper">
                                <div class="group-checkbox-wrapper">
                                  <el-checkbox :value="JSON.stringify(group)" class="checkbox-margin" @click.stop/>
                                </div>
                                <div class="group-label">
                                  <el-text style="max-width: 100px;" truncated>{{ group.name }}</el-text>
                                  &nbsp;
                                  <el-text class="mx-1" size="small">{{ group['updateDay'] }}</el-text>
                                  <el-text v-if="(group.items || []).length" class="mx-1" size="small" type="info">
                                    · 最近 {{ group.items.length }} 条
                                  </el-text>
                                </div>
                                <div v-if="showTag()">
                                  <el-tag v-for="tag in group['groupRegex']['tags']"
                                          class="tag-margin">
                                    {{ tag }}
                                  </el-tag>
                                </div>
                                <div class="group-action">
                                  <el-button bg icon="Plus" @click.stop="callback(group, anime['exists'])">
                                    添加
                                  </el-button>
                                </div>
                              </div>
                            </template>
                            <div class="group-items">
                              <div v-for="ti in group.items" class="item-margin">
                                <el-card shadow="never">
                                  <div>
                                    <h5>
                                      {{ ti.title }}
                                    </h5>
                                    <div class="item-footer">
                                      <p>
                                        {{ ti['formatSize'] }}
                                        {{ formatTime(ti['publishedAt']) }}
                                      </p>
                                      <div>
                                        <el-button :icon="DocumentCopy" bg text @click="copy(ti['magnet'])"/>
                                      </div>
                                    </div>
                                  </div>
                                </el-card>
                              </div>
                            </div>
                          </el-collapse-item>
                        </el-collapse>
                      </div>
                    </el-collapse-item>
                  </el-collapse>
                </div>
              </el-collapse-item>
            </el-collapse>
          </el-scrollbar>
        </div>
      </div>
    </el-checkbox-group>
  </el-dialog>
</template>

<script setup>
import {computed, ref} from "vue";
import {ElMessage, ElText} from "element-plus";
import {DocumentCopy} from "@element-plus/icons-vue";
import * as http from "@/js/http.js";
import formatTime from "../js/format-time.js";
import {proxyImage, copyText} from "@/js/global.js";

// 批量添加订阅
let rssList = ref([]);

let groupLoading = ref(false)
let activeName = ref("")
let dialogVisible = ref(false)
let loading = ref(false)
let data = ref({
  'seasons': [],
  'items': []
})

let season = ref('')

let show = (bgmUrl = '') => {
  dialogVisible.value = true
  // 重置搜索词与季度，避免上次的关键词/季度被静默带入
  title.value = ''
  season.value = ''
  groups.value = {}
  data.value = {
    'seasons': [],
    'items': []
  }
  rssList.value = []
  search(bgmUrl)
}

let title = ref('')
let searchLoading = ref(false)

let search = async (bgmUrl = '') => {
  loading.value = true
  searchLoading.value = true
  return http.aniBT(season.value, bgmUrl, title.value)
      .then(res => {
        let {requestedSeason, availableSeasons, byWeekday} = res.data;

        season.value = requestedSeason
        data.value.seasons = availableSeasons
        data.value.items = byWeekday
        if (byWeekday.length) {
          activeName.value = byWeekday[0].weekdayLabel
        }
      })
      .catch(err => {
        ElMessage.error(err?.message || '获取 AniBT 数据失败')
      })
      .finally(() => {
        loading.value = false
        searchLoading.value = false
      });
}

let change = (v) => {
  let body = data.value.seasons.filter(item => item === v)
  if (body.length) {
    search()
  }
}

let selectName = ref('')
let groups = ref({})

let collapseChange = (v) => {
  if (!v) {
    return
  }
  selectName.value = v
  if (groups.value[v]) {
    return;
  }
  groupLoading.value = true
  http.aniBTGroup(v)
      .then(res => {
        groups.value[v] = res.data
      })
      .catch(err => {
        ElMessage.error(err?.message || '获取字幕组失败')
      })
      .finally(() => {
        groupLoading.value = false
      })
}


let matchDialogVisible = ref(false)

let addAni = ref({
  'bgmUrl': '',
  'url': '',
  'match': '',
  'group': ''
})

let regexList = ref([])
/** 该番剧是否已订阅（由 picker 返回的 exists 标记） */
let matchExistsHint = ref(false)
/** 匹配弹窗里的可下载摘要 */
let matchSummary = ref('')

let callback = (v, exists = false) => {
  let {bgmId, rss, name, items} = v
  let regexItems = v.groupRegex && v.groupRegex.regexList ? v.groupRegex.regexList : []
  regexList.value = JSON.parse(JSON.stringify(regexItems))

  addAni.value.bgmUrl = `https://bgm.tv/subject/${bgmId}`
  addAni.value.url = rss
  addAni.value.subgroup = name
  addAni.value.match = '[]'

  matchExistsHint.value = !!exists
  let count = Array.isArray(items) ? items.length : 0
  matchSummary.value = count
      ? `该字幕组最近有 ${count} 条更新；添加后可在「订阅列表 → 预览」查看将下载的完整集数`
      : ''

  regexList.value.push([])
  matchDialogVisible.value = true
}

let confirmMatch = () => {
  emit('callback', addAni.value)
  dialogVisible.value = false
  matchDialogVisible.value = false
}

let showTag = () => {
  return window.innerWidth > 900;
}

let open = url => {
  window.open(url);
}

defineExpose({show})

let emit = defineEmits(['callback'])

let batchAdditionNum = ref(0)
let batchTotal = ref(0)
let batchAdditionDialogVisible = ref(false)
let batchAdding = ref(false)
let batchCanceled = ref(false)
let batchResult = ref([])

const batchPercent = computed(() => {
  if (!batchTotal.value) {
    return 0
  }
  return Number.parseInt((batchAdditionNum.value / batchTotal.value) * 100.0)
})
const batchProgressStatus = computed(() => {
  if (batchAdding.value || batchCanceled.value) {
    return ''
  }
  return batchFailedCount.value ? 'exception' : 'success'
})
const batchSuccessCount = computed(() => batchResult.value.filter(it => it.ok).length)
const batchFailedCount = computed(() => batchResult.value.filter(it => !it.ok).length)

const cancelBatch = () => {
  batchCanceled.value = true
}

const batchAddition = async () => {
  if (!rssList.value.length) {
    ElMessage.warning('请先勾选要添加的字幕组')
    return
  }

  let map
  try {
    map = rssList.value.reduce((acc, item) => {
      let parsed = JSON.parse(item)
      let bangumiId = getBgmId(parsed['rss'])
      if (!acc[bangumiId]) {
        acc[bangumiId] = []
      }
      acc[bangumiId].push(parsed)
      return acc
    }, {})
  } catch (e) {
    ElMessage.error('所选条目的数据已损坏，请关闭弹窗重新搜索后再试')
    return
  }

  let grouped = Object.values(map)
  let totalCount = grouped.reduce((sum, g) => sum + g.length, 0)

  batchAdditionNum.value = 0
  batchTotal.value = totalCount
  batchResult.value = []
  batchCanceled.value = false
  batchAdding.value = true
  batchAdditionDialogVisible.value = true

  for (let item of grouped) {
    if (batchCanceled.value) {
      break
    }
    let name = item[0].name || '未知字幕组'
    try {
      let ani = {
        "url": item[0]['rss'],
        "season": 1,
        "offset": 0,
        "title": "",
        "exclude": [],
        "totalEpisodeNumber": 0,
        "match": [],
        "type": "ani-bt",
        "bgmUrl": `https://bgm.tv/subject/${item[0].bgmId}`,
        "subgroup": item[0].name
      }

      ani = (await http.rssToAni(ani)).data
      if (item.length > 1) {
        ani.standbyRssList = item.slice(1)
            .map(o => {
              return {
                label: o.name,
                url: o['rss'],
                offset: 0
              }
            })
      }
      await http.addAni(ani)
      batchResult.value.push({ok: true, name})
    } catch (e) {
      // 单条失败不再中断整批，逐条记录失败原因
      batchResult.value.push({ok: false, name, reason: e?.message || String(e)})
    }
    batchAdditionNum.value += item.length
  }

  batchAdding.value = false

  if (!batchCanceled.value) {
    if (batchFailedCount.value) {
      ElMessage.warning(`批量添加完成：成功 ${batchSuccessCount.value} 条，失败 ${batchFailedCount.value} 条`)
    } else {
      ElMessage.success(`批量添加完成：成功 ${batchSuccessCount.value} 条`)
    }
  }

  // 刷新 AniBT 列表以更新"已订阅"标记（此前误调用不存在的 list()，导致成功后反报错且主列表不刷新）
  search()
  window.$reLoadList()
}

let getBgmId = (url) => {
  try {
    return new URL(url).searchParams.get('bgmId');
  } catch (e) {
    return null
  }
}

let copy = (v) => {
  copyText(v)
}

</script>

<style scoped>
.el-collapse {
  --el-collapse-header-height: 55px;
}

.match-item {
  margin-right: 12px;
  display: inline;
}

.tag-margin {
  margin-right: 4px;
}

.dialog-footer {
  display: flex;
  width: 100%;
  justify-content: end;
}

.content-wrapper {
  min-height: 300px;
}

.search-section {
  margin: 4px;
}

.search-header {
  display: flex;
  justify-content: space-between;
}

.season-selector {
  margin-top: 4px;
  width: 100%;
  justify-content: space-between;
}

.season-select {
  max-width: 140px;
}

.scroll-container {
  margin: 8px 0 4px 0;
  height: 600px;
}

.collapse-content {
  margin-left: 15px;
}

.collapse-title {
  align-items: center;
}

.title-text {
  margin-left: 6px;
  line-height: 1.6;
  font-weight: bold;
}

.score-margin {
  margin-left: 4px;
}

.score-color {
  color: #E800A4;
}

.badge-margin {
  margin-left: 4px;
}

.group-content {
  margin-left: 15px;
  min-height: 50px;
}

.group-title-wrapper {
  width: 100%;
  display: flex;
  justify-content: space-between;
}

.group-checkbox-wrapper {
  height: 100%;
}

.checkbox-margin {
  margin-right: 8px;
}

.group-label {
  display: flex;
  align-items: center;
  flex: 1;
  text-align: start;
}

.group-action {
  display: flex;
  align-items: center;
  margin-right: 14px;
  margin-left: 4px;
}

.group-items {
  margin-left: 15px;
}

.item-margin {
  margin-bottom: 4px;
}

.item-footer {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.cover {
  border-radius: var(--el-border-radius-base);
  cursor: pointer;
  width: 45px;
  height: 45px;
  object-fit: cover;
  flex-shrink: 0;
}

.match-content {
  max-width: 500px;
  min-width: 200px;
  margin-bottom: 4px;
}

.batch-result {
  margin-top: 10px;
}

.batch-failed-list {
  margin: 4px 0 0 18px;
  max-height: 160px;
  overflow: auto;
}

.match-exists-alert {
  margin-bottom: 10px;
}

.group-exists-alert {
  margin: 4px 0 6px 0;
}
</style>
