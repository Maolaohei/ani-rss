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
  <SettingsItem label="错峰更新">
    <div class="full-width">
      <div class="num-field">
        <el-text class="num-label" size="small">启用</el-text>
        <div class="num-switch">
          <el-switch v-model="props.config['staggeredUpdateEnable']"/>
        </div>
        <el-text class="num-hint" size="small" type="info">默认开启</el-text>
      </div>
      <div class="num-field">
        <el-text class="num-label" size="small">批间隔</el-text>
        <el-input-number v-model="props.config['staggerBatchIntervalMs']"
                         :min="0" :max="60000" :step="500" controls-position="right"
                         placeholder="默认 2000"
                         :disabled="props.config['staggeredUpdateEnable'] === false"/>
        <el-text class="num-hint" size="small" type="info">毫秒，默认 2000</el-text>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small" type="info">
          数值项留空时按右侧标注的默认值处理。
        </el-text>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small">
          把一轮全量扫描按「RSS 并发度」分成多批提交，批与批之间等待上面的间隔，避免同一瞬间把源站、
          下载器、网盘一起打爆。等待总时长有上限（轮询周期的 1/4），超出后剩余批次连续提交，不会把整轮拖长。
          关闭后行为与旧版本一致。
        </el-text>
      </div>
    </div>
  </SettingsItem>
  <SettingsItem label="网盘 API 限流">
    <div class="full-width">
      <div class="num-field">
        <el-text class="num-label" size="small">速率</el-text>
        <el-input-number v-model="props.config['openListApiPerSecond']" :min="1" :max="20"
                         controls-position="right" placeholder="默认 1"/>
        <el-text class="num-hint" size="small" type="info">次/秒，默认 1</el-text>
      </div>
      <div class="num-field">
        <el-text class="num-label" size="small">突发</el-text>
        <el-input-number v-model="props.config['openListApiBurst']" :min="1" :max="5"
                         controls-position="right" placeholder="默认 1"/>
        <el-text class="num-hint" size="small" type="info">允许瞬时连续发出的次数，默认 1</el-text>
      </div>
      <div class="num-field">
        <el-text class="num-label" size="small">连续失败</el-text>
        <el-input-number v-model="props.config['openListFailThreshold']" :min="1" :max="10"
                         controls-position="right" placeholder="默认 3"/>
        <el-text class="num-hint" size="small" type="info">次后熔断，默认 3</el-text>
      </div>
      <div class="num-field">
        <el-text class="num-label" size="small">冷却</el-text>
        <el-input-number v-model="props.config['openListCooldownSeconds']" :min="10" :max="600"
                         controls-position="right" placeholder="默认 60"/>
        <el-text class="num-hint" size="small" type="info">秒，默认 60（逐级加倍，上限 10 分钟）</el-text>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small" type="info">
          数值项留空时按右侧标注的默认值处理。
        </el-text>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small">
          仅对 OpenList / Alist 生效。网盘按账号限流，请求全部串行发送：速率控制每秒最多几次，
          突发是允许瞬时连续发出的次数。连续失败达到阈值后进入冷却（逐级加倍，上限 10 分钟），
          冷却期内不再发起请求，受影响的条目一律显示为「存疑」而不是「不存在」。
          目录不存在属于正常结果，不会触发熔断。
        </el-text>
      </div>
    </div>
  </SettingsItem>
  <SettingsItem label="静默窗口">
    <div class="full-width">
      <div class="num-field">
        <el-text class="num-label" size="small">连续确认</el-text>
        <el-input-number v-model="props.config['quiescentConfirmTimes']" :min="1" :max="10"
                         controls-position="right" placeholder="默认 2"/>
        <el-text class="num-hint" size="small" type="info">次，默认 2</el-text>
      </div>
      <div class="num-field">
        <el-text class="num-label" size="small">超时</el-text>
        <el-input-number v-model="props.config['quiescentTimeoutMinutes']" :min="1" :max="1440"
                         controls-position="right" placeholder="默认 30"/>
        <el-text class="num-hint" size="small" type="info">分钟，默认 30（= 轮询周期 × 2，不小于 5）</el-text>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small" type="info">
          数值项留空时按右侧标注的默认值处理。
        </el-text>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small">
          开启新一轮扫描前，先等改名 / 上传 / 离线归位这些后处理收尾，避免在「文件已下载但还没改名落地」
          的窗口里判定本地不存在而重复下载。连续确认多次是为了躲开瞬时空窗；等待超过超时时间则强制开轮
          （会在任务管理器标注「超时强制开轮」，并跳过尚未收尾的订阅）。
        </el-text>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small" type="info">
          注意：只要还有下载任务在跑（或做种但未改名），本轮就会一直等到超时。若你有长期挂着的慢速/停滞任务，
          扫描频率会因此变慢——把超时时间调小即可缓解（等待时间会从轮询间隔里扣除，不会额外叠加）。
        </el-text>
      </div>
    </div>
  </SettingsItem>
  <SettingsItem label="结果缓存">
    <div class="full-width">
      <div class="num-field">
        <el-text class="num-label" size="small">缓存时长</el-text>
        <el-input-number v-model="props.config['stateCacheTtlDays']" :min="1" :max="90" :step="1"
                         controls-position="right" placeholder="默认 10"/>
        <el-text class="num-hint" size="small" type="info">天，默认 10，最高 90；本地与网盘共用</el-text>
      </div>
      <div class="num-field">
        <el-text class="num-label" size="small">单轮预算</el-text>
        <el-input-number v-model="props.config['openListApiBudgetPerRound']" :min="1" :max="200" :step="10"
                         controls-position="right" placeholder="留空 = 自动"/>
        <el-text class="num-hint" size="small" type="info">次，留空 = 自动（按订阅数与限速推算）</el-text>
      </div>
      <div class="num-field">
        <el-text class="num-label" size="small">列举上限</el-text>
        <el-input-number v-model="props.config['cloudListMaxFiles']" :min="100" :max="50000" :step="500"
                         controls-position="right" placeholder="默认 5000"/>
        <el-text class="num-hint" size="small" type="info">个文件，默认 5000</el-text>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small" type="info">
          数值项留空时按右侧标注的默认值处理；「单轮预算」留空表示自动取值。
        </el-text>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small">
          「本地存在」的判定结果会按订阅缓存：预览、媒体库、RSS 扫描、手动搜索共用同一份快照，
          同一轮内同一订阅的目录列举次数不超过 1 次。本地磁盘与网盘已合并为同一个设置项，单位为天。
        </el-text>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small">
          缓存不是靠过期自愈的：下载完成、离线归位会增量并入本集（不重新列举），
          删除、洗版、改名、模板变更、订阅增删会主动失效。缓存时长在这里的角色是
          「兜底对账间隔」——只用来回收带外变更（在网盘上手动增删文件、手动往下载目录拷文件、
          重启后离线任务自行完成）留下的偏差。把它调小不会更准，只会让每轮扫描都真实列举一次。
        </el-text>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small">
          单轮预算只统计「目录列举」次数（mkdir、移动、上传、下载器查询都不消耗它）。
          留空时自动取值：先按「每个订阅 1 次根目录 + 估算子目录数」给足，再以本轮周期内网盘限速
          发得出的次数封顶（速率 × 轮询周期 ÷ 4），并保证不低于订阅数——这样大库也不会因为预算不够
          而在后半程把订阅整体判成「存疑」。手动填写时最多 200 次。预算用完后停止真实文件校验，
          剩余条目保持「存疑」并在任务管理器标注原因。列举文件数超过上限时会截断——
          截断后仍能确认「存在」，但不能断言「不存在」，相关条目同样显示为「存疑」。
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
  <SettingsItem label="字幕手动获取">
    <div class="full-width">
      <div>
        <el-switch v-model="props.config['subtitleManualFetch']"/>
      </div>
      <div class="margin-top-4">
        <el-text class="mx-1" size="small">
          字幕统一在「字幕匹配」页面手动处理：可选择<b>手动上传本地字幕</b>，或<b>从射手网(ASSRT)获取</b>字幕。
          下载完成后不再自动抓取，且无论哪种方式，写入前都会弹窗展示「改名前 / 改名后 / 对应的视频」并二次确认，
          避免自动匹配到错误字幕。本开关用于启用射手网获取能力。
        </el-text>
      </div>
      <div class="margin-top-4 flex-col gap-8">
        <div>
          <el-text class="mx-1" size="small">ASSRT Token</el-text>
          <el-input
              v-model="props.config['assrtToken']"
              placeholder="在 assrt.net 用户后台获取的 API Token"
              show-password
              style="max-width: 360px"/>
        </div>
        <div>
          <el-text class="mx-1" size="small">字幕语言</el-text>
          <el-radio-group v-model="props.config['subtitleLang']">
            <el-radio label="简体 chs" value="chs"/>
            <el-radio label="繁体 cht" value="cht"/>
          </el-radio-group>
        </div>
        <div>
          <el-text class="mx-1" size="small">调用频率限制（次/分钟）</el-text>
          <el-input-number
              v-model="props.config['assrtRateLimitPerMinute']"
              :min="1" :max="120" :step="1" controls-position="right"
              style="max-width: 160px"/>
          <el-text class="mx-1" size="small" type="info">与 assrt.net 后台配额一致，默认 5</el-text>
        </div>
        <div>
          <el-text class="mx-1" size="small">连接超时（毫秒）</el-text>
          <el-input-number
              v-model="props.config['assrtConnectTimeoutMs']"
              :min="3000" :max="120000" :step="1000" controls-position="right"
              style="max-width: 180px"/>
          <el-text class="mx-1" size="small" type="info">默认 15000；链路差可调大</el-text>
        </div>
        <div>
          <el-text class="mx-1" size="small">读取超时（毫秒）</el-text>
          <el-input-number
              v-model="props.config['assrtReadTimeoutMs']"
              :min="5000" :max="300000" :step="1000" controls-position="right"
              style="max-width: 180px"/>
          <el-text class="mx-1" size="small" type="info">默认 30000；字幕源响应慢可调大</el-text>
        </div>
        <div>
          <el-text class="mx-1" size="small">瞬时故障重试次数</el-text>
          <el-input-number
              v-model="props.config['assrtRetryCount']"
              :min="0" :max="5" :step="1" controls-position="right"
              style="max-width: 160px"/>
          <el-text class="mx-1" size="small" type="info">
            默认 2（最多请求 3 次）；对超时/连接失败/5xx/限流按 1s、2s、4s 退避重试
          </el-text>
        </div>
        <el-text class="mx-1" size="small" type="info">
          Token 获取：登录 assrt.net → 用户中心 → API Token（免费）。未填 Token 时即便开启也无法获取。
          搜索以番剧英文标题单次请求，候选由你自行挑选后再下载。接口与字段说明见项目 docs/assrt-api.md。
        </el-text>
      </div>
      <div class="margin-top-8">
        <el-switch v-model="props.config['subtitleMetaEnabled']"/>
        <el-text class="mx-1" size="small">
          启用字幕季数元数据解析（TMDB/Bangumi）：当字幕番剧名未带 S1/S2 等季数标记时，自动查元数据推断季数，
          避免跨季误匹配（如第 2 季订阅误挂第 1 季字幕）。命中结果会缓存到本地，相同番剧名不再重复查询。
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

<style scoped>
/*
设置页数值项的统一布局：标签（定宽）| 输入框（定宽）| 单位 / 默认值说明。

原实现把标签塞进 el-input-number 的 #prefix / #suffix 插槽里：
el-input-number 默认宽 150px，左右各留 42px 给加减按钮，实际可输入区只剩约 66px；
再被「连续失败」「次后熔断」这类长中文盖住，用户既看不清自己输的数字、也点不准输入位置。
改为「标签外置 + controls-position="right" + 定宽输入框」后，输入区约 130px 且位置固定。
*/
.num-field {
    display: grid;
    grid-template-columns: 84px 170px minmax(0, 1fr);
    align-items: center;
    column-gap: 8px;
    row-gap: 2px;
    margin-bottom: 6px;
}

.num-field > .num-label {
    white-space: nowrap;
}

.num-field > .num-switch {
    display: flex;
    align-items: center;
}

/* 输入框占满自己那一列；element-plus 自带的 width:150px 会与列宽打架 */
.num-field > .el-input-number {
    width: 100%;
}

.num-field > .num-hint {
    line-height: 1.4;
}

@media (max-width: 700px) {
    .num-field {
        grid-template-columns: 84px minmax(0, 1fr);
    }

    .num-field > .num-hint {
        grid-column: 2;
    }
}
</style>

