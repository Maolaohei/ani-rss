export const SUBTITLE_EXT = ['ass', 'srt', 'ssa', 'vtt', 'sub']
export const MAX_FILE_BYTES = 20 * 1024 * 1024
export const SUBTITLE_ACCEPT = SUBTITLE_EXT.map(ext => `.${ext}`).join(',')

export const statusLabel = status => ({
  '已匹配': '已匹配',
  '未命中': '未命中',
  '无候选': '无候选'
}[status] || status || '-')

export const statusType = status => ({
  '已匹配': 'success',
  '未命中': 'warning',
  '无候选': 'info'
}[status] || 'info')
