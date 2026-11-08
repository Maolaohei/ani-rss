<template>
  <el-dialog v-model="dialogVisible"
             append-to-body
             :z-index="4000"
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
      <template v-else>
        <el-alert v-if="groups.length > 1"
                  class="collection-preview-alert"
                  :closable="false"
                  show-icon
                  type="success">
          <template #title>
            该种子包含 {{ groups.length }} 个部分（{{ groups.map(g => g.name).join(' + ') }}），
            每个部分会生成独立订阅，并按各自的季 / 偏移 / 目录落盘。
          </template>
        </el-alert>
        <el-tabs v-if="groups.length > 1" v-model="activeTab">
          <el-tab-pane v-for="group in groups" :key="group.id" :name="group.id">
            <template #label>
              {{ group.name }}
              <el-tag size="small" style="margin-left:4px;">{{ group.items.length }}</el-tag>
            </template>
          </el-tab-pane>
        </el-tabs>
        <el-empty v-if="!loading && !currentItems.length" description="没有可预览的条目"/>
        <el-table v-else :data="currentItems" height="460"
                  size="small"
                  scrollbar-always-on
                  stripe>
          <el-table-column v-if="groups.length > 1" label="部分" width="110" prop="segmentName">
            <template #default="scope">
              {{ segmentName(scope.row.segmentId) }}
            </template>
          </el-table-column>
          <el-table-column label="标题" min-width="360" prop="title"/>
          <el-table-column label="重命名" min-width="280" prop="reName"/>
          <el-table-column label="集数" prop="episode"/>
          <el-table-column label="大小" min-width="100" prop="formatSize"/>
        </el-table>
      </template>
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
        <span>共 {{ currentItems.length }} 项</span>
      </div>
      <div>
        <el-button bg icon="Refresh" text @click="show">重新预览</el-button>
        <el-button bg icon="Close" text @click="dialogVisible = false">关闭</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import {computed, ref, watch} from "vue";
import {ElMessage} from "element-plus";
import * as http from "@/js/http.js";

// props 必须先声明：下面的 segmentOrder / groups 等计算属性，以及 watch(groups) 在 setup
// 阶段就会读取 props.data；声明写在它们后面会命中 TDZ（Cannot access 'props' before initialization）
let props = defineProps(['data'])

let dialogVisible = ref(false)
let loading = ref(false)
let loadError = ref('')

let list = ref([])
let activeTab = ref('')

let subgroup = ref('')

let show = () => {
  subgroup.value = ''
  loadError.value = ''
  dialogVisible.value = true
  loading.value = true
  http.previewCollection(props.data)
      .then(res => {
        list.value = res.data || []
        subgroup.value = getSubgroup()
        activeTab.value = groups.value.length ? groups.value[0].id : ''
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

/**
 * 分段顺序以合集设置里的 segments 为准（后端按季 / 剧场版排好），
 * 这样即使预览接口按集数全局排序，分组的展示顺序也不会乱。
 */
let segmentOrder = computed(() => {
  let order = new Map()
  ;(props.data.segments || []).forEach((seg, index) => order.set(seg.id, index))
  return order
})

let segmentName = (id) => {
  if (!id) {
    return '全部'
  }
  let seg = (props.data.segments || []).find(item => item.id === id)
  if (!seg) {
    return '部分'
  }
  if (seg.ova) {
    return seg.name || '剧场版'
  }
  return seg.name || `第${seg.season ?? 0}季`
}

let groups = computed(() => {
  let order = segmentOrder.value
  let byId = new Map()
  for (let item of list.value) {
    let id = item.segmentId || ''
    if (!byId.has(id)) {
      byId.set(id, [])
    }
    byId.get(id).push(item)
  }

  let ids = [...byId.keys()].sort((a, b) => {
    return (order.get(a) ?? 999) - (order.get(b) ?? 999)
  })

  return ids.map(id => ({
    id: id || 'default',
    name: segmentName(id),
    items: byId.get(id).slice().sort((a, b) => (a.episode ?? 0) - (b.episode ?? 0)),
  }))
})

let currentItems = computed(() => {
  if (groups.value.length <= 1) {
    return list.value
  }
  let group = groups.value.find(item => item.id === activeTab.value)
  return group ? group.items : []
})

// 切段 / 重新预览后 tab 可能失效，兜底回第一段
watch(groups, (value) => {
  if (value.length && !value.some(item => item.id === activeTab.value)) {
    activeTab.value = value[0].id
  }
})

const subgroupApplyable = computed(() => {
  return !!subgroup.value && subgroup.value !== props.data.ani.subgroup
})

let applySubgroup = () => {
  props.data.ani.subgroup = subgroup.value
  ElMessage.success(`已使用字幕组：${subgroup.value}`)
  show()
}

defineExpose({show})
</script>
<style scoped>
.action {
  margin-top: 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.collection-preview-alert {
  margin-bottom: 8px;
}
</style>
