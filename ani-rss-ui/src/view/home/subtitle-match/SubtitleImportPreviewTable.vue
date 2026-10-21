<template>
  <el-table :data="items" class="import-result-table" max-height="300" size="small">
    <el-table-column label="改名前" min-width="200" prop="originalName" show-overflow-tooltip>
      <template #default="{row}">
        <span v-if="row.originalName">{{ row.originalName }}</span>
        <el-text v-else type="info">—</el-text>
      </template>
    </el-table-column>
    <el-table-column label="改名后" min-width="200" show-overflow-tooltip>
      <template #default="{row}">
        <span v-if="row.renamedName">{{ row.renamedName }}</span>
        <el-text v-else type="info">—</el-text>
      </template>
    </el-table-column>
    <el-table-column label="对应的视频" min-width="180" prop="videoName" show-overflow-tooltip>
      <template #default="{row}">
        <span v-if="row.videoName">{{ row.videoName }}</span>
        <el-text v-else type="info">—</el-text>
      </template>
    </el-table-column>
    <el-table-column label="语言" width="70">
      <template #default="{row}">
        <span v-if="row.lang">{{ row.lang }}</span>
        <el-text v-else type="info">—</el-text>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="90">
      <template #default="{row}">
        <el-tag :type="row.status === '已匹配' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="说明" min-width="200" prop="reason" show-overflow-tooltip>
      <template #default="{row}">
        <span v-if="row.reason">{{ row.reason }}</span>
        <el-text v-else type="info">—</el-text>
      </template>
    </el-table-column>
  </el-table>
</template>

<script setup>
defineProps({
  items: {type: Array, default: () => []}
})
</script>

<style scoped src="./subtitle-import.css"></style>
