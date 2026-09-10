/**
 * 播放进度 / 已看标记（纯前端，localStorage）
 *
 * 追番场景每周都要重新找"我看到哪了"，而此前选集弹窗既不标记已看、
 * 也不提示上次看到哪一集。这里按订阅 id 记录"最后一个播放的条目"，
 * 供选集列表高亮；真正的秒级续播由 Artplayer 自带的 autoPlayback 负责
 * （它以播放地址为键，已能跨会话恢复）。
 */

const LAST_WATCHED_KEY = 'ani-rss-last-watched'

const readAll = () => {
  try {
    const raw = localStorage.getItem(LAST_WATCHED_KEY)
    if (!raw) {
      return {}
    }
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch (e) {
    return {}
  }
}

const writeAll = map => {
  try {
    localStorage.setItem(LAST_WATCHED_KEY, JSON.stringify(map))
  } catch (e) {
    // 隐私模式 / 配额不足时静默降级，不影响播放
  }
}

/**
 * 记录某订阅最后播放的条目
 * @param {string} aniId 订阅 id（必填，否则不记录）
 * @param {object} item  播放条目（含 filename / title）
 */
export const markWatched = (aniId, item) => {
  if (!aniId || !item) {
    return
  }
  const key = item.filename || item.title
  if (!key) {
    return
  }
  const map = readAll()
  map[aniId] = {
    key,
    title: item.title || '',
    at: Date.now()
  }
  writeAll(map)
}

/**
 * 判断某条目是否是该订阅"上次看到"的那一集
 * @returns {boolean}
 */
export const isLastWatched = (aniId, item) => {
  if (!aniId || !item) {
    return false
  }
  const record = readAll()[aniId]
  if (!record) {
    return false
  }
  const key = item.filename || item.title
  return Boolean(key) && record.key === key
}

/**
 * 取该订阅"上次看到"的记录（无则返回 null），用于列表顶部的续播提示
 */
export const getLastWatched = aniId => {
  if (!aniId) {
    return null
  }
  return readAll()[aniId] || null
}
