<template>
  <BgmView ref="bgmRef" @callback="bgmAdd"/>
  <CollectionPreviewView ref="collectionPreviewRef" v-model:data="data"/>
  <el-dialog v-model="dialogVisible"
             center
             title="添加合集">
    <div v-loading="loading" style="height: 500px;">
      <el-scrollbar style="padding: 0 12px;">
        <div>
          <el-form @submit.prevent label-width="auto">
            <el-form-item label="番剧名称">
              <div class="full-width">
                <div class="flex full-width">
                  <el-input
                      v-model:model-value="data.ani.title"
                      :disabled="rssButtonLoading"
                      placeholder="请勿留空"
                      @keyup.enter="bgmRef?.show(data.ani.title)"
                  />
                  <div style="width: 4px;"></div>
                  <el-button :disabled="rssButtonLoading" bg icon="Search" text type="primary"
                             @click="bgmRef?.show(data.ani.title)"/>
                </div>
                <div v-if="data.show" class="change-title-button">
                  <el-button :loading="getBgmNameLoading"
                             bg
                             icon="DocumentAdd" text @click="getBgmName">
                    使用Bangumi
                  </el-button>
                  <el-button :disabled="data.ani.title === data.ani.themoviedbName || !data.ani.themoviedbName.length"
                             bg
                             icon="DocumentAdd"
                             text
                             @click="data.ani.title = data.ani.themoviedbName">
                    使用TMDB
                  </el-button>
                </div>
              </div>
            </el-form-item>
            <template v-if="data.show">
              <el-form-item label="TMDB">
                <div class="flex full-width" style="justify-content: space-between;">
                  <div class="el-input is-disabled">
                    <div class="el-input__wrapper"
                         style="pointer-events: auto;cursor: auto;justify-content: left;padding: 0 11px;"
                         tabindex="-1">
                      <el-link v-if="data.ani?.tmdb?.id"
                               :href="`https://www.themoviedb.org/${data.ani.ova ? 'movie' : 'tv'}/${data.ani.tmdb.id}`"
                               target="_blank"
                               type="primary">
                        {{ data.ani.themoviedbName }}
                      </el-link>
                      <span v-else>{{ data.ani.themoviedbName }}</span>
                    </div>
                  </div>
                  <div style="width: 4px;"></div>
                  <el-button :loading="getThemoviedbNameLoading" bg icon="Refresh" text @click="getThemoviedbName"/>
                </div>
              </el-form-item>
              <el-form-item label="字幕组">
                <div class="form-item-flex">
                  <el-input v-model:model-value="data.ani.subgroup" placeholder="字幕组" class="width-150"/>
                </div>
              </el-form-item>
              <el-form-item label="季">
                <div class="form-item-flex">
                  <el-input-number v-model:model-value="data.ani.season" :disabled="splitMode" :min="0" class="width-150"/>
                  <el-text v-if="splitMode" size="small" type="info" style="margin-left:8px;">拆分模式下由各部分组成</el-text>
                </div>
              </el-form-item>
              <el-form-item label="集数偏移">
                <div class="form-item-flex">
                  <el-input-number v-model:model-value="data.ani.offset" :disabled="splitMode"/>
                </div>
              </el-form-item>
              <el-form-item label="日期">
                <div class="form-item-flex">
                  <el-date-picker
                      v-model="data.ani.releaseDate"
                      style="max-width: 150px;"
                  />
                </div>
              </el-form-item>
              <el-form-item label="匹配">
                <ExcludeView v-model:exclude="data.ani.match" :import-exclude="false"/>
              </el-form-item>
              <el-form-item label="排除">
                <ExcludeView v-model:exclude="data.ani.exclude" :import-exclude="true"/>
              </el-form-item>
              <el-form-item label="全局排除">
                <el-switch v-model:model-value="data.ani['globalExclude']"/>
              </el-form-item>
              <el-form-item label="剧场版">
                <el-switch v-model:model-value="data.ani.ova" :disabled="splitMode"/>
              </el-form-item>
              <el-form-item label="自定义集数规则">
                <div class="flex full-width">
                  <el-input v-model:model-value="data.ani.customEpisodeStr"
                            class="full-width"/>
                  <div style="width: 4px;"></div>
                  <el-input-number v-model:model-value="data.ani.customEpisodeGroupIndex"/>
                </div>
              </el-form-item>
              <el-form-item label="下载位置">
                <div class="full-width">
                  <el-input v-model:model-value="data.ani.customDownloadPathTemplate" :autosize="{ minRows: 2}"
                            class="full-width"
                            type="textarea"/>
                </div>
                <div style="margin-top: 6px;">
                  <el-button :disabled="!data.ani.customDownloadPath"
                             :loading="downloadPathLoading" bg icon="Refresh"
                             text
                             @click="downloadPath"/>
                </div>
              </el-form-item>
              <el-form-item label="重命名模版">
                <div class="full-width">
                  <el-switch v-model="data.ani['customRenameTemplateEnable']"/>
                  <br>
                  <el-input v-model:model-value="data.ani['customRenameTemplate']"
                            :disabled="!data.ani['customRenameTemplateEnable']"
                            placeholder="${title} S${seasonFormat}E${episodeFormat}"/>
                  <br>
                  <el-link
                      style="font-size: var(--el-font-size-extra-small)"
                      type="primary"
                      href="https://docs.wushuo.top/config/basic/rename#rename-template"
                      target="_blank">
                    详细说明
                  </el-link>
                </div>
              </el-form-item>
              <el-form-item label="自定义标签">
                <CustomTagsView :config="data.ani"/>
              </el-form-item>
              <el-form-item label="Torrent">
                <div class="torrent-source">
                  <el-radio-group v-model="torrentSource" size="small" @change="clearTorrentSource">
                    <el-radio-button value="file">种子文件</el-radio-button>
                    <el-radio-button value="magnet">磁力链接</el-radio-button>
                  </el-radio-group>
                  <div v-if="torrentSource === 'file'" class="torrent-input">
                    <el-tag v-if="data.filename" closable @close="clearTorrent">
                      <el-tooltip :content="data.filename">
                        <el-text line-clamp="1" size="small" class="filename">
                          {{ data.filename }}
                        </el-text>
                      </el-tooltip>
                    </el-tag>
                    <UploadView v-else
                                url="api/uploadAndReadToBase64"
                                :extensions="['torrent']"
                                :callback="uploadCallback">
                      <el-button bg icon="Upload">选择并上传种子</el-button>
                    </UploadView>
                  </div>
                  <div v-else class="torrent-input">
                    <el-input v-model="data.torrent"
                              :autosize="{ minRows: 2, maxRows: 4 }"
                              clearable
                              placeholder="magnet:?xt=urn:btih:..."
                              type="textarea"
                              @blur="analyze"/>
                    <div class="margin-top-4">
                      <el-text size="small" type="info">
                        点「预览 / 开始」时才解析元数据（获取文件列表才能匹配、改名），
                        热门资源几秒完成，冷门资源最长等待 60 秒；解析结果会缓存在本地。
                      </el-text>
                    </div>
                  </div>
                </div>
              </el-form-item>
              <el-form-item v-if="splitMode" label="合集拆分">
                <div class="full-width">
                  <el-alert :closable="false" show-icon style="margin-bottom:8px;" type="success">
                    <template #title>
                      检测到 {{ data.segments.length }} 个部分：{{ segmentSummary }}。
                      每部分会生成独立订阅，按各自的季 / 偏移 / 目录落盘。
                    </template>
                  </el-alert>
                  <el-alert v-for="(w, wi) in data.warnings" :key="'warn' + wi"
                            :closable="false" :title="w" show-icon style="margin-bottom:6px;" type="warning"/>
                  <div v-for="(seg, si) in data.segments" :key="seg.id || si" class="segment-row">
                    <div class="segment-head">
                      <el-tag :type="seg.ova ? 'warning' : 'primary'" size="small">{{ segmentKind(seg) }}</el-tag>
                      <el-input v-model="seg.title" :placeholder="data.ani.title" size="small" style="max-width:240px;"/>
                      <el-input-number v-if="!seg.ova" v-model="seg.season" :controls="false" :min="0"
                                       placeholder="季" size="small" style="width:70px;"/>
                      <el-input-number v-if="!seg.ova" v-model="seg.offset" :controls="false"
                                       placeholder="偏移" size="small" style="width:80px;"/>
                      <el-text size="small" type="info">{{ seg.fileCount }} 个文件</el-text>
                    </div>
                    <el-input v-model="seg.downloadPath" placeholder="下载位置（留空则按模版自动计算）" size="small"/>
                    <el-text v-if="seg.sample" class="segment-sample" size="small" type="info">{{ seg.sample }}</el-text>
                  </div>
                  <div class="segment-actions">
                    <el-button :loading="analyzing" bg icon="Refresh" size="small" text type="primary" @click="analyze">
                      重新分析
                    </el-button>
                    <el-text size="small" type="info">改完标题 / 季 / 偏移 / 目录后点「预览」查看重命名结果</el-text>
                  </div>
                </div>
              </el-form-item>
            </template>
          </el-form>
        </div>
      </el-scrollbar>
    </div>
    <div class="action">
      <el-button :disabled="!data.torrent" bg
                 icon="Grid"
                 text
                 @click="collectionPreviewRef?.show">
        预览
      </el-button>
      <el-button :disabled="!data.torrent"
                 :loading="startLoading"
                 bg
                 icon="Check"
                 text
                 type="primary" @click="start">
        开始
      </el-button>
    </div>
  </el-dialog>
</template>

<script setup>
import {computed, ref} from "vue";
import {ElMessage, ElMessageBox} from "element-plus";

const emit = defineEmits(['added'])
import BgmView from "./BgmView.vue";
import ExcludeView from "@/view/config/ExcludeView.vue";
import CollectionPreviewView from "./CollectionPreviewView.vue";
import CustomTagsView from "@/view/config/CustomTagsView.vue";
import {aniData} from "@/js/ani.js";
import * as http from "@/js/http.js";
import {getBgmTitle} from "@/js/http.js";
import UploadView from "@/view/custom/UploadView.vue";

let start = () => {
  startLoading.value = true
  http.startCollection(data.value)
      .then((res) => {
        // 关联的番剧已写入订阅列表，通知父组件刷新订阅列表
        emit('added')
        ElMessageBox.confirm(
            res.message,
            'success',
            {
              confirmButtonText: 'OK',
              confirmButtonClass: 'is-text is-has-bg el-button--primary',
              type: 'success',
              center: true,
              showCancelButton: false
            }
        )
      })
      .finally(() => {
        startLoading.value = false
      })
}

let downloadPathLoading = ref(false)
let downloadPath = () => {
  downloadPathLoading.value = true
  let newAni = JSON.parse(JSON.stringify(data.value.ani))
  newAni.customDownloadPath = false
  http.downloadPath(newAni)
      .then(res => {
        data.value.ani.customDownloadPathTemplate = res.data.downloadPath
      })
      .finally(() => {
        downloadPathLoading.value = false
      })
}

let collectionPreviewRef = ref()

let startLoading = ref(false);

let bgmRef = ref()

let rssButtonLoading = ref(false)
let loading = ref(false)

let bgmAdd = (bgm) => {
  loading.value = true
  data.value.show = false
  data.value.torrent = ''
  data.value.filename = ''
  http.getAniBySubjectId(bgm['id'])
      .then((res) => {
        data.value.ani = res.data
        data.value.ani.subgroup = '未知字幕组'
        data.value.ani.customEpisode = true
        data.value.show = true
        data.value.ani.match = []
        data.value.ani.exclude = ['^(SPs?|CDs|Scans|PV|menu)/', 'Fonts|NCED|NCOP|迷你动画']
      })
      .finally(() => {
        loading.value = false
      })
}

let uploadCallback = (res, file) => {
  data.value.filename = file.name
  data.value.torrent = res.data
  // 获取字幕组
  http.getCollectionSubgroup(data.value)
      .then(res => {
        data.value.ani.subgroup = res.data
        if (res.data !== '未知字幕组') {
          ElMessage.success(`字幕组已更新为 ${res.data}`)
        }
      })
  // 结构分析：多季 / 剧场版混合的种子会返回多个分段，由用户逐段确认
  analyze()
}

/**
 * 合集结构分析。只上传结构字段，分段自带的 items 不回传（体积大且用不上）。
 */
let analyze = () => {
  if (!data.value.torrent) {
    data.value.mode = ''
    data.value.segments = []
    data.value.warnings = []
    return
  }
  analyzing.value = true
  http.analyzeCollection({torrent: data.value.torrent, ani: data.value.ani})
      .then(res => {
        let segments = (res.data?.segments || []).map(seg => ({
          id: seg.id,
          name: seg.name,
          kind: seg.kind,
          season: seg.season,
          offset: seg.offset,
          title: seg.title,
          scope: seg.scope,
          ova: seg.ova,
          downloadPath: seg.downloadPath,
          fileCount: (seg.items || []).length,
          sample: (seg.items || [])[0]?.reName || '',
        }))
        data.value.mode = res.data?.mode || 'SINGLE'
        data.value.segments = segments
        data.value.warnings = res.data?.warnings || []
        if (segments.length > 1) {
          ElMessage.success(`检测到 ${segments.length} 个部分，请确认季 / 偏移 / 目录`)
        }
      })
      .catch(() => {
        data.value.mode = ''
        data.value.segments = []
        data.value.warnings = []
      })
      .finally(() => analyzing.value = false)
}

let analyzing = ref(false)

let splitMode = computed(() => data.value.mode === 'SPLIT' && data.value.segments.length > 0)

let segmentSummary = computed(() => (data.value.segments || []).map(segmentKind).join(' + '))

let segmentKind = (seg) => {
  if (seg.ova) {
    return seg.name || '剧场版'
  }
  return `第${seg.season ?? 0}季`
}

let data = ref({
  filename: '',
  torrent: '',
  ani: aniData,
  show: false,
  // 合集结构分析结果：mode=SPLIT 时按 segments 分别设置并分别建订阅
  mode: '',
  segments: [],
  warnings: [],
})

// 合集来源：种子文件（base64）或磁力链接，后端按 torrent 内容自动识别
let torrentSource = ref('file')

let clearTorrent = () => {
  data.value.filename = ''
  data.value.torrent = ''
  data.value.mode = ''
  data.value.segments = []
  data.value.warnings = []
}

// 切换来源时清空已选内容，避免把上一种来源的数据带过去提交
let clearTorrentSource = () => {
  clearTorrent()
}

let dialogVisible = ref(false)

let show = () => {
  data.value.show = false
  data.value.ani.title = ''
  data.value.torrent = ''
  data.value.filename = ''
  data.value.mode = ''
  data.value.segments = []
  data.value.warnings = []
  torrentSource.value = 'file'
  dialogVisible.value = true
}

let getBgmNameLoading = ref(false)

let getBgmName = () => {
  getBgmNameLoading.value = true
  getBgmTitle(data.value.ani)
      .then(res => {
        data.value.ani.title = res.data
      })
      .finally(() => {
        getBgmNameLoading.value = false
      })
}

let getThemoviedbNameLoading = ref(false)

let getThemoviedbName = () => {
  if (!data.value.ani.title.length) {
    return
  }

  getThemoviedbNameLoading.value = true
  http.getThemoviedbName(data.value.ani)
      .then(res => {
        ElMessage.success(res.message)
        data.value.ani['themoviedbName'] = res.data['themoviedbName']
        data.value.ani['tmdb'] = res.data['tmdb']
      })
      .finally(() => {
        getThemoviedbNameLoading.value = false
      })
}


defineExpose({show})

</script>

<style scoped>
.change-title-button {
  width: 100%;
  justify-content: end;
  display: flex;
  margin-top: 12px;
}

.form-item-flex {
  width: 100%;
  display: flex;
  justify-content: end;
}

.torrent-source {
  width: 100%;
}

.torrent-input {
  margin-top: 8px;
}

.filename {
  max-width: 300px;
  color: var(--el-color-info);
}

.action {
  width: 100%;
  display: flex;
  justify-content: space-between;
  margin-top: 10px;
}

.segment-row {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px;
  margin-bottom: 8px;
}

.segment-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.segment-sample {
  display: block;
  margin-top: 4px;
  word-break: break-all;
}

.segment-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
</style>
