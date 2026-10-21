<template>
  <div v-if="visible" class="failed-queue">
    <div class="residual-preview-head">
      <span class="residual-preview-title">失败队列 {{ count }}</span>
      <div class="failed-queue-actions">
        <el-button size="small" bg text :loading="loading" @click="$emit('load')">加载明细</el-button>
        <el-button size="small" bg text type="danger" :loading="clearing" @click="$emit('clear')">清空</el-button>
      </div>
    </div>
    <div v-if="!items.length" class="muted">点击刷新加载明细</div>
    <div v-for="row in items" :key="row.id" class="residual-row">
      <div class="residual-row-main">
        <el-tag size="small" type="danger">{{ row.errorCode || 'FAIL' }}</el-tag>
        <span class="residual-name" :title="row.reName || row.title">{{ row.reName || row.title || row.id }}</span>
        <el-button size="small" bg text type="primary" :loading="actingId === row.id" @click="$emit('retry', row)">重试</el-button>
        <el-button size="small" bg text @click="$emit('remove', row)">移除</el-button>
      </div>
      <div class="residual-row-meta">
        <span>{{ row.message || '-' }}</span>
        <span v-if="row.suggestion">{{ row.suggestion }}</span>
        <span v-if="row.attempts">×{{ row.attempts }}</span>
        <span>{{ formatTime(row.failedAt) }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import {formatTime} from "./format.js";

defineProps({
  visible: {type: Boolean, default: false},
  count: {type: Number, default: 0},
  items: {type: Array, default: () => []},
  loading: {type: Boolean, default: false},
  clearing: {type: Boolean, default: false},
  actingId: {type: String, default: ''}
})

defineEmits(['load', 'clear', 'retry', 'remove'])
</script>

<style scoped src="./task-manager.css"></style>
