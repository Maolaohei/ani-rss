import {computed, onActivated, onDeactivated, onUnmounted, ref} from "vue";
import * as http from "@/js/http.js";
import {dayMs, weekLabels} from "./format.js";

const downloadingStates = ['forcedDL', 'downloading', 'forcedMetaDL', 'metaDL', 'stalledDL', 'checkingDL', 'queuedDL', 'allocating', 'moving']
const seedingStates = ['forcedUP', 'uploading', 'stalledUP', 'queuedUP', 'checkingUP']

const POLL_ACTIVE_MS = 5000
const POLL_IDLE_MS = 30000

export function useDashboard() {
  const refreshLoading = ref(false)
  const weekList = ref([])
  const subscriptionTotalValue = ref(0)
  const torrentsInfos = ref([])
  const config = ref({
    procrastinatingDay: 14
  })
  const historyDays = ref([])
  const historySummary = ref({success: 0, failed: 0, skip: 0, successRate: 1, size: 0})

  let timer
  let polling = false

  const todayLabel = computed(() => weekLabels[new Date().getDay()])
  const flatAnis = computed(() => weekList.value.flatMap(week => week.items || []))
  const enabledAnis = computed(() => flatAnis.value.filter(item => item.enable))
  const subscriptionTotal = computed(() => subscriptionTotalValue.value || flatAnis.value.length)
  const enabledTotal = computed(() => enabledAnis.value.length)
  const todayAnis = computed(() => {
    const today = weekList.value.find(week => week.weekLabel === todayLabel.value)
    return (today ? today.items || [] : []).filter(item => item.enable)
  })
  const todayText = computed(() => todayAnis.value.length ? `${todayAnis.value.length} 个订阅` : '没有订阅')

  const isDownloading = item => downloadingStates.includes(item.state)
  const isSeeding = item => seedingStates.includes(item.state)

  const downloadingList = computed(() => torrentsInfos.value.filter(isDownloading))
  const seedingList = computed(() => torrentsInfos.value.filter(isSeeding))
  const procrastinatingList = computed(() => {
    const threshold = Number(config.value.procrastinatingDay || 14)
    return enabledAnis.value
        .filter(item => item.procrastinating !== false)
        .filter(item => !item.totalEpisodeNumber || item.currentEpisodeNumber < item.totalEpisodeNumber)
        .map(item => {
          const time = getCompareTime(item)
          const procrastinatingDays = time ? Math.floor((Date.now() - time) / dayMs) : 0
          return {
            ...item,
            procrastinatingDays
          }
        })
        .filter(item => item.procrastinatingDays >= threshold)
        .sort((a, b) => b.procrastinatingDays - a.procrastinatingDays)
  })

  const getCompareTime = item => {
    if (item.lastDownloadTime > 0) {
      return item.lastDownloadTime
    }
    const releaseTime = new Date(item.releaseDate).getTime()
    return Number.isNaN(releaseTime) ? 0 : releaseTime
  }

  const successRateText = computed(() => `${Math.round((historySummary.value.successRate || 0) * 100)}%`)

  /**
   * 健康分布：按 healthLevel 归桶。健康分由后端在 listAni 时算好，
   * 这里只做聚合，不再额外请求。
   */
  const healthBuckets = computed(() => {
    const buckets = [
      {level: 'good', label: '良好', count: 0},
      {level: 'warn', label: '注意', count: 0},
      {level: 'bad', label: '异常', count: 0},
      {level: 'paused', label: '已停用', count: 0},
      {level: 'completed', label: '已完结', count: 0}
    ]
    const map = new Map(buckets.map(b => [b.level, b]))
    for (const ani of enabledAnis.value) {
      const bucket = map.get(ani.healthLevel) || map.get('warn')
      bucket.count += 1
    }
    return buckets
  })

  /**
   * 漏集 TOP：用 RSS 周期缓存的 omitCount，不额外拉源
   */
  const omitTop = computed(() => enabledAnis.value
      .filter(item => Number(item.omitCount || 0) > 0)
      .map(item => ({...item, omitCount: Number(item.omitCount)}))
      .sort((a, b) => b.omitCount - a.omitCount)
      .slice(0, 8))

  const loadAni = async () => {
    const res = await http.listAni()
    weekList.value = res.data.weekList || []
    subscriptionTotalValue.value = res.data.total || 0
  }

  const loadConfig = async () => {
    const res = await http.config()
    config.value = res.data || config.value
  }

  /**
   * 按 hash 做增量更新：只替换内容真正变化的任务对象，未变的沿用旧引用。
   * 整体替换会让 Vue 认为每张卡片都变了、每次轮询都全量重渲染（P1-11）。
   * 键序稳定（同一接口的 JSON），因此直接比字符串即可。
   */
  const mergeTorrents = list => {
    const previous = new Map(torrentsInfos.value.map(item => [item.hash, item]))
    return (list || []).map(item => {
      const old = previous.get(item.hash)
      if (old && JSON.stringify(old) === JSON.stringify(item)) {
        return old
      }
      return item
    })
  }

  const loadTorrents = async () => {
    const res = await http.torrentsInfos()
    torrentsInfos.value = mergeTorrents(res.data)
  }

  const loadHistory = async () => {
    try {
      const res = await http.downloadHistoryStats({days: 7})
      historySummary.value = res.data?.summary || historySummary.value
      historyDays.value = res.data?.days || []
    } catch (e) {
      historyDays.value = []
    }
  }

  const loadAll = async () => {
    refreshLoading.value = true
    try {
      await Promise.all([
        loadAni(),
        loadConfig(),
        loadTorrents(),
        loadHistory()
      ])
    } finally {
      refreshLoading.value = false
    }
  }

  /*
  轮询间隔自适应（P1-11）：有任务在跑时 5 秒，空闲时 30 秒。
  原实现固定 5 秒，而 /torrentsInfos 会真的打到下载器 API，与 RSS 轮次、
  网盘限流形成叠加争抢；而首页绝大多数时间是空闲的，那 5 秒一次纯属白问。
  改成 setTimeout 自调度（而不是 setInterval），间隔才能在每轮结束时重新计算。
  */
  const pollIntervalMs = () => (downloadingList.value.length > 0 || seedingList.value.length > 0)
      ? POLL_ACTIVE_MS
      : POLL_IDLE_MS

  const scheduleNextPoll = () => {
    if (!polling) {
      return
    }
    timer = setTimeout(async () => {
      try {
        await loadTorrents()
      } catch (e) {
        // 单次失败不影响后续轮询
      } finally {
        scheduleNextPoll()
      }
    }, pollIntervalMs())
  }

  const startPolling = () => {
    if (polling) {
      return
    }
    polling = true
    scheduleNextPoll()
  }

  const stopPolling = () => {
    polling = false
    clearTimeout(timer)
    timer = undefined
  }

  // 移植上游 0a544f8a: 首页被 keep-alive 复用时不走 onMounted,
  // 切回首页必须重新拉数据, 否则展示的是离开前的陈旧内容
  onActivated(() => {
    loadAll()
    startPolling()
  })
  onDeactivated(stopPolling)
  onUnmounted(stopPolling)

  return {
    refreshLoading,
    subscriptionTotal,
    enabledTotal,
    downloadingList,
    seedingList,
    todayAnis,
    todayLabel,
    todayText,
    historyDays,
    historySummary,
    successRateText,
    healthBuckets,
    omitTop,
    procrastinatingList,
    loadAll
  }
}
