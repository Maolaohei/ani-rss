<template>
  <el-form @submit.prevent label-width="auto"
           class="full-width">
    <el-form-item label="下载工具">
      <el-select v-model:model-value="props.config.downloadToolType">
        <el-option v-for="item in downloadSelect"
                   :key="item"
                   :label="item"
                   :value="item"/>
      </el-select>
    </el-form-item>
    <el-form-item label="地址">
      <el-input v-model:model-value="props.config.downloadToolHost"
                placeholder="http://192.168.1.x:8080"
                @blur="normalizeHost"/>
    </el-form-item>
    <template v-if="props.config.downloadToolType === 'qBittorrent'">
      <el-form-item label="用户名">
        <el-input v-model.trim="props.config.downloadToolUsername"
                  placeholder="qBittorrent ≤5.1 用户名（≥5.2 可留空）"
                  autocomplete="off"/>
      </el-form-item>
      <el-form-item label="密码 / ApiKey">
        <el-input v-model.trim="props.config.downloadToolPassword" placeholder="密码 或 qbt_xxxx" show-password>
          <template #prefix>
            <el-icon class="el-input__icon">
              <Key/>
            </el-icon>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item>
        <el-alert show-icon type="info" :closable="false">
          <template #title>
            qBittorrent ≥5.2 可在密码栏填 Bearer ApiKey（Tools → Options → Web UI → API Key）；旧版（≤5.1）填写用户名密码即可，自动兼容。
          </template>
        </el-alert>
      </el-form-item>
    </template>
    <el-form-item v-else-if="props.config.downloadToolType === 'Aria2'" label="RPC 密钥">
      <el-input v-model.trim="props.config.downloadToolPassword" placeholder="" show-password>
        <template #prefix>
          <el-icon class="el-input__icon">
            <Key/>
          </el-icon>
        </template>
      </el-input>
    </el-form-item>
    <template v-else-if="props.config.downloadToolType === 'OpenList'">
      <el-form-item label="Token">
        <el-input v-model:model-value="props.config.downloadToolPassword" placeholder="OpenList-xxxxxx" show-password>
          <template #prefix>
            <el-icon class="el-input__icon">
              <Key/>
            </el-icon>
          </template>
        </el-input>
        <br/>
        <el-text class="mx-1" size="small">
          请设置好 <strong>保存位置</strong> 才能通过测试<br/>
          请在 OpenList -> 设置-> 其他 -> 配置临时目录<br/>
          支持离线下载到 115、PikPak、迅雷云盘
        </el-text>
        <template v-if="props.config.delayedDownload < 1">
          <br/>
          <el-alert show-icon type="warning" :closable="false">
            <template #title>
              未设置 <strong>延迟下载</strong>
            </template>
          </el-alert>
        </template>
      </el-form-item>
      <el-form-item label="Driver">
        <el-select v-model="props.config['provider']" class="width-150">
          <el-option v-for="it in offlineList" :key="it.label" :label="it.label" :value="it.value"/>
        </el-select>
      </el-form-item>
      <el-form-item label="重试次数">
        <div>
          <el-input-number v-model="props.config['alistDownloadRetryNumber']" :min="-1"/>
          <br>
          <el-text class="mx-1" size="small">
            设置为 -1 将一直进行重试
          </el-text>
        </div>
      </el-form-item>
      <el-form-item label="离线超时">
        <el-input-number v-model:model-value="props.config['alistDownloadTimeout']" :min="1">
          <template #suffix>
            <span>分钟</span>
          </template>
        </el-input-number>
      </el-form-item>
      <el-form-item label="新种子下载等待">
        <div>
          <el-input-number v-model:model-value="props.config['newTorrentWaitHours']" :min="0">
            <template #suffix>
              <span>小时</span>
            </template>
          </el-input-number>
          <br/>
          <el-text class="mx-1" size="small">
            发布时间距今不足该值的种子暂缓下载：新种子云端常无人做种，立即提交只会离线超时失败并反复重提；0 为关闭
          </el-text>
        </div>
      </el-form-item>
      <el-form-item label="115 云下载路径">
        <el-input v-model:model-value="props.config['alistCloudDownloadDir']" placeholder="/云下载（留空自动发现）"/>
        <br/>
        <el-text class="mx-1" size="small">
          115 离线完成后的文件可能落在根目录「云下载」而非目标路径，填此路径兜底扫描并自动移动/重命名；留空自动发现
        </el-text>
      </el-form-item>
    </template>
    <template v-else>
      <el-form-item label="用户名">
        <el-input v-model:model-value="props.config.downloadToolUsername" placeholder="username"
                  autocomplete="new-password">
          <template #prefix>
            <el-icon class="el-input__icon">
              <User/>
            </el-icon>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item label="密码">
        <el-input v-model:model-value="props.config.downloadToolPassword" placeholder="password" show-password
                  autocomplete="new-password">
          <template #prefix>
            <el-icon class="el-input__icon">
              <Key/>
            </el-icon>
          </template>
        </el-input>
      </el-form-item>
    </template>
    <el-form-item label="连接测试">
      <div class="download-test-button">
        <el-button @click="downloadLoginTest" bg text :loading="downloadLoginTestLoading" icon="Odometer">测试连接
        </el-button>
        <el-text class="mx-1" size="small" type="info">使用当前表单（未保存）的值，测完记得点确定保存</el-text>
      </div>
      <el-alert
          v-if="downloadTestResult"
          class="download-alert"
          :type="downloadTestResult.ok ? 'success' : 'error'"
          show-icon
          :closable="false"
          :title="downloadTestResult.ok ? `连接成功：${downloadTestResult.message}` : `连接失败：${downloadTestResult.message}`"
      />
    </el-form-item>
    <el-form-item label="保存位置">
      <div class="full-width">
        <el-input v-model:model-value="props.config['downloadPathTemplate']"/>
        <el-alert
            v-if="pathTemplateIssue(props.config['downloadPathTemplate'])"
            class="download-alert"
            type="warning"
            show-icon
            :closable="false"
            :title="pathTemplateIssue(props.config['downloadPathTemplate'])"
        />
      </div>
    </el-form-item>
    <el-form-item label="剧场版保存位置">
      <div class="full-width">
        <el-input v-model:model-value="props.config['ovaDownloadPathTemplate']"/>
        <el-alert
            v-if="pathTemplateIssue(props.config['ovaDownloadPathTemplate'])"
            class="download-alert"
            type="warning"
            show-icon
            :closable="false"
            :title="pathTemplateIssue(props.config['ovaDownloadPathTemplate'])"
        />
      </div>
    </el-form-item>
    <el-form-item label="自动删除">
      <div>
        <el-switch v-model:model-value="props.config.delete"/>
        <br>
        <el-text class="mx-1" size="small">
          自动删除已完成的任务
          <br>
          如果同时开启了 <strong>备用rss功能</strong> 将会自动删除对应洗版视频, 以实现 <strong>主rss</strong> 的替换
        </el-text>
        <br>
        <el-checkbox v-model:model-value="props.config.awaitStalledUP"
                     :disabled="!props.config.delete"
                     label="等待做种完毕"/>
        <br>
        <el-checkbox v-model:model-value="props.config.deleteStandbyRSSOnly"
                     :disabled="!props.config.delete"
                     label="仅在主RSS更新后删除备用RSS"/>
        <br>
        <el-text class="mx-1" size="small">
          <strong>主RSS</strong> 将 <span class="download-danger-text">不会自动删除</span>，仅在其更新后删除对应备用RSS的任务与文件
        </el-text>
      </div>
    </el-form-item>
    <el-form-item label="失败重试次数">
      <el-input-number v-model:model-value="props.config['downloadRetry']" :max="100" :min="3"/>
    </el-form-item>
    <el-form-item label="同时下载限制">
      <div>
        <el-input-number v-model:model-value="props.config.downloadCount" :min="0"/>
        <div>
          <el-text class="mx-1" size="small">
            设置为 0 表示不做限制
          </el-text>
        </div>
      </div>
    </el-form-item>
    <el-form-item label="延迟下载">
      <el-input-number v-model:model-value="props.config.delayedDownload" :min="0">
        <template #suffix>
          <span>分钟</span>
        </template>
      </el-input-number>
    </el-form-item>
    <el-form-item label="优先保留">
      <div class="full-width">
        <el-switch v-model:model-value="props.config.priorityKeywordsEnable"/>
        <div>
          <el-text class="mx-1" size="small">
            启用多文件种子的文件优先保留过滤
          </el-text>
        </div>
        <div v-if="props.config.priorityKeywordsEnable">
          <PrioKeys
              v-model:keywords="props.config.priorityKeywords"
              :import-global="false"
              :show-text="true"
          />
        </div>
      </div>
    </el-form-item>
    <el-form-item label="自定义标签">
      <custom-tags :config="props.config"/>
    </el-form-item>
    <el-collapse v-model="activeName">
      <el-collapse-item name="qBittorrent" title="qBittorrent 设置">
        <QBittorrent v-if="activeName.indexOf('qBittorrent') > -1" :config="props.config"/>
      </el-collapse-item>
    </el-collapse>
  </el-form>
</template>

<script setup>
import {ref} from "vue";
import {ElMessage, ElText} from "element-plus";
import {Key, User} from "@element-plus/icons-vue";
import QBittorrent from "@/config/download/qBittorrent.vue";
import PrioKeys from "@/config/PrioKeys.vue";
import CustomTags from "@/config/CustomTags.vue";
import * as http from "@/js/http.js";

const downloadSelect = ref([
  'qBittorrent',
  'Transmission',
  'Aria2',
  'OpenList'
])

const offlineList = ref([
  {
    label: '115 开放平台',
    value: '115 Open'
  },
  {
    label: '115 网盘',
    value: '115 Cloud'
  },
  {
    label: '123 开放平台',
    value: '123 Open'
  },
  {
    label: '123 网盘',
    value: '123Pan'
  },
  {
    label: '迅雷',
    value: 'Thunder'
  },
  {
    label: 'PikPak',
    value: 'PikPak'
  }
])

const downloadLoginTestLoading = ref(false)
const downloadTestResult = ref(null)

const downloadLoginTest = () => {
  downloadLoginTestLoading.value = true
  downloadTestResult.value = null
  // 测试用的是当前表单（可能尚未保存）的值
  http.downloadLoginTest(props.config)
      .then(res => {
        downloadTestResult.value = {
          ok: true,
          message: res.message || '连接成功'
        }
      })
      .catch(e => {
        downloadTestResult.value = {
          ok: false,
          message: e?.message || '连接失败，请检查地址/端口/账号'
        }
      })
      .finally(() => {
        downloadLoginTestLoading.value = false
      })
}

/**
 * 与后端一致的路径模板变量白名单。
 * 参考 DownloadService.getDownloadPath / RenameUtil.replaceField 实际替换的字段。
 */
const PATH_TEMPLATE_VARS = [
  'title', 'themoviedbName', 'subgroup', 'jpTitle',
  'letter', 'year', 'tmdbYear', 'month', 'monthFormat',
  'season', 'seasonFormat', 'bgmId', 'tmdbid',
  'quarter', 'quarterFormat', 'quarterName'
]

const PATH_TEMPLATE_VAR_SET = new Set(PATH_TEMPLATE_VARS)

/**
 * 路径模板校验：返回 null 表示没问题，否则返回**具体原因**（不再只给一句"未按模版填写"）。
 * 1. 未识别的变量会被原样保留在路径里，属于典型配错；
 * 2. `${` 之前的静态前缀是下载根，缺失会让根目录变成进程工作目录。
 */
const pathTemplateIssue = (path) => {
  const value = (path || '').trim()
  if (!value) {
    return '路径模板为空，将使用系统默认下载位置'
  }
  const unknown = []
  const re = /\$\{([^}]*)\}/g
  let match
  while ((match = re.exec(value)) !== null) {
    const name = match[1]
    if (!PATH_TEMPLATE_VAR_SET.has(name)) {
      unknown.push(name)
    }
  }
  if (unknown.length) {
    return `存在无法识别的变量：${unknown.map(it => '${' + it + '}').join('、')}（可用变量：${PATH_TEMPLATE_VARS.join('、')}）`
  }
  const staticRoot = value.split('${')[0]
  if (!staticRoot.trim()) {
    return '模板以变量开头，下载根目录会变成程序运行目录；建议前面补一个绝对路径，例如 /media 或 D:\\Media'
  }
  return null
}

/**
 * 下载器地址即时校验：只提示"缺少协议"这类最常见配错，并回显归一化结果。
 */
const normalizeHost = () => {
  const raw = (props.config.downloadToolHost || '').trim()
  if (!raw) {
    return
  }
  if (!/^https?:\/\//i.test(raw)) {
    const fixed = `http://${raw}`
    props.config.downloadToolHost = fixed
    ElMessage.warning(`地址已自动补全协议为 ${fixed}，如实际是 https 请手动修改`)
  }
}

let activeName = ref([])

let props = defineProps(['config'])
</script>

<style scoped>
.download-test-button {
  display: flex;
  width: 100%;
  justify-content: end;
}

.download-alert {
  margin-top: 8px;
}

.download-danger-text {
  color: red;
}
</style>
