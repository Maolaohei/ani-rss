<template>
  <SettingsItem label="Api">
    <el-button icon="DocumentCopy" @click="copyEmbyApi">复制 emby 自动点格子 api</el-button>
    <el-button icon="DocumentCopy" @click="copyIcs">复制 ics</el-button>
  </SettingsItem>
  <SettingsItem label="Mikan">
    <el-input v-model:model-value="props.config.mikanHost" placeholder="https://mikanani.me"/>
  </SettingsItem>
  <SettingsItem label="GithubToken">
    <div class="full-width">
      <div>
        <el-input v-model="props.config['githubToken']" clearable placeholder="在此处输入GithubToken"/>
      </div>
      <div style="justify-content: end;" class="flex margin-top-4">
        <el-button :icon="Github" bg
                   @click="openUrl('https://github.com/login/oauth/authorize?client_id=Ov23li1dD89l7iGKhYa3&redirect_uri=https://github-app.wushuo.top/&scope=read:user')">
          获取GithubToken
        </el-button>
      </div>
    </div>
  </SettingsItem>
  <SettingsItem label="最大日志条数">
    <div class="width-150">
      <el-select v-model:model-value="props.config.logsMax">
        <el-option v-for="it in [128,256,512]" :key="it" :label="it" :value="it"/>
      </el-select>
    </div>
  </SettingsItem>
  <SettingsItem label="自动更新">
    <div class="full-width">
      <div>
        <el-switch v-model:model-value="props.config.autoUpdate"/>
      </div>
      <div>
        <el-text class="mx-1" size="small">
          每天 06:00 自动更新程序
        </el-text>
      </div>
    </div>
  </SettingsItem>
  <SettingsItem label="DEBUG">
    <el-switch v-model:model-value="props.config.debug"/>
  </SettingsItem>
  <SettingsItem label="缓存">
    <div class="full-width">
      <div>
        <el-button :loading="clearCacheLoading" bg icon="Delete" @click="clearCache">清理</el-button>
      </div>
      <div>
        <el-text class="mx-1" size="small">
          清理现在不被使用的缓存
        </el-text>
      </div>
    </div>
  </SettingsItem>
  <SettingsItem label="自动备份配置">
    <div>
      <el-switch v-model="props.config['configBackup']"/>
      <br>
      <el-input-number v-model="props.config['configBackupDay']" :min="1">
        <template #suffix>
          <span>天</span>
        </template>
      </el-input-number>
    </div>
  </SettingsItem>
  <SettingsItem label="开机自启">
    <el-switch v-model="props.config['autoStart']"/>
  </SettingsItem>
  <SettingsItem label="网络协议">
    <div class="full-width">
      <el-select v-model="props.config['networkPrefer']" style="width: 200px;">
        <el-option label="系统默认" value=""/>
        <el-option label="IPv4 优先" value="ipv4"/>
        <el-option label="IPv6 优先" value="ipv6"/>
      </el-select>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small">
          部分网络环境下 IPv6 解析/连接会拖慢 RSS 抓取与 TMDB 刮削，可在此强制优先 IPv4；修改后需手动重启程序生效
        </el-text>
      </div>
    </div>
  </SettingsItem>
  <SettingsItem label="RSS 并发度">
    <div class="full-width">
      <div class="width-150">
        <el-input-number v-model="props.config['rssConcurrency']" :min="1" :max="8"/>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small">
          一轮扫描同时处理的订阅数，默认 3。调大可缩短大订阅量的全量扫描时间，但会同时给下载器与源站加压；
          订阅优先级（编辑订阅 → 优先级）决定谁先被扫描。
        </el-text>
      </div>
    </div>
  </SettingsItem>
  <SettingsItem label="磁盘空间监控">
    <div class="full-width">
      <div>
        <el-switch v-model="props.config['diskMonitor']"/>
      </div>
      <div class="margin-top-4 flex flex-wrap gap-8">
        <el-input-number v-model="props.config['diskWarnPercent']" :min="50" :max="99">
          <template #prefix>预警</template>
          <template #suffix><span>%</span></template>
        </el-input-number>
        <el-input-number v-model="props.config['diskCheckIntervalMinutes']" :min="5">
          <template #prefix>间隔</template>
          <template #suffix><span>分钟</span></template>
        </el-input-number>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small">
          周期探测下载目录 / 剧场版目录 / 完结迁移目录 / 配置目录的可用空间，越线时推送「系统通知」。
          需要在通知渠道里勾选「系统通知」才能收到。
        </el-text>
      </div>
    </div>
  </SettingsItem>
  <SettingsItem label="追番周报">
    <div class="full-width">
      <div>
        <el-switch v-model="props.config['weeklyReport']"/>
      </div>
      <div class="margin-top-4 flex flex-wrap gap-8">
        <el-input-number v-model="props.config['weeklyReportIntervalHours']" :min="1">
          <template #prefix>每</template>
          <template #suffix><span>小时</span></template>
        </el-input-number>
        <el-checkbox v-model="props.config['weeklyReportAutoRetry']" label="有漏集时自动补种"/>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small">
          汇总本周新增 / 失败 / 漏集并推送。自动补种会走统一刷新入口，可在任务页观察与取消。
        </el-text>
      </div>
    </div>
  </SettingsItem>
  <SettingsItem label="事件 Webhook">
    <div class="full-width">
      <el-input v-model="props.config['eventWebhookUrl']" clearable
                placeholder="https://example.com/hook（留空不发送）"/>
      <div class="margin-top-4">
        <el-input v-model="props.config['eventWebhookHeader']" clearable
                  placeholder="鉴权头，如 Authorization: Bearer xxx（可空）"/>
      </div>
      <div class="margin-top-4">
        <el-input v-model="props.config['eventWebhookTypes']" clearable
                  placeholder="事件类型，逗号分隔；留空=全部，ALL=全部"/>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small">
          发送结构化 JSON 事件（下载完成 / 失败 / 订阅增删 / 轮次结束 / 磁盘预警），
          供 HomeAssistant、n8n、自建看板直接消费。可用类型：
          DOWNLOAD_START、DOWNLOAD_END、DOWNLOAD_FAILED、SUBSCRIPTION_ADDED、SUBSCRIPTION_DELETED、
          SUBSCRIPTION_ENABLED_CHANGED、RSS_ROUND_FINISHED、OMIT_DETECTED、DISK_WARNING。
        </el-text>
      </div>
    </div>
  </SettingsItem>
  <SettingsItem label="字幕自动获取">
    <div class="full-width">
      <div>
        <el-switch v-model="props.config['subtitleAutoFetch']"/>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small">
          默认关闭。开启后由部署方接入字幕源；未接入时「工具 → 系统自检」与订阅的字幕扫描仍可用来
          发现缺字幕的剧集，并支持手动就地附加字幕。
        </el-text>
      </div>
    </div>
  </SettingsItem>
</template>

<script setup>
import SettingsItem from "@/view/custom/SettingsItem.vue";
import {ElMessage, ElText} from "element-plus";
import {ref} from "vue";
import * as http from "@/js/http.js";
import {Github} from "@vicons/fa";
import {getBaseUrl} from "@/js/global.js";

let openUrl = (url) => window.open(url)

let clearCacheLoading = ref(false)
let clearCache = () => {
  clearCacheLoading.value = true
  http.clearCache()
      .then(res => {
        ElMessage.success(res.message);
      })
      .finally(() => {
        clearCacheLoading.value = false
      })
}

let copyEmbyApi = () => {
  let url = `${getBaseUrl()}api/embyWebHook?api-key=${props.config.apiKey}`;
  copy(url)
}

let copyIcs = () => {
  let url = `${getBaseUrl()}api/calendar.ics?api-key=${props.config.apiKey}`;
  copy(url)
}

let copy = (v) => {
  const input = document.createElement('input');
  input.value = v
  document.body.appendChild(input);
  input.select();
  document.execCommand('copy');
  document.body.removeChild(input);
  ElMessage.success('已复制')
}

let props = defineProps(['config'])
</script>

