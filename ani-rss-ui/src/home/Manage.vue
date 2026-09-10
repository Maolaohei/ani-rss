<template>
  <ImportAni ref="importAniRef" @callback="getList"/>
  <Del ref="delRef" @callback="reLoadList"/>
  <el-dialog v-model="dialogVisible" center title="管理" @closed="onClosed">
    <div class="manage-content" v-loading="loading">
      <div class="manage-header">
        <div class="auto-flex">
          <div>
            <el-input
                v-model="text"
                @input="changeFilterList"
                @clear="changeFilterList"
                clearable
                placeholder="搜索标题/拼音/字幕组"
                prefix-icon="Search"
                style="width: 200px;"
            />
          </div>
          <div class="spacer"></div>
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
          <div class="spacer"></div>
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
        <div class="manage-toolbar-full">
          <el-button bg text icon="Check" :disabled="!selectList.length" @click="selectAll">
            全选当前
          </el-button>
          <el-button bg text icon="Switch" :disabled="!selectList.length" @click="invertSelection">
            反选
          </el-button>
          <el-button bg text icon="Close" :disabled="!selectList.length" @click="clearSelection">
            清空选择
          </el-button>
          <el-dropdown :trigger="'click'">
            <el-button bg text icon="Operation">
              批量操作<template v-if="selectList.length">（{{ selectList.length }}）</template>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="updateTotalEpisodeNumber(false)">
                  <el-text>
                    <el-icon>
                      <RefreshRight/>
                    </el-icon>
                    更新总集数
                  </el-text>
                </el-dropdown-item>
                <el-dropdown-item @click="updateTotalEpisodeNumber(true)">
                  <el-text type="warning">
                    <el-icon>
                      <Refresh/>
                    </el-icon>
                    强制更新总集数（忽略缓存）
                  </el-text>
                </el-dropdown-item>
                <el-dropdown-item divided @click="batchScrape(false)">
                  <el-text>
                    <el-icon>
                      <RefreshRight/>
                    </el-icon>
                    刮削
                  </el-text>
                </el-dropdown-item>
                <el-dropdown-item @click="batchScrape(true)">
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
                <el-dropdown-item divided :disabled="!selectList.length" @click="delRef?.show(selectList)">
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
      <el-alert
          v-if="selectList.length"
          class="manage-scope"
          type="info"
          :closable="false"
          show-icon
          :title="`已选中 ${selectList.length} 项，将在选中范围内执行；当前列表共 ${searchList.length} 项`"/>
      <el-alert
          v-else-if="searchList.length"
          class="manage-scope"
          type="warning"
          :closable="false"
          show-icon
          title="未选中任何订阅：启用/禁用/刮削/更新总集数/导出/删除都已禁用，请先在左侧勾选"/>
      <el-table
          ref="tableRef"
          size="small"
          row-key="id"
          @selection-change="handleSelectionChange"
          :data="searchList"
          height="400px"
          stripe
      >
        <el-table-column type="selection" width="45" fixed reserve-selection/>
        <el-table-column label="标题" width="180" fixed>
          <template #default="it">
            <el-text :line-clamp="2" size="small">
              {{ searchList[it.$index].title }}
            </el-text>
          </template>
        </el-table-column>
        <el-table-column label="季" prop="season" width="46"/>
        <el-table-column label="字幕组" width="92">
          <template #default="it">
            <el-text size="small" truncated>
              {{ searchList[it.$index].subgroup }}
            </el-text>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="70">
          <template #default="it">
            <el-switch
                :model-value="!!searchList[it.$index].enable"
                size="small"
                @change="() => toggleEnable(searchList[it.$index])"/>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="92">
          <template #default="it">
            <el-tag type="warning">
              {{ searchList[it.$index]['currentEpisodeNumber'] }} /
              {{ searchList[it.$index]['totalEpisodeNumber'] ? searchList[it.$index]['totalEpisodeNumber'] : '*' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近更新" width="110">
          <template #default="it">
            <el-text size="small" type="info">{{ searchList[it.$index].lastDownloadFormat || '无记录' }}</el-text>
          </template>
        </el-table-column>
        <el-table-column label="漏集" width="80">
          <template #default="it">
            <el-tag v-if="searchList[it.$index].omitCount > 0" type="danger" size="small">
              漏 {{ searchList[it.$index].omitCount }}
            </el-tag>
            <el-text v-else size="small" type="info">-</el-text>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="70">
          <template #default="it">
            <el-tag type="info" v-if="searchList[it.$index].ova">
              ova
            </el-tag>
            <el-tag type="info" v-else>
              tv
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="RSS" min-width="120">
          <template #default="it">
            <el-tooltip :content="searchList[it.$index].url" placement="top">
              <el-text size="small" truncated>
                {{ searchList[it.$index].url }}
              </el-text>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
      <div>
        <p class="manage-count">
          共 {{ searchList.length }} 项<template v-if="list.length !== searchList.length">（全部 {{ list.length }} 项）</template>
        </p>
      </div>
    </div>
  </el-dialog>
</template>
<script setup>
import {computed, nextTick, onUnmounted, ref} from "vue";
import {ElMessage, ElText} from "element-plus";
import Del from "./Del.vue";
import ImportAni from "@/home/ImportAni.vue";
import {
  CircleCheck,
  CircleClose,
  Operation,
  Refresh,
  RefreshRight,
  Remove,
  Switch,
  Upload
} from "@element-plus/icons-vue";
import * as http from "@/js/http.js";
import formatTime from "@/js/format-time.js";

let releaseDateList = ref([])

let delRef = ref()
let importAniRef = ref()
let tableRef = ref()

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

/**
 * 批量任务进行中标记：与任务管理器的"运行中"无关，仅表示本页已提交的批量请求尚未收敛
 */
let batchRunning = ref(false)

/**
 * 过滤条件快照：用于判断"筛选变化是否导致勾选被清空"
 */
let lastFilterKey = ''

const filterKey = () => `${text.value}|${releaseDate.value}|${selectFilter.value}`

const decorate = (items) => items.map(item => {
  return {
    ...item,
    lastDownloadFormat: item.lastDownloadTime > 0 ? formatTime(item.lastDownloadTime) : ''
  }
})

let changeFilterList = () => {
  const filter = item => {
    if (text.value.length < 1) {
      return true
    }
    const keyword = text.value.trim().toLowerCase()
    if (!keyword) {
      return true
    }
    let {title, pinyin, pinyinInitials, subgroup} = item
    return (title || '').toLowerCase().indexOf(keyword) > -1 ||
        (pinyin || '').toLowerCase().indexOf(keyword) > -1 ||
        (pinyinInitials || '').toLowerCase().indexOf(keyword) > -1 ||
        (subgroup || '').toLowerCase().indexOf(keyword) > -1;
  }

  const changed = filterKey() !== lastFilterKey
  lastFilterKey = filterKey()

  const next = decorate(list.value
      .filter(filter)
      .filter(it => {
        if (!releaseDate.value) {
          return true
        }
        // 仅对比年月
        return releaseDate.value === it.releaseDate.replace(/-\d{2}$/, '');
      })
      .filter(selectFilters.value.filter(item => selectFilter.value === item.label)[0].fun))

  // 表格 data 整体替换会触发 clearSelection：勾选被清空与"筛选条件变了"是同一件事，
  // 这里不再静默处理，而是明确告诉用户（见模板中的 scope 提示）
  const hadSelection = selectList.value.length > 0
  searchList.value = next

  if (hadSelection && changed) {
    ElMessage.info('筛选条件已变化，原先的勾选已清空，请在当前列表重新勾选')
  }
}

let dialogVisible = ref(false)
let loading = ref(false)

let show = () => {
  releaseDate.value = ''
  selectFilter.value = '全部'
  dialogVisible.value = true
  selectList.value = []
  text.value = ''
  lastFilterKey = ''
  batchRunning.value = false
  tableRef.value?.clearSelection()
  getList()
}

const onClosed = () => {
  stopBatchPolling()
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
        changeFilterList()
      })
      .finally(() => {
        loading.value = false
      });
}

let selectList = ref([])

let handleSelectionChange = (v) => {
  selectList.value = v
}

const selectAll = () => {
  nextTick(() => {
    searchList.value.forEach(row => tableRef.value?.toggleRowSelection(row, true))
  })
}

const invertSelection = () => {
  nextTick(() => {
    const selected = new Set(selectList.value.map(it => it.id))
    searchList.value.forEach(row => tableRef.value?.toggleRowSelection(row, !selected.has(row.id)))
  })
}

const clearSelection = () => {
  tableRef.value?.clearSelection()
  selectList.value = []
}

/**
 * 行内启用开关：只改这一条，不发批量请求
 */
const toggleEnable = (row) => {
  const next = !row.enable
  loading.value = true
  http.batchEnable(next, [row.id])
      .then(() => {
        row.enable = next
        ElMessage.success(`${row.title} 已${next ? '启用' : '禁用'}`)
        window.$reLoadList()
      })
      .finally(() => {
        loading.value = false
      })
}

// ---------------------------------------------------------------- 批量任务收敛

let batchPollTimer = null
let batchPollDeadline = 0

const stopBatchPolling = () => {
  if (batchPollTimer) {
    clearInterval(batchPollTimer)
    batchPollTimer = null
  }
  batchRunning.value = false
}

/**
 * 批量任务在后端是异步执行的：这里在提交后轮询任务管理器状态，
 * 运行结束后再刷新列表，避免"弹成功 + 立刻刷新出旧数据"。
 * 超时或查询失败则提示用户去任务管理器看结果，不无限等。
 */
const waitBatchFinish = (ids, label, onDone) => {
  stopBatchPolling()
  batchRunning.value = true
  batchPollDeadline = Date.now() + 10 * 60 * 1000

  batchPollTimer = setInterval(() => {
    if (!dialogVisible.value) {
      stopBatchPolling()
      return
    }
    if (Date.now() > batchPollDeadline) {
      stopBatchPolling()
      ElMessage.warning(`${label}仍在后台执行，请到任务管理器查看进度后再刷新本列表`)
      return
    }
    http.rssJobStatus()
        .then(res => {
          const status = res?.data || {}
          const busy = status.running || status.pending || status.openListBusy
          if (busy) {
            return
          }
          stopBatchPolling()
          ElMessage.success(`${label}已完成（${ids.length} 项）`)
          onDone?.()
        })
        .catch(() => {
          // 单次查询失败不终止轮询，等待下一轮
        })
  }, 3000)
}

onUnmounted(() => {
  stopBatchPolling()
})

// ---------------------------------------------------------------- 批量动作

const requireSelection = () => {
  if (!selectList.value.length) {
    ElMessage.warning('请先勾选要操作的订阅')
    return false
  }
  return true
}

const selectedIds = computed(() => selectList.value.map(it => it['id']))

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
  const stamp = new Date().toISOString().slice(0, 10)
  a.download = `ani.v2.${stamp}.json`;
  document.body.appendChild(a);
  a.click();
  URL.revokeObjectURL(url);
  document.body.removeChild(a);
}

let batchEnable = (value) => {
  if (!requireSelection()) {
    return
  }
  loading.value = true
  let ids = selectedIds.value
  http.batchEnable(value, ids)
      .then(res => {
        ElMessage.success(`${res.message || '修改完成'}（${ids.length} 项已${value ? '启用' : '禁用'}）`)
        clearSelection()
      })
      .finally(() => {
        loading.value = false
        reLoadList()
      })
}

let releaseDate = ref('')

let updateTotalEpisodeNumber = (force) => {
  if (!requireSelection()) {
    return
  }
  let ids = selectedIds.value
  const label = force ? '强制更新总集数' : '更新总集数'
  http.updateTotalEpisodeNumber(force, ids)
      .then(res => {
        ElMessage.info(`${label}已加入后台队列，正在处理 ${ids.length} 项，完成后会自动刷新列表`)
        waitBatchFinish(ids, label, () => reLoadList())
      })
      .catch(() => {
      })
}

let batchScrape = (force) => {
  if (!requireSelection()) {
    return
  }
  let ids = selectedIds.value
  const label = force ? '强制刮削' : '刮削'
  http.batchScrape(force, ids)
      .then(res => {
        ElMessage.info(`${label}已加入后台队列，正在处理 ${ids.length} 项，完成后会自动刷新列表`)
        waitBatchFinish(ids, label, () => reLoadList())
      })
      .catch(() => {
      })
}

defineExpose({show})
</script>

<style scoped>
.manage-content {
  min-height: 300px;
}

.manage-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 8px;
  width: 100%;
}

.manage-toolbar-full {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 8px;
}

.manage-scope {
  margin-bottom: 8px;
}

.select-width {
  width: 120px;
}

.manage-count {
  margin: 6px;
  text-align: end;
}
</style>
