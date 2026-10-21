<template>
  <el-dialog v-model="dialogVisible" title="字幕管理" width="880px" style="max-width: 94vw"
             :close-on-click-modal="false" @closed="resetDialog">
    <el-radio-group v-model="mode" class="mode-switch" :disabled="step !== 'form'">
      <el-radio-button value="upload">手动上传字幕</el-radio-button>
      <el-radio-button value="assrt">获取射手网字幕</el-radio-button>
    </el-radio-group>

    <SubtitleImportForm
        v-model:mode="mode"
        v-model:ani-id="aniId"
        v-model:drag-over="dragOver"
        :step="step"
        :ani-options="aniOptions"
        :selected-ani="selectedAni"
        :pending-files="pendingFiles"
        :pending-bytes="pendingBytes"
        @add-files="addFiles"
        @remove-pending="removePending"
        @clear-pending="clearPending"
    />

    <el-alert v-if="step === 'form'" class="import-tip" :closable="false" type="info" show-icon>
      <template #title>
        按「季 + 集」自动匹配订阅目录下<b>已重命名</b>的视频：字幕 <b>碧蓝之海 S03E15.cht.ass</b> 对应视频
        <b>碧蓝之海 S03E15.mkv</b>，将重命名为 <b>碧蓝之海 S03E15.cht.ass</b>；无语言标识则命名为
        <b>碧蓝之海 S03E15.ass</b>。同名文件覆盖前会自动备份到视频同目录的 <b>sub_bak/</b>（不存在则新建）。
      </template>
    </el-alert>

    <section v-if="step === 'select'" class="import-result">
      <div class="section-title">
        <h3>选择字幕</h3>
        <div class="result-summary">
          <el-tag size="small" type="primary">共 {{ candidates.length }} 条候选</el-tag>
          <el-tag size="small" type="info">关键词：{{ searchKeyword || '—' }}</el-tag>
        </div>
      </div>

      <el-alert class="import-tip" :closable="false" type="info" show-icon>
        <template #title>
          已用<b>英文标题</b>单次搜索，未按季集拆分请求。请点击选中一条最合适的字幕（合集包会自动按目标集数解包挑选），
          再点「使用选中字幕」下载并预览。
        </template>
      </el-alert>

      <el-empty v-if="!candidates.length" description="未搜索到候选字幕，可换用「手动上传字幕」"/>

      <SubtitleCandidateTable
          v-else
          :candidates="candidates"
          :selected-index="selectedIndex"
          @select="selectCandidate"
      />
    </section>

    <section v-if="step === 'preview' || step === 'done'" class="import-result">
      <div class="section-title">
        <h3>{{ step === 'preview' ? '请确认导入内容' : '导入结果' }}</h3>
        <div class="result-summary">
          <el-tag size="small" type="success">
            {{ step === 'preview' ? '可导入' : '成功' }} {{ step === 'preview' ? importableCount : (importResult?.success || 0) }}
          </el-tag>
          <el-tag v-if="failedCount" size="small" type="danger">
            {{ step === 'preview' ? '未命中' : '失败' }} {{ failedCount }}
          </el-tag>
        </div>
      </div>

      <el-alert v-if="step === 'preview'" class="import-tip" :closable="false" type="warning" show-icon>
        <template #title>
          以下为<b>将要写入</b>的变更，确认后才会真正写入；同名文件覆盖前会自动备份到视频同目录的 <b>sub_bak/</b>。
        </template>
      </el-alert>

      <SubtitleImportPreviewTable :items="displayItems"/>
    </section>

    <template #footer>
      <el-button v-if="step === 'preview'" @click="backFromPreview">返回</el-button>
      <el-button v-else @click="dialogVisible = false">{{ step === 'done' ? '完成' : '取消' }}</el-button>

      <el-button v-if="step === 'form'" :disabled="!canPreview" :loading="previewing" type="primary"
                 @click="onPrimary">
        {{ mode === 'upload' ? '预览匹配结果' : '搜索候选字幕' }}
      </el-button>
      <el-button v-else-if="step === 'select'" :disabled="selectedIndex < 0" :loading="previewing" type="primary"
                 @click="onPrimary">
        使用选中字幕
      </el-button>
      <el-button v-else-if="step === 'preview'" :disabled="!importableCount" :loading="importing" type="primary"
                 @click="doConfirm">
        确认导入（{{ importableCount }}）
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import SubtitleImportForm from "./SubtitleImportForm.vue";
import SubtitleCandidateTable from "./SubtitleCandidateTable.vue";
import SubtitleImportPreviewTable from "./SubtitleImportPreviewTable.vue";
import {useSubtitleImport} from "./useSubtitleImport.js";

const emit = defineEmits(['imported'])

const {
  step,
  dialogVisible,
  mode,
  aniId,
  aniOptions,
  pendingFiles,
  dragOver,
  previewing,
  importing,
  searchKeyword,
  candidates,
  selectedIndex,
  importResult,
  pendingBytes,
  selectedAni,
  displayItems,
  importableCount,
  failedCount,
  canPreview,
  openDialog,
  resetDialog,
  addFiles,
  removePending,
  clearPending,
  onPrimary,
  selectCandidate,
  doConfirm,
  backFromPreview
} = useSubtitleImport(() => emit('imported'))

defineExpose({
  show: openDialog
})
</script>

<style scoped src="./subtitle-import.css"></style>
