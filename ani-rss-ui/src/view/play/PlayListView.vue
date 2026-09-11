<template>
  <PlayStartView ref="playStartRef"/>
  <el-dialog v-model="dialogVisible" :title="ani.title" center>
    <div v-loading="listLoading" v-if="list.length || listLoading">
      <el-scrollbar style="height: 500px;">
        <div class="grid-container">
          <div v-for="it in list">
            <el-card shadow="never" :class="{'is-current': isCurrentEpisode(it)}">
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
                  <br v-if="isCurrentEpisode(it)"/>
                  <el-tag v-if="isCurrentEpisode(it)" size="small" class="current-tag">
                    上次看到
                  </el-tag>
                </div>
                <el-button circle
                           icon="VideoPlay"
                           size="large"
                           text
                           type="primary"
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
      <el-text type="danger">
        未下载集数或 docker 映射存在问题
      </el-text>
    </div>
  </el-dialog>
</template>

<script setup>
import {ref} from "vue";
import PlayStartView from "./PlayStartView.vue";
import {fromNow} from "@/js/format.js";
import * as http from "@/js/http.js";
import {isLastWatched, markWatched} from "@/js/play-progress.js";

const dialogVisible = ref(false)
const listLoading = ref(false)
const list = ref([])

let ani = ref({})
let playStartRef = ref()

let playStartShow = (it) => {
  // 记录"看到这里"（按订阅 id），下次打开选集时高亮并可自动定位
  markWatched(ani.value?.id, it)
  ani.value = {...ani.value, lastWatchedKey: it.filename || it.title}
  playStartRef.value?.show(JSON.parse(JSON.stringify(it)))
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
  return Number(episode) === Number(current)
}

const show = (it) => {
  ani.value = it
  listLoading.value = true
  list.value = []
  dialogVisible.value = true
  http.playList(it)
      .then(res => {
        list.value = res.data.map(it => {
          return {...it, lastModifyFormat: fromNow(it['lastModify'])}
        })
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

.content {
  min-height: 200px;
  width: 100%;
  display: flex;
  justify-content: center;
  align-items: center;
}

.grid-container {
  display: grid;
  grid-gap: 5px;
  width: 100%;
  grid-template-columns: repeat(2, 1fr);
  padding: 0 5px;
}

.grid-item {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.total-text {
  margin: 6px;
  text-align: end;
}

.is-current {
  border-color: var(--el-color-primary);
}

.current-tag {
  margin-top: 2px;
}
</style>
