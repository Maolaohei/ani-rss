<template>
  <play-start ref="playStartRef"/>
  <el-dialog v-model="dialogVisible" :title="ani.title" center
             @opened="onOpened"
             @closed="onClosed">
    <div v-loading="listLoading" v-if="list.length || listLoading">
      <el-scrollbar ref="scrollbarRef" class="play-list-scroll" tabindex="0">
        <div class="grid-container">
          <div v-for="it in list" :key="it.filename || it.title"
               :ref="el => setItemRef(it, el)"
               class="grid-cell"
               :class="{'is-current': isCurrentEpisode(it)}">
            <el-card shadow="never">
              <div class="grid-item">
                <div>
                  <el-tooltip :content="it.title" placement="top">
                    <el-text :line-clamp="2">
                      {{ it.title }}
                    </el-text>
                  </el-tooltip>
                  <br/>
                  <el-text size="small" type="info">
                    {{ it.formatSize }}&nbsp;|&nbsp;{{ it.lastModifyFormat }}
                  </el-text>
                  <el-tag v-if="isCurrentEpisode(it)" class="current-tag" size="small" type="primary">
                    看到这里
                  </el-tag>
                </div>
                <el-button circle
                           icon="VideoPlay"
                           size="large"
                           text
                           type="primary"
                           :aria-label="`播放 ${it.title}`"
                           :title="`播放 ${it.title}`"
                           @click="playStartShow(it)"
                />
              </div>
            </el-card>
          </div>
        </div>
        <div class="bottom-spacer"></div>
      </el-scrollbar>
      <div>
        <p class="total-text">共 {{ list.length }} 项</p>
      </div>
    </div>
    <div class="content" v-else>
      <el-text :type="errorMessage ? 'danger' : 'info'">
        {{ errorMessage || '没有找到已下载的集数。若确认已下载，请检查下载目录是否已正确映射到容器内（Docker 需挂载卷）。' }}
      </el-text>
    </div>
  </el-dialog>
</template>

<script setup>
import {defineAsyncComponent, nextTick, ref} from "vue";
import formatTime from "@/js/format-time.js";
import * as http from "@/js/http.js";
import {isLastWatched, markWatched} from "@/js/play-progress.js";

/**
 * 播放链路懒加载：PlayStart → Artplayer（含 artplayer 本体）只有在用户真正点某一集时才需要。
 * 注意 List.vue 是静态 import PlayList，所以那一层拆包需要改 home/ 下的文件（不在本批次范围），
 * 这里先把最重的 Artplayer 部分异步化。
 */
const PlayStart = defineAsyncComponent(() => import("./PlayStart.vue"));

const dialogVisible = ref(false)
const listLoading = ref(false)
const list = ref([])
const errorMessage = ref('')

let ani = ref({})
let playStartRef = ref()
let scrollbarRef = ref()
/** filename -> 卡片 DOM，用于把当前集滚动到可见区域 */
let itemRefs = new Map()
/** 本次打开是否已自动定位（只定位一次，避免用户手动滚动后被拉回） */
let located = false
/** 当前绑定的滚动容器（用于监听用户手动滚动） */
let scrollWrap = null

let setItemRef = (it, el) => {
  const key = it.filename || it.title
  if (el) {
    itemRefs.set(key, el)
  } else {
    itemRefs.delete(key)
  }
}

/**
 * 判断是否"当前集"。
 * 优先用本机记录的"上次看到的那一集"（最贴近用户真实进度），
 * 没有记录时退化为后端返回的"已下载到第几集"；都没有则不标记。
 */
let isCurrentEpisode = (it) => {
  if (isLastWatched(ani.value?.id, it)) {
    return true
  }
  if (ani.value?.lastWatchedKey) {
    return false
  }
  const current = ani.value?.currentEpisodeNumber
  const episode = it?.episode
  if (current == null || episode == null) {
    return false
  }
  return Number(current) === Number(episode)
}

let onUserScroll = () => {
  // 用户一旦自己滚动，就不再自动定位
  located = true
}

let bindScrollListener = () => {
  unbindScrollListener()
  // el-scrollbar 的真实滚动元素是 wrapRef
  scrollWrap = scrollbarRef.value?.wrapRef
  scrollWrap?.addEventListener('scroll', onUserScroll, {passive: true})
}

let unbindScrollListener = () => {
  if (scrollWrap) {
    scrollWrap.removeEventListener('scroll', onUserScroll)
    scrollWrap = null
  }
}

/**
 * 把当前集滚动到可见区域并轻高亮，解决"每次都从第一集开始手动往下翻"。
 * 注意：列表是异步来的，所以这里可能被调用两次（弹窗 opened / 数据到达后），
 * 用 located 标记保证只生效一次。
 */
let scrollToCurrent = async () => {
  if (located || !dialogVisible.value) {
    return
  }
  await nextTick()
  if (located) {
    return
  }
  const target = list.value.find(it => isCurrentEpisode(it))
  if (!target) {
    return
  }
  const key = target.filename || target.title
  const el = itemRefs.get(key)
  if (!el || typeof el.scrollIntoView !== 'function') {
    return
  }
  located = true
  try {
    el.scrollIntoView({block: 'center'})
  } catch (e) {
    // 老浏览器不支持 options，退化为无参调用
    el.scrollIntoView()
  }
}

let onOpened = () => {
  bindScrollListener()
  scrollToCurrent()
}

let onClosed = () => {
  unbindScrollListener()
}

let playStartShow = (it) => {
  // 记录"看到这里"（按订阅 id），下次打开选集时高亮并可自动定位
  markWatched(ani.value?.id, it)
  ani.value = {...ani.value, lastWatchedKey: it.filename || it.title}
  playStartRef.value?.show(JSON.parse(JSON.stringify(it)))
}

const show = (it) => {
  ani.value = it
  listLoading.value = true
  list.value = []
  errorMessage.value = ''
  itemRefs.clear()
  located = false
  dialogVisible.value = true
  http.playList(it)
      .then(res => {
        const items = Array.isArray(res?.data) ? res.data : []
        list.value = items.map(it => {
          return {...it, lastModifyFormat: formatTime(it['lastModify'])}
        })
        if (!list.value.length) {
          errorMessage.value = '订阅里还没有已下载的集数，或下载目录未被识别。'
        }
        // 数据晚于 opened 到达时，这里补一次定位
        scrollToCurrent()
      })
      .catch(err => {
        // 接口失败与"确实没有已下载集数"是两回事，必须分开提示
        errorMessage.value = err?.message
            ? `读取已下载列表失败：${err.message}`
            : '读取已下载列表失败，请检查服务是否在运行'
      })
      .finally(() => {
        listLoading.value = false
      })
}

defineExpose({
  show
})
</script>


<style scoped>
.bottom-spacer {
  height: 5px;
}

/* 选集区高度：桌面保留原 500px，窄屏/矮屏改为跟随视口，避免顶出屏幕 */
.play-list-scroll {
  height: 500px;
}

@media (max-width: 640px), (max-height: 720px) {
  .play-list-scroll {
    height: 55dvh;
  }
}

.content {
  min-height: 200px;
  width: 100%;
  display: flex;
  justify-content: center;
  align-items: center;
  text-align: center;
}

.grid-container {
  display: grid;
  grid-gap: 8px;
  width: 100%;
  grid-template-columns: repeat(2, 1fr);
  padding: 0 5px;
}

/* 窄屏改单列，避免两列把标题压成竖排 */
@media (max-width: 640px) {
  .grid-container {
    grid-template-columns: 1fr;
  }
}

.grid-cell {
  border-radius: var(--el-border-radius-base);
  transition: box-shadow var(--dur) var(--ease-apple);
}

.grid-cell.is-current {
  box-shadow: 0 0 0 2px var(--el-color-primary);
}

.grid-item {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.current-tag {
  margin-top: 6px;
}

.total-text {
  margin: 6px;
  text-align: end;
}
</style>
