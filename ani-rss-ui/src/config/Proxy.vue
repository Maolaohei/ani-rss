<template>
  <el-form @submit.prevent label-width="auto">
    <el-form-item label="IP">
      <el-input v-model:model-value="props.config.proxyHost" :disabled="!props.config.proxy"
                placeholder="192.168.0.x"/>
    </el-form-item>
    <el-form-item label="端口">
      <el-input-number v-model:model-value="props.config.proxyPort" :disabled="!props.config.proxy" :min="1"
                       :max="65535"/>
    </el-form-item>
    <el-form-item label="用户名">
      <el-input v-model:model-value="props.config.proxyUsername" :disabled="!props.config.proxy"
                placeholder="可以为空">
        <template #prefix>
          <el-icon class="el-input__icon">
            <User/>
          </el-icon>
        </template>
      </el-input>
    </el-form-item>
    <el-form-item label="密码">
      <el-input v-model:model-value="props.config.proxyPassword" :disabled="!props.config.proxy"
                placeholder="可以为空" show-password>
        <template #prefix>
          <el-icon class="el-input__icon">
            <Key/>
          </el-icon>
        </template>
      </el-input>
    </el-form-item>
    <el-form-item label="代理列表">
      <el-input
          class="full-width"
          type="textarea"
          :autosize="{ minRows: 3, maxRows: 3}"
          v-model="props.config.proxyList"
          :disabled="!props.config.proxy"/>
    </el-form-item>
    <el-form-item label="启用">
      <div class="full-width">
        <el-switch v-model:model-value="props.config.proxy"/>
        <el-alert
            v-if="props.config.proxy && !(props.config.proxyHost || '').trim()"
            class="proxy-alert"
            type="error"
            show-icon
            :closable="false"
            title="已启用代理但 IP 为空，保存会被后端拒绝（代理参数不完整），请填写代理 IP 与端口"
        />
        <el-alert
            v-else-if="props.config.proxy && (!props.config.proxyPort || props.config.proxyPort < 1)"
            class="proxy-alert"
            type="error"
            show-icon
            :closable="false"
            title="代理端口无效，请填写 1-65535 之间的端口"
        />
      </div>
    </el-form-item>
    <el-form-item label="代理连通测试">
      <div class="auto-flex proxy-test-container">
        <div class="proxy-test-controls">
          <el-select v-model:model-value="url" class="proxy-test-select">
            <el-option :value="it" :label="it" :key="it" v-for="it in urls"/>
          </el-select>
          <div class="proxy-test-spacer"></div>
          <el-button bg text :loading="testLoading" @click="test" icon="Odometer">测试</el-button>
        </div>
        <el-alert
            v-if="status"
            class="proxy-alert"
            :type="testOk ? 'success' : 'error'"
            show-icon
            :closable="false"
            :title="testOk
              ? `通过代理访问成功：HTTP ${status}，耗时 ${time}ms`
              : `未能通过代理访问：HTTP ${status}，耗时 ${time}ms，请检查 IP/端口/账号或代理软件`"/>
      </div>
    </el-form-item>
  </el-form>
</template>

<script setup>
import {onMounted, ref} from "vue";
import {ElMessage} from "element-plus";
import {Key, User} from "@element-plus/icons-vue";
import {testProxy} from "@/js/http.js";
import {base64Encode} from "@/js/global.js";

let urls = ref([
  'https://mikanani.me',
  'https://nyaa.si',
  'https://acg.rip',
  'https://github.com',
  'https://www.google.com',
  'https://bgm.tv',
  'https://www.themoviedb.org'
])

let url = ref('')
let status = ref('')
let time = ref('')
let testOk = ref(false)

onMounted(() => {
  url.value = urls.value[0]
})

let testLoading = ref(false)

let test = () => {
  testLoading.value = true
  status.value = ''
  time.value = ''
  testOk.value = false
  // 测试用的是当前表单（可能尚未保存）的值
  testProxy(base64Encode(url.value), props.config)
      .then(res => {
        status.value = res.data.status
        time.value = res.data.time
        // 2xx/3xx 视为连通；4xx/5xx 与 0 都说明代理链路有问题
        testOk.value = Number(status.value) >= 200 && Number(status.value) < 400
      })
      .catch(e => {
        status.value = '失败'
        time.value = '-'
        testOk.value = false
        ElMessage.error(e?.message || '代理测试失败')
      })
      .finally(() => {
        testLoading.value = false
      })
}

let props = defineProps(['config'])

</script>

<style scoped>
.proxy-test-container {
  justify-content: space-between;
  width: 100%;
}

.proxy-test-controls {
  display: flex;
}

.proxy-test-select {
  width: 240px;
}

.proxy-test-spacer {
  width: 4px;
}

.proxy-alert {
  margin-top: 8px;
}
</style>
