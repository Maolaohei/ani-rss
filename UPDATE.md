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

## 3.2.30 增量（2026-09）

### UI 新版皮肤
- **设计令牌 `src/css/tokens.css`**：新版圆角/阴影/弹层观感与深色配色（页面底 `#141416`、面板 `#202023`、弹层 `#26262a`），`index.html` 与 `bgm-oauth-callback.html` 双入口接入；`style.css` 迁除旧深色变量（旧 `#2D2E2F` 系）避免覆盖新令牌。仅覆盖 EP 设计变量，不动任何业务逻辑与配置字段，切换 UI 不影响用户配置
- **主题色变体修复**：`colorChange` 同步派生 `light-3/5/7/8/9` 与 `dark-2`（`color-mix` 按明暗底色计算），`useDark` 切换后自动重算——修复自定义主题色下按钮 hover / 浅色底失效的问题
- **重命名模板增强**：新增 `RenameTemplateTools.vue`（官方文档 17 个变量 chip 点选追加、⚡预设一键覆盖——官方 EMBY 标准格式 / 剧场版电影格式、"当前模板效果"实时预览与未知变量告警），`Rename.vue` 两个模板输入框接入；纯展示层组件，config 字段与保存逻辑不变

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
