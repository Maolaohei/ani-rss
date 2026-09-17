import api from "@/js/api.js";
import CryptoJS from "crypto-js";
import {authorization, base64Encode} from "./global.js";

/**
 * 获取设置
 * @returns {Promise<unknown>}
 */
export let config = () => api.post('api/config')

/**
 * 修改设置
 * @param config 设置
 * @returns {Promise<unknown>}
 */
export let setConfig = (config) => api.post('api/setConfig', config);

/**
 * 订阅列表
 * @returns {Promise<unknown>}
 */
export let listAni = () => api.post('api/listAni')

/**
 * 添加订阅
 * @param ani 订阅
 * @returns {Promise<unknown>}
 */
export let addAni = (ani) => api.post('api/addAni', ani)

/**
 * 修改订阅
 * @param move 自动移动本地文件
 * @param ani 订阅
 * @returns {Promise<unknown>}
 */
export let setAni = (move, ani) => api.post(`api/setAni?move=${move}`, ani)

/**
 * 删除订阅
 * @param deleteFiles 同时删除本地文件
 * @param ids ids
 * @returns {Promise<unknown>}
 */
export let deleteAni = (deleteFiles, ids) => api.post(`api/deleteAni?deleteFiles=${deleteFiles}`, ids)

/**
 * 关于
 * @returns {Promise<unknown>}
 */
export let about = () => api.post('api/about')

/**
 * 更新
 * @returns {Promise<unknown>}
 */
export let update = () => api.post('api/update')

/**
 * Fork 关于信息（fork 自维护的更新通道）
 * @returns {Promise<unknown>}
 */
export let forkAbout = () => api.post('api/forkUpdate')

/**
 * 执行 fork 更新
 * @returns {Promise<unknown>}
 */
export let doForkUpdate = () => api.post('api/doForkUpdate')

/**
 * 获取Mikan番剧列表
 * @param text 关键词
 * @param season 季度
 * @returns {Promise<unknown>}
 */
export let mikan = (text, season) => api.post(`api/mikan?text=${text}`, season)

/**
 * 获取Mikan番剧的字幕组列表
 * @param url 番剧url
 * @returns {Promise<unknown>}
 */
export let mikanGroup = (url) => api.post(`api/mikanGroup?url=${url}`)

/**
 * 获取AniBT番剧的字幕组列表
 * @param url 番剧url
 * @returns {Promise<unknown>}
 */
export let aniBTGroup = (url) => api.post(`api/aniBTGroup?bgmId=${url}`)

/**
 * 获取AnimeGarden番剧列表
 * @returns {Promise<unknown>}
 */
export let animeGardenList = (bgmUrl) => api.post(`api/animeGardenList?bgmUrl=${bgmUrl}`)

/**
 * 获取AnimeGarden番剧的字幕组列表
 * @param bgmId 番剧ID
 * @returns {Promise<unknown>}
 */
export let animeGardenGroup = (bgmId) => api.post(`api/animeGardenGroup?bgmId=${bgmId}`)

/**
 * 刷新全部订阅
 * @returns {Promise<unknown>}
 */
export let refreshAll = () => api.post('api/refreshAll')

/**
 * 刷新订阅
 * @param ani 订阅
 * @returns {Promise<unknown>}
 */
export let refreshAni = (ani) => api.post('api/refreshAni', ani)

/**
 * RSS 任务状态
 * @param options 透传 api 选项（任务管理器用 silent，自行展示常驻告警）
 * @returns {Promise<unknown>}
 */
export let rssJobStatus = (options) => api.post('api/rssJobStatus', undefined, options)

/**
 * 取消当前 RSS 任务
 * @returns {Promise<unknown>}
 */
export let rssJobCancel = () => api.post('api/rssJobCancel')

/**
 * 按条目取消 RSS/OpenList 任务
 * @param {string} id
 * @returns {Promise<unknown>}
 */
export let rssJobCancelItem = (id) => api.post('api/rssJobCancelItem', {id})

/**
 * 扫描 OpenList 离线残留
 * @returns {Promise<unknown>}
 */
export let rssJobResidualScan = () => api.post('api/rssJobResidualScan')

/**
 * 清理 OpenList 离线残留（含进行中；保护当前 hash）
 * @returns {Promise<unknown>}
 */
export let rssJobResidualClean = () => api.post('api/rssJobResidualClean')

/**
 * 修复 OpenList 遗留嵌套目录（归位+清理空壳）
 * @returns {Promise<unknown>}
 */
export let rssJobLegacyRepair = () => api.post('api/rssJobLegacyRepair')

/**
 * 扫描 OpenList 临时目录残留
 * @returns {Promise<unknown>}
 */
export let rssJobTempDirResidualScan = () => api.post('api/rssJobTempDirResidualScan')

/**
 * 清理 OpenList 临时目录残留（仅 FORCE/JUNK）
 * @returns {Promise<unknown>}
 */
export let rssJobTempDirResidualClean = () => api.post('api/rssJobTempDirResidualClean')

export let rssJobRecheckDownloaded = () => api.post('api/rssJobRecheckDownloaded')

/**
 * 失败下载队列
 */
export let failedDownloadQueue = () => api.post('api/failedDownloadQueue')
export let failedDownloadQueueRemove = (id) => api.post('api/failedDownloadQueueRemove', {id})
export let failedDownloadQueueClear = () => api.post('api/failedDownloadQueueClear')
export let failedDownloadQueueRetry = (id) => api.post('api/failedDownloadQueueRetry', {id})

/**
 * 将RSS转换为订阅
 * @param ani 订阅
 * @returns {Promise<unknown>}
 */
export let rssToAni = (ani) => api.post('api/rssToAni', ani)

/**
 * 预览订阅
 * @param ani 订阅
 * @returns {Promise<unknown>}
 */
export let previewAni = (ani) => api.post('api/previewAni', ani)

export let forceDownload = (ani, infoHashes) => api.post('api/forceDownload', {ani, infoHashes})

/**
 * 日志（倒序最新 N 条，默认 200，与后端 /logs limit 对齐）
 * @param limit 条数
 * @param options 透传 api 选项
 * @returns {Promise<unknown>}
 */
export let logs = (limit = 200, options) => api.post('api/logs', {limit}, options)

/**
 * 清理日志
 * @returns {Promise<unknown>}
 */
export let clearLogs = () => api.post('api/clearLogs')

/**
 * 获取TMDB标题
 * @param ani 订阅
 * @returns {Promise<unknown>}
 */
export let getThemoviedbName = (ani) => api.post('api/getThemoviedbName', ani)

/**
 * 搜索TMDB（返回候选列表）
 * @param ani 标题/ova
 * @returns {Promise<unknown>}
 */
export let searchThemoviedb = (ani) => api.post('api/searchThemoviedb', ani)

/**
 * 获取TMDB剧集组
 * @param ani 订阅
 * @returns {Promise<unknown>}
 */
export let getThemoviedbGroup = (ani) => api.post('api/getThemoviedbGroup', ani)

/**
 * 测试通知
 * @param notificationConfig 通知设置
 * @returns {Promise<unknown>}
 */
export let testNotification = (notificationConfig) => api.post('api/testNotification', notificationConfig)

/**
 * 新的通知
 * @returns {Promise<unknown>}
 */
export let newNotification = () => api.post('api/newNotification')

/**
 * 最近一次通知发送结果（运行期失败此前只在日志里，设置页看不到）
 * @returns {Promise<unknown>}
 */
export let notificationLastSend = () => api.post('api/notificationLastSend')

/**
 * 获取BGM标题
 * @param ani 订阅
 * @returns {Promise<unknown>}
 */
export let getBgmTitle = (ani) => api.post('api/getBgmTitle', ani)


/**
 * 搜索BGM条目
 * @param name 关键词
 * @returns {Promise<unknown>}
 */
export let searchBgm = (name) => api.post(`api/searchBgm?name=${name}`)

/**
 * 代理测试
 * @param url url
 * @param config 设置
 * @returns {Promise<unknown>}
 */
export let testProxy = (url, config) => api.post(`api/testProxy?url=${url}`, config)

/**
 * 下载列表
 * @param options 透传 api 选项（下载面板用 silent，自行区分“空/失败/过期”三态）
 * @returns {Promise<unknown>}
 */
export let torrentsInfos = (options) => api.post('api/torrentsInfos', undefined, options)

/**
 * 订单号校验
 * @param config 设置
 * @returns {Promise<unknown>}
 */
export let verifyNo = (config) => api.post('api/verifyNo', config)

/**
 * 更新总集数
 * @param force 强制
 * @param ids ids
 * @returns {Promise<unknown>}
 */
export let updateTotalEpisodeNumber = (force, ids) => api.post(`api/updateTotalEpisodeNumber?force=${force}`, ids)

/**
 * 批量刮削
 * @param force 强制
 * @param ids ids
 * @returns {Promise<unknown>}
 */
export let batchScrape = (force, ids) => api.post(`api/batchScrape?force=${force}`, ids)

/**
 * 批量 启用/禁用 订阅
 * @param value true/false
 * @param ids ids
 * @returns {Promise<unknown>}
 */
export let batchEnable = (value, ids) => api.post(`api/batchEnable?value=${value}`, ids)

/**
 * 批量设置订阅分组（group 传空串表示移出分组）
 * @param group 分组名
 * @param ids ids
 * @returns {Promise<unknown>}
 */
export let batchGroup = (group, ids) => api.post(`api/batchGroup?group=${encodeURIComponent(group || '')}`, ids)

/**
 * 导入订阅
 * @param anis 订阅列表
 * @returns {Promise<unknown>}
 */
export let importAni = (anis) => api.post('api/importAni', anis)

/**
 * 停止服务
 * @param status 0:重启 2:关闭
 * @returns {Promise<unknown>}
 */
export let stop = (status) => api.post(`api/stop?status=${status}`)

/**
 * 刷新封面
 * @param ani 订阅
 * @returns {Promise<unknown>}
 */
export let refreshCover = (ani) => api.post('api/refreshCover', ani)

/**
 * 获取评分
 * @param ani 订阅
 * @returns {Promise<unknown>}
 */
export let rate = (ani) => api.post('api/rate', ani)

/**
 * 进行评分
 * @param ani 订阅
 * @returns {Promise<unknown>}
 */
export let setRate = (ani) => api.post('api/setRate', ani)

/**
 * 获取下载位置
 * @param ani 订阅
 * @returns {Promise<unknown>}
 */
export let downloadPath = (ani) => api.post('api/downloadPath', ani)

/**
 * 刮削
 * @param force 强制 true/false
 * @param ani 订阅
 * @returns {Promise<unknown>}
 */
export let scrape = (force, ani) => api.post(`api/scrape?force=${force}`, ani)

/**
 * 获取当前BGM账号信息
 * @param ani 订阅
 * @returns {Promise<unknown>}
 */
export let meBgm = (ani) => api.post('api/meBgm', ani)

/**
 * 更新trackers
 * @param config 设置
 * @returns {Promise<unknown>}
 */
export let trackersUpdate = (config) => api.post('api/trackersUpdate', config)

/**
 * 获取Emby媒体库
 * @param config 设置
 * @returns {Promise<unknown>}
 */
export let getEmbyViews = (config) => api.post('api/getEmbyViews', config)

/**
 * 清理缓存
 * @returns {Promise<unknown>}
 */
export let clearCache = () => api.post('api/clearCache')

/**
 * 下载器测试
 * @param config 设置
 * @returns {Promise<unknown>}
 */
export let downloadLoginTest = (config) => api.post('api/downloadLoginTest', config)

/**
 * 获取TG最近消息
 * @param notificationConfig 通知配置
 * @returns {Promise<unknown>}
 */
export let getTgUpdates = (notificationConfig) => api.post('api/getTgUpdates', notificationConfig)

/**
 * 登录
 * @param user
 * @returns {Promise<unknown>}
 */
export let login = (user) => {
    user = JSON.parse(JSON.stringify(user))
    user.password = CryptoJS['SHA256'](user.password).toString()
    return api.post('api/login', user)
}

/**
 * 登录（登录页专用）
 *
 * 与 login 的唯一区别：不弹全局瞬态 toast、且 403（限流）不会触发
 * api.js 的「清令牌 + 1 秒后强制刷新」——那条路径会把错误提示当场抹掉，
 * 让用户完全看不到失败原因。登录页改用常驻 el-alert 展示。
 *
 * @param user
 * @returns {Promise<unknown>}
 */
export let loginInteractive = (user) => {
    user = JSON.parse(JSON.stringify(user))
    user.password = CryptoJS['SHA256'](user.password).toString()
    return api.post('api/login', user, {silent: true, skipAuthReload: true})
}

/**
 * 测试IP白名单
 * @returns {Promise<Response>}
 */
export let testIpWhitelist = () => fetch('api/testIpWhitelist', {method: 'post'}).then(res => res.json())

/**
 * 获取视频列表
 * @param ani 订阅
 * @returns {Promise<unknown>}
 */
export let playList = (ani) => api.post('api/playList', ani)

/**
 * 获取内封字幕
 * @param filename 视频文件路径
 * @returns {Promise<unknown>}
 */
export let getSubtitles = (filename) => {
    return api.post(`api/getSubtitles?filename=${base64Encode(filename)}`);
}

/**
 * 开始下载合集
 * @param info 合集
 * @returns {Promise<unknown>}
 */
export let startCollection = (info) => api.post('api/startCollection', info)

/**
 * 预览合集
 * @param info 合集
 * @returns {Promise<unknown>}
 */
export let previewCollection = (info) => api.post('api/previewCollection', info)

/**
 * 获取合集字幕组
 * @param info 合集
 * @returns {Promise<unknown>}
 */
export let getCollectionSubgroup = (info) => api.post('api/getCollectionSubgroup', info)

/**
 * 将指定id的BGM番剧转换为订阅
 * @param id BGM的ID
 * @returns {Promise<unknown>}
 */
export let getAniBySubjectId = (id) => api.post(`api/getAniBySubjectId?id=${id}`)

/**
 * 获取AniBT番剧列表
 * @param season 季度
 * @param bgmUrl
 * @param text
 * @returns {Promise<unknown>}
 */
export let aniBT = (season, bgmUrl, text) => api.post('api/aniBT', {
    season,
    bgmUrl,
    title: text
})

/**
 * 删除缓存的种子
 * @param id 订阅id
 * @param hash 种子hash
 * @returns {Promise<unknown>}
 */
export let deleteTorrent = (id, hash) => api.post(`api/deleteTorrent?id=${id}&hash=${hash}`)

export let importConfig = (file) => {
    const formData = new FormData();
    formData.append("file", file);
    return fetch('api/importConfig', {
        method: 'POST',
        body: formData,
        headers: {
            'Authorization': authorization.value
        }
    }).then(res => res.json())
}

export let ping = () => api.get("api/ping")
/* ==================== 系统自检（Doctor） ==================== */

/**
 * 系统自检：聚合下载器/通知/代理/磁盘/任务线程等检查项
 * @returns {Promise<unknown>}
 */
export let doctor = () => api.post('api/doctor')

/* ==================== 下载历史 ==================== */

/**
 * 下载历史列表
 * @param query { aniId?, result?, days?, limit? }
 * @returns {Promise<unknown>}
 */
export let downloadHistory = (query) => api.post('api/downloadHistory', query || {})

/**
 * 下载历史统计（总览 + 按天趋势）
 * @param query { days? }
 * @returns {Promise<unknown>}
 */
export let downloadHistoryStats = (query) => api.post('api/downloadHistoryStats', query || {})

/**
 * 移除下载历史条目
 * @param id 条目 id
 * @returns {Promise<unknown>}
 */
export let downloadHistoryRemove = (id) => api.post('api/downloadHistoryRemove', {id})

/**
 * 清空下载历史
 * @returns {Promise<unknown>}
 */
export let downloadHistoryClear = () => api.post('api/downloadHistoryClear')

/* ==================== 手动搜索补种 ==================== */

/**
 * 手动搜索补种（聚合主 RSS + 备用 RSS + 自定义 RSS）
 * @param query { aniId?, rssUrl?, rssLabel?, keyword?, onlyMissing?, limit? }
 * @returns {Promise<unknown>}
 */
export let manualSearch = (query) => api.post('api/manualSearch', query || {})

/**
 * 手动补种下单
 * @param aniId 订阅 id
 * @param item 条目
 * @returns {Promise<unknown>}
 */
export let manualDownload = (aniId, item) => api.post('api/manualDownload', {aniId, item})

/* ==================== 媒体库 ==================== */

/**
 * 媒体库列表
 * @param query { keyword?, onlyExisting? }
 * @returns {Promise<unknown>}
 */
export let library = (query) => api.post('api/library', query || {})

/**
 * 媒体库详情（某订阅的本地剧集）
 * @param aniId 订阅 id
 * @returns {Promise<unknown>}
 */
export let libraryDetail = (aniId) => api.post('api/libraryDetail', {aniId})

/**
 * 强制刷新媒体库缓存
 * @returns {Promise<unknown>}
 */
export let libraryRefresh = () => api.post('api/libraryRefresh')

/* ==================== 订阅分享 ==================== */

/**
 * 生成订阅分享码
 * @param ids 订阅 id 列表；all=true 时表示全部
 * @returns {Promise<unknown>}
 */
export let shareAni = (ids, all) => api.post('api/shareAni', {ids, all})

/**
 * 按分享码导入订阅
 * @param code 分享码
 * @param conflict SKIP | REPLACE
 * @returns {Promise<unknown>}
 */
export let importAniByCode = (code, conflict) => api.post('api/importAniByCode', {code, conflict})

/* ==================== 字幕 ==================== */

/**
 * 扫描缺失字幕
 * @param aniId 订阅 id
 * @returns {Promise<unknown>}
 */
export let subtitleScan = (aniId) => api.post('api/subtitleScan', {aniId})

/**
 * 就地附加字幕
 * @param payload { filename, content, ext, languageTag }
 * @returns {Promise<unknown>}
 */
export let subtitleAttach = (payload) => api.post('api/subtitleAttach', payload)

/**
 * 字幕开关状态
 * @returns {Promise<unknown>}
 */
export let subtitleStatus = () => api.post('api/subtitleStatus')

/**
 * 字幕匹配记录
 * @param limit 返回条数（后端上限 200）
 * @returns {Promise<unknown>}
 */
export let subtitleMatchLog = (limit) => api.post('api/subtitleMatchLog', {limit})

/**
 * 清空字幕匹配记录
 * @returns {Promise<unknown>}
 */
export let subtitleMatchLogClear = () => api.post('api/subtitleMatchLogClear')

/**
 * 批量导入本地字幕（multipart：订阅 id + 多个字幕文件）
 * @param formData FormData：aniId、files[]
 * @returns {Promise<unknown>}
 */
export let subtitleImport = (formData) => postFormData('api/subtitleImport', formData)

/**
 * 预览本地字幕导入（不写盘）：用于导入前二次确认
 * @param formData FormData：aniId、files[]
 * @returns {Promise<unknown>}
 */
export let subtitleImportPreview = (formData) => postFormData('api/subtitleImportPreview', formData)

/**
 * 搜索射手网(ASSRT)字幕候选：单次搜索，返回 { searchId, keyword, candidates, total }
 * 由用户从 candidates 里自行挑选，再调 subtitleFetchPreview 下载选中项。
 * @param aniId 订阅 id
 * @returns {Promise<unknown>}
 */
export let subtitleAssrtSearch = (aniId) => api.post('api/subtitleAssrtSearch', {aniId}, {silent: true})

/**
 * 预览射手网(ASSRT)字幕获取（不写盘）：下载用户选中的候选，返回 { planId, items, total, matched }
 * @param aniId 订阅 id
 * @param searchId 搜索结果 id（来自 subtitleAssrtSearch）
 * @param index 用户选中的候选序号
 * @returns {Promise<unknown>}
 */
export let subtitleFetchPreview = (aniId, searchId, index) =>
    api.post('api/subtitleFetchPreview', {aniId, searchId, index}, {silent: true})

/**
 * 执行射手网字幕写入：消费预览返回的 planId
 * @param planId 预览计划 id
 * @returns {Promise<unknown>}
 */
export let subtitleFetch = (planId) => api.post('api/subtitleFetch', {planId}, {silent: true})

/**
 * multipart 表单提交（绕开 api.js 的 JSON 封装，与 importConfig 同处理方式）。
 * 返回原始 { code, message, data }，由调用方判定业务结果。
 */
let postFormData = (url, formData) => {
    return fetch(url, {
        method: 'POST',
        body: formData,
        headers: {
            'Authorization': authorization.value
        }
    }).then(res => res.json())
}
