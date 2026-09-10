/**
 * 设置项搜索索引。
 *
 * 设置面板有 8 个页签、上百个字段，此前没有任何搜索能力：
 * 用户要找「OAuth 授权」得先猜到它藏在「基本设置 → Bangumi → 获取方式=自动获取」里，
 * 找「IP 白名单 / 反代」得知道它在「登录设置」。
 *
 * 这里维护"页签 → 关键词"的静态索引（含中英别名与同义说法），
 * 命中后自动切到对应页签并高亮关键词。索引是纯前端常量，
 * 不需要后端参与；新增设置项时同步补一行即可。
 */

/** 关键词里包含了常见的英文名、别名与用户口语说法 */
export const SETTING_SEARCH_INDEX = [
  {
    name: 'download',
    label: '下载设置',
    keywords: [
      '下载', '下载器', 'qbittorrent', 'qb', 'transmission', 'tr', 'aria2', 'openlist', 'alist',
      '地址', 'host', '用户名', '密码', 'apikey', 'api key', '保存位置', '下载目录', '路径',
      '下载工具', '限速', 'dllimit', '分享率', 'ratio', '做种', '连接数', '重试', '并发',
      '延迟下载', '离线', '离线超时', '云下载', '下载位置模板', '路径模板', '目录结构',
      '匿名', '分类', '标签', '校验', 'qbt', 'rpc'
    ]
  },
  {
    name: 'basic',
    label: '基本设置',
    keywords: [
      '基本', 'rss', '间隔', '刷新间隔', '周期', '订阅', '重命名', '模板', 'rename', 'renameTemplate',
      '命名', '文件名', '最大文件名长度', '剔除年份', '剔除tmdb', '字幕文件夹', '备用rss', '备胎',
      'standby', 'trackers', 'tracker', 'mikan', '蜜柑', 'bangumi', 'bgm', 'oauth', '授权', 'token',
      'access token', '代理', 'proxy', 'page', '页面', '外观', '主题', '深色', '夜间', '主题色',
      '排序', '最大内容宽度', '显示评分', '按星期', '视频列表', '自定义css', '自定义js', 'customjs',
      'customcss', 'webui', '刮削', 'scrape', 'tmdb', 'themoviedb', '备份', 'backup', '导出', '导入',
      '其他', 'other', 'api', 'github', '日志条数', '自动更新', '开机自启', '缓存', '自动备份',
      '网络协议', 'ipv4', 'ipv6', '调试', 'debug', '排除规则', '全局排除', '排除'
    ]
  },
  {
    name: 'exclude',
    label: '全局排除',
    keywords: ['排除', 'exclude', '全局排除', '正则', 'regex', '字幕组排除', '关键词排除', '过滤']
  },
  {
    name: 'proxy',
    label: '代理设置',
    keywords: ['代理', 'proxy', 'socks', 'http代理', '端口', '代理账号', '代理密码', '测试代理', '爬虫代理']
  },
  {
    name: 'login',
    label: '登录设置',
    keywords: [
      '登录', 'login', '账号', '用户名', '密码', 'password', '有效', '登录有效',
      '多端登录', '禁止公网', 'ip', '白名单', 'ipwhitelist', 'cidr', '反向代理', '反代', '限流',
      '尝试次数', '跨域', 'cors', 'origin', 'api key', 'apikey', 'emby api', 'ics'
    ]
  },
  {
    name: 'notification',
    label: '通知',
    keywords: [
      '通知', 'notification', '模板', 'telegram', 'tg', 'bot', 'bark', 'serverchan', 'server酱',
      '邮件', 'mail', 'smtp', 'webhook', 'shell', '系统通知', 'system', 'emby', '刷新媒体库',
      '文件移动', 'openlist 上传', '上传', '推送', '发送', 'retry', '状态'
    ]
  },
  {
    name: 'afdian',
    label: '捐赠',
    keywords: ['捐赠', '爱发电', 'afdian', '订单号', '激活', '赞助']
  },
  {
    name: 'about',
    label: '关于',
    keywords: ['关于', 'about', '版本', 'version', '更新', 'update', '重启', '关闭', '退出', 'logout', '日志', 'github']
  }
]

/**
 * 关键词命中过滤。
 * @param {string} keyword 用户输入
 * @returns {Array} 命中的索引项（无输入时返回空数组）
 */
export const matchSettings = keyword => {
  const kw = String(keyword || '').trim().toLowerCase()
  if (!kw) {
    return []
  }
  return SETTING_SEARCH_INDEX.filter(item => {
    if (item.label.toLowerCase().includes(kw)) {
      return true
    }
    return item.keywords.some(k => k.toLowerCase().includes(kw))
  })
}
