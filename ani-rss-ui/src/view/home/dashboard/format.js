export const weekLabels = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']

export const dayMs = 24 * 60 * 60 * 1000

export const barHeight = (value, max) => Math.max(value > 0 ? 4 : 0, Math.round((value / max) * 56))

export const shortDate = date => (date || '').slice(5)
