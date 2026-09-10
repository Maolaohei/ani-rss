<template>
  <el-form @submit.prevent label-width="auto"
           class="full-width">
    <el-form-item label="对外接口">
      <div class="full-width">
        <el-button icon="DocumentCopy" @click="copyEmbyApi">复制 emby 自动点格子 api</el-button>
        <el-button icon="DocumentCopy" @click="copyIcs">复制 ics</el-button>
        <br>
        <el-text class="mx-1" size="small">
          需要先在「登录设置」生成 Api Key；复制的地址里已带该 Key，请不要外发
        </el-text>
      </div>
    </el-form-item>
    <el-form-item label="Mikan 站点地址">
      <el-input v-model:model-value="props.config.mikanHost" placeholder="https://mikanani.me"/>
    </el-form-item>
    <el-form-item label="Github Token">
      <div class="full-width">
        <div>
          <el-input v-model="props.config['githubToken']" clearable placeholder="在此处输入 Github Token"
                    show-password/>
        </div>
        <div style="justify-content: end;margin-top: 4px;" class="flex">
          <el-button :icon="Github" bg
                     @click="openUrl('https://github.com/login/oauth/authorize?client_id=Ov23li1dD89l7iGKhYa3&redirect_uri=https://github-app.wushuo.top/&scope=read:user')">
            获取GithubToken
          </el-button>
        </div>
      </div>
    </el-form-item>
    <el-form-item label="最大日志条数">
      <div>
        <div class="width-150">
          <el-select v-model:model-value="props.config.logsMax">
            <el-option v-for="it in [128,256,512]" :key="it" :label="it" :value="it"/>
          </el-select>
        </div>
        <div>
          <el-text class="mx-1" size="small">
            界面日志面板保留的条数，上限 512（超出会被自动收敛回 512）
          </el-text>
        </div>
      </div>
    </el-form-item>
    <el-form-item label="自动更新">
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
    </el-form-item>
    <el-form-item label="DEBUG">
      <el-switch v-model:model-value="props.config.debug"/>
    </el-form-item>
    <el-form-item label="缓存">
      <div class="full-width">
        <div>
          <el-button :loading="clearCacheLoading" bg icon="Delete" @click="clearCache">清理封面与图片缓存</el-button>
        </div>
        <div>
          <el-text class="mx-1" size="small">
            清理现在不被使用的封面/图片缓存（下次浏览会重新联网下载），不含设置、订阅与下载记录
          </el-text>
        </div>
      </div>
    </el-form-item>
    <el-form-item label="自动备份配置">
      <div>
        <el-switch v-model="props.config['configBackup']"/>
        <br>
        <el-input-number v-model="props.config['configBackupDay']" :min="1">
          <template #suffix>
            <span>天</span>
          </template>
        </el-input-number>
        <br>
        <el-text class="mx-1" size="small">
          每天打包一次到 <code>config/backup/YYYY-MM-DD.zip</code>，只保留最近这么多天，过期自动清理；
          导出/导入入口在「基本设置 → 备份」
        </el-text>
      </div>
    </el-form-item>
    <el-form-item label="开机自启">
      <el-switch v-model="props.config['autoStart']"/>
    </el-form-item>
    <el-form-item label="网络协议">
      <div class="full-width">
        <el-select v-model="props.config['networkPrefer']" style="width: 200px;">
          <el-option label="系统默认" value=""/>
          <el-option label="IPv4 优先" value="ipv4"/>
          <el-option label="IPv6 优先" value="ipv6"/>
        </el-select>
        <div style="margin-top: 4px;">
          <!-- 与后端返回文案对齐：该设置不会自动重启，需手动重启才生效 -->
          <el-alert
              class="network-prefer-alert"
              type="warning"
              :closable="false"
              show-icon
              title="保存后需手动重启服务才生效（海外 VPS 推荐 IPv4 优先）"
          />
        </div>
      </div>
    </el-form-item>
  </el-form>
</template>

<script setup>
import {ElMessage, ElMessageBox} from "element-plus";
import {ref} from "vue";
import * as http from "@/js/http.js";
import {Github} from "@vicons/fa";
import {copyText, getBaseUrl} from "@/js/global.js";

let openUrl = (url) => window.open(url)

let clearCacheLoading = ref(false)
let clearCache = async () => {
  try {
    // 清理范围包含封面缓存目录，此前一次误点即丢失且无任何说明
    await ElMessageBox.confirm(
        '将清理未被使用的图片/封面缓存（下次浏览会重新下载，需要网络与时间），是否继续？',
        '清理缓存',
        {type: 'warning', confirmButtonText: '清理', cancelButtonText: '取消'}
    )
  } catch (e) {
    return
  }
  clearCacheLoading.value = true
  http.clearCache()
      .then(res => {
        ElMessage.success(res.message);
      })
      .finally(() => {
        clearCacheLoading.value = false
      })
}

const hasApiKey = () => Boolean(props.config.apiKey)

let copyEmbyApi = () => {
  if (!hasApiKey()) {
    ElMessage.warning('请先在「登录设置」中生成 Api Key')
    return
  }
  let url = `${getBaseUrl()}api/embyWebHook?api-key=${props.config.apiKey}`;
  copy(url)
}

let copyIcs = () => {
  if (!hasApiKey()) {
    ElMessage.warning('请先在「登录设置」中生成 Api Key')
    return
  }
  let url = `${getBaseUrl()}api/calendar.ics?api-key=${props.config.apiKey}`;
  copy(url)
}

let copy = async (v) => {
  await copyText(v)
}

let props = defineProps(['config'])
</script>

