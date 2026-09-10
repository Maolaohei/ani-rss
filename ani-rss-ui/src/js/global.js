import {useColorMode, useDark, useDebounceFn, useEventListener, useLocalStorage} from "@vueuse/core";
import {ref} from "vue";
import {ElMessage} from "element-plus";

/**
 * 保存登录信息
 */
let rememberThePassword = useLocalStorage('rememberThePassword', {
    remember: false,
    username: '',
    password: ''
})

/**
 * 令牌
 */
const authorization = useLocalStorage('authorization', '')

/**
 * 主题管理
 */
const {store} = useColorMode()

/**
 * 最大内容宽度
 */
const maxContentWidth = useLocalStorage('max-content-width', 1600);

/**
 * 显示评分
 */
const showScore = useLocalStorage('show-score', true)

/**
 * 按星期展示
 */
const showWeek = useLocalStorage("show-week", true)

/**
 * 显示视频列表
 */
const showPlaylist = useLocalStorage('show-playlist', true)

/**
 * 显示更新时间
 */
const showLastDownloadTime = useLocalStorage("show-last-download-time", true);

/**
 * 强调色
 */
const color = useLocalStorage('--el-color-primary', '#409eff')

/**
 * 十六进制混色：a 以 percent 权重与 b 混合
 * 不依赖 CSS color-mix()，兼容旧移动端浏览器（iOS < 16.2 / 旧 WebView）
 */
const mixHex = (a, b, percent) => {
    const pa = percent / 100
    const pb = 1 - pa
    const ar = parseInt(a.slice(1, 3), 16)
    const ag = parseInt(a.slice(3, 5), 16)
    const ab = parseInt(a.slice(5, 7), 16)
    const br = parseInt(b.slice(1, 3), 16)
    const bg = parseInt(b.slice(3, 5), 16)
    const bb = parseInt(b.slice(5, 7), 16)
    const to = n => Math.round(n).toString(16).padStart(2, '0')
    return '#' + to(ar * pa + br * pb) + to(ag * pa + bg * pb) + to(ab * pa + bb * pb)
}

/**
 * 改动强调色
 * 同步派生 light-3/5/7/8/9 与 dark-2 变体，
 * 修复此前仅更新主色导致按钮 hover / 浅色底失效的问题。
 */
const colorChange = (v) => {
    if (!v) {
        return
    }
    const el = document.documentElement
    el.style.setProperty('--el-color-primary', v)
    if (!/^#[0-9a-fA-F]{6}$/.test(v)) {
        // 非标准 6 位十六进制（如透明/异常值）时仅设置主色，变体保持 EP 默认
        return
    }
    const dark = document.documentElement.classList.contains('dark')
    const base = dark ? '#141414' : '#ffffff'
    el.style.setProperty('--el-color-primary-light-3', mixHex(base, v, 30))
    el.style.setProperty('--el-color-primary-light-5', mixHex(base, v, 50))
    el.style.setProperty('--el-color-primary-light-7', mixHex(base, v, 70))
    el.style.setProperty('--el-color-primary-light-8', mixHex(base, v, 80))
    el.style.setProperty('--el-color-primary-light-9', mixHex(base, v, 90))
    el.style.setProperty('--el-color-primary-dark-2', mixHex('#000000', v, 20))
}

/**
 * 是否非移动设备
 */
const isNotMobile = ref(false)

/**
 * el-icon的class
 *
 * 自动适应移动布局
 */
const elIconClass = ref('')

/**
 * 主题初始化
 */
const initTheme = () => {
    /**
     * 夜间模式
     */
    useDark({
        onChanged: dark => {
            // 自动根据夜间模式修改沉浸式状态栏
            const meta = document.getElementById('themeColorMeta');
            // 与 tokens.css 的实际页面底色保持一致，避免状态栏与内容割裂
            meta.content = dark ? '#141416' : '#f5f5f7';
            // 明暗切换后按对应底色重新派生强调色变体
            colorChange(color.value)
        }
    })

    // 修改强调色
    colorChange(color.value)
}

/**
 * 布局初始化
 */
const initLayout = () => {
    let app = document.querySelector('#app');

    // 设置最大布局宽度；尊重用户设置，仅在低于可用最小值时才夹紧
    if (maxContentWidth.value < 1200) {
        maxContentWidth.value = 1200
    }

    app
        .style.maxWidth = `${maxContentWidth.value}px`

    const el = document.documentElement
    el.style.setProperty('--max-content-width', `${maxContentWidth.value}px`)

    // 是否非移动设备
    isNotMobile.value = app.offsetWidth > 800

    if (isNotMobile.value) {
        elIconClass.value = 'el-icon--left'
    } else {
        // 用以控制图标与文字的间距 当为移动设备时便不需要间距了
        elIconClass.value = ''
    }
}

/**
 * 初始化
 */
const init = () => {
    initTheme()
    initLayout()
}

/**
 * 当页面大小变化时重新计算一下布局
 * 对方法做节流处理
 */
useEventListener(window, 'resize', useDebounceFn(initLayout, 500))

const base64Encode = s => {
    const encoder = new TextEncoder();
    const data = encoder.encode(s);
    return window.btoa(String.fromCharCode(...data));
}

const getBaseUrl = () => {
    const {protocol, host, pathname} = location
    return `${protocol}//${host}${pathname}`
}

const toApiUrl = (path, params) => {
    const url = new URL(getBaseUrl())
    url.pathname += path
    url.search = new URLSearchParams(params).toString()
    return url.toString();
}

const proxyImage = imgUrl => {
    return toApiUrl('api/proxyImage', {
        imgUrl: base64Encode(imgUrl),
        s: authorization.value
    })
}

const toApiFile = filename => {
    return toApiUrl('api/file', {
        filename: base64Encode(filename),
        s: authorization.value
    })
}

/**
 * 复制文本到剪贴板。
 *
 * 优先使用异步 Clipboard API（iOS Safari 与非安全上下文下 execCommand 会静默失败，
 * 而此前各调用点无论成功与否都提示“已复制”）。
 */
const copyText = async (text) => {
    const value = String(text ?? '')
    if (!value) {
        ElMessage.warning('没有可复制的内容')
        return false
    }

    try {
        if (navigator.clipboard && window.isSecureContext) {
            await navigator.clipboard.writeText(value)
            ElMessage.success('已复制')
            return true
        }
    } catch (e) {
        // 继续走兜底方案
    }

    try {
        const input = document.createElement('textarea')
        input.value = value
        input.setAttribute('readonly', 'readonly')
        input.style.position = 'fixed'
        input.style.top = '-1000px'
        input.style.opacity = '0'
        document.body.appendChild(input)
        input.select()
        input.setSelectionRange(0, value.length)
        const ok = document.execCommand('copy')
        document.body.removeChild(input)
        if (!ok) {
            throw new Error('execCommand copy returned false')
        }
        ElMessage.success('已复制')
        return true
    } catch (e) {
        ElMessage.error('复制失败，请手动选择文本复制')
        return false
    }
}

export {
    rememberThePassword,
    authorization,
    store,
    maxContentWidth,
    showScore,
    showWeek,
    showPlaylist,
    showLastDownloadTime,
    color,
    colorChange,
    isNotMobile,
    elIconClass,
    init,
    initTheme,
    initLayout,
    base64Encode,
    toApiUrl,
    proxyImage,
    toApiFile,
    getBaseUrl,
    copyText
};
