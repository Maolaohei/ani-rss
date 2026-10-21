<template>
  <div class="metric-grid">
    <div class="metric-item">
      <div class="metric-icon subscriptions">
        <el-icon>
          <List/>
        </el-icon>
      </div>
      <div>
        <el-text size="small" type="info">订阅数量</el-text>
        <div class="metric-value">{{ subscriptionTotal }}</div>
      </div>
    </div>
    <div class="metric-item">
      <div class="metric-icon enabled">
        <el-icon>
          <CircleCheck/>
        </el-icon>
      </div>
      <div>
        <el-text size="small" type="info">已启用</el-text>
        <div class="metric-value">{{ enabledTotal }}</div>
      </div>
    </div>
    <div class="metric-item">
      <div class="metric-icon downloading">
        <el-icon>
          <Download/>
        </el-icon>
      </div>
      <div>
        <el-text size="small" type="info">下载中</el-text>
        <div class="metric-value">{{ downloadingCount }}</div>
      </div>
    </div>
    <div class="metric-item">
      <div class="metric-icon seeding">
        <el-icon>
          <Upload/>
        </el-icon>
      </div>
      <div>
        <el-text size="small" type="info">做种中</el-text>
        <div class="metric-value">{{ seedingCount }}</div>
      </div>
    </div>
  </div>

  <section class="dashboard-section today-section">
    <div class="today-section-content">
      <div class="section-title">
        <h3>今天的订阅</h3>
        <div class="today-heading-actions">
          <el-tag type="info">{{ todayAnis.length }}</el-tag>
          <div v-if="todayAnis.length" class="today-scroll-actions">
            <el-button aria-label="向左滚动" bg circle text @click="scrollToday(-1)">
              <el-icon>
                <ArrowLeft/>
              </el-icon>
            </el-button>
            <el-button aria-label="向右滚动" bg circle text @click="scrollToday(1)">
              <el-icon>
                <ArrowRight/>
              </el-icon>
            </el-button>
          </div>
        </div>
      </div>
      <el-empty v-if="!todayAnis.length" description="今天没有订阅"/>
      <div v-else ref="todayTrack" class="today-track">
        <div v-for="ani in todayAnis"
             :key="ani.id"
             class="today-item">
          <AniCoverView
              :item="ani"
              @edit="$emit('edit', $event)"
              @playlist="$emit('playlist', $event)"
              @cover="$emit('cover', $event)"
              @del="$emit('del', $event)"
              @rate="$emit('rate', $event)"/>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import {ref} from "vue";
import {ArrowLeft, ArrowRight, CircleCheck, Download, List, Upload} from "@element-plus/icons-vue";
import AniCoverView from "@/view/home/AniCoverView.vue";

defineProps({
  subscriptionTotal: {type: Number, default: 0},
  enabledTotal: {type: Number, default: 0},
  downloadingCount: {type: Number, default: 0},
  seedingCount: {type: Number, default: 0},
  todayAnis: {type: Array, default: () => []}
})

defineEmits(['edit', 'playlist', 'cover', 'del', 'rate'])

const todayTrack = ref()

const scrollToday = direction => {
  const track = todayTrack.value
  if (!track) {
    return
  }
  track.scrollBy({
    left: direction * Math.min(track.clientWidth * 0.75, 560),
    behavior: 'smooth'
  })
}
</script>

<style scoped src="./dashboard.css"></style>
