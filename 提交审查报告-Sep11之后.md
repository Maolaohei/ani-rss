# 提交审查报告 — 2026-09-11 之后

**审查范围**：`a8ea757b` 回溯至 `23c2e945`，共 42 个提交、244 个文件、+26488 / −5979 行
**审查方式**：按模块分四路只读审查（字幕子系统 / 安全与鉴权 / v3.4.3 批量功能 / 订阅·合集·前端），关键结论均回到源码逐条复核
**结论**：**1 个 P0、4 个 P1** 需要优先处理，其余 18 条为 P2/P3

---

## P0 — 必须修

### 1. 只读令牌可通过 `/config` 拿到管理 `apiKey`，实现完全提权

**位置**
- `ani-rss/auth/ViewerPolicy.java:34` — `"/config"` 在只读白名单内
- `ani-rss/controller/ConfigController.java:88-97` — 只清空了 `login.password`
- `ani-rss/auth/AuthAspect.java:35` — 只按路径判定是否放行

**完整链路（已逐步复核）**

1. `auth/fun/ApiKey.java:30` — 只读令牌（`viewerApiKey`）通过 `@Auth` 鉴权
2. `AuthAspect.java:48-63` — `isViewerRequest()` 返回 `true`（与管理令牌不同值时）
3. `AuthAspect.java:35` → `ViewerPolicy.isReadOnlyAllowed("/api/config")` → 白名单命中，**放行**
4. `ConfigController.config()` 返回 `ObjectUtil.clone(ConfigUtil.CONFIG)`，仅 `login.password` 被置空
5. 只读用户拿到管理 `apiKey` → 用它调用 `/setConfig`、`/deleteAni`、`/addAni` 等任意写接口 → **只读边界完全失效**

**实际泄露的凭据字段**（`entity/Config.java`）

| 行号 | 字段 |
|---|---|
| 36 | `tmdbApiKey` |
| 81 | `downloadToolPassword` |
| 297 | `proxyPassword` |
| 413 / 425 / 431 | `bgmToken` / `bgmAppSecret` / `bgmRefreshToken` |
| 443 | **`apiKey`（管理令牌，提权关键）** |
| 624 | `githubToken` |
| 766 | `assrtToken` |
| 885 | `viewerApiKey` |

**建议**：`/config` 同时承担读取与保存语义（是 `POST`），无法用 HTTP 方法区分，因此最稳妥的做法是**把 `/config` 移出只读白名单**（只读用户本来也不需要看设置页），或在返回前把上表字段全部置空。仅清 `login.password` 远远不够。

---

## P1 — 功能不工作 / 必崩

### 2. `PreviewView.vue` 树形表格子行取数用错索引 → 数据错位、子集多时整表白屏

**位置**：`ani-rss-ui/src/view/home/PreviewView.vue` 共 15 处 `showItems[it.$index]`（53、60、61、64、70、76、77、84、91、92、94、95、98、106、113、120、127 行）

**理由（已在 element-plus 源码确认）**
- 表格开启了树形模式：`:data="showItems"` + `row-key="infoHash"` + `:tree-props="{children:'children'}"`（43-46 行）
- `node_modules/element-plus/es/components/table/src/table-body/render-helper.mjs:159`
  → `tmp.push(rowRender(node, $index + i, innerTreeRowData));`
  子行拿到的 `$index` 是**扁平合成索引**，不是 `showItems` 的下标
- 后果一：父行 0 带 3 个子集时，子行读到 `showItems[1..3]`，即**别的顶级订阅的数据**（"是否下载"、"本地存在"、"主RSS"、"来源"等列全部错）
- 后果二：子集数量超过剩余顶级行数时 `showItems[N]` 为 `undefined` → `undefined['episode']` 抛 TypeError → **整个表格渲染中断**
- 这是 `4fdf8bf4`（UI 迁移回上游架构）引入的回归；迁移前的 fork 版本用的是正确的 `it.row['episode']`

**建议**：全部改回 `it.row[...]`。文件内 `it.row` 当前出现 0 次，替换是纯机械操作。

### 3. 射手网压缩包字幕扩展名取错 → 写出 `.zip` 后缀文件，字幕永远不生效

**位置**：`ani-rss/service/SubtitleService.java:782`

```java
String originalName = StrUtil.blankToDefault(pick.getOriginalName(), c.getFileName());
String ext = StrUtil.blankToDefault(c.getExt(), "ass").toLowerCase();   // ← 问题行
String renamedName = expectedSubtitleName(mainName, ext, langTag);
```

**理由**
- `c` 是 ASSRT 搜索返回的**候选**（`SubtitleCandidate`）。压缩包候选的 `c.getExt()` 是 `"zip"`，不是字幕本身的扩展名
- 而 `originalName` 取自 `pick.getOriginalName()`，这个是正确的——`AssrtSubtitleProvider.java:531` 构造 `SubtitlePick` 时用的是压缩包**内层**文件名（如 `xxx.ass`）
- 于是 `expectedSubtitleName(mainName, "zip", tag)` 生成 `剧名 S01E01.zip`；`SubtitleService.java:675-676` 又把 `item.getExt()` 原样交给 `attachSubtitleBytes` 作为真实落盘扩展名
- 最终结果：预览里「改名后」显示 `.zip`，磁盘上也真的写出 `.zip` 文件，播放器不会把它识别为外挂字幕 → **压缩包来源的字幕 100% 失效**（散装 `.ass` 候选不受影响，因为 `c.getExt()` 恰好等于真实扩展名，所以平时不易发现）

**建议**：`String ext = FileUtil.extName(originalName)`，为空再兜底 `"ass"`。`originalName` 在两个分支下都已正确。

### 4. 删除订阅（`deleteFiles=true`）无任何目录边界校验

**位置**：`ani-rss/controller/AniController.java:245-271`

**理由**
- `files` 直接取 `downloadService.getDownloadPath(ani)`，随后 `FileUtil.del(file)` 递归删除
- 下载位置来自订阅的「自定义下载位置」或全局模板，可为 `D:/`、`/` 等任意绝对路径
- 模板为空时 `FileUtils.getAbsolutePath("")` 返回**进程工作目录**（即程序目录，内含 `config/`、`logs/`）→ 一次删除整棵树
- `clearParentFile` 还会继续向上清理空目录，扩大影响面
- 本批 `e72765da` 只在前端 `DelAniView` 加了确认弹窗与路径清单，**直接调用 API 或绕过前端仍可越界删除**

**建议**：后端删除前 `normalize()` 并校验路径位于允许的下载根之下，显式拒绝根路径、进程工作目录与层级过浅的目录。

### 5. `SubscriptionListView.vue` emit 漏传 `groupList` → 分组筛选整个不可用

**位置**：`ani-rss-ui/src/view/home/SubscriptionListView.vue:225-228`

```js
emit('loaded', {
  releaseDateList: releaseDateList.value,
  total: weekList.value.reduce((total, week) => total + week.items.length, 0)
  // ← 缺 groupList
})
```

**理由**
- 后端已经返回：`AniController.java:317-324` 组装 `groupList` 并 `listAni.setGroupList(groupList)`
- 父层读取：`SubscriptionView.vue:174` `groupList.value = data.groupList || []`
- 子组件没有把 `groupList` 透传出去，且整个 `SubscriptionListView.vue` 文件中 `groupList` 出现 0 次
- 结果：`SubscriptionView.vue:43` 的「分组」下拉 `v-for="it in groupList"` 永远为空 → 按分组筛选功能**完全不可用**（本批新功能之一）

**建议**：emit 里补 `groupList: data.groupList`。

---

## P2 — 特定条件下出问题

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| 6 | `service/DownloadService.java:214`、`:346` | 占位任务与文件在后续闸门**之前**被删。214 行早于「新种子等待」(302)、并发限制(397)、离线进行中(408)、24h 失败队列(415)；命中任一 `continue` 时占位已删、主 RSS 未下 → 该集在等待窗口（默认 2h）甚至更久内没有任何文件 | 把删除动作下移到 `download()` 之前（约 435 行） |
| 7 | `util/other/ConfigUtil.java:677-687` | 通知渠道的「系统通知」复选框**永远关不掉**。SYSTEM 补全写在 `format()` 里，而 `format()` 在启动(345) 和每次 `updateFromApi`(450) 都会跑，用户取消勾选保存后被静默加回 | 只在启动迁移时补一次（配置版本号或迁移标记），不要放在 `updateFromApi` 路径 |
| 8 | `controller/ShareController.java:214-228` | 分享码先 `ZipUtil.unGZip` **全量解压到内存**，之后才判 `raw.length > 2MB`。gzip 压缩比可达千倍，几 MB 输入即可展开成 GB 级数组，大小校验形同虚设 | 改用 `GZIPInputStream` 流式读，累计超 2MB 立即中止；同时限制入参长度 |
| 9 | `entity/QualityRule.java:59-61` + `util/other/ItemsUtil.java:915` | 质量规则 `enable=false` 时 `preferCollection` **仍然生效**。`effective()` 在订阅无自定义规则时返回全局 profile（即使已关闭），去重仍按合集/单集优先级跑，违反 `QualityProfile` 自述的「enable=false 行为零变化」 | `profile == null \|\| !profile.enabled() \|\| profile.preferCollectionOrDefault()` |
| 10 | `controller/DoctorController.java:249` | `HttpReq.get(host, TIMEOUT).execute()` 未 close，且只读 status 不读 body。项目同类调用都走 try-with-resources 或 `HttpReq.thenClose`（`HttpReq.java:253` 注释「防止连接泄漏」）。该端点在只读白名单内，可被反复调用 | `HttpReq.thenClose(HttpReq.get(host, PROBE_TIMEOUT_MS), HttpResponse::getStatus)` |
| 11 | `util/other/AniUtil.java:619` `addCollectionAni` | 未走 `verify(ani)`。`verify` 会拦截标题含 `..`/`/`/`\`（防下载路径穿越）并断言 season 非空；合集路径直接落盘订阅，season 为 `null` 时 `getDownloadPath` 的 `int season = ani.getSeason()` 拆箱 NPE，且异常被 `buildDownloadPathIndex` 的 catch 吞成 debug → 订阅**静默失效** | 在 `addCollectionAni` 内先调用 `AniUtil.verify(ani)` |
| 12 | `view/home/TaskManagerView.vue:917-922` | `<KeepAlive>` 缓存路由页（`MainLayoutView.vue:71`），离开 `/downloads` 只触发 deactivated、不 unmount；组件只有 `onMounted/onUnmounted`，`while(!disposed…)` 轮询（2–4s 一次）**常驻后台**，与提交说明「卸载即停」不符 | 补 `onDeactivated(()=>{disposed=true; pollToken++})` + `onActivated(show)` |
| 13 | `view/home/TorrentsInfosView.vue:121,137` | 裸 `localStorage.getItem` 无 try/catch，隐私模式/禁用存储时抛 SecurityError，且在 setup 顶层执行 → **整页空白**。同仓 `play-progress.js` 已做兜底 | 包 try/catch 或复用现成的安全封装 |
| 14 | `view/home/SubscriptionListView.vue:216-236` | `getList` 无请求序号守卫。刷新按钮、编辑保存后的 `$reLoadList()`、合集添加后刷新会并发触发，先发的慢响应回来会覆盖新数据并把 `loading` 提前置 false。同批提交里 PreviewView / TaskManagerView 都加了 `loadVersion`/`seq`，此处漏了 | 加 `loadVersion` 递增比对后再赋值 |
| 15 | `view/home/LogsView.vue:222` | 用 `res.ok` 判断失败，但 `CustomExceptionHandler` 以 **HTTP 200 + body `{code:403/500}`** 返回；token 过期或用只读令牌（`/downloadLogs` 不在白名单）时 `res.ok` 为 true，错误 JSON 被存成 `ani-rss-logs-*.zip` | 校验 `content-type`，或先 `text()` 判断是否 JSON 且 `code != 200` |
| 16 | `service/SubtitleService.java:315`（`createdAt` 字段初始化）+ `:629`（`new FetchPlan(...)` 在扫描**开始**时构造） | ASSRT 限流默认 5 req/min，长季扫描（如 24 集 × 6 候选 ≈ 144 次请求）可能超过 30 分钟 TTL，用户点「确认」时计划已过期 → 白跑一轮限流配额 | `createdAt` 改为扫描**完成**时赋值 |

---

## P3 — 健壮性

| # | 位置 | 问题 |
|---|---|---|
| 17 | `download/OpenList.java:1505` `buildCollectionPlanRenameMap` | `renameMap` 以**文件名**为键，跨目录同名文件互相覆盖 → 第二个文件不入 map，不重命名也不移动，最终被 `cleanupTempDownloadDir(force=true)` 一并删除，**合法产物丢失**。建议键改为「路径+文件名」，冲突时告警并保留 |
| 18 | `entity/web/EventTypeEnum.java:15,38` | `DOWNLOAD_START`、`OMIT_DETECTED` 全项目**无任何发送点**，但设置页（`OtherView.vue:155`）把它们列为可用事件类型。建议补发送点或从文案移除 |
| 19 | `controller/LibraryController.java:207` | `invalidate()` 无调用者。媒体库有 60 秒缓存，增删订阅后无人失效 → 最多 60 秒显示已删除的订阅。建议在 `AniController` 增删订阅后调用 |
| 20 | `view/home/PlayListView.vue:7`、`TorrentsInfosView.vue:82`、`NotificationView.vue:16` | `v-for` 缺 `:key`，列表重排/筛选后按位置复用易错位（`NotificationView` 该行正是 `de99468a` 改动行） |
| 21 | `view/home/SubscriptionListView.vue:264-274,298-303` | `focusAniWithRetry` 的 4 个 `setTimeout` 未在 `onUnmounted` 清理；卸载只 `delete window.$focusAni`，遗留的 `$reLoadList` 指向已销毁实例的 `getList` |
| 22 | `view/home/SubscriptionView.vue:177-183` | `onClearFilter` 把 `enable` 写回 localStorage。`enable` 是 `useLocalStorage('select-enable','已启用')`，定位跳转清筛选会把它持久化成「全部」，**覆盖用户「只看已启用」的偏好** |
| 23 | `util/other/AniUtil.java:629-660` | `SUBSCRIPTION_LOCK` 临界区内做 `saveCover` 网络下载与 `sync` 落盘；封面 URL 不可达时阻塞至 HttpReq 超时，期间**添加/删除订阅全部排队** |

---

## 已验证为误报 / 已修复（无需再查）

- **`subtitleAttach` 的 languageTag 目录穿越** —— `f59f17de` 时期确实存在（当时只 `trim()` 就拼进文件名），但**当前已修复**：`SubtitleService.java:437` 走 `expectedSubtitleName` → `normalizeLangTag`（464-469 行，`[^A-Za-z0-9&._-]` 白名单 + 折叠 `..` + 去首尾点）。属历史问题，不必再动。
- `distinctByEpisodeWithQuality` 的 null episode 分支不会 NPE —— `ItemsUtil.java:395` 有 `.setEpisode(1.0)` 兜底。
- 旧订阅 / 旧配置兼容 —— `AniUtil.load()` 用 `BeanUtil.copyProperties(..., ignoreNullValue, override=false)` 补齐 `priority`/`group`/`tags`/`qualityProfile`；`ConfigUtil.format()` 启动即执行。
- `23c2e945` 备用 RSS 占位逻辑 —— `recordValid` 的 if/else 重构与原 `continue` 语义等价，无重复计数；占位标签 `TorrentsTags.BACK_RSS("备用RSS")` 写入端与识别端一致；`preferMaster` 仅 `standbyRss && delete && !coexist` 可达。
- 钉钉加签（已核对 hutool 5.8 `Mac.digestBase64(String,boolean)` 第二参为 URL-safe 开关）、企业微信 `truncate` 的 UTF-8 边界回退（`end=3800 < length` 不越界）。
- `RssTask` 优先级排序（先复制再稳定排序，`priorityOf` 钳制 null/越界）、`DownloadHistory` / `RssJobStateStore` 的原子写与容量裁剪、`OpenList` 的 `ctx.collectionPlan` 无跨线程竞态。
- `47bda885` 的性能优化未引入虚拟滚动或截断，筛选仍基于全量 `weekList`，搜索不漏结果。
- `RenameTemplateToolsView.vue` 的 `v-html` 全程经 `esc()` 转义，无 XSS；`parseTemplate` 无 `}` 时不会死循环。
- `PreviewView.vue:53` 的 `notDownload` 不会是 null（`AniUtil.load` 用 `copyProperties` 补齐空集合）；`AboutView.vue` 的 `res.code !== 200` 判断成立。
- 前后端字段契约：6 个新通知渠道字段、`rssConcurrency`/`diskWarnPercent`/`eventWebhook*`/`subtitleManualFetch` 均与 `Config` 匹配；History / Library / ManualSearch / Doctor 视图读取字段与 VO 一致。

---

## 建议处理顺序

1. **P0**（只读令牌提权）—— 改动最小、影响最大，先做
2. **P1-2**（PreviewView 索引）—— 纯前端机械替换，但会造成整表白屏
3. **P1-3**（字幕扩展名）—— 一行修复，恢复压缩包字幕功能
4. **P1-4 / P1-5**（删除越界、分组筛选）—— 前者安全，后者新功能完全不可用
5. P2 中的 6、7、8、11 属「会静默出错」类，建议紧随其后

---

## 修复完成状态（2026-09-15）

全部发现已修复并通过验证。后端 `./mvn-env.sh -o -f ani-rss-application/pom.xml test` 全量 **224 测试类 / 0 失败 / 1 跳过**；前端 `npx vite build` 通过。

| 等级 | 发现 | 修复文件 | 状态 |
|---|---|---|---|
| P0 | 只读令牌经 `/config` 提权读到 10 个凭据字段 | `ViewerPolicy.java`（新增 `sanitizeCredentials` 字段名正则脱敏）、`AuthAspect.java`、`ConfigController.java` | 已修 |
| P1-1 | `PreviewView.vue` 树表 `$index` 子行读错订阅 / 整表白屏 | `PreviewView.vue`（17 处 `showItems[it.$index]` → `it.row`） | 已修 |
| P1-2 | 射手网压缩包字幕扩展名错写成 `.zip` | `SubtitleService.java:782`（`FileUtil.extName(originalName)`）→ 兼修 `FetchPlan.createdAt` 改为 `stampCreatedAt` 修长番 TTL | 已修 |
| P1-3 | 删除订阅无目录边界校验，空模板删 CWD | `AniController.java`（`DeleteGuard.isSafeToDeleteRecursively` 闸门）+ 新增 `commons/DeleteGuard.java` | 已修 |
| P1-4 | 分组筛选 `groupList` 漏传 → 下拉恒空 | `SubscriptionListView.vue`（emit 补 `groupList` + `loadVersion` 请求序号守卫） | 已修 |
| P2 | `DownloadService` 占位文件删早于后续闸门 | `DownloadService.java`（拆 `findRemovableStandbyPlaceholder` / `removeStandbyPlaceholderTorrent`，删除移至 `saveTorrent.exists()` 之后；新增 `DOWNLOAD_START` / `OMIT_DETECTED` 事件） | 已修 |
| P2 | `ConfigUtil` 系统通知关不掉 | `ConfigUtil.java`（移除 `format()` 自动追加；`load()` 后 `migrateNotificationSystem` + `notificationSystemMigrated` 标志）、`Config.java` | 已修 |
| P2 | `ShareController` 先解压后校验（zip bomb） | `ShareController.java`（`decode` 先限长再 `ungzipLimited` 流式解压带累计大小上限） | 已修 |
| P2 | `ItemsUtil` profile 禁用却走默认偏好 | `ItemsUtil.java:942`（`profile == null \|\| !profile.enabled()` 视为无偏好） | 已修 |
| P2 | `DoctorController` HTTP 响应未关闭 | `DoctorController.java:249`（`try (HttpResponse ...)`） | 已修 |
| P2 | `AniUtil.addCollectionAni` 未走校验 | `AniUtil.java`（新增 `verifyCollectionAni`；`addCollectionAni` try/catch 包裹；`saveCover` 移出 `SUBSCRIPTION_LOCK`）、`CollectionController.java`（入口早校验） | 已修 |
| P2 | `OpenList.buildCollectionPlanRenameMap` 同名不同路静默覆盖 | `OpenList.java`（按名追踪 `mappedPathByName`，冲突抛 `IllegalStateException`） | 已修 |
| P2 | `LibraryController` 缓存未随订阅同步失效 | `LibraryController.java`（`invalidate()`）+ `AniController` 聚合 `syncAniList()` | 已修 |
| P3 | `TaskManagerView` 轮询在 `<KeepAlive>` 下不停止 | `TaskManagerView.vue`（`onActivated/onDeactivated/onUnmounted` + `stopPolling` + `pollToken`） | 已修 |
| P3 | `TorrentsInfosView` 隐私模式 `localStorage` 抛错白屏 | `TorrentsInfosView.vue`（`useLocalStorage` + `destroyed` 守卫 + `:key="tag"`） | 已修 |
| P3 | `SubscriptionView` 清筛选持久化用户偏好 | `SubscriptionView.vue`（`enableSession/groupSession` computed 会话级覆盖） | 已修 |
| P3 | `LogsView` 把 JSON 错误当 zip 下载 | `LogsView.vue`（先判 `content-type` / `application/json` 再解析） | 已修 |
| P3 | `PlayListView` / `NotificationView` 列表 `:key` 缺唯一性 | `PlayListView.vue`（`it.filename`）、`NotificationView.vue`（`\`${type}-${index}\``） | 已修 |

新增测试：`ViewerPolicyTest`（脱敏 4 例 + `isViewerRequest`）、`DeleteGuardTest`（7 例：拒 null/CWD/祖先/根/user.home，放正常下载目录与 CWD 相对路径）。
