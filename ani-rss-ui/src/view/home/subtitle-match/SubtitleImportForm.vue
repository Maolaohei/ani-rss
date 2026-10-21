<template>
  <el-form class="match-form" label-width="90px">
    <el-form-item label="订阅">
      <el-select v-model="aniId" :disabled="step !== 'form'" clearable filterable class="full-width"
                 placeholder="选择字幕对应的订阅（匹配该订阅下载目录内的视频）">
        <el-option v-for="ani in aniOptions" :key="ani.id" :label="ani.title" :value="ani.id"/>
      </el-select>
    </el-form-item>

    <el-form-item v-if="selectedAni" label="剧集信息">
      <div class="ani-meta">
        <el-tag size="small" type="primary">第 {{ selectedAni.season || 1 }} 季</el-tag>
        <el-tag size="small" type="info">
          共 {{ selectedAni.totalEpisodeNumber || '未知' }} 集
        </el-tag>
        <el-tag size="small">已下载 {{ selectedAni.currentEpisodeNumber ?? 0 }} 集</el-tag>
        <el-text class="ani-meta-title" size="small" type="info">{{ selectedAni.title }}</el-text>
      </div>
    </el-form-item>

    <el-form-item v-if="mode === 'upload'" label="字幕文件">
      <div class="import-dropzone" :class="{'is-dragover': dragOver}"
           @click="pickFiles"
           @dragenter.prevent="dragOver = true"
           @dragover.prevent
           @dragleave.prevent="dragOver = false"
           @drop.prevent="onDrop">
        <el-icon class="dropzone-icon">
          <UploadFilled/>
        </el-icon>
        <div class="dropzone-text">点击选择，或将字幕文件拖拽到此处（可多选）</div>
        <div class="dropzone-hint">支持 ass / srt / ssa / vtt / sub，单个不超过 20MiB</div>
      </div>
      <input ref="fileInputRef" :accept="acceptExt" hidden multiple type="file" @change="onPick">

      <div v-if="pendingFiles.length" class="pending-list">
        <div class="pending-head">
          <el-text size="small">已选择 {{ pendingFiles.length }} 个文件（{{ formatSize(pendingBytes) }}）</el-text>
          <el-button link size="small" type="primary" @click="$emit('clear-pending')">清空</el-button>
        </div>
        <el-scrollbar max-height="150px">
          <div v-for="(f, i) in pendingFiles" :key="f.name + '-' + i" class="pending-item">
            <el-text class="pending-name" size="small">{{ f.name }}</el-text>
            <el-text size="small" type="info">{{ formatSize(f.size) }}</el-text>
            <el-button link size="small" type="danger" icon="Close" @click="$emit('remove-pending', i)"/>
          </div>
        </el-scrollbar>
      </div>
    </el-form-item>

    <el-form-item v-else label="获取说明">
      <el-text size="small" type="info">
        先<b>搜索候选</b>：以该订阅的<b>英文标题</b>向射手网(ASSRT)发起<b>单次</b>搜索（不按季集拆分请求），
        列出候选字幕后由你挑选；选中后再下载并预览，确认无误才写入。
        需先在 设置 → 其他设置 中开启「字幕手动获取」并填写 ASSRT Token。
        字幕源有调用频率限制（默认 5 次/分钟），因此搜索阶段只发一次请求。
      </el-text>
    </el-form-item>
  </el-form>
</template>

<script setup>
import {ref} from "vue";
import {formatSize} from "@/js/format.js";
import {SUBTITLE_ACCEPT} from "./format.js";

defineProps({
  step: {type: String, default: 'form'},
  aniOptions: {type: Array, default: () => []},
  selectedAni: {type: Object, default: null},
  pendingFiles: {type: Array, default: () => []},
  pendingBytes: {type: Number, default: 0}
})

const emit = defineEmits(['add-files', 'remove-pending', 'clear-pending'])

const mode = defineModel('mode', {type: String, default: 'upload'})
const aniId = defineModel('aniId', {type: String, default: ''})
const dragOver = defineModel('dragOver', {type: Boolean, default: false})

const acceptExt = SUBTITLE_ACCEPT
const fileInputRef = ref()

const pickFiles = () => {
  if (!fileInputRef.value) {
    return
  }
  fileInputRef.value.value = ''
  fileInputRef.value.click()
}

const onPick = () => {
  emit('add-files', fileInputRef.value?.files)
}

const onDrop = (e) => {
  dragOver.value = false
  emit('add-files', e.dataTransfer?.files)
}
</script>

<style scoped src="./subtitle-import.css"></style>
