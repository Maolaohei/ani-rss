import {createRouter, createWebHashHistory} from 'vue-router'
import DashboardView from '@/view/home/DashboardView.vue'
import SubscriptionView from '@/view/home/SubscriptionView.vue'
import TorrentsInfosView from '@/view/home/TorrentsInfosView.vue'
import LogsView from '@/view/home/LogsView.vue'
import ConfigView from '@/view/home/ConfigView.vue'
import {startupPage} from '@/js/global.js'

const startupPaths = ['/home', '/subscriptions']

const routes = [
    {
        path: '/',
        redirect: () => startupPaths.includes(startupPage.value) ? startupPage.value : '/home'
    },
    {
        path: '/home',
        component: DashboardView
    },
    {
        path: '/subscriptions',
        component: SubscriptionView
    },
    {
        path: '/downloads',
        component: TorrentsInfosView
    },
    {
        // 以下为新页面：走路由懒加载，避免把首屏体积顶上去
        // （3.2.34 刚把 main.js 从 1007.7KB 压到 316.7KB，不能因为加页面而回归）
        path: '/library',
        component: () => import('@/view/home/LibraryView.vue')
    },
    {
        path: '/history',
        component: () => import('@/view/home/HistoryView.vue')
    },
    {
        // 工具页：自检 / 日历 / 手动补种，用 ?tab= 深链
        path: '/tools',
        component: () => import('@/view/home/ToolsView.vue')
    },
    {
        path: '/logs',
        component: LogsView
    },
    {
        path: '/settings',
        component: ConfigView
    },
    {
        path: '/subtitle-match',
        component: () => import('@/view/home/SubtitleMatchView.vue')
    }
]

const router = createRouter({
    history: createWebHashHistory(),
    routes
})

export default router
