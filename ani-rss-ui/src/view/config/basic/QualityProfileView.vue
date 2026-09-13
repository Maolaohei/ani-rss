<template>
  <SettingsItem v-if="showEnable" label="启用">
    <div class="full-width">
      <el-switch v-model="profile.enable"/>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small">
          关闭时同集候选沿用既有逻辑（分辨率 2160p &gt; 1080p &gt; 720p &gt; 480p，同分辨率取体积更大者）。
          开启后按下面的规则择优。规则只影响"同一集有多个候选时选哪个"，
          不会改变「多字幕组共存」与「洗版时主源优先」这两条既有优先级。
        </el-text>
      </div>
    </div>
  </SettingsItem>
  <template v-if="profile.enable || !showEnable">
    <SettingsItem label="分辨率顺序">
      <div class="full-width">
        <el-select v-model="profile.resolutionOrder" multiple class="full-width"
                   placeholder="选择分辨率，靠前的优先">
          <el-option v-for="it in resolutionOptions" :key="it" :label="it" :value="it"/>
        </el-select>
        <div class="margin-top-4">
          <el-text class="mx-1" size="small">按选择顺序作为优先级（先选的更优先），留空表示沿用默认顺序。</el-text>
        </div>
      </div>
    </SettingsItem>
    <SettingsItem label="分辨率范围">
      <div class="flex flex-wrap gap-8">
        <el-select v-model="profile.minResolution" clearable placeholder="最低（不限制）" class="resolution-field">
          <el-option v-for="it in resolutionOptions" :key="`min-${it}`" :label="it" :value="it"/>
        </el-select>
        <el-select v-model="profile.maxResolution" clearable placeholder="最高（不限制）" class="resolution-field">
          <el-option v-for="it in resolutionOptions" :key="`max-${it}`" :label="it" :value="it"/>
        </el-select>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small">
          硬过滤范围外的候选；未知分辨率会保留，不会误过滤。若上下限冲突，过滤全空时自动回退。
        </el-text>
      </div>
    </SettingsItem>
    <SettingsItem label="编码偏好">
      <div class="full-width">
        <el-select v-model="profile.preferCodecs" multiple class="full-width"
                   placeholder="选择编码，靠前的优先">
          <el-option v-for="it in codecOptions" :key="it.value" :label="it.label" :value="it.value"/>
        </el-select>
        <div class="margin-top-4">
          <el-text class="mx-1" size="small">仅影响排序；未识别的编码排在最后。</el-text>
        </div>
      </div>
    </SettingsItem>
    <SettingsItem label="排除编码">
      <div class="full-width">
        <el-select v-model="profile.excludeCodecs" multiple class="full-width"
                   placeholder="命中即过滤（如只要 HEVC 就排除 avc）">
          <el-option v-for="it in codecOptions" :key="it.value" :label="it.label" :value="it.value"/>
        </el-select>
        <div class="margin-top-4">
          <el-text class="mx-1" size="small" type="warning">
            过滤后若某集一个候选都不剩，会自动回退为不过滤——不会因为规则太严导致这集不下。
          </el-text>
        </div>
      </div>
    </SettingsItem>
    <SettingsItem label="体积区间">
      <div class="flex flex-wrap gap-8">
        <el-input-number v-model="profile.minSizeMb" :min="0" :max="100000" :step="50">
          <template #prefix>≥</template>
          <template #suffix><span>MB</span></template>
        </el-input-number>
        <el-input-number v-model="profile.maxSizeMb" :min="0" :max="100000" :step="50">
          <template #prefix>≤</template>
          <template #suffix><span>MB</span></template>
        </el-input-number>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small">填 0 表示不限制。超出区间的候选会被过滤。</el-text>
      </div>
    </SettingsItem>
    <SettingsItem label="做种数下限">
      <div class="full-width">
        <el-input-number v-model="profile.minSeeders" :min="0" :max="100000"/>
        <div class="margin-top-4">
          <el-text class="mx-1" size="small">
            仅当 RSS 源提供做种数时生效；源未提供该字段会保留候选，不会误过滤。
            填 0 表示不限制。
          </el-text>
        </div>
      </div>
    </SettingsItem>
    <SettingsItem label="优先字幕组">
      <el-input-tag v-model="profile.preferSubgroups" placeholder="输入字幕组名后回车"/>
    </SettingsItem>
    <SettingsItem label="排除字幕组">
      <el-input-tag v-model="profile.excludeSubgroups" placeholder="输入字幕组名后回车，命中即过滤"/>
    </SettingsItem>
    <SettingsItem label="优先合集包">
      <div class="full-width">
        <el-switch v-model="profile.preferCollection"/>
        <div class="margin-top-4">
          <el-text class="mx-1" size="small">
            同集同时有合集源与单集源时优先合集（与既有默认行为一致）。关闭后优先单集源。
          </el-text>
        </div>
      </div>
    </SettingsItem>
  </template>
</template>

<script setup>
import {onMounted} from "vue";
import SettingsItem from "@/view/custom/SettingsItem.vue";

const props = defineProps({
  profile: {
    type: Object,
    required: true
  },
  showEnable: {
    type: Boolean,
    default: true
  }
})

const profile = props.profile

const resolutionOptions = ['2160p', '1440p', '1080p', '720p', '480p']
const codecOptions = [
  {label: 'HEVC / H.265 / x265', value: 'hevc'},
  {label: 'AV1', value: 'av1'},
  {label: 'AVC / H.264 / x264', value: 'avc'}
]

/**
 * 后端可能返回 null（存量配置没有该字段），这里就地补全，
 * 避免 v-model 绑到 undefined 导致控件不可用
 */
const ensureDefaults = () => {
  const p = props.profile
  if (p.enable === undefined || p.enable === null) {
    p.enable = props.showEnable ? false : true
  }
  if (!Array.isArray(p.resolutionOrder)) {
    p.resolutionOrder = []
  }
  if (!Array.isArray(p.preferCodecs)) {
    p.preferCodecs = []
  }
  if (!Array.isArray(p.excludeCodecs)) {
    p.excludeCodecs = []
  }
  if (p.minResolution === undefined || p.minResolution === null) {
    p.minResolution = ''
  }
  if (p.maxResolution === undefined || p.maxResolution === null) {
    p.maxResolution = ''
  }
  if (!Array.isArray(p.preferSubgroups)) {
    p.preferSubgroups = []
  }
  if (!Array.isArray(p.excludeSubgroups)) {
    p.excludeSubgroups = []
  }
  if (p.minSizeMb === undefined || p.minSizeMb === null) {
    p.minSizeMb = 0
  }
  if (p.maxSizeMb === undefined || p.maxSizeMb === null) {
    p.maxSizeMb = 0
  }
  if (p.minSeeders === undefined || p.minSeeders === null) {
    p.minSeeders = 0
  }
  if (p.preferCollection === undefined || p.preferCollection === null) {
    p.preferCollection = true
  }
}

onMounted(ensureDefaults)
</script>

<style scoped>
.resolution-field {
  width: 160px;
}

@media (max-width: 560px) {
  .resolution-field {
    width: 140px;
  }
}
</style>
