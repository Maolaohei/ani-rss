<template>
  <div class="tpl-tools">
    <div class="tpl-live">
      <span class="tpl-live-label">当前模板效果</span>
      <code class="tpl-live-code" v-html="liveHtml"></code>
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

const liveHtml = computed(() => {
  const t = props.modelValue ?? ''
  let html = ''
  let buf = ''
  let i = 0
  while (i < t.length) {
    if (t[i] === '$' && t[i + 1] === '{') {
      const end = t.indexOf('}', i + 2)
      if (end > -1) {
        const name = t.slice(i + 2, end)
        if (buf) {
          html += esc(buf)
          buf = ''
        }
        const sample = SAMPLE[name]
        html += '<span class="v"' + (sample === undefined ? ' style="color:var(--el-color-danger)"' : ' title="' + esc(sample) + '"') + '>${' + name + '}</span>'
        i = end + 1
        continue
      }
    }
    buf += t[i++]
  }
  html += esc(buf)
  return html || '<span style="opacity:.5">（空）</span>'
})

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
}

.tpl-live-code {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--el-color-primary);
  word-break: break-all;
  min-width: 0;
}

.tpl-live-code :deep(.v) {
  background: var(--el-color-primary-light-9);
  border-radius: 4px;
  padding: 0 3px;
  margin: 0 1px;
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
</style>
