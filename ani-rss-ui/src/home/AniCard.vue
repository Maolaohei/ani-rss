<template>
  <el-card shadow="never">
    <div class="list-card-content">
      <div class="list-card-image-container">
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
      </div>
      <div class="list-card-info">
        <div class="list-card-info-inner">
          <div class="flex">
            <el-tooltip :content="item.title" placement="top">
              <el-text :line-clamp="1"
                       @click="openBgmUrl(item)"
                       class="list-card-title"
                       truncated>
                {{ item.title }}
              </el-text>
            </el-tooltip>
          </div>
          <div class="list-card-score-container" v-if="showScore">
            <!-- 状态收敛为评分旁一个小圆点：不占布局、不改原先高度，点开即可看到原因 -->
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
            <h4 class="list-card-score" @click="emit('rate', item)">
              {{ item['score'].toFixed(1) }}
            </h4>
          </div>
          <!-- 健康原因在触屏无法 hover：改用可点击 popover；标签样式与原版一致 -->
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
          <!-- 与历史一致：仅在关闭「显示评分」且没有健康分时，才用 RSS 地址当副标题 -->
          <el-text v-else-if="!showScore"
                   line-clamp="2"
                   size="small"
                   class="list-card-url">
            {{ safeUrl }}
          </el-text>
          <!-- 沿用原版网格：固定 3 列（桌面）/ 2 列（窄屏），不新增行、不改变卡片高度节奏 -->
          <div class="list-card-tags"
               :class="isNotMobile ? 'gtc3' : 'gtc2'"
          >
            <el-tag v-if="showSeasonTag">
              第 {{ item.season }} 季
            </el-tag>
            <el-tag type="success" v-if="item.enable">
              已启用
            </el-tag>
            <el-tag type="info" v-else>
              未启用
            </el-tag>
            <el-tag type="info">
              <el-tooltip :content="item['subgroup']">
                <el-text line-clamp="1" size="small" class="list-card-subgroup">
                  {{ item['subgroup'] ? item['subgroup'] : '未知字幕组' }}
                </el-text>
              </el-tooltip>
            </el-tag>
            <!-- 进度：仍是原来那一格，紧凑写法不撑开格子；提示里给完整语义 -->
            <el-tooltip :content="progressTooltip" placement="top">
              <el-tag type="warning">
                {{ progressText }}
              </el-tag>
            </el-tooltip>
            <el-tag type="danger" v-if="item.ova">
              ova
            </el-tag>
            <el-tag type="danger" v-else>
              tv
            </el-tag>
            <el-tag v-if="item.standbyRssList.length > 0">
              备用RSS
            </el-tag>
          </div>
          <el-text v-if="showLastDownloadTime && item.lastDownloadTime > 0" size="small"
                   type="info">
            {{ item.lastDownloadFormat }}
          </el-text>
        </div>
        <!-- 操作区沿用原版：absolute 竖排浮层，不占布局、悬停表现与原样一致 -->
        <div class="list-card-actions">
          <el-button text @click="emit('playlist', item)" bg v-if="showPlaylist"
                     aria-label="查看视频列表" title="查看视频列表">
            <el-icon>
              <Files/>
            </el-icon>
          </el-button>
          <div class="list-card-spacer" v-if="showPlaylist"></div>
          <el-button bg text @click="emit('edit', item)" aria-label="修改订阅" title="修改订阅">
            <el-icon>
              <EditIcon/>
            </el-icon>
          </el-button>
          <div class="list-card-spacer"></div>
          <el-button type="danger" text @click="emit('del', [item])" bg
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
import {isNotMobile, showLastDownloadTime, showPlaylist, showScore, toApiFile} from "@/js/global.js";
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

/** 第 1 季是默认值，不必占一格（原版会把 3 列网格挤到第二行） */
const showSeasonTag = computed(() => {
  const season = props.item?.season
  return typeof season === 'number' && season > 1
})

/** 进度：仍是原来的紧凑写法，避免长文案把 180px 的格子撑开 */
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

/** 需要用户注意的原因，统一收进评分旁的小圆点，不再额外占布局 */
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
  align-items: center;
}

.list-card-image-container {
  height: 100%;
  border-radius: var(--el-border-radius-base);
  overflow: hidden;
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
  transition: transform 320ms cubic-bezier(0.32, 0.72, 0, 1);
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

.list-card-info {
  flex-grow: 1;
  position: relative;
}

.list-card-info-inner {
  margin-left: 8px;
}

.list-card-title {
  width: 200px;
  line-height: 1.6;
  letter-spacing: 0.0125em;
  font-weight: 500;
  font-size: 0.97em;
  cursor: pointer;
  color: var(--el-text-color-primary);
}

/* 原样高度：flex 行高由 h4 决定，圆点不改变容器高度，评分位置不变 */
.list-card-score-container {
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 6px;
}

/* 状态圆点：8px，在评分左侧，不改变容器高度 */
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

.list-card-score {
  color: #E800A4;
  cursor: pointer;
}

.list-card-url {
  max-width: 300px;
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

/* 沿用原版固定宽度与列数，保证与改动前的对齐与换行完全一致 */
.list-card-tags {
  width: 180px;
  display: grid;
  grid-gap: 4px;
}

.list-card-subgroup {
  max-width: 60px;
  color: var(--el-color-info);
}

.list-card-actions {
  display: flex;
  align-items: flex-end;
  justify-content: flex-end;
  flex-direction: column;
  position: absolute;
  right: 0;
  bottom: 0;
}

.list-card-spacer {
  height: 5px;
}

.gtc3 {
  grid-template-columns: repeat(3, 1fr);
}

.gtc2 {
  grid-template-columns: repeat(2, 1fr);
}
</style>
