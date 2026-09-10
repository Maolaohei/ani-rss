import {ElMessage} from "element-plus";
import {authorization} from "@/js/global.js";

/**
 * 登录页专用错误码：由 api.js 抛出的 Error.code 携带，
 * 便于登录页区分「密码错误 / IP 白名单 / 限流」并给出可行动的文案。
 * 403 = 登录失败次数达到上限（限流），其余非 2xx 统一为登录失败。
 */
export const LOGIN_RATE_LIMITED_CODE = 403

let post = async (url, body, options) => {
    return await fetch_(url, 'POST', body, options);
}

let get = async (url, options) => {
    return await fetch_(url, 'GET', '', options);
}

let del = async (url, body, options) => {
    return await fetch_(url, 'DELETE', body, options);
}

let put = async (url, body, options) => {
    return await fetch_(url, 'PUT', body, options);
}

/**
 * 统一请求入口。
 *
 * 兜底三类此前会让界面「静默空白且无提示」的失败：
 *   1. 网络层失败（后端未启动 / 容器重启 / 断网）
 *   2. 响应体不是 JSON（反代 502、登录页 HTML、网关错误页）
 *   3. 业务层非 2xx（沿用原有 ElMessage.error）
 *
 * options:
 *   silent       —— 不弹全局错误提示（调用方自行展示）
 *   skipAuthReload —— 403 时不清理令牌、不 reload（登录页必须用，否则提示会被刷新抹掉）
 */
let fetch_ = async (url, method, body, options = {}) => {
    let {silent = false, skipAuthReload = false} = options
    let headers = {}
    if (authorization.value) {
        headers['Authorization'] = authorization.value
    }
    if (body) {
        headers['Content-Type'] = 'application/json'
    }

    let res
    try {
        res = await fetch(url, {
            method: method,
            body: body ? JSON.stringify(body) : null,
            headers: headers
        })
    } catch (e) {
        return Promise.reject(handleTransportError(e, silent))
    }

    if (!res.ok) {
        // 反代 / 网关层错误（502、503、504 等），或鉴权中间件直接拒绝
        const msg = `请求失败（HTTP ${res.status}）`
        if (!silent && (res.status === 502 || res.status === 503 || res.status === 504)) {
            ElMessage.error('服务暂不可用，请确认后端是否正在重启')
        }
        return Promise.reject(makeError(msg, res.status))
    }

    let data
    try {
        data = await res.json()
    } catch (e) {
        // 200 但不是 JSON：多为反代返回的 HTML 登录页 / 错误页
        const msg = '服务返回了非预期的内容，可能是反向代理或登录页拦截'
        if (!silent) {
            ElMessage.error(msg)
        }
        return Promise.reject(makeError(msg, res.status))
    }

    let {code, message, t} = data

    if (!checkTimestampRange(t, true)) {
        console.warn('与服务端时差超过30分钟')
    }

    if (code >= 200 && code < 300) {
        return data
    }

    if (!silent) {
        ElMessage.error(message)
    }
    if (code === 403 && !skipAuthReload) {
        authorization.value = ''
        setTimeout(() => {
            location.reload()
        }, 1000)
    }
    return Promise.reject(makeError(message, code));
}

let makeError = (message, code) => {
    let error = new Error(message || `请求失败${code ? `（${code}）` : ''}`)
    error.code = code
    return error
}

let handleTransportError = (e, silent) => {
    let error
    if (e && e.name === 'AbortError') {
        error = makeError('请求已取消')
    } else {
        let base = '网络请求失败，请确认 ani-rss 服务正在运行'
        if (typeof navigator !== 'undefined' && navigator.onLine === false) {
            base = '设备已离线，请检查网络连接'
        }
        error = makeError(base)
    }
    error.cause = e
    if (!silent) {
        ElMessage.error(error.message)
    }
    return error
}

export default {post, get, del, put}

let checkTimestampRange = (timestamp, isMilli = true) => {
    const ts = Math.floor(Number(timestamp));
    if (Number.isNaN(ts)) return false;
    const targetTime = isMilli ? ts : ts * 1000;
    const now = Date.now();
    // 30 分钟
    const range = 30 * 60 * 1000;
    const diff = Math.abs(now - targetTime);
    return diff <= range;
}
