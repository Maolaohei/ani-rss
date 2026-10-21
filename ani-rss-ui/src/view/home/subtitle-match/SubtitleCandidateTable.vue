<template>
  <el-table :data="candidates" class="import-result-table" highlight-current-row max-height="320"
            size="small" @row-click="handleRowClick">
    <el-table-column width="44">
      <template #default="{row}">
        <el-icon v-if="row.index === selectedIndex" color="var(--el-color-primary)">
          <Select/>
        </el-icon>
      </template>
    </el-table-column>
    <el-table-column label="字幕名" min-width="300" prop="title" show-overflow-tooltip>
      <template #default="{row}">
        <span v-if="row.title">{{ row.title }}</span>
        <el-text v-else type="info">（无标题）</el-text>
      </template>
    </el-table-column>
    <el-table-column label="语言" width="80">
      <template #default="{row}">
        <span v-if="row.lang">{{ row.lang }}</span>
        <el-text v-else type="info">—</el-text>
      </template>
    </el-table-column>
    <el-table-column label="类型" width="90">
      <template #default="{row}">
        <el-tag :type="row.archive ? 'warning' : 'success'" size="small">
          {{ row.archive ? '合集包' : '单文件' }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="文件数" width="90">
      <template #default="{row}">
        <span v-if="row.fileCount >= 0">{{ row.fileCount }}</span>
        <el-text v-else type="info">需解析</el-text>
      </template>
    </el-table-column>
  </el-table>
</template>

<script setup>
defineProps({
  candidates: {type: Array, default: () => []},
  selectedIndex: {type: Number, default: -1}
})

const emit = defineEmits(['select'])

const handleRowClick = row => {
  emit('select', row)
}
</script>

<style scoped src="./subtitle-import.css"></style>
