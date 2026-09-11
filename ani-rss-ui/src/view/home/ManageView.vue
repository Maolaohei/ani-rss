<template>
  <ImportAniView ref="importAniRef" @callback="getList"/>
  <DelAniView ref="delAniRef" @callback="reLoadList"/>
  <el-dialog v-model="dialogVisible" center title="管理" @closed="stopBatchPolling">
    <div class="manage-content" v-loading="loading">
      <div class="manage-header">
        <div class="auto-flex mange-toolbar">
          <div>
            <el-input
                v-model="text"
                @input="changeFilterList"
                @clear="changeFilterList"
                clearable
                placeholder="搜索"
                prefix-icon="Search"
                style="width: 180px;"
            />
          </div>
          <div class="select-width">
            <el-select
                v-model:model-value="releaseDate"
                clearable
                @change="changeFilterList">
              <el-option v-for="it in releaseDateList"
                         :key="it" :label="it" :value="it"
              />
            </el-select>
          </div>
          <div class="select-width">
            <el-select v-model:model-value="selectFilter"
                       @change="changeFilterList">
              <el-option v-for="filter in selectFilters"
                         :key="filter.label"
                         :label="filter.label"
                         :value="filter.label"/>
            </el-select>
          </div>
        </div>
        <div class="manage-actions">
          <el-button-group class="manage-selection-tools">
            <el-button size="small" bg text @click="selectAll">全选</el-button>
            <el-button size="small" bg text @click="invertSelection">反选</el-button>
            <el-button size="small" bg text @click="clearSelection">清空</el-button>
          </el-button-group>
          <el-dropdown trigger="click">
            <el-button bg text>
              <el-icon class="el-icon--left">
                <Operation/>
              </el-icon>
              批量操作（{{ selectList.length }}）
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item :disabled="!selectList.length" @click="updateTotalEpisodeNumber(false)">
                  <el-text>
                    <el-icon>
                      <RefreshRight/>
                    </el-icon>
                    更新总集数
                  </el-text>
                </el-dropdown-item>
                <el-dropdown-item :disabled="!selectList.length" @click="updateTotalEpisodeNumber(true)">
                  <el-text type="warning">
                    <el-icon>
                      <Refresh/>
                    </el-icon>
                    强制更新总集数（忽略缓存）
                  </el-text>
                </el-dropdown-item>
                <el-dropdown-item divided :disabled="!selectList.length" @click="batchScrape(false)">
                  <el-text>
                    <el-icon>
                      <RefreshRight/>
                    </el-icon>
                    刮削
                  </el-text>
                </el-dropdown-item>
                <el-dropdown-item :disabled="!selectList.length" @click="batchScrape(true)">
                  <el-text type="warning">
                    <el-icon>
                      <Refresh/>
                    </el-icon>
                    强制刮削（覆盖已有数据）
                  </el-text>
                </el-dropdown-item>
                <el-dropdown-item divided :disabled="!selectList.length" @click="batchEnable(true)">
                  <el-text type="primary">
                    <el-icon>
                      <CircleCheck/>
                    </el-icon>
                    启用
                  </el-text>
                </el-dropdown-item>
                <el-dropdown-item :disabled="!selectList.length" @click="batchEnable(false)">
                  <el-text type="warning">
                    <el-icon>
                      <CircleClose/>
                    </el-icon>
                    禁用
                  </el-text>
                </el-dropdown-item>
                <el-dropdown-item divided @click="importAniRef?.show">
                  <el-text>
                    <el-icon>
                      <Download/>
                    </el-icon>
                    导入订阅
                  </el-text>
                </el-dropdown-item>
                <el-dropdown-item :disabled="!selectList.length" @click="exportData">
                  <el-text>
                    <el-icon>
                      <Upload/>
                    </el-icon>
                    导出选中
                  </el-text>
                </el-dropdown-item>
                <el-dropdown-item divided :disabled="!selectList.length" @click="delSelected">
                  <el-text type="danger">
                    <el-icon>
                      <Remove/>
                    </el-icon>
                    删除选中
                  </el-text>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
      <el-table
          ref="tableRef"
          size="small"
          row-key="id"
          @selection-change="handleSelectionChange"
          v-model:data="searchList"
          height="400px"
          stripe
      >
        <el-table-column type="selection" width="55" fixed :reserve-selection="true"/>
        <el-table-column label="标题" width="200" fixed>
          <template #default="it">
            <el-text :line-clamp="2" size="small">
              {{ searchList[it.$index].title }}
            </el-text>
          </template>
        </el-table-column>
        <el-table-column label="季" prop="season" width="50"/>
        <el-table-column label="字幕组" width="100">
          <template #default="it">
            <el-text size="small" truncated>
              {{ searchList[it.$index].subgroup }}
            </el-text>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="70">
          <template #default="it">
            <el-switch
                :model-value="searchList[it.$index].enable"
                :loading="searchList[it.$index].enableLoading"
                @change="value => toggleEnable(value, searchList[it.$index])"/>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="100">
          <template #default="it">
            <el-tag type="warning">
              {{ searchList[it.$index]['currentEpisodeNumber'] }} /
              {{ searchList[it.$index]['totalEpisodeNumber'] ? searchList[it.$index]['totalEpisodeNumber'] : '*' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="漏集" width="70">
          <template #default="it">
            <el-tag v-if="searchList[it.$index]['omitCount'] > 0" type="danger" size="small">
              {{ searchList[it.$index]['omitCount'] }}
            </el-tag>
            <el-text v-else size="small" type="info">-</el-text>
          </template>
        </el-table-column>
        <el-table-column label="最近更新" width="120">
          <template #default="it">
            <el-text size="small" type="info">
              {{ searchList[it.$index]['lastDownloadFormat'] || '暂无' }}
            </el-text>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="it">
            <el-tag type="danger" v-if="searchList[it.$index].ova">
              ova
            </el-tag>
            <el-tag type="danger" v-else>
              tv
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="URL" width="300">
          <template #default="it">
            <el-text :line-clamp="2" size="small">
              {{ searchList[it.$index].url }}
            </el-text>
          </template>
        </el-table-column>
      </el-table>
      <div>
        <p class="manage-count">共 {{ searchList.length }} 项</p>
      </div>
    </div>
  </el-dialog>
</template>
<script setup>
import {onUnmounted, ref} from "vue";
import {ElMessage, ElText} from "element-plus";
import DelAniView from "./DelAniView.vue";
import ImportAniView from "@/view/home/ImportAniView.vue";
import {
  CircleCheck,
  CircleClose,
  Download,
  Operation,
  Refresh,
  RefreshRight,
  Remove,
  Upload
} from "@element-plus/icons-vue";
import * as http from "@/js/http.js";
import formatTime from "@/js/format-time.js";

let releaseDateList = ref([])

let delAniRef = ref()
let importAniRef = ref()

let selectFilter = ref('全部')

let selectFilters = ref([
  {
    label: '全部',
    fun: () => true
  },
  {
    label: '已启用',
    fun: it => it.enable
  },
  {
    label: '未启用',
    fun: it => !it.enable
  },
])

let searchList = ref([])

let text = ref('')

let changeFilterList = () => {
  const keyword = text.value.trim().toLowerCase()
  const filter = item => {
    if (!keyword) {
      return true
    }
    let {title, pinyin, pinyinInitials, subgroup} = item
    return (title || '').toLowerCase().indexOf(keyword) > -1 ||
        (pinyin || '').toLowerCase().indexOf(keyword) > -1 ||
        (pinyinInitials || '').toLowerCase().indexOf(keyword) > -1 ||
        (subgroup || '').toLowerCase().indexOf(keyword) > -1;
  }

  searchList.value = list.value
      .filter(filter)
      .filter(it => {
        if (!releaseDate.value) {
          return true
        }
        // 仅对比年月
        return releaseDate.value === it['releaseDate'].replace(/-\d{2}$/, '');
      })
      .filter(selectFilters.value.filter(item => selectFilter.value === item.label)[0].fun)

  // 筛选条件变化后清空勾选，避免"看到没选、实际还在选"的误操作
  const filterKey = `${text.value}|${releaseDate.value}|${selectFilter.value}`
  if (lastFilterKey && lastFilterKey !== filterKey && selectList.value.length) {
    clearSelection()
    ElMessage.info('筛选条件变化，已清空勾选')
  }
  lastFilterKey = filterKey
}

let lastFilterKey = ''

let dialogVisible = ref(false)
let loading = ref(false)

let show = () => {
  releaseDate.value = ''
  selectFilter.value = '全部'
  dialogVisible.value = true
  selectList.value = []
  text.value = ''
  getList()
}

const list = ref([])

const reLoadList = () => {
  return getList()
      .then(() => {
        window.$reLoadList()
      });
}

const getList = () => {
  loading.value = true
  return http.listAni()
      .then(res => {
        // 新接口返回 ListAni 对象
        let data = res.data
        releaseDateList.value = data.releaseDateList
        list.value = data.weekList.flatMap(week => week.items)
            .map(it => ({...it, lastDownloadFormat: formatTime(it['lastDownloadTime'])}))
        changeFilterList()
      })
      .finally(() => {
        loading.value = false
      });
}

let tableRef = ref()
let selectList = ref([])

let handleSelectionChange = (v) => {
  selectList.value = v
}

const selectAll = () => {
  searchList.value.forEach(row => tableRef.value?.toggleRowSelection(row, true))
}

const invertSelection = () => {
  searchList.value.forEach(row => tableRef.value?.toggleRowSelection(row))
}

const clearSelection = () => {
  tableRef.value?.clearSelection()
}

let requireSelection = () => {
  if (!selectList.value.length) {
    ElMessage.warning('请先勾选要操作的订阅')
    return false
  }
  return true
}

let exportData = () => {
  if (!requireSelection()) {
    return
  }
  const textContent = JSON.stringify(selectList.value);
  const blob = new Blob([textContent], {type: "text/plain"});
  const url = URL.createObjectURL(blob);

  const a = document.createElement("a");
  a.style.display = "none";
  a.href = url;
  a.download = `ani.v2.${new Date().toISOString().slice(0, 10)}.json`;
  document.body.appendChild(a);
  a.click();
  URL.revokeObjectURL(url);
  document.body.removeChild(a);
}

/** 行内启用开关：单行即时生效，不弹确认 */
let toggleEnable = (value, row) => {
  row.enableLoading = true
  http.batchEnable(value, [row.id])
      .then(res => {
        row.enable = value
        ElMessage.success(res.message)
      })
      .finally(() => {
        row.enableLoading = false
      })
}

let batchEnable = (value) => {
  if (!requireSelection()) {
    return
  }
  loading.value = true
  let ids = selectList.value.map(it => it['id']);
  http.batchEnable(value, ids)
      .then(res => {
        ElMessage.success(`${res.message}（${ids.length} 项）`)
        clearSelection()
      })
      .finally(() => {
        reLoadList()
      })
}

let releaseDate = ref('')

let updateTotalEpisodeNumber = (force) => {
  if (!requireSelection()) {
    return
  }
  let ids = selectList.value.map(it => it['id']);
  http.updateTotalEpisodeNumber(force, ids)
      .then(res => {
        ElMessage.info(`${res.message}（${ids.length} 项），完成后会自动提示`)
        waitBatchFinish('更新总集数', ids)
      })
}

let batchScrape = (force) => {
  if (!requireSelection()) {
    return
  }
  let ids = selectList.value.map(it => it['id']);
  http.batchScrape(force, ids)
      .then(res => {
        ElMessage.info(`${res.message}（${ids.length} 项），完成后会自动提示`)
        waitBatchFinish('刮削', ids)
      })
}

let delSelected = () => {
  if (!requireSelection()) {
    return
  }
  delAniRef.value?.show(selectList.value)
}

/**
 * 批量操作收敛提示（fork 移植）：提交后轮询 rssJobStatus，
 * 忙碌中静默等待，全部收敛后统一提示；10 分钟超时引导去任务管理器。
 */
let batchPollTimer = null

const stopBatchPolling = () => {
  if (batchPollTimer) {
    clearInterval(batchPollTimer)
    batchPollTimer = null
  }
}

const waitBatchFinish = (label, ids) => {
  if (batchPollTimer) {
    ElMessage.warning(`${label}仍在后台执行，请到任务中心「追番流水线」查看进度后再发起新的批量操作`)
    return
  }
  const startedAt = Date.now()
  batchPollTimer = setInterval(() => {
    if (Date.now() - startedAt > 10 * 60 * 1000) {
      stopBatchPolling()
      ElMessage.warning(`${label}仍在后台执行，请到任务中心「追番流水线」查看进度`)
      return
    }
    http.rssJobStatus({silent: true})
        .then(res => {
          const status = res?.data || {}
          const busy = status.running || status.pending || status.openListBusy
          if (busy) {
            return
          }
          stopBatchPolling()
          ElMessage.success(`${label}已完成（${ids.length} 项）`)
          reLoadList()
        })
        .catch(() => {
        })
  }, 3000)
}

onUnmounted(stopBatchPolling)

defineExpose({show})
</script>

<style scoped>
.manage-content {
  min-height: 300px;
}

.manage-header {
  display: flex;
  justify-content: space-between;
  width: 100%;
}

.manage-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.select-width {
  width: 120px;
}

.manage-count {
  margin: 6px;
  text-align: end;
}

.mange-toolbar {
  gap: 8px;
}

@media (max-width: 1000px) {
  .mange-toolbar > div {
    margin-top: 8px;
  }

  .manage-header {
    flex-direction: column;
    gap: 8px;
  }
}

</style>
