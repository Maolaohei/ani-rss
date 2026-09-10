<template>
  <template v-if="notificationConfig['notificationType'] === 'EMBY_REFRESH'">
    <el-form-item label="Emby 地址">
      <el-input v-model="notificationConfig['embyHost']" placeholder="http://x.x.x.x:8096"/>
    </el-form-item>
    <el-form-item label="Emby 密钥">
      <el-input v-model="notificationConfig['embyApiKey']" show-password
                placeholder="Emby → 设置 → API 密钥"/>
    </el-form-item>
    <el-form-item label="媒体库">
      <div class="full-width">
        <el-checkbox-group v-model="notificationConfig['embyRefreshViewIds']">
          <el-checkbox
              v-for="view in views"
              :key="view.id"
              :label="view.name"
              :value="view.id"/>
        </el-checkbox-group>
        <div class="emby-views-actions">
          <el-button :loading="getEmbyViewsLoading" bg icon="Refresh" text @click="getEmbyViews">
            拉取媒体库列表
          </el-button>
          <el-text class="mx-1" size="small" type="info">
            填好地址与密钥后点这里获取，勾选需要在新集下载后刷新的媒体库
          </el-text>
        </div>
      </div>
    </el-form-item>
    <el-form-item label="延迟刷新">
      <div>
        <el-input-number v-model="notificationConfig['embyDelayed']"
                         class="notification-input-width"
                         :min="0">
          <template #suffix>
            <span>秒</span>
          </template>
        </el-input-number>
        <br>
        <el-text class="mx-1" size="small" type="info">
          下载完成后等待这么久再通知 Emby 刷新，避免文件还没落盘
        </el-text>
      </div>
    </el-form-item>
  </template>
</template>

<script setup>
import {onMounted, ref} from "vue";
import * as http from "@/js/http.js";

const views = ref([])

const getEmbyViewsLoading = ref(false)

const getEmbyViews = () => {
  getEmbyViewsLoading.value = true
  http.getEmbyViews(props.notificationConfig)
      .then(res => {
        views.value = res.data
      })
      .finally(() => {
        getEmbyViewsLoading.value = false
      })
}

onMounted(() => {
  if (props.notificationConfig['embyHost'] && props.notificationConfig['embyApiKey']) {
    getEmbyViews()
  }
})

let props = defineProps(['notificationConfig', 'config'])
</script>

<style scoped>
.emby-views-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 4px;
}
</style>
