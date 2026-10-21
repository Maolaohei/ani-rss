<template>
  <section class="match-section">
    <div class="section-title">
      <h3>匹配记录</h3>
      <el-select v-model="limit" class="filter-limit" @change="$emit('load')">
        <el-option label="最近 50 条" :value="50"/>
        <el-option label="最近 100 条" :value="100"/>
        <el-option label="最近 200 条" :value="200"/>
      </el-select>
    </div>

    <el-alert v-if="error" :closable="false" :title="error" type="error" show-icon/>
    <el-empty v-else-if="!loading && !records.length" description="暂无匹配记录"/>
    <el-table v-else :data="records" class="match-table" size="small">
      <el-table-column label="改名前" min-width="220" prop="originalName" show-overflow-tooltip>
        <template #default="{row}">
          <span v-if="row.originalName">{{ row.originalName }}</span>
          <el-text v-else type="info">—</el-text>
        </template>
      </el-table-column>
      <el-table-column label="改名后" min-width="220" prop="renamedName" show-overflow-tooltip>
        <template #default="{row}">
          <span v-if="row.renamedName">{{ row.renamedName }}</span>
          <el-text v-else type="info">—</el-text>
        </template>
      </el-table-column>
      <el-table-column label="对应的视频" min-width="200" prop="videoName" show-overflow-tooltip>
        <template #default="{row}">
          <span v-if="row.videoName">{{ row.videoName }}</span>
          <el-text v-else type="info">—</el-text>
        </template>
      </el-table-column>
      <el-table-column label="季" width="70">
        <template #default="{row}">
          <el-tag v-if="row.season != null" size="small" type="primary">S{{ row.season }}</el-tag>
          <el-text v-else type="info">—</el-text>
        </template>
      </el-table-column>
      <el-table-column label="语言" width="80" prop="lang">
        <template #default="{row}">
          <span v-if="row.lang">{{ row.lang }}</span>
          <el-text v-else type="info">—</el-text>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{row}">
          <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="170">
        <template #default="{row}">{{ formatTime(row.time) }}</template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup>
import {formatTime} from "@/js/format.js";
import {statusLabel, statusType} from "./format.js";

defineProps({
  records: {type: Array, default: () => []},
  error: {type: String, default: ''},
  loading: {type: Boolean, default: false}
})

defineEmits(['load'])

const limit = defineModel('limit', {type: Number, default: 100})
</script>

<style scoped src="./subtitle-match.css"></style>
