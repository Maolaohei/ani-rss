import {computed, onMounted, ref} from "vue";
import {ElMessage, ElMessageBox} from "element-plus";
import * as http from "@/js/http.js";

export function useSubtitleRecords() {
  const loading = ref(false)
  const error = ref('')
  const records = ref([])
  const limit = ref(100)

  const subtitle = computed(() => `${records.value.length} 条记录`)

  const load = async () => {
    loading.value = true
    error.value = ''
    try {
      const res = await http.subtitleMatchLog(limit.value)
      records.value = res.data || []
    } catch (e) {
      error.value = e?.message || '加载字幕匹配记录失败'
    } finally {
      loading.value = false
    }
  }

  const clearAll = async () => {
    try {
      await ElMessageBox.confirm('清空后无法恢复，确定继续？', '清空字幕匹配记录', {
        type: 'warning',
        confirmButtonText: '清空',
        cancelButtonText: '取消'
      })
    } catch (e) {
      return
    }
    try {
      const res = await http.subtitleMatchLogClear()
      ElMessage.success(res.message || '已清空')
      load()
    } catch (e) {
      // 错误提示由 api 层统一弹出
    }
  }

  onMounted(load)

  return {
    loading,
    error,
    records,
    limit,
    subtitle,
    load,
    clearAll
  }
}
