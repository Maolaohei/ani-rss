<template>
  <EditAniView ref="editAniRef"/>
  <PlayListView ref="playListRef"/>
  <CoverView ref="coverRef"/>
  <DelAniView ref="delAniRef"/>
  <BgmRateView ref="bgmRateRef"/>
  <div class="list-container" v-loading="loading">
    <el-scrollbar class="hide-scrollbar">
      <div class="list-content">
        <div class="list-toolbar">
          <el-text size="small" type="info">
            {{ hasFilter ? `筛出 ${flatFilterList.length} / ${allCount} 项` : `共 ${allCount} 项` }}
          </el-text>
          <el-select
              v-model="sortType"
              class="list-sort-select"
              size="small"
              aria-label="订阅排序方式">
            <el-option
                v-for="option in sortOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"/>
          </el-select>
        </div>
        <el-alert v-if="loadError" class="list-error" type="error" show-icon :closable="false">
          <template #title>
            <div class="list-error-row">
              <span>{{ loadError }}</span>
              <el-button size="small" bg text @click="getList">重试</el-button>
            </div>
          </template>
        </el-alert>
        <template v-else>
          <el-empty v-if="!allCount" description="还没有订阅，点右上角「添加订阅」开始追番"/>
          <el-empty v-else-if="!flatFilterList.length" description="没有符合条件的订阅">
            <el-button size="small" bg text @click="clearFilter">清空筛选</el-button>
          </el-empty>
        </template>
        <template v-if="showWeek">
          <div v-for="weekItem in filterList" :key="weekItem.weekLabel">
            <h2 class="list-week-title" :class="{'is-today': weekItem.isToday}">
              {{ weekItem.weekLabel }}
              <el-tag v-if="weekItem.isToday" class="list-today-tag" size="small" type="primary">今天</el-tag>
            </h2>
            <div :class="gridClass">
              <div v-for="item in weekItem.items" :key="item.id"
                   v-memo="[item, highlightId === item.id, viewComponent]"
                   :data-ani-id="item.id"
                   :class="{'list-item-highlight': highlightId === item.id}">
                <component
                    :is="viewComponent"
                    :item="item"
                    @edit="editAniRef?.show"
                    @playlist="playListRef?.show"
                    @cover="coverRef?.show"
                    @del="delAniRef?.show"
                    @rate="bgmRateRef?.show"
                />
              </div>
            </div>
          </div>
        </template>
        <template v-else>
          <div :class="gridClass">
            <div v-for="item in flatFilterList" :key="item.id"
                 v-memo="[item, highlightId === item.id, viewComponent]"
                 :data-ani-id="item.id"
                 :class="{'list-item-highlight': highlightId === item.id}">
              <component
                  :is="viewComponent"
                  :item="item"
                  @edit="editAniRef?.show"
                  @playlist="playListRef?.show"
                  @cover="coverRef?.show"
                  @del="delAniRef?.show"
                  @rate="bgmRateRef?.show"
              />
            </div>
          </div>
        </template>
        <div class="list-bottom-spacer"></div>
      </div>
    </el-scrollbar>
  </div>
</template>

<script setup>
import {computed, nextTick, onActivated, onMounted, onUnmounted, ref, watch} from "vue";
import {useRoute} from "vue-router";
import EditAniView from "./EditAniView.vue";
import PlayListView from "@/view/play/PlayListView.vue";
import CoverView from "./CoverView.vue";
import DelAniView from "./DelAniView.vue";
import BgmRateView from "./BgmRateView.vue";
import {listAni} from "@/js/http.js";
import AniCardView from "@/view/home/AniCardView.vue";
import AniCoverView from "@/view/home/AniCoverView.vue";
import {showWeek} from "@/js/global.js";

const props = defineProps({
  title: String,
  filter: Function,
  viewMode: {
    type: String,
    default: 'card'
  }
})
const emit = defineEmits(['loaded', 'clear-filter'])

const editAniRef = ref()
const delAniRef = ref()
const coverRef = ref()
const playListRef = ref()
const bgmRateRef = ref()

const weekList = ref([])
const releaseDateList = ref([])

const loading = ref(true)
const loadError = ref('')
const viewComponent = computed(() => props.viewMode === 'cover' ? AniCoverView : AniCardView)
const gridClass = computed(() => [
  'grid-container',
  props.viewMode === 'cover' ? 'cover-grid-container' : 'card-grid-container'
])

/** 首页排序：后端默认顺序 / 最近更新 / 评分 / 标题拼音（fork 移植） */
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

/** 关键词由父组件传入（父层已做 250ms 防抖），这里只做归一化 */
const keyword = computed(() => String(props.title || '').trim().toLowerCase())

const matchesKeyword = item => {
  const kw = keyword.value
  if (!kw) {
    return true
  }
  let {title, pinyin, pinyinInitials, subgroup} = item
  return (title || '').toLowerCase().indexOf(kw) > -1 ||
      (pinyin || '').toLowerCase().indexOf(kw) > -1 ||
      (pinyinInitials || '').toLowerCase().indexOf(kw) > -1 ||
      (subgroup || '').toLowerCase().indexOf(kw) > -1
}

/**
 * 派生列表：不再对整棵 weekList 做 JSON 深拷贝。
 * 此前每个搜索字符都会 JSON.parse(JSON.stringify(...)) 使全部 item 引用失效，
 * 导致 200+ 卡片全量重渲染。现在 item 引用保持稳定，
 * 配合模板上的 v-memo，未变化的卡片会被整体跳过 diff。
 */
const filterList = computed(() => {
  const todayLabel = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'][new Date().getDay()]

  const groups = weekList.value
      .map(week => {
        let items = (week.items || []).filter(props.filter).filter(matchesKeyword)
        if (sortType.value !== 'default') {
          items = sortFlatList(items)
        }
        return {
          weekLabel: week.weekLabel,
          isToday: week.weekLabel === todayLabel,
          items
        }
      })
      .filter(it => it.items.length)

  // 今天置顶：后端已把今天排首位，这里仅在缺失时补一次，保证"今天更新了哪几部"一眼可见
  const todayIndex = groups.findIndex(it => it.isToday)
  if (todayIndex > 0) {
    const [todayGroup] = groups.splice(todayIndex, 1)
    groups.unshift(todayGroup)
  }

  return groups
})

const flatFilterList = computed(() => {
  const flat = filterList.value.flatMap(it => it.items)
  return sortType.value === 'default' ? flat : sortFlatList(flat)
})

const allCount = computed(() => weekList.value.reduce((total, week) => total + (week.items || []).length, 0))

const hasFilter = computed(() => {
  if (allCount.value === 0) {
    return false
  }
  return flatFilterList.value.length !== allCount.value
})

const clearFilter = () => {
  emit('clear-filter')
}

const getList = () => {
  loading.value = true
  loadError.value = ''

  listAni()
      .then(res => {
        let data = res.data
        weekList.value = data.weekList
        releaseDateList.value = data.releaseDateList
        emit('loaded', {
          releaseDateList: releaseDateList.value,
          total: weekList.value.reduce((total, week) => total + week.items.length, 0)
        })
      })
      .catch(e => {
        loadError.value = e?.message || '订阅列表加载失败，请检查服务是否可用'
      })
      .finally(() => {
        loading.value = false
      })
}

/** 任务中心跳转定位（?focusAni=）：清筛选→滚动→短暂高亮 */
const highlightId = ref(null)
let highlightTimer = null

const focusAni = aniId => {
  if (!aniId) {
    return
  }
  // 目标可能被筛选藏住：让父层清掉全部筛选（关键词/启用/日期）再定位。
  // 关键词经父层防抖生效，首个 nextTick 可能落空，由 focusAniWithRetry 的重试兜底
  emit('clear-filter')
  nextTick(() => {
    const el = document.querySelector(`[data-ani-id="${aniId}"]`)
    if (!el) {
      return
    }
    el.scrollIntoView({behavior: 'smooth', block: 'center'})
    highlightId.value = aniId
    clearTimeout(highlightTimer)
    highlightTimer = setTimeout(() => {
      highlightId.value = null
    }, 2000)
  })
}

/** 跨页定位（任务中心 → 订阅列表）：首次进入时列表可能尚未加载完，做有限重试 */
const focusAniWithRetry = (aniId, attempts = 0) => {
  if (!aniId || attempts > 4) {
    return
  }
  focusAni(aniId)
  setTimeout(() => {
    if (!document.querySelector(`[data-ani-id="${aniId}"]`)) {
      focusAniWithRetry(aniId, attempts + 1)
    }
  }, 600)
}

const route = useRoute()

const focusFromQuery = () => {
  const id = route.query.focusAni
  if (id && route.path === '/subscriptions') {
    focusAniWithRetry(String(id))
  }
}

watch(() => route.query.focusAni, focusFromQuery)

onMounted(() => {
  window.$reLoadList = getList
  window.$focusAni = focusAni
  getList()
  focusFromQuery()
})

onActivated(() => {
  focusFromQuery()
})

onUnmounted(() => {
  if (window.$focusAni === focusAni) {
    delete window.$focusAni
  }
  clearTimeout(highlightTimer)
})

defineExpose({
  releaseDateList,
  getList
})

</script>

<style scoped>
.grid-container {
  display: grid;
  grid-gap: 8px;
  width: 100%;
}

.list-container {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.hide-scrollbar {
  flex: 1;
  min-height: 0;
}

.list-content {
  margin: 0;
  background: var(--el-bg-color);
  border-radius: 8px;
  padding: 10px 12px 12px;
}

.list-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 4px 0 0 4px;
}

.list-sort-select {
  width: 118px;
}

.list-week-title {
  margin-top: 12px;
  margin-bottom: 4px;
}

.list-week-title.is-today {
  color: var(--el-color-primary);
}

.list-today-tag {
  margin-left: 6px;
}

.list-item-highlight {
  animation: list-item-flash 2s ease-out;
  border-radius: 10px;
}

@keyframes list-item-flash {
  0%, 40% {
    box-shadow: 0 0 0 2px var(--el-color-primary-light-5);
  }
  100% {
    box-shadow: 0 0 0 2px transparent;
  }
}

.list-error {
  margin: 12px 4px;
}

.list-error-row {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.list-bottom-spacer {
  height: 8px;
}

.card-grid-container {
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
}

.cover-grid-container {
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  grid-gap: 24px;
}

/*
 * 屏外卡片跳过渲染/绘制：200+ 订阅全量挂载时滚动卡顿的主要来源。
 * contain-intrinsic-size 提供估算高度（元素渲染过一次后浏览器会记住真实尺寸），
 * 滚动条高度稳定，任务中心跳转的 scrollIntoView 锚点定位不受影响；
 * 旧浏览器不支持该属性时自动降级为全量渲染。
 */
.cover-grid-container > * {
  content-visibility: auto;
  contain-intrinsic-size: auto 260px;
}

.card-grid-container > * {
  content-visibility: auto;
  contain-intrinsic-size: auto 172px;
}

@media (max-width: 800px) {
  .card-grid-container {
    grid-template-columns: 1fr;
  }

  .cover-grid-container {
    grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
    grid-gap: 12px;
  }
}
</style>
