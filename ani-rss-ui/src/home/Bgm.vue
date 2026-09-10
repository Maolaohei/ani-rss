<template>
  <el-dialog v-model="dialogVisible" center title="Bangumi">
    <div class="bgm-dialog-content">
      <div>
        <div class="bgm-search-container">
          <div class="bgm-search-input-wrapper">
            <el-input v-model:model-value="name" @keyup.enter="search" placeholder="请输入搜索标题（可用日文原名）"
                      clearable/>
          </div>
          <div class="bgm-search-spacer"></div>
          <el-button @click="search" :loading="searchLoading" text bg icon="Search" :disabled="!name">搜索</el-button>
        </div>
        <el-text class="bgm-search-tip" size="small">
          未找到时请尝试日文原名；名称相同的条目可根据「放送日期」区分。
        </el-text>
      </div>
      <el-table size="small" :data="list" height="500px" :empty-text="emptyText">
        <el-table-column prop="id" label="ID" width="70"/>
        <el-table-column label="封面" width="120">
          <template #default="it">
            <img :alt="list[it.$index]['name']" :src="proxyImage(list[it.$index]['images']['large'])" height="100px"
                 width="78px">
          </template>
        </el-table-column>
        <el-table-column label="名称" min-width="200">
          <template #default="it">
            <div>
              <span>{{ list[it.$index]['nameCn'] ? list[it.$index]['nameCn'] : list[it.$index]['name'] }}</span>
              <br v-if="list[it.$index]['nameCn']">
              <el-text v-if="list[it.$index]['nameCn']" size="small" type="info">
                {{ list[it.$index]['name'] }}
              </el-text>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="放送日期" width="120">
          <template #default="it">
            <el-text size="small">{{ list[it.$index]['date'] || '-' }}</el-text>
          </template>
        </el-table-column>
        <el-table-column label="BGM 链接" min-width="220">
          <template #default="it">
            <el-link :href="list[it.$index]['url']" target="_blank" type="primary" class="bgm-link">
              {{ list[it.$index]['url'] }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column width="90">
          <template #default="it">
            <div class="flex flex-center full-width">
              <el-button bg text type="primary" @click="ok(list[it.$index])">选择</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </el-dialog>
</template>
<script setup>
import {computed, ref} from "vue";
import {ElMessage} from "element-plus";
import {proxyImage} from "@/js/global.js";
import * as http from "@/js/http.js";

let dialogVisible = ref(false)

let name = ref('')

let searchLoading = ref(false)
let list = ref([])
/** 是否已经发起过搜索，用于区分"没搜"和"没搜到" */
let searched = ref(false)

const emptyText = computed(() => {
  if (searchLoading.value) {
    return '搜索中…'
  }
  return searched.value
      ? '未找到对应番剧，请尝试改用日文原名搜索'
      : '请输入关键词后搜索'
})

let search = () => {
  if (!name.value || !name.value.trim()) {
    ElMessage.warning('请输入搜索标题')
    return
  }
  searchLoading.value = true
  http.searchBgm(name.value.trim())
      .then(res => {
        list.value = res.data ? res.data : []
        searched.value = true
      })
      .catch(err => {
        ElMessage.error(err?.message || '搜索失败，请检查 Bangumi 服务是否可用')
      })
      .finally(() => {
        searchLoading.value = false
      })
}

let show = (s) => {
  name.value = ''
  searched.value = false
  list.value = []
  dialogVisible.value = true
  if (s) {
    name.value = s
    search()
  }
}

let ok = (it) => {
  if (!it) {
    return
  }
  emit('callback', it)
  dialogVisible.value = false
}

defineExpose({show})

const emit = defineEmits(['callback'])
</script>

<style scoped>
.bgm-dialog-content {
  min-height: 300px;
}

.bgm-search-container {
  display: flex;
  width: 100%;
}

.bgm-search-input-wrapper {
  flex: 1;
}

.bgm-search-spacer {
  width: 4px;
}

.bgm-search-tip {
  display: block;
  margin: 6px 0 8px 0;
}

.bgm-link {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
  vertical-align: bottom;
}
</style>
