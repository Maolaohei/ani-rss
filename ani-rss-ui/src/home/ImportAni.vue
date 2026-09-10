<template>
  <el-dialog
      v-model="dialogVisible"
      title="导入数据"
      width="500px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
  >
    <div class="import-dialog">
      <!-- 文件上传区域 -->
      <div class="upload-section">
        <div class="section-title">
          <el-icon>
            <document/>
          </el-icon>
          <span>选择文件</span>
        </div>

        <div v-if="data.filename" class="file-selected">
          <el-tag
              closable
              @close="resetFile"
              :type="parsed ? 'success' : 'danger'"
              size="large"
              class="file-tag"
          >
            <el-icon>
              <document/>
            </el-icon>
            {{ data.filename }}
          </el-tag>
          <div class="file-info">
            <el-text v-if="parsed" type="info" size="small">
              已选择文件，共 {{ data.aniList.length }} 条数据
            </el-text>
            <el-text v-else type="danger" size="small">
              {{ parseError || '文件解析中…' }}
            </el-text>
          </div>
        </div>

        <el-upload
            v-else
            :before-upload="beforeUpload"
            :show-file-list="false"
            class="upload-area"
            drag
            accept=".json"
        >
          <div class="upload-content">
            <el-icon class="upload-icon">
              <upload-filled/>
            </el-icon>
            <div class="upload-text">
              <div class="upload-main-text">拖拽文件到此处</div>
              <div class="upload-sub-text">或 <em>点击选择文件</em></div>
            </div>
            <div class="upload-tip">
              仅支持由本程序「导出」生成的 JSON，文件大小不超过 10MB
            </div>
          </div>
        </el-upload>
      </div>

      <!-- 冲突处理设置 -->
      <div class="conflict-section" v-if="data.filename && parsed">
        <div class="section-title">
          <el-icon>
            <setting/>
          </el-icon>
          <span>冲突处理</span>
        </div>
        <div class="conflict-content">
          <el-radio-group v-model="data.conflict" class="conflict-options">
            <el-radio value="REPLACE" class="conflict-option">
              <div class="option-content">
                <div class="option-title">替换现有数据</div>
                <div class="option-desc">用新数据覆盖同名的现有数据</div>
              </div>
            </el-radio>
            <el-radio value="SKIP" class="conflict-option">
              <div class="option-content">
                <div class="option-title">跳过冲突数据</div>
                <div class="option-desc">保留现有数据，跳过重复项</div>
              </div>
            </el-radio>
          </el-radio-group>
          <el-alert
              v-if="data.conflict === 'REPLACE'"
              class="conflict-alert"
              type="warning"
              show-icon
              :closable="false"
              :title="conflictAlertText"
          />
        </div>
      </div>

      <!-- 操作按钮 -->
      <div class="action-section">
        <el-button
            @click="dialogVisible = false"
            size="large"
        >
          取消
        </el-button>
        <el-button
            type="primary"
            :loading="importDataLoading"
            :disabled="!parsed"
            @click="startImport"
            size="large"
        >
          <el-icon v-if="!importDataLoading">
            <upload/>
          </el-icon>
          {{ importDataLoading ? '导入中...' : '开始导入' }}
        </el-button>
      </div>
    </div>
  </el-dialog>
</template>
<script setup>
import {computed, getCurrentInstance, ref} from "vue";
import {Document, Setting, Upload, UploadFilled} from "@element-plus/icons-vue";
import {ElMessage} from "element-plus";
import {importAni} from "@/js/http.js";
import * as http from "@/js/http.js";

/** 单文件上限：与界面提示保持一致 */
const MAX_FILE_SIZE = 10 * 1024 * 1024

let importDataLoading = ref(false);
/** 是否已成功解析出可用的订阅数组 */
let parsed = ref(false);
let parseError = ref('');

const conflictAlertText = computed(() => {
  if (!replaceCount.value) {
    return '未检测到与现有订阅同名的数据（导入后会新增）'
  }
  return `将覆盖 ${replaceCount.value} 条同名订阅（匹配、排除规则与下载进度会随之丢失）`
})

/** 与现有订阅同名同季的条数（用于 REPLACE 风险提示与结果核对） */
let replaceCount = ref(0)

let startImport = () => {
  if (!parsed.value) {
    ElMessage.error(parseError.value || '请先选择有效的 JSON 文件')
    return
  }
  importDataLoading.value = true;
  importAni(data.value)
      .then(res => {
        let total = data.value.aniList.length
        if (res.code !== 200) {
          ElMessage.error(res.message || '导入失败')
          return
        }
        if (data.value.conflict === 'REPLACE' && replaceCount.value) {
          ElMessage.success(`导入完成：共 ${total} 条，其中覆盖同名订阅 ${replaceCount.value} 条`)
        } else if (data.value.conflict === 'SKIP' && replaceCount.value) {
          ElMessage.success(`导入完成：共 ${total} 条，跳过同名订阅 ${replaceCount.value} 条`)
        } else {
          ElMessage.success(`导入完成：新增 ${total} 条`)
        }
        if (instance.vnode.props.onCallback) {
          emit('callback')
        } else {
          window.$reLoadList()
        }
        dialogVisible.value = false
      })
      .catch(err => {
        ElMessage.error(err?.message || '导入失败，请检查文件内容后重试')
      })
      .finally(() => {
        importDataLoading.value = false
      })
}

let dialogVisible = ref(false);
let data = ref({
  filename: '',
  aniList: [],
  conflict: 'REPLACE'
})

const resetFile = () => {
  data.value.filename = ''
  data.value.aniList = []
  parsed.value = false
  parseError.value = ''
  replaceCount.value = 0
}

let beforeUpload = (rawFile) => {
  resetFile()
  if (!rawFile.name.toLowerCase().endsWith('.json')) {
    parseError.value = '仅支持 .json 文件'
    ElMessage.error(parseError.value)
    return false
  }
  if (rawFile.size > MAX_FILE_SIZE) {
    parseError.value = `文件不能超过 ${MAX_FILE_SIZE / 1024 / 1024}MB`
    ElMessage.error(parseError.value)
    return false
  }

  data.value.filename = rawFile.name;
  parseError.value = '文件解析中…'
  readJSONFile(rawFile)
      .then(list => {
        if (!Array.isArray(list)) {
          throw new Error('文件内容不是订阅数组，请使用本程序「导出」生成的文件')
        }
        data.value.aniList = list
        parsed.value = true
        parseError.value = ''
        countConflicts()
      })
      .catch(error => {
        // 解析失败时清空选择，避免出现“已选文件 共 0 条”却还能点导入
        let message = error?.message || String(error)
        data.value.aniList = []
        parsed.value = false
        parseError.value = message
        ElMessage.error(message)
      })
  return false
}

/** 统计将要被覆盖/跳过的同名同季订阅数量 */
let countConflicts = () => {
  replaceCount.value = 0
  return http.listAni()
      .then(res => {
        let existing = new Set()
        let weekList = res?.data?.weekList || []
        for (let week of weekList) {
          for (let item of (week.items || [])) {
            existing.add(`${(item.title || '').trim()}#${item.season ?? 1}`)
          }
        }
        let count = 0
        for (let item of data.value.aniList) {
          if (existing.has(`${(item.title || '').trim()}#${item.season ?? 1}`)) {
            count++
          }
        }
        replaceCount.value = count
      })
      .catch(() => {
        replaceCount.value = 0
      })
}

let readJSONFile = (file) => {
  return new Promise((resolve, reject) => {
    let reader = new FileReader();

    reader.onload = (event) => {
      try {
        let jsonData = JSON.parse(event.target.result);
        resolve(jsonData);
      } catch (error) {
        // 抛 Error 而不是字符串，否则调用方取 error.message 会得到 undefined（此前会弹空白 toast）
        reject(new Error(`JSON 解析失败：${error.message}`));
      }
    };

    reader.onerror = () => {
      reject(new Error("文件读取失败"));
    };

    reader.readAsText(file);
  });
}

let show = () => {
  data.value = {
    filename: '',
    aniList: [],
    conflict: 'REPLACE'
  }
  parsed.value = false
  parseError.value = ''
  replaceCount.value = 0
  dialogVisible.value = true;
}

defineExpose({show})

const instance = getCurrentInstance()

const emit = defineEmits(['callback'])
</script>

<style scoped>
.import-dialog {
  padding: 0;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 16px;
  font-size: 16px;
}

.upload-section {
  margin-bottom: 24px;
}

.file-selected {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-8);
  border-radius: 8px;
}

.file-tag {
  align-self: flex-start;
  font-size: 14px;
  padding: 8px 12px;
}

.file-info {
  margin-top: 4px;
}

.upload-area {
  width: 100%;
}

.upload-content {
  padding: 40px 20px;
  text-align: center;
}

.upload-icon {
  font-size: 48px;
  color: var(--el-text-color-placeholder);
  margin-bottom: 16px;
}

.upload-text {
  margin-bottom: 12px;
}

.upload-main-text {
  font-size: 16px;
  color: var(--el-text-color-regular);
  margin-bottom: 4px;
}

.upload-sub-text {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}

.upload-sub-text em {
  color: var(--el-color-primary);
  font-style: normal;
}

.upload-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 8px;
}

.conflict-section {
  margin-bottom: 24px;
  padding: 20px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
  border: 1px solid var(--el-border-color-extra-light);
}

.conflict-content {
  margin-top: 12px;
}

.conflict-options {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.conflict-alert {
  margin-top: 12px;
}

.conflict-option {
  margin: 0;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  transition: border-color var(--dur-fast), background-color var(--dur-fast);
  box-sizing: content-box;
}

.conflict-option:hover {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.conflict-option.is-checked {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.option-content {
  margin-left: 8px;
}

.option-title {
  font-weight: 500;
  color: var(--el-text-color-primary);
  margin-bottom: 4px;
}

.option-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}

.action-section {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color-extra-light);
}

/* 上传区域拖拽状态（组件层 el-upload-dragger 规则的本地兜底） */
.upload-area :deep(.el-upload-dragger) {
  border: 2px dashed var(--el-border-color);
  border-radius: 8px;
  transition: border-color var(--dur-fast), background-color var(--dur-fast);
}

.upload-area :deep(.el-upload-dragger:hover) {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.upload-area :deep(.el-upload-dragger.is-dragover) {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

/* 响应式设计 */
@media (max-width: 768px) {
  .import-dialog {
    padding: 0;
  }

  .upload-content {
    padding: 30px 15px;
  }

  .upload-icon {
    font-size: 36px;
  }

  .conflict-section {
    padding: 16px;
  }

  .action-section {
    flex-direction: column;
  }

  .action-section .el-button {
    width: 100%;
  }
}
</style>
