<template>
  <div>
    <div ref="appRef" class="art-app"></div>
    <div class="flex art-toolbar">
      <el-text v-if="errorMessage" class="art-error" type="danger" size="small">
        {{ errorMessage }}
      </el-text>
      <div class="spacer"></div>
      <el-dropdown>
        <el-button bg text icon="MoreFilled"
                   aria-label="用外部播放器打开"
                   title="用外部播放器打开"/>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="openUrl(`potplayer://${playItem.src}`)">
              <el-text>
                <el-icon>
                  <img alt="PotPlayer" class="el-icon--left icon" src="../icon/icon-PotPlayer.webp"/>
                </el-icon>
                Pot
              </el-text>
            </el-dropdown-item>
            <el-dropdown-item @click="openUrl(`vlc://${playItem.src}`)">
              <el-text>
                <el-icon>
                  <img alt="VLC" class="el-icon--left icon" src="../icon/icon-VLC.webp"/>
                </el-icon>
                VLC
              </el-text>
            </el-dropdown-item>
            <el-dropdown-item
                @click="openUrl(`iina://weblink?url=${encodeUrl(playItem.src)}&mpv_force-media-title=${encodeUrl(playItem.name)}`)">
              <el-text>
                <el-icon>
                  <img alt="IINA" class="el-icon--left icon" src="../icon/icon-IINA.webp"/>
                </el-icon>
                IINA
              </el-text>
            </el-dropdown-item>
            <el-dropdown-item
                @click="openUrl(`mpvplay://${playItem.src}&mpv_force-media-title=${encodeUrl(playItem.name)}`)">
              <el-text>
                <el-icon>
                  <img alt="MPV" class="el-icon--left icon" src="../icon/icon-MPV.webp"/>
                </el-icon>
                MPV
              </el-text>
            </el-dropdown-item>
            <el-dropdown-item
                @click="openUrl(`infuse://x-callback-url/play?url=${encodeUrl(playItem.src)}&filename=${encodeUrl(playItem.name)}`)">
              <el-text>
                <el-icon>
                  <img alt="Infuse" class="el-icon--left icon" src="../icon/icon-Infuse.png"/>
                </el-icon>
                Infuse
              </el-text>
            </el-dropdown-item>
            <el-dropdown-item
                @click="openUrl(`ddplay:${encodeUrl(playItem.src)}|filePath=${encodeUrl(playItem.name)}`)">
              <el-text>
                <el-icon>
                  <img alt="DandanPlay" class="el-icon--left icon" src="../icon/icon-DandanPlay.webp"/>
                </el-icon>
                弹弹Play
              </el-text>
            </el-dropdown-item>
            <el-dropdown-item @click="openUrl(`anix://openVideo/${encodeUrl(playItem.src)}`)">
              <el-text>
                <el-icon>
                  <img alt="AnimacX" class="el-icon--left icon" src="../icon/icon-AnimacX.webp"/>
                </el-icon>
                AnimacX
              </el-text>
            </el-dropdown-item>
            <el-dropdown-item
                @click="openUrl(`SenPlayer://x-callback-url/play?url=${encodeUrl(playItem.src)}&name=${encodeUrl(playItem.name)}`)">
              <el-text>
                <el-icon>
                  <img alt="SenPlayer" class="el-icon--left icon" src="../icon/icon-SenPlayer.webp"/>
                </el-icon>
                SenPlayer
              </el-text>
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<script setup>
import {onBeforeUnmount, onMounted, ref} from 'vue'
import Artplayer from 'artplayer';
import artplayerPluginMultipleSubtitles from 'artplayer-plugin-multiple-subtitles';

const props = defineProps(['playItem'])
const emit = defineEmits(['error'])

/** 关闭字幕时使用的哨兵名：插件按名查找会得到 undefined，mergeTrees 会跳过该轨道 */
const SUBTITLE_OFF = '__off__'

/** 自定义关闭图标：只能通过 option.icons 传入字符串（art.icons 在构造前不存在） */
const CUSTOM_ICONS = {
  close: '<svg viewBox="0 0 24 24" width="18" height="18" xmlns="http://www.w3.org/2000/svg"><path fill="currentColor" d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm3.54 12.46a1 1 0 0 1-1.41 1.41L12 13.83l-2.12 2.04a1 1 0 0 1-1.42-1.41L10.59 12 8.46 9.88a1 1 0 0 1 1.42-1.41L12 10.59l2.12-2.12a1 1 0 1 1 1.41 1.41L13.41 12l2.13 2.46z"/></svg>'
}

/**
 * 播放容器改用 ref，避免全局选择器 '.art-app' 在多个实例（或残留节点）之间串台。
 */
const appRef = ref(null)

/** 播放器自身的错误提示（容器内展示，用户不需要去控制台） */
const errorMessage = ref('')

let openUrl = (url) => {
  window.open(url)
}

let encodeUrl = (str) => {
  return encodeURIComponent(str)
}

let art = null
let subtitleTimers = []
let defaultSubtitleApplied = false

/**
 * 依赖确认（artplayer 5.4.0 / option.d.ts）：
 * - `preload` 不是顶层选项，真实位置是 `moreVideoAttr.preload`（默认 metadata）。
 * - 该包只在构建里内置了 zh-cn 一套 i18n（dist/i18n 下没有 zh-cn.js），
 *   而 `lang` 默认取 `navigator.language`；非中文浏览器下所有控件文案都会回落成英文 key。
 *   因此显式指定 lang: 'zh-cn'。
 * - `autoPlayback` 的存储键是 `option.id || option.url`；url 里带会轮换的 token，
 *   必须传稳定 id（用文件名），进度记忆才能跨会话命中。
 */
let applyDefaultSubtitle = (plugin, name) => {
  if (!name || !plugin?.tracks) {
    return false
  }
  try {
    plugin.tracks([name])
    return true
  } catch (e) {
    // 插件内部异常不应打断播放
    return false
  }
}

/**
 * 插件是 async 注册的：它要先把所有字幕 fetch+解析完才把自己挂到 art.plugins 上。
 * 所以不能在 canplay 时一次性调用（那时插件往往还不存在），改为轮询等待就绪；
 * 成功一次就停止，避免重复重载字幕轨道。
 */
let waitPluginThenApplyDefault = (defaultName) => {
  if (!defaultName) {
    return
  }
  for (let i = 1; i <= 40; i++) {
    const timer = setTimeout(() => {
      if (defaultSubtitleApplied) {
        return
      }
      const plugin = art?.plugins?.['multipleSubtitles']
      if (plugin) {
        defaultSubtitleApplied = applyDefaultSubtitle(plugin, defaultName)
        if (defaultSubtitleApplied) {
          subtitleTimers.forEach(t => clearTimeout(t))
          subtitleTimers = []
        }
      }
    }, i * 250)
    subtitleTimers.push(timer)
  }
}

onMounted(() => {
  let {src, subtitles, extName, filename} = props.playItem
  subtitles = subtitles || []

  let defaultName = ''
  let settings = []
  if (subtitles.length) {
    defaultName = subtitles[0].name
    // "关闭字幕"：没有它用户无法关掉字幕（插件默认会把所有轨道叠加显示）。
    // 图标只能用字符串（option.icons 的 customIcons.close），
    // 因为 art.icons 是实例属性、构造前不存在；Setting.icon 也只接受 string | HTMLElement。
    const selector = [
      ...subtitles.map(s => ({name: s.name, html: s.name})),
      {name: SUBTITLE_OFF, html: '关闭字幕', icon: CUSTOM_ICONS.close}
    ]
    settings = [
      {
        width: 200,
        html: '字幕',
        tooltip: defaultName || '关闭字幕',
        selector,
        onSelect: function (item) {
          const p = art?.plugins?.['multipleSubtitles']
          if (p?.tracks) {
            p.tracks([item.name])
          }
          return item.html
        },
      },
    ]
  }

  art = new Artplayer({
    container: appRef.value,
    id: filename || src,
    url: src,
    type: extName,
    lang: 'zh-cn',
    theme: '#646cff',
    playbackRate: true,
    aspectRatio: true,
    screenshot: true,
    setting: true,
    pip: true,
    fullscreen: true,
    fullscreenWeb: true,
    airplay: true,
    autoPlayback: true,
    icons: CUSTOM_ICONS,
    moreVideoAttr: {
      preload: 'auto',
      playsInline: true
    },
    plugins: [
      artplayerPluginMultipleSubtitles({
        subtitles
      })
    ],
    settings: settings
  });

  art.on('ready', () => {
    waitPluginThenApplyDefault(defaultName)
  })

  art.on('video:error', () => {
    errorMessage.value = '视频加载失败：该文件可能已被移动或删除；若编码不被浏览器支持，请用右侧菜单在外部播放器打开'
    emit('error', errorMessage.value)
  })

  art.on('error', () => {
    if (!errorMessage.value) {
      errorMessage.value = '视频加载失败：该文件可能已被移动或删除；若编码不被浏览器支持，请用右侧菜单在外部播放器打开'
      emit('error', errorMessage.value)
    }
  })
})

onBeforeUnmount(() => {
  subtitleTimers.forEach(t => clearTimeout(t))
  subtitleTimers = []
  if (!art) {
    return
  }
  try {
    art.destroy(true);
    art = null;
  } catch (e) {
  }
})
</script>

<style scoped>
.art-app {
  width: 700px;
  height: 450px;
  max-width: calc(100vw - 48px);
  /* 同时受宽度比例与可用视口高度约束，避免横屏/矮视口被裁切 */
  max-height: min(calc(56.25vw - 27px), calc(100dvh - 220px));
  margin-bottom: 8px;
}

.art-toolbar {
  align-items: center;
  gap: 8px;
}

.art-error {
  word-break: break-all;
}

.icon {
  height: 14px;
  width: 14px;
}
</style>
