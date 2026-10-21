export const formatDuration = (ms, force = false) => {
  const n = Number(ms || 0)
  if (!force && n <= 0) return '-'
  if (!n && n !== 0) return '-'
  const totalSec = Math.floor(Math.max(0, n) / 1000)
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  if (h > 0) return `${h}小时${m}分${s}秒`
  if (m > 0) return `${m}分${s}秒`
  return `${s}秒`
}

export const formatTime = ts => {
  const n = Number(ts || 0)
  if (!n) return '-'
  try {
    return new Date(n).toLocaleString()
  } catch (_) {
    return String(n)
  }
}
