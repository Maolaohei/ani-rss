<template>
  <div class="dashboard-page app-page-layout">
    <EditAniView ref="editAniRef"/>
    <PlayListView ref="playListRef"/>
    <CoverView ref="coverRef"/>
    <DelAniView ref="delAniRef"/>
    <BgmRateView ref="bgmRateRef"/>
    <PageHeaderView title="首页" :subtitle="`${todayLabel} · ${todayText}`">
      <template #actions>
        <el-button :loading="refreshLoading" icon="Refresh" @click="loadAll" class="auto-button">
          刷新
        </el-button>
      </template>
    </PageHeaderView>
    <div class="dashboard-body app-page-content app-page-padding">
      <el-scrollbar class="dashboard-scrollbar">
        <div class="dashboard-content">
          <DashboardStats
              :subscription-total="subscriptionTotal"
              :enabled-total="enabledTotal"
              :downloading-count="downloadingList.length"
              :seeding-count="seedingList.length"
              :today-anis="todayAnis"
              @edit="openEdit"
              @playlist="openPlaylist"
              @cover="openCover"
              @del="openDelete"
              @rate="openRate"
          />
          <DashboardAnalytics
              :history-days="historyDays"
              :history-summary="historySummary"
              :success-rate-text="successRateText"
              :enabled-total="enabledTotal"
              :health-buckets="healthBuckets"
              :omit-top="omitTop"
              :procrastinating-list="procrastinatingList"
          />
        </div>
      </el-scrollbar>
    </div>
  </div>
</template>

<script setup>
import {ref} from "vue";
import EditAniView from "@/view/home/EditAniView.vue";
import PlayListView from "@/view/play/PlayListView.vue";
import CoverView from "@/view/home/CoverView.vue";
import DelAniView from "@/view/home/DelAniView.vue";
import BgmRateView from "@/view/home/BgmRateView.vue";
import PageHeaderView from "@/view/custom/PageHeaderView.vue";
import DashboardStats from "./dashboard/DashboardStats.vue";
import DashboardAnalytics from "./dashboard/DashboardAnalytics.vue";
import {useDashboard} from "./dashboard/useDashboard.js";

const editAniRef = ref()
const delAniRef = ref()
const coverRef = ref()
const playListRef = ref()
const bgmRateRef = ref()

const openEdit = item => editAniRef.value?.show(item)
const openPlaylist = item => playListRef.value?.show(item)
const openCover = item => coverRef.value?.show(item)
const openDelete = items => delAniRef.value?.show(items)
const openRate = item => bgmRateRef.value?.show(item)

const {
  refreshLoading,
  subscriptionTotal,
  enabledTotal,
  downloadingList,
  seedingList,
  todayAnis,
  todayLabel,
  todayText,
  historyDays,
  historySummary,
  successRateText,
  healthBuckets,
  omitTop,
  procrastinatingList,
  loadAll
} = useDashboard()
</script>

<style scoped src="./dashboard/dashboard.css"></style>
