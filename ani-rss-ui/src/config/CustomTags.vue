<template>
  <div class="full-width">
    <el-tag
        v-if="!props.config.customTags.length"
        type="info"
        class="margin-4px">
      无
    </el-tag>
    <el-tag
        v-for="(tag, index) in props.config.customTags"
        :key="tag"
        closable
        @close="removeCustomTag(index)"
        class="margin-4px"
    >
      {{ tag }}
    </el-tag>
    <el-input
        v-if="inputVisible"
        v-model="inputValue"
        ref="inputRef"
        type="textarea"
        :autosize="{minRows: 2}"
        size="small"
        class="margin-4px custom-tags-input"
        placeholder="可一次粘贴多行，每行一个标签"
        @keyup.enter.stop="handleInputConfirm"
    />
    <template v-else>
      <el-button
          icon="Plus"
          size="small"
          bg text
          class="margin-4px"
          @click="showInput"
      >
        添加标签
      </el-button>
      <el-button
          v-if="props.config.customTags.length"
          icon="Delete"
          size="small"
          bg text
          type="danger"
          class="margin-4px"
          @click="clearAll"
      >
        清空全部
      </el-button>
    </template>
    <el-text class="mx-1" size="small" type="info" style="display: block;">
      这些标签会写进重命名结果，用于 Emby/Jellyfin 识别；支持一次粘贴多行
    </el-text>
  </div>
</template>

<script setup>
import {nextTick, ref} from "vue";
import {ElMessage, ElMessageBox} from "element-plus";

let props = defineProps(['config'])

const inputVisible = ref(false)
const inputValue = ref('')
const inputRef = ref()

const showInput = () => {
  inputVisible.value = true
  nextTick(() => {
    inputRef.value && inputRef.value.focus()
  })
}

const handleInputConfirm = () => {
  if (!Array.isArray(props.config.customTags)) {
    props.config.customTags = []
  }
  // 支持一次粘贴多行，每行一个标签
  const lines = (inputValue.value || '')
      .split('\n')
      .map(it => it.trim())
      .filter(it => it.length)
  let skipped = 0
  for (const value of lines) {
    if (props.config.customTags.includes(value)) {
      skipped++
      continue
    }
    props.config.customTags.push(value)
  }
  if (skipped) {
    ElMessage.warning(`已跳过 ${skipped} 个重复标签`)
  }
  inputVisible.value = false
  inputValue.value = ''
}

const removeCustomTag = (index) => {
  props.config.customTags.splice(index, 1)
}

const clearAll = async () => {
  const removed = [...props.config.customTags]
  try {
    await ElMessageBox.confirm(
        `将清空全部 ${removed.length} 个自定义标签，是否继续？`,
        '清空自定义标签',
        {type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消'}
    )
  } catch (e) {
    return
  }
  props.config.customTags.length = 0
  ElMessage({
    type: 'info',
    duration: 6000,
    showClose: true,
    message: `已清空 ${removed.length} 个标签（点「确定」保存后生效）`
  })
}
</script>

<style scoped>
.margin-4px {
  margin: 0 0 4px 4px;
}

.custom-tags-input {
  width: 260px;
}
</style>
