<template>
  <first-use-guide v-if="!keyword" :config="props.config"/>
  <div class="basic-search">
    <el-input v-model="keyword"
              clearable
              placeholder="筛选设置分组，如 重命名 / 白名单 / 代理 / RSS"
              prefix-icon="Search"/>
    <el-text class="mx-1" size="small" type="info">
      支持中文名与关键词（如"反代""OAuth""刮削"），只做分组定位，不影响任何配置值
    </el-text>
  </div>
  <el-collapse v-model:model-value="activeName" accordion>
    <el-collapse-item v-for="group in visibleGroups" :key="group.name" :name="group.name" :title="group.title">
      <component :is="group.component" :config="props.config"/>
    </el-collapse-item>
  </el-collapse>
  <el-empty v-if="!visibleGroups.length" description="没有匹配的设置分组，试试「重命名」「白名单」「RSS」等关键词" :image-size="70"/>
</template>

<script setup>
import {computed, ref} from "vue";
import Page from "@/config/basic/Page.vue";
import Add from "@/config/basic/Add.vue";
import Rename from "@/config/basic/Rename.vue";
import Rss from "@/config/basic/Rss.vue";
import Trackers from "@/config/basic/Trackers.vue";
import Other from "@/config/basic/Other.vue";
import Bangumi from "@/config/basic/Bangumi.vue";
import Backup from "./basic/Backup.vue";
import Scrape from "@/config/basic/Scrape.vue";
import FirstUseGuide from "@/config/basic/FirstUseGuide.vue";

/**
 * 分组元数据（name 稳定不变，便于后续深链/搜索跳转）；
 * title 用"中文（英文原词）"形式，让英文行话也能被找到。
 */
const groups = [
  {
    name: 'page',
    title: '页面设置（外观/主题色/排序）',
    component: Page,
    keywords: ['外观', '主题', '深色', '暗色', '排序', '宽度', '自定义', 'css', 'js', 'webui']
  },
  {
    name: 'add',
    title: '添加订阅',
    component: Add,
    keywords: ['添加', '全局排除', '重名', '替换', '订阅']
  },
  {
    name: 'rename',
    title: '重命名设置（命名模板）',
    component: Rename,
    keywords: ['重命名', '模板', '命名', '年份', '字幕文件夹', '文件名']
  },
  {
    name: 'scrape',
    title: '刮削设置（TMDB）',
    component: Scrape,
    keywords: ['刮削', 'tmdb', '元数据', '图片', '海报']
  },
  {
    name: 'rss',
    title: 'RSS 设置（间隔/备用 RSS/摸鱼检测）',
    component: Rss,
    keywords: ['rss', '间隔', '超时', '重试', '备用', '摸鱼', '遗漏', '跳过', '完结']
  },
  {
    name: 'trackers',
    title: 'Trackers（Tracker 服务器）',
    component: Trackers,
    keywords: ['trackers', 'tracker', '服务器', '做种']
  },
  {
    name: 'bangumi',
    title: 'Bangumi（账号/授权/OAuth）',
    component: Bangumi,
    keywords: ['bangumi', 'bgm', '账号', '授权', 'oauth', 'token', 'api', '获取方式']
  },
  {
    name: 'other',
    title: '其他（日志/缓存/网络/自启/备份策略）',
    component: Other,
    keywords: ['其他', '日志', '缓存', '清理', '网络', 'ipv4', 'ipv6', '自启', '备份', 'debug', 'github']
  },
  {
    name: 'backup',
    title: '备份（导出/导入）',
    component: Backup,
    keywords: ['备份', '导出', '导入', '恢复', '配置']
  }
]

let activeName = ref('page')
let keyword = ref('')

const visibleGroups = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) {
    return groups
  }
  return groups.filter(group => {
    const haystack = [group.title, ...(group.keywords || [])].join(' ').toLowerCase()
    return haystack.indexOf(kw) > -1
  })
})

let props = defineProps(['config'])

defineOptions({name: 'ConfigBasic'})
</script>

<style scoped>
.basic-search {
  margin-bottom: 8px;
}
</style>
