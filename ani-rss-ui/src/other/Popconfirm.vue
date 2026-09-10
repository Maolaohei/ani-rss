<template>
  <el-popconfirm
      :title="props.title"
      :width="props.width"
      :disabled="props.disabled"
      @confirm="emit('confirm')"
  >
    <template #reference>
      <slot name="reference"/>
    </template>
    <template #actions="{ confirm, cancel }">
      <div class="flex action">
        <div>
          <el-button size="small" @click="cancel" bg text icon="Close">取消</el-button>
        </div>
        <div>
          <el-button
              :type="props.type"
              size="small"
              @click="confirm"
              bg text
              icon="Check"
          >
            确定
          </el-button>
        </div>
      </div>
    </template>
  </el-popconfirm>
</template>

<script setup>
let props = defineProps({
  title: String,
  width: {
    type: Number,
    default: 160
  },
  type: {
    type: String,
    default: 'danger'
  },
  /**
   * 禁用确认气泡：调用方可在请求进行中置 true，避免重复触发
   * （el-popconfirm 只弹出气泡，不提供 pending 语义）
   */
  disabled: Boolean
})

const emit = defineEmits(['confirm'])
</script>

<style scoped>
.action {
  justify-content: space-between;
}
</style>
