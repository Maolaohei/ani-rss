<template>
  <el-form-item label="下载速度限制">
    <el-input-number v-model:model-value="props.config['dlLimit']" :min="0">
      <template #suffix>
        <span>KiB/s</span>
      </template>
    </el-input-number>
  </el-form-item>
  <el-form-item label="上传速度限制">
    <el-input-number v-model:model-value="props.config['upLimit']" :min="0">
      <template #suffix>
        <span>KiB/s</span>
      </template>
    </el-input-number>
  </el-form-item>
  <el-form-item label="分享率">
    <div class="qb-limit-field">
      <el-select :model-value="limitMode(props.config.ratioLimit)"
                 class="width-150"
                 @change="v => props.config.ratioLimit = fromLimitMode(v, props.config.ratioLimit)">
        <el-option v-for="it in limitModes" :key="it.value" :label="it.label" :value="it.value"/>
      </el-select>
      <el-input-number v-if="limitMode(props.config.ratioLimit) === 'custom'"
                       v-model:model-value="props.config.ratioLimit" :min="0"/>
      <el-text class="mx-1" size="small">
        qBittorrent 原始取值：-1 关闭该限制，-2 跟随全局设置
      </el-text>
    </div>
  </el-form-item>
  <el-form-item label="总做种时长">
    <div class="qb-limit-field">
      <el-select :model-value="limitMode(props.config.seedingTimeLimit)"
                 class="width-150"
                 @change="v => props.config.seedingTimeLimit = fromLimitMode(v, props.config.seedingTimeLimit)">
        <el-option v-for="it in limitModes" :key="it.value" :label="it.label" :value="it.value"/>
      </el-select>
      <el-input-number v-if="limitMode(props.config.seedingTimeLimit) === 'custom'"
                       v-model:model-value="props.config.seedingTimeLimit" :min="0">
        <template #suffix>
          <span>分钟</span>
        </template>
      </el-input-number>
      <el-text class="mx-1" size="small">
        qBittorrent 原始取值：-1 关闭该限制，-2 跟随全局设置
      </el-text>
    </div>
  </el-form-item>
  <el-form-item label="非活跃时长">
    <div class="qb-limit-field">
      <el-select :model-value="limitMode(props.config.inactiveSeedingTimeLimit)"
                 class="width-150"
                 @change="v => props.config.inactiveSeedingTimeLimit = fromLimitMode(v, props.config.inactiveSeedingTimeLimit)">
        <el-option v-for="it in limitModes" :key="it.value" :label="it.label" :value="it.value"/>
      </el-select>
      <el-input-number v-if="limitMode(props.config.inactiveSeedingTimeLimit) === 'custom'"
                       v-model:model-value="props.config.inactiveSeedingTimeLimit" :min="0">
        <template #suffix>
          <span>分钟</span>
        </template>
      </el-input-number>
      <el-text class="mx-1" size="small">
        qBittorrent 原始取值：-1 关闭该限制，-2 跟随全局设置
      </el-text>
    </div>
  </el-form-item>
  <el-form-item label="内容布局">
    <div>
      <el-select v-model:model-value="props.config.qbContentLayout" class="width-150">
        <el-option label="原始" value="Original"/>
        <el-option label="创建子文件夹" value="Subfolder"/>
        <el-option label="不创建子文件夹" value="NoSubfolder"/>
      </el-select>
    </div>
  </el-form-item>
  <el-form-item label="qb保存路径">
    <div>
      <el-switch v-model:model-value="props.config.qbUseDownloadPath"
                 :disabled="config.downloadToolType !== 'qBittorrent'"/>
      <br>
      <el-text class="mx-1" size="small">
        开启后将使用qBittorrent的临时下载位置 (最终下载位置不受影响)
      </el-text>
    </div>
  </el-form-item>
</template>

<script setup>
let props = defineProps(['config'])

/**
 * qBittorrent 用 -1/-2 表达"关闭该限制 / 跟随全局设置"，
 * 直接让用户填负数既不直观也容易填错，这里统一收敛成一个下拉。
 */
const limitModes = [
  {label: '跟随全局设置（-2）', value: 'global'},
  {label: '关闭该限制（-1）', value: 'disabled'},
  {label: '自定义数值', value: 'custom'}
]

const limitMode = (value) => {
  if (value === -1) {
    return 'disabled'
  }
  if (value === -2) {
    return 'global'
  }
  return 'custom'
}

const fromLimitMode = (mode, current) => {
  if (mode === 'disabled') {
    return -1
  }
  if (mode === 'global') {
    return -2
  }
  // 从 -1/-2 切到自定义时给一个合理的起点
  return current >= 0 ? current : 0
}
</script>

<style scoped>
.qb-limit-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}
</style>