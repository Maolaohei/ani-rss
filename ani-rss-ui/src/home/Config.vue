<template>
  <el-dialog
      v-model="dialogVisible"
      center
      title="设置"
      :close-on-click-modal="false"
      :before-close="handleBeforeClose">
    <div v-loading="loading" class="loading">
      <!-- 设置搜索：8 个页签上百个字段，此前完全靠猜位置 -->
      <div class="settings-search">
        <el-input
            v-model="searchKeyword"
            clearable
            placeholder="搜索设置项，如 代理 / OAuth / 白名单 / 重命名 / 通知"
            prefix-icon="Search"
            aria-label="搜索设置项"
            @input="onSearchSettings"
            @clear="onSearchSettings"/>
        <div v-if="searchKeyword.trim()" class="settings-search-result">
          <template v-if="matchedTabs.length">
            找到 {{ matchedTabs.length }} 个相关页签：
            <el-link
                v-for="tab in matchedTabs"
                :key="tab.name"
                class="settings-search-link"
                type="primary"
                @click="gotoTab(tab.name)">{{ tab.label }}</el-link>
          </template>
          <template v-else>
            <el-text type="warning" size="small">没有匹配的页签，请换个关键词试试</el-text>
          </template>
        </div>
      </div>
      <el-tabs v-model:model-value="activeName" class="tabs-center" style="margin: 0 15px;">
        <el-tab-pane label="下载设置" name="download" :lazy="true">
          <div style="height: 500px;">
            <el-scrollbar style="padding: 0 12px">
              <Download v-model:config="config"/>
            </el-scrollbar>
          </div>
        </el-tab-pane>
        <el-tab-pane :lazy="true" label="基本设置" name="basic">
          <div style="height: 500px;">
            <el-scrollbar style="padding: 0 12px;">
              <Basic v-model:config="config"/>
            </el-scrollbar>
          </div>
        </el-tab-pane>
        <el-tab-pane label="全局排除" name="exclude" :lazy="true">
          <Exclude v-model:exclude="config.exclude" :show-text="true"/>
        </el-tab-pane>
        <el-tab-pane label="代理设置" name="proxy" :lazy="true">
          <Proxy v-model:config="config"/>
        </el-tab-pane>
        <el-tab-pane label="登录设置" name="login" :lazy="true">
          <LoginConfig :config="config"/>
        </el-tab-pane>
        <el-tab-pane label="通知" name="notification" :lazy="true">
          <div style="height: 500px;">
            <el-scrollbar style="padding: 0 12px;">
              <Notification v-model:config="config"/>
            </el-scrollbar>
          </div>
        </el-tab-pane>
        <el-tab-pane :lazy="true" label="捐赠" name="afdian">
          <Afdian :config="config"/>
        </el-tab-pane>
        <el-tab-pane label="关于" name="about" :lazy="true">
          <About :config="config"/>
        </el-tab-pane>
      </el-tabs>
      <div class="action">
        <el-button :loading="configButtonLoading" bg icon="Check" text type="primary" @click="saveConfig">确定
        </el-button>
        <el-button icon="Close" bg text @click="requestClose">取消</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import {computed, nextTick, ref, watch} from "vue";
import {ElMessage, ElMessageBox} from "element-plus";
import CryptoJS from "crypto-js";
import Exclude from "@/config/Exclude.vue";
import Notification from "@/config/Notification.vue";
import Proxy from "@/config/Proxy.vue";
import Download from "@/config/Download.vue";
import Basic from "@/config/Basic.vue";
import About from "@/config/About.vue";
import LoginConfig from "@/config/LoginConfig.vue";
import Afdian from "@/config/Afdian.vue";
import {configData} from "@/js/config.js";
import {matchSettings} from "@/js/settings-search.js";
import * as http from "@/js/http.js";

const dialogVisible = ref(false)
const configButtonLoading = ref(false)
const loading = ref(true)

const config = ref(configData)

const activeName = ref('download')

/**
 * 打开弹窗时的配置快照，用于脏数据比对。
 * 此前 Esc / 点击遮罩会直接把弹窗关掉，跨页签的全部改动静默丢失。
 */
let snapshot = ''

const snapshotOf = value => JSON.stringify(value ?? null)

/** 设置项搜索：命中页签可一键跳转 */
const searchKeyword = ref('')
const matchedTabs = ref([])

const onSearchSettings = () => {
  matchedTabs.value = matchSettings(searchKeyword.value)
  // 只有一个命中时直接切过去，少一次点击
  if (matchedTabs.value.length === 1) {
    activeName.value = matchedTabs.value[0].name
  }
}

const gotoTab = name => {
  activeName.value = name
}

const isDirty = computed(() => !loading.value && snapshot !== '' && snapshotOf(config.value) !== snapshot)

const dirtyTabLabel = computed(() => {
  if (!isDirty.value) {
    return ''
  }
  return '当前有未保存的修改'
})

const show = (update) => {
  activeName.value = update ? 'about' : 'download'
  dialogVisible.value = true
  loading.value = true
  http.config()
      .then(res => {
        config.value = res.data
        nextTick(() => {
          snapshot = snapshotOf(config.value)
        })
      })
      .finally(() => {
        loading.value = false
      })
}

/**
 * 关闭前拦截：有未保存改动时二次确认。
 * Element Plus 的 closeOnPressEscape / closeOnClickModal 都会走 before-close，
 * 因此这里能覆盖 Esc、点击遮罩、右上角 × 三条路径。
 */
const handleBeforeClose = (done) => {
  if (!isDirty.value) {
    done()
    return
  }
  ElMessageBox.confirm(
      '设置尚未保存，关闭将丢失全部修改，是否继续关闭？',
      '未保存的修改',
      {
        type: 'warning',
        confirmButtonText: '放弃修改并关闭',
        cancelButtonText: '继续编辑',
        confirmButtonClass: 'is-text is-has-bg el-button--danger',
        cancelButtonClass: 'is-text is-has-bg'
      }
  )
      .then(() => {
        snapshot = ''
        done()
      })
      .catch(() => {
      })
}

/** 「取消」与关闭同一语义：有改动就确认，避免“取消”静默丢数据 */
const requestClose = () => {
  handleBeforeClose(() => {
    dialogVisible.value = false
  })
}

watch(dialogVisible, visible => {
  if (!visible) {
    snapshot = ''
  }
})

const saveConfig = () => {
  configButtonLoading.value = true
  let my_config = JSON.parse(JSON.stringify(config.value))

  let username = my_config.login.username.trim()
  let password = my_config.login.password.trim()

  my_config.login.username = username
  if (password) {
    my_config.login.password = CryptoJS['SHA256'](password).toString();
  }

  http.setConfig(my_config)
      .then(res => {
        ElMessage.success(res.message)
        snapshot = snapshotOf(config.value)
        window.$reLoadList()
        dialogVisible.value = false
      })
      .finally(() => {
        configButtonLoading.value = false
      })
}

defineExpose({
  show,
  isDirty,
  dirtyTabLabel
})
</script>
<style scoped>
.action {
  display: flex;
  justify-content: end;
  width: 100%;
  margin-top: 8px;
}

.settings-search {
  margin: 0 15px 10px;
}

.settings-search-result {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.settings-search-link {
  margin-right: 10px;
  font-size: 12px;
}
</style>
