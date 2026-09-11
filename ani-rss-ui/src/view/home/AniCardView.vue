<template>
  <el-card shadow="never">
    <div class="list-card-content">
      <div class="list-card-image-container">
        <img :src="toApiFile(item['cover'])"
             :alt="item.title"
             class="list-card-image"
             @error="coverFailed = true"
             @click="openBgmUrl(item)"/>
        <span v-if="coverFailed" class="list-card-cover-fallback" aria-hidden="true">
          <el-icon>
            <Picture/>
          </el-icon>
        </span>
      </div>
      <div class="list-card-info">
        <div class="list-card-info-inner">
          <div class="flex">
            <el-popover trigger="click" placement="top-start" :width="260">
              <template #reference>
                <span class="list-card-dot"
                      :class="{'is-warn': attentionReasons.length}"
                      :title="attentionSummary"
                      role="img"
                      tabindex="0"></span>
              </template>
              <div class="list-card-info-body">
                <div class="list-card-info-title">{{ item.title }}</div>
                <ul v-if="attentionReasons.length" class="list-card-info-list">
                  <li v-for="(reason, index) in attentionReasons" :key="index">{{ reason }}</li>
                </ul>
                <el-text v-else size="small" type="info">暂无异常</el-text>
              </div>
            </el-popover>
            <el-tooltip :content="item.title" placement="top">
              <el-text :line-clamp="1"
                       @click="openBgmUrl(item)"
                       class="list-card-title"
                       truncated>
                {{ item.title }}
              </el-text>
            </el-tooltip>
          </div>
          <div class="list-card-score-container" v-if="scoreText">
            <h4 class="list-card-score" @click="emit('rate', item)">
              {{ scoreText }}
            </h4>
          </div>
          <el-text v-else
                   line-clamp="2"
                   size="small"
                   class="list-card-url">
            {{ decodeURLComponentSafe(item.url) }}
          </el-text>
          <div class="list-card-tags">
            <el-tag>
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
            <el-tag type="warning">
              {{ item['currentEpisodeNumber'] }} /
              {{ item['totalEpisodeNumber'] ? item['totalEpisodeNumber'] : '*' }}
            </el-tag>
            <el-tag type="danger" v-if="item.ova">
              ova
            </el-tag>
            <el-tag type="danger" v-else>
              tv
            </el-tag>
            <el-tag v-if="item.standbyRssList.length > 0">
              备用RSS
            </el-tag>
            <el-tag v-if="item.healthScore != null" :type="healthTagType">
              <el-tooltip :content="`运维健康分 ${item.healthScore}（非 BGM 评分）：${healthReasons.join('；')}`"
                          placement="top">
                <span>健康 {{ item.healthScore }}</span>
              </el-tooltip>
            </el-tag>
          </div>
          <div v-if="showProgressTrack" class="list-card-progress-track" :title="progressTooltip">
            <div class="list-card-progress-fill" :style="{width: progressPercent + '%'}"></div>
          </div>
          <el-text v-if="showLastDownloadTime && item.lastDownloadTime > 0" size="small"
                   type="info">
            {{ item.lastDownloadFormat }}
          </el-text>
        </div>
        <div class="list-card-actions">
          <el-button text @click="emit('playlist', item)" bg v-if="showPlaylist">
            <el-icon>
              <Files/>
            </el-icon>
          </el-button>
          <div class="list-card-spacer" v-if="showPlaylist"></div>
          <el-button bg text title="更换封面" @click="emit('cover', item)">
            <el-icon>
              <Picture/>
            </el-icon>
          </el-button>
          <div class="list-card-spacer"></div>
          <el-button bg text @click="emit('edit', item)">
            <el-icon>
              <EditIcon/>
            </el-icon>
          </el-button>
          <div class="list-card-spacer"></div>
          <el-button type="danger" text @click="emit('del', [item])" bg>
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
import {computed, ref, watch} from "vue";
import {showLastDownloadTime, showPlaylist, showScore, toApiFile} from "@/js/global.js";
import {Delete, Edit as EditIcon, Files, Picture} from "@element-plus/icons-vue";

let openBgmUrl = (it) => {
  if (it.bgmUrl?.length) {
    window.open(it.bgmUrl, '_blank', 'noopener')
    return
  }
  if (it.title?.length) {
    let title = it.title.replace(/ ?\((19|20)\d{2}\)/g, "").trim()
    title = title.replace(/ ?\[tmdbid=(\d+)]/g, "").trim()
    window.open(`https://bgm.tv/subject_search/${encodeURIComponent(title)}?cat=2`, '_blank', 'noopener')
  }
}

let decodeURLComponentSafe = (str) => {
  return decodeURIComponent(str.replace('+', ' '));
}

const emit = defineEmits(['edit', 'playlist', 'cover', 'del', 'rate'])
let props = defineProps(["item"])

// 封面加载失败兜底：显示占位图标而不是裂图
const coverFailed = ref(false)
watch(() => props.item?.id, () => {
  coverFailed.value = false
})

// 评分守卫：无评分/非法值时回退显示 RSS 地址，避免 toFixed 抛错
const scoreText = computed(() => {
  if (!showScore.value) {
    return ''
  }
  const score = Number(props.item?.score)
  return Number.isFinite(score) && score > 0 ? score.toFixed(1) : ''
})

// 需要用户注意的原因，统一收进状态行的小圆点，不再额外占布局（fork 移植）
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

// 运维健康分（fork 后端附带，非 BGM 评分）
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

// 下载进度条：仅总集数已知时显示
const progressPercent = computed(() => {
  const total = Number(props.item?.totalEpisodeNumber)
  const current = Number(props.item?.currentEpisodeNumber)
  if (!Number.isFinite(total) || total <= 0) {
    return 0
  }
  const base = Number.isFinite(current) ? current : 0
  return Math.min(100, Math.max(0, (base / total) * 100))
})

const showProgressTrack = computed(() => {
  const total = Number(props.item?.totalEpisodeNumber)
  return Number.isFinite(total) && total > 0
})

const progressTooltip = computed(() => {
  const total = props.item?.totalEpisodeNumber
  const current = props.item?.currentEpisodeNumber
  return `已下载 ${current ?? 0} / ${total} 集（${progressPercent.value}%）`
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
  position: relative;
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
}

.list-card-dot {
  width: 8px;
  height: 8px;
  flex-shrink: 0;
  display: inline-block;
  border-radius: 50%;
  background: var(--el-color-info-light-5);
  cursor: help;
}

.list-card-dot.is-warn {
  background: var(--el-color-warning);
}

.list-card-info-body {
  font-size: 13px;
  line-height: 1.6;
}

.list-card-info-title {
  font-weight: 600;
  margin-bottom: 4px;
  word-break: break-all;
}

.list-card-info-list {
  margin: 0;
  padding-left: 18px;
  color: var(--el-text-color-regular);
}

.list-card-progress-track {
  height: 4px;
  width: 180px;
  max-width: 100%;
  margin: 6px 0 4px;
  border-radius: 2px;
  background: var(--el-fill-color);
  overflow: hidden;
  cursor: default;
}

.list-card-progress-fill {
  height: 100%;
  border-radius: 2px;
  background: var(--el-color-primary-light-3);
  transition: width 0.3s ease;
}

.list-card-image {
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--el-border-radius-base);
  cursor: pointer;
  height: 130px;
  width: 92px;
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

.list-card-score-container {
  margin-bottom: 8px;
}

.list-card-score {
  color: #E800A4;
  cursor: pointer;
}

.list-card-url {
  max-width: 300px;
}

.list-card-tags {
  width: 180px;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
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

@media (max-width: 800px) {
  .list-card-tags {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
