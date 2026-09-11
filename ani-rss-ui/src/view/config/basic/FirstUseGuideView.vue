<template>
  <el-alert
      v-if="visible"
      class="first-use-guide"
      type="info"
      :closable="true"
      show-icon
      title="首次使用建议按这三步检查，装完就能自动追番"
      @close="dismiss"
  >
    <template #default>
      <ol class="guide-steps">
        <li v-for="step in steps" :key="step.key" class="guide-step">
          <el-tag size="small" :type="step.ok ? 'success' : 'warning'" class="guide-tag">
            {{ step.ok ? '已就绪' : '待配置' }}
          </el-tag>
          <span class="guide-text">
            {{ step.title }}：{{ step.ok ? step.okText : step.todoText }}
          </span>
        </li>
      </ol>
      <el-text size="small" type="info">
        本清单只检查配置是否填写；下载器能否连通请在「下载设置 → 连接测试」验证，通知能否送达请在「通知」里点测试。
      </el-text>
    </template>
  </el-alert>
  <div v-else-if="dismissed && hasTodo" class="guide-reopen-row">
    <el-link class="guide-reopen" type="info" :underline="false" @click="reopen">
      首次使用清单已收起（仍有待配置项）· 点此重新打开
    </el-link>
  </div>
</template>

<script setup>
import {computed} from "vue";
import {useLocalStorage} from "@vueuse/core";

/**
 * 首次使用引导（P1-25）：把最小可跑闭环拆成"下载器 / 保存位置 / 通知"三步，
 * 状态只基于已保存/正在编辑的配置字段，不额外发请求。
 *
 * 展示规则：
 * - 三步全部「已就绪」→ 不显示（引导使命完成）
 * - 有待配置项 → 显示；点 X 写入 localStorage（浏览器维度），后续不再自动弹出
 * - 已收起且仍有待配置项 → 显示一行小链接，可随时重新展开
 */
let props = defineProps(['config'])

// 浏览器维度记忆（清缓存/换浏览器会重新出现，对"首次使用"语义合理）
const dismissed = useLocalStorage('first-use-guide-dismissed', false)

const configuredDownloader = computed(() => {
  const config = props.config || {}
  const host = (config.downloadToolHost || '').trim()
  if (!host) {
    return false
  }
  const type = config.downloadToolType
  // OpenList 用 Token，其余下载器至少要地址
  return !!type
})

const configuredPath = computed(() => {
  const template = (props.config?.downloadPathTemplate || '').trim()
  if (!template) {
    return false
  }
  // 至少要能解析出一个静态根目录（与 FileController 的同名判定一致）
  return !!template.split('${')[0].trim()
})

const configuredNotification = computed(() => {
  const list = props.config?.notificationConfigList
  if (!Array.isArray(list)) {
    return false
  }
  return list.some(it => it && it.enable)
})

const steps = computed(() => [
  {
    key: 'downloader',
    ok: configuredDownloader.value,
    title: '下载器',
    okText: `已填写 ${props.config?.downloadToolType || '下载器'} 地址`,
    todoText: '到「下载设置」填下载器类型与地址，并点一次「连接测试」'
  },
  {
    key: 'path',
    ok: configuredPath.value,
    title: '保存位置',
    okText: '下载路径模板已配置',
    todoText: '到「下载设置 → 保存位置」填一个绝对路径模板，例如 /media/番剧/${title}/Season ${season}'
  },
  {
    key: 'notification',
    ok: configuredNotification.value,
    title: '通知（可选）',
    okText: '已有启用中的通知渠道',
    todoText: '到「通知」添加一个渠道（Telegram/Bark/Webhook 等）并点测试，收不到也能正常下载'
  }
])

const hasTodo = computed(() => steps.value.some(step => !step.ok))
const visible = computed(() => !dismissed.value && hasTodo.value)

const dismiss = () => {
  dismissed.value = true
}

const reopen = () => {
  dismissed.value = false
}
</script>

<style scoped>
.first-use-guide {
  margin-bottom: 8px;
}

.guide-reopen-row {
  margin-bottom: 8px;
}

.guide-reopen {
  font-size: 12px;
}

.guide-steps {
  margin: 6px 0 6px 0;
  padding-left: 20px;
}

.guide-step {
  margin-bottom: 4px;
  line-height: 1.7;
}

.guide-tag {
  margin-right: 6px;
}

.guide-text {
  font-size: 13px;
}
</style>
