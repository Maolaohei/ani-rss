<template>
  <Edit ref="editRef"/>
  <PlayList ref="playListRef"/>
  <Cover ref="coverRef"/>
  <Del ref="delRef"/>
  <BgmRate ref="bgmRateRef"/>
  <div class="list-container">
    <!-- 加载失败：给出原因与重试入口，避免与“没有订阅”混淆成一片空白 -->
    <el-result
        v-if="loadError"
        class="list-state"
        icon="warning"
        title="订阅列表加载失败"
        :sub-title="loadError">
      <template #extra>
        <el-button type="primary" @click="getList">重试</el-button>
      </template>
    </el-result>
    <!-- 骨架屏：首屏等待期间给出结构占位，避免整片空白 -->
    <div v-else-if="loading" class="list-content">
      <div class="grid-container">
        <el-card v-for="n in skeletonCount" :key="n" shadow="never">
          <el-skeleton animated>
            <template #template>
              <div class="skeleton-card">
                <el-skeleton-item variant="image" class="skeleton-cover"/>
                <div class="skeleton-info">
                  <el-skeleton-item variant="h3" style="width: 55%"/>
                  <el-skeleton-item variant="text" style="width: 35%; margin-top: 8px"/>
                  <el-skeleton-item variant="text" style="width: 70%; margin-top: 8px"/>
                  <el-skeleton-item variant="text" style="width: 45%; margin-top: 8px"/>
                </div>
              </div>
            </template>
          </el-skeleton>
        </el-card>
      </div>
    </div>
    <!-- 空状态：区分“一条订阅都没有”和“筛选后没有结果”，并给出一键清除筛选 -->
    <el-empty
        v-else-if="isEmpty"
        class="list-state"
        :description="emptyDescription">
      <el-button v-if="hasFilter" @click="clearFilter">清除筛选条件</el-button>
      <el-button v-else type="primary" @click="emit('add')">添加订阅</el-button>
    </el-empty>
    <div v-else class="list-content">
      <div class="list-toolbar">
        <span v-if="hasFilter" class="list-filter-hint">
          筛选后 {{ totalCount }} / 共 {{ allCount }} 项
        </span>
        <span v-else class="list-filter-hint">共 {{ allCount }} 项</span>
        <el-select
            v-model="sortType"
            class="list-sort-select"
            size="small"
            aria-label="订阅排序方式"
            @change="changeSort"
        >
          <el-option
              v-for="option in sortOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"/>
        </el-select>
      </div>
      <template v-if="showWeek">
        <div v-for="weekItem in filterList" :key="weekItem.weekLabel">
          <h2 class="list-week-title" :class="{'is-today': weekItem.isToday}">
            {{ weekItem.weekLabel }}
            <el-tag v-if="weekItem.isToday" class="list-today-tag" size="small" type="primary">今天</el-tag>
          </h2>
          <div class="grid-container">
            <div v-for="item in weekItem.items" :key="item.id" :data-ani-id="item.id"
                 :class="{'list-item-highlight': highlightId === item.id}">
              <AniCard
                  :item="item"
                  @edit="editRef?.show"
                  @playlist="playListRef?.show"
                  @cover="coverRef?.show"
                  @del="delRef?.show"
                  @rate="bgmRateRef?.show"
                  @refresh="emit('refresh', $event)"
              />
            </div>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="grid-container">
          <div v-for="item in flatFilterList" :key="item.id" :data-ani-id="item.id"
               :class="{'list-item-highlight': highlightId === item.id}">
            <AniCard
                :item="item"
                @edit="editRef?.show"
                @playlist="playListRef?.show"
                @cover="coverRef?.show"
                @del="delRef?.show"
                @rate="bgmRateRef?.show"
                @refresh="emit('refresh', $event)"
            />
          </div>
        </div>
      </template>
      <div class="list-bottom-spacer"></div>
    </div>
  </div>
</template>

<script setup>
import {computed, nextTick, onMounted, onUnmounted, ref, watch} from "vue";
import {useDebounceFn, useIntervalFn} from "@vueuse/core";
import Edit from "./Edit.vue";
import PlayList from "@/play/PlayList.vue";
import Cover from "./Cover.vue";
import Del from "./Del.vue";
import BgmRate from "./BgmRate.vue";
import formatTime from "@/js/format-time.js";
import {listAni} from "@/js/http.js";
import AniCard from "@/home/AniCard.vue";
import {showWeek} from "@/js/global.js";

const editRef = ref()
const delRef = ref()
const coverRef = ref()
const playListRef = ref()
const bgmRateRef = ref()

const weekList = ref([])
const filterList = ref([])
const flatFilterList = ref([])
const releaseDateList = ref([])

const loading = ref(true)
/** 加载失败原因；与“没有订阅”区分开，避免失败被渲染成空白页 */
const loadError = ref('')
/** 未过滤的总订阅数，用于“筛选后 N / 共 M 项” */
const allCount = ref(0)
/** 当前生效的筛选（顶栏搜索词 + 已启用/未启用 + 上映年月） */
const currentFilter = ref(() => true)

const emit = defineEmits(['add', 'clear-filter', 'refresh', 'update:title'])

/** 骨架屏占位数量：跟随当前网格列数与一屏行数，不写死 */
const skeletonCount = ref(6)

/** 首页排序：后端默认顺序 / 最近更新 / 评分 / 标题拼音 */
const sortType = ref('default')
const sortOptions = [
  {label: '默认排序', value: 'default'},
  {label: '最近更新', value: 'lastDownloadTime'},
  {label: '评分最高', value: 'score'},
  {label: '标题拼音', value: 'pinyin'}
]

const sortFlatList = list => {
  switch (sortType.value) {
    case 'lastDownloadTime':
      return [...list].sort((a, b) => (b.lastDownloadTime || 0) - (a.lastDownloadTime || 0))
    case 'score':
      return [...list].sort((a, b) => (b.score || 0) - (a.score || 0))
    case 'pinyin':
      return [...list].sort((a, b) => (a.pinyin || a.title || '').localeCompare(b.pinyin || b.title || '', 'zh'))
    default:
      return [...list].sort((a, b) => a.sort - b.sort)
  }
}

const changeSort = () => {
  applyFilter(props.title)
}

const hasFilter = computed(() => {
  if (allCount.value === 0) {
    return false
  }
  return flatFilterList.value.length !== allCount.value
})

const totalCount = computed(() => flatFilterList.value.length)

const isEmpty = computed(() => flatFilterList.value.length === 0)

const emptyDescription = computed(() => {
  if (allCount.value === 0) {
    return '还没有添加任何订阅'
  }
  if (hasFilter.value) {
    return '没有符合当前筛选条件的订阅'
  }
  return '没有可显示的订阅'
})

const clearFilter = () => {
  emit('clear-filter')
  emit('update:title', '')
  changeFilterList('')
}

const applyFilter = (text = '', outerFilter = undefined) => {
  if (outerFilter) {
    currentFilter.value = outerFilter
  }

  // 只读原数组、单次 map，避免此前每按键整体深拷贝带来的输入卡顿
  const source = weekList.value
  const matchKeyword = item => {
    const keyword = (text || '').trim().toLowerCase()
    if (keyword.length < 1) {
      return true
    }
    let {title, pinyin, pinyinInitials, subgroup} = item
    return (title || '').toLowerCase().indexOf(keyword) > -1
        || (pinyin || '').toLowerCase().indexOf(keyword) > -1
        || (pinyinInitials || '').toLowerCase().indexOf(keyword) > -1
        || (subgroup || '').toLowerCase().indexOf(keyword) > -1;
  }

  const today = new Date().getDay()
  const todayLabel = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'][today]

  filterList.value = source
      .map(it => {
        let items = it.items;
        items = items
            .filter(currentFilter.value)
            .filter(matchKeyword)
            .map(it => {
              return {...it, lastDownloadFormat: formatTime(it['lastDownloadTime'])}
            });
        return {
          weekLabel: it.weekLabel,
          isToday: it.weekLabel === todayLabel,
          items: sortType.value === 'default' ? items : sortFlatList(items)
        }
      })
      .filter(it => it.items.length)

  // 当不按星期展示时，展平并按当前排序方式排列
  flatFilterList.value = sortFlatList(
      Array.from(filterList.value).flatMap(it => it.items)
  )

  // 今天置顶：后端已把今天排首位，这里仅在缺失时补一次，保证“今天更新了哪几部”一眼可见
  const todayIndex = filterList.value.findIndex(it => it.isToday)
  if (todayIndex > 0) {
    const [todayGroup] = filterList.value.splice(todayIndex, 1)
    filterList.value.unshift(todayGroup)
  }
}

const changeFilterList = (text = '') => {
  applyFilter(text)
}

/** 防抖：顶栏搜索每按键都会触发，200ms 足够且避免长列表卡顿 */
const changeFilterListDebounced = useDebounceFn(text => changeFilterList(text), 200)

const getList = () => {
  loading.value = true
  loadError.value = ''

  listAni()
      .then(res => {
        let data = res.data
        weekList.value = data.weekList
        releaseDateList.value = data.releaseDateList
        allCount.value = data.weekList
            .flatMap(it => it.items)
            .length

        updateGridLayout()
        applyFilter(props.title)
      })
      .catch(err => {
        // api.js 已统一提示一次；这里再给页面级失败态与重试入口
        loadError.value = err?.message || '请检查服务是否在运行后重试'
      })
      .finally(() => {
        loading.value = false
      })
}

let updateGridLayout = () => {
  const app = document.querySelector('#app');
  let gridColumns = Math.max(1, Math.floor(app.offsetWidth / 400));

  const el = document.documentElement
  el.style.setProperty('--grid-columns', gridColumns)

  // 骨架屏占位按网格列数给两行左右，避免只显示一两个占位块
  skeletonCount.value = Math.min(12, gridColumns * 2)
}

// “最近更新”相对时间需要随时间推移自动重算，否则挂着页面几小时后仍显示“刚刚”
useIntervalFn(() => {
  applyFilter(props.title)
}, 30000)

/** 高亮的订阅 id（任务管理器定位/添加成功定位用） */
const highlightId = ref('')

/**
 * 定位到指定订阅：清掉可能把它藏起来的筛选（搜索词/启用状态/年月），
 * 重载列表后滚动到卡片并高亮 2 秒。
 */
const focusAni = async aniId => {
  if (!aniId) {
    return
  }
  emit('clear-filter')
  await getList()
  highlightId.value = aniId
  nextTick(() => {
    const el = document.querySelector(`[data-ani-id="${aniId}"]`)
    if (el && typeof el.scrollIntoView === 'function') {
      el.scrollIntoView({behavior: 'smooth', block: 'center'})
    }
  })
  setTimeout(() => {
    if (highlightId.value === aniId) {
      highlightId.value = ''
    }
  }, 2500)
}

onMounted(() => {
  window.addEventListener('resize', updateGridLayout);
  window.$reLoadList = getList
  // 任务管理器「定位订阅」：刷新后滚动并高亮目标卡片
  window.$focusAni = focusAni
  getList()
})

onUnmounted(() => {
  window.removeEventListener('resize', updateGridLayout)
  if (window.$focusAni === focusAni) {
    window.$focusAni = undefined
  }
})

defineExpose({
  releaseDateList,
  /** 顶栏搜索走这个（内部 200ms 防抖） */
  changeFilterList: changeFilterListDebounced,
  /** 下拉筛选等需要立即生效的入口走这个 */
  applyFilter,
  hasFilter,
  allCount,
  totalCount
})

let props = defineProps({
  title: String,
  filter: Function
})

watch(() => props.filter, val => {
  applyFilter(props.title, val)
})
</script>

<style scoped>
.grid-container {
  display: grid;
  grid-gap: 8px;
  width: 100%;
  grid-template-columns: repeat(var(--grid-columns), 1fr);
}

.list-container {
  width: 100%;
  min-height: 240px;
}

.list-content {
  margin: 0 10px;
}

.list-week-title {
  margin: 16px 0 8px 4px;
}

/* 今天的分组：一眼可见，配合 sticky 让“今天更新了哪几部”始终在视野内 */
.list-week-title.is-today {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: 0;
  padding: 4px 4px;
  background: var(--el-bg-color-page);
  color: var(--el-color-primary);
}

.list-today-tag {
  font-weight: 500;
}

.list-state {
  padding: 40px 0;
}

/* 工具条：左侧计数、右侧排序入口 */
.list-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 32px;
}

.list-sort-select {
  width: 120px;
  margin: 8px 0 0;
  flex: none;
}

.list-filter-hint {
  margin: 8px 4px 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 骨架屏：与真实卡片同样的“封面 + 三行文字”结构，避免首屏是纯空白 */
.skeleton-card {
  display: flex;
  align-items: center;
  gap: 10px;
}

.skeleton-cover {
  width: 92px;
  height: 130px;
  flex: none;
  border-radius: var(--el-border-radius-base);
}

.skeleton-info {
  flex: 1;
  min-width: 0;
}

.list-bottom-spacer {
  height: 8px;
}

/* 任务管理器「定位订阅」/ 添加成功后的目标卡片高亮 */
.list-item-highlight {
  border-radius: var(--el-border-radius-base);
  animation: list-highlight-pulse 2.4s var(--ease-apple);
}

@keyframes list-highlight-pulse {
  0%, 100% {
    box-shadow: 0 0 0 0 transparent;
  }

  15%, 60% {
    box-shadow: 0 0 0 3px var(--el-color-primary-light-5);
  }
}

@media (max-width: 640px) {
  .list-sort-select {
    width: 104px;
  }
}
</style>


