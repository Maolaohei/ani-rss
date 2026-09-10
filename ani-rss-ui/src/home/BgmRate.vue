<template>
  <el-dialog v-model="dialogVisible" align-center center width="300" title="评分">
    <div v-loading="loading">
      <div class="flex bgm-rate-rate-container">
        <el-rate v-model="ani.score"
                 :max="10"
                 :min="0"/>
      </div>
      <div class="bgm-rate-text-container">
        <p v-if="ani.score > 0" class="bgm-rate-text">{{ texts[ani.score - 1] }}</p>
      </div>
      <div class="flex bgm-rate-button-container">
        <el-button :icon="Ban" bg text @click="clearRate">清空评分</el-button>
        <el-button :icon="Save" bg text @click="saveRate">保存评分</el-button>
      </div>
    </div>
  </el-dialog>
</template>


<script setup>

import {ref} from "vue";
import {Ban, Save} from "@vicons/fa";
import {ElMessage} from "element-plus";
import * as http from "@/js/http.js";

let texts = ref([
  '不忍直视 1 (请谨慎评价)', '很差 2', '差 3', '较差 4', '不过不失 5',
  '还行 6', '推荐 7', '力荐 8', '神作 9', '超神作 10 (请谨慎评价)'
])

let dialogVisible = ref(false)

let ani = ref({
  score: 0
})

let loading = ref(false);

let show = (v) => {
  ani.value = JSON.parse(JSON.stringify(v))
  if (ani.value.score == null) {
    ani.value.score = 0
  }

  let tmpAni = JSON.parse(JSON.stringify(ani.value))
  // 查询接口以 score=null 为标志（score=0 会走写入分支）
  tmpAni.score = null

  rate(tmpAni)

  dialogVisible.value = true
}

let clearRate = () => {
  // 清空评分必须走写入接口(setRate)：此前误调查询接口(rate)，
  // 返回的旧评分会把本地的 0 覆盖回来，表现为"点了没反应"。
  ani.value.score = 0
  saveRate()
}

/**
 * 读取当前评分（查询接口，忽略请求体里的 score）
 */
let rate = (v) => {
  loading.value = true
  http.rate(v)
      .then(res => {
        ani.value.score = res.data ?? 0
      })
      .finally(() => {
        loading.value = false
      })
}

/**
 * 写入评分（清空评分同样走这里，score=0 即清除）
 */
let saveRate = () => {
  loading.value = true
  http.setRate(ani.value)
      .then(res => {
        ani.value.score = res.data ?? 0

        let message = res.message
        if (message) {
          ElMessage.success(message)
        }
      })
      .finally(() => {
        loading.value = false
      })
}

defineExpose({show})
</script>

<style scoped>
.bgm-rate-rate-container {
  justify-content: center;
  width: 100%;
}

.bgm-rate-text-container {
  height: 12px;
}

.bgm-rate-text {
  text-align: center;
}

.bgm-rate-button-container {
  width: 100%;
  justify-content: space-between;
  margin-top: 14px;
}
</style>
