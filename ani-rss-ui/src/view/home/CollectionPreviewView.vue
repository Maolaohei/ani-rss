<template>
  <el-dialog v-model="dialogVisible"
             center
             class="el-dialog-auto-width"
             title="合集预览">
    <div v-loading="loading">
      <el-alert
          v-if="loadError"
          class="collection-preview-alert"
          type="error"
          show-icon
          :closable="false"
          :title="loadError"/>
      <el-empty v-else-if="!loading && !list.length" description="没有可预览的条目"/>
      <el-table v-else :data="list" height="500"
                size="small"
                scrollbar-always-on
                stripe>
        <el-table-column label="标题" min-width="400" prop="title"/>
        <el-table-column label="重命名" min-width="280" prop="reName"/>
        <el-table-column label="集数" prop="episode"/>
        <el-table-column label="大小" min-width="100" prop="formatSize"/>
      </el-table>
    </div>
    <div v-if="subgroupApplyable" style="margin-top:12px;">
      <el-alert show-icon :closable="false">
        <template #title>
          <div class="flex" style="width:100%;justify-content: space-between;">
            <span>
              检测到字幕组为 {{ subgroup }}
            </span>
            <el-button bg size="small" text type="primary" @click="applySubgroup">使用该字幕组</el-button>
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
let loadError = ref('')

let list = ref([])

let subgroup = ref('')

let show = () => {
  subgroup.value = ''
  loadError.value = ''
  dialogVisible.value = true
  loading.value = true
  http.previewCollection(props.data)
      .then(res => {
        list.value = res.data
        subgroup.value = getSubgroup()
      })
      .catch(e => {
        list.value = []
        loadError.value = e?.message || '预览加载失败，请稍后重试'
      })
      .finally(() => {
        loading.value = false
      })
}

/**
 * 从标题的 [x] 前缀统计众数作为字幕组；
 * 合集常见多字幕组混排，取"第一条"容易写错。
 */
let getSubgroup = () => {
  if (!list.value) {
    return ''
  }

  let counts = new Map()
  for (let item of list.value) {
    let match = (item['title'] || '').match(/^\[(.+?)]/)
    if (!match) {
      continue
    }
    let name = match[1]
    counts.set(name, (counts.get(name) || 0) + 1)
  }

  let best = ''
  let bestCount = 0
  for (let [name, count] of counts) {
    if (count > bestCount) {
      best = name
      bestCount = count
    }
  }
  return best
}

const subgroupApplyable = computed(() => {
  return !!subgroup.value && subgroup.value !== props.data.ani.subgroup
})

let applySubgroup = () => {
  props.data.ani.subgroup = subgroup.value
  ElMessage.success(`已使用字幕组：${subgroup.value}`)
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

.collection-preview-alert {
  margin-bottom: 8px;
}
</style>
