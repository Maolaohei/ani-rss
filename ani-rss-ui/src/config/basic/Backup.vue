<template>
  <input id="backup-file" hidden="hidden" type="file" accept=".zip" @change="changeFile">
  <div class="content flex">
    <el-button bg @click="importConfig" icon="Upload">导入设置</el-button>
    <el-button bg @click="exportConfig" icon="Download">导出设置</el-button>
  </div>
  <el-text class="mx-1 backup-tip" size="small">
    导出/导入的是<b>整套</b>配置（设置 + 订阅 + 下载记录），导出文件名带当天日期，可重复导出不会互相覆盖。
  </el-text>
  <el-text class="mx-1 backup-tip" size="small">
    自动备份由「基本设置 → 其他 → 自动备份配置」控制，结果打包为
    <code>config/backup/YYYY-MM-DD.zip</code>，只保留最近 N 天（N 即「保留天数」），过期自动清理。
  </el-text>
</template>
<script setup>
import {authorization} from "@/js/global.js";
import * as http from "@/js/http.js"
import {ElMessage, ElMessageBox} from "element-plus";
import {markRaw} from "vue";
import {WarnTriangleFilled} from "@element-plus/icons-vue";

let importConfig = () => {
  // 不用 dangerouslyUseHTMLString，避免任何插值被当 HTML 解析
  ElMessageBox({
    title: '警告',
    message: '将会覆盖掉现有的设置、订阅、下载记录，是否执意继续？',
    type: 'warning',
    icon: markRaw(WarnTriangleFilled),
    showCancelButton: true,
    confirmButtonText: '继续',
    confirmButtonClass: 'is-text is-has-bg el-button--danger',
    cancelButtonText: '取消',
    cancelButtonClass: 'is-text is-has-bg',
  })
      .then(() => {
        let element = document.querySelector('#backup-file');
        element.click();
      })
      .catch(() => {
      })
}

let changeFile = () => {
  let element = document.querySelector('#backup-file');
  const file = element?.files?.[0]
  if (!file) {
    return
  }
  http.importConfig(file)
      .then(res => {
        let {code, message} = res
        if (code !== 200) {
          ElMessage.error(message)
          return
        }
        ElMessage.success(`${message || '导入成功'}，正在重启并刷新页面`)
        setTimeout(() => {
          location.reload();
        }, 1000)
      })
      .catch(e => {
        ElMessage.error(e?.message || '导入失败：文件无法读取或服务正在重启，请稍后重试')
      })
      .finally(() => {
        // 允许用户重新选择同一个文件
        element.value = ''
      })
}

let exportConfig = () => {
  let element = document.createElement('a');
  element.href = `api/exportConfig?s=${authorization.value}`
  element.download = `ani-rss.${new Date().toISOString().slice(0, 10)}.zip`

  document.body.appendChild(element);

  element.click();

  document.body.removeChild(element);
}

let props = defineProps(['config'])
</script>
<style scoped>
.content {
  width: 100%;
  justify-content: center;
}

.backup-tip {
  display: block;
  margin-top: 8px;
}
</style>
