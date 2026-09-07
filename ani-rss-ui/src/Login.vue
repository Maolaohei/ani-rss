<template>
  <div
      class="flex-center content">
    <div id="login-page" class="flex-center">
      <div id="form">
        <div style="text-align: center;">
          <img src="../public/icon.svg" height="80" width="80" alt="icon.svg"/>
        </div>
        <h2 class="title-h2">ANI-RSS</h2>
        <el-form @submit.prevent
                 @keyup.enter="login">
          <el-form-item>
            <el-input v-model.trim="user.username"
                      placeholder="用户名" autocomplete="username">
              <template #prefix>
                <el-icon class="el-input__icon">
                  <User/>
                </el-icon>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item>
            <el-input v-model.trim="user.password" show-password
                      placeholder="密码" autocomplete="current-password">
              <template #prefix>
                <el-icon class="el-input__icon">
                  <Key/>
                </el-icon>
              </template>
            </el-input>
          </el-form-item>
          <div class="flex-center action">
            <el-checkbox v-model:model-value="rememberThePassword.remember">记住密码</el-checkbox>
            <el-button @click="login" :loading="loading" text bg icon="Right">登录</el-button>
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
        github
      </el-link>
    </div>
  </div>
</template>

<script setup>
import {onMounted, ref} from "vue";
import * as http from "./js/http.js";
import {Key} from "@element-plus/icons-vue";
import {ElMessage} from "element-plus";
import {authorization, rememberThePassword} from "@/js/global.js";

let loading = ref(false)

let user = ref({
  username: '',
  password: ''
})

/**
 * 登录
 */
let login = () => {
  let {username, password} = user.value;

  if (!password || !username) {
    ElMessage.error('请输入账号与密码')
    return
  }

  loading.value = true

  http.login(user.value)
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
      .finally(() => {
        loading.value = false
      })
}

/**
 * 测试是否处于白名单
 */
let test = () => {
  if (authorization.value) {
    return
  }
  http.testIpWhitelist()
      .then(res => {
        if (res.code === 200) {
          authorization.value = new Date().getTime() + '';
          return
        }
        authorization.value = ''
      })
}

onMounted(() => {
  test()
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
}

.footer {
  margin-bottom: 16px;
  display: flex;
  gap: 14px;
  justify-content: center;
  align-items: center;
  font-size: 12px;
}

@media (max-width: 450px) {
  #form {
    width: 80%;
  }
}

#login-page {
  flex: 1;
  width: 100%;
}
</style>
