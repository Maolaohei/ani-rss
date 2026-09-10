<template>
  <el-form @submit.prevent label-width="auto"
           class="full-width">
    <el-form-item label="自动刮削">
      <el-switch v-model="props.config['scrape']"/>
    </el-form-item>
    <el-form-item label="追更天数">
      <div>
        <el-input-number v-model="props.config['followDay']" :min="1">
          <template #suffix>
            天
          </template>
        </el-input-number>
        <br/>
        <el-text class="mx-1" size="small">
          自动强制刮削最近更新集的元数据
        </el-text>
      </div>
    </el-form-item>
    <el-form-item label="更多">
      <el-checkbox label="bangumi.ini" v-model="props.config['bangumiIniEnabled']"/>
    </el-form-item>
    <el-form-item label="TMDB API 地址">
      <el-input v-model:model-value="props.config['tmdbApi']"
                placeholder="https://api.themoviedb.org"
                @blur="normalizeTmdbApi"/>
    </el-form-item>
    <el-form-item label="TMDB API Key">
      <div class="full-width">
        <el-input v-model:model-value="props.config['tmdbApiKey']"
                  placeholder="请自备 API 密钥, 留空使用系统默认"
                  show-password/>
        <el-text class="mx-1" size="small">
          留空时使用内置默认密钥；若刮削频繁失败（429/401）建议自带密钥
        </el-text>
      </div>
    </el-form-item>
    <el-form-item label="TMDB 图片地址">
      <el-input v-model:model-value="props.config['tmdbImage']"
                placeholder="https://image.tmdb.org"
                @blur="normalizeTmdbImage"/>
    </el-form-item>
  </el-form>
</template>

<script setup>

import {ElMessage} from "element-plus";

let props = defineProps(['config'])

/**
 * 地址类字段即时校验：留空表示用默认值，不能擅自补协议；
 * 有值但缺协议时补全并回显，避免保存后只得到一句"刮削失败"。
 */
const ensureProtocol = (field, label) => {
  const raw = (props.config[field] || '').trim()
  if (!raw) {
    return
  }
  if (!/^https?:\/\//i.test(raw)) {
    const fixed = `https://${raw}`
    props.config[field] = fixed
    ElMessage.warning(`${label} 已自动补全为 ${fixed}，如不是 https 请手动修改`)
  }
}

const normalizeTmdbApi = () => ensureProtocol('tmdbApi', 'TMDB API 地址')
const normalizeTmdbImage = () => ensureProtocol('tmdbImage', 'TMDB 图片地址')
</script>
