<template>
  <div class="task-list">
    <div v-for="item in items" :key="item.id" class="task-card">
      <div class="task-head">
        <div class="task-title-wrap">
          <el-tag size="small" :type="statusTagType(item.status)">{{ statusText(item.status) }}</el-tag>
          <span class="task-title">{{ item.title || kindText(item.kind) }}</span>
        </div>
        <el-button
            type="danger"
            bg
            text
            size="small"
            :disabled="!item.cancellable || item.status === 'canceling'"
            :loading="cancelingId === item.id"
            @click="$emit('cancel', item)"
        >
          取消
        </el-button>
      </div>
      <div class="task-meta">
        <span>类型：{{ kindText(item.kind) }}</span>
        <span>来源：{{ sourceText(item.source) }}</span>
        <span>范围：{{ scopeText(item.scope) }}</span>
      </div>
      <div class="task-message">{{ item.message || '-' }}</div>
      <div v-if="item.progress != null" class="task-progress">
        <el-progress :percentage="Number(item.progress) || 0" :stroke-width="10"/>
        <span v-if="item.etaMs != null" class="muted eta">ETA {{ formatDuration(item.etaMs, true) }}</span>
      </div>
      <div class="task-grid">
        <div><span class="muted">已运行</span> {{ formatDuration(item.elapsedMs, item.status === 'running' || item.status === 'busy' || item.status === 'canceling') }}</div>
        <div><span class="muted">已处理时间</span> {{ formatTime(item.processedAt) }}</div>
        <div v-if="item.durationMs != null"><span class="muted">耗时</span> {{ formatDuration(item.durationMs, true) }}</div>
        <div v-if="item.hash" class="mono"><span class="muted">Hash</span> {{ item.hash }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import {formatDuration, formatTime} from "./format.js";

defineProps({
  items: {type: Array, default: () => []},
  cancelingId: {type: String, default: ''},
  statusTagType: {type: Function, required: true},
  statusText: {type: Function, required: true},
  kindText: {type: Function, required: true},
  sourceText: {type: Function, required: true},
  scopeText: {type: Function, required: true}
})

defineEmits(['cancel'])
</script>

<style scoped>
.task-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.task-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px;
  background: var(--el-fill-color-light);
}

.task-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.task-title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.task-title {
  font-weight: 600;
  word-break: break-all;
}

.task-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-bottom: 6px;
}

.task-message {
  margin-bottom: 8px;
  word-break: break-all;
}

.task-progress {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.task-progress .el-progress {
  flex: 1;
}

.eta {
  white-space: nowrap;
  font-size: 12px;
}

.task-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 12px;
  font-size: 13px;
}

.muted {
  color: var(--el-text-color-secondary);
  margin-right: 6px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
  font-size: 12px;
  word-break: break-all;
}

@media (max-width: 720px) {
  .task-grid {
    grid-template-columns: 1fr;
  }
}
</style>
