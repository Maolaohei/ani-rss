<template>
  <AnimeGarden ref="animeGardenRef" @callback="rssCallback"/>
  <AniBT ref="aniBTRef" @callback="rssCallback"/>
  <Mikan ref="mikanRef" @callback="rssCallback"/>
  <Bgm ref="bgmRef" @callback="bgmCallback"/>
  <el-dialog v-model="dialogVisible" center title="添加订阅"
             :close-on-click-modal="!rssButtonLoading"
             :close-on-press-escape="!rssButtonLoading"
             :show-close="!rssButtonLoading"
  >
    <div v-show="showRss">
      <el-alert
          class="source-tip"
          type="info"
          :closable="false"
          show-icon
          title="左侧标签是「RSS 来源」，决定后端用哪种方式解析地址；选错来源会提示“获取失败”。不确定时可直接粘贴 RSS，系统会尝试自动识别。"
      />
      <el-tabs tab-position="left" v-model="activeName">
        <el-tab-pane label="Mikan" name="mikan">
          <el-form @submit.prevent label-width="auto"
                   style="height: 260px">
            <el-form-item label="RSS 地址">
              <div class="full-width">
                <el-input
                    :disabled="rssButtonLoading"
                    type="textarea"
                    :autosize="{ minRows: 2}"
                    v-model:model-value="ani.url"
                    @blur="autoDetectSource"
                    placeholder="https://mikanani.me/RSS/Bangumi?bangumiId=xxx&subgroupid=xxx"
                />
                <br>
                <div class="mikan-button">
                  <el-button @click="mikanRef?.show()" text bg type="primary"
                             :disabled="rssButtonLoading">
                    <template #icon>
                      <img src="@/icon/icon-Mikan.png" alt="mikan" class="icon el-icon--left"/>
                    </template>
                    浏览 Mikan 字幕组
                  </el-button>
                </div>
                <div>
                  <el-text class="mx-1" size="small">
                    来源：Mikan（mikanani.me）。不支持聚合订阅，原因是如果一次过多更新会出现遗漏。
                    <br>
                    不必在 mikan 网站添加订阅, 你可以通过上方👆按钮浏览字幕组订阅。
                    <br>
                    若提示“获取失败”，常见原因是 Mikan 被 Cloudflare 拦截，请在「设置 - 代理设置」配置代理后重试。
                  </el-text>
                </div>
              </div>
            </el-form-item>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="AniBT" name="ani-bt">
          <el-form @submit.prevent label-width="auto"
                   style="height: 260px">
            <el-form-item label="RSS 地址">
              <div class="full-width">
                <el-input
                    :disabled="rssButtonLoading"
                    type="textarea"
                    :autosize="{ minRows: 2}"
                    v-model:model-value="ani.url"
                    @blur="autoDetectSource"
                    placeholder="https://anibt.net/rss/anime.xml?bgmId=xxx&groupSlug=xxx"
                />
                <br>
                <div class="mikan-button">
                  <el-button @click="aniBTRef?.show()" text bg type="primary"
                             :disabled="rssButtonLoading">
                    <template #icon>
                      <img src="@/icon/icon-AniBT.png" alt="ani-bt" class="icon el-icon--left"/>
                    </template>
                    浏览 AniBT 字幕组
                  </el-button>
                </div>
                <div>
                  <el-text class="mx-1" size="small">
                    来源：AniBT（anibt.net）。不支持聚合订阅，原因是如果一次过多更新会出现遗漏。
                    <br>
                    不必在 AniBT 网站添加订阅, 你可以通过上方👆按钮浏览字幕组订阅。
                  </el-text>
                </div>
              </div>
            </el-form-item>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="AnimeGarden" name="anime-garden">
          <el-form @submit.prevent label-width="auto"
                   style="height: 260px">
            <el-form-item label="RSS 地址">
              <div class="full-width">
                <el-input
                    :disabled="rssButtonLoading"
                    type="textarea"
                    :autosize="{ minRows: 2}"
                    v-model:model-value="ani.url"
                    @blur="autoDetectSource"
                    placeholder="https://api.animes.garden/feed.xml?subject=xxx&fansub=xxx"
                />
                <br>
                <div class="mikan-button">
                  <el-button @click="animeGardenRef?.show()" text bg type="primary"
                             :disabled="rssButtonLoading">
                    <template #icon>
                      <img src="@/icon/icon-AnimeGarden.png" alt="AnimeGarden" class="icon el-icon--left"/>
                    </template>
                    浏览 AnimeGarden 字幕组
                  </el-button>
                </div>
                <div>
                  <el-text class="mx-1" size="small">
                    来源：AnimeGarden（animes.garden）。不支持聚合订阅，原因是如果一次过多更新会出现遗漏。
                    <br>
                    这里只能浏览当季番剧；要精确添加某部番，请到「编辑订阅」页用 AnimeGarden 按钮（会带上 Bangumi 条目）。
                  </el-text>
                </div>
              </div>
            </el-form-item>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="其他 RSS" name="other">
          <el-form @submit.prevent label-width="auto"
                   style="height: 200px">
            <el-form-item label="番剧名称">
              <div class="flex full-width">
                <el-input
                    v-model:model-value="ani.title"
                    :disabled="rssButtonLoading"
                    placeholder="建议与 Bangumi 条目名称一致"
                />
                <div style="width: 4px;"></div>
                <el-button :disabled="rssButtonLoading" bg icon="Search" text type="primary"
                           @click="bgmRef?.show(ani.title)"/>
              </div>
            </el-form-item>
            <el-form-item label="Bangumi 链接" required>
              <div class="full-width">
                <el-input
                    v-model:model-value="ani.bgmUrl"
                    placeholder="https://bgm.tv/subject/123456"
                    :disabled="rssButtonLoading"
                />
                <el-text class="mx-1" size="small">
                  必填：用于刮削、评分与集数匹配。没有条目可先点上方 🔍 搜索后选择。
                </el-text>
              </div>
            </el-form-item>
            <el-form-item label="RSS 地址">
              <el-input
                  :disabled="rssButtonLoading"
                  :autosize="{ minRows: 2}"
                  type="textarea"
                  v-model:model-value="ani.url"
                  placeholder="https://xxxx.com/a.xml"
              />
            </el-form-item>
          </el-form>
          <el-text class="mx-1" size="small">
            来源：其他 RSS（任意地址，按通用方式解析）。dmhy等含有磁力链接的RSS不支持Aria2。
          </el-text>
        </el-tab-pane>
      </el-tabs>
      <div class="action">
        <el-button :loading="rssButtonLoading" @click="getRss" text bg icon="Check">确定</el-button>
      </div>
    </div>
    <div v-if="!showRss">
      <Ani v-model:ani="ani" @callback="addAni"/>
    </div>
  </el-dialog>
</template>

<script setup>
import {ref} from "vue";
import {ElMessage, ElMessageBox} from "element-plus";
import Mikan from "./Mikan.vue";
import Ani from "./Ani.vue";
import Bgm from "./Bgm.vue";
import {aniData} from "@/js/ani.js";
import * as http from "@/js/http.js";
import AniBT from "@/home/AniBT.vue";
import {useLocalStorage} from "@vueuse/core";
import AnimeGarden from "@/home/AnimeGarden.vue";

const showRss = ref(true)
const aniBTRef = ref()
const mikanRef = ref()
const animeGardenRef = ref()
const bgmRef = ref()

const dialogVisible = ref(false)

const ani = ref(aniData)

const rssButtonLoading = ref(false)

/**
 * 按 RSS 域名嗅探来源，避免用户把其它站的 RSS 粘在 Mikan 标签页后
 * 只得到一句“获取失败”。只在域名能明确判定时切换，避免误改用户选择。
 */
const SOURCE_BY_HOST = [
  [/mikanani\.me|mikanime\.tv/i, 'mikan'],
  [/anibt\.net/i, 'ani-bt'],
  [/animes\.garden/i, 'anime-garden']
]

const autoDetectSource = () => {
  let url = (ani.value.url || '').trim()
  if (!url || rssButtonLoading.value) {
    return
  }
  let host
  try {
    host = new URL(url).host
  } catch (e) {
    return
  }
  for (let [pattern, type] of SOURCE_BY_HOST) {
    if (pattern.test(host)) {
      if (activeName.value !== type) {
        activeName.value = type
        ElMessage.info(`已根据地址自动切换到「${typeLabel(type)}」来源`)
      }
      return
    }
  }
}

const typeLabel = type => {
  if (type === 'mikan') return 'Mikan'
  if (type === 'ani-bt') return 'AniBT'
  if (type === 'anime-garden') return 'AnimeGarden'
  return '其他 RSS'
}

/**
 * 已知订阅的 标题+季 快照，用于添加前提示同名冲突
 * （后端 replace=true 时会静默删除旧订阅，replace=false 时直接报“订阅标题重复”）
 */
const existingKeys = ref(new Set())

const titleKey = (title, season) => `${(title || '').trim()}#${season ?? 1}`

const loadExistingKeys = () => {
  return http.listAni()
      .then(res => {
        let keys = new Set()
        let weekList = res?.data?.weekList || []
        for (let week of weekList) {
          for (let item of (week.items || [])) {
            keys.add(titleKey(item.title, item.season))
          }
        }
        existingKeys.value = keys
      })
      .catch(() => {
        // 拿不到列表时不阻断添加流程，跳过冲突提示
        existingKeys.value = new Set()
      })
}

const getRss = () => {
  if (activeName.value === 'other') {
    if (!ani.value.bgmUrl) {
      ElMessage.error('请填写 Bangumi 链接（用于刮削、评分与集数匹配），可先点搜索按钮选择对应番剧')
      return
    }
  }
  rssButtonLoading.value = true
  ani.value.type = activeName.value
  http.rssToAni(ani.value)
      .then(res => {
        let match = ani.value['match'];
        ani.value = res['data']
        ani.value['match'] = match
        ani.value.showDownlaod = false
        showRss.value = false
      })
      .catch(err => {
        // 后端把解析失败统一收敛为“获取失败”，这里补一句可行动的排查方向
        let message = err?.message || '获取 RSS 失败'
        if (message.includes('获取失败') || message.includes('解析失败')) {
          ElMessage.error(`${message}。请确认左侧「来源」与 RSS 地址匹配；Mikan/AniBT 站点可能被 Cloudflare 拦截，请在「设置 - 代理设置」配置代理后重试`)
          return
        }
        ElMessage.error(message)
      })
      .finally(() => {
        rssButtonLoading.value = false
      })
}

/**
 * 添加订阅：同名同季已存在时先确认，避免“自动替换”开关静默删除旧订阅
 */
const addAni = async (fun) => {
  let key = titleKey(ani.value.title, ani.value.season)
  if (existingKeys.value.has(key)) {
    try {
      await ElMessageBox.confirm(
          `已存在同名同季的订阅「${ani.value.title} 第${ani.value.season}季」。\n` +
          '继续将覆盖/替换旧订阅（旧订阅的匹配、排除规则与下载进度会随之丢失）。\n' +
          '若只想多加一个字幕组，请改用「编辑订阅 → 备用 RSS」。',
          '该番已订阅',
          {
            type: 'warning',
            confirmButtonText: '仍然继续',
            cancelButtonText: '取消',
            confirmButtonClass: 'is-text is-has-bg el-button--danger',
            cancelButtonClass: 'is-text is-has-bg'
          }
      )
    } catch (e) {
      if (fun) {
        fun()
      }
      return
    }
  }

  http.addAni(ani.value)
      .then(res => {
        ElMessage.success(res.message)
        // 新订阅可能被顶栏的搜索词/筛选藏起来，这里清掉搜索条件并刷新，确保用户能看到结果
        clearHomeFilter()
        window.$reLoadList()
        dialogVisible.value = false
      })
      .finally(fun)
}

const emit = defineEmits(['update:title', 'update:filter'])

/**
 * 清空首页顶栏的关键词搜索，避免刚添加的订阅被筛选隐藏
 */
const clearHomeFilter = () => {
  // 通过 v-model:title 通知首页清空搜索词
  emit('update:title', '')
}

const activeName = useLocalStorage('add-active-name', 'mikan')

const show = () => {
  ani.value = JSON.parse(JSON.stringify(aniData))
  showRss.value = true
  dialogVisible.value = true
  rssButtonLoading.value = false
  loadExistingKeys()
}

let bgmCallback = it => {
  ani.value.title = it['name_cn'] ? it['name_cn'] : it['name']
  ani.value.bgmUrl = it.url
}

let rssCallback = v => {
  let {subgroup, match, url, bgmUrl} = v
  ani.value.url = url
  ani.value.bgmUrl = bgmUrl
  ani.value.subgroup = subgroup
  ani.value.match = JSON.parse(match)
      .map(s => `{{${subgroup}}}:${s}`)
  // 已订阅的提示由 picker 的「匹配」弹窗与保存前的同名确认共同承担
  getRss()
}

defineExpose({show})
</script>

<style scoped>
.source-tip {
  margin-bottom: 10px;
}

.mikan-button {
  width: 100%;
  display: flex;
  justify-content: end;
  margin-top: 8px;
}

.action {
  width: 100%;
  display: flex;
  justify-content: end;
  margin-top: 10px;
}

.icon {
  width: 24px;
  height: 24px;
  border-radius: 8px;
}
</style>
