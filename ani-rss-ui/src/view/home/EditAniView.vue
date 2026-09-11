<template>
  <el-dialog v-model="downloadPathDialogVisible" align-center center width="300"
             :close-on-click-modal="false"
             :close-on-press-escape="false"
             :show-close="false"
             title="移动文件">
    <div>
      <strong>
        检测到修改后的下载位置发生了改动，是否将已下载文件移动到新的位置？
      </strong>
      <br>
      <el-text class="mx-1" size="small">
        {{ downloadPath }}
      </el-text>
      <br>
      <el-text class="mx-1" size="small" type="warning">
        选择「移动」后会移动整个文件夹，且不会再弹出第二次确认。
      </el-text>
    </div>
    <div class="action">
      <el-button icon="Check" text bg type="danger" :loading="movingLoading" @click="confirmMove(true)">移动
      </el-button>
      <el-button icon="Close" bg text :disabled="movingLoading" @click="confirmMove(false)">不移动
      </el-button>
    </div>
  </el-dialog>
  <el-dialog v-model="dialogVisible" title="修改订阅" center v-if="dialogVisible"
             :before-close="beforeClose">
    <AniView v-model:ani="ani" @callback="editChange"/>
  </el-dialog>
</template>

<script setup>

import {ref, watch} from "vue";
import {ElMessage, ElMessageBox} from "element-plus";
import AniView from "./AniView.vue";
import {aniData} from "@/js/ani.js";
import * as http from '@/js/http.js'


const dialogVisible = ref(false)
const downloadPathDialogVisible = ref(false)

const ani = ref(aniData)

let move = ref(false)
let downloadPath = ref('')
let callback = ref(() => {
})

// 打开编辑时的主RSS快照, 保存后对比判断主源是否变化
let originalUrl = ref('')

// 订阅表单是否存在未保存改动（就地修改 props.ani，由这里统一对比快照）
let aniDirty = ref(false)
let aniSnapshot = ''
const snapshotOf = v => JSON.stringify(v)

watch(ani, v => {
  if (!dialogVisible.value) {
    return
  }
  aniDirty.value = snapshotOf(v) !== aniSnapshot
}, {deep: true})

// 是否正在提交（提交中不允许关闭弹窗）
let saving = ref(false)
// "移动文件"弹窗的按钮 loading
let movingLoading = ref(false)

const editChange = async (fun) => {
  callback.value = fun
  saving.value = true
  try {
    let req = await http.downloadPath(ani.value)
    downloadPath.value = req.data.downloadPath
    if (req.data.change) {
      downloadPathDialogVisible.value = true
      return
    }
  } catch (e) {
    // 拉取下载位置失败时此前会直接把异常抛出，导致外层「确定」永远停在 loading
    ElMessage.error(e?.message || '获取下载位置失败，请稍后重试')
    saving.value = false
    fun?.()
    return
  }
  editAni()
}

const editAni = () => {
  saving.value = true
  http.setAni(move.value, ani.value)
      .then(res => {
        ElMessage.success(res.message)
        // 保存成功：清掉未保存标记，避免关闭时再次确认
        aniDirty.value = false
        window.$reLoadList()
        dialogVisible.value = false
        // 主RSS变化(如备用互换)时自动触发一次刷新, 拉取新主源集数
        let url = ani.value.url
        if (url && url !== originalUrl.value && ani.value.enable) {
          http.refreshAni(ani.value)
              .then(r => ElMessage.info(r.message || '已自动刷新新主RSS'))
              .catch(() => {
              })
        }
      })
      .catch(() => {
        // 错误提示由 api.js 统一弹出
      })
      .finally(() => {
        callback.value()
        saving.value = false
        movingLoading.value = false
      })
}

/**
 * 「移动文件」弹窗只有这两个显式出口，不再叠加第二次确认弹窗
 */
const confirmMove = (value) => {
  move.value = value
  movingLoading.value = true
  downloadPathDialogVisible.value = false
  editAni()
}

/**
 * 关闭前拦截：保存中禁止关闭；有未保存改动时二次确认
 */
const beforeClose = (done) => {
  if (saving.value) {
    ElMessage.warning('正在保存，请稍候')
    return
  }
  if (!aniDirty.value) {
    done()
    return
  }
  ElMessageBox.confirm('订阅有未保存的修改，关闭将丢弃这些修改，是否继续？', '未保存的修改', {
    confirmButtonText: '丢弃并关闭',
    confirmButtonClass: 'is-text is-has-bg el-button--danger',
    cancelButtonText: '继续编辑',
    cancelButtonClass: 'is-text is-has-bg',
    type: 'warning',
  })
      .then(() => done())
      .catch(() => {
      })
}

const show = (item) => {
  ani.value = JSON.parse(JSON.stringify(item))
  ani.value.showDownlaod = true
  move.value = false
  movingLoading.value = false
  saving.value = false
  aniDirty.value = false
  aniSnapshot = snapshotOf(ani.value)
  originalUrl.value = item.url
  dialogVisible.value = true
}

defineExpose({
  show
})
</script>

<style scoped>
.action {
  width: 100%;
  display: flex;
  justify-content: end;
  margin-top: 8px;
}
</style>
