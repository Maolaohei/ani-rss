<template>
  <div
      class="flex-center content">
    <div id="login-page" class="flex-center">
      <div id="form">
        <div style="text-align: center;">
          <img :src="logoUrl" height="80" width="80" alt="ANI-RSS"/>
        </div>
        <h2 class="title-h2">ANI-RSS</h2>
        <el-form @submit.prevent
                 @keyup.enter="login">
          <el-form-item label="用户名" label-position="top" class="login-field">
            <el-input v-model.trim="user.username"
                      aria-label="用户名"
                      placeholder="用户名" autocomplete="username">
              <template #prefix>
                <el-icon class="el-input__icon">
                  <User/>
                </el-icon>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item label="密码" label-position="top" class="login-field">
            <!-- 不用 show-password：EP 的眼睛图标是裸 <i onClick>，键盘用户无法操作、读屏也叫不出名字。
                 这里用真实按钮自绘开关，兼容鼠标与键盘。 -->
            <el-input v-model.trim="user.password"
                      :type="showPassword ? 'text' : 'password'"
                      aria-label="密码"
                      placeholder="密码" autocomplete="current-password">
              <template #prefix>
                <el-icon class="el-input__icon">
                  <Key/>
                </el-icon>
              </template>
              <template #suffix>
                <el-button
                    class="password-toggle"
                    text bg
                    size="small"
                    :icon="showPassword ? View : Hide"
                    :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                    :title="showPassword ? '隐藏密码' : '显示密码'"
                    @mousedown.prevent
                    @click="showPassword = !showPassword"/>
              </template>
            </el-input>
          </el-form-item>
          <!-- 登录失败原因常驻展示：不依赖会消失的 toast，避免「表单闪一下」后无从得知原因 -->
          <el-alert
              v-if="errorMessage"
              class="login-error"
              :title="errorMessage"
              :type="errorType"
              :closable="false"
              show-icon/>
          <div class="flex-center action">
            <el-checkbox v-model:model-value="rememberThePassword.remember" class="remember">记住密码</el-checkbox>
            <el-button class="login-submit" @click="login" :loading="loading" text bg icon="Right"
                       aria-label="登录">登录
            </el-button>
          </div>
        </el-form>
      </div>
    </div>
    <div class="footer">
      <el-link type="default"
               href="https://docs.wushuo.top"
               target="_blank">
        ani-rss
      </el-link>
      &nbsp;
      <el-link type="default"
               href="https://github.com/wushuo894/ani-rss"
               target="_blank">
        GitHub
      </el-link>
    </div>
  </div>
</template>

<script setup>
import {onMounted, ref} from "vue";
import * as http from "./js/http.js";
import {Hide, Key, View} from "@element-plus/icons-vue";
import logoUrl from "../public/icon.svg";
import {authorization, rememberThePassword} from "@/js/global.js";
import {LOGIN_RATE_LIMITED_CODE} from "@/js/api.js";

let loading = ref(false)
let showPassword = ref(false)

let user = ref({
  username: '',
  password: ''
})

/** 常驻错误提示（密码错误 / 限流 / 网络异常） */
let errorMessage = ref('')
let errorType = ref('error')

/** 白名单探测是否结束：用于通知父级（Main.vue），避免已授权用户看到登录表单闪现 */
const emit = defineEmits(['ready'])

/**
 * 登录
 */
let login = () => {
  // 并发守卫：el-button 的 loading 只拦截点击，回车不受约束，
  // 按住回车会连发请求并加速触发后端 30 次/天的登录限流。
  if (loading.value) {
    return
  }

  let {username, password} = user.value;

  if (!password || !username) {
    errorMessage.value = '请输入账号与密码'
    errorType.value = 'warning'
    return
  }

  errorMessage.value = ''
  loading.value = true

  http.loginInteractive(user.value)
      .then(res => {
        // 记住密码
        if (rememberThePassword.value.remember) {
          rememberThePassword.value.username = username
          rememberThePassword.value.password = password
        } else {
          rememberThePassword.value.username = ''
          rememberThePassword.value.password = ''
        }

        authorization.value = res.data
      })
      .catch(err => {
        if (err?.code === LOGIN_RATE_LIMITED_CODE) {
          errorType.value = 'error'
          errorMessage.value = '登录失败次数过多，已限制登录 1 天。请稍后再试，或到服务器上直接修改配置中的账号密码'
          return
        }
        errorType.value = 'error'
        errorMessage.value = err?.message || '登录失败，请检查账号密码'
      })
      .finally(() => {
        loading.value = false
      })
}

/**
 * 测试是否处于白名单
 */
let test = () => {
  if (authorization.value) {
    return Promise.resolve()
  }
  return http.testIpWhitelist()
      .then(res => {
        if (res.code === 200) {
          // 命中白名单即自动进入主界面；用户不会看到登录表单，因此无需额外提示
          authorization.value = new Date().getTime() + '';
          return
        }
        authorization.value = ''
      })
      .catch(() => {
        // 白名单探测失败不阻塞手动登录
      })
}

onMounted(() => {
  test().finally(() => emit('ready'))
  let {remember, username, password} = rememberThePassword.value;
  if (remember && username && password) {
    user.value.username = username
    user.value.password = password
  }
})

</script>

<style scoped>
.content {
  width: 100%;
  height: 100%;
  flex-flow: column;
  justify-content: space-between;
  /* 刘海屏/底部手势条安全区 */
  padding: env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left);
}

/* 演示页 dlg-login 规格：居中卡片 + 卡内图标/标题/表单 */
#form {
  max-width: 380px;
  box-sizing: border-box;
  padding: 30px 28px 24px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-extra-light);
  border-radius: 16px;
  box-shadow: var(--el-box-shadow-light);
}

.login-field {
  margin-bottom: 14px;
}

.login-field :deep(.el-form-item__label) {
  padding-bottom: 2px;
  font-size: 13px;
}

/* 触控目标：EP 默认 32px 低于 44px 的可点下限 */
.password-toggle {
  width: 32px;
  height: 32px;
  padding: 0;
}

.login-error {
  margin-bottom: 12px;
}

.title-h2 {
  text-align: center;
  margin: 16px 0 28px;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--el-text-color-primary);
}

.action {
  width: 100%;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.action :deep(.remember) {
  height: 44px;
}

.login-submit {
  min-height: 44px;
  padding: 0 20px;
}

.footer {
  margin-bottom: calc(16px + env(safe-area-inset-bottom));
  display: flex;
  gap: 14px;
  justify-content: center;
  align-items: center;
  font-size: 12px;
}

@media (max-width: 450px) {
  #form {
    width: 86%;
    padding: 24px 18px 18px;
  }
}

#login-page {
  flex: 1;
  width: 100%;
}
</style>
