import {computed, ref} from "vue";
import {ElMessage, ElMessageBox} from "element-plus";
import * as http from "@/js/http.js";
import {MAX_FILE_BYTES, SUBTITLE_EXT} from "./format.js";

export function useSubtitleImport(onImported = () => {}) {
  /** 当前步骤：form 填写 → (射手网)select 选候选 → preview 二次确认 → done 结果 */
  const step = ref('form')
  const dialogVisible = ref(false)
  const mode = ref('upload')
  const aniId = ref('')
  const aniOptions = ref([])
  const pendingFiles = ref([])
  const dragOver = ref(false)
  const previewing = ref(false)
  const importing = ref(false)
  /** 预览得到的待写入清单（改名前 / 改名后 / 对应的视频） */
  const planItems = ref([])
  /** 射手网预览计划 id，确认导入时回传给后端消费 */
  const planId = ref('')
  /** 射手网搜索结果 id（选中候选后生成计划时回传） */
  const searchId = ref('')
  /** 射手网实际使用的搜索关键词（通常是英文标题），用于向用户说明搜的是什么 */
  const searchKeyword = ref('')
  /** 射手网候选条目列表 */
  const candidates = ref([])
  /** 用户选中的候选下标，-1 表示未选 */
  const selectedIndex = ref(-1)
  const importResult = ref(null)

  const pendingBytes = computed(() => pendingFiles.value.reduce((sum, f) => sum + (f.size || 0), 0))
  const selectedAni = computed(() => aniOptions.value.find(a => a.id === aniId.value) || null)
  const displayItems = computed(() => step.value === 'preview' ? planItems.value : (importResult.value?.items || []))
  const importableCount = computed(() => planItems.value.filter(item => item.status === '已匹配').length)
  const failedCount = computed(() => step.value === 'preview'
      ? planItems.value.filter(item => item.status !== '已匹配').length
      : (importResult.value?.failed || 0))
  const canPreview = computed(() => !!aniId.value && (mode.value === 'assrt' || pendingFiles.value.length > 0))

  const openDialog = async () => {
    dialogVisible.value = true
    step.value = 'form'
    mode.value = 'upload'
    resetSelection()
    importResult.value = null
    if (!aniOptions.value.length) {
      await loadAniOptions()
    }
  }

  const resetSelection = () => {
    planItems.value = []
    planId.value = ''
    searchId.value = ''
    searchKeyword.value = ''
    candidates.value = []
    selectedIndex.value = -1
  }

  const resetDialog = () => {
    step.value = 'form'
    pendingFiles.value = []
    resetSelection()
    importResult.value = null
    dragOver.value = false
  }

  const loadAniOptions = async () => {
    try {
      const res = await http.listAni()
      aniOptions.value = (res?.data?.weekList || []).flatMap(w => w.items || [])
    } catch (e) {
      aniOptions.value = []
    }
  }

  /**
   * 统一收口：扩展名白名单 + 单文件大小 + 去重（同名同大小视为同一文件）
   */
  const addFiles = (fileList) => {
    const incoming = Array.from(fileList || [])
    if (!incoming.length) {
      return
    }
    const existing = new Set(pendingFiles.value.map(f => `${f.name}#${f.size}`))
    let rejected = 0
    for (const file of incoming) {
      const ext = (file.name.match(/\.([^.]+)$/)?.[1] || '').toLowerCase()
      if (!SUBTITLE_EXT.includes(ext)) {
        rejected++
        continue
      }
      if (file.size > MAX_FILE_BYTES) {
        ElMessage.warning(`${file.name} 超过 20MiB，已跳过`)
        continue
      }
      const key = `${file.name}#${file.size}`
      if (existing.has(key)) {
        continue
      }
      existing.add(key)
      pendingFiles.value.push(file)
    }
    if (rejected) {
      ElMessage.warning(`已忽略 ${rejected} 个非字幕文件`)
    }
  }

  const removePending = (index) => {
    pendingFiles.value.splice(index, 1)
  }

  const clearPending = () => {
    pendingFiles.value = []
  }

  const buildFormData = () => {
    const formData = new FormData()
    formData.append('aniId', aniId.value)
    pendingFiles.value.forEach(file => formData.append('files', file))
    return formData
  }

  /**
   * 主按钮统一入口，按「当前模式 + 当前步骤」分发：
   * - 上传模式：预览匹配结果
   * - 射手网模式：form 步 → 搜索候选；select 步 → 下载选中候选并预览
   */
  const onPrimary = async () => {
    if (mode.value === 'upload') {
      await doPreviewUpload()
    } else if (step.value === 'form') {
      await doSearch()
    } else {
      await doFetchSelected()
    }
  }

  /**
   * 上传模式第一步：只做匹配预览（不写盘），把「改名前 / 改名后 / 对应的视频」交给用户确认。
   */
  const doPreviewUpload = async () => {
    if (!aniId.value) {
      ElMessage.warning('请先选择字幕对应的订阅')
      return
    }
    if (!pendingFiles.value.length) {
      ElMessage.warning('请先选择字幕文件')
      return
    }
    previewing.value = true
    resetSelection()
    importResult.value = null
    try {
      const res = await http.subtitleImportPreview(buildFormData())
      if (res?.code !== 200) {
        ElMessage.error(res?.message || '预览失败')
        return
      }
      planItems.value = res.data?.items || []
      if (!planItems.value.length) {
        ElMessage.warning('没有解析出可导入的字幕')
        return
      }
      step.value = 'preview'
    } catch (e) {
      ElMessage.error(e?.message || '预览失败')
    } finally {
      previewing.value = false
    }
  }

  /**
   * 射手网第一步：以英文标题<b>单次</b>搜索，返回候选供用户挑选（不下载、不写盘）。
   */
  const doSearch = async () => {
    if (!aniId.value) {
      ElMessage.warning('请先选择字幕对应的订阅')
      return
    }
    previewing.value = true
    resetSelection()
    importResult.value = null
    try {
      const res = await http.subtitleAssrtSearch(aniId.value)
      if (res?.code !== 200) {
        ElMessage.error(res?.message || '搜索失败')
        return
      }
      candidates.value = res.data?.candidates || []
      searchId.value = res.data?.searchId || ''
      searchKeyword.value = res.data?.keyword || ''
      if (!candidates.value.length) {
        ElMessage.warning(`未搜索到候选字幕（关键词：${searchKeyword.value || '未知'}），可换用「手动上传字幕」`)
        return
      }
      step.value = 'select'
    } catch (e) {
      ElMessage.error(e?.message || '搜索失败')
    } finally {
      previewing.value = false
    }
  }

  /**
   * 射手网第二步：下载用户选中的候选并生成写入预览（不写盘）。
   */
  const doFetchSelected = async () => {
    if (selectedIndex.value < 0) {
      ElMessage.warning('请先选中一条候选字幕')
      return
    }
    previewing.value = true
    planItems.value = []
    planId.value = ''
    importResult.value = null
    try {
      const res = await http.subtitleFetchPreview(aniId.value, searchId.value, selectedIndex.value)
      if (res?.code !== 200) {
        ElMessage.error(res?.message || '获取字幕失败')
        return
      }
      planItems.value = res.data?.items || []
      planId.value = res.data?.planId || ''
      if (!planItems.value.length) {
        ElMessage.warning('该订阅没有缺失字幕的视频')
        return
      }
      step.value = 'preview'
    } catch (e) {
      ElMessage.error(e?.message || '获取字幕失败')
    } finally {
      previewing.value = false
    }
  }

  /** 点击候选行即选中 */
  const selectCandidate = (row) => {
    selectedIndex.value = row.index
  }

  /**
   * 第二步：用户确认后才真正写入。
   * 先弹一次确认框（二次确认），再执行写入，避免误点直接落盘。
   */
  const doConfirm = async () => {
    const source = mode.value === 'assrt' ? '射手网' : '本地上传'
    try {
      await ElMessageBox.confirm(
          `即将写入 ${importableCount.value} 个字幕文件（来源：${source}）。覆盖同名文件前会自动备份到视频同目录的 sub_bak/，确认继续？`,
          '确认导入字幕',
          {type: 'warning', confirmButtonText: '确认导入', cancelButtonText: '再检查一下'}
      )
    } catch (e) {
      return
    }

    importing.value = true
    try {
      let result
      if (mode.value === 'upload') {
        const res = await http.subtitleImport(buildFormData())
        if (res?.code !== 200) {
          ElMessage.error(res?.message || '导入失败')
          return
        }
        result = res.data
      } else {
        const res = await http.subtitleFetch(planId.value)
        result = res.data
      }
      importResult.value = result || {items: [], success: 0, failed: 0}
      step.value = 'done'
      const success = importResult.value.success || 0
      const failed = importResult.value.failed || 0
      if (success && !failed) {
        ElMessage.success(`导入完成，成功 ${success} 个`)
      } else if (success) {
        ElMessage.warning(`导入完成：成功 ${success}，失败 ${failed}`)
      } else {
        ElMessage.error('未导入任何字幕，请查看下方说明')
      }
      if (mode.value === 'upload') {
        pendingFiles.value = []
      }
      onImported()
    } catch (e) {
      ElMessage.error(e?.message || '导入失败')
    } finally {
      importing.value = false
    }
  }

  /** 从预览返回：射手网模式回到候选选择（候选还在），上传模式回到表单 */
  const backFromPreview = () => {
    step.value = mode.value === 'assrt' && candidates.value.length ? 'select' : 'form'
    planItems.value = []
    planId.value = ''
    importResult.value = null
  }

  return {
    step,
    dialogVisible,
    mode,
    aniId,
    aniOptions,
    pendingFiles,
    dragOver,
    previewing,
    importing,
    planItems,
    planId,
    searchId,
    searchKeyword,
    candidates,
    selectedIndex,
    importResult,
    pendingBytes,
    selectedAni,
    displayItems,
    importableCount,
    failedCount,
    canPreview,
    openDialog,
    resetDialog,
    addFiles,
    removePending,
    clearPending,
    onPrimary,
    selectCandidate,
    doConfirm,
    backFromPreview
  }
}
