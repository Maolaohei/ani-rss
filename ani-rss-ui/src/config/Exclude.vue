<template>
  <el-dialog title="添加正则" v-if="add" v-model:model-value="add" center align-center width="420">
    <el-form @submit.prevent label-width="auto">
      <el-form-item label="字幕组">
        <el-input placeholder="留空匹配所有字幕组" v-model="subgroup"></el-input>
      </el-form-item>
      <div class="exclude-spacer"></div>
      <el-form-item label="正则">
        <el-input type="textarea"
                  :autosize="{minRows: 3}"
                  placeholder="如 720、简、\d-\d；可一次粘贴多行，每行一条"
                  v-model="exclude"></el-input>
      </el-form-item>
      <el-text class="mx-1" size="small" type="info">
        多行粘贴会按行拆成多条规则，空行自动忽略
      </el-text>
    </el-form>
    <div class="flex exclude-dialog-footer">
      <el-button bg text @click="addExclude" icon="Plus">添加</el-button>
    </div>
  </el-dialog>
  <div class="full-width">
    <div class="gap-2">
      <el-tag v-if="!props.exclude.length"
              type="info"
              class="exclude-tag">
        无
      </el-tag>
      <el-tag
          v-for="tag in props.exclude"
          :key="tag"
          closable
          :disable-transitions="false"
          @close="handleClose(tag)"
          class="exclude-tag"
      >
        <el-tooltip :content="tag">
          <el-text line-clamp="1" size="small" class="exclude-tag-text">
            {{ tag }}
          </el-text>
        </el-tooltip>
      </el-tag>
      <el-button bg
                 icon="Plus"
                 size="small"
                 class="exclude-tag"
                 text
                 @click="()=> add = true"
      />
      <el-button
          v-if="props.exclude.length"
          bg
          icon="Delete"
          size="small"
          class="exclude-delete-button"
          text
          type="danger"
          @click="clearAll"
      >
        清空全部
      </el-button>
    </div>
    <div class="flex exclude-footer">
      <el-button bg text size="small" @click="importExclude" v-if="props.importExclude"
                 :disabled="disabledImportExclude" :loading="importExcludeLoading">
        <el-icon>
          <Download/>
        </el-icon>
        导入全局排除
      </el-button>
      <el-text class="mx-1" size="small" v-if="props.showText">
        支持&nbsp;
        <el-link
            class="exclude-link"
            type="primary"
            href="https://www.runoob.com/regexp/regexp-syntax.html"
            target="_blank">
          正则表达式
        </el-link>
      </el-text>
    </div>
  </div>
</template>

<script setup>
import {ref} from "vue";
import {ElMessage, ElMessageBox} from "element-plus";
import {config} from "@/js/http.js";

const handleClose = (tag) => {
  props.exclude.splice(props.exclude.indexOf(tag), 1)
}

/**
 * 清空全部：此前是一次误点即丢光所有规则且不可撤销。
 * 这里先确认，清空后给一条带"撤销"的提示。
 */
const clearAll = async () => {
  const removed = [...props.exclude]
  try {
    await ElMessageBox.confirm(
        `将清空全部 ${removed.length} 条排除规则，是否继续？`,
        '清空排除规则',
        {type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消'}
    )
  } catch (e) {
    return
  }
  props.exclude.length = 0
  ElMessage({
    type: 'info',
    duration: 6000,
    showClose: true,
    message: `已清空 ${removed.length} 条规则（点「确定」保存后生效）`
  })
}

const add = ref(false)

let importExcludeLoading = ref(false)
let disabledImportExclude = ref(false)

let importExclude = () => {
  importExcludeLoading.value = true
  config()
      .then(res => {
        disabledImportExclude.value = true
        for (let it of res.data.exclude) {
          if (props.exclude.indexOf(it) > -1) {
            continue
          }
          props.exclude.push(it)
        }
      })
      .finally(() => {
        importExcludeLoading.value = false
      })

}

let subgroup = ref('')
let exclude = ref('')

let addExclude = () => {
  if (!exclude.value.trim()) {
    ElMessage.error('正则为空')
    return
  }
  // 支持一次粘贴多行：每行一条规则，空行忽略
  const lines = exclude.value
      .split('\n')
      .map(it => it.trim())
      .filter(it => it.length)
  if (!lines.length) {
    ElMessage.error('正则为空')
    return
  }
  for (const line of lines) {
    const rule = subgroup.value ? `{{${subgroup.value}}}:${line}` : line
    if (props.exclude.indexOf(rule) > -1) {
      continue
    }
    props.exclude.push(rule)
  }
  subgroup.value = ''
  exclude.value = ''
  add.value = false
}

let props = defineProps({
  exclude: Array,
  importExclude: Boolean,
  showText: Boolean
})
</script>

<style scoped>
.exclude-spacer {
  margin: 4px;
}

.exclude-dialog-footer {
  width: 100%;
  justify-content: end;
  margin-top: 8px;
}

.exclude-tag {
  margin-right: 4px;
  margin-bottom: 4px;
}

.exclude-tag-text {
  max-width: 300px;
  color: var(--el-color-primary);
}

.exclude-delete-button {
  margin-left: 0;
  margin-bottom: 4px;
}

.exclude-footer {
  margin-top: 4px;
  width: 100%;
  justify-content: space-between;
}

.exclude-link {
  font-size: var(--el-font-size-extra-small);
}
</style>
