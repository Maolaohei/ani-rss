<template>
  <div class="app-shell">
    <aside class="app-nav">
      <div class="app-brand">
        <img class="app-logo" src="/icon.svg" alt="ANI-RSS">
        <span>ANI-RSS</span>
      </div>
      <el-menu
          :default-active="route.path"
          :ellipsis="false"
          class="app-menu"
          router>
        <el-menu-item index="/home">
          <el-icon>
            <House/>
          </el-icon>
          <span>首页</span>
        </el-menu-item>
        <el-menu-item index="/subscriptions">
          <el-icon>
            <Collection/>
          </el-icon>
          <span>订阅</span>
        </el-menu-item>
        <el-menu-item index="/downloads">
          <el-icon>
            <Download/>
          </el-icon>
          <span>任务</span>
        </el-menu-item>
        <el-menu-item index="/library">
          <el-icon>
            <VideoCamera/>
          </el-icon>
          <span>媒体库</span>
        </el-menu-item>
        <el-menu-item index="/history">
          <el-icon>
            <Clock/>
          </el-icon>
          <span>历史</span>
        </el-menu-item>
        <el-menu-item index="/tools">
          <el-icon>
            <Tools/>
          </el-icon>
          <span>工具</span>
        </el-menu-item>
        <el-menu-item index="/subtitle-match">
          <el-icon>
            <Files/>
          </el-icon>
          <span>字幕匹配</span>
        </el-menu-item>
        <el-menu-item index="/logs">
          <el-icon>
            <Tickets/>
          </el-icon>
          <span>日志</span>
        </el-menu-item>
        <el-menu-item index="/settings">
          <el-icon>
            <Setting/>
          </el-icon>
          <span>设置</span>
        </el-menu-item>
      </el-menu>
    </aside>
    <main class="app-main">
      <RouterView v-slot="{ Component }">
        <KeepAlive>
          <component :is="Component"/>
        </KeepAlive>
      </RouterView>
    </main>
  </div>
</template>

<script setup>
import {onMounted} from "vue";
import {RouterView, useRoute} from "vue-router";
import {Clock, Collection, Download, Files, House, Setting, Tickets, Tools, VideoCamera} from "@element-plus/icons-vue";
import {initLayout} from "@/js/global.js";

const route = useRoute()

onMounted(() => {
  initLayout()
})
</script>

<style scoped>
.app-shell {
  width: 100%;
  height: 100%;
  display: flex;
}

.app-nav {
  /* 侧边栏需要容纳「字幕匹配」等四字菜单项：过窄时四字项会被挤压/换行，
     与两字项混排显得参差。这里给足宽度并收紧左右外边距，让四字与两字项左对齐、整齐美观。 */
  width: 168px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--el-bg-color);
}

.app-brand {
  height: 80px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 600;
}

.app-logo {
  width: 34px;
  height: 34px;
}

.app-menu {
  flex: 1;
  border: 0;
  background: none;
}

.app-menu :deep(.el-menu-item) {
  height: 46px;
  margin: 8px 10px;
  border-radius: 8px;
  border: none !important;
  white-space: nowrap;
}

.app-menu :deep(.el-menu-item.is-active) {
  background-color: var(--el-menu-hover-bg-color);
}

.app-main {
  flex: 1;
  min-width: 0;
  height: 100%;
  overflow: hidden;
}

@media (max-width: 800px) {
  .app-shell {
    display: block;
    padding-bottom: calc(58px + env(safe-area-inset-bottom, 0px));
  }

  .app-nav {
    position: fixed;
    left: 0;
    bottom: 0;
    z-index: 10;
    width: 100%;
    padding-bottom: env(safe-area-inset-bottom, 0px);
    border-top: 1px solid var(--el-border-color-light);
  }

  .app-brand {
    display: none;
  }

  .app-menu {
    flex: none;
    height: 58px;
    display: flex;
    justify-content: flex-start;
    gap: 2px;
    padding: 4px 8px;
    box-sizing: border-box;
    /* 导航项已增至 9 个，窄屏改为横向滚动而不是强行均分（均分会把文字挤没） */
    overflow-x: auto;
    overflow-y: hidden;
    -webkit-overflow-scrolling: touch;
    scrollbar-width: none;
  }

  .app-menu::-webkit-scrollbar {
    display: none;
  }

  .app-menu :deep(.el-menu-item) {
    flex: 0 0 auto;
    min-width: 56px;
    height: 50px;
    line-height: 1;
    display: flex;
    flex-direction: column;
    justify-content: center;
    gap: 4px;
    margin: 0;
    padding: 0 4px !important;
    transition: color var(--el-transition-duration), background-color var(--el-transition-duration);
  }

  .app-menu :deep(.el-menu-item .el-icon) {
    margin: 0;
  }

  .app-menu :deep(.el-menu-item span) {
    font-size: 12px;
  }

  .app-main {
    height: 100%;
  }
}
</style>
