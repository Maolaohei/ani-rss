<template>
  <div
      class="download-settings-form full-width">
    <section class="settings-section">
      <div class="settings-section-heading">
        <h3>下载器连接</h3>
      </div>
      <SettingsItem label="下载工具">
        <el-select v-model:model-value="props.config.downloadToolType">
          <el-option v-for="item in downloadSelect"
                     :key="item"
                     :label="item"
                     :value="item"/>
        </el-select>
      </SettingsItem>
      <SettingsItem label="地址">
        <el-input v-model.trim="props.config.downloadToolHost" placeholder="http://192.168.1.x:8080"
                  autocomplete="new-password"/>
      </SettingsItem>
      <SettingsItem v-if="props.config.downloadToolType === 'qBittorrent'" label="ApiKey">
        <el-input v-model.trim="props.config.downloadToolPassword" placeholder="qbt_xxxx" show-password
                  autocomplete="new-password">
          <template #prefix>
            <el-icon class="el-input__icon">
              <Key/>
            </el-icon>
          </template>
        </el-input>
        <div v-if="!props.config.downloadToolPassword.startsWith('qbt_')" class="full-width margin-top-4">
          <el-alert show-icon type="warning" :closable="false">
            <template #title>
              ApiKey 未正确配置
            </template>
          </el-alert>
        </div>
      </SettingsItem>
      <SettingsItem v-else-if="props.config.downloadToolType === 'Aria2'" label="RPC 密钥">
        <el-input v-model.trim="props.config.downloadToolPassword" placeholder="" show-password
                  autocomplete="new-password">
          <template #prefix>
            <el-icon class="el-input__icon">
              <Key/>
            </el-icon>
          </template>
        </el-input>
      </SettingsItem>
      <template v-else-if="props.config.downloadToolType === 'OpenList'">
        <SettingsItem label="Token">
          <el-input v-model.trim="props.config.downloadToolPassword" placeholder="OpenList-xxxxxx" show-password
                    autocomplete="new-password">
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
        </SettingsItem>
        <SettingsItem label="Driver">
          <el-select v-model="props.config['provider']" class="width-150">
            <el-option v-for="it in offlineList" :key="it.label" :label="it.label" :value="it.value"/>
          </el-select>
        </SettingsItem>
        <SettingsItem label="重试次数">
          <div>
            <el-input-number v-model="props.config['alistDownloadRetryNumber']" :min="-1"/>
            <br>
            <el-text class="mx-1" size="small">
              设置为 -1 将一直进行重试
            </el-text>
          </div>
        </SettingsItem>
        <SettingsItem label="离线超时">
          <el-input-number v-model:model-value="props.config['alistDownloadTimeout']" :min="1">
            <template #suffix>
              <span>分钟</span>
            </template>
          </el-input-number>
        </SettingsItem>
        <SettingsItem label="115 云下载路径">
          <div class="full-width">
            <el-input v-model:model-value="props.config['alistCloudDownloadDir']" placeholder="/云下载（留空自动发现）"/>
            <el-text class="mx-1" size="small">
              115 离线完成后的文件可能落在根目录「云下载」而非目标路径，填此路径兜底扫描并自动移动/重命名；留空自动发现
            </el-text>
          </div>
        </SettingsItem>
        <SettingsItem label="115 离线看门狗">
          <div class="full-width">
            <el-switch v-model:model-value="props.config['offline115Janitor']"/>
            <br>
            <el-text class="mx-1" size="small">
              提交离线任务被 <strong>10008 任务已存在</strong> 挡住时（115 上还留着同 hash 的历史任务），
              按 hash 查一次 115 离线队列：<strong>只有卡在 99% 永不完成的任务才删除后重提</strong>，
              其它任务一律不动。仅 OpenList 下载工具下生效
            </el-text>
          </div>
        </SettingsItem>
        <template v-if="props.config['offline115Janitor']">
          <SettingsItem label="115 挂载路径">
            <div class="full-width">
              <el-input v-model:model-value="props.config['offline115Mount']" placeholder="/115"/>
              <el-text class="mx-1" size="small">
                OpenList 里指向 115 的存储挂载路径，用于自动读取 cookie
              </el-text>
            </div>
          </SettingsItem>
          <SettingsItem label="卡死判定">
            <div>
              <el-input-number v-model:model-value="props.config['offline115StuckMinutes']" :min="10" :max="4320">
                <template #suffix>
                  <span>分钟</span>
                </template>
              </el-input-number>
              <br>
              <el-text class="mx-1" size="small">
                进度 ≥99% 且持续超过该时长仍未完成，才判定为卡死；低进度不判定，避免误删正常任务
              </el-text>
            </div>
          </SettingsItem>
          <SettingsItem label="配额告警">
            <div>
              <el-input-number v-model:model-value="props.config['offline115QuotaWarnPercent']" :min="1" :max="100">
                <template #suffix>
                  <span>%</span>
                </template>
              </el-input-number>
              <br>
              <el-text class="mx-1" size="small">
                115 离线配额使用率超过该比例时，自检会给出告警
              </el-text>
            </div>
          </SettingsItem>
          <SettingsItem label="115 Cookie">
            <div class="full-width">
              <el-input v-model.trim="props.config['offline115Cookie']" placeholder="留空则自动从 OpenList 读取"
                        show-password autocomplete="new-password"/>
              <el-text class="mx-1" size="small">
                仅当 OpenList 反查不到 cookie 时才需要手填
              </el-text>
            </div>
          </SettingsItem>
          <SettingsItem label="离线队列">
            <div class="full-width">
              <el-button size="small" :loading="offline115Loading" @click="queryOffline115">
                查询 115 离线队列
              </el-button>
            </div>
          </SettingsItem>
        </template>
      </template>
      <template v-else>
        <SettingsItem label="用户名">
          <el-input v-model.trim="props.config.downloadToolUsername" placeholder="username"
                    autocomplete="new-password">
            <template #prefix>
              <el-icon class="el-input__icon">
                <User/>
              </el-icon>
            </template>
          </el-input>
        </SettingsItem>
        <SettingsItem label="密码">
          <el-input v-model.trim="props.config.downloadToolPassword" placeholder="password" show-password
                    autocomplete="new-password">
            <template #prefix>
              <el-icon class="el-input__icon">
                <Key/>
              </el-icon>
            </template>
          </el-input>
        </SettingsItem>
      </template>
      <SettingsItem>
        <div class="download-test-button">
          <el-button @click="downloadLoginTest" bg text :loading="downloadLoginTestLoading" icon="Odometer">测试
          </el-button>
        </div>
      </SettingsItem>
    </section>

    <section class="settings-section">
      <div class="settings-section-heading">
        <h3>保存与清理</h3>
      </div>
      <SettingsItem label="保存位置">
        <el-input v-model.trim="props.config['downloadPathTemplate']"/>
        <div class="full-width margin-top-4" v-if="!testPathTemplate(props.config['downloadPathTemplate'])">
          <el-alert
              type="warning"
              show-icon
              :closable="false"
          >
            <template #title>
              你的 保存位置 并未按照模版填写, 可能会遇到下载位置错误
            </template>
          </el-alert>
        </div>
      </SettingsItem>
      <SettingsItem label="剧场版保存位置">
        <el-input v-model.trim="props.config['ovaDownloadPathTemplate']"/>
        <div class="full-width margin-top-4" v-if="!testPathTemplate(props.config['ovaDownloadPathTemplate'])">
          <el-alert
              type="warning"
              show-icon
              :closable="false"
          >
            <template #title>
              你的 剧场版保存位置 并未按照模版填写, 可能会遇到下载位置错误
            </template>
          </el-alert>
        </div>
      </SettingsItem>
      <SettingsItem label="自动删除">
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
      </SettingsItem>
    </section>

    <section class="settings-section">
      <div class="settings-section-heading">
        <h3>任务控制</h3>
      </div>
      <SettingsItem label="失败重试次数">
        <el-input-number v-model:model-value="props.config['downloadRetry']" :max="100" :min="3"/>
      </SettingsItem>
      <SettingsItem label="同时下载限制">
        <div>
          <el-input-number v-model:model-value="props.config.downloadCount" :min="0"/>
          <br/>
          <el-text class="mx-1" size="small">
            设置为 0 时不做限制
          </el-text>
        </div>
      </SettingsItem>
      <SettingsItem label="延迟下载">
        <el-input-number v-model:model-value="props.config.delayedDownload" :min="0">
          <template #suffix>
            <span>分钟</span>
          </template>
        </el-input-number>
      </SettingsItem>
      <SettingsItem label="新种子下载等待">
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
      </SettingsItem>
      <SettingsItem label="优先保留">
        <div class="full-width">
          <el-switch v-model:model-value="props.config.priorityKeywordsEnable"/>
          <div>
            <el-text class="mx-1" size="small">
              启用多文件种子的文件优先保留过滤
            </el-text>
          </div>
          <div v-if="props.config.priorityKeywordsEnable">
            <PrioKeysView
                v-model:keywords="props.config.priorityKeywords"
                :import-global="false"
                :show-text="true"
            />
          </div>
        </div>
      </SettingsItem>
      <SettingsItem label="自定义标签">
        <CustomTagsView :config="props.config"/>
      </SettingsItem>
    </section>

    <section class="settings-section settings-section-advanced">
      <div class="settings-section-heading">
        <h3>高级设置</h3>
      </div>
      <el-collapse v-model="activeName">
        <el-collapse-item name="qBittorrent" title="qBittorrent 设置">
          <q-bittorrent-view v-if="activeName.indexOf('qBittorrent') > -1" :config="props.config"/>
        </el-collapse-item>
      </el-collapse>
    </section>
  </div>
</template>

<script setup>
import SettingsItem from "@/view/custom/SettingsItem.vue";
import {ref} from "vue";
import {ElMessage, ElText} from "element-plus";
import {Key, User} from "@element-plus/icons-vue";
import qBittorrentView from "@/view/config/download/qBittorrentView.vue";
import PrioKeysView from "@/view/config/PrioKeysView.vue";
import CustomTagsView from "@/view/config/CustomTagsView.vue";
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
const downloadLoginTest = () => {
  downloadLoginTestLoading.value = true
  http.downloadLoginTest(props.config)
      .then(res => {
        ElMessage.success(res.message)
      })
      .finally(() => {
        downloadLoginTestLoading.value = false
      })
}

const offline115Loading = ref(false)
const queryOffline115 = () => {
  offline115Loading.value = true
  http.offline115Status()
      .then(res => {
        const data = res.data || {}
        if (data.enabled === false) {
          ElMessage.warning(data.message || '先把下载工具设为 OpenList，115 离线看门狗才会生效')
          return
        }
        ElMessage.success(
            `115 离线任务 ${data.taskCount ?? 0} 条（进行中 ${data.runningCount ?? 0}、`
            + `已完成 ${data.finishedCount ?? 0}、卡死 ${data.stuckCount ?? 0}），`
            + `配额 ${data.quota ?? 0}/${data.total ?? 0}（${data.quotaPercent ?? 0}%）`
        )
      })
      .finally(() => {
        offline115Loading.value = false
      })
}

let testPathTemplate = (path) => {
  return new RegExp('\\$\{[A-z]+\}').test(path);
}

let activeName = ref([])

let props = defineProps(['config'])
</script>

<style scoped>
.settings-section + .settings-section {
  margin-top: 28px;
  padding-top: 24px;
  border-top: 1px solid var(--el-border-color-extra-light);
}

.settings-section-heading {
  margin-bottom: 18px;
}

.settings-section-heading h3 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 15px;
  font-weight: 650;
  line-height: 1.4;
}

.download-test-button {
  display: flex;
  width: 100%;
  justify-content: flex-end;
}

.download-danger-text {
  color: var(--el-color-danger);
}

.settings-section-advanced :deep(.el-collapse) {
  border-top: 0;
}

@media (max-width: 700px) {
  .settings-section + .settings-section {
    margin-top: 22px;
    padding-top: 20px;
  }

  .settings-section-heading {
    margin-bottom: 15px;
  }
}
</style>
