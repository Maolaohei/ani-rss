<template>
  <Config ref="configRef"/>
  <Add ref="addRef"/>
  <Logs ref="logsRef"/>
  <Manage ref="manageRef"/>
  <Collection ref="collectionRef"/>
  <TorrentsInfos ref="torrentsInfosRef"/>
  <TaskManager ref="taskManagerRef"/>
  <div class="content">
    <div id="header" :class="{'is-scrolled': scrolled}">
      <div style="margin: 10px;" class="auto-flex">
        <div>
          <el-input
              ref="searchInputRef"
              v-model="title"
              @input="onSearch"
              @clear="onSearch"
              clearable
              placeholder="搜索标题 / 拼音 / 字幕组（按 / 聚焦）"
              prefix-icon="Search"
              aria-label="搜索订阅"
              style="min-width: 210px"/>
        </div>
        <div class="spacer"></div>
        <div style="min-width: 300px;display: flex">
          <div style="flex: 1;">
            <el-select
                v-model="releaseDate"
                clearable
                placeholder="上映年月"
                aria-label="按上映年月筛选"
                @change="selectChange"
            >
              <el-option v-for="it in listRef?.releaseDateList"
                         :key="it" :label="it" :value="it"
              />
            </el-select>
          </div>
          <div class="spacer"></div>
          <div style="flex: 1;">
            <el-select v-model:model-value="enable"
                       aria-label="按启用状态筛选"
                       @change="selectChange">
              <el-option v-for="selectItem in enableSelect"
                         :key="selectItem.label"
                         :label="selectItem.label"
                         :value="selectItem.label"
              >
              </el-option>
            </el-select>
          </div>
        </div>
      </div>
      <div class="add-button">
        <div style="margin: 0 4px;">
          <el-button bg text title="任务管理器" aria-label="任务管理器" @click="taskManagerRef?.show">
            <el-icon :class="iconClass">
              <ListIcon/>
            </el-icon>
            <span class="btn-label">任务管理器</span>
          </el-button>
        </div>
        <div style="margin: 0 4px;">
          <el-dropdown trigger="click">
            <el-button bg text type="primary" title="添加订阅或合集" aria-label="添加订阅或合集">
              <el-icon :class="iconClass">
                <Plus/>
              </el-icon>
              <span class="btn-label">添加</span>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="addRef?.show">
                  添加订阅
                </el-dropdown-item>
                <el-dropdown-item @click="collectionRef?.show">
                  添加合集
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <div style="margin: 0 4px;">
          <el-button bg text title="下载列表" aria-label="下载列表" @click="torrentsInfosRef?.show">
            <el-icon :class="iconClass">
              <Download/>
            </el-icon>
            <span class="btn-label">下载</span>
          </el-button>
        </div>
        <div style="margin: 0 4px;">
          <popconfirm :title="refreshTitle" @confirm="refreshAni">
            <template #reference>
              <el-button bg text title="刷新全部订阅" aria-label="刷新全部订阅">
                <el-icon :class="iconClass">
                  <Refresh/>
                </el-icon>
                <span class="btn-label">刷新</span>
              </el-button>
            </template>
          </popconfirm>
        </div>
        <div style="margin: 0 4px;">
          <el-button text bg title="批量管理订阅" aria-label="批量管理订阅" @click="manageRef?.show">
            <el-icon :class="iconClass">
              <Fold/>
            </el-icon>
            <span class="btn-label">管理</span>
          </el-button>
        </div>
        <div style="margin: 0 4px;">
          <el-badge :is-dot="about.update" class="item">
            <el-button @click="configRef?.show(about.update)" text bg title="设置" aria-label="设置">
              <el-icon :class="iconClass">
                <Setting/>
              </el-icon>
              <span class="btn-label">设置</span>
            </el-button>
          </el-badge>
        </div>
        <div style="margin-left: 4px;">
          <el-button @click="logsRef?.show" text bg title="日志" aria-label="日志">
            <el-icon :class="iconClass">
              <Tickets/>
            </el-icon>
            <span class="btn-label">日志</span>
          </el-button>
        </div>
      </div>
    </div>
    <div style="flex: 1;overflow: hidden;">
      <List
          ref="listRef"
          v-model:title="title"
          :filter="filter"
          @add="addRef?.show"
          @refresh="onAniRefreshed"
          @update:filter="updateFilter"
          @clear-filter="clearFilter"/>
    </div>
  </div>
</template>

<script setup>
import {computed, onMounted, onUnmounted, ref} from "vue";
import {ElMessage, ElMessageBox} from "element-plus";
import {Download, Fold, List as ListIcon, Plus, Refresh, Setting, Tickets} from "@element-plus/icons-vue"
import Config from "./Config.vue";
import List from "./List.vue";
import Add from "./Add.vue";
import Logs from "./Logs.vue";
import Popconfirm from "@/other/Popconfirm.vue";
import Manage from "./Manage.vue";
import {useIntervalFn, useLocalStorage} from "@vueuse/core";
import Collection from "./Collection.vue";
import TorrentsInfos from "./TorrentsInfos.vue";
import TaskManager from "./TaskManager.vue";
import {elIconClass, initLayout} from "@/js/global.js";
import * as http from "@/js/http.js";

const listRef = ref()
const configRef = ref()
const addRef = ref()
const logsRef = ref()
const manageRef = ref()
const collectionRef = ref()
const torrentsInfosRef = ref()
const taskManagerRef = ref()

/** 顶栏按钮图标间距 */
const iconClass = elIconClass

const title = ref('')
const enable = useLocalStorage('select-enable', '已启用')
const enableSelect = ref([
  {
    label: '全部',
    fun: () => true
  },
  {
    label: '已启用',
    fun: item => item.enable
  },
  {
    label: '未启用',
    fun: item => !item.enable
  }
])
const filter = ref(() => true)
const releaseDate = ref('')

/** 搜索走 List 内部的 200ms 防抖，避免长列表逐键重建 */
const onSearch = () => {
  listRef.value?.changeFilterList(title.value)
}

const selectChange = () => {
  const selected = enableSelect.value.find(it => it.label === enable.value)
  filter.value = (it) => {
    if (selected && !selected.fun(it)) {
      return false
    }
    if (!releaseDate.value) {
      return true
    }

    // 仅对比年月
    return releaseDate.value === (it.releaseDate || '').replace(/-\d{2}$/, '');
  }
  // 下拉筛选是显式动作，立即生效（不走 200ms 防抖）
  listRef.value?.applyFilter(title.value)
  refreshTaskState()
}

const clearFilter = () => {
  title.value = ''
  enable.value = '全部'
  releaseDate.value = ''
  filter.value = () => true
  listRef.value?.applyFilter('')
}

/** 子组件请求清空筛选（顶部搜索/启用状态/上映年月） */
const updateFilter = () => {
  clearFilter()
}

const about = ref({
  'version': '',
  'latest': '',
  'update': false,
  'markdownBody': ''
})

/** 任务状态：用于把「刷新」的后果说清楚（空闲=立即刷新；忙碌=会中断当前扫描） */
const taskBusy = ref(false)

const refreshTaskState = () => {
  http.rssJobStatus()
      .then(res => {
        const data = res.data || {}
        taskBusy.value = !!(data.running || data.pending || data.openListBusy || data.cancelRequested)
      })
      .catch(() => {
        taskBusy.value = false
      })
}

// 周期扫描可能在本页打开之后才开始，状态必须持续刷新，否则确认框会一直说“立即刷新”
useIntervalFn(refreshTaskState, 20000)

const refreshTitle = computed(() => {
  if (taskBusy.value) {
    return '当前正在扫描，刷新会中断本轮扫描并抢先执行，是否继续？'
  }
  return '立即刷新全部订阅？'
})

/** 单订阅「立即检查新集」提交后刷新任务状态与列表（集数/时间可能已变） */
const onAniRefreshed = () => {
  refreshTaskState()
}

// 页面级滚动后顶栏加投影（毛玻璃 sticky 顶栏的滚动反馈）
const scrolled = ref(false)
let onScroll = () => {
  scrolled.value = (window.scrollY || document.documentElement.scrollTop) > 8
}

let refreshAni = () => {
  http.refreshAll()
      .then(res => {
        // 忙碌时的“让路/排队”属中性结果，不再统一用绿色成功提示
        const message = res.message
        if (taskBusy.value || message.indexOf('排队') > -1) {
          ElMessageBox.alert(message, '刷新已排队', {type: 'warning', confirmButtonText: '知道了'})
              .catch(() => {
              })
        } else {
          ElMessage.success(message)
        }
        refreshTaskState()
      })
}

onMounted(() => {
  initLayout()
  selectChange()
  refreshTaskState()
  window.addEventListener('scroll', onScroll, {passive: true})
  onScroll()

  http.about()
      .then(res => {
        about.value = res.data
      })
})

/**
 * 键盘快捷键：此前全站只有各搜索框的回车。
 * `/` 或 Ctrl/⌘+K 聚焦搜索，Esc 清空焦点；不在输入态时才拦截，
 * 避免影响用户在表单里输入。
 */
const onKeydown = e => {
  const target = e.target
  const typing = target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)

  if (!typing && (e.key === '/' || ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k'))) {
    e.preventDefault()
    searchInputRef.value?.focus()
  }
}

const searchInputRef = ref()

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
.content {
  display: flex;
  flex-direction: column;
}

.add-button {
  margin: 10px;
  display: flex;
  justify-content: flex-end;
}

/* 窄屏才收起按钮文字；收起后靠 title/aria-label 保证可理解、读屏可读 */
@media (max-width: 720px) {
  .btn-label {
    display: none;
  }
}
</style>
