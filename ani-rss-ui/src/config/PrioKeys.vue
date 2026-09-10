<template>
  <el-dialog title="关键词设置" v-if="add" v-model:model-value="add" center align-center width="420"
             @open="$nextTick(() => $refs.keywordInput?.focus())">
    <el-form @submit.prevent="addKeyword" label-width="auto">
      <el-form-item label="关键词">
        <el-input ref="keywordInput" type="textarea" :autosize="{minRows: 3}"
                  placeholder="如：简体、繁体、1080p；可一次粘贴多行，每行一个"
                  v-model="keyword"
                  @keyup.enter.stop="addKeyword"/>
      </el-form-item>
      <el-text class="mx-1" size="small" type="info">
        多行粘贴会按行拆成多个关键词，空行自动忽略
      </el-text>
    </el-form>
    <div class="flex prio-keys-dialog-footer">
      <el-button bg text @click="addKeyword" icon="Plus">添加</el-button>
    </div>
  </el-dialog>
  <div class="full-width">
    <div class="gap-2">
      <el-tag v-if="!props.keywords.length" type="info" class="prio-keys-tag">
        无
      </el-tag>
      <el-tag v-for="(tag, index) in props.keywords" :key="tag" closable :disable-translations="false"
              @close="handleClose(index)" class="prio-keys-tag">
        <el-tooltip :content="`优先级: ${index + 1}`">
          <el-text line-clamp="1" size="small" class="prio-keys-tag-text">
            {{ tag }}
          </el-text>
        </el-tooltip>
      </el-tag>
      <el-button bg icon="Plus" size="small" class="prio-keys-tag" text
                 @click="() => add = true"/>
      <el-button v-if="props.keywords.length" bg icon="Delete" size="small"
                 class="prio-keys-delete-button" text type="danger"
                 @click="clearAll">
        清空全部
      </el-button>
    </div>
    <div class="flex prio-keys-footer">
      <el-button bg text size="small" @click="importGlobalKeywords" v-if="props.importGlobal"
                 :disabled="disabledImport" :loading="importLoading">
        <el-icon>
          <Download/>
        </el-icon>
        从全局导入关键词
      </el-button>
      <el-text class="mx-1" size="small" v-if="props.showText">
        当种子包含多个文件时，优先保留的文件关键词，优先级从左到右递减。
      </el-text>
    </div>
  </div>
</template>

<script setup>
import {ref} from "vue";
import {ElMessage, ElMessageBox} from "element-plus";
import {Download} from '@element-plus/icons-vue'
import {config} from "@/js/http.js";

const handleClose = (index) => {
  props.keywords.splice(index, 1)
}

/**
 * 清空全部：此前一次误点即丢光所有关键词且不可撤销
 */
const clearAll = async () => {
  const removed = [...props.keywords]
  try {
    await ElMessageBox.confirm(
        `将清空全部 ${removed.length} 个优先保留关键词，是否继续？`,
        '清空关键词',
        {type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消'}
    )
  } catch (e) {
    return
  }
  props.keywords.length = 0
  ElMessage({
    type: 'info',
    duration: 6000,
    showClose: true,
    message: `已清空 ${removed.length} 个关键词（点「确定」保存后生效）`
  })
}

const add = ref(false)
const keyword = ref('')

const addKeyword = () => {
  // 支持一次粘贴多行，每行一个关键词
  const lines = (keyword.value || '')
      .split('\n')
      .map(it => it.trim())
      .filter(it => it.length)
  if (!lines.length) {
    ElMessage.error('关键词不能为空')
    return
  }
  let duplicated = 0
  for (const line of lines) {
    if (props.keywords.indexOf(line) > -1) {
      duplicated++
      continue
    }
    props.keywords.push(line)
  }
  if (duplicated) {
    ElMessage.warning(`已跳过 ${duplicated} 个重复关键词`)
  }
  keyword.value = ''
  add.value = false
}

let importLoading = ref(false)
let disabledImport = ref(false)

let importGlobalKeywords = () => {
  importLoading.value = true
  config()
      .then(res => {
        disabledImport.value = true
        if (!res.data.priorityKeywords || !res.data.priorityKeywords.length) {
          ElMessage.warning('全局优先保留关键词为空')
          return
        }
        for (let keyword of res.data.priorityKeywords) {
          if (props.keywords.indexOf(keyword) > -1) {
            continue
          }
          props.keywords.push(keyword)
        }
        ElMessage.success('导入成功')
      })
      .finally(() => {
        importLoading.value = false
      })
}

let props = defineProps({
  keywords: Array,
  importGlobal: Boolean,
  showText: Boolean
})
</script>

<style scoped>
.prio-keys-dialog-footer {
  width: 100%;
  justify-content: end;
  margin-top: 8px;
}

.prio-keys-tag {
  margin-right: 4px;
  margin-bottom: 4px;
}

.prio-keys-tag-text {
  max-width: 300px;
  color: var(--el-color-primary);
}

.prio-keys-delete-button {
  margin-left: 0;
  margin-bottom: 4px;
}

.prio-keys-footer {
  margin-top: 4px;
  width: 100%;
  justify-content: space-between;
}
</style>
