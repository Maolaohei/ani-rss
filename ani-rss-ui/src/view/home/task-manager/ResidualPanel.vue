<template>
  <div v-if="visible" class="residual-preview">
    <div class="residual-preview-head">
      <span class="residual-preview-title">{{ title }}</span>
      <span class="muted">共 {{ summary }}，展示 {{ items.length }} 条</span>
    </div>
    <div v-for="row in items" :key="row.id || row.name" class="residual-row">
      <div class="residual-row-main">
        <el-tag size="small" :type="tagType(row)">{{ kindText(row) }}</el-tag>
        <el-tag v-if="row.protectedCurrent" size="small" type="success" style="margin-left: 6px;">保护中</el-tag>
        <span class="residual-name" :title="row.name">{{ row.name || row.id || '-' }}</span>
      </div>
      <div class="residual-row-meta">
        <span>{{ secondaryText(row) }}</span>
        <span class="residual-action">{{ row.action || defaultAction(row) }}</span>
      </div>
      <div v-if="row.error" class="residual-error">{{ row.error }}</div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  visible: {type: Boolean, default: false},
  title: {type: String, default: ''},
  summary: {type: String, default: ''},
  items: {type: Array, default: () => []},
  tagType: {type: Function, required: true},
  kindText: {type: Function, required: true},
  secondaryText: {type: Function, default: () => ''},
  defaultAction: {type: Function, default: () => ''}
})
</script>

<style scoped src="./task-manager.css"></style>
