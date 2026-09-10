import {createApp} from 'vue'
import Main from './Main.vue'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import {ElMessage} from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'

const app = createApp(Main)

// 全局错误兜底：未捕获的渲染/生命周期异常此前只进浏览器控制台，
// 用户侧表现为「点了没反应」或整块界面空白，没有任何提示。
app.config.errorHandler = (err, instance, info) => {
    console.error('[ani-rss] 未捕获异常', info, err)
    ElMessage.error(describeError(err))
}

// 未处理的 Promise 拒绝同理；做去重避免同类错误刷屏
const reportedErrors = new Set()
window.addEventListener('unhandledrejection', event => {
    console.error('[ani-rss] 未处理的 Promise 拒绝', event.reason)
    const message = describeError(event.reason)
    if (reportedErrors.has(message)) {
        return
    }
    reportedErrors.add(message)
    setTimeout(() => reportedErrors.delete(message), 5000)
    ElMessage.error(message)
})

const describeError = err => {
    const raw = err?.message || String(err || '未知错误')
    return raw.length > 120 ? `${raw.slice(0, 120)}…` : raw
}

// 引入图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component)
}
app.mount('#app')
