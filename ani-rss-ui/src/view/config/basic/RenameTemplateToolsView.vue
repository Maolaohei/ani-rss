<template>
  <div class="tpl-tools">
    <div class="tpl-live">
      <span class="tpl-live-label">模板</span>
      <code class="tpl-live-code" v-html="templateHtml"></code>
    </div>
    <div class="tpl-live is-effect">
      <span class="tpl-live-label">效果</span>
      <code class="tpl-live-code" v-html="effectHtml"></code>
      <el-button
          class="tpl-copy"
          text
          bg
          size="small"
          icon="CopyDocument"
          :disabled="!props.modelValue"
          @click="copyEffect">
        复制
      </el-button>
    </div>
    <el-collapse class="tpl-collapse">
      <el-collapse-item name="vars">
        <template #title>
          <span class="tpl-collapse-title">
            模板变量
            <span class="tpl-collapse-tip">点选变量自动追加 · ⚡预设 直接覆盖</span>
          </span>
        </template>
        <div class="tpl-body">
          <button type="button" class="tpl-chip is-preset" title="点击覆盖当前模板为该预设" @click="applyPreset">
            <b>⚡ 预设</b>
            <i>{{ presetName }}</i>
          </button>
          <button v-for="v in VARS" :key="v.name" type="button" class="tpl-chip"
                  :class="{'is-hot': v.name === 'seasonFormat' || v.name === 'episodeFormat'}"
                  :title="'点击追加 ${' + v.name + '}（示例：' + (SAMPLE[v.name] ?? '') + '）'"
                  @click="appendVar(v.name)">
            <b>{{ '${' + v.name + '}' }}</b>
            <i>{{ v.label }}</i>
          </button>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup>
import {computed} from "vue";
import {ElMessage} from "element-plus";
import {copyText} from "@/js/global.js";

/**
 * 模板变量与示例值均来自官方文档 docs.wushuo.top/config/basic/rename#rename-template
 */
const VARS = [
  {name: 'title', label: '标题'},
  {name: 'themoviedbName', label: 'TMDB标题'},
  {name: 'jpTitle', label: 'Bangumi 日文标题'},
  {name: 'subgroup', label: '字幕组'},
  {name: 'seasonFormat', label: '季'},
  {name: 'season', label: '季'},
  {name: 'episodeFormat', label: '集'},
  {name: 'episode', label: '集'},
  {name: 'itemTitle', label: '原始标题'},
  {name: 'resolution', label: '分辨率'},
  {name: 'tmdbid', label: 'TMDB ID'},
  {name: 'bgmId', label: 'BGM ID'},
  {name: 'episodeTitle', label: '集标题 (TMDB)'},
  {name: 'bgmEpisodeTitle', label: '集标题 (Bangumi)'},
  {name: 'bgmJpEpisodeTitle', label: '日文集标题 (Bangumi)'},
  {name: 'part', label: '分卷（剧场版）'},
  {name: 'year', label: '年份'}
]

const SAMPLE = {
  title: 'Re：从零开始的异世界生活',
  themoviedbName: 'Re：从零开始的异世界生活',
  jpTitle: 'Re:ゼロから始める異世界生活',
  subgroup: 'ANi',
  season: '1',
  seasonFormat: '01',
  episode: '1',
  episodeFormat: '01',
  itemTitle: '[ANi] Re：从零开始的异世界生活 第三季 - 01 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]',
  resolution: '1080p',
  tmdbid: '65942',
  bgmId: '140001',
  episodeTitle: '起始的终结与终结的起始',
  bgmEpisodeTitle: '起始的终结与终结的起始',
  bgmJpEpisodeTitle: '始まりの終わりと終わりの始まり',
  part: '1',
  year: '2024'
}

const esc = s => String(s).replace(/[&<>"']/g, m => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[m]))

const props = defineProps({
  modelValue: {type: String, default: ''},
  preset: {type: String, default: ''},
  presetName: {type: String, default: '预设'}
})

const emit = defineEmits(['update:modelValue'])

/**
 * 把模板解析为段：text（字面量）/ var（已知变量）/ unknown（未知变量）。
 * 「模板」行显示变量名，「效果」行显示示例值，两行共用同一段落结构保证逐段对应。
 */
const parseTemplate = t => {
  const segments = []
  let buf = ''
  let i = 0
  while (i < t.length) {
    if (t[i] === '$' && t[i + 1] === '{') {
      const end = t.indexOf('}', i + 2)
      if (end > -1) {
        const name = t.slice(i + 2, end)
        if (buf) {
          segments.push({type: 'text', text: buf})
          buf = ''
        }
        segments.push(SAMPLE[name] === undefined ? {type: 'unknown', name} : {type: 'var', name})
        i = end + 1
        continue
      }
    }
    buf += t[i++]
  }
  if (buf) {
    segments.push({type: 'text', text: buf})
  }
  return segments
}

const templateHtml = computed(() => {
  const segments = parseTemplate(props.modelValue ?? '')
  if (!segments.length) {
    return '<span style="opacity:.5">（空）</span>'
  }
  return segments.map(segment => {
    if (segment.type === 'var') {
      const sample = SAMPLE[segment.name]
      return '<span class="v" title="' + esc(sample) + '">${' + esc(segment.name) + '}</span>'
    }
    if (segment.type === 'unknown') {
      return '<span class="u">${' + esc(segment.name) + '}</span>'
    }
    return esc(segment.text)
  }).join('')
})

const effectHtml = computed(() => {
  const segments = parseTemplate(props.modelValue ?? '')
  if (!segments.length) {
    return '<span style="opacity:.5">（空）</span>'
  }
  return segments.map(segment => {
    if (segment.type === 'var') {
      return '<span class="v">' + esc(SAMPLE[segment.name]) + '</span>'
    }
    if (segment.type === 'unknown') {
      return '<span class="u">${' + esc(segment.name) + '}</span>'
    }
    return esc(segment.text)
  }).join('')
})

const effectText = computed(() => {
  const segments = parseTemplate(props.modelValue ?? '')
  return segments.map(segment => segment.type === 'var' ? SAMPLE[segment.name] : segment.type === 'unknown' ? '${' + segment.name + '}' : segment.text).join('')
})

const copyEffect = () => copyText(effectText.value)

const appendVar = name => emit('update:modelValue', (props.modelValue ?? '') + '${' + name + '}')

const applyPreset = () => {
  emit('update:modelValue', props.preset)
  ElMessage.success('已覆盖为预设：' + props.presetName)
}
</script>

<style scoped>
.tpl-tools {
  width: 100%;
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.tpl-live {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  background: var(--el-fill-color-light);
  border-radius: 10px;
  padding: 8px 12px;
  min-width: 0;
  box-sizing: border-box;
}

.tpl-live-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-weight: 600;
  flex: none;
  width: 26px;
}

.tpl-live-code {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--el-color-primary);
  word-break: break-all;
  min-width: 0;
  flex: 1;
}

.tpl-live-code :deep(.v) {
  background: var(--el-color-primary-light-9);
  border-radius: 4px;
  padding: 0 3px;
  margin: 0 1px;
}

.tpl-live-code :deep(.u) {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  border-radius: 4px;
  padding: 0 3px;
  margin: 0 1px;
}

/* 效果行：真实文件名观感，替换值保留浅底呼应模板行 */
.tpl-live.is-effect .tpl-live-code {
  color: var(--el-text-color-regular);
}

.tpl-live.is-effect .tpl-live-code :deep(.v) {
  color: var(--el-color-primary);
}

.tpl-copy {
  flex: none;
  margin-left: auto;
}

.tpl-collapse {
  width: 100%;
  --el-collapse-border-color: transparent;
  border-radius: 10px;
  overflow: hidden;
}

.tpl-collapse :deep(.el-collapse-item__header) {
  padding: 0 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-bottom: none;
  height: 34px;
}

.tpl-collapse :deep(.el-collapse-item__wrap) {
  background: var(--el-fill-color-light);
  border-bottom: none;
}

.tpl-collapse :deep(.el-collapse-item__content) {
  padding: 4px 12px 10px;
}

.tpl-collapse-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.tpl-collapse-tip {
  font-size: 10.5px;
  opacity: .75;
}

.tpl-body {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tpl-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 24px;
  padding: 0 9px;
  border: none;
  border-radius: 7px;
  background: var(--el-fill-color);
  color: var(--el-text-color-regular);
  font-size: 11px;
  cursor: pointer;
  transition: filter .12s, transform .12s cubic-bezier(.32, .72, 0, 1);
}

.tpl-chip b {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-weight: 600;
  color: var(--el-color-primary);
}

.tpl-chip i {
  font-style: normal;
  color: var(--el-text-color-secondary);
}

.tpl-chip:hover {
  filter: brightness(.96);
}

html.dark .tpl-chip:hover {
  filter: brightness(1.3);
}

.tpl-chip:active {
  transform: scale(.94);
}

.tpl-chip.is-hot {
  background: var(--el-color-primary-light-9);
}

.tpl-chip.is-preset {
  background: var(--el-color-warning-light-9);
  box-shadow: inset 0 0 0 1px var(--el-color-warning-light-5);
}

.tpl-chip.is-preset b,
.tpl-chip.is-preset i {
  color: var(--el-color-warning);
}

.tpl-chip.is-preset i {
  font-weight: 600;
}

/* 移动端适配：放大触控目标、预览行标签换行不挤压 */
@media (pointer: coarse) {
  .tpl-chip {
    height: 34px;
    font-size: 12px;
    gap: 8px;
    padding: 0 12px;
  }

  .tpl-collapse :deep(.el-collapse-item__header) {
    height: 40px;
    font-size: 13px;
  }
}

@media (max-width: 480px) {
  .tpl-live {
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }

  .tpl-copy {
    align-self: flex-end;
    margin-left: 0;
  }

  .tpl-collapse-tip {
    display: none;
  }
}
</style>
