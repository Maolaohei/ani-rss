<template>
  <el-dialog v-model="dialogVisible" center title="封面">
    <div class="content">
      <div>
        <img
            :src="`${toApiFile(ani['cover'])}&t=${time}`"
            :alt="ani.title"
            class="cover"
            @error="onCoverError"
        />
      </div>
      <div style="width: 12px;">
      </div>
      <div style="flex: 1">
        <el-form @submit.prevent label-width="auto">
          <el-form-item label="URL">
            <div class="full-width">
              <div class="flex full-width">
                <el-input v-model:model-value="ani.image" placeholder="https://lain.bgm.tv/pic/cover/1234.jpg"/>
                <div class="spacer"/>
                <el-tooltip content="按新 URL 重新下载封面并刷新左侧预览" placement="top">
                  <el-button :disabled="!ani.image" :loading="reLoadIng" bg icon="Refresh" text @click="reLoad">
                    应用封面
                  </el-button>
                </el-tooltip>
              </div>
              <div v-if="!urlApplied && ani.image" style="margin-top: 8px;">
                <el-alert type="warning" :closable="false" show-icon
                          title="新 URL 尚未应用：点「应用封面」下载后才能生效，直接点「确定」会沿用旧封面。"/>
              </div>
              <div style="margin-top: 8px;">
                <el-upload
                    :action="`api/upload?s=${authorization}`"
                    :before-upload="beforeCoverUpload"
                    :on-success="onCoverUploadSuccess"
                    :on-error="onCoverUploadError"
                    :show-file-list="false"
                    class="upload-demo"
                    drag
                >
                  <el-icon class="el-icon--upload">
                    <upload-filled/>
                  </el-icon>
                  <div class="el-upload__text">
                    在这里拖放文件或<em>点击上传</em>
                  </div>
                  <template #tip>
                    <div class="el-upload__tip flex" style="justify-content: end;">
                      仅支持 jpg / png，且小于 1M
                    </div>
                  </template>
                </el-upload>
              </div>
            </div>
          </el-form-item>
        </el-form>
      </div>
    </div>
    <div class="flex" style="justify-content: end;">
      <el-button :loading="okLoading" bg icon="Check" text @click="ok">确定</el-button>
    </div>
  </el-dialog>
</template>
<script setup>
import {ref, watch} from "vue";
import {ElMessage} from "element-plus";
import {UploadFilled} from "@element-plus/icons-vue";
import {authorization, toApiFile} from "@/js/global.js";
import * as http from "@/js/http.js";

let reLoadIng = ref(false)
// 左侧预览是否已应用当前输入框里的 URL
let urlApplied = ref(true)
// 与当前预览对应的封面 URL；仅当 ani.cover 真的变化时才更新
let appliedImage = ''
let lastCover = null

let reLoad = () => {
  reLoadIng.value = true
  // 以当前输入框的 URL 重新下载封面
  http.refreshCover(ani.value)
      .then(res => {
        time.value = new Date().getTime()
        ani.value.cover = res.data
        urlApplied.value = true
      })
      .catch(e => {
        ElMessage.error(e?.message || '封面下载失败，请检查 URL 是否可访问')
      })
      .finally(() => {
        reLoadIng.value = false
      })
}

let onCoverError = () => {
  // 本地封面文件缺失（清理缓存/迁移目录）时不再是一张破图
  if (!ani.value?.cover) {
    return
  }
  ElMessage.error('封面文件加载失败，可点「应用封面」重新下载或重新上传')
}

let dialogVisible = ref(false)

let ani = ref({})
let time = ref()

let show = (newAni) => {
  time.value = new Date().getTime()
  ani.value = JSON.parse(JSON.stringify(newAni))
  appliedImage = ani.value.image
  lastCover = ani.value.cover
  urlApplied.value = true
  dialogVisible.value = true
}

let okLoading = ref(false)
let ok = () => {
  if (!urlApplied.value && ani.value.image) {
    // 避免"点了确定提示修改成功，但封面其实没换"
    ElMessage.warning('新封面 URL 尚未应用，请先点「应用封面」')
    return
  }
  okLoading.value = true
  http.setAni(false, ani.value)
      .then(res => {
        ElMessage.success(res.message)
        window.$reLoadList()
        dialogVisible.value = false
      })
      .catch(e => {
        ElMessage.error(e?.message || '保存失败，请稍后重试')
      })
      .finally(() => {
        okLoading.value = false
      })
}

const onCoverUploadSuccess = (res) => {
  if (res?.code && res.code !== 200) {
    ElMessage.error(res.message || '封面上传失败')
    return
  }
  ani.value.cover = res.data
  time.value = new Date().getTime()
  urlApplied.value = true
  ElMessage.success('封面上传成功')
}

const onCoverUploadError = (err) => {
  let message = '封面上传失败'
  try {
    let body = JSON.parse(err?.message)
    if (body?.message) {
      message = body.message
    }
  } catch (e) {
    // 非 JSON 响应（如 502），保留默认文案
  }
  ElMessage.error(message)
}

const beforeCoverUpload = (rawFile) => {
  if (!['image/jpeg', 'image/png'].includes(rawFile.type)) {
    ElMessage.error('封面仅支持 jpg / png 格式')
    return false
  }
  if (rawFile.size / 1024 / 1024 > 1) {
    ElMessage.error('封面文件不能超过 1M')
    return false
  }
  return true
}

watch(() => ani.value.image, (value) => {
  // URL 一改就认为"未应用"，需要用户显式点「应用封面」
  if (value !== appliedImage) {
    urlApplied.value = false
  }
})

watch(() => ani.value.cover, (cover) => {
  // 封面文件真的换了一个：认为当前输入框的 URL 已被应用
  if (lastCover !== null && cover !== lastCover) {
    appliedImage = ani.value.image
    urlApplied.value = true
  }
  lastCover = cover
}, {immediate: true})

defineExpose({show})
</script>

<style scoped>
.content {
  width: 100%;
  display: flex;
  justify-content: space-between;
  padding: 0 20px;
}

.cover {
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--el-border-radius-base);
  cursor: pointer;
  height: 260px;
  width: 180px;
}
</style>
