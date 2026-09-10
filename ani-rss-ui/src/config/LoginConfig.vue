<template>
  <el-form @submit.prevent label-width="auto">
    <el-form-item label="用户名">
      <el-input v-model:model-value="props.config.login.username" autocomplete="new-password">
        <template #prefix>
          <el-icon class="el-input__icon">
            <User/>
          </el-icon>
        </template>
      </el-input>
    </el-form-item>
    <el-form-item label="密码">
      <el-input v-model:model-value="props.config.login.password" show-password autocomplete="new-password">
        <template #prefix>
          <el-icon class="el-input__icon">
            <Key/>
          </el-icon>
        </template>
      </el-input>
    </el-form-item>
    <el-form-item label="登录有效">
      <el-input-number v-model:model-value="props.config.loginEffectiveHours" :min="1">
        <template #suffix>
          <span>小时</span>
        </template>
      </el-input-number>
    </el-form-item>
    <el-form-item label="其他">
      <el-checkbox v-model="props.config['multiLoginForbidden']" label="禁止多端登录"/>
      <el-checkbox v-model="props.config.innerIP" label="禁止公网访问"/>
      <el-checkbox v-model="props.config.verifyLoginIp" label="如果IP发生改变登录将失效"/>
      <el-checkbox v-model="props.config.limitLoginAttempts" label="限制尝试次数"/>
      <el-checkbox v-model="props.config.allowCors" label="允许跨域"/>
      <el-input v-if="props.config.allowCors"
                v-model="props.config.corsOrigins"
                class="cors-input"
                placeholder="跨域白名单 Origin（逗号分隔），如 https://a.example.com,https://b.example.com"/>
    </el-form-item>
    <el-form-item label="IP白名单">
      <div class="full-width">
        <div class="flex ip-whitelist-switch">
          <el-switch v-model:model-value="config['ipWhitelist']"/>
          <div class="spacer"></div>
          <el-button bg text size="small" :loading="whitelistTesting" @click="testWhitelist">
            检测当前 IP
          </el-button>
        </div>
        <div class="full-width">
          <el-input class="full-width" type="textarea"
                    :autosize="{ minRows: 2}"
                    :disabled="!config['ipWhitelist']"
                    :placeholder="'127.0.0.1\n192.168.1.0/24'" v-model:model-value="config['ipWhitelistStr']"/>
          <br>
          <el-text class="mx-1" size="small">
            对IP白名单跳过身份验证, 换行可填写多个
          </el-text>
          <el-alert
              v-if="whitelistResult"
              class="mt-8"
              :type="whitelistResult.type"
              :title="whitelistResult.text"
              :closable="false"
              show-icon/>
        </div>
      </div>
    </el-form-item>
    <el-form-item label="信任的反代IP">
      <div class="full-width">
        <el-checkbox label="启用" v-model="config.reverseProxyTrustIpListEnabled"/>
        <br>
        <el-input-tag v-model="config.reverseProxyTrustIpList"/>
      </div>
    </el-form-item>
    <el-form-item label="Api Key">
      <div class="flex full-width">
        <el-input v-model:model-value="props.config.apiKey" readonly/>
        <div class="login-api-key-buttons flex">
          <el-button bg text @click="createApiKey">生成</el-button>
          <el-button bg text @click="copy(props.config.apiKey)">复制</el-button>
        </div>
      </div>
    </el-form-item>
  </el-form>
</template>

<script setup>
import {ElMessage, ElText} from "element-plus";
import {Key, User} from "@element-plus/icons-vue";
import {copyText} from "@/js/global.js";

let generateRandomString = (length) => {
  const charset = 'abcdefghijklmnopqrstuvwxyz0123456789';
  let randomString = '';
  for (let i = 0; i < length; i++) {
    const randomIndex = Math.floor(Math.random() * charset.length);
    randomString += charset[randomIndex];
  }
  return randomString;
}

let createApiKey = () => {
  props.config.apiKey = generateRandomString(64);
}

let copy = (v) => {
  if (!v) {
    ElMessage.warning('Api Key 为空，请先点击「生成」')
    return
  }
  copyText(v)
}

/** IP 白名单自测：写错 CIDR 会静默失效，此前只能在登录页被动验证 */
let whitelistTesting = ref(false)
let whitelistResult = ref(null)

let testWhitelist = () => {
  whitelistTesting.value = true
  whitelistResult.value = null
  http.testIpWhitelist()
      .then(res => {
        if (res && res.code === 200) {
          whitelistResult.value = {type: 'success', text: '当前 IP 命中白名单：已跳过登录验证'}
        } else {
          whitelistResult.value = {
            type: 'info',
            text: res?.message || '当前 IP 不在白名单内，仍需账号密码登录'
          }
        }
      })
      .catch(() => {
        whitelistResult.value = {type: 'warning', text: '检测失败，请确认服务可访问后重试'}
      })
      .finally(() => {
        whitelistTesting.value = false
      })
}

let props = defineProps(['config'])
</script>

<style scoped>
.login-api-key-buttons {
  margin-left: 12px;
}

.cors-input {
  width: 360px;
  margin-left: 12px;
}

.ip-whitelist-switch {
  align-items: center;
  margin-bottom: 6px;
}

.mt-8 {
  margin-top: 8px;
}
</style>
