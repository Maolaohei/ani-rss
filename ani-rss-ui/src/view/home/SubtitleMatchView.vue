<template>
  <div class="match-page app-page-layout">
    <PageHeaderView title="字幕匹配" :subtitle="subtitle">
      <template #actions>
        <el-button icon="Files" type="primary" @click="openDialog">字幕管理</el-button>
        <el-button :loading="loading" icon="Refresh" @click="load">刷新</el-button>
        <el-button :disabled="!records.length" icon="Delete" @click="clearAll">清空</el-button>
      </template>
    </PageHeaderView>
    <div class="match-body app-page-content app-page-padding">
      <el-alert class="match-tip" :closable="false" type="info" show-icon>
        <template #title>
          字幕统一在这里手动处理：可<b>上传本地字幕</b>，或<b>从射手网获取字幕</b>。下载完成后不会自动抓取，
          且无论哪种方式，写入前都会弹窗展示「改名前 / 改名后 / 对应的视频」并二次确认，避免自动匹配到错误字幕。
          开启「字幕季数元数据解析」后，字幕番剧名未带 S1/S2 标记时会自动查 TMDB/Bangumi 与订阅对比推断季数，避免跨季误匹配。
        </template>
      </el-alert>

      <SubtitleRecordsPanel
          v-model:limit="limit"
          :records="records"
          :error="error"
          :loading="loading"
          @load="load"
      />
    </div>

    <SubtitleImportDialog ref="importDialogRef" @imported="load"/>
  </div>
</template>

<script setup>
import {ref} from "vue";
import PageHeaderView from "@/view/custom/PageHeaderView.vue";
import SubtitleRecordsPanel from "./subtitle-match/SubtitleRecordsPanel.vue";
import SubtitleImportDialog from "./subtitle-match/SubtitleImportDialog.vue";
import {useSubtitleRecords} from "./subtitle-match/useSubtitleRecords.js";

const {
  loading,
  error,
  records,
  limit,
  subtitle,
  load,
  clearAll
} = useSubtitleRecords()

const importDialogRef = ref()

const openDialog = () => {
  importDialogRef.value?.show()
}
</script>

<style scoped src="./subtitle-match/subtitle-match.css"></style>
