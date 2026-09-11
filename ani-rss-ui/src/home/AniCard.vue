<template>
  <el-card shadow="never" class="list-card">
    <div class="list-card-content">
      <div class="list-card-image-container" :class="{ 'is-disabled': !item.enable }">
        <!-- 语义化：button 承载点击，键盘可 Tab + 回车，读屏有名称；视觉与原 img 完全一致 -->
        <button
            type="button"
            class="list-card-cover"
            :aria-label="`更换《${item.title}》的封面`"
            title="更换封面"
            @click="emit('cover', item)">
          <img :src="coverSrc"
               :alt="item.title"
               class="list-card-image"
               loading="lazy"
               decoding="async"
               @error="coverFailed = true"/>
          <span v-if="coverFailed" class="list-card-cover-fallback" aria-hidden="true">
            <el-icon>
              <Picture/>
            </el-icon>
          </span>
        </button>
        <!-- 评分徽章：海报右上角，按分数分级配色；与封面按钮平级，避免嵌套可点击元素 -->
        <button v-if="scoreText"
                type="button"
                class="list-card-score-badge"
                :style="{ backgroundColor: scoreColor }"
                :aria-label="`BGM 评分 ${scoreText}，点击打分`"
                :title="`BGM 评分 ${scoreText}`"
                @click.stop="emit('rate', item)">
          {{ scoreText }}
        </button>
      </div>
      <div class="list-card-info">
        <div class="list-card-info-inner">
          <el-tooltip :content="item.title" placement="top">
            <el-text :line-clamp="1"
                     @click="openBgmUrl(item)"
                     class="list-card-title"
                     truncated>
              {{ item.title }}
            </el-text>
          </el-tooltip>

          <!-- 与历史一致：仅在关闭「显示评分」且没有健康分时，才用 RSS 地址当副标题 -->
          <el-text v-if="!showScore && item.healthScore == null"
                   line-clamp="2"
                   size="small"
                   class="list-card-url">
            {{ safeUrl }}
          </el-text>

          <!-- 次要信息一行灰字：TV/OVA · 第 X 季 · 字幕组 -->
          <div class="list-card-meta">
            <span>{{ item.ova ? 'OVA' : 'TV' }}</span>
            <span v-if="showSeasonTag">· 第 {{ item.season }} 季</span>
            <el-tooltip :content="item['subgroup']">
              <span class="list-card-subgroup">
                · {{ item['subgroup'] ? item['subgroup'] : '未知字幕组' }}
              </span>
            </el-tooltip>
          </div>

          <!-- 集数进度：文字 + 细进度条（仅总集数已知时），提示里给完整语义 -->
          <el-tooltip :content="progressTooltip" placement="top">
            <div class="list-card-progress-row">
              <span class="list-card-episode">{{ progressText }}</span>
              <div v-if="showProgressTrack" class="list-card-progress-track">
                <div class="list-card-progress-fill" :style="{ width: progressPercent + '%' }"></div>
              </div>
            </div>
          </el-tooltip>

          <!-- 状态行：注意圆点 · 启用状态 · 备用RSS · 健康分，末尾右对齐最近下载时间 -->
          <div class="list-card-status-row">
            <el-popover trigger="click" placement="top-start" :width="240">
              <template #reference>
                <span class="list-card-dot"
                      :class="{ 'is-warn': attentionReasons.length }"
                      :aria-label="attentionSummary"
                      :title="attentionSummary"
                      role="img"
                      tabindex="0"></span>
              </template>
              <div class="list-card-info-body">
                <div class="list-card-info-title">{{ item.title }}</div>
                <ul class="list-card-info-list">
                  <li v-for="(reason, index) in attentionReasons" :key="index">{{ reason }}</li>
                </ul>
                <div v-if="!attentionReasons.length" class="list-card-info-ok">暂无异常</div>
              </div>
            </el-popover>
            <span class="list-card-status" :class="item.enable ? 'is-enabled' : 'is-disabled'">
              {{ item.enable ? '已启用' : '未启用' }}
            </span>
            <span v-if="item.standbyRssList?.length > 0" class="list-card-standby">
              备用RSS
            </span>
            <!-- 健康原因在触屏无法 hover：改用可点击 popover -->
            <el-popover v-if="item.healthScore != null"
                        trigger="click"
                        placement="top-start"
                        :width="240">
              <template #reference>
                <el-tag size="small" :type="healthTagType" class="list-card-health">
                  健康 {{ item.healthScore }}
                </el-tag>
              </template>
              <div class="list-card-info-body">
                <div class="list-card-info-title">运维健康分（非 BGM 评分）</div>
                <ul class="list-card-info-list">
                  <li v-for="(reason, index) in healthReasons" :key="index">{{ reason }}</li>
                </ul>
              </div>
            </el-popover>
            <el-text v-if="showLastDownloadTime && item.lastDownloadTime > 0"
                     size="small"
                     type="info"
                     class="list-card-time">
              {{ item.lastDownloadFormat }}
            </el-text>
          </div>
        </div>
        <!-- 操作区：默认隐藏，悬停/聚焦时浮现；触屏设备常显 -->
        <div class="list-card-actions">
          <el-button text bg @click="emit('playlist', item)" v-if="showPlaylist"
                     aria-label="查看视频列表" title="查看视频列表">
            <el-icon>
              <Files/>
            </el-icon>
          </el-button>
          <el-button bg text @click="emit('edit', item)" aria-label="修改订阅" title="修改订阅">
            <el-icon>
              <EditIcon/>
            </el-icon>
          </el-button>
          <el-button type="danger" text bg @click="emit('del', [item])"
                     aria-label="删除订阅" title="删除订阅">
            <el-icon>
              <Delete/>
            </el-icon>
          </el-button>
        </div>
      </div>
    </div>
  </el-card>
</template>

<script setup>
import {computed, ref} from "vue";
import {showLastDownloadTime, showPlaylist, showScore, toApiFile} from "@/js/global.js";
import {Delete, Edit as EditIcon, Files, Picture} from "@element-plus/icons-vue";

let openBgmUrl = (it) => {
  if (it.bgmUrl && it.bgmUrl.length) {
    window.open(it.bgmUrl)
    return
  }
  if (it.title && it.title.length) {
    let title = it.title.replace(/ ?\((19|20)\d{2}\)/g, "").trim()
    title = title.replace(/ ?\[tmdbid=(\d+)]/g, "").trim()
    window.open(`https://bgm.tv/subject_search/${title}?cat=2`)
  }
}

/**
 * URL 可能来自用户/源站，含裸 % 时 decodeURIComponent 会抛 URIError；
 * 单个卡片抛错会让整块列表渲染失败（首页空白），所以这里必须兜底。
 */
let decodeURLComponentSafe = (str) => {
  if (typeof str !== 'string') {
    return ''
  }
  try {
    return decodeURIComponent(str.replace(/\+/g, ' '));
  } catch (e) {
    return str
  }
}

const emit = defineEmits(['edit', 'playlist', 'cover', 'del', 'rate'])
let props = defineProps(["item"])

const coverFailed = ref(false)

const coverSrc = computed(() => {
  const cover = props.item?.cover
  return cover ? toApiFile(cover) : ''
})

const safeUrl = computed(() => decodeURLComponentSafe(props.item?.url || ''))

/** 第 1 季是默认值，不必展示（原版会把信息行挤到换行） */
const showSeasonTag = computed(() => {
  const season = props.item?.season
  return typeof season === 'number' && season > 1
})

/** 进度：仍是原来的紧凑写法，避免长文案撑开卡片 */
const progressText = computed(() => {
  const current = props.item?.currentEpisodeNumber
  const total = props.item?.totalEpisodeNumber
  if (current == null || current <= 0) {
    return '暂无记录'
  }
  return `${current} / ${total ? total : '*'}`
})

const progressTooltip = computed(() => {
  const current = props.item?.currentEpisodeNumber
  const total = props.item?.totalEpisodeNumber
  if (current == null || current <= 0) {
    return '尚无下载记录'
  }
  return total
      ? `已下载到第 ${current} 集 / 共 ${total} 集`
      : `已下载到第 ${current} 集（总集数未知）`
})

/** 细进度条只在总集数为已知数字时出现；未知总集数(*)不画条、不做除法 */
const showProgressTrack = computed(() => {
  const total = Number(props.item?.totalEpisodeNumber)
  return Number.isFinite(total) && total > 0
})

const progressPercent = computed(() => {
  const total = Number(props.item?.totalEpisodeNumber)
  const current = Number(props.item?.currentEpisodeNumber)
  if (!Number.isFinite(total) || total <= 0) {
    return 0
  }
  const base = Number.isFinite(current) ? current : 0
  return Math.min(100, Math.max(0, (base / total) * 100))
})

/**
 * 评分徽章：仅在开启「显示评分」且评分有效时展示；
 * 按数值分级配色，全部使用 Element Plus 主题变量（暗色模式自适应）。
 */
const scoreText = computed(() => {
  if (!showScore.value) {
    return ''
  }
  const score = Number(props.item?.score)
  return Number.isFinite(score) && score > 0 ? score.toFixed(1) : ''
})

const scoreColor = computed(() => {
  const score = Number(props.item?.score)
  if (!Number.isFinite(score)) {
    return 'var(--el-color-info)'
  }
  if (score >= 7) {
    return 'var(--el-color-success)'
  }
  if (score >= 5) {
    return 'var(--el-color-warning)'
  }
  return 'var(--el-color-danger)'
})

/** 需要用户注意的原因，统一收进状态行的小圆点，不再额外占布局 */
const attentionReasons = computed(() => {
  const reasons = []
  const omit = props.item?.omitCount
  if (typeof omit === 'number' && omit > 0) {
    reasons.push(`疑似漏集 ${omit} 处`)
  }
  const notDownload = props.item?.notDownload
  if (Array.isArray(notDownload) && notDownload.length) {
    reasons.push(`已禁止下载 ${notDownload.length} 集`)
  }
  if (props.item?.procrastinating) {
    reasons.push('摸鱼中：不自动下载新集')
  }
  const weekLabel = props.item?.weekLabel
  if (weekLabel) {
    const today = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'][new Date().getDay()]
    reasons.push(weekLabel === today ? '今天更新' : `${weekLabel}更新`)
  }
  return reasons
})

const attentionSummary = computed(() => {
  return attentionReasons.value.length ? attentionReasons.value.join('；') : '查看订阅状态'
})

const healthTagType = computed(() => {
  const l = props.item?.healthLevel
  if (l === 'good' || l === 'completed') return 'success'
  if (l === 'warn') return 'warning'
  if (l === 'bad') return 'danger'
  return 'info'
})

const healthReasons = computed(() => {
  const rs = props.item?.healthReasons
  if (Array.isArray(rs) && rs.length) {
    return rs
  }
  return ['运维健康分（非 BGM 评分）']
})
</script>

<style scoped>
.list-card-content {
  display: flex;
  width: 100%;
  align-items: flex-start;
}

.list-card-image-container {
  position: relative;
  height: 100%;
  border-radius: var(--el-border-radius-base);
}

/* 未启用订阅：封面置灰降饱和，一眼可辨 */
.list-card-image-container.is-disabled .list-card-image {
  filter: grayscale(1) brightness(0.75);
}

/* 封面按钮：去掉按钮默认外观，尺寸/圆角/边框与原 img 完全一致 */
.list-card-cover {
  position: relative;
  display: block;
  padding: 0;
  border: none;
  background: none;
  cursor: pointer;
  border-radius: var(--el-border-radius-base);
  line-height: 0;
}

.list-card-cover:focus-visible {
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: 2px;
}

.list-card-image {
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--el-border-radius-base);
  cursor: pointer;
  height: 130px;
  width: 92px;
  object-fit: cover;
  display: block;
}

.list-card-cover-fallback {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  color: var(--el-text-color-placeholder);
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--el-border-radius-base);
  line-height: 1;
}

/* 评分徽章：海报右上角小圆角胶囊，配色随分数分级 */
.list-card-score-badge {
  position: absolute;
  top: 6px;
  right: 6px;
  min-width: 28px;
  height: 20px;
  padding: 0 6px;
  border: none;
  border-radius: 999px;
  color: #ffffff;
  font-size: 12px;
  font-weight: 500;
  line-height: 20px;
  text-align: center;
  cursor: pointer;
}

.list-card-score-badge:focus-visible {
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: 1px;
}

.list-card-info {
  flex-grow: 1;
  position: relative;
  min-width: 0;
}

.list-card-info-inner {
  margin-left: 8px;
}

.list-card-title {
  width: 100%;
  line-height: 1.6;
  letter-spacing: 0.0125em;
  font-weight: 500;
  font-size: 0.97em;
  cursor: pointer;
  color: var(--el-text-color-primary);
}

.list-card-url {
  max-width: 300px;
  display: block;
}

/* 次要信息：TV/OVA · 第 X 季 · 字幕组，一行灰字 */
.list-card-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 4px 0 8px;
}

.list-card-subgroup {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 140px;
}

/* 集数进度：文字 + 细进度条 */
.list-card-progress-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.list-card-episode {
  font-size: 12px;
  font-weight: 500;
  color: var(--el-text-color-primary);
  white-space: nowrap;
}

.list-card-progress-track {
  flex: 1;
  height: 4px;
  border-radius: 2px;
  background: var(--el-fill-color-light);
  overflow: hidden;
}

.list-card-progress-fill {
  height: 100%;
  background: var(--el-color-primary);
  border-radius: 2px;
}

/* 状态行：注意圆点 / 启用状态 / 备用RSS / 健康分 / 最近下载时间 */
.list-card-status-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 12px;
}

.list-card-status {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.list-card-status::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.list-card-status.is-enabled {
  color: var(--el-color-success);
}

.list-card-status.is-disabled {
  color: var(--el-text-color-secondary);
}

.list-card-standby {
  color: var(--el-color-warning);
}

.list-card-time {
  margin-left: auto;
}

/* 状态圆点：8px，可点击查看注意原因，不改变行高 */
.list-card-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--el-border-color);
  cursor: pointer;
}

.list-card-dot.is-warn {
  background: var(--el-color-warning);
}

.list-card-dot:focus-visible {
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: 2px;
}

.list-card-info-body {
  font-size: 12.5px;
  line-height: 1.6;
}

.list-card-info-title {
  font-weight: 600;
  margin-bottom: 6px;
  color: var(--el-text-color-primary);
}

.list-card-info-list {
  margin: 0;
  padding-left: 18px;
  color: var(--el-text-color-regular);
}

.list-card-info-ok {
  color: var(--el-text-color-secondary);
}

/* 操作区：默认隐藏，卡片悬停或按钮获得焦点时浮现（与封面模式一致的交互语言）；
   触屏设备无 hover，始终显示 */
.list-card-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  position: absolute;
  right: 0;
  bottom: 0;
  opacity: 0;
  transform: translateY(4px);
  pointer-events: none;
  transition: opacity 0.15s ease, transform 0.15s ease;
}

.list-card:hover .list-card-actions,
.list-card-actions:focus-within {
  opacity: 1;
  transform: translateY(0);
  pointer-events: auto;
}

@media (hover: none) {
  .list-card-actions {
    opacity: 1;
    transform: none;
    pointer-events: auto;
  }
}
</style>
