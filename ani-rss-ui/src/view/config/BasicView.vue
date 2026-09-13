<template>
  <FirstUseGuideView :config="props.config"/>
  <el-collapse v-model:model-value="activeName" accordion>
    <el-collapse-item name="page" title="页面设置">
      <PageView :config="props.config"/>
    </el-collapse-item>
    <el-collapse-item name="add" title="添加订阅">
      <AddView :config="props.config"/>
    </el-collapse-item>
    <el-collapse-item name="rename" title="重命名设置">
      <RenameView :config="props.config"/>
    </el-collapse-item>
    <el-collapse-item name="scrape" title="刮削设置">
      <ScrapeView :config="props.config"/>
    </el-collapse-item>
    <el-collapse-item name="rss" title="RSS设置">
      <RssView :config="props.config"/>
    </el-collapse-item>
    <el-collapse-item name="quality" title="质量择优">
      <QualityProfileView :profile="qualityProfile"/>
    </el-collapse-item>
    <el-collapse-item name="trackers" title="Trackers">
      <TrackersView :config="props.config"/>
    </el-collapse-item>
    <el-collapse-item name="bangumi" title="Bangumi">
      <BangumiView :config="props.config"/>
    </el-collapse-item>
    <el-collapse-item name="other" title="其他">
      <OtherView :config="props.config"/>
    </el-collapse-item>
    <el-collapse-item name="backup" title="备份">
      <BackupView :config="props.config"/>
    </el-collapse-item>
  </el-collapse>
</template>

<script setup>
import {ref} from "vue";
import PageView from "@/view/config/basic/PageView.vue";
import AddView from "@/view/config/basic/AddView.vue";
import RenameView from "@/view/config/basic/RenameView.vue";
import RssView from "@/view/config/basic/RssView.vue";
import TrackersView from "@/view/config/basic/TrackersView.vue";
import OtherView from "@/view/config/basic/OtherView.vue";
import BangumiView from "@/view/config/basic/BangumiView.vue";
import BackupView from "./basic/BackupView.vue";
import ScrapeView from "./basic/ScrapeView.vue";
import FirstUseGuideView from "@/view/config/basic/FirstUseGuideView.vue";
import QualityProfileView from "@/view/config/basic/QualityProfileView.vue";
import {computed} from "vue";

const props = defineProps(['config'])

/**
 * 后端可能返回 null（存量配置没有该字段），就地补一个空对象承载编辑，
 * 保存时随 config 一起提交
 */
const qualityProfile = computed(() => {
  if (!props.config.qualityProfile) {
    props.config.qualityProfile = {
      enable: false,
      resolutionOrder: [],
      preferCodecs: [],
      excludeCodecs: [],
      minResolution: '',
      maxResolution: '',
      minSizeMb: 0,
      maxSizeMb: 0,
      minSeeders: 0,
      preferSubgroups: [],
      excludeSubgroups: [],
      preferCollection: true
    }
  }
  return props.config.qualityProfile
})

let activeName = ref('page')
</script>
