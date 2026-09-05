<template>
  <el-dialog v-model="dialogVisible" center title="切换主RSS" width="520px">
    <el-alert :closable="false" class="switch-alert" show-icon type="info">
      <template #title>
        选择一条备用 RSS 与当前主 RSS 互换；原主 RSS 会自动转为备用（重复 RSS 自动去重），匹配/排除规则随字幕组标签生效，无需改动
      </template>
    </el-alert>
    <el-alert v-if="config && !config.standbyRss" :closable="false"
              class="switch-alert" show-icon type="warning">
      <template #title>
        当前备用RSS功能并未开启, 转为备用的RSS将不会被拉取, 可前往 设置-基本设置-RSS设置-备用RSS 启用
      </template>
    </el-alert>
    <div v-loading="loading">
      <div class="rss-item rss-item-master">
        <div class="rss-item-main">
          <div class="rss-item-head">
            <el-tag size="small" type="primary">主</el-tag>
            <span class="rss-item-label">{{ masterLabel }}</span>
          </div>
          <el-text class="rss-item-url" line-clamp="1" size="small" truncated>
            {{ props.ani.url }}
          </el-text>
        </div>
      </div>
      <div
          :key="`${it.url}-${index}`"
          class="rss-item rss-item-standby"
          v-for="(it, index) in standbyList">
        <div class="rss-item-main">
          <div class="rss-item-head">
            <el-tag size="small" type="warning">备</el-tag>
            <span class="rss-item-label">{{ standbyLabel(it) }}</span>
          </div>
          <el-text class="rss-item-url" line-clamp="1" size="small" truncated>
            {{ it.url }}
          </el-text>
        </div>
        <el-button bg size="small" text type="primary" @click="switchTo(it)">设为主</el-button>
      </div>
      <el-empty
          :image-size="60"
          description="暂无备用 RSS，可先在下方「备用 RSS - 管理」中添加"
          v-if="!standbyList.length"/>
    </div>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import {computed, ref} from "vue";
import {ElMessage} from "element-plus";
import * as http from "@/js/http.js";

const dialogVisible = ref(false)
const loading = ref(false)
const config = ref(null)

const standbyList = computed(() => {
  let list = props.ani?.standbyRssList || []
  return list.filter(it => it && it.url)
})

const masterLabel = computed(() => {
  let subgroup = props.ani?.subgroup
  return subgroup?.length ? subgroup : '未知字幕组'
})

let standbyLabel = it => {
  let label = it?.label
  return label?.length ? label : '未知字幕组'
}

let show = () => {
  dialogVisible.value = true
  loading.value = true
  http.config()
      .then(res => {
        config.value = res.data
      })
      .catch(() => {
        config.value = null
      })
      .finally(() => {
        loading.value = false
      })
}

/**
 * 将备用 RSS 与主 RSS 原位互换：
 * - 备用的 url/label/offset 迁移为主 RSS（url/subgroup/offset）
 * - 原主 RSS 快照转存到备用列表原位置，其余备用顺序保持不变
 * - 去重：添加订阅时可能自动复制过主RSS至备用，互换会重复；
 *   剔除与新主RSS/原主RSS同URL的备用条目，其余重复URL保留最先出现的
 */
let switchTo = standby => {
  let ani = props.ani

  let url = (ani.url || '').trim()
  let newUrl = (standby.url || '').trim()
  if (!newUrl) {
    ElMessage.warning('该备用RSS无效')
    return
  }
  if (newUrl === url) {
    ElMessage.info('该RSS已是当前主RSS')
    return
  }

  let subgroup = ani.subgroup?.length ? ani.subgroup : '未知字幕组'
  let offset = ani.offset

  ani.url = newUrl
  ani.subgroup = standbyLabel(standby)
  ani.offset = standby.offset ?? ani.offset ?? 0

  let list = ani.standbyRssList || []
  let idx = list.indexOf(standby)

  // 去重重建备用列表: 移除被提升的条目; 新主RSS与原主RSS的URL不再进入备用;
  // 其余重复URL保留最先出现的; 原主RSS快照插入到被提升条目的原位置
  let seen = new Set([newUrl, ...(url ? [url] : [])])
  let rest = []
  let at = 0
  list.forEach((it, i) => {
    if (i === idx) {
      at = rest.length
      return
    }
    let u = (it?.url || '').trim()
    if (!u || seen.has(u)) {
      return
    }
    seen.add(u)
    rest.push(it)
  })

  if (url) {
    // 原主RSS在原位置转为备用
    rest.splice(Math.min(at, rest.length), 0, {
      label: subgroup,
      url: url,
      offset: offset ?? 0
    })
  }
  ani.standbyRssList = rest

  dialogVisible.value = false
  ElMessage.success(`已切换主RSS: ${standbyLabel(standby)}，点击「确定」保存后自动刷新`)
}

defineExpose({show})
let props = defineProps(['ani'])
</script>

<style scoped>
.switch-alert {
  margin-bottom: 8px;
}

.rss-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 8px 12px;
  margin-bottom: 8px;
}

.rss-item-master {
  background-color: var(--el-fill-color-light);
}

.rss-item-main {
  flex: 1;
  min-width: 0;
}

.rss-item-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 2px;
}

.rss-item-label {
  font-weight: 600;
}

.rss-item-url {
  width: 100%;
}
</style>
