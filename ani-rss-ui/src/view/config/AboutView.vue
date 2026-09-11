<template>
  <div class="flex-center about-container">
    <div class="flex about-header">
      <img alt="icon.svg" height="80" src="/public/icon.svg" width="80"/>
      <div>
        <h1>ANI-RSS</h1>
        <el-tooltip
            :disabled="!props.config.buildInfo"
            :content="props.config.buildInfo"
            placement="right">
          <el-text class="mx-1 cursor-pointer" size="small" @click="copyVersion">
            &nbsp;v{{ props.config.version }}
            <el-icon>
              <DocumentCopy/>
            </el-icon>
          </el-text>
        </el-tooltip>
        <br>
        <el-text v-if="props.config.buildInfo" size="small" type="info" class="build-info">
          构建信息：{{ props.config.buildInfo }}
        </el-text>
      </div>
    </div>
    <div class="flex about-buttons">
      <div id="button-list">
        <el-button :icon="Github" bg text type="info" @click="openUrl('https://github.com/wushuo894/ani-rss')">GitHub
        </el-button>
        <el-button :icon="Book" bg text type="info" @click="openUrl('https://docs.wushuo.top')">使用文档</el-button>
        <el-button :icon="Telegram" bg text type="info" @click="openUrl('https://t.me/ani_rss')">TG群</el-button>
      </div>
    </div>
    <div v-loading.fullscreen.lock="actionLoading" class="flex about-actions">
      <PopconfirmView title="你确定要退出吗?" @confirm="logout">
        <template #reference>
          <el-button type="danger" bg text icon="Back">
            退出
          </el-button>
        </template>
      </PopconfirmView>
      <div class="about-action-spacer"></div>
      <PopconfirmView title="重启会中断正在进行的 RSS 扫描/下载；若非 Docker 或进程守护方式运行，需要你手动把程序拉起来。确定重启？"
                      @confirm="stop(0)">
        <template #reference>
          <el-button bg icon="RefreshRight" text type="warning">重启</el-button>
        </template>
      </PopconfirmView>
      <div class="about-action-spacer"></div>
      <PopconfirmView title="关闭会立即停止所有任务，且不会自动拉起，需要你手动启动。确定关闭？" @confirm="stop(1)">
        <template #reference>
          <el-button bg icon="SwitchButton" text type="danger">关闭</el-button>
        </template>
      </PopconfirmView>
      <div class="about-action-spacer"></div>
      <el-badge :hidden="!about.update" class="item" value="new">
        <el-button :loading="about.version.length < 1" bg icon="Top" text type="success" @click="dialogVisible = true">
          更新
        </el-button>
      </el-badge>
      <div class="about-action-spacer"></div>
      <PopconfirmView title="将从 Fork 仓库强制拉取最新版本并重启，确认更新？" @confirm="forkUpdateAction">
        <template #reference>
          <el-button bg icon="Upload" text type="primary">
            Fork更新
          </el-button>
        </template>
      </PopconfirmView>
    </div>
  </div>
  <el-dialog v-if="dialogVisible" v-model="dialogVisible" align-center center title="版本更新"
             class="about-dialog">
    <div v-if="about.update">
      <div>
        <SettingsItem label="版本号">
          <el-link type="default" :href="`https://github.com/wushuo894/ani-rss/releases/tag/v${about.latest}`"
                   target="_blank">
            {{ about.latest }}
          </el-link>
        </SettingsItem>
        <SettingsItem label="发布时间">
          {{ about.date }}
        </SettingsItem>
        <SettingsItem label="大小">
          {{ about['formatSize'] }}
        </SettingsItem>
        <SettingsItem label="更新内容">
          <el-scrollbar class="about-scrollbar" :always="true">
            <div class="markdown-body about-markdown" v-html="md.render(about.markdownBody)"></div>
            <el-alert
                show-icon
                :closable="false"
                class="about-alert"
                title="更新依赖于Github, 需要网络环境支持"
                type="info"
            />
          </el-scrollbar>
        </SettingsItem>
      </div>
    </div>
    <div v-else>
      <el-empty description="无更新"></el-empty>
    </div>
    <div class="flex about-dialog-footer">
      <el-button bg text icon="Tickets"
                 @click="openUrl('https://docs.wushuo.top/history')"
                 type="primary">
        更新历史
      </el-button>
      <div>
        <el-button :disabled="!about.update" bg text icon="Check"
                   type="success" @click="update">
          开始更新
        </el-button>
        <el-button bg icon="Close" text @click="dialogVisible = false">取消</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import SettingsItem from "@/view/custom/SettingsItem.vue";
import {onMounted, ref} from "vue";
import {ElMessage, ElText} from "element-plus";
import {DocumentCopy} from "@element-plus/icons-vue";
import PopconfirmView from "@/view/custom/PopconfirmView.vue";
import {Book, Github, Telegram} from "@vicons/fa";

import markdownit from 'markdown-it'
import MarkdownItGitHubAlerts from 'markdown-it-github-alerts'
import 'markdown-it-github-alerts/styles/github-colors-light.css'
import 'markdown-it-github-alerts/styles/github-colors-dark-media.css'
import 'markdown-it-github-alerts/styles/github-base.css'

import {authorization, copyText} from "@/js/global.js";
import * as http from "@/js/http.js";

let md = markdownit({
  html: true,
  linkify: true
})

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  token.attrSet('target', '_blank') // 强制添加属性
  return self.renderToken(tokens, idx, options)
}

md.use(MarkdownItGitHubAlerts)

const actionLoading = ref(false)

/**
 * 重启/关闭：后端在 Windows exe 场景会返回 error（不支持重启），
 * 此前无论成败都先弹 success，之后再执行，属于"成功→失败反转"。
 * 这里先校验返回码，只有真正受理才提示并等待重连。
 */
const stop = (status) => {
  actionLoading.value = true
  http.stop(status)
      .then(res => {
        if (res.code !== 200) {
          ElMessage.error(res.message || '操作失败')
          return
        }
        ElMessage.success(res.message || '已受理，服务正在重启')
        setTimeout(() => {
          authorization.value = ''
          location.reload()
        }, 5000)
      })
      .catch(e => {
        ElMessage.error(e?.message || '操作失败')
      })
      .finally(() => {
        actionLoading.value = false
      })
}

const update = async () => {
  let sleep = ms => {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  actionLoading.value = true
  http.update()
      .then(async res => {
        ElMessage.success(res.message)
        for (let i = 0; i < 24; i++) {
          await sleep(5000)
          try {
            let pingRes = await http.ping()
            if (pingRes.code === 200) {
              authorization.value = ''
              location.reload()
              return
            }
          } catch (e) {
          }
        }
        ElMessage.error("重启时遇到错误")
      })
      .finally(() => {
        actionLoading.value = false
      })
}

const about = ref({
  'version': '',
  'latest': '',
  'update': false,
  'markdownBody': ''
})

onMounted(() => {
  http.about()
      .then(res => {
        about.value = res.data
      })
})

const forkUpdateAction = async () => {
  let sleep = ms => {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  actionLoading.value = true
  http.doForkUpdate()
      .then(async res => {
        ElMessage.success(res.message)
        for (let i = 0; i < 24; i++) {
          await sleep(5000)
          try {
            let pingRes = await http.ping()
            if (pingRes.code === 200) {
              authorization.value = ''
              location.reload()
              return
            }
          } catch (e) {
          }
        }
        ElMessage.error("重启时遇到错误")
      })
      .catch(e => {
        ElMessage.error(e?.message || '操作失败')
      })
      .finally(() => {
        actionLoading.value = false
      })
}

/**
 * 版本号此前是 cursor-pointer 但没有点击行为，用户会下意识去点
 */
let copyVersion = async () => {
  const info = [props.config.version, props.config.buildInfo].filter(Boolean).join(' / ')
  if (!info) {
    return
  }
  await copyText(info)
}

let logout = () => {
  authorization.value = ''
  location.reload()
}

let openUrl = (url) => window.open(url)

let dialogVisible = ref(false)
let props = defineProps(['config'])

</script>

<style scoped>
.about-container {
  width: 100%;
  flex-flow: column;
}

.about-header {
  margin-bottom: 12px;
  align-items: end;
}

.cursor-pointer {
  cursor: pointer;
}

.about-buttons {
  margin-bottom: 12px;
  align-items: center;
}

.about-actions {
  margin-bottom: 8px;
}

.about-action-spacer {
  margin: 6px;
}

.about-dialog {
  max-width: 500px;
}

.about-scrollbar {
  margin-bottom: 16px;
  max-height: 400px;
}

.about-markdown {
  width: 800px;
}

.about-alert {
  margin-top: 8px;
}

.about-dialog-footer {
  width: 100%;
  justify-content: space-between;
}

#button-list > button {
  margin-top: 12px;
  margin-left: 0;
}

#button-list > button {
  margin-right: 12px;
}

#button-list > button:last-child {
  margin-right: 0;
}

</style>
