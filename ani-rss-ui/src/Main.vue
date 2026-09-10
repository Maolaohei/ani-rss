<template>
  <el-config-provider
      :locale="zhCn"
      :link="linkConfig"
      :dialog="dialogConfig">
    <!-- 已登录：直接进主界面 -->
    <App v-if="authorization"/>
    <!-- 未登录：先等一次白名单探测，避免已授权用户看到登录表单闪一下 -->
    <template v-else-if="authChecked">
      <Login @ready="authChecked = true"/>
    </template>
    <div v-else class="auth-booting">
      <el-text type="info" size="small">正在检查登录状态…</el-text>
    </div>
  </el-config-provider>
</template>

<script setup>
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import Login from "@/Login.vue";
import App from "@/home/App.vue";
import {authorization, init} from "@/js/global.js";
import {onMounted, reactive, ref} from "vue";

/**
 * 链接配置
 */
let linkConfig = reactive({
  type: 'primary',
  underline: 'never'
})

let dialogConfig = reactive({
  alignCenter: true
})

/**
 * 白名单探测是否已结束。
 * Login.vue 在探测 Promise 落定后 emit('ready')；这里再加一个兜底超时，
 * 保证探测万一卡住也不会把用户永久留在占位页。
 */
const authChecked = ref(false)

onMounted(() => {
  setTimeout(() => {
    authChecked.value = true
  }, 4000)
})

init()

</script>

<style scoped>
.auth-booting {
  width: 100%;
  height: 100%;
  min-height: 60vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
