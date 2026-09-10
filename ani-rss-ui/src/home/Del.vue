<template>
  <el-dialog v-model="dialogVisible" align-center center width="420" title="删除订阅">
    <div class="del-body">
      <el-text v-if="aniList.length === 1" size="large">
        是否删除「{{ aniList[0].title }}」第{{ aniList[0].season }}季？
      </el-text>
      <el-text v-else size="large">
        是否删除以下 {{ aniList.length }} 个订阅？
      </el-text>

      <ul class="del-list">
        <li v-for="item in previewList" :key="item.id">
          {{ item.title }}（第{{ item.season }}季）
        </li>
        <li v-if="aniList.length > previewLimit" class="del-list-more">
          …等共 {{ aniList.length }} 个订阅
        </li>
      </ul>

      <el-checkbox v-model="deleteFiles" class="el-checkbox-danger" @change="onDeleteFilesChange">
        同时删除整个下载目录（含目录内其他文件，不可恢复）
      </el-checkbox>

      <div v-if="deleteFiles" class="del-paths" v-loading="pathLoading">
        <el-text size="small" type="info">将删除以下目录：</el-text>
        <ul class="del-list">
          <li v-for="p in pathList" :key="p" class="del-path-item">{{ p }}</li>
          <li v-if="!pathLoading && !pathList.length" class="del-list-more">
            未能获取下载目录，为避免误删已阻止执行
          </li>
        </ul>
      </div>
    </div>
    <div class="action">
      <el-button icon="Check" :loading="okLoading" :disabled="deleteFiles && pathLoading"
                 @click="delAni" text bg type="danger">确定
      </el-button>
      <el-button icon="Close" bg text @click="dialogVisible = false">取消</el-button>
    </div>
  </el-dialog>
</template>

<script setup>

import {computed, getCurrentInstance, h, markRaw, ref} from "vue";
import * as http from "@/js/http.js";
import {deleteAni} from "@/js/http.js";
import {ElMessage, ElMessageBox} from "element-plus";
import {Delete} from "@element-plus/icons-vue";

const dialogVisible = ref(false)

const aniList = ref([])

const previewLimit = 5

const previewList = computed(() => aniList.value.slice(0, previewLimit))

let okLoading = ref(false)
let deleteFiles = ref(false)

let pathLoading = ref(false)
let pathList = ref([])

/**
 * 删除文件时先解析每个订阅的真实下载目录，
 * 让用户在确认前看到"到底会删哪些目录"，而不是一句空路径。
 */
const resolvePaths = async () => {
  pathList.value = []
  pathLoading.value = true
  try {
    for (const ani of aniList.value) {
      try {
        const res = await http.downloadPath(ani)
        const path = res?.data?.['downloadPath']
        if (path) {
          pathList.value.push(path)
        }
      } catch (e) {
        // 单条失败不影响其它条目，最后统一提示
      }
    }
  } finally {
    pathLoading.value = false
  }
}

const onDeleteFilesChange = (value) => {
  if (value) {
    resolvePaths()
  } else {
    pathList.value = []
  }
}

const delAni = async () => {
  if (deleteFiles.value && pathLoading.value) {
    ElMessage.warning('正在获取下载目录，请稍候')
    return
  }
  if (deleteFiles.value && !pathList.value.length) {
    ElMessage.error('未能获取任何下载目录，已阻止删除以避免误删后续文件')
    return
  }

  okLoading.value = true
  let ids = aniList.value.map(it => it['id'])
  let action = () => deleteAni(deleteFiles.value, ids)
      .then(res => {
        ElMessage.success(res.message)
        if (instance.vnode.props.onCallback) {
          emit('callback')
        } else {
          window.$reLoadList()
        }
        dialogVisible.value = false
      })
      .finally(() => {
        okLoading.value = false
      });

  if (!deleteFiles.value) {
    await action()
    return
  }

  // 不用 dangerouslyUseHTMLString：标题/路径来自 RSS 与用户输入，直接拼 HTML 存在注入面
  ElMessageBox({
    title: '警告',
    type: 'warning',
    icon: markRaw(Delete),
    showCancelButton: true,
    confirmButtonText: '执意继续删除',
    confirmButtonClass: 'is-text is-has-bg el-button--danger',
    cancelButtonText: '取消',
    cancelButtonClass: 'is-text is-has-bg',
    message: () => h('div', {class: 'del-confirm'}, [
      h('p', {class: 'del-confirm-title'}, `将会删除整个下载目录，共 ${pathList.value.length} 个，是否执意继续？`),
      h('ul', {class: 'del-confirm-list'}, pathList.value.map(p => h('li', p)))
    ])
  })
      .then(action)
      .catch(() => {
      })
      .finally(() => {
        okLoading.value = false
      })
}

const show = (anis) => {
  if (!anis.length) {
    ElMessage.error('未选择订阅')
    return
  }

  aniList.value = JSON.parse(JSON.stringify(anis))
  deleteFiles.value = false
  pathList.value = []
  pathLoading.value = false
  dialogVisible.value = true
}

defineExpose({
  show
})

const instance = getCurrentInstance()

const emit = defineEmits(['callback'])
</script>

<style scoped>
.del-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.del-list {
  margin: 0;
  padding-left: 18px;
  max-height: 160px;
  overflow: auto;
  font-size: 13px;
  color: var(--el-text-color-regular);
  word-break: break-all;
}

.del-list-more {
  list-style: none;
  margin-left: -18px;
  color: var(--el-text-color-secondary);
}

.del-path-item {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

.action {
  width: 100%;
  display: flex;
  justify-content: end;
  margin-top: 8px;
}
</style>
