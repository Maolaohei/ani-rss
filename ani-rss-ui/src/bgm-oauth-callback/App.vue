<template>
  <div class="page">
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-header">
          <span>授权结果</span>
        </div>
      </template>
      <div>
        <div v-if="me">
          <el-descriptions direction="vertical" border>
            <el-descriptions-item :rowspan="2" :width="140" label="头像" align="center">
              <el-avatar :src="me?.avatar?.large"/>
            </el-descriptions-item>
            <el-descriptions-item label="用户名">
              <el-text v-if="me.username">
                {{ me.username }}
              </el-text>
              <el-text v-else>
                {{ me.id }}
              </el-text>
            </el-descriptions-item>
            <el-descriptions-item label="主页">
              {{ me.url }}
            </el-descriptions-item>
            <el-descriptions-item label="邮箱">
              {{ me.email }}
            </el-descriptions-item>
            <el-descriptions-item label="注册日期">
              <el-text>
                {{ me.regTime }}
              </el-text>
            </el-descriptions-item>
            <el-descriptions-item label="授权剩余过期时间">
              <!-- 不只靠颜色区分：色觉障碍用户同样能读出状态 -->
              <el-tag :type="expiresSoon ? 'danger' : 'success'">
                {{ me.expiresDays }} 天{{ expiresSoon ? '（即将过期，请及时重新授权）' : '（有效）' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="签名">
              <el-text v-if="me.sign">
                {{ me.sign }}
              </el-text>
              <el-text v-else>
                无
              </el-text>
            </el-descriptions-item>
          </el-descriptions>
        </div>
        <el-alert
            class="result"
            :title="text"
            :type="type"
            v-loading.fullscreen.lock="loading"
            :closable="false"
            show-icon
        />
      </div>
      <template #footer>
        <div class="footer">
          <el-button bg text @click="backToSettings">返回设置</el-button>
          <el-button bg text type="primary" @click="close">关闭本页</el-button>
        </div>
      </template>
    </el-card>
  </div>
</template>

<script setup>
import {computed, onMounted, ref} from 'vue'
import {init} from "@/js/global.js";
import api from "@/js/api.js";
import * as http from "@/js/http.js";

const type = ref('success')
const text = ref('')
const loading = ref(false)
const me = ref(null)

const expiresSoon = computed(() => {
  const days = me.value?.expiresDays
  return days != null && Number(days) <= 3
})

const close = () => {
  // 非脚本打开的窗口 window.close() 会被浏览器忽略，必须给出兜底说明
  window.close()
  setTimeout(() => {
    if (!document.hidden) {
      text.value = '浏览器阻止了自动关闭，请手动关闭本页'
      type.value = 'info'
    }
  }, 300)
}

const backToSettings = () => {
  // 相对路径，兼容子路径部署
  location.href = './'
}

const loadMe = async () => {
  return http.meBgm()
      .then(res => {
        me.value = res.data
      });
}

const load = async (code) => {
  loading.value = true
  api.post(`api/bgm/oauth/callback?code=${code}`)
      .then(async res => {
        let {code, message} = res
        type.value = code === 200 ? 'success' : 'error'

        if (code === 200) {
          await loadMe()
        }

        text.value = message
      })
      .catch(err => {
        // 此前没有 catch：请求失败会留下一个空白 alert，用户不知道发生了什么
        type.value = 'error'
        text.value = err?.message || '授权回调失败，请回到设置页重新发起授权'
      })
      .finally(() => {
        loading.value = false
      })
}

onMounted(() => {
  const url = new URL(location.href)
  const code = url.searchParams.get('code')
  if (!code) {
    type.value = 'error'
    text.value = '回调地址缺少 code 参数，请回到设置页重新发起 Bangumi 授权'
    return
  }
  load(code)
})

init()
</script>

<style scoped>
:global(body) {
  margin: 0;
  padding: 0;
  display: flex;
  justify-content: center;
  align-items: center;
  /* 移动端地址栏收起时 100vh 会抖动；并在下方留出手势条安全区 */
  min-height: 100dvh;
  padding-bottom: env(safe-area-inset-bottom);
}

.page {
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  min-height: 100%;
  padding: 12px;
  box-sizing: border-box;
}

.card {
  /* 原来固定 min-width:480px，窄屏必然左溢出；改为按容器自适应 */
  width: 100%;
  max-width: 520px;
}

.result {
  margin-top: 8px;
}

.footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

@media (max-width: 450px) {
  .footer {
    flex-direction: column-reverse;
  }

  .footer .el-button {
    width: 100%;
    margin-left: 0;
  }
}
</style>
