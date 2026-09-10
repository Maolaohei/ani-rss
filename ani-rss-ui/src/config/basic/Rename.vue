<template>
  <el-form @submit.prevent label-width="auto"
           class="full-width">
    <el-form-item label="自动重命名">
      <el-switch v-model:model-value="props.config.rename"/>
    </el-form-item>
    <el-form-item label="重命名间隔">
      <el-input-number v-model:model-value="props.config['renameSleepSeconds']"
                       :disabled="!config.rename"
                       :min="5">
        <template #suffix>
          <span>秒</span>
        </template>
      </el-input-number>
    </el-form-item>
    <el-form-item label="最大文件名长度">
      <div>
        <el-input-number v-model:model-value="props.config.maxFileNameLength" :min="0"/>
        <br>
        <el-text class="mx-1" size="small">
          超出该长度的文件名会被截断；填 0 表示不限制
        </el-text>
      </div>
    </el-form-item>
    <el-form-item label="重命名模版">
      <div class="full-width">
        <div>
          <el-input v-model:model-value="props.config.renameTemplate"
                    placeholder="${title} S${seasonFormat}E${episodeFormat}"/>
        </div>
        <div>
          <el-alert
              v-if="!testRenameTemplate(props.config.renameTemplate)"
              class="mt-8"
              type="warning"
              show-icon
              :closable="false"
          >
            <template #title>
              模板内至少需要保留 S${seasonFormat}E${episodeFormat} 或 S${season}E${episode}，否则会导致无法正常重命名
            </template>
          </el-alert>
          <el-alert
              v-if="unknownTemplateVars.length"
              class="mt-8"
              type="warning"
              show-icon
              :closable="false"
              :title="`存在无法识别的变量：${unknownTemplateVars.map(it => '${' + it + '}').join('、')}，会被原样写进文件名（可用变量：${RENAME_TEMPLATE_VARS.join('、')}）`"
          />
        </div>
        <el-text class="mx-1" size="small">
          <el-link
              class="text-extra-small"
              type="primary"
              href="https://docs.wushuo.top/config/basic/rename#rename-template"
              target="_blank">详细说明
          </el-link>
        </el-text>
        <rename-template-tools
            v-model="props.config.renameTemplate"
            :preset="EMBY_PRESET"
            preset-name="官方EMBY标准格式"/>
      </div>
    </el-form-item>
    <el-form-item label="剧场版重命名模版(电影式)">
      <div class="full-width">
        <div>
          <el-input v-model:model-value="props.config.ovaRenameTemplate"
                    placeholder="${title} (${year}) [${subgroup}]"/>
        </div>
        <rename-template-tools
            v-model="props.config.ovaRenameTemplate"
            :preset="OVA_PRESET"
            preset-name="电影格式（内置默认）"/>
        <el-text class="mx-1" size="small">
          仅对剧场版生效（媒体类型选"剧场版"），不包含 S/E 占位符，便于 Emby/Jellyfin 识别为电影。
          多部（上/中/下、Part N）会自动追加 Part N，可用 ${part} 占位符自定义位置。
          留空使用内置默认 ${title} (${year}) [${subgroup}]。OVA 特典不受此模板影响。
          注意：开启上方"剔除年份"会把 ${year} 生成的年份一并移除。
        </el-text>
      </div>
      </el-form-item>
    <el-form-item label="剔除年份">
      <div>
        <el-switch v-model:model-value="props.config.renameDelYear"/>
        <br>
        <el-text class="mx-1" size="small">
          重命名时剔除 年份, 如 (2024)
        </el-text>
      </div>
    </el-form-item>
    <el-form-item label="剔除TMDB ID">
      <div>
        <el-switch v-model:model-value="props.config.renameDelTmdbId"/>
        <br>
        <el-text class="mx-1" size="small">
          重命名时剔除 tmdbid, 如 [tmdbid=242143]
        </el-text>
      </div>
    </el-form-item>
    <el-form-item label="字幕独立文件夹">
      <div>
        <el-switch v-model:model-value="props.config.subtitleIndependentFolderEnabled"/>
        <br>
        <el-input v-model="config.subtitleIndependentFolderName"
                  :disabled="!props.config.subtitleIndependentFolderEnabled"
                  placeholder="字幕"/>
        <br>
        <el-text class="mx-1" size="small">
          仅支持 qBittorrent；只填文件夹名，不要带路径分隔符
        </el-text>
        <el-alert
            v-if="folderNameIssue"
            class="mt-8"
            type="warning"
            show-icon
            :closable="false"
            :title="folderNameIssue"
        />
      </div>
    </el-form-item>
  </el-form>
</template>

<script setup>
import {computed} from "vue";
import {ElText} from "element-plus";
import RenameTemplateTools from "@/config/basic/RenameTemplateTools.vue";

/**
 * 重命名模板可用变量白名单（与 RenameUtil 实际替换的字段一致）。
 * 不校验的话，写错的变量会被原样写进文件名。
 */
const RENAME_TEMPLATE_VARS = [
  'title', 'themoviedbName', 'subgroup', 'jpTitle', 'episodeTitle',
  'season', 'seasonFormat', 'episode', 'episodeFormat',
  'year', 'resolution', 'tmdbid', 'part'
]

const RENAME_TEMPLATE_VAR_SET = new Set(RENAME_TEMPLATE_VARS)

let testRenameTemplate = renameTemplate => {
  let test = [
    'S${season}E${episode}',
    'S${seasonFormat}E${episodeFormat}'
  ]
  for (let s of test) {
    if ((renameTemplate || '').indexOf(s) > -1) {
      return true;
    }
  }
  return false;
}

/**
 * 未知变量的具体清单：只报"未按模版填写"对用户没有帮助
 */
const unknownTemplateVars = computed(() => {
  const value = props.config.renameTemplate || ''
  const unknown = []
  const re = /\$\{([^}]*)\}/g
  let match
  while ((match = re.exec(value)) !== null) {
    if (!RENAME_TEMPLATE_VAR_SET.has(match[1])) {
      unknown.push(match[1])
    }
  }
  return unknown
})

const folderNameIssue = computed(() => {
  if (!props.config.subtitleIndependentFolderEnabled) {
    return null
  }
  const name = (props.config.subtitleIndependentFolderName || '').trim()
  if (!name) {
    return '已开启字幕独立文件夹但名称为空，将退回默认行为'
  }
  if (/[\\/:*?"<>|]/.test(name) || name === '.' || name === '..') {
    return '文件夹名包含非法字符（\\ / : * ? " < > |）或为相对路径，重命名阶段会失败'
  }
  return null
})

let props = defineProps(['config'])

/** 官方 Emby 标准格式预设（点击覆盖整个模板） */
const EMBY_PRESET = '${title} (${year}) - S${seasonFormat}E${episodeFormat} - ${episodeTitle} ${resolution}'
/** 剧场版内置默认预设 */
const OVA_PRESET = '${title} (${year}) [${subgroup}]'
</script>

<style scoped>
.mt-8 {
  margin-top: 8px;
}

.text-extra-small {
  font-size: var(--el-font-size-extra-small);
}
</style>
