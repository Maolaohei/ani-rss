<template>
  <div class="subscription-page app-page-layout">
    <AddView ref="addRef" v-model:title="title"/>
    <CollectionView ref="collectionRef" @added="onCollectionAdded"/>
    <ManageView ref="manageRef"/>
    <PageHeaderView title="订阅" :subtitle="`共 ${subscriptionTotal} 个订阅`"/>
    <div class="subscription-body app-page-content app-page-padding">
      <div class="subscription-toolbar">
        <div class="subscription-filters">
          <el-input
              v-model="title"
              class="subscription-search"
              clearable
              placeholder="搜索"
              prefix-icon="Search"/>
          <el-select
              v-model:model-value="releaseDate"
              class="subscription-select"
              clearable
              placeholder="日期"
              @change="selectChange">
            <el-option v-for="it in releaseDateList"
                       :key="it"
                       :label="it"
                       :value="it"/>
          </el-select>
          <el-select
              v-model:model-value="enable"
              class="subscription-select"
              @change="selectChange">
            <el-option v-for="selectItem in enableSelect"
                       :key="selectItem.label"
                       :label="selectItem.label"
                       :value="selectItem.label"/>
          </el-select>
        </div>
        <div class="subscription-actions">
          <el-dropdown trigger="click">
            <el-button aria-label="添加" type="primary" class="auto-button" icon="Plus">
              添加
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
          <PopconfirmView :title="refreshTitle" @confirm="refreshAni">
            <template #reference>
              <el-button aria-label="刷新" :loading="refreshLoading" class="auto-button" icon="Refresh">
                刷新
              </el-button>
            </template>
          </PopconfirmView>
          <el-button aria-label="任务中心" title="任务中心" @click="goTaskCenter" class="auto-button" icon="List">
            任务
          </el-button>
          <el-button aria-label="管理" @click="manageRef?.show" class="auto-button" icon="Fold">
            管理
          </el-button>
        </div>
      </div>
      <SubscriptionListView
          ref="listRef"
          :filter="filter"
          :title="searchTitle"
          :view-mode="subscriptionViewMode"
          @loaded="listLoaded"
          @clear-filter="onClearFilter"/>
    </div>
  </div>
</template>

<script setup>
import {computed, onActivated, onDeactivated, onMounted, ref} from "vue";
import {useRouter} from "vue-router";
import {ElMessage, ElMessageBox} from "element-plus";
import {useIntervalFn, useLocalStorage, refDebounced} from "@vueuse/core";
import SubscriptionListView from "@/view/home/SubscriptionListView.vue";
import AddView from "@/view/home/AddView.vue";
import CollectionView from "@/view/home/CollectionView.vue";
import ManageView from "@/view/home/ManageView.vue";
import PopconfirmView from "@/view/custom/PopconfirmView.vue";
import PageHeaderView from "@/view/custom/PageHeaderView.vue";
import {subscriptionViewMode} from "@/js/global.js";
import * as http from "@/js/http.js";

const listRef = ref()
const addRef = ref()
const collectionRef = ref()
const manageRef = ref()
const router = useRouter()

/** 任务中心：跳转并直接落到「追番流水线」Tab */
const goTaskCenter = () => {
  router.push({path: '/downloads', query: {tab: 'pipeline'}})
}
const title = ref('')
const releaseDate = ref('')
const releaseDateList = ref([])
const subscriptionTotal = ref(0)
const refreshLoading = ref(false)
const enable = useLocalStorage('select-enable', '已启用')
const enableSelect = [
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
]
const filter = ref(() => true)

/**
 * 搜索关键词 250ms 防抖后下发给列表：
 * 子列表改为 prop 驱动的 computed 派生，输入每个字符不再触发全量卡片重渲染
 */
const searchTitle = refDebounced(title, 250)

const selectChange = () => {
  filter.value = it => {
    const selectedEnable = enableSelect.find(item => item.label === enable.value)
    if (selectedEnable && !selectedEnable.fun(it)) {
      return false
    }
    if (!releaseDate.value) {
      return true
    }

    return releaseDate.value === it.releaseDate.replace(/-\d{2}$/, '')
  }
}

const listLoaded = data => {
  releaseDateList.value = data.releaseDateList || []
  subscriptionTotal.value = data.total || 0
}

const onClearFilter = () => {
  title.value = ''
  enable.value = '全部'
  releaseDate.value = ''
  selectChange()
}

const refreshAni = () => {
  refreshLoading.value = true
  http.refreshAll()
      .then(res => {
        // 忙碌时的"让路/排队"属中性结果，不再统一用绿色成功提示
        const message = res.message
        if (taskBusy.value || message.indexOf('排队') > -1) {
          ElMessageBox.alert(message, '刷新已排队', {type: 'warning', confirmButtonText: '知道了'})
              .catch(() => {
              })
        } else {
          ElMessage.success(message)
        }
        listRef.value?.getList()
      })
      .finally(() => {
        refreshLoading.value = false
      })
}

/**
 * 添加合集成功后：关联的番剧已写入订阅列表，刷新列表让其立即出现
 */
const onCollectionAdded = () => {
  listRef.value?.getList()
}

/**
 * RSS 任务状态：用于把「刷新」的后果说清楚（空闲=立即刷新；忙碌=会中断当前扫描）
 */
const taskBusy = ref(false)

const refreshTaskState = () => {
  http.rssJobStatus({silent: true})
      .then(res => {
        const data = res.data || {}
        taskBusy.value = !!(data.running || data.pending || data.openListBusy || data.cancelRequested)
      })
      .catch(() => {
        taskBusy.value = false
      })
}

// 周期扫描可能在本页打开之后才开始，状态必须持续刷新，否则确认框会一直说"立即刷新"
const {pause: pauseTaskPoll, resume: resumeTaskPoll} = useIntervalFn(refreshTaskState, 20000)

const refreshTitle = computed(() => {
  if (taskBusy.value) {
    return '当前正在扫描，刷新会中断本轮扫描并抢先执行，是否继续？'
  }
  return '立即刷新全部订阅？'
})

onMounted(() => {
  selectChange()
  refreshTaskState()
})
onActivated(resumeTaskPoll)
onDeactivated(pauseTaskPoll)
</script>

<style scoped>
.subscription-toolbar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-bottom: 10px;
}

.subscription-filters,
.subscription-actions {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.subscription-filters {
  flex: 1;
}

.subscription-actions {
  flex-shrink: 0;
}

.subscription-actions > * {
  margin: 0 !important;
}

.subscription-actions :deep(.el-button) {
  margin: 0;
}

.subscription-search {
  width: 220px;
}

.subscription-select {
  width: 128px;
}

@media (max-width: 900px) {
  .subscription-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .subscription-filters,
  .subscription-actions {
    width: 100%;
  }

  .subscription-filters {
    flex-wrap: wrap;
  }

  .subscription-actions {
    justify-content: flex-end;
  }

  .subscription-search {
    flex: 1 1 180px;
  }

  .subscription-select {
    flex: 1 1 120px;
  }
}

@media (max-width: 560px) {
  .subscription-toolbar {
    padding-bottom: 8px;
  }

  .subscription-actions {
    justify-content: flex-end;
    gap: 4px;
  }
}
</style>
