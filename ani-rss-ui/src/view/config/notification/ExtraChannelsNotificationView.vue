<template>
  <!-- ntfy -->
  <template v-if="notificationConfig['notificationType'] === 'NTFY'">
    <SettingsItem label="通知模版">
      <el-input v-model:model-value="notificationConfig['notificationTemplate']" :autosize="{ minRows: 2}"
                placeholder="${notification}" type="textarea"/>
    </SettingsItem>
    <SettingsItem label="ServerUrl">
      <el-input v-model="notificationConfig['ntfyServerUrl']" placeholder="https://ntfy.sh"/>
    </SettingsItem>
    <SettingsItem label="Topic">
      <el-input v-model="notificationConfig['ntfyTopic']" placeholder="订阅的主题名"/>
    </SettingsItem>
    <SettingsItem label="Token">
      <el-input v-model="notificationConfig['ntfyToken']" placeholder="可选，受保护主题需要" show-password/>
    </SettingsItem>
    <SettingsItem label="优先级">
      <el-input-number v-model="notificationConfig['ntfyPriority']" :min="1" :max="5"/>
    </SettingsItem>
  </template>

  <!-- Gotify -->
  <template v-if="notificationConfig['notificationType'] === 'GOTIFY'">
    <SettingsItem label="通知模版">
      <el-input v-model:model-value="notificationConfig['notificationTemplate']" :autosize="{ minRows: 2}"
                placeholder="${notification}" type="textarea"/>
    </SettingsItem>
    <SettingsItem label="ServerUrl">
      <el-input v-model="notificationConfig['gotifyServerUrl']" placeholder="https://gotify.example.com"/>
    </SettingsItem>
    <SettingsItem label="Token">
      <el-input v-model="notificationConfig['gotifyToken']" placeholder="应用令牌" show-password/>
    </SettingsItem>
    <SettingsItem label="优先级">
      <el-input-number v-model="notificationConfig['gotifyPriority']" :min="0" :max="10"/>
    </SettingsItem>
  </template>

  <!-- PushDeer -->
  <template v-if="notificationConfig['notificationType'] === 'PUSH_DEER'">
    <SettingsItem label="通知模版">
      <el-input v-model:model-value="notificationConfig['notificationTemplate']" :autosize="{ minRows: 2}"
                placeholder="${notification}" type="textarea"/>
    </SettingsItem>
    <SettingsItem label="Key">
      <el-input v-model="notificationConfig['pushDeerKey']" placeholder="PushDeer Key" show-password/>
    </SettingsItem>
    <SettingsItem label="ApiUrl">
      <el-input v-model="notificationConfig['pushDeerApiUrl']" placeholder="https://api2.pushdeer.com"/>
    </SettingsItem>
  </template>

  <!-- 飞书 -->
  <template v-if="notificationConfig['notificationType'] === 'FEISHU'">
    <SettingsItem label="通知模版">
      <el-input v-model:model-value="notificationConfig['notificationTemplate']" :autosize="{ minRows: 2}"
                placeholder="${notification}" type="textarea"/>
    </SettingsItem>
    <SettingsItem label="Webhook">
      <el-input v-model="notificationConfig['feishuWebhook']"
                placeholder="https://open.feishu.cn/open-apis/bot/v2/hook/xxx"/>
    </SettingsItem>
  </template>

  <!-- 钉钉 -->
  <template v-if="notificationConfig['notificationType'] === 'DING_TALK'">
    <SettingsItem label="通知模版">
      <el-input v-model:model-value="notificationConfig['notificationTemplate']" :autosize="{ minRows: 2}"
                placeholder="${notification}" type="textarea"/>
    </SettingsItem>
    <SettingsItem label="Webhook">
      <el-input v-model="notificationConfig['dingTalkWebhook']"
                placeholder="https://oapi.dingtalk.com/robot/send?access_token=xxx"/>
    </SettingsItem>
    <SettingsItem label="加签密钥">
      <el-input v-model="notificationConfig['dingTalkSecret']" placeholder="安全设置选「加签」时填写" show-password/>
    </SettingsItem>
    <SettingsItem label="">
      <el-text size="small" type="info">
        钉钉要求消息包含自定义关键词，或在安全设置中使用「加签」/「IP 白名单」。
      </el-text>
    </SettingsItem>
  </template>

  <!-- 企业微信 -->
  <template v-if="notificationConfig['notificationType'] === 'WE_COM'">
    <SettingsItem label="通知模版">
      <el-input v-model:model-value="notificationConfig['notificationTemplate']" :autosize="{ minRows: 2}"
                placeholder="${notification}" type="textarea"/>
    </SettingsItem>
    <SettingsItem label="Webhook">
      <el-input v-model="notificationConfig['weComWebhook']"
                placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx"/>
    </SettingsItem>
    <SettingsItem label="">
      <el-text size="small" type="info">
        企业微信 markdown 正文上限 4096 字节，超长内容会自动截断。
      </el-text>
    </SettingsItem>
  </template>
</template>

<script setup>
import SettingsItem from "@/view/custom/SettingsItem.vue";

defineProps(['notificationConfig', 'config'])
</script>
