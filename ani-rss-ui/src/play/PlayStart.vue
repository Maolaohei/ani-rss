<template>
  <el-dialog v-model="dialogVisible" :title="playItem.name" center
             destroy-on-close
             :close-on-click-modal="!subtitleLoading"
             :close-on-press-escape="!subtitleLoading"
             :show-close="!subtitleLoading"
             @closed="onClosed">
    <div class="content" v-loading="subtitleLoading" element-loading-text="正在解析内封字幕，最长等待 4 秒">
      <el-alert
          v-if="subtitleError"
          class="subtitle-alert"
          type="warning"
          :closable="false"
          show-icon
          :title="subtitleError"/>
      <Artplayer v-if="dialogVisible && playerReady" :playItem="playItem" @error="onPlayerError"/>
      <div v-else class="player-placeholder">
        <el-text type="info" size="small">正在准备播放器…</el-text>
      </div>
    </div>
  </el-dialog>
</template>
<script setup>

import {ref} from "vue";
import Artplayer from "./Artplayer.vue";
import {toApiFile} from "@/js/global.js";
import * as http from "@/js/http.js";

/**
 * 内封字幕解析是服务端同步读取整部文件的操作，可能持续很久。
 * 这里给它一个上限：超时就先放主视频，字幕不再阻塞开播（解决"点了播放一直转圈"）。
 */
const SUBTITLE_WAIT_MS = 4000

let subtitleLoading = ref(false)
let subtitleError = ref('')
let playerReady = ref(false)
let dialogVisible = ref(false)
let playItem = ref({})

let subtitleTimer = null
/** 每次打开播放器/关闭弹窗自增；所有异步回调先比对自己捕获的序号 */
let requestId = 0

let clearSubtitleTimer = () => {
  if (subtitleTimer != null) {
    clearTimeout(subtitleTimer)
    subtitleTimer = null
  }
}

/**
 * 结束"等待字幕"阶段并挂载播放器。
 * 字幕先返回或等待超时，谁先到谁生效；重复调用会被 ready 标记挡掉。
 */
let finishSubtitleWait = (id, message) => {
  if (id !== requestId || playerReady.value) {
    return
  }
  clearSubtitleTimer()
  if (message) {
    subtitleError.value = message
  }
  subtitleLoading.value = false
  playerReady.value = true
}

/** 本次播放的快照：所有异步回调都读它，避免串集 / 关闭后 undefined */
let loadSubtitles = (id, filename) => {
  return http.getSubtitles(filename)
      .then(res => {
        if (id !== requestId) {
          return
        }
        const items = Array.isArray(res?.data) ? res.data : []
        for (let sub of items) {
          if (!playItem.value?.subtitles) {
            return
          }
          const blob = new Blob([sub.content], {type: "text/plain"});
          sub.url = URL.createObjectURL(blob);
          playItem.value.subtitles.push(sub)
        }
      })
      .catch(() => {
        if (id === requestId) {
          subtitleError.value = '内封字幕解析失败，已直接播放视频'
        }
      })
}

let show = (pi) => {
  clearSubtitleTimer()
  const id = ++requestId

  subtitleError.value = ''
  playerReady.value = false
  subtitleLoading.value = true

  const next = {...pi}
  next.src = toApiFile(next.filename)
  next.subtitles = Array.isArray(next.subtitles) ? [...next.subtitles] : []
  for (let subtitle of next.subtitles) {
    subtitle.url = toApiFile(subtitle.url)
  }
  playItem.value = next
  dialogVisible.value = true

  // 字幕与开播解耦：拿到就开播，超时就先开播
  loadSubtitles(id, next.filename)
      .finally(() => {
        finishSubtitleWait(id)
      })

  subtitleTimer = setTimeout(() => {
    finishSubtitleWait(id, '内封字幕解析超时，已先开始播放（可在设置中改用外挂字幕）')
  }, SUBTITLE_WAIT_MS)
}

defineExpose({
  show
})

let onPlayerError = (message) => {
  subtitleError.value = message
}

let onClosed = () => {
  requestId++
  clearSubtitleTimer()
  dialogVisible.value = false
  playerReady.value = false
  subtitleLoading.value = false
  subtitleError.value = ''
  playItem.value = {}
}
</script>

<style scoped>
.content {
  width: 100%;
  min-height: 200px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.player-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 700px;
  max-width: calc(100vw - 48px);
  height: 450px;
  max-height: min(calc(56.25vw - 27px), calc(100dvh - 220px));
}

.subtitle-alert {
  width: 100%;
  margin-bottom: 8px;
}
</style>
