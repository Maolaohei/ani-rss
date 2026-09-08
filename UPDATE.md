# 更新日志（本 Fork）

基线版本号：对齐上游 **3.2.22**（仅版本号；代码为本 fork 增量，已选择性移植上游 3.2.10~3.2.22 有价值提交，未完整合入全部行为变更）。

下列为相对该基线后的主要增量；更细提交见 git log。

## 相对上游：我们多做了什么

| 方向 | 说明 |
| --- | --- |
| OpenList/AList 离线可靠性 | 硬超时清理、分级轮询、本集完成判定、10008 冷却、残留 adopt、启动回扫、卡住自动重提、云下载兜底归位 |
| 记录信任校验 | 归位对账 → 兜底检查（只认视频文件）→ 清理重下 三级链路，杜绝「幽灵已存在」永久漏下 |
| 可观测与干预 | 首页「任务管理器」：多槽位状态、取消、排队/抢先、残留扫描清理、遗留问题修复 |
| 调度一致性 | 手动刷新 / 添加订阅统一走可观察入口，避免 UI 空闲却只剩 Hash |
| 通知与体验 | Bark 通知（含 Level/Volume）；默认禁用自动检查更新；通知反压不阻塞下载 |
| 运维 UX | 失败人话化/失败队列原子写+精确重下、列表健康分（含缓存漏集）、临时目录残留扫描优化 |
| TMDB 匹配 | 中文标题误匹配防御 + 日文原名兜底 + 候选列表手动选择弹窗 |

---

## 3.2.31 增量（2026-09）

### 稳定性专项：全部 P0/P1 审计条目修复（45 文件，+2322/−900）

**P0（生产故障级，5 项全修）**
- **任务线程自愈**：`BaseTask.run` 捕获 Throwable 后 60s 退避续跑，单次异常不再导致对应定时任务永久停摆；BGM 评分/总集数、重命名等任务拆箱 NPE 全项目防护（`Boolean.TRUE.equals` / `ObjectUtil.defaultIfNull`）
- **RSS 全局锁防挂死**：收尾等待 5 分钟整体 deadline；`getBean`/线程池提交失败兜底释放幽灵锁；手动刷新抢先收进生命周期锁并做世代重验，排队请求不再被误杀
- **配置文件防损坏死循环**：首次启动 `config.v2.json`/`ani.v2.json` 改 temp+move 原子写；解析失败自动改名 `.corrupt-<时间戳>` 保留现场并以默认配置继续启动（不再 exit 循环）
- **通知模板防无限递归**：全局模板递归前剥离 `${notification}`；重试逻辑捕获 Throwable（StackOverflowError 只记录不重试），`send()` 返回值纳入重试判定
- **OpenList 孤儿 pending**：启动即清（远端任务由 10008/adopt 逻辑接管），该集下轮自动重下，不再永久静默跳过

**并发与配置（节选）**
- `ConfigUtil.CONFIG` 改 volatile 快照（copy-on-write）：`updateFromApi` 锁内深拷贝合并后原子交换，RSS/下载线程不再读到撕裂配置；爱发电激活同步收敛为锁内合并
- `TaskService.stop` 30s 有界等待；下载器/Aria2 锁内网络调用与 sleep 全部外移；BGM `setToken`/`getSubjectId` 去类锁，全站流量不再被串行化
- 登录限流迁移独立存储（1 天固定窗口、原子计数、401 不再误计数）；X-Forwarded-For 改信任代理链解析，防伪造绕过白名单

**下载器可靠性**
- qBittorrent：SID 失效自动重登一次 + 全字段守护 + 查询异常上抛（杜绝"无任务/查询失败"混淆导致的坏种误报删记录）
- Transmission：RPC 统一封装（409 握手重试），会话按 host 隔离，测试登录不再偷换运行会话
- Aria2：JSON-RPC error 体判定，登录假成功/删除假成功修复
- OpenList：同 hash 等待 60s 上限、开工算超时（排队不再挤占窗口）、按 hash 隔离的取消语义、重试次数判空、等待池改有界队列 + 拒绝策略
- 非 OpenList 路径：失败队列 24h 内同集跳过，坏种/密钥错误不再每轮 RSS 无限重下重推

**数据完整性与解析防御**
- 备份/导入/清理全链路"先校验、暂存、原子替换"：坏 zip 不再删种子缓存，nfo 写入原子化，临时目录整树误删防护（Season/Specials 黑名单 + 集数标识校验）
- 日志：解析失败兜底挂载输出（不再零日志黑洞）；文件日志 50MB/1GB 滚动上限
- Mikan/BGM/AniBT/AnimeGarden/Github/ICS/RSS 全边界判空 + 单条隔离：一条脏数据/改版页面不再打挂整页或整个订阅
- Cloudflare 挑战页/429/5xx 明确报错；搜索关键词 URL 编码修复特殊标题静默搜索失败

**Web 安全面**
- 图片代理路径穿越封堵（段白名单 + normalize 断言，`../` 不再可达 config.yaml）；SSRF 校验覆盖 IPv6/十进制 IP/链路本地/DNS 重解析（含重定向环深度上限）
- 上传接口流式 MD5 + 扩展名白名单（拒绝 svg/html）；播放列表软链环防护 + 字幕 20MiB 上限；WebUI 更新解压改暂存目录 + 失败回滚

验证：`mvn compile` 全绿；既有解析测试通过（tv 99.77% / movie 100% / ova 100%）。

---

## 3.2.30 增量（2026-09）

### UI 新版皮肤
- **设计令牌 `src/css/tokens.css`**：新版圆角/阴影/弹层观感与深色配色（页面底 `#141416`、面板 `#202023`、弹层 `#26262a`），`index.html` 与 `bgm-oauth-callback.html` 双入口接入；`style.css` 迁除旧深色变量（旧 `#2D2E2F` 系）避免覆盖新令牌。仅覆盖 EP 设计变量，不动任何业务逻辑与配置字段，切换 UI 不影响用户配置
- **组件适配层**：浅色灰底页面（`#f5f5f7`）/文字灰阶/边框与填充透明度体系/苹果系状态色板（light-9 预计算静态 hex，旧浏览器兼容）/细滚动条/遮罩毛玻璃；默认按钮经 EP 变量覆写为灰底无边框（`:where` 零特异性，类型按钮配色不受影响）、输入框灰底融合聚焦主色描边、分段式页签、表格圆角行悬停、卡片悬停轻抬、开关选中态成功绿、弹层苹果弹簧动效
- **主题色变体修复**：`colorChange` 同步派生 `light-3/5/7/8/9` 与 `dark-2`（JS 十六进制混色，不依赖 CSS `color-mix()`，旧移动端浏览器同样生效），`useDark` 切换后自动重算——修复自定义主题色下按钮 hover / 浅色底失效的问题
- **重命名模板增强**：新增 `RenameTemplateTools.vue`（官方文档 17 个变量 chip 点选追加、⚡预设一键覆盖——官方 EMBY 标准格式 / 剧场版电影格式、"当前模板效果"实时预览与未知变量告警），`Rename.vue` 两个模板输入框接入；纯展示层组件，config 字段与保存逻辑不变
- **毛玻璃 sticky 顶栏**：首页列表由内层滚动改为页面级滚动，`#header` 吸顶 + 78% 页底色毛玻璃（blur 20px/saturate 180%），滚动后加深至 90%，底部以两端渐隐的发丝线 + 柔影收边、顶缘白色细缘受光（亮暗双态）；老浏览器回退纯色、reduced-transparency 关闭模糊；滚动监听 passive + 卸载清理
- **组件适配层补全（系统迁移批次）**：alert（info 变体转主色调）、checkbox（16px/圆角 5px 方块）、dropdown-menu（宽松圆角条目）、empty（描述 13px）、progress（4px 圆角条 + 苹果曲线动画）、upload-dragger（1.5px 虚线 + 主色悬停）、tag--info（中性灰底）、select 下拉（条目圆角 + 单选右对勾）、badge 圆点（白圈浮起）——全部按演示页规则经 EP 变量/低特异性选择器移植；15 个演示页未设计的组件（avatar/color-picker/date-picker/descriptions/divider/form/input-tag/link/popconfirm/radio/rate/scrollbar/text/tooltip）保持 EP 原生
- **登录页卡片化**：按演示页 dlg-login 规格——380px 圆角卡片 + 投影 + 18px/700 标题 + 居中链接行（原 200px 裸表单）
- **导入数据弹窗旧皮肤清理**：21 处旧版硬编码色值（#409eff/#303133/#f0f9ff 等）全部替换为 token 变量，深色模式不再出现刺眼浅蓝块
- **意外实现排查修复（两处 token/选择器外溢）**：① `--el-mask-color` 深幕布外溢 v-loading 加载遮罩——已改专属轻磨砂白纱；② `.el-radio-group` 灰底药丸样式外溢普通圆点单选（Ani 类型/ImportAni 冲突/Bangumi 获取方式/AniBT/AG/Mikan 匹配组共 6 处）——药丸收窄为显式 opt-in `.dsh-segmented`（外观三态/下载排序两处挂类保持分段观感），圆点组回归 EP 原生
- **细节修复**：修改订阅弹窗"基本/自定义"页签等宽居中（此前内容宽度排版导致偏离中心）；订阅卡封面悬停轻放大（容器裁切防溢出）+ 操作按钮悬停浮现/触屏常显；`el-radio-button` 分段化（外观三态/下载工具，选中白底浮起、深色自适应）；`el-input-number` 步进按钮悬停反馈；弹层圆角 12px；弹窗 footer 顶部分隔线；按钮 `:focus-visible` 键盘焦点环；弹窗底部操作条窄屏适配。注：确认框的 `is-has-bg` 为 EP 官方类（自带样式），无需自定义

---

## 3.2.28-fork 增量（2026-09）

### 版本
- chore: 同步上游版本号至 **3.2.28**（`pom.xml` / 子模块 parent version）；上游 3.2.23~3.2.28 共 15 个提交（发布在 `test` 分支，`master` 仍停在 v3.2.22），逐个评估后仅移植 2 个修复，UI 美化类重构（背景色/图标/表格布局/按钮/首页订阅过滤）与日志优化未搬入
- port: **AniBT 部分种子缺失修复**（上游 `3a719665`，`ItemsUtil`）：RSS 条目缺 `.torrent` 文件时回退 `contentLength`/`magneturi` 字段，非种子 `link` 不再丢弃整个条目（可能已被 magneturi 兜底）
- port: **订阅封面回填**（上游 `2147d525`，`AniUtil`）：`saveCover` 返回值写回 `ani.cover`，补全缺失封面；本 fork `CopyOptions.setOverride(false)` 拷贝语义一致，回填不会被模板值覆盖

### 代码简化（奥卡姆剃刀）
- **云下载认领口径统一**：主扫描兜底（`scanEpisodeFilesOnce` 云分支）此前是唯一不带标题守卫的平行实现，现与 Error/Failed 检查、10008 等待、超时终检、归位对账共用 `findCloudDownloadEpisodeVideos`（集数匹配 + 目录链守卫 + 原样结构兼容）；同链字幕同样受守卫约束
- **判定参数一次解析**：`expectedEpisodes`/`expectedSeason`/`titleTokensOf` 提升到方法入口，等待循环与归位对账不再逐文件/逐轮重建
- **清理请求量约降 3/4**：`purgeJunkAndEmptyDirsBottomUp` 返回子树空标志，垃圾/空子目录合并批量删除，列表复用
- **空壳链上溯单规则化**：`deleteEmptyChainUnderCloudRoot` 双防护分支合一为「不越过 effectiveRoot」，魔数界改为深度派生
- **冗余删除**：字幕过滤恒冗余的 `cloudSourceDirs` 析取、`forceRemoveTree` 死缓存失效（`fsList(refresh=true)` 不经 findFilesCache）

---

## 3.2.22-fork 增量（2026-08）

### 版本
- chore: 同步上游版本号至 **3.2.22**（`pom.xml` / 子模块 parent version）；选择性移植 3.2.16~3.2.22 中的三个有价值提交（db1295ca / e497071f / 94afa0b1），未合入 3.2.19「全新的页面」等 UI 大改。

### qBittorrent 内容布局
- 新增 `qbContentLayout` 配置项：原硬编码 `contentLayout="Original"` 改为配置，前端下拉可选 原始 / 创建子文件夹 / 不创建子文件夹（`qBittorrent.vue` + `Config` + `ConfigUtil` 默认 `"Original"`；`qBittorrent.java` / `CollectionController.java` 两处提交点均改为读配置）。

### WebUI 更新 / 上传（新子系统）
- 新增 `WebUIController` / `WebUIService` / `GithubService` / `UpdateInfo` / `WebUI`：读取 `configDir/webui/webui.json` 元数据（owner/repo/version/filename）。
  - `/webui/getUpdate`：检查对应 GitHub release，返回更新信息（sha256 / size / 跨小版本自动更新判定）。
  - `/webui/update`：下载并校验 sha256 后解压覆盖 `webui/` 目录（与既有「备用 webui」覆盖机制兼容）。
  - `/webui/upload`：上传 zip（须含 `webui.json`）替换；`/webui/delete`：删除。
- 设置页新增 WebUI 上传入口（`Page.vue`）。

### OpenList 云下载兜底（原样下载结构支持）
- **标题守卫目录链感知**：115 原样下载（保留种子文件夹结构）时文件可能落在 `云下载/[组名] 番剧名/番剧名 XX.XX 13.mp4` —— 标题只在任务子目录名上，文件名是纯 `S01E03.mkv`；原守卫只看文件名导致这类文件永远不被认领，兜底形同虚设直至超时失败。新增 `cloudEntryLacksTitleToken`：文件名不含标题时再查「云下载根之下、文件所在目录之上」的目录链，任一含标题别名即放行；Error/Failed 检查、10008 无 tid 等待、超时终检、归位对账四条路径全部接入。
- **字幕随行修复**：`relocateEpisodeFiles` 云分支原字幕收集只递归 `savePath`，云下载目录下的字幕永不随视频移动（`cloudSourceDirs.contains` 过滤是死代码）；现从云下载源目录补充收集并按路径+名称去重，同样受目录链标题守卫约束。
- **残留深度清理**：原清理仅删除「单层完全为空」的直接源目录；现自底向上清理任务目录内的残留垃圾（`.aria2`/`.tmp`/`thumbs.db` 等）、删除清空后的空子目录（如 `Subs/`），再向上回溯删除空壳目录链（有内容即停；云下载根目录本身与根外路径受保护，仅限源目录链内）。
- 测试：`OpenListWorkflowSimulationTest` 新增原样结构端到端场景（视频归位 + 垃圾/空子目录/空壳链深度清理 + 相邻任务内容不受影响）与 10008 无 tid 路径目录链认领场景；`OpenListResidualPolicyTest` 新增守卫/相对路径纯函数单测。

### 说明
- 上游 `9721fa7b`（日志自动刷新 onMounted→onActivated）**未搬入**：fork 的日志是 `el-dialog` 常驻挂载、`show()` 每次显式 `getLogs()` 重拉，无 keep-alive，onActivated 不生效且强搬会回归。

---

## 3.2.15-fork 增量（2026-08）

### 版本
- chore: 同步上游版本号至 **3.2.15**（`pom.xml` / 子模块 parent version）
- port: webui 加载优化 + 默认启用标题年份/TMDB 标题、备用 webui 支持、播放器修复（Range 长度差一、infuse/SenPlayer URL 编码）、删除种子修复

### 新增
- **新种子下载等待** `newTorrentWaitHours`（默认 2 小时）：发布不足 N 小时的种子暂缓下载，离线场景云端无人做种不再立即提交只会超时失败反复重提；洗版条目不拦截
- 预览界面**强制下载**（删除已有文件后重新下载），强制下载前预检归位可免重复提交
- TMDB 支持标题搜索返回候选列表，前端弹窗手动选择修正误匹配

### OpenList 离线可靠性
- **「幽灵已存在」修复**：完成判定收紧（必须视频落盘顶层）+ 归位对账 + 强删递归 + 临时目录保护多槽化
- **记录信任三级校验**：归位对账（RELOCATED/ALREADY_AT_TOP 才信任）→ 兜底检查（下载器任务+网盘**只认视频扩展名**，空目录壳/字幕不再误判）→ 都没找到才清理记录重新下载
- 归位对账失败/未找到同步**失败队列**（任务管理器可见）；已记录本集跳过二次对账防无限重复，文件就位后自动清除过期记录
- 10008 云端去重残留进入 24h 长冷却；死循环与卡住任务自动重提（上限 2 次）
- 990009 异步容忍：假成功经移动校验兜住，不再误报完成
- 云下载路径兜底：115 完成后文件落在根「云下载」时自动识别移动归位 + 空壳清理；自动发现支持挂载点递归
- 文件操作全链路排除目录条目（115 任务目录不再误判为视频）

### 稳定性 / 性能
- 通知队列满(256)时丢弃并告警，不再中断订阅下载处理（反压防放大）
- 失败队列改 temp+rename **原子写**；解析失败保留原文件不静默清空
- 上传 HttpClient 单例复用（原每文件 new 且不关闭，批量上传累积 selector 线程）
- 下载路径索引 miss 重建加 60s 负缓存限频，消除 O(任务×订阅) 重算风暴
- RSS 拉取 last-success 回退写入短缓存，源持续故障时请求不再放大

### 重命名 / 识别
- 磁力早退短路、TR 假后缀过滤、文件级集数提取对齐、字幕语言白名单
- 剧场版电影式/OVA 特典式命名适配；真实种子文件结构/VCBD 整季包适配

---

## 3.2.9-fork 增量（2026-08）

### 版本
- chore: 同步上游版本号至 **3.2.9**（`pom.xml` / 子模块 parent version；不含上游 3.2.6~3.2.9 功能提交）
- merge: 合入上游 fix 修复 anibt 发布时间错误、订阅列表居中、下载位置 `${tmdbYear}` 变量

### 主要增量
- 任务管理器新增「遗留问题修复」（嵌套目录归位+空壳清理），扫描合并进清理，按钮 7→5
- OpenList 全流程模拟测试（内存 mock AList/115 API）+ 兜底真实触发断言
- 安全加固：认证与注入防护
- TMDB 中文标题误匹配防御 + jpTitle 兜底 + tmdb-api 升级 1.0.9

---

## 3.2.2-fork 增量（2026-07）

### 版本
- chore: 同步上游版本号至 **3.2.2**（`pom.xml` / 子模块 parent version；不含上游 3.2.1/3.2.2 功能提交）

### 新增 / 优化
- **RSS 任务管理器**（首页入口）：总览、槽位、刷新/扫描/清理/取消
- 手动刷新抢先 / 排队；OpenList 残留扫描与清理
- 列表健康分纳入缓存漏集；失败队列按单条精确重下
- OpenList 临时目录扫描：路径去重、浅层 list、活动目录保护
- 修复列表卡片有评分时误显示 RSS 地址

### 使用注意
- 任务管理器是**全局单 RSS 调度**的观察面板，不是并行历史队列
- 重启丢失内存调度态；OpenList 残留靠回扫/扫描恢复
- 升级后请 **完整重启**，否则可能仍跑旧前端
- 反馈请附：下载工具类型、离线超时配置、任务管理器截图与关键日志

---

## 历史摘录（更早）

- chore: 同步上游版本号至 3.2.0
- chore: 同步上游版本至 3.1.77
- refactor: 局部借鉴上游 syncLock/syncDownload，统一手动刷新入口
- feat: Bark 通知支持设置 Level、Volume
- feat: 增加 Bark 通知
