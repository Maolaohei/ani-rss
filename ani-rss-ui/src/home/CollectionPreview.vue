<template>
  <el-dialog v-model="dialogVisible"
             center
             class="el-dialog-auto-width"
             title="合集预览">
    <div v-loading="loading">
      <el-table :data="list" height="500"
                size="small"
                scrollbar-always-on
                stripe>
        <el-table-column label="标题" min-width="400" prop="title"/>
        <el-table-column label="重命名" min-width="280" prop="reName"/>
        <el-table-column label="集数" prop="episode"/>
        <el-table-column label="大小" min-width="100" prop="formatSize"/>
      </el-table>
    </div>
    <el-alert
        v-if="loadError"
        class="preview-alert"
        type="error"
        show-icon
        :closable="false"
        :title="`获取预览失败：${loadError}`"
    />
    <div v-if="!loading && !loadError && !list.length">
      <el-empty description="没有可预览的条目" :image-size="80"/>
    </div>
    <div v-if="subgroupApplyable" class="subgroup-apply" style="margin-top:12px;">
      <el-alert type="info" show-icon :closable="false">
        <template #title>
          <div class="flex subgroup-apply-inner">
            <span>检测到字幕组为 {{ subgroup }}，与当前订阅不一致</span>
            <el-button size="small" bg text type="primary" @click="applySubgroup">
              使用该字幕组
            </el-button>
          </div>
        </template>
      </el-alert>
    </div>
    <div class="action">
      <div>
        <span>共 {{ list.length }} 项</span>
      </div>
      <el-button bg icon="Close" text @click="dialogVisible = false">关闭</el-button>
    </div>
  </el-dialog>
</template>

<script setup>
import {computed, ref} from "vue";
import {ElMessage} from "element-plus";
import * as http from "@/js/http.js";

let dialogVisible = ref(false)
let loading = ref(false)

let list = ref([])

let loadError = ref('')

/** 预览里识别出的主要字幕组（取出现次数最多的一个，避免被单条混排取样带偏） */
let subgroup = ref('')

const subgroupApplyable = computed(() => {
  return !!subgroup.value && subgroup.value !== props.data.ani.subgroup
})

let show = () => {
  subgroup.value = ''
  loadError.value = ''
  list.value = []
  dialogVisible.value = true
  loading.value = true
  http.previewCollection(props.data)
      .then(res => {
        list.value = res.data ? res.data : []
        subgroup.value = getSubgroup()
      })
      .catch(err => {
        loadError.value = err?.message || '未知错误'
      })
      .finally(() => {
        loading.value = false
      })
}

/**
 * 统计所有条目标题里 [字幕组] 前缀的出现次数，取众数。
 * 旧实现只取第一条，多字幕组混排时会把错误的字幕组写进订阅。
 */
let getSubgroup = () => {
  if (!list.value || !list.value.length) {
    return ''
  }

  let counter = new Map()
  for (let item of list.value) {
    let matched = (item['title'] || '').match(/^\[(.+?)]/)
    if (!matched) {
      continue
    }
    let name = matched[1]
    counter.set(name, (counter.get(name) || 0) + 1)
  }

  let best = ''
  let bestCount = 0
  for (let [name, count] of counter) {
    if (count > bestCount) {
      best = name
      bestCount = count
    }
  }
  return best
}

/** 显式「使用该字幕组」，不再借用 alert 的关闭按钮（×）语义 */
let applySubgroup = () => {
  if (!subgroup.value) {
    return
  }
  props.data.ani.subgroup = subgroup.value
  ElMessage.success(`已使用字幕组「${subgroup.value}」`)
  show()
}

defineExpose({show})

let props = defineProps(['data'])
</script>
<style scoped>
.action {
  margin-top: 12px;
  display: flex;
  justify-content: space-between;
}

.preview-alert {
  margin-bottom: 8px;
}

.subgroup-apply-inner {
  width: 100%;
  justify-content: space-between;
  align-items: center;
}
</style>
