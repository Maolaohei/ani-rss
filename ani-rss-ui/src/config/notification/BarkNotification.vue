<template>
  <template v-if="notificationConfig['notificationType'] === 'BARK'">
    <el-form-item label="通知模版">
      <el-input v-model:model-value="notificationConfig['notificationTemplate']" :autosize="{ minRows: 2}"
                placeholder="${notification}" type="textarea"/>
    </el-form-item>
    <el-form-item label="服务地址">
      <el-input v-model="notificationConfig['barkServerUrl']" placeholder="https://api.day.app"/>
    </el-form-item>
    <el-form-item label="设备 Key">
      <el-input-tag v-model="notificationConfig['barkDeviceKeys']"/>
    </el-form-item>
    <el-form-item label="分组">
      <el-input v-model="notificationConfig['barkGroup']"/>
    </el-form-item>
    <el-form-item label="推送级别">
      <el-select v-model="notificationConfig['barkLevel']" placeholder="选择级别">
        <el-option label="Critical（重要，会响铃）" value="critical"/>
        <el-option label="Active（默认）" value="active"/>
        <el-option label="TimeSensitive（时效性）" value="timeSensitive"/>
        <el-option label="Passive（静默）" value="passive"/>
      </el-select>
    </el-form-item>
    <el-form-item label="提示音量">
      <el-input-number v-model="notificationConfig['barkVolume']" :min="1" :max="10"/>
    </el-form-item>
    <el-form-item label="其他">
      <el-checkbox v-model="notificationConfig['barkUseMarkdown']">
        使用 Markdown 排版
      </el-checkbox>
    </el-form-item>
  </template>
</template>

<script setup>
let props = defineProps(['notificationConfig', 'config'])
</script>
