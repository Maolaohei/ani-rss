<template>
  <!-- 状态不可用时常驻告警：此前会把“接口失败”显示成“空闲” -->
  <el-alert
      v-if="statusError"
      class="status-error"
      type="error"
      :closable="false"
      show-icon
      :title="`任务状态获取失败：${statusError}`">
    <template #default>
      <div class="status-error-body">
        <span>{{ staleText }}</span>
        <el-button size="small" bg text type="primary" :loading="loading" @click="$emit('refresh')">重试</el-button>
      </div>
    </template>
  </el-alert>
  <div class="job-summary">
    <div class="job-row">
      <span class="job-label">总览</span>
      <el-tag :type="status.running ? 'warning' : 'info'">
        {{ status.running ? '运行中' : '空闲' }}
      </el-tag>
      <el-tag v-if="status.cancelRequested" type="danger" style="margin-left: 8px;">
        取消中
      </el-tag>
      <el-tag v-if="status.pending" type="success" style="margin-left: 8px;">
        有待执行
      </el-tag>
      <el-tag v-if="status.openListBusy" type="warning" style="margin-left: 8px;">
        OpenList 处理中
      </el-tag>
    </div>
    <div class="job-row">
      <span class="job-label">消息</span>
      <span class="job-value">{{ status.message || '-' }}</span>
    </div>
    <div v-if="status.running && status.subscriptionTotal > 0" class="job-row">
      <span class="job-label">订阅进度</span>
      <span class="job-value progress-summary">
        已处理 {{ status.subscriptionCompleted }}/{{ status.subscriptionTotal }}
        <span>运行中 {{ status.subscriptionActive }}</span>
        <span v-if="status.subscriptionFailed > 0" class="progress-failed">失败 {{ status.subscriptionFailed }}</span>
        <span v-if="status.totalBatch > 1" class="progress-batch">
          批次 {{ status.currentBatch || 1 }}/{{ status.totalBatch }}
          <template v-if="nextBatchCountdownText">· {{ nextBatchCountdownText }}</template>
        </span>
      </span>
    </div>
    <!-- 静默超时强制开轮：必须明示，否则用户会把"跳过订阅/结果偏差"当成 bug -->
    <div v-if="status.running && status.quiescentForced" class="job-row">
      <span class="job-label">静默窗口</span>
      <span class="job-value">
        <el-tooltip content="等待后处理（改名/上传/离线归位）收尾超时，本轮为强制开启。结果可信度低于平时，建议查看是否有订阅长期卡在收尾阶段。"
                    placement="top">
          <el-tag type="warning" size="small">超时强制开轮</el-tag>
        </el-tooltip>
        <span v-if="status.quiescentSkipped > 0" class="progress-batch">
          · 跳过未收尾订阅 {{ status.quiescentSkipped }}
        </span>
      </span>
    </div>
    <!-- 本轮本地状态分布：回答"这一轮为什么什么都没下" -->
    <div v-if="status.running && status.roundLocalState" class="job-row">
      <span class="job-label">本地状态</span>
      <span class="job-value progress-summary">
        <span>已存在 {{ status.roundLocalState.exists }}</span>
        <span>无法确认 {{ status.roundLocalState.unknown }}</span>
        <span>已下发 {{ status.roundLocalState.absent }}</span>
      </span>
    </div>
    <!-- 存疑成因：只说"有 N 条无法确认"用户不知道该做什么，分开列才有行动价值 -->
    <div v-if="status.running && unknownReasonText" class="job-row">
      <span class="job-label">存疑原因</span>
      <span class="job-value progress-summary">
        <el-tooltip placement="top" :show-after="300">
          <template #content>
            <div>熔断冷却中：网盘连续失败触发了接口冷却，本轮一个请求都没发，等冷却结束即可自愈</div>
            <div>列举失败：网盘查询失败（超时 / 连接被重置 / 5xx），确认网盘可用后会自行恢复</div>
            <div>超预算：本轮网盘 API 预算用尽，可调大「单轮预算」或减少订阅</div>
            <div>索引不完整：网盘目录文件数超过「列举上限」被截断，可调大该上限</div>
            <div>等待改名：下载器里已有任务但尚未改名落地，稍后会自行收敛</div>
            <div>缺席未确认：网盘目录列举自带缓存（可达 1 小时），刚下完可能列不到；约 1 小时后复核确认才会清理记录重下</div>
          </template>
          <span class="muted">为什么无法确认？</span>
        </el-tooltip>
        <span v-if="status.unknownReasons.cooldown">冷却中 {{ status.unknownReasons.cooldown }}</span>
        <span v-if="status.unknownReasons.verifyFailed">列举失败 {{ status.unknownReasons.verifyFailed }}</span>
        <span v-if="status.unknownReasons.budgetExhausted">超预算 {{ status.unknownReasons.budgetExhausted }}</span>
        <span v-if="status.unknownReasons.indexIncomplete">索引不完整 {{ status.unknownReasons.indexIncomplete }}</span>
        <span v-if="status.unknownReasons.downloading">等待改名 {{ status.unknownReasons.downloading }}</span>
        <span v-if="status.unknownReasons.absenceUnconfirmed">缺席未确认 {{ status.unknownReasons.absenceUnconfirmed }}</span>
      </span>
    </div>
    <div class="job-row">
      <span class="job-label">已处理时间</span>
      <span>{{ lastProcessedText }}</span>
    </div>
  </div>
</template>

<script setup>
/**
 * 任务总览区：状态告警、运行总览、订阅进度、本地状态分布、存疑原因。
 * 文案与轮询解耦，只接收父组件算好的展示文本。
 */
defineProps({
  status: {type: Object, required: true},
  statusError: {type: String, default: ''},
  staleText: {type: String, default: ''},
  loading: {type: Boolean, default: false},
  lastProcessedText: {type: String, default: '-'},
  nextBatchCountdownText: {type: String, default: ''},
  unknownReasonText: {type: String, default: ''}
})

defineEmits(['refresh'])
</script>

<style scoped src="./task-manager.css"></style>
