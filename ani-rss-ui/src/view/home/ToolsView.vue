<template>
  <div class="tools-page">
    <el-tabs v-model="tab" class="tools-tabs">
      <el-tab-pane label="系统自检" name="doctor">
        <DoctorView v-if="mounted.doctor"/>
      </el-tab-pane>
      <el-tab-pane label="追番日历" name="calendar">
        <CalendarView v-if="mounted.calendar"/>
      </el-tab-pane>
      <el-tab-pane label="手动补种" name="search">
        <ManualSearchView v-if="mounted.search"/>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import {onMounted, reactive, ref, watch} from 'vue'
import {useRoute} from 'vue-router'
import DoctorView from '@/view/home/DoctorView.vue'
import CalendarView from '@/view/home/CalendarView.vue'
import ManualSearchView from '@/view/home/ManualSearchView.vue'

const route = useRoute()
const VALID = ['doctor', 'calendar', 'search']

const tab = ref(VALID.includes(route.query.tab) ? route.query.tab : 'doctor')

// 懒挂载：切到哪个 Tab 才渲染哪个，避免一次性触发自检 + 全量订阅拉取
const mounted = reactive({doctor: false, calendar: false, search: false})

const ensureMounted = name => {
  if (VALID.includes(name)) {
    mounted[name] = true
  }
}

ensureMounted(tab.value)

watch(tab, value => ensureMounted(value))

watch(() => route.query.tab, value => {
  if (VALID.includes(value)) {
    tab.value = value
  }
})

onMounted(() => ensureMounted(tab.value))
</script>

<style scoped>
.tools-page {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.tools-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0 24px;
}

.tools-tabs :deep(.el-tabs__header) {
  margin-top: 8px;
  margin-bottom: 0;
  flex-shrink: 0;
}

.tools-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
}

.tools-tabs :deep(.el-tab-pane) {
  height: 100%;
}

@media (max-width: 800px) {
  .tools-tabs {
    padding: 0 12px;
  }
}
</style>
