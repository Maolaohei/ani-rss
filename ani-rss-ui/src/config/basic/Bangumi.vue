<template>
  <bangumi-me ref="bangumiMeRef"/>
  <el-form @submit.prevent label-width="auto"
           class="full-width">
    <el-form-item label="BgmApi（番剧信息接口）">
      <el-input v-model:model-value="props.config['bgmApi']"
                placeholder="https://api.bgm.tv"
                @blur="normalizeBgmApi"/>
    </el-form-item>
    <el-form-item label="获取方式">
      <el-radio-group v-model="props.config['bgmTokenType']">
        <el-radio label="手动输入" value="INPUT"/>
        <el-radio label="自动获取" value="AUTO"/>
      </el-radio-group>
    </el-form-item>
    <template v-if="props.config['bgmTokenType'] === 'INPUT'">
      <el-form-item label="Token">
        <div class="full-width">
          <el-input
              v-model="props.config.bgmToken"
              placeholder="ABCDEFGHIJKLMNOPQRS"
          />
          <div>
            <el-text class="mx-1" size="small">
              你可以在&nbsp;
              <el-link
                  type="primary"
                  class="text-extra-small"
                  href="https://next.bgm.tv/demo/access-token"
                  target="_blank">
                https://next.bgm.tv/demo/access-token
              </el-link>
              &nbsp;生成一个 Access Token
            </el-text>
          </div>
        </div>
      </el-form-item>
    </template>
    <template v-if="props.config['bgmTokenType'] === 'AUTO'">
      <el-form-item label="App ID">
        <el-input
            v-model="props.config['bgmAppID']"
            placeholder="bgm123456789"
        />
      </el-form-item>
      <el-form-item label="App Secret">
        <el-input
            v-model="props.config['bgmAppSecret']"
            placeholder="abcdefghijklm"
        />
      </el-form-item>
      <el-form-item label="回调地址">
        <div class="full-width">
          <el-input v-model="props.config['bgmRedirectUri']"/>
          <el-button bg icon="Refresh"
                     class="mt-6"
                     @click="setRedirectUri"/>
        </div>
      </el-form-item>
      <div class="flex justify-space-between">
        <el-text class="mx-1" size="small">
          自动获取可以实现token自动续期
          <br>
          前往&nbsp;
          <el-link
              class="text-extra-small"
              type="primary"
              target="_blank"
              href="https://bgm.tv/dev/app">
            Bangumi 开发者平台
          </el-link>
          &nbsp;设置你自己的应用
        </el-text>
        <div>
          <el-button bg
                     type="primary"
                     :disabled="!props.config['bgmAppSecret'] || !props.config['bgmAppID']"
                     @click="start"
                     :loading="loading"
          >
            获取授权
          </el-button>
          <el-button
              bg
              type="success"
              @click="bangumiMeRef?.show">
            查看授权状态
          </el-button>
        </div>
      </div>
    </template>
  </el-form>
  <div class="flex justify-start">
    <el-link type="primary"
             class="text-extra-small"
             href="https://docs.wushuo.top/config/basic/other#emby-webhook"
             target="_blank">支持自动点格子
    </el-link>
  </div>
</template>

<script setup>
import {ElMessage, ElText} from "element-plus";
import BangumiMe from "@/config/basic/BangumiMe.vue";
import {onMounted, ref} from "vue";
import {setConfig} from "@/js/http.js";
import {getBaseUrl} from "@/js/global.js";

let bangumiMeRef = ref()

let props = defineProps(['config'])

let setRedirectUri = () => {
  props.config['bgmRedirectUri'] = `${getBaseUrl()}bgm-oauth-callback`
}

onMounted(() => {
  if (props.config['bgmRedirectUri']) {
    return
  }
  setRedirectUri()
})

let loading = ref(false);

/**
 * 接口地址即时校验：缺协议会被后端静默归一化，用户看不出自己填错了
 */
let normalizeBgmApi = () => {
  const raw = (props.config['bgmApi'] || '').trim()
  if (!raw) {
    return
  }
  if (!/^https?:\/\//i.test(raw)) {
    const fixed = `https://${raw}`
    props.config['bgmApi'] = fixed
    ElMessage.warning(`BgmApi 已自动补全为 ${fixed}，如不是 https 请手动修改`)
  }
}

let start = () => {
  if (!props.config['bgmAppID']) {
    ElMessage.error('请先填写 App ID（在 Bangumi 开发者平台创建应用后获得）')
    return
  }
  loading.value = true;
  setConfig(props.config)
      .then(async res => {
        let redirect = window.encodeURI(props.config['bgmRedirectUri'])
        let url = `https://bgm.tv/oauth/authorize?client_id=${props.config['bgmAppID']}&response_type=code&redirect_uri=${redirect}`
        window.open(url)
        // 不再立刻 location.reload()：会让用户丢掉当前设置上下文与这条提示。
        // 把 App ID/Secret 落盘后再跳转，用户在新标签页完成授权即可回来手动刷新。
        ElMessage.success('已保存应用信息并打开授权页；在弹出的页面完成授权后，回到本页刷新即可')
      })
      .finally(() => {
        loading.value = false;
      })
}

</script>

<style scoped>
.text-extra-small {
  font-size: var(--el-font-size-extra-small);
}

.mt-6 {
  margin-top: 6px;
}

.justify-space-between {
  justify-content: space-between;
}

.justify-start {
  justify-content: start;
}
</style>
