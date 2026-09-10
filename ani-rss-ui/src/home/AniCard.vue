<template>
  <el-card shadow="never">
    <div class="list-card-content">
      <div class="list-card-image-container">
        <!-- 封面可点击换图：用 button 承载，键盘可 Tab + 回车，读屏有名称 -->
        <button
            type="button"
            class="list-card-cover"
            :aria-label="`更换《${item.title}》的封面`"
            :title="'更换封面'"
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
          <!-- 追番核心状态：更新节奏 / 下载进度 / 漏集 / 禁止下载，放在评分之前 -->
          <div class="list-card-status">
            <el-tag v-if="airingText"
                    size="small"
                    class="list-card-airing"
                    :type="isAiringToday ? 'primary' : 'info'">
              {{ airingText }}
            </el-tag>
            <el-tooltip :content="progressTooltip" placement="top">
              <el-tag size="small" type="warning">{{ progressText }}</el-tag>
            </el-tooltip>
            <el-tag v-if="omitCount > 0" size="small" type="danger">
              疑似漏集 {{ omitCount }}
            </el-tag>
            <el-tag v-if="notDownloadCount > 0" size="small" type="info">
              已禁下 {{ notDownloadCount }} 集
            </el-tag>
          </div>
          <div class="list-card-score-container" v-if="showScore">
            <h4 class="list-card-score"
                :class="{'is-empty': item['score'] == null}"
                @click="emit('rate', item)">
              <template v-if="item['score'] == null">暂无评分</template>
              <template v-else>{{ item['score'].toFixed(1) }}</template>
            </h4>
          </div>
          <!-- 健康原因在触屏不可 hover：改成可点击的 popover -->
          <el-popover v-if="item.healthScore != null"
                      trigger="click"
                      placement="top-start"
                      :width="260">
            <template #reference>
              <el-tag size="small" :type="healthTagType" class="list-card-health">
                健康 {{ item.healthScore }}
              </el-tag>
            </template>
            <div class="list-card-health-body">
              <div class="list-card-health-title">运维健康分（非 BGM 评分）</div>
              <ul class="list-card-health-reasons">
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
          <div class="list-card-tags">
            <el-tag v-if="showSeasonTag" type="info">
              第 {{ item.season }} 季
            </el-tag>
            <el-tag v-if="item.procrastinating" type="warning" title="已开启「摸鱼」：不自动下载新集">
              摸鱼中
            </el-tag>
            <el-tag type="success" v-else-if="item.enable">
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
            <!-- 媒体类型不再用「危险红」：正常态不该是告警色 -->
            <el-tag type="info">
              {{ mediaTypeText }}
            </el-tag>
            <el-tag v-if="item.standbyRssList.length > 0">
              备用RSS
            </el-tag>
          </div>
          <el-text v-if="showLastDownloadTime && item.lastDownloadTime > 0"
                   size="small"
                   type="info"
                   class="list-card-last-download">
            最近更新 · {{ item.lastDownloadFormat }}
          </el-text>
        </div>
      </div>
    </div>
    <!-- 操作区独立成行：横向排列、触控目标更大、编辑与删除之间留出间隔 -->
    <div class="list-card-actions">
      <el-tooltip content="立即检查这部番的新集" placement="top">
        <el-button bg
                   text
                   class="list-card-action"
                   aria-label="立即检查新集"
                   :loading="refreshing"
                   @click="checkNewEpisodes">
          <el-icon>
            <Refresh/>
          </el-icon>
        </el-button>
      </el-tooltip>
      <el-tooltip content="查看已下载集数" placement="top" v-if="showPlaylist">
        <el-button bg
                   text
                   class="list-card-action"
                   aria-label="查看视频列表"
                   @click="emit('playlist', item)">
          <el-icon>
            <Files/>
          </el-icon>
        </el-button>
      </el-tooltip>
      <el-tooltip content="修改订阅" placement="top">
        <el-button bg
                   text
                   class="list-card-action"
                   aria-label="修改订阅"
                   @click="emit('edit', item)">
          <el-icon>
            <EditIcon/>
          </el-icon>
        </el-button>
      </el-tooltip>
      <span class="list-card-action-gap"></span>
      <el-tooltip content="删除订阅" placement="top">
        <el-button type="danger"
                   text
                   bg
                   class="list-card-action"
                   aria-label="删除订阅"
                   @click="emit('del', [item])">
          <el-icon>
            <Delete/>
          </el-icon>
        </el-button>
      </el-tooltip>
    </div>
  </el-card>
</template>

<script setup>
import {computed, ref} from "vue";
import {ElMessage} from "element-plus";
import {Delete, Edit as EditIcon, Files, Picture, Refresh} from "@element-plus/icons-vue";
import {showLastDownloadTime, showPlaylist, showScore, toApiFile} from "@/js/global.js";
import * as http from "@/js/http.js";

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

const emit = defineEmits(['edit', 'playlist', 'cover', 'del', 'rate', 'refresh'])
let props = defineProps(["item"])

const coverFailed = ref(false)

const coverSrc = computed(() => {
  const cover = props.item?.cover
  return cover ? toApiFile(cover) : ''
})

const safeUrl = computed(() => decodeURLComponentSafe(props.item?.url || ''))

const refreshing = ref(false)

/** 单订阅"立即检查新集"：走与其他入口相同的 refreshAni，结果按语义着色 */
const checkNewEpisodes = () => {
  if (refreshing.value) {
    return
  }
  refreshing.value = true
  http.refreshAni({id: props.item?.id})
      .then(res => {
        emit('refresh', props.item)
        const message = res?.message || '已提交刷新'
        if (message.indexOf('排队') > -1 || message.indexOf('已存在') > -1 || message.indexOf('进行中') > -1) {
          ElMessage.warning(message)
        } else {
          ElMessage.success(message)
        }
      })
      .catch(() => {
        // api.js 已统一提示；这里只负责复位按钮状态
      })
      .finally(() => {
        refreshing.value = false
      })
}

/** 漏集数：仅当该订阅开启了遗漏检测时后端才会给出非 0 值 */
const omitCount = computed(() => {
  const n = props.item?.omitCount
  return typeof n === 'number' && n > 0 ? n : 0
})

const notDownloadCount = computed(() => {
  const list = props.item?.notDownload
  return Array.isArray(list) ? list.length : 0
})

const currentEpisodeNumber = computed(() => {
  const n = props.item?.currentEpisodeNumber
  return typeof n === 'number' ? n : 0
})

const totalEpisodeNumber = computed(() => {
  const n = props.item?.totalEpisodeNumber
  return typeof n === 'number' && n > 0 ? n : 0
})

const progressText = computed(() => {
  if (currentEpisodeNumber.value <= 0) {
    return '尚无下载记录'
  }
  if (totalEpisodeNumber.value > 0) {
    return `已下载到第 ${currentEpisodeNumber.value} 集 / 共 ${totalEpisodeNumber.value} 集`
  }
  return `已下载到第 ${currentEpisodeNumber.value} 集`
})

const progressTooltip = computed(() => progressText.value)

/** 更新节奏：周几更新（releaseDate 形如 2024-10-05 或 2024） */
const airingText = computed(() => {
  const weekLabel = props.item?.weekLabel
  if (weekLabel) {
    return isAiringToday.value ? '今天更新' : `${weekLabel}更新`
  }
  const year = (props.item?.releaseDate || '').match(/^(\d{4})/)?.[1]
  return year ? `上映 ${year}` : ''
})

const isAiringToday = computed(() => {
  const today = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'][new Date().getDay()]
  return props.item?.weekLabel === today
})

/** 只在「按星期展示」关闭时需要：分组模式下标题已说明周几 */
const showSeasonTag = computed(() => {
  const season = props.item?.season
  return typeof season === 'number' && season > 1
})

const mediaTypeText = computed(() => {
  if (props.item?.mediaType === 'movie') {
    return '剧场版'
  }
  return props.item?.ova ? 'ova' : 'tv'
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

/* 封面按钮：去掉按钮默认外观，只保留图片本身 */
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
  min-width: 0;
}

.list-card-info-inner {
  margin-left: 8px;
}

/* 标题不再写死 200px：窄屏/长标题时跟随可用宽度 */
.list-card-title {
  max-width: 100%;
  line-height: 1.6;
  letter-spacing: 0.0125em;
  font-weight: 500;
  font-size: 0.97em;
  cursor: pointer;
  color: var(--el-text-color-primary);
}

/* 追番核心状态行：换行不挤压 */
.list-card-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin: 6px 0 4px;
}

.list-card-airing {
  font-weight: 500;
}

.list-card-score-container {
  margin-bottom: 8px;
}

.list-card-score {
  color: #E800A4;
  cursor: pointer;
}

.list-card-score.is-empty {
  color: var(--el-text-color-placeholder);
  font-size: 0.9em;
  font-weight: 400;
}

.list-card-url {
  max-width: 300px;
}

.list-card-health-body {
  font-size: 12.5px;
  line-height: 1.6;
}

.list-card-health-title {
  font-weight: 600;
  margin-bottom: 6px;
  color: var(--el-text-color-primary);
}

.list-card-health-reasons {
  margin: 0;
  padding-left: 18px;
  color: var(--el-text-color-regular);
}

/* 标签网格自适应：不再用固定 180px + 2/3 列切换 */
.list-card-tags {
  width: 100%;
  max-width: 220px;
  display: grid;
  grid-gap: 4px;
  grid-template-columns: repeat(auto-fill, minmax(78px, 1fr));
}

.list-card-subgroup {
  max-width: 60px;
  color: var(--el-color-info);
}

.list-card-last-download {
  display: inline-block;
  margin-top: 6px;
}

/* 操作区独立成行：横向排列，触控目标 36px，编辑与删除之间留 16px 间隔 */
.list-card-actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 2px;
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px solid var(--el-border-color-extra-light);
}

.list-card-action {
  min-width: 36px;
  min-height: 36px;
  padding: 0 8px;
}

.list-card-action :deep(.el-icon) {
  width: 16px;
  height: 16px;
}

.list-card-action-gap {
  width: 16px;
}

@media (max-width: 640px) {
  .list-card-tags {
    max-width: 100%;
  }

  .list-card-action {
    min-width: 44px;
    min-height: 44px;
  }
}
</style>
