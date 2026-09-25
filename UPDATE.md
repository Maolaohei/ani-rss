# 更新日志（本 Fork）

基线版本号：对齐上游 **3.2.30**（仅版本号；代码为本 fork 增量，已选择性移植上游有价值提交，未完整合入全部行为变更）。

下列为相对该基线后的主要增量；更细提交见 git log。

## 相对上游：我们多做了什么

| 方向 | 说明 |
| --- | --- |
| OpenList/AList 离线可靠性 | 硬超时清理、分级轮询、本集完成判定、10008 冷却、残留 adopt、启动回扫、卡住自动重提、云下载兜底归位、**期望文件计划（字节数比对提前收尾 / 计划归位 / 部分成功可见化 / 重启恢复）** |
| 记录信任校验 | 归位对账 → 兜底检查（只认视频文件）→ 清理重下 三级链路，杜绝「幽灵已存在」永久漏下 |
| 可观测与干预 | 首页「任务管理器」：多槽位状态、取消、排队/抢先、残留扫描清理、遗留问题修复 |
| 调度一致性 | 手动刷新 / 添加订阅统一走可观察入口，避免 UI 空闲却只剩 Hash |
| 下载去重与洗版 | hash 忽略大小写去重（下载器回的 hash 大小写不保证）+ 备用RSS洗版改为「主RSS提交成功后才清理备用」+ 洗版清扫排除刚提交的主RSS任务、文件按提交前快照清理 |
| 通知与体验 | Bark 通知（含 Level/Volume）；默认禁用自动检查更新；通知反压不阻塞下载 |
| 运维 UX | 失败人话化/失败队列原子写+精确重下、列表健康分（含缓存漏集）、临时目录残留扫描优化 |
| TMDB 匹配 | 中文标题误匹配防御 + 日文原名兜底 + 候选列表手动选择弹窗 |
| 本地状态与缓存 | 「本地存在」三态判定（是 / 存疑 / 否，存疑不降级成"没有"）+ 订阅级快照缓存（分级 TTL / 单飞 / 版本化失效 / **成功即增量追加，不整份重列**）+ 订阅级读写锁（读不阻塞在下载上） |
| 网盘限流规避 | 令牌桶 + 请求合并 + 熔断指数冷却 + 分级 TTL + 每轮 API 预算（只计目录列举次数，大库不再被硬顶 200 卡成「存疑」）+ 列举缓存按目录树定向失效，预算耗尽显示「未确认」而非 0 集 |
| 稳定性与性能 | 任务代际化（停止超时后旧线程不再复活重复跑轮次）+ 可中断周期等待（停止响应 ≤500ms）+ JVM 参数修正（恢复 C2 JIT、堆转储、栈与 Metaspace）+ 网盘 API 去实例锁（一次慢列举不再阻塞整条链路）+ 外部接口成本治理（BGM 限流移到出网点、TMDB/Mikan 进程内缓存、字幕合集包不再重复整包下载）+ 落盘写放大治理（内容未变跳过 / 状态回写 500ms 合并 / 预览按实质变化落盘）+ 长阻塞出锁（更新下载、合集后处理、订阅项解析）+ 列表接口不再改写共享订阅对象 + 单订阅超时隔离（卡死只记失败不拖整轮）+ 下载器/网盘查询失败语义收紧（失败抛而非空，避免误判重推）+ 下载历史与失败队列落盘节流 |

---

## 3.4.22 增量（2026-09）

上一版收的是「启动恢复」上的宽容/严格混用；这一版把同一类问题在**清理路径**上收干净。
这一批比上一版更严重：上一版丢的是可以重建的计划快照，这一版删的是**网盘上不可恢复的文件**。

### 清理路径：查询失败不再被当成「目录为空」而整树删除

`OpenListApi.fsList()` 内部是 `catch (Exception) { return List.of(); }` —— **从不抛异常**。
清理路径上有 7 处直接用它判空，于是「网盘抖一下」被读成「这个目录是空的」，紧接着就是 `fs/remove`；
而 115 对非空目录的 `fs/remove` 是**整树删除**（本仓库自己的注释就写着这一点）——
一次抖动就能删掉仍含未归位视频的目录。多处 `try/catch` 也因此是死代码：被包的调用根本不抛。

| 位置 | 原先的判空 | 后果 |
| --- | --- | --- |
| `purgeJunkAndEmptyDirsBottomUp` | `fsList(childPath).isEmpty()` | 子目录被整树删 |
| `deleteEmptyChainUnderCloudRoot` | `!children.isEmpty()` | 沿「空链」上溯删掉非空目录 |
| `cleanupEmptyChain` | 同上 | 同上（任务管理器「遗留修复」） |
| `removeEmptyDirsBottomUp` | `after.isEmpty()` | 子目录被删 |
| `repairNestedUnder` 的顶层同名守卫 | `topNames` 取自宽容版 | 守卫失效 ⇒ `fsMove` **覆盖顶层已有成片** |
| `cleanupTempDownloadDir(force=false)` 的谨慎模式 | `listFilesWithRetry` 用宽容版 | 「确认没有受保护媒体」假成立 ⇒ 整树删临时目录 |
| 临时目录残留扫描的分类 | `fsList(tempPath)` 失败被读成「仅垃圾/为空」 | 预览判为可清理 ⇒ 一键清理整树删 |

新增 `fsListForCleanup(path)`：走严格版，`isDirNotFoundMessage`（目录确实不存在）按既定口径视为空列表，
其余失败一律抛出，由调用方**放弃本次清理**。清理是幂等的、可以下次再做，而删除不可逆 ——
这与判定路径「查询失败绝不能降级成确认没有」是同一条不变量，只是方向从「误判没下完」变成了「误删已下完」。

顺带两处收尾：

- 临时目录残留扫描里 `catch (Exception e) { junkOnly = false; }` 本就写着「查不到 → 交人工确认」的意图，
  只因宽容版从不抛异常而是**死代码**。这次让那段 catch 真正可达，并补上 `empty = false`
  （否则紧随其后的 `if (empty) { junkOnly = true; }` 会把它翻回来）。
- `purgeJunkAndEmptyDirsBottomUp` 里的 `allChildrenEmpty` 写了三处、从不被读（返回值用的是末尾的实时列举），
  已删除，避免继续误导。

### 测试

`OpenListWorkflowSimulationTest` 新增 7 条用例（场景 9「清理路径不得把查询失败读成目录为空」）：
每条先用 mock 注入一次真实列举失败（`failingListPaths`，`code=500`，文案刻意不含
`not found`/`timeout` 关键词，以免被当成「目录不存在」或触发重试），再断言「目录/文件必须存活」。

**已做反向验证**：把两处严格调用临时改回宽容版后，7 条全部失败，症状与预期一致 ——
顶层成片被覆盖（1000 → 700 字节）、整树目录从文件树上消失、残留扫描判成 `JUNK_CLEAN`。

#### 验证

- 定向 `OpenListWorkflowSimulationTest`：**16 通过 / 0 失败 / 0 跳过**（9 条原有 + 7 条新增）。
- 后端 `mvn test` 全量 **974 通过 / 0 失败 / 0 跳过**（另 e2e **4 通过**）。

---

## 3.4.21 增量（2026-09）

上一版把「归位对账」的请求放大器消掉了，这一版把同一类问题在**启动恢复**链路上收干净，
并修掉一个「网盘抖一下就丢计划快照」的数据安全缺陷。

### 启动恢复：云下载目录查询失败不再丢计划快照

`recoverOnePlan` 的云下载兜底用的是**宽容版**列举（`findCloudDownloadFiles`），
它内部把异常吞成空列表。于是"这次没查成"被读成"云目录里没有本集"，
紧接着 `OfflinePlanStore.delete` 就把计划快照删了 ——
**一次网络抖动 = 永久放弃这次补救**，与该路径自己的注释
（"保留快照：可能是网盘暂时不可用，下次启动再试"）以及
"查询失败绝不能降级成确认没有"这条既定原则直接矛盾。

同一条链路的兄弟方法 `relocateEpisodeFiles` 用的是严格版，两处口径本就不一致。
现在统一改用严格版：查询失败一律抛出，由 `recoverOfflinePlans` 兜住并**保留快照**。

> 与「文件确实没下完」严格区分：那种情况仍然丢快照、交回 RSS 正常轮次
> （避免历史快照无限堆积），这条行为没变。

### 启动恢复：候选文件改为定点列举

`recoverOnePlan` 原先用递归 `findFilesStrict(savePath)` 收集候选，
代价是「1 + savePath 下全部子目录数」次请求 —— 而子目录数恰恰就是残留临时目录数，
于是"残留越多扫描越贵 → 越容易撞上上游抖动 → 失败后残留更多"这个正反馈在启动恢复上同样成立。

现在与归位对账同一口径：只列举**最终目录顶层**与**本次临时目录**
（含其下 115 任务目录那一层），请求数与子目录数解耦。
临时目录名由两边共用同一实现算出，避免漂移导致"下载完了却永远找不到"。

### 云下载目录扫描：补「每轮只刷新一次」闸门

宽容版 `findCloudDownloadFiles` 原先**每次调用**都 `invalidateFindFilesCache` + 递归重走，
而它会被 `planScan` 反复调用（每集、每轮、多个早退检查点与轮询路径）——
一个订阅就是几十次 `fs/list`，网盘一抖就是几十次失败。

严格版早有 `markCloudListingRefreshedThisRound` 闸门，宽容版漏了，现已补上。
语义不变：本轮首次读仍强制刷新（刚提交的离线任务可能刚落盘），
其余调用复用共享列举缓存。

### 测试

新增两个守卫用例（**先确认在旧实现下失败**，再改代码）：

- `startup_recovery_keeps_snapshot_when_cloud_dir_listing_fails`
  —— 让云目录列举返回真实失败（`code=500`，文案刻意不含"目录不存在"与瞬时故障关键词），
  断言快照**必须保留**；旧实现下该用例因快照被删而失败。
- `startup_recovery_lists_only_the_expected_temp_dir_not_every_subdirectory`
  —— 堆 20 个无关子目录，断言它们**一个都不被列举**，同时功能上仍能把本次临时目录里的文件归位；
  旧实现下列出了全部 20 个（该用例耗时也因此到了 30s）。

mock 网盘新增 `failingListPaths` 旋钮（只对 `fs/list` 生效），
`OpenListPlanWorkflowTest` 的 `@AfterEach` 补 `OpenListApi.resetRateLimitState()`
—— 刻意制造过列举失败的用例必须清掉静态熔断状态，否则后续用例会撞上"冷却中"，
表现为"某用例秒败"的级联假失败。

#### 验证

后端 `mvn test` 全量 **967 通过 / 0 失败 / 0 跳过**（另 e2e **4 通过**）。

---

## 3.4.20 增量（2026-09）

本版三件事：**修掉登录后的 403**、**把 OpenList 的「本地是否存在」判定收敛成「单层、一次、仅用于确认」**、
以及**消除归位对账里「1 + 子目录数」的请求放大器**。另附端到端测试与测试策略。

### 登录鉴权：登录成功之后所有 @Auth 接口 403

`LoginController` 用 `sha256(json(Login + key))` 校验口令，而服务端读的是**脱敏后的**配置
（密码字段为空），于是"服务端摘要"与"客户端摘要"在密码非空时永远不等。

修复：先按"单层摘要直接相等"放行（口令从未被改动过的情形），否则把输入归一化到客户端摘要再比较，
通过后 `ConfigUtil.sync()` 落盘。**不会产生双层哈希**——空字段在任一层都不会被再次哈希。

### OpenList 判定收敛：单层、一次、仅用于确认（v3）

原先回答"网盘上到底有没有这个文件"要走**递归列举**（`findFilesStrict`），代价是「1 + 子目录数」次往返：
上游一抖，任意一个子目录超时都会把整条判定链拖垮，而全局熔断再把它放大成"所有订阅一起瘫痪"。

v3 的口径：

- 判定路径只允许**单层列举**（`fs/list` 一次，恒定 1 个请求），且**只用于确认**；
- 判定结果收敛为三态：`SUCCESS` / **`UNCERTAIN`（默认态）** / `FAILED`（收紧到"本地可确定"）。
  `awaitAndFinalize` **永不**返回 FAILED；唯一分流点是 `finalizeOfflineDownload`，顺序固定
  **取消 → 成功 → 存疑 → 失败**；
- **查询失败绝不降级成"确认没有"**：`RelocateResult.UNVERIFIABLE`（这次没查成）与 `NOT_FOUND`
  （查过确实没有）严格区分，只有后者允许删种子记录；
- 判定路径上的递归列举有计数器暴露（自检页可见），目标恒为 0。

> 自检页那项指标**只覆盖"已包守卫"的调用点**——守卫是自愿包裹式的，没包住的判定链不记账。
> 这一点已写进自检页文案与 `OpenListApi` javadoc，避免被读成"整条判定链都合规"。

### 归位对账改为定点列举：消除「1 + 子目录数」放大器

`relocateEpisodeFiles` 原先用递归列举扫 `savePath` 整棵树。而 `savePath` 下的子目录数**恰恰就是
残留临时目录数**——残留越多扫描越贵、越容易撞上上游抖动、失败后残留更多，是个正反馈。

现在只走**定点三条边**：`savePath` 顶层（单层）→ 本次下载的临时目录（名 = `tempDirName`，与提交阶段
共用同一实现 `resolveTempDirName`）→ 临时目录下那一层 115 任务目录（115 会在里面再建一层
「任务目录 = 种子文件名含扩展名」，视频落在那一层）。请求数从「1 + 全部子目录数」降到**常数**；
定点列举自带 30s 短缓存 + 请求合并，否则按 item 调用只会把放大器换成"订阅集数"。

`OpenListWorkflowSimulationTest` 新增一条把放大器钉出来的用例：堆 20 个无关子目录，断言它们
**一个都不被列举**，同时保留两层嵌套的归位能力。

### 端到端测试与 CI

新增 `ani.rss.e2e.DownloadJourneyE2ETest`（`@Tag("e2e")`，4 个场景）：加订阅 → 抓 RSS/种子 → 网盘离线 →
重命名归位 → 清理临时目录 → 状态落盘；第二轮刷新不得重复下载；保存设置不改密码层数、不踢下线；
以及**工件自证**。测试只替换"应用之外的世界"（假 RSS/种子站 + 假 OpenList 服务端），
**没有任何一处 mock 应用自己的类**；结束时在 `target/e2e-artifact/` 生成可复核的 `report.json` / `report.md`。

`pom.xml` 增加 `e2e-tests` 执行段（`groups=e2e`、`reuseForks=false`）；CI 改为先 `mvn test` 再打包，
并上传 e2e 工件（`if: always()`，保留 14 天）。

### 测试策略：删掉变更检测与无效测试

`docs/TESTING.md` 明确六条规则，核心判据是：**"删掉它，会不会有某种失败变得无法被发现？"答"不会" ⇒ 删。**
按此清掉 35 个用例：断言中文文案子串的自检页用例、"为只在 1 处使用的守卫写机制自证"、纯函数逐分支穷举、
断言实现常量、**与重构前的旧实现逐条对齐**，以及**零断言的分析脚本**（`VcbAnalyzeTest`）与
**不引用任何生产类**的 `TvFailClassifyTest`（连带 2 个孤儿测试资源 160K）。
`DoctorController` 里三个"为测试而抽出的 package-private 纯函数"改回 `private`。

#### 验证

后端 `mvn test` 全量 **965 通过 / 0 失败 / 0 跳过**（另 e2e **4 通过**）。

---

## 3.4.19 增量（2026-09）

### 本地侧洗版也连带删旧字幕（与 OpenList 侧统一）

背景：3.4.18 修洗版时记过一条口径差异——OpenList 侧删"本集全部条目"（含旧字幕 `.ass`），
本地侧只删 视频 / nfo / bif / -thumb.jpg。

**现在统一**：`DownloadService.isWashableEntry` 的白名单加上字幕
（认 `FileUtils.SUBTITLE_FORMAT`：`ass` / `ssa` / `sub` / `srt` / `lyc` / `sup` / `pgs` / `mks`）。
理由：旧字幕是按旧片源的时间轴做的，主RSS 换版后留着会与新视频错配。

安全边界不变：仍受"提交前快照"约束（提交后才落地的新字幕不在快照里，绝不删），
普通封面图（非 `-thumb.jpg`）仍不在白名单。

#### 验证

后端 `mvn test` 全量 **957 通过 / 0 失败 / 0 跳过**。新增用例：
`StandbySweepTest.sweep_deletes_old_subtitles_with_the_video`（视频/ass/srt/nfo 全清，
普通封面图保留）、`sweep_keeps_subtitles_not_seen_before_submit`（提交后落地的新字幕绝不删）。
反向验证：摘掉 `isSubtitleFormat` 分支 → 两项失败。

---

## 3.4.18 增量（2026-09）

### 备用RSS 洗版：专项审计后的 5 处修复

对「主RSS 替换 备用RSS」这条链路做了一次专项审计。**时序本身是稳的**（登记 → 闸门 → 提交成功才删；
只主删备；备用未改名完成就等下一轮；提交前快照保护新落地文件），但审出 5 处问题：

1. **【会误删】OpenList 侧用前缀包含匹配**：`isWashTarget` 原为 `name.contains(seasonKey)` →
   替换 `S01E01` 时会顺手删掉 **`S01E010`** 与 **`S01E01.5`**（后者是本仓库明确区分的独立一集，
   集数索引键就是 `1:1.5`）；且大小写敏感（网盘/下载器回的名字大小写不保证）。
   现改为"提取出 SxxExx 后 `equalsIgnoreCase`"，与本地路径同口径。
2. **【静默失效】OpenList 洗版用宽容版 `fsList`**：列举失败被吞成空列表 → 洗版什么都没删，
   用户却以为已替换（同一文件 `:2247` 就写着 "P0-3：必须用 fsListStrict"）→ 改用严格版，
   失败时显式打 `本次洗版跳过（列举或删除失败，旧文件保留，下轮可再试）`。
3. **删掉死代码 `removeStandbyPlaceholder`**（"检测并立即删除"入口）：P0 改造后生产已无调用者，
   只有测试在调它 → 测试改为组合 `findRemovableStandbyPlaceholder` + `removeStandbyPlaceholderTorrent`。
4. **同集多条备用任务只删第一条**（`findFirst`）：文件侧是全清、任务侧只删一条，口径不一致 →
   改为全删，并在 >1 条时 WARN。
5. **提交前快照只拍一层**：子目录里的同集旧文件永远清不到 → 快照与清扫都改**递归**，
   键统一为**相对路径**（避免子目录重名互相干扰），且"目录名不在快照里就整棵不动"
   （提交后才出现的新目录属于主RSS版本，绝不进）。

顺带把类型白名单抽成纯函数 `isWashableEntry`（名字里的 SxxExx 必须整体相等 + 只认
视频 / nfo / bif / -thumb.jpg / 目录）。

> 另记一条**口径差异（非缺陷）**：OpenList 侧删的是"本集全部条目"（含旧字幕 `.ass`），
> 本地侧当时只删视频/nfo/bif/缩略图——旧字幕留着会与新视频错配。
> （**该差异已在 3.4.19 统一**：本地侧也已连带删旧字幕。）

#### 验证

后端 `mvn test` 全量 **955 通过 / 0 失败 / 0 跳过**。新增用例：
`OpenListStandbyWashTest`(2，真 HTTP mock：只删本集 / 列举失败必须上抛且一个删除请求都不发)、
`StandbySweepTest` +4（同集多条全删 / 快照递归+相对路径 / 递归清扫子目录 / 相邻集不被牵连）、
`OpenListResidualPolicyTest` +1（`S01E010`、`S01E01.5`、大小写边界）。
反向验证（逐条摘掉修复，确认用例真会红）：回到 `contains` → 2 项失败；回到宽容版列举 → 1 项失败；
回到 `findFirst` → 1 项失败；快照回到单层 → 2 项失败。

---

## 3.4.17 增量（2026-09）

### 媒体库：OpenList 模式不再列举网盘

**现象**：每次打开媒体库（或点「重新扫描」）都会遍历**全部订阅**并逐个递归列举网盘目录——
与预览 / RSS 主流程抢同一个令牌桶、列举缓存与单轮预算，而页面展示的只是"有几集 / 占多大"，
对 OpenList 用户是纯开销（媒体库因此显得"鸡肋"）。

**改法**：

- **批量扫描（`/library`）在 OpenList 模式下零网盘调用**：集数改由订阅级本地状态快照派生
  （`LocalStateCache.peek`，与预览 / 手动搜索 / RSS 共用同一份，不产生任何 API 调用），
  `CLOUD_SCAN_BUDGET_MS` 与那套 10s 扫描预算随之删除；
- 快照派生的条目置 `cacheOnly`：前端把「视频文件」改标「已知集数」并注明"集数来自本地状态快照
  （不列举网盘）"；**占用空间显示「不可测」**（大小要列网盘才知道，不拿 0 冒充），
  且 cacheOnly 条目不参与占用空间汇总；
- **没有快照 ≠ 没有内容**：标「未确认」（tooltip 说明"打开一次订阅预览或等一轮 RSS 后出现"），
  不再把"不知道"混成"确认没有"——否则 OpenList 用户的库看起来是空的；
- **单订阅详情（`/libraryDetail`）保留按需列举**：只在用户点开某个订阅时列一次它的目录，
  并新增冷却短路（冷却期直接回原因，不发请求）。这是刻意保留的不对称：OpenList 用户的
  逐集视图只有它（订阅页的播放列表只认本地目录）；
- 随之失效的 `applyCloudScan` 及其用例删除（`scanCloud` / `toPlayItems` 保留，只服务详情）。

### 自检「下载路径」兼容 OpenList

**现象**：OpenList 模式下自检固定报 `下载根目录尚不存在（首次下载会自动创建）: /115/动漫/转存/追番`——
因为下载路径是**网盘虚拟路径**，本地 `File.exists()` 永远为 false（只花 2ms 也正是这个原因：压根没查网盘）。

**改法**：按下载器分派；OpenList 分支走网盘语义并分三层判定：

| 探测结果 | 级别 | 说明 |
|---|---|---|
| 目录存在 | ok | 给出直接子项数 |
| 目录不存在、首段挂载名存在 | ok | 「尚未创建（首次下载会自动创建）」——原来的噪音在这里变成正常信息 |
| 目录与首段都不存在 | warn | 同时给出两种可能：挂载点配错 / 自建目录尚未创建（**刻意不报 fail**：模板首段不一定是挂载点，报 fail 会把"第一次用"误判成配置错误） |
| 探测失败（超时/5xx/冷却中） | warn | 明确是"没查成"，附上游归因（域名/类别）与 DNS / MTU / IPv6 / 代理建议 |
| 冷却中 | warn | 直接跳过探测（不发请求），给出剩余秒数 |

- 顺带校验**云下载目录** `alistCloudDownloadDir`：不存在 → warn（云下载兜底归位会失效）；
  未配置 → 只提示"按根目录自动发现"；
- 新增只读探测入口 `OfflineDownloader.probeDirectChildren(path)`（OpenList 实现走**单层** `fs/list`，
  不递归——`listFilesStrict` 是递归的，对根目录用它等于列一整棵树），不计入单轮列举预算，但走令牌桶限流。

#### 验证

后端 `mvn test` 全量 **947 通过 / 0 失败 / 0 跳过**；前端 `pnpm build` 通过。
新增用例：`LibraryOfflineSnapshotTest`(3，快照派生的"未确认≠0 集"与 cacheOnly 语义)、
`DoctorDownloadPathProbeTest`(7，分层判定表 + 首段提取)、`OpenListUpstreamResilienceTest` +1
（单层探测：存在返回子项、不存在抛 `OpenListDirNotFoundException`）。
反向验证：把分层判定改成"永远 ok" → 5 项失败；把"没有快照"改回"确认没有" → 未确认用例失败。

---

## 3.4.16 增量（2026-09）

### 上游抖动（OpenList → 115）的六条缓解

**现象**：日志反复刷 `归位对账无法完成（网盘不可用或异常）…: net/http: TLS handshake timeout`，
但网盘其实是可用的。这条错误里 `Get "https://webapi.115.com/files?…"` + `net/http:` 已经说明
失败发生在 **OpenList 服务端 → 115** 那一跳（Go 的错误被 115 驱动层层包装后以业务 code=500 回传），
不是 ani-rss → OpenList。同一个抖动之所以能刷满日志，是因为下游少了四道闸门。

**六条（相互配套，不是取一）**：

1. **瞬时故障重试**：`TLS handshake timeout` / 超时 / 连接层错误类文案纳入
   `isTransientOpenListFailure`（复用现有 500ms/1500ms 退避、最多 3 次）。
   此前这条错**一次都不重试**（判据只认 `read timed out` / `connect timed out` / `502|503|504`）。
2. **失败记忆（同目录本轮只打一次）**：`fsListStrict` 把"失败本身"按 path 缓存 30s。
   原先一个订阅的 N 条记录会对同一目录重打 N 次，每次都要等上游 10~30s 超时，
   熔断计数还被直接顶到阈值 → 整批订阅一起停摆。
3. **云下载目录轮内只真列一次**：`findCloudDownloadFilesStrict` 原先每条 item 都
   `invalidate + list`；现改为本轮首次强制刷新 + 其余复用缓存。
   顺带修了它名下名不副实的 `Strict`：内部走的是宽容版 `findFiles`（查询失败被吞成空列表），
   于是"云下载目录里没有本集"曾是一个基于失败的结论，现在改用 `findFilesStrict`。
4. **快照兜底（只信"有"）**：列举失败（`UNVERIFIABLE`）时先查订阅级快照，命中即改判 `EXISTS`。
   已下载过的集不再因一次抖动全部显示"存疑"；**绝不**用快照断言"不存在"（那会删记录重下）。
5. **缺席需要跨时间确认（新成因 `ABSENCE_UNCONFIRMED`）**：Alist/OpenList 的目录列举
   **自带缓存**（存储驱动"缓存过期时间"默认可达 1 小时），刚下完/刚移动完去列目录常列不到；
   一次判 `ABSENT` 就删记录重下会换来重复下载 + 与已落盘文件撞名。现在要求
   两次都判没有、且相隔 ≥ **62 分钟**（`ABSENT_CONFIRM_INTERVAL_MS`，**刻意比默认 1 小时的目录缓存更长一点**，
   否则两次观察可能落在同一份过期缓存里）才允许删记录。
6. **自检页「OpenList → 上游网盘」**：从最近一次失败里提取上游域名与错误类别，
   给出 DNS / MTU / IPv6 / 代理 / 限流的具体建议；进冷却的 WARN 也补上同一归因。

#### 验证

后端 `mvn test` 全量 **939 通过 / 0 失败 / 0 跳过**；前端 `pnpm build` 通过。
新增用例：`OpenListUpstreamResilienceTest`(3，真 HTTP mock 含重试/失败记忆/云目录复用)、
`DownloadSnapshotFallbackTest`(4)、`AbsenceConfirmationTest`(5，合成时间跑间隔边界，并断言间隔 > 1 小时)、
`DoctorUpstreamSuggestionTest`(2)、`OpenListRateLimitTest` +2。
反向验证（逐条摘掉修复，确认用例真会红）：摘 `isTransientMessage` → 重试用例失败；
摘失败记忆 → `same_path_failure_is_only_attempted_once` 失败；恢复云目录每条强制刷新 →
`cloud_dir_is_listed_once_per_round` 失败；把缺席闸门改成"永真" → `AbsenceConfirmationTest` 3 项失败。

### 网盘 API 速率默认 3 → 1 次/秒

默认速率下调到 **1 次/秒**（`OpenListApi.DEFAULT_API_PER_SECOND` / `ConfigUtil.format()` /
`ani-rss-ui/src/js/config.js` / 设置页占位符与提示四处同步），取值区间 `[1,20]` 不变，
用户显式配置的值优先。

理由：网盘按账号限流，而一次递归列举天然是“1 + 子目录数”次往返（`findFilesStrict`），
速率过高只会把账号打进服务端限流，随后被熔断停得更久——**限流比熔断便宜**。

**连带影响（已同步调整用例口径，不是回归）**：单轮列举预算的“周期负担”=
速率 × 周期秒 ÷ 4，默认周期 15 分钟下从 `3×900÷4 = 675` 变为 `1×900÷4 = 225`；
预算取 `max(订阅数, min(订阅数×4, 周期负担))`，因此 50 订阅仍是 200（需求估算先撞线），
而大库保底“每订阅一次列举”成立时，单轮列举耗时上限约为 `订阅数 ÷ 速率`
（1000 订阅 ≈ 16.7 分钟，已超过默认 15 分钟周期，下一轮顺延）。
觉得慢就调大速率或缩短周期；两者的联动口径由 `RssTask.resolveAffordableListingsPerRound` 统一。

#### 验证

`EnabledOnlyTest` 中与默认值硬编码相关的断言已改为按 `resolveAffordableListingsPerRound` 推导
（不再把 675 写死，避免下次调默认值时用例“假绿”）；后端 `mvn test` 全量通过，
前端 `pnpm build` 通过。

### 「本地状态无法确认（网盘不可用）」拆开：成因可区分 + 目录不存在不再谎报故障

**现象**：OpenList 模式下，一轮里每条已下载记录的日志都是同一句
`本地状态无法确认（网盘不可用），本轮保留记录不重下 X`。用户看不出到底是网盘访问不了、
还是实现自己有毛病——排查卡在这句话上。

**根因（三层）**：

1. **文案把三种完全不同的故障揉成一句**。能走到这句的路径至少有四条：熔断冷却中、
   归位对账列举失败、索引被截断、兜底列举失败。统称"网盘不可用"之后，
   "等 60s 冷却"和"下载路径配错了"在日志里长得一模一样。
2. **冷却期在下载路径上完全静默**。`resolveOpenListPresence` 的冷却短路不打日志，
   `relocateEpisodeFiles` 的逐条跳过只打 `debug`，于是冷却期用户只能看到那句 INFO。
3. **「下载目录在网盘上不存在」被 `catch (Exception)` 吞成 UNVERIFIABLE**。它在需求文档
   §2.1 里明确属于「目录不存在 → 视为确认没有 → ABSENT」，且 `OpenListApi.buildFileNames`
   早就按这个口径处理。落到归位对账的宽 catch 后，路径/挂载配错这种配置问题被报成"网盘不可用"、
   **每轮每集都报且永不收敛**（记录既不清、文件也不重下）。

**改法**：

- `PresenceDecision{presence, reason}`：判定结果带上 `UnknownReason`，调用方据此打印
  `本地状态无法确认（网盘接口熔断冷却中，剩余 47s（冷却期内不发任何网盘请求））` 这类可直接行动的文案；
  冷却新增独立成因 `UnknownReason.COOLDOWN`（进 `RssTask.countRoundUnknownReason`，
  任务管理器「为什么无法确认」多一格「冷却中」），不再与「列举失败」混计。
- 下载路径的存疑**开始计入** `unknownReasons`（此前只加 `localUnknown`，所以任务管理器那一行永远统计不到它们）。
- `OpenListApi.logCooldownSkipOnce(...)`：同一个冷却窗口只打一条带剩余秒数的 INFO，既不刷屏也不静默。
- `OpenList.relocateEpisodeFiles` 内层单独 catch `OpenListDirNotFoundException` → 回报 NOT_FOUND
  （交兜底校验按「目录为空」判定），WARN 带上 `path=`；外层 catch 的文案改为
  「网盘列举失败或异常，非「确认没有」」。
- **「目录不存在」判定加了一道反向守卫**（同一天的真实日志逼出来的）：
  `isDirNotFoundMessage` 先经过新增的 `isTransientMessage` 过滤，含
  超时 / `tls handshake` / 连接层错误 / `too many requests` 的文案一律不得判成「目录不存在」。
  证据：115 的 transient 错误是 `failed get objs: failed get dir: failed get parent list:
  failed to list objs: Get "https://webapi.115.com/files?…": net/http: TLS handshake timeout`——
  与真正的 `failed to get dir` 只差一个 "to"。匹配一旦被放宽，上面刚接上的 NOT_FOUND 分支
  就会把超时读成"确认没有"并删记录重下。守卫只认网络层短语，不认裸数字（调用方常把含
  `path=` / `tmdbid=` 的整串传进来，裸数字会在路径上假阳性）。

#### 验证

后端 `mvn test` 全量 **924 通过 / 0 失败 / 1 跳过**；前端 `pnpm build` 通过。新增用例
`OpenListRelocateDirNotFoundTest`(3)、`DownloadPresenceDecisionTest` +2、`RoundLocalStateSummaryTest` +2、
`OpenListRateLimitTest.transient_failure_never_reads_as_dir_not_found`(1)，
并已做反向验证：把内层 catch 还原成 `throw e` → 新增用例报 `expected: <NOT_FOUND> but was: <UNVERIFIABLE>`；
把冷却文案改回与列举失败同串 → `unknown_reason_texts_are_distinguishable` 失败；
去掉 `isTransientMessage` 守卫 → `transient_failure_never_reads_as_dir_not_found` 失败。

---

## 3.4.15 增量（2026-09）

### P0 修复：v3.4.14 启动失败（依赖被静默丢弃）

**现象**：v3.4.14 启动即崩，`java.lang.IllegalStateException: Unable to read meta-data for class org.springdoc.core.properties.SwaggerUiConfigProperties` ← `FileNotFoundException: class path resource [...] cannot be opened because it does not exist`。

**根因**：新增的 FrostWire Maven 仓库（jlibtorrent 只发布在那里）对**不存在的 artifact 返回 302 → HTML 200**，而不是 404。Maven 默认的 `checksumPolicy=warn` 会把这段 HTML 当成 POM 收下并缓存，于是：

```
[WARNING] Could not validate integrity of download from https://dl.frostwire.com/maven/org/neo4j/...
org.eclipse.aether.transfer.ChecksumFailureException: Checksum validation failed, expected '<!DOCTYPE' ...
[FATAL] Non-parseable POM .../neo4j-bolt-connection-bom-10.1.1.pom: end tag name </head> must be the same as start tag <meta>
[WARNING] The POM for org.springdoc:springdoc-openapi-starter-webmvc-ui:jar:3.0.3 is invalid,
          transitive dependencies (if any) will not be available
```

`springdoc-openapi-starter-webmvc-ui` 的**有效模型**因被污染的 import BOM 而构建失败，其**全部传递依赖**被静默丢弃——发布包里少 8 个 jar：

| | v3.4.13 | v3.4.14（坏） | v3.4.15 |
| --- | --- | --- | --- |
| `BOOT-INF/lib` jar 数 | 115 | 113 | **120** |
| springdoc | common + webmvc-api + ui | **只有 ui** | common + webmvc-api + ui |
| validation 链 | spring-boot-validation + hibernate-validator + jboss-logging | **全无** | 全有 |
| swagger / webjars | swagger-ui + webjars-locator-lite | **全无** | 全有 |

**修复**：给 FrostWire 仓库显式加 `checksumPolicy=fail`（并禁用 snapshots）。这种"假 200"会被判为校验失败而被丢弃，Maven 回落到 Central 取真件；真正来自 FrostWire 的 jlibtorrent 有正规 `.sha1`，不受影响。

**验证**（隔离空仓库全量重建，等同 CI 冷启动）：

- 修复前：`invalid POM` + `Non-parseable POM` 各 1 条，fat jar 只有 `springdoc-...-ui`；
- 修复后：两条告警清零，`neo4j-bolt-connection-bom` 改为从 central 取得（`_remote.repositories` 记录 `>central=`），fat jar 120 个 jar（上述 8 个全部回归）；
- 本机实跑发布形态：`Started AniRssApplication in 5.129 seconds`、Tomcat 端口监听、`GET /` 返回 200 与 `ANI-RSS` 首页标题。

> 注意：v3.4.14 的构建缓存里可能残留被污染的 POM。CI 的 Maven 缓存 key 含 `**/pom.xml` 哈希，本次改动会自然失效重建；**本地如遇同样报错，删除 `~/.m2/repository/org/neo4j/bolt` 与 `~/.m2/repository/org/springdoc` 后重试即可**。

---

## 3.4.14 增量（2026-09）

### 结果缓存统一为「天」

- **本地磁盘与网盘合并成同一个设置项**：`Config.stateCacheTtlDays`，单位<b>天</b>，默认 **10**，范围 **1–90**（设置页只有「结果缓存 → 缓存时长」一个输入框）。原 `localStateCacheTtlSeconds`（60s）与 `cloudStateCacheTtlSeconds`（24h）<b>直接废弃</b>，旧值不再读取。
- 行为变化要知道：本地磁盘快照从分钟级变成天级。正确性依旧由**失效优先于过期**保证（下载完成/离线归位走 `appendEpisode` 增量并入，删除、洗版、改名、模板变更、订阅增删走主动失效），TTL 只当「兜底对账间隔」——用于回收带外变更（在网盘手动增删、手动往下载目录拷文件、重启后离线任务自行完成）。
- `LocalStateCache.resolveTtlMs(Source)` 保留 `Source` 参数仅用于可观测性（「这份快照有多贵」），两个来源返回同一个 TTL；上下限夹取移到统一口径（1 天 / 90 天）。

### 离线下载「期望文件计划」（P1–P4）

**问题**：OpenList/115 的离线任务状态与实际文件经常不一致——任务一直 `Running` 或报"部分成功"，其实文件早已下全；反过来也有报成功但只到了一部分。旧实现靠"扫网盘目录 + 从文件名猜集数"推断，既认不出被 115 改名的产物，也无法证明"这一集到底下全了没有"。

**方案**：提交时从种子元数据生成<b>期望文件计划</b>（`TorrentPlanUtil`：文件相对路径 + 字节数 + 最终目标名），用它当完成判据与归位依据，失败则全链路回退旧启发式。

- **P1 提前收尾**：等待循环里每轮用 `TorrentPlanMatcher` 按<b>字节数</b>比对候选目录（临时目录 → 最终目录递归 → 115 云下载兜底）——计划内视频全部到位就立刻归位，不等下载器状态、不等离线超时。
- **P2 计划归位**：`finalizeFromScan` 改为按计划条目认领文件（字节数为主键、名字/父目录消歧），字幕不再靠主名启发式配对；返回的是"源文件+真实路径+目标名"，顺带修掉旧实现"按裸文件名反查目录"在跨子目录同名时会认错目录的问题。计划认不出时仍走 `buildEpisodeRenameMap` 老逻辑。
- **P3 部分成功可见化**：归位只标记<b>真正落位的集</b>（`promoteTorrent(..., resolvedKeys)`，不再拿 `item.episodeRange` 整段标记——那是"部分成功 → 缓存假装全下完 → 永久漏下"的根因）；计划内缺的视频写 `FailedDownloadQueue`，在界面上可见可重试。
- **P4 重启恢复**：计划快照落盘到 `{config}/torrents/.pending/<hash>.plan.json`（`cleanupOrphanPending` 刻意保留），启动后 `recoverOfflinePlans()` 做一次只读比对——齐了就直接重命名/移动/清理/标记完成，不用等下一轮 RSS 重新提交；没齐就丢弃快照交回正常轮次。订阅列表尚未就绪时不做任何判断。
- **磁力链**：记录是 `.txt` 时计划在独立线程池后台抓元数据（复用磁力缓存，未命中才真抓，最长 60s），不阻塞提交与等待。

#### 真实 AList/115 实测（/115/，测试目录已清理）

用真实 VCB/喵萌发布结构造种子（7 文件：2 视频 + 4 字幕 + 字体包），把占位文件按 115 的真实形态上传后跑生产同一实现（`recoverOfflinePlans`）：

| 场景 | 结果 |
| --- | --- |
| 视频被改名 + 按文件名再套一层同名目录（`part-a.mkv/part-a.mkv`） | 按字节数认领成功，归位为 `[Nekomoe kissaten] Dandadan S01.E01 第1话.mkv` |
| 字幕保留真名 / 改成无关名（`sub-x.ass`） | 均正确归位并带上 `.jpsc/.jptc` 语言后缀 |
| 计划外的字体包 | 随临时目录被清理，未搬进最终目录 |
| 临时目录 / 计划快照 | 归位后整体清理，无残留 |
| 反例：文件没齐 | 只丢弃快照、不搬运任何文件 |

**实测发现并修掉一个真实缺陷**：AList 的 115 驱动 `fs/rename`/`fs/batch_rename` <b>只改主名、保留原扩展名</b>（实测 `x.bin` 请求改成 `y.mkv` → 得到 `y.bin`）。旧代码按"请求的目标名"去移动/校验，会让扩展名与种子不一致的文件判归位失败、永远搬不出临时目录。现在重命名后回读目录拿真实名（`OpenList.resolveRenamedNames`），同主名唯一候选即采纳并告警。

#### 验证

- 后端全量 `surefire` **916 通过 / 0 失败**（新增 `TorrentPlanUtilTest` 7、`TorrentPlanMatcherTest` 10、`OpenListPlanWorkflowTest` 8——含 P1 提前收尾、P2 计划归位、P3 精确标记+失败队列、P4 恢复与快照生命周期、网盘保留扩展名；原有 `OpenListWorkflowSimulationTest` 8 条全部保持通过，作为"无计划时旧路径不退化"的回归基线）。
- 前端 `vite build` 通过。

### 合集添加支持磁力链接

- 「添加合集」新增「种子文件 / 磁力链接」来源切换：磁力链接经 `MagnetTorrentUtil`（jlibtorrent，BEP-9）抓取元数据并缓存为 `{config}/cache/magnet/{sha256}.torrent`，之后预览、匹配/排除、改名计划、qBittorrent 提交、OpenList 离线下载、种子记录**全部复用上传种子的同一条链路**，没有任何分支。
- 缓存按「补过默认 Tracker 的磁力链接」取 SHA-256 命名（归一化幂等，同一磁力不会算出两个键），写入走「临时文件 + 原子移动」，`/api/clearCache` 一并清理并计入释放大小。
- **缓存命中路径完全不碰原生库**（只校验首字节 `d`），因此原生库加载不了的环境里已有缓存仍可用；解析失败（超时/坏链/无对应平台包）一律转成中文提示，不生成空文件冒充成功。
- 依赖：新增 `com.frostwire:jlibtorrent` 及 windows / linux-x86_64 / linux-arm64 / macosx-arm64 平台包（约 18MB），需 `https://dl.frostwire.com/maven` 仓库（不发布到 Central）。32 位 arm（`Dockerfile-arm32v7`）与 musl 环境缺原生库 → 磁力功能不可用，界面会提示改用 `.torrent`，其余功能不受影响。

#### 验证

- 后端 `surefire`：`LocalStateCacheTest` / `LocalStateCacheAppendTest` / `ConfigLocalStateInvalidationTest` / `MagnetTorrentUtilTest` 全绿（TTL 夹取与默认值断言已改成天级；新增磁力纯函数、Tracker 归一化幂等、缓存命中不触原生库、清理计数）。
- 前端 `vite build` 通过。
- **真实种子实测**：Ubuntu 官方 `.torrent`（1 文件 / 6.35GB）→ 本地 infoHash → 拼磁力链接 → jlibtorrent 抓取元数据 **8.1s** 成功，双端 infoHash 一致；二次调用命中缓存 **2ms**；`clearCache` 释放 484,465 字节。

---

## 3.4.13 增量（2026-09）

### 下载去重与备用RSS洗版时序（P0）

- **「本地已下载还重复推送」的三个根因**：
  - **hash 比对区分大小写**。本地 hash 来自 `FileUtil.mainName(torrent).toLowerCase()` 恒为小写，而下载器回的 `getHash()` 大小写不保证（Transmission 常回大写），"已有下载任务"直接 `equals` 认不出 → 同集每轮重复推送。现在统一走 `isSameHash`（忽略大小写 + null 安全），去重与洗版清扫共用同一判定。
  - **`download()` 补上"提交是否成功"的返回值**（原为 void，调用方无从得知）。此前失败也一样推进 `lastDownloadTime`、占并发配额、按"已下载"计数，任务管理器的本地状态还被记成「缺失」而非「存疑」。
  - **缺种子文件的早期返回补落失败记录**，`DOWNLOAD_START` / `DOWNLOAD_FAILED` 事件不再成对缺失（否则外部对账悬空）。

- **备用RSS洗版改为「主RSS提交成功后才清理备用」**。原先占位清除与「仅在主RSS更新后删除备用RSS」都在**提交之前**执行：主RSS提交失败 / 离线失败时备用已被删，该集两头空，下轮还得重下。现在两路都改为"本轮只登记待删（+ 剥掉本地集数索引，让主RSS不被'文件已存在'拦住），提交成功后才真正删除"；提交失败则本轮保留备用、不占配额、不推进进度，失败信息照常进失败队列。清理段各自 try/catch，清理失败只告警、不中断本轮剩余条目。

- **洗版清扫不再可能删掉刚提交的主RSS版本**。主/备用同一模板重命名后文件名可能完全一致（模板里没有 `${subgroup}` 时就是一致），"提交成功后按 SxxExx 清扫"既可能删掉刚提交的主任务，也可能删掉刚落地的主版本文件。现在三道闸门：
  - 任务侧：`excludeHash` 排除刚提交的主RSS infoHash，且只认带「备用RSS」标签的任务（无标签的历史任务不再被顺手删掉——它很可能就是主RSS自己的任务）；
  - 文件侧：只清**提交前目录快照**里的文件，提交后新落地的文件属于主版本，一个不碰（无快照则不清理文件）；
  - 主条目闸门：备用条目下载时不得反向清理主RSS的任务与文件。

- **已知边界**：OpenList 网盘洗版仍在提交前清理（网盘文件可从转存/回收站找回，且本次任务目录已有排除），与本地"成功后清理"的口径差异已知，后续单独对齐。

#### 验证

后端全量 `surefire` **887 通过 / 0 失败 / 0 跳过**（相对 3.4.12 的 880 新增 7 项：`StandbySweepTest` 7——覆盖 hash 大小写去重、excludeHash 闸门、备用标签过滤、提交前文件快照、主条目闸门，均做过反向验证）；前端 `vite build` 通过。

---

## 3.4.12 增量（2026-09）

### 离线下载「判定 / 归位 / 记录」的误判与死循环（P0）

- **查询失败不再被当成"确认没有"**。归位对账原先把"熔断冷却中 / TLS 超时 / 列举失败"与"真的没找到"合并成同一个 `NOT_FOUND`，调用方据此执行**破坏性动作**：删种子记录 + 重新下单。线上日志里同集四分钟内先报"归位对账未找到且兜底检查无文件，清理过期种子记录并重新下载"、随后又报"本地已存在"，就是这个误判。现在：
  - `RelocateResult` 新增 `UNVERIFIABLE`（"这次没查成"），与 `NOT_FOUND`（"查过确实没有"）严格区分；
  - 归位对账开头加熔断冷却守卫，冷却期内直接回报 `UNVERIFIABLE` 且**一个请求都不发**；
  - 判定由布尔改成五态 `EXISTS / ABSENT / UNVERIFIABLE / RETRY_EXHAUSTED / VALID`，**只有 `ABSENT` 才允许删种子记录**；`NOT_FOUND + UNVERIFIABLE` 仍判 `UNVERIFIABLE`。
- **顺带挖出的更深一层根因**：`OpenListApi.findFiles()` 内部 `catch` 后 `return List.of()` —— 网盘查询失败被**伪装成"空目录"**。这正是"递归列出网盘目录失败"紧接着"归位对账未找到"的成因链。判定/写路径一律改用 `findFilesStrict`（目录不存在仍视为空列表，其余抛出）。
- **归位重命名冲突不再抛异常**。"保留原名"分支在文件本就在目标位置时会产生恒等映射（`X → X`），却被目标名去重误判为"两个源抢同一名字"而抛 `IllegalStateException`，导致已落盘的文件被判离线失败 → 清 pending → 下轮重下 → 再冲突的死循环。现在恒等映射优先占位，其余撞名者退回原名/加序号避让；`finalizeFromScan` 与 `relocateEpisodeFiles` 两处入口都接。
- **失败重推上限**：同一集连续失败 3 次后保留记录但停止自动重推，等用户在失败列表手动重试，避免坏种/超时场景每轮往网盘堆一份新产物。

### 结果缓存改为「成功即增量追加」

- **新增 `LocalStateCache.appendEpisode`**：离线归位成功后**只并入本集**，不再整份失效后重新列举。网盘列举是最贵的一步（限流 + 单轮预算），而"这一集刚落地"是已知的增量事实。语义约束：CAS 写入防并发覆盖、快照不存在则 no-op（不造残缺快照）、**不续命 `builtAt`**（不延长 TTL）、**不放宽 `complete`**、只增不减。
- **网盘快照 TTL 默认 300s → 86400s（24 小时）**，上限同步放开。新鲜度改由"增量追加 + 主动失效"承担，TTL 只作兜底对账（回收用户在网盘手动增删留下的偏差）。附带好处：绝大多数 RSS 轮次直接命中缓存、不发请求，也就没有"列举失败被当成目录为空 → 删记录重下"的风险窗口。
- 自检页补上「增量追加」计数 —— 它长期为 0 就说明每集仍在走整份重列，API 消耗已悄悄回到改造前的量级。

### 种子记录（`.txt` / `.torrent`）

- **「下载种子」日志不再谎报下载**。这条 info 原先打在下游幂等检查**之前**，于是「本地已存在 → 跳过下载」这条 RSS **每轮 × 每集**都会走的正常路径也被记成一次下载：日志读起来像在反复重下，拿它计数更是严重虚高。现在记录已存在就直接复用、不打日志；真下载仍有「下载种子」+「种子下载完成」。
- **`getPendingTorrent` 补上"磁力链 ↔ `.torrent` 直链"切换容错**。`getTorrent` 早已做了这层容错（先找已存在的 `.txt` 再找 `.torrent`），`getPendingTorrent` 漏了：订阅源切换表示后就"找不到"已经写下的待完成标记 → 重复提交离线任务、`promoteTorrent` 因找不到标记而跳过提升、记录永不落盘、下轮再提交。现改为与 `getTorrent` 同口径。
- **提升时按标记自身扩展名落盘**。下载器是**按文件扩展名**分派提交方式的（`.txt` → 内容当 URL/磁力链；其余 → 当种子二进制上传/解析），共 4 处依赖。原先 `promoteTorrent` 沿用 `getTorrent` 按"当前表示"算出的目标名，表示切换后会把装着磁力链文本的 `.txt` 搬成 `.torrent`，导致扩展名与内容不符。

#### 验证

后端 `mvn test` 全量 **880 通过 / 0 失败 / 0 跳过**（相对 3.4.11 的 842 新增 38 项：`OpenListRenameCollisionTest` 5 · `OpenListRelocateCooldownTest` 3 · `LocalStateCacheAppendTest` 7 · `DownloadPresenceDecisionTest` 7 · `EpisodeIndexKeyTest` 7 · `TorrentUtilSaveTorrentTest` 7 · `DoctorLocalStateCacheTest` 1 · `LocalStateCacheTest` +1）；前端 `vite build` 通过。新增用例均做过反向验证（摘掉修复后确认会失败）。

---

## 3.4.11 增量（2026-09）

### 挂死与误判（P0）

- **单订阅卡死不再拖住整轮**：轮次收尾的 `future.get()` 原本无超时，一个订阅卡在不可中断 IO 会让整轮不返回、全局锁最长 70 分钟才恢复。现在单订阅上限 5 分钟，超时取消该订阅并计失败，其余订阅继续。
- **下载器查询失败不再当成"没有任务"**：TR / Aria2 查询异常原本吞成空列表，会放行并发上限并把已有任务误判成坏种重推。现在与 qB 对齐向上抛，调用方区分"无任务"与"查询失败"；等不到在途查询时优先回退过期缓存，等待上限从 60s 收敛到 10s。
- **网盘抖动不再误判归位失败 / 重复提交**：归位校验、任务复用、残留 adopt 原本用非严格口径，一次抖动返回空列表就会"全部缺失"或认不出进行中任务。现在写路径改严格版，失败抛而非空。
- **导入配置不再先删种子记录**：`torrents` 改为改名备份，全量搬入成功后再删，失败回滚，避免半截目录永久丢种子。

### 锁内长活与通知

- **`login / delete / renameOnce / notification` 去类锁 / 单例锁**，重试睡眠与网络 IO 移出临界区；RSS 订阅池换命名 daemon 线程 + 超时二次 `shutdownNow`；BGM 令牌桶锁内只算等待、锁外睡眠。
- **通知 16 通道统一 10s 超时**；登录失败不再 `sleep` 占 Web 线程；Emby / Telegram 去类锁；上传通知去掉固定 2s 罚站。

### 解析与 SSRF

- **用户可控 URL（RSS / WebHook / 代理测试）补 SSRF 校验**；BGM / TMDB / AniBT / AnimeGarden / AniList / Emby / Telegram 的乐观取值加空守卫；ASSRT 下载加 150MiB / 单条目 20MiB 上限；BGM 无 platform 条目不再 NPE。

### 写放大与 Web

- **下载历史 / 失败队列 / 调度快照落盘 2s 合并 + 内容比对**（首写旁路，测试隔离）；备份前 WAL checkpoint 且拷贝移出锁；`clearCover` 每天最多全扫一次；导入包 200MB 上限；NFO 唯一临时名 + 原子移动。
- **媒体库扫描去类锁改单飞**；日志接口分页；合集临时文件必删；种子目录 `mkdir` 缓存；任务列表前端按 hash 增量合并 + 轮询 Abort；远端 Markdown 关闭内联 HTML。

#### 验证

后端 `mvn test` 全量 **836 通过 / 0 失败 / 2 跳过**（另 `OpenListWorkflowSimulationTest` 8 项单独通过）；前端 `vite build` 通过。

---

## 3.4.10 增量（2026-09）

### 性能：订阅状态回写的写盘放大

- **每次订阅状态变化都会把整份订阅列表重新序列化并重写一遍**。一轮扫描里每下完一集、每刷新一次预览都会触发，200 个订阅就是 200 次全量写。现在加了三道闸门：
  - **内容没变就不写**：先算出 JSON，再与上一次真正写下去的内容比对，一样就跳过（订阅文件不存在时不跳过，避免配置被手工清理后一直不落盘）。
  - **状态回写按 500ms 合并**：窗口内的多次回写合并成一次**尾部落盘**，数据只延后、不丢失。首次回写不节流；订阅增删改这类结构性变更不节流。
  - **预览只在有实质变化时落盘**：预览每次都会刷新「检查时间」，只看内容有没有变会永远认为变了——改成只有「漏集数真的变了」或「距上次落盘超过 1 小时」才写。
- **订阅落盘改为紧凑格式**（原先每行都缩进）。200 订阅实测 224.7KB → 171.4KB（约 −24%）。**导出 / 备份仍然带缩进**，给人看的那份不受影响。

### 性能：几处「拿着锁做长活」

- **检查更新会按住整条更新链路**：`/about`、`/forkAbout`、`/update` 原本共用一把类锁，而 `/update` 是在锁内**完整下载几十 MB 的更新包并算校验**，慢链路下可达数分钟，期间其它接口全部阻塞（每个阻塞占一个 Web 线程）。现在拆成「短逻辑一把锁 + 下载 / 应用一个互斥标记」，并给检查更新加了 1 分钟缓存；重复点击更新会直接提示「已有更新任务正在进行」，而不是排队。
- **合集下载会把 Web 线程占住最多 32 秒**：合集提交后的「等元数据 → 逐集重命名」原本跑在请求线程里。现在移到后台线程池，接口立即返回「已开始下载合集，进度可在日志中查看」；顺带修掉「同一集被重复发重命名」，并每 5 轮打一次进度。
- **订阅项解析不再每次新建线程池**，改用共享的有界线程池。

### 一致性

- **订阅列表接口不再往订阅对象上写派生字段**：原先会把拼音、健康分等 7 个字段直接写在共享的订阅对象上，与后台落盘并发交错时互相干扰。现在改为拷贝一份再写，**接口输出结构不变**。
- **新增订阅 id 索引**，按 id 查订阅不再全表扫描；增删订阅后索引保证失效。
- **订阅级读写锁会被回收**：删除订阅或被自动替换时释放对应锁，长期运行不再累积（只在确认无人持锁时才回收）。

### 健壮性

- **检查更新的版本号比对加了空值兜底**：解析失败时原先会退化成「相等」从而允许自动更新，现在收紧为「两侧都解析出且相同才允许」。
- **macOS 更新包下载改走统一请求层**：原先是全项目唯一一处裸下载，超时是无限等待、也不走统一代理。
- **进程内缓存去掉全局锁**，改并发容器 + 插入序号淘汰；网盘请求 URL 的重复斜杠折叠改为预编译正则。
- **`src/main/resources` 下混进了 976KB 的 Windows 系统缓存**（`%SystemDrive%/ProgramData/...` 的 COM+ `.db`），会被打进 jar 一起发布。已从打包环节排除并加入忽略规则——**只删除不够，它会被本机重新生成**。

### 界面

- **设置页四组数值输入框看不清输入位置**：「错峰更新 / 网盘 API 限流 / 静默窗口 / 结果缓存」的可用输入区只有约 66px，还被框内的中文前后缀盖住。现在统一改为「标签 | 输入框 | 说明」网格，加减按钮收到右侧，可用输入区约翻倍；**留空时按右侧标注的默认值处理**（如「毫秒，默认 2000」「次/秒，默认 3」），单轮预算留空表示自动取值。
- **首页任务列表轮询改为自适应**（有任务在跑 5 秒、空闲 30 秒），且任务未变化时不再整表重渲染。

#### 验证

后端 `mvn test` 全量 **842 通过 / 0 失败 / 0 跳过**（相对 3.4.9 的 795 新增 47 项：`CacheUtilsTest` 10 · `AniUtilIdIndexTest` 8 · `RenameCacheUtilTest` 7 · `GsonStaticCompactTest` 6 · `AniUtilSyncTest` 6 · `HttpRequestPlusNormalizeTest` 6 · `AniLocksTest` +4）；前端 `vite build` 通过。

---

## 3.4.9 增量（2026-09）

### 修复：订阅里的日期可能被写错（并发序列化）

- **第三方 TMDB 库的日期适配器不是线程安全的，而它跑在全应用共享的 Gson 单例上**。该适配器内部持有一个 `SimpleDateFormat`（它内部共享一个可变的 `Calendar`），而 Gson 对每个 Gson 实例**只创建一个适配器并复用**——于是所有线程在同时序列化订阅时会互相踩。
  - **实测**：8 线程并发序列化同一个对象 4000 次，约 **36% 的日期被写成错误日期**，并偶发 `ArrayIndexOutOfBoundsException`。
  - **影响**：这个 Gson 同时被用作 Web 接口的消息转换器，而订阅对象里带着 TMDB 信息。所以订阅列表接口的响应、`ani.v2.json` 落盘、订阅深拷贝都可能带上错误日期，最终体现为 **NFO 里的年份 / 发行日期** 与 **「标题 (年份)」改名结果**出错。
  - **修复**：让承载 TMDB 对象的 Gson 实例按线程独占，不再跨线程共享。该适配器来自第三方 jar 且是**字段级注解**，优先级高于任何自建适配器（已实测无法覆盖），所以只能换掉承载它的 Gson。
  - 这与 3.4.8 修掉的 `DateAdapter` 是**同一类问题**，只是这一处藏在第三方库的字段注解里。

### 稳定性与性能专项（第三批：外部接口成本）

BGM / TMDB / 字幕三条外部依赖的调用次数与等待方式。

- **BGM 限流写在了「加请求头」的方法里**：`setToken` 的职责只是加一个 Authorization 头，但它里面固定睡 0.5~1 秒，且被 11 处调用；`getEpisodes` / `getSubjectId` 在这之外还各睡 0.5 / 1 秒。也就是说单次 BGM 元数据获取的**纯睡眠**就有 1.5~2.5 秒，而 BGM 任务是逐订阅串行的——200 个订阅仅纯睡眠就 ≥6 分钟，与网络快慢无关。已改为在**真正发请求前**统一取令牌（令牌桶速率与原均值一致，**不增加**对 bgm.tv 的压力），并删掉那 5 处睡眠。
- **「并行获取」跑在公共线程池上且都在阻塞**：`ForkJoinPool.commonPool` 的并行度是「CPU 核数 − 1」，而 FJP 的阻塞补偿只对 `ManagedBlocker`/`ForkJoinTask` 生效、对普通 `CompletableFuture` **不生效**——阻塞的网络读会把公共池占满，饿死其它同样使用它的代码。已给 BGM / Mikan 的并行获取各配一个专用小线程池。
- **TMDB 查询在类锁内发网络**：`TmdbUtils.getFinalName` 是 `synchronized static`，一次慢查询会按住所有订阅的改名。已去掉类锁，并给标题查询加了进程内缓存（命中 10 分钟）。
- **Mikan 详情 / BGM 剧集列表没有缓存**：同一番剧在同一轮里会被反复抓取（`getBgmInfo` 早有缓存，这两条是漏网的）。已补上短 TTL 缓存。
- **字幕合集包被重复整包下载**：合集压缩包候选在「逐视频」循环里被反复取用，同一 URL 每个视频都要把整包重新下载一遍——一季 12~24 集就等于同一次下载做了 12~24 次。已改为**一次计划内只下载一次**（缓存生命周期绑定本次计划构建，构建结束即释放）。
  - 顺带说明：审计报告里「单个视频最长可等约 79 秒」的量级**未能复现**，实际最坏在 15~30 秒量级；且限流只作用于搜索/详情两次调用，CDN 直链下载不受限流影响。

#### 验证

后端 `mvn test` 全量 **795 通过 / 0 失败 / 0 跳过**（相对 3.4.8 的 762 新增 33 项：`BgmUtilRateLimitTest` 9 · `TmdbUtilsCacheTest` 7 · `AssrtSubtitleProviderBytesCacheTest` 5 · `BgmUtilEpisodeCacheTest` 4 · `MikanServiceCacheTest` 4 · `GsonStaticTmdbConcurrencyTest` 4）；前端本批未改动。

（3.4.8 记的「1 跳过」是 BGM 网络探测在不可达时条件跳过，本次网络可达故执行并通过，因此通过数比「762 + 33」多 1。）

---

## 3.4.8 增量（2026-09）

### 稳定性与性能专项（第一批：任务生命周期 / JVM 参数）

- **停止任务后旧线程复活，导致重复跑轮次（重复下载）**：`TaskService` 原先用全局单旗标，`stop()` 超时后会丢弃线程，而 `start()` 又把共享旗标置回 true，于是被放弃的旧线程复活并继续跑。改为**代际旗标**——每次启动换一个新旗标实例，线程只持有自己那一代；未退出的线程记入「被放弃」名单，并在自检里显式告警。
- **周期等待不可中断**：原先用 `ThreadUtil.sleep`，停止响应要等满一个完整周期（BGM 任务最长 12 小时）。新增可中断的分片等待，停止响应 ≤500ms。
- **JVM 参数**：去掉 `-XX:TieredStopAtLevel=1`（它让 JIT 停留在 C1，吞吐下降 2~5 倍；该参数同时被 Linux 启动脚本复用，等于三条生产路径的 C2 编译全被禁用）；`-Xss256k` → `-Xss512k`；新增 OOM 堆转储（写到配置目录的 `logs/`）、`+ExitOnOutOfMemoryError`、`MaxMetaspaceSize=256m`。
- **`DateAdapter` 线程安全**：`SimpleDateFormat` 经 `@JsonAdapter` 注册到全应用唯一的 Gson 单例后被并发复用，存在数据竞争。改用不可变的 `DateTimeFormatter`，并保留原有宽容解析语义（`2024-1-5`、尾部多余内容仍可解析），历史数据不受影响。

### 稳定性与性能专项（第二批：网盘调用放大）

- **一次慢列举会阻塞该网盘的全部 API**：`OpenListApi` 的实例监视器原先跨着整棵目录树的每一次 `fs/list`（60 秒超时）以及重试退避，把 3~8 个 RSS 线程、前端每 5 秒的任务列表轮询、离线等待池全部串在一起。已去掉该锁；原先靠它顺带实现的「同一路径去重」改由显式的请求合并承担。
  - 需要说明的是：令牌桶保证的是**全局速率上限**，不保证请求端到端串行。请求不再互相排队是有意的结果——调压旋钮仍是设置页「网盘 API 限流」的速率与突发。
- **列举缓存「一改全清」**：原先任何一次目录写入（每下载完一集都会改名/移动）都会清空**所有订阅共享**的列举缓存，30 秒缓存期在下载期间形同失效。改为**按目录树定向失效**（自身 / 子目录 / 祖先），并加世代号防止「失效后旧结果回填」。
- **每轮 API 预算的口径算错了**：原先统计的是**所有** API 调用，mkdir / 移动 / 上传 / 下载器查询都在消耗这份本该用于「确认本地文件」的预算；默认值还是「每订阅 1 次」，而每个订阅实际需要 1 + 子目录数 次列举。已改为只统计目录列举次数，并按「每订阅估算列举数」与「本轮周期内限速发得出的次数」取小给足。
  - **原先无论订阅多少都只给 200 次**（预算就是 `min(订阅数, 200)`，而每个订阅要把下载目录加各子目录逐层列举，200 个订阅实际需要 800 次上下），于是预算只够覆盖前 50 个订阅，其后的订阅本轮完全不做本地状态校验、整体退化为「存疑」。**这是本次修复的主要用户可见问题**——如果你此前看到「一批订阅长期显示存疑」，原因就在这里，不是网盘有问题。
- **下载器任务列表查询**：`TorrentUtil.getTorrentsInfos()` 原先与登录 / 删除 / 重命名共用同一把类锁，而下载器查询超时是 20 秒；一次慢查询会把它们全部按住排队。已改为只锁缓存读写并补请求合并。

#### 验证

后端 `mvn test` 全量 **762 通过 / 0 失败 / 1 跳过**（相对 3.4.7 的 715 新增 47 项：`DateAdapterTest` 10 · `TaskServiceGenerationTest` 9 · `OpenListApiCacheTest` 10 · `TorrentUtilTorrentsCacheTest` 10 · `OpenListRateLimitTest` +2 · `EnabledOnlyTest` +6）；前端 `vite build` 通过。

---

## 3.4.7 增量（2026-09）

### 错峰更新与本地状态同步（需求文档 F1~F7 全部落地）

依据仓库内《错峰更新与本地状态同步需求.md》实现。要解决三件事：**不要重复下载**、**不要因为查询失败就谎报"本地不存在"**、**不要把网盘打限流**。

#### 结果缓存（F2）

- 新增 `LocalStateCache`：订阅级本地状态快照，键为「订阅 id + 下载目录摘要」。预览 / 媒体库 / 手动搜索 / RSS 主流程共用同一份结果——此前同一订阅在一轮里会被反复列举，网盘模式下就是反复打 API。
- **分级 TTL**：本地磁盘 60s、网盘 300s（网盘列举贵得多），可在 设置 → 其他设置 调整。
- **失败不入缓存**：一次网盘抖动绝不能被固化成"目录里什么都没有"，否则整个 TTL 内都会谎报"本地不存在"。
- **单飞**：同一 key 的并发构建只跑一次，其余等同一份结果；等待超时按"校验失败"处理，绝不返回空列表冒充"目录为空"。
- **版本化失效**：订阅版本 + 路径版本 + 全局世代，三者任一在构建期间变化就丢弃这次结果——否则"失效"会被一个更早开始、更晚结束的旧构建覆盖掉。
- 改名完成只拿得到下载目录、拿不到订阅 id，因此额外提供按路径失效；只能按订阅失效的话就会退化成"整体失效"，而一轮里每下完一集都会改名，等于缓存从未生效。

#### 只处理已启用的订阅（F3）

- 轮次提交前、子任务开头两处都重新读取**实时**订阅对象（此前用的是轮次开始时的副本，`enable` / `notDownload` / `season` 改了不生效）。
- 扫到一半关掉某订阅，剩余批次不会再把它提交进线程池。

#### 后处理完成后才开新一轮（F4）

- 新增静默窗口闸门：改名 / 离线归位未收尾时不启新一轮，避免"文件已下载但还没改名落地"被按文件名匹配判成"未下载"→ 重复下载。
- 连续确认 + 超时兜底（超时强制开轮并 WARN）；**强制开轮时剔除"后处理未收尾"的订阅**，其余照常扫描，不因个别订阅卡住让整轮停摆。
- 等待时长从周期里扣除，不会让扫描频率凭空变慢。

#### 三态判定与存疑成因（F5）

- 「本地存在」统一为**是 / 存疑 / 否**三态，预览、媒体库、手动搜索共用同一判定入口，不再各自写分支。
- 口径：`rename=false` → 记录存在即"是"，**零网盘调用**；`rename=true` + 列举失败 → 记录判定但标"存疑"；`rename=true` + 列举成功 → 按真实文件判定。
- **查询失败绝不降级成"确认没有"**。存疑原因分四类并分别透出（列举失败 / 超预算 / 索引不完整 / 等待改名），因为对策完全不同（等网盘恢复 / 调预算或减订阅 / 调 `cloudListMaxFiles` / 等一会儿）。
- 网盘列举超过 `cloudListMaxFiles` 会截断：**截断的索引可以确认"存在"，但不得断言"不存在"**。
- 「本地存在」筛选把"存疑"归入已下载——把"不知道"悄悄降级成"不存在"，正是重复下载与误清理的起点。

#### 订阅级读写锁（F6）

- 新增 `AniLocks`：`ReadWriteLock` per 订阅。写 = RSS 更新 / `downloadAni` / 强制下载 / 重试失败项 / 改名回写 / 删除种子；读 = 预览 / 媒体库详情 / 手动搜索。
- **读路径只等 300ms，等不到就不持锁直接读快照**——写锁在下载期间是分钟级的，让预览排队等它比读到稍旧数据糟糕得多。退化次数可在自检页观测。
- 媒体库**批量**扫描刻意不加订阅锁（逐个 `tryLock` 在多订阅同时下载时会线性叠加成秒级延迟，而它读的只是展示计数）；单订阅的详情则加读锁。
- 状态回写节流：运行时状态（下载进度 / 漏集数 / `lastDownloadTime` / `enable`）改走新增的 `AniUtil.syncStateOnly()`（只落盘、不失效缓存），且一轮最多写一次 `ani.v2.json`。
- 配置联动：`downloadPathTemplate` / `ovaDownloadPathTemplate` / `rename` / `fileExist` / `downloadToolType` 变更即失效本地状态缓存与媒体库缓存。

#### 网盘 API 访问策略（F7）

- **令牌桶**替代固定 300ms 间隔（`openListApiPerSecond` / `openListApiBurst`，仍全局串行，因为限流是按账号算的）。
- **请求合并**：同一目录的并发列举只发一次请求。
- **熔断**：连续失败进入指数冷却，冷却期内直接返回"未知"而不发请求（成功一次即复位）。
- **分级 TTL**：浏览类 30s、判定类 300s。
- **每轮预算**：默认 = 本轮启用订阅数，硬上限 200；超出即停止真实文件校验，剩余条目保持"存疑"并 WARN。媒体库预算不足时显示为「未确认」而不是 0 集——订阅多时预算耗尽必然发生，当成"没有"会让媒体库大面积变空。
- 「目录不存在」从「查询失败」里摘出来单独处理：它是业务结果（= 确认没有），不重试也不参与熔断——否则新订阅（下载目录尚未创建）一进预览就会触发全局熔断。

#### 可观测

- 任务管理器新增：批次进度「批次 N/M · Ns 后下一批」、`超时强制开轮` 标签 + 跳过订阅数、本轮 `已存在 / 无法确认 / 已下发` 三计数、「存疑原因」分布（带 tooltip 逐条解释该做什么）。
- 自检页新增「网盘 API 限流」与「本地状态快照缓存」两项：速率/突发、本轮与累计调用数、缓存命中率、请求合并省下次数、限流累计等待、熔断次数与冷却剩余、超预算放弃次数、快照条目/容量/TTL/失效丢弃次数。

#### 新增配置项（设置 → 其他设置 → 「结果缓存」）

`localStateCacheTtlSeconds`(60) · `cloudStateCacheTtlSeconds`(300) · `openListApiBudgetPerRound`(留空 = 按启用订阅数自动，硬上限 200) · `cloudListMaxFiles`(5000)

旧配置无需迁移：`ConfigUtil.format()` 自动补默认值。

#### 验证

后端 `mvn test` 全量 **715 通过 / 0 失败 / 1 跳过**（相对 3.4.6 的 557 新增 158 项：`QuiescentWindowTest` 27 · `OpenListRateLimitTest` 23 · `LocalStateCacheTest` 20 · `AniLocksTest` 20 · `StaggeredUpdateTest` 16 · `EnabledOnlyTest` 12 · `ConfigLocalStateInvalidationTest` 9 · `LocalStateResolutionTest` · `PreviewLocalExistsTest` 6 · `OpenListItemDownloadedTest` 5 · `RoundLocalStateSummaryTest` 3 · `LibraryCloudScanTest` …）；前端 `vite build` 通过。

---


## 3.4.6 增量（2026-09）

### 射手网(ASSRT) 网络层加固：超时 / 重试 / 备用域名
此前所有请求共用 `HttpReq` 的 20s 单一超时，且**没有任何重试**——一次链路抖动或服务端慢响应就直接失败，用户看到的是 `ConnectException: Connection timed out`。

- `HttpReq` 新增 `get(url, connectTimeoutMs, readTimeoutMs)` 重载：原 `timeout(ms)` 同时作用于连接与读取，无法区分「握手慢」与「响应慢」。
- `AssrtSubtitleProvider.getWithRetry`：瞬时故障按 **1s / 2s / 4s 指数退避**重试；重试时**交替使用主/备域名**（`api.assrt.net` ↔ `api.makedie.me`）。
- 重试判定穿透异常 `cause` 链：**可重试**——连接/读取超时、连接被重置、DNS 失败、HTTP 5xx、429，以及 ASSRT 的 `30900`（超出调用限制，文档明确要求退避重试）；**不重试**——`20001`（Token 无效）、`101`（关键词过短）等 4xx 确定性错误，避免白白消耗配额。
- 默认**连接 15s / 读取 30s / 重试 2 次**，新增设置项「连接超时」「读取超时」「瞬时故障重试次数」，可在 设置 → 其他设置 调整。

### 搜索逻辑优化：英文标题 + 单次检索 + 候选交用户选择
旧实现按「**每个视频** × 精确(`no_muxer`) / 宽泛两档」发请求，还要逐条调 `sub/detail` 补全文件列表——一次 12 集批量匹配就是 24+ 次请求，直接打满默认 **5 次/分钟**的配额并触发 `30900`。

- **单次搜索**：新增 `AssrtSubtitleItem` 承载搜索记录；`searchItems` 只发**一次** `sub/search`，不加 `no_muxer`，搜索阶段也**不调** `sub/detail`。
- **优先英文标题**：关键词按 `themoviedbName` → 含拉丁字母的订阅标题 → 日文原名 → 订阅标题 取第一个可用者，并剔除 TMDB 附加的 `(2018)` / `{tmdb-12345}` 后缀（否则关键词过窄搜不到）。ASSRT 条目名以英文/原文为主，用中文标题常常搜不到。
- **候选交用户选择**：新增 `/subtitleAssrtSearch` 返回候选列表，用户选中某条后才下载——后端不再自动挑选，从根上避免匹配到错误字幕。选中条目内联 `files` 时零额外请求，否则按 `id` 调一次 `detail`。
- **前端流程**：`SubtitleMatchView` 改为 `填写 → 选择候选 → 预览 → 确认写入`，候选表展示字幕名 / 语言 / 类型（单文件 / 合集包）/ 文件数，并显示实际使用的搜索关键词。
- **正确性**：单文件候选先过季/集门槛，避免把第 3 集的字幕挂到第 5 集；压缩包仍由解包阶段按目标集数挑选。

### 其他
- `ConfigUtil.format()` 补齐 ASSRT 参数默认值（`assrtRateLimitPerMinute` / `subtitleLang` / 三个新参数）。此前这些字段在 `Config` 里是可空包装类型且无默认值，而设置页是整体替换配置、不合并前端默认值，导致输入框显示空白。
- `/subtitleFetchPreview` 改为接收 `{aniId, searchId, index}`；`resolveFetchAni` 增加 Token 已配置校验。
- `docs/assrt-api.md` 重写取数策略，新增「6.1 超时与重试」「6.2 排查 `ConnectException`」（含 DNS / ICMP / TCP / IPv6 / 代理白名单 / 出网的逐层排查顺序）。
- 新增测试 `AssrtSubtitleProviderTest`（重试判定 + 季集门槛）、`SubtitleSearchKeywordTest`（英文标题选取）；验证：全量 **557 通过 / 0 失败 / 1 跳过**，前端 `vite build` 通过。

---


## 3.4.5 增量（2026-09）

### 字幕匹配重构（手动获取 + 二次确认）
- 设置项「字幕自动获取」→「字幕手动获取」（`subtitleAutoFetch` → `subtitleManualFetch`，Gson `@SerializedName(alternate=...)` 兼容旧配置）；下载完成后不再自动抓取，字幕统一在「字幕匹配」页管理，避免自动匹配到错误字幕。
- 导入前二次确认（预览不写盘）：`SubtitleService.importLocalSubtitles(dir, files, dryRun)` + `expectedSubtitleName()`/`normalizeLangTag()`，保证「预览显示文件名 == 实际落盘名」。
- 射手网(ASSRT) 手动获取：`planFetchFromAssrt`（只读生成计划）+ `applyFetchPlan`（写盘），中间用 LRU+TTL 30min 计划缓存，预览与确认只跑一次射手网请求（避开频率限制）；新增 `/subtitleImportPreview` `/subtitleFetchPreview` `/subtitleFetch`。
- 字幕覆盖前备份到视频同目录 `sub_bak/`（不再散落 `视频.ass.bak`）；`PlayController` 过滤 `sub_bak/` 避免备份被当成外挂字幕列出。

### UI 调整
- 移除「工具 → 追番日历」Tab（与订阅页信息重复）；首页移除「下载中」卡片（顶部计数保留）；「近 7 天下载」独占整行、七列均分；侧边栏加宽 132→168px。

### 代码审查修复（Sep 11 之后 42 提交：1×P0 + 4×P1 + 18×P2/P3）
- **P0 只读令牌提权**：`ViewerPolicy.sanitizeCredentials` 按字段名正则脱敏凭据，`ConfigController.config()` 对 viewer 请求脱敏，阻断只读令牌经 `/config` 读到 `apiKey` 等 10 个凭据字段后提权写接口。
- **P1**：`PreviewView` 树表索引回 `it.row`（修复子行读错订阅 / 整表白屏）；`SubtitleService` 压缩包字幕扩展名改用 `originalName` 且 `FetchPlan` TTL 改 `stampCreatedAt`；`AniController` 删除本地文件前加 `DeleteGuard` 目录边界闸门（防空模板删 CWD）；`SubscriptionListView` 补 `groupList` emit + `loadVersion` 守卫（修复分组筛选失效）。
- **P2**：`DownloadService` 占位文件删除后置到 `saveTorrent` 校验之后；`ConfigUtil` 系统通知迁移用 `notificationSystemMigrated` 标志（修复关不掉）；`ShareController` 先限长再流式解压带累计大小上限（修复 zip bomb）；`ItemsUtil` profile 禁用视为无偏好；`DoctorController` 关闭 HTTP 响应；`AniUtil` 合集校验 `verifyCollectionAni` + `saveCover` 移出订阅锁；`OpenList` 重命名图同名不同路冲突抛 `IllegalStateException`；`LibraryController.invalidate` 随订阅同步。
- **P3（前端）**：`TaskManagerView` 轮询在 KeepAlive 下 `onDeactivated` 停止；`TorrentsInfosView` `useLocalStorage` 防隐私模式白屏 + `:key=tag`；`SubscriptionView` 清筛选不再持久化用户偏好；`LogsView` 先判 `content-type` 再解析（修复把 JSON 错误当 zip）；`PlayListView`/`NotificationView` 列表 `:key` 唯一性。
- 新增测试 `ViewerPolicyTest`、`DeleteGuardTest`；验证：后端全量 224 测试类 / 0 失败 / 1 跳过，前端 `vite build` 通过。


## 3.4.4 增量（2026-09）

- **本地字幕批量导入**：字幕匹配页支持选择订阅、点击或拖拽多选字幕文件；后端按季集匹配已重命名视频，自动生成 `剧名 SxxExx[.语言].ass/srt` 标准文件名，并逐文件返回成功/失败原因。
- **字幕安全与兼容性**：支持 ASS/SRT/SSA/VTT/SUB，单文件 20MiB、单次 200 个；按原始字节写盘以避免 GBK 字幕乱码；同名字幕覆盖前备份 `.bak`，临时文件原子归位。
- **合集判定修复**：统一按不同集数而非视频文件数量判断合集；总集数为 1 的 RSS、qBittorrent、Aria2、OpenList 条目按单集处理。
- **字幕匹配页面修复**：刷新/清空日志改用已导出的 API 方法，修复页面运行时调用未导出的 `http.post` 问题。


## 3.4.3 增量（2026-09）

### F-11 质量择优规则（2026-09）

- 新增全局 `Config.qualityProfile` 与订阅级 `Ani.customQualityProfile`（默认关闭，存量行为不变）
- 支持：分辨率偏好顺序与上下限、编码偏好/排除（HEVC/AV1/AVC）、体积上下限、字幕组偏好/排除、做种数下限、合集优先开关
- 规则优先级明确：**多字幕组共存 > 洗版主源优先 > 质量规则**；质量规则只在既有候选池内部择优，不会把主源换成备用源
- 规则过严导致同集候选全部被过滤时自动回退为不过滤，避免一集永远不下；RSS 未提供做种数时不按 0 过滤
- 非 v2 命名路径也接入同集择优；v2 合集继续保持合集优先后再应用质量规则
- 前端「基本设置 → 质量择优」与订阅编辑「自定义 → 质量择优」均提供表单；新增 13 个规则测试 + 2 个旧订阅兼容测试全部通过

### 已验证的环境/兼容性边界（2026-09）

- **`File.getUsableSpace()` / OpenList/AList 挂载点**：在当前 Windows 环境实际测得：本地 `D:\UGit\ani-rss` 返回 `total=317404307456`、`usable=121405263872`；配置中常见的 `/115` 在 Windows 下解析为 `D:\115`，当前未挂载时 `exists=false`、`total=0`、`usable=0`；UNC `\\localhost\115` 同样未挂载返回 0。结论：不能把 `usable=0` 当成"磁盘已满"，`DiskMonitorUtil` 已将 `total<=0` 标记为**不可测**并跳过告警。真实 OpenList 远端空间不由 Java `File` 反映，应以 OpenList API 配额接口另做探测；本次未伪造远端结论。
- **Artplayer `autoPlayback` 文件重命名**：已读取当前锁定版本 `artplayer@5.4.0` 源码确认：时间进度写在 `storage.times[art.option.id || art.option.url]`；当前项目已显式传 `id: playItem.name || src`，因此 URL token 变化不会影响同名文件的续播；但**文件重命名会改变 `id`，旧进度不会自动迁移**（会表现为新文件从 0 开始）。本次未实现 F-12 服务端同步，避免把半成品留在代码库；若后续做 F-12，应以订阅 id + episode/稳定内容标识做服务端主键，并在重命名时迁移旧 key。
- **旧 `ani.v2.json` 反序列化**：已用真实 `AniUtil.load()` 路径测试旧 JSON（不含 `priority/group/tags/qualityProfile`），Gson 反序列化成功，`createAni()` + `BeanUtil.copyProperties(... ignoreNull, override=false)` 补齐：`priority=1`、`group=""`、`tags=[]`、自定义质量规则关闭且字段完整。新增 `AniLegacyFieldCompatibilityTest` 2 例全通过。

### 功能规划落地批次（17 项）

依据仓库内《功能规划建议报告.md》落地：P0 快赢 8 项 + P1 调度优先级与并发 + P2 增强 5 项 + P3 战略 3 项。
**验证：后端 `mvn test` 全量 479 通过（新增 72）；前端 `vite build` 通过。**

#### P0 快赢

| 编号 | 功能 | 说明 |
| --- | --- | --- |
| F-01 | **系统自检（Doctor）** | `POST /api/doctor` 聚合 9 项检查（配置目录可写 / 下载路径 / 下载器登录 / 磁盘 / TMDB / BGM / Mikan 可达 / 通知渠道 / 任务线程），每项给出结论 + 证据 + 下一步建议。**不新增探测逻辑**，全部复用既有能力（`BaseDownload.login`、`HttpReq`、`DiskMonitorUtil`、`NotificationUtil.getLastSend`） |
| F-02 | **下载历史 / 活动时间线** | 新增 `DownloadHistory`（仿 `FailedDownloadQueue` 的 temp+rename 原子写、容量 1000、解析失败改名保留现场）。埋点 3 处：qB/TR/Aria2 走 `DownloadService.notification`，失败走 `recordDownloadFailure`，OpenList 走自身完成链路（3 个完成点）。新增 `/downloadHistory`、`/downloadHistoryStats`（总览 + 按天趋势）、`/downloadHistoryRemove`、`/downloadHistoryClear` |
| F-03 | **通知渠道扩展 6 个** | ntfy / Gotify / PushDeer / 飞书 / 钉钉（支持加签）/ 企业微信（markdown 4096 字节安全截断）。`NotificationUtil.NOTIFICATION_MAP` 从 `Map.of`（10 对上限）改为 `LinkedHashMap`，后续加渠道不再受限 |
| F-04 | **MCP 写操作扩展** | 新增 8 个 `@McpTool`：`set_subscription_enabled`、`refresh_subscription`、`get_task_status`、`list_failed_items`、`retry_failed_item`、`cancel_rss_job`、`cancel_rss_item`、`diagnose_subscription`。破坏性操作如实标注 `destructiveHint` |
| F-05 | **磁盘空间监控** | 新增 `DiskMonitorUtil`（路径解析规则与 `FileController` 一致）+ `DiskTask`（默认 60 分钟一轮，阈值默认 85%）。**档位去重**（阈值/+5/+10 三档，6 小时冷却，回落清态）；网络盘 / 未挂载判为「不可测」而非 0% 使用率 |
| F-06 | **追番日历视图** | `CalendarView.vue` 周视图 + 月视图，复用 `listAni` 的 `weekLabel` 与 `healthLevel`，零新增后端 |
| F-07 | **手动搜索补种** | `ManualSearchController` 聚合「主 RSS + 全部备用 RSS + 用户粘贴的 RSS」；底层全部复用（`ItemsUtil.getItems` 解析、`itemDownloaded` 判重、`forceDownloadItem` 下单）。`/manualSearch` + `/manualDownload` |
| F-08 | **首页看板增强** | 新增「近 7 天下载趋势」（CSS 柱状图，不引入图表库，守住首屏体积）、健康分分布、漏集 TOP |

#### P1 调度优先级与并发（F-10）

- `Ani.priority`（0=高 / 1=普通 / 2=低，越界收敛到 [0,2]）；`RssTask.sortByPriority` **稳定排序**，同级保持原顺序
- `Config.rssConcurrency`（默认仍为 3，上限 8）；非法值回落，**绝不出现 0 线程池**
- 未改动任何锁与抢先语义（3.2.31 刚加固的全局锁与手动刷新逻辑保持不变）

#### P2 增强

| 编号 | 功能 | 说明 |
| --- | --- | --- |
| F-15 | **本地媒体库浏览** | `/library`（60 秒缓存，复用 `PlayController.getPlayItem` 保证与播放侧同一套字幕/视频判定）、`/libraryDetail`、`/libraryRefresh` |
| F-16 | **结构化事件 Webhook** | `EventWebhookUtil` + `EventTypeEnum`（9 种事件）。发 **JSON 事件体**而非渲染文本；单线程 + 有界队列 256 反压，队列满丢弃计数、**绝不上抛中断下载**（沿用 3.2.15 的保护）；支持事件类型过滤（留空=全部，`ALL`=全部） |
| F-17 | **追番周报** | `WeeklyReportTask` 复用 `DownloadHistory.summary` + `FailedDownloadQueue` + `SubscriptionHealth.cachedOmitCount`；可选「有漏集时自动补种」，走统一刷新入口（可在任务页观察/取消） |
| F-18 | **字幕匹配与补全** | `SubtitleService`：缺字幕扫描（复用播放侧判定）+ 就地附加字幕（原子写、覆盖前 `.bak` 备份、20MiB 上限、UTF-8 安全）。**在线抓取默认关闭**，`subtitleAutoFetch` 作为开关位由部署方接入具体源 |
| F-19 | **订阅分享 / 一键导入** | `/shareAni` + `/importAniByCode`。采用**白名单复制**（而非黑名单剔除）：只写明确安全的字段，将来 `Ani` 新增字段默认不外泄；分享码为 gzip+base64url；导入统计新增/替换/跳过 |

#### P3 战略

| 编号 | 功能 | 说明 |
| --- | --- | --- |
| F-20 | **AI 诊断** | `diagnose_subscription` 聚合健康分 + 漏集缓存 + 下载历史 + 失败队列，输出**可读结论 + 可执行建议**（如"已 14 天没有新下载，可能是番剧停更、RSS 源失效或字幕组更换"） |
| F-21 | **调度状态持久化** | `RssJobStateStore` 落盘「上一轮结果 + 订阅级失败明细（上限 50）」，启动时经 `RssTask.restorePersistedState()` 恢复。**刻意不持久化运行中/排队中的活动态**——重启后那些任务客观上已不存在，恢复出"运行中"只会制造幽灵状态 |
| F-22 | **只读访问令牌** | `Config.viewerApiKey` + `ViewerPolicy` 白名单。用只读令牌访问只能「看和播」，写操作一律 403。**未配置时行为与之前完全一致**；白名单语义保证将来新增端点默认对只读者关闭 |

#### 配套改动

- **`NotificationStatusEnum` 新增 `SYSTEM`**（系统通知）；`ConfigUtil.format` 对存量配置做**追加式迁移**（只补 `SYSTEM`，不删用户已有选择），否则磁盘预警/周报会静默不发送
- **`NotificationUtil.sendSystem`**：系统级通知不依赖任何订阅，使用合成 `Ani`（标题/季度/发布日期给安全默认值，避免通知模板 NPE）
- **动作类渠道过滤**：`EMBY_REFRESH` / `FILE_MOVE` / `OPEN_LIST_UPLOAD` / `SHELL` 在 `SYSTEM` 状态下跳过——否则一次磁盘预警会触发 Emby 全库刷新或执行用户的下载后脚本
- **导航扩展**：新增「媒体库」「历史」「工具（自检/日历/补种，支持 `?tab=` 深链）」；移动端导航改为横向滚动（8 项均分会把文字挤没）
- **设置页新增**：RSS 并发度、磁盘空间监控、追番周报、事件 Webhook、字幕自动获取、只读令牌
- **订阅编辑新增**：优先级三档

#### 新增测试（72 个，全绿）

`DownloadHistoryTest`(8) · `DiskMonitorUtilTest`(9) · `DiskTaskTest`(3) · `RssTaskPriorityTest`(8) ·
`ShareControllerTest`(6) · `ViewerPolicyTest`(5) · `EventWebhookUtilTest`(8) ·
`ExtraNotificationChannelsTest`(10) · `SubtitleServiceTest`(10) · `RssJobStateStoreTest`(5)

---

---


## 3.4.2 增量（2026-09）

### 合集：OpenList 离线下载支持

- 「添加合集」按下载器分流：**qBittorrent 原路径不变**；**OpenList/Alist 走离线下载全链路**（提交即受理 → 分级轮询等待 → 10008 去重/卡住重提/超时终检 → 任务管理器进度）；其他下载器给出明确提示
- 离线完成后按「预览计划」归位：按 文件名+大小 匹配离线产物 → 重命名为预览目标名（含字幕语言段）→ 移动到下载目录顶层 → 顶层校验 → 完成通知（含归位文件数）
- 未匹配文件（SPs/Scans/音乐/Fonts 等排除项）随临时目录强制清理，效果与 qB 的 filePrio=0 一致；整包离线是网盘固有限制
- 防御：归位映射为空时判失败并保留临时目录（杜绝「空归位→清临时目录→误报完成」）；合集基名优先取第一个正片文件，避免字幕语言段混入；缺集校验复用现有机制

### 修复：合集上传种子报 NPE

- 修复「Cannot read the array length because "data" is null」：UI 迁回上游架构后，后端缺失 `/uploadAndReadToBase64`（合集种子上传）与 `/uploadAndRead`（订阅导入）两个端点，上传 404 后前端仍显示文件名，实际 `torrent=null` 触发底层 NPE
- 恢复两个端点（保留本 fork 的 10MiB 大小限制），合集入口增加「种子内容为空/解析失败」的友好报错；订阅导入功能同批恢复

### 其他

- 共享批量重命名跳过 src==new 同名重命名（部分网盘实现对 src==new 报错）

验证：真实 VCB BDRip 合集种子 dry-run（36/36 归位匹配、104 附加文件清理、0 冲突）+ OpenList 既有 52 项回归测试全过。

---


## 3.4.1 增量（2026-09）

### 任务中心：下载页与任务管理器合并

- `#/downloads` 升级为**单页双 Tab**：「下载器任务」（原下载表格）+「追番流水线」（原任务管理器弹窗内容整体平移：RSS 调度 / 离线等待 / 失败队列 / 残留运维）
- 默认 Tab 按下载工具自动选（**OpenList 用户默认落流水线**，不再看到空下载页）；手动切换浏览器记忆；支持 `?tab=` 跳转参数
- 侧边菜单「下载」改名「任务」；订阅页「任务」按钮改为直达流水线 Tab
- 任务管理器改可嵌入面板，轮询随 Tab 生命周期启停；失败订阅「定位订阅」改跨页跳转（`?focusAni=`，带有限重试）
- 修复「下载器任务 共 undefined 个」计数

### 其他 UI 改进

- **重命名模板预览**：拆「模板 / 效果」双行实时预览（效果行按示例值渲染真实文件名结果），效果行一键复制，未知变量两行均红色标注
- **首次使用引导**：X 掉后不再每次显示（浏览器记忆）；三步全部「已就绪」自动隐藏；收起后留一行小链接可随时找回
- **通知渠道卡片**：行内启用开关（状态即开关，免进对话框确认）+ 停用渠道整卡降透明度
- **视觉**：任务/订阅内容区白底卡片化，流水线任务卡与失败队列改灰底分层

构建 ✓（后端 compile + 前端 vite build）。

---


## 3.4.0 增量（2026-09）

### 合并上游 3.2.23~3.2.30（基线 3.2.22 → 3.2.30）

逐文件评估上游 8 个版本（47 文件，代码约 +750/−466），移植全部有价值改动，fork 已有等价实现的不重复合入：

- **首页修复**：「今天的订阅」仅显示已启用的订阅（与上游 3.2.23 同向，独立先行落地 `5c4f59a6`）
- **种子缓存复用**（上游 3.2.28/3.2.30 #726）：`TorrentUtil.getTorrent` 先查已存在的 `.txt`/`.torrent` 缓存再按磁力/种子类型返回，避免同 infoHash 在磁力↔种子表示切换时缓存文件名漂移、重复下载
- **代理测试增强**（上游 3.2.23）：后端返回页面标题（`ProxyTest.title`，fork 侧位于 ConfigController）；前端全新测试结果卡片——自定义测试地址（可创建项）、成功/失败状态卡、HTTP 状态码+耗时、响应式布局
- **自定义点击封面行为**（上游 3.2.29 #725）：设置页新增「点击封面」（编辑订阅/视频列表/编辑封面），卡片与封面两种视图统一接入；封面字幕组 tooltip 移除（3.2.30）
- **日志结构化**（上游 3.2.30）：后端 `Log` 消息只保留正文+异常，时间由独立时间戳字段承载；日志页改由前端格式化，不再正则拆前缀
- **封面质量**（上游 3.2.26）：配置键 `bgmImage`→`bgmImageSize`，默认 large→**medium**（存量配置自动回落 medium），选项收敛为 large/medium/common
- **预览增强摘取**（上游 3.2.30）：`navigator.clipboard` 优先+execCommand 兜底（复制成败有明确提示）、对话框关闭重置状态、加载竞态守卫

**无需合入**（fork 已有等价或更强）：AniBT 磁力兜底（3.2.28，已移植）、ManageView 批量操作重构、PreviewView 整体重构、qBittorrent 空配置守卫、AniUtil 封面回填。

**视觉与工程同步**：按钮图标规范化（`icon` prop + `.auto-button` 移动端隐藏文字）、`--body-background-color` 背景 token（亮 `#f7f5fa` / 暗 `#000`）、分区卡片去边框；vite dev 代理支持 https 后端（`changeOrigin+secure:false`）、jsoup 1.23.2、`pnpm-lock.yaml` 纳入版本管理。

验证：后端 `mvn compile` ✓ / 前端 `vite build` ✓。

---


## 3.3.0 增量（2026-09）

### UI 整体迁移回上游新版（view/ 架构 + 新视觉）

本次为 **前端整体换代**：丢弃 fork 自有的 `home//config/` 平铺旧版结构，整体采用上游 `wushuo894/ani-rss` 3.2.30 的新版 UI（`src/view/{home,config,custom,play}` 路由化架构、vue-router 页面路由、PageHeaderView/SettingsItem 设计语言与新视觉皮肤），并把 fork 的**全部自有功能**重新适配移植到新 UI 之上。**后端零改动**，fork 全部 API 端点已逐一验证兼容；登录仍为 SHA-256（兼容旧密码，升级无需重新设置）。

### 旧版独家功能全部回归

| 模块 | 移植内容 |
| --- | --- |
| 订阅列表 | 任务管理器入口、备用RSS 标识、运维健康分、注意圆点（漏集/摸鱼/今天更新）、下载进度条、封面失败兜底、评分守卫、排序（默认/最近更新/评分/拼音）、今天置顶、字幕组搜索、加载失败重试、任务管理器点击跳转定位 |
| 编辑订阅 | TMDB **标题搜索→候选列表→选择应用**（替代手输 ID）、切换主RSS、OVA/电影命名模板与变量工具、剧集组、下载路径计算 |
| 播放 | 续播记忆（autoPlayback 稳定 id + play-progress「上次看到」）、lang=zh-cn、preload 归位；修复上游弹弹Play/AnimacX/SenPlayer 菜单项不渲染缺陷 |
| 添加订阅 | RSS 域名嗅探自动切来源、同名同季覆盖前二次确认、添加成功清空首页搜索词 |
| 预览 | **强制下载**（删文件重下）、合集树形展示（修复子集条目不可见）、来源列、本地存在三态（已下载/下载中/未下载）、筛选聚合判定 |
| 管理 | 全选/反选/清空、批量操作(N) 计数、行内启用开关、漏集/最近更新列、批量任务 rssJobStatus 轮询收敛（3s/10min 引导）、未勾选守卫 |
| 删除 | 批量预览清单、删除前解析真实下载目录并展示、确认框改 h() 渲染（移除 dangerouslyUseHTMLString 注入面） |
| 导入 | 冲突计数预警（同名同季风险提示）；服务端解析（uploadAndRead） |
| 设置 | 页签搜索（唯一命中自动切换）、首次使用引导、本地偏好/排序/宽度提示；fork 配置字段全量适配（alist*、buildInfo、corsOrigins、networkPrefer、newTorrentWaitHours、rssRetry、ovaRenameTemplate、maxFileNameLength 等） |
| 日志 | 下载改带 Authorization 头的 fetch+Blob（令牌不再进 URL/历史/反代日志）+ 跟随开关 |
| 其他 | 订阅刷新感知后台任务状态（忙碌改确认文案、排队警告弹窗）、Bark 通知配置、登录交互提示与常驻限制 |

### 说明
- 版本号进位到 **3.3.0**：UI 换代属于大版本变更；旧版 UI 的历史截图与交互不复存在，功能等价或更完整。
- 新版 UI 的设置搜索、快捷入口等按新架构重新接线；fork 的 settings-search 索引、play-progress、格式化工具等 js 层全部保留。
- 逐项功能提交见 git log（`04a1affc`…`e72765da` 共 12 个迁移提交）。

---


## 3.2.35 增量（2026-09）

### 订阅卡片（card 模式）重设计

按专家任务书（`订阅卡片修改参考/`）重构 `AniCard.vue`，信息层级重排、去标签方阵，同时保留本 fork 全部既有差异（封面 button 化 + 加载失败占位、注意圆点 popover、运维健康分 popover、进度 tooltip、第 1 季不占位、aria-label、URL 解码兜底）：

- **评分**改为海报右上角小圆角徽章，按分数分级配色（≥7 绿 / 5~6.9 橙 / <5 红，全部 Element Plus 变量，暗色自适应），替换原写死 `#E800A4` 的文字行；点击仍弹出打分。
- **次要信息合并一行灰字**：`TV/OVA · 第 X 季 · 字幕组`（字幕组超长省略 + tooltip），替代等宽标签方阵。
- **集数进度**：`当前/总数` 文字 + 细进度条（仅总集数已知时出现，`*` 不画条、不做除法）。
- **状态行**：注意圆点 · 启用状态（小圆点+文字）· 备用RSS（小字）· 健康分标签，最近下载时间右对齐同行末尾。
- **操作按钮默认隐藏**，卡片 hover / 键盘聚焦时浮现（沿用封面模式交互语言）；触屏设备（`hover: none`）常显。
- **未启用订阅封面置灰降饱和**，一眼可辨。
- 对外契约不变：`props: ["item"]`、五个事件与参数原样；showScore=false 时回退 RSS 地址副标题逻辑保留（健康分存在时优先显示健康分，与历史一致）。
- `ani-rss-ui` vite 构建通过（3569 模块，无新增告警）。

---

### 备用RSS占位替换修复（主RSS洗版）

**问题**：主RSS暂缺某集时由备用RSS补下；主RSS出种后本应洗版替换，但主RSS条目会被 `itemDownloaded` 的「本地文件已存在 / 已存在下载任务」判定拦截（该判定按集数/重命名匹配，不区分主备来源），且判定命中时还会补写主RSS种子记录，此后每轮在更早的「种子记录已存在」处被跳过——替换逻辑 `deleteStandbyRss` 位于判定之后，永远无法执行。「仅在主RSS更新后删除备用RSS」模式同样只删任务不删文件，仍被文件判定拦截。

**修复**（洗版 delete + 备用RSS + 未开共存时生效）：
- 下载器任务带「备用RSS」标签即视为该集占位：主RSS条目被「已存在」拦截时识别占位，删除备用任务与文件后放行主RSS下载（qBittorrent / Transmission；Aria2 不打标签、OpenList 无任务列表，保持原行为）。
- 「仅在主RSS更新后删除备用RSS」分支补齐文件删除（对齐 UI 文案「删除对应备用RSS的任务与文件」），并同步移除本轮集数索引防止 stale 误判。
- 同集候选选择（合集优先去重）在洗版场景下主RSS条目优先，避免备用条目按画质/体积永久遮蔽主RSS条目。
- `TorrentUtil.delete` 的空目录清理改为辅助操作，失败不再使整个删除返回失败。
- 回归测试：`StandbyPlaceholderTest`（占位识别/删除/守卫 10 例）、`DistinctMasterPriorityTest`（主RSS优先选择 7 例）。

---


## 3.2.34 增量（2026-09）

### 卡片布局修正 + 用户体验专项（122 条）定稿

**修正：卡片布局改回原版**
- 上一版我把卡片操作按钮从「卡片内右侧竖排浮层」改成「通栏独立一行」，并给标签网格换成自适应列，导致卡片高度/宽度/标签换行都不对齐、窄列放不下、悬停时卡片还会因操作行占位而跳动。现**全部退回原版布局**：操作按钮回到 `absolute` 竖排浮层（不占布局）、标签网格回到固定 `180px` + 桌面 3 列 / 窄屏 2 列、标题回到 `200px`、封面保持 `92×130`，不再新增任何占位行。
- 进度文案退回紧凑的 `当前 / 总数`（完整语义放进 tooltip），第 1 季不再占一格。
- 只保留不涉及排版的改进：封面改 `<button>`（键盘可 Tab / 读屏有名）、操作按钮补 `aria-label`、空值防护、评分旁一个 8px 状态小圆点（漏集/禁下/摸鱼/周几更新）、健康原因改可点击弹出。
- 校验：原版 15 条布局规则逐条核对 **0 缺失**，样式块相对原版 **0 行删除**。

**同时包含 3.2.33 的全部内容（P0 × 6 / P1 × 63 / P2 × 53，共 122 条）**
- **反馈与错误处理**：接口失败不再静默空白（统一网络/非 JSON/非 2xx 提示 + 全局错误兜底 + 失败态与重试）；日志加时间戳、关键词搜索、跟随开关；下载列表区分「空 / 查询失败 / 数据过期」；任务管理器失败改常驻告警 + 退避；登录限流提示不再被 1 秒后强制刷新抹掉；设置 Esc/遮罩/取消 均确认未保存改动
- **确凿功能修复**：「清空评分」此前调查询接口、功能完全不可用；AniBT 批量添加调用不存在的 `list()`；播放器 3 个菜单项错位导致弹弹Play/AnimacX/SenPlayer 不可达；预览补「下载中」第三态；运行中异常补人话化；通知测试返回真实结果 + 「最近一次发送」展示
- **信息透出**：订阅级失败明细（归因/建议/原始错误）+ 一键定位；下载列表补速度/ETA/已下载/做种数（qB·TR·Aria2）；Transmission 状态映射 3→7 种
- **首页与批量**：今天置顶 + sticky、搜索防抖与字幕组匹配、排序下拉、骨架屏、键盘快捷键 `/` 与 `Ctrl/⌘+K`；管理页已选计数/全选/反选/清空 + 跨筛选保留；删除对话框列明细并移除 `dangerouslyUseHTMLString`
- **添加订阅**：来源说明 + 域名嗅探 + 同名二次确认；批量添加可取消且逐条容错；AnimeGarden 补关键词搜索（后端 `searchBgmUrl`）
- **播放**：字幕不再阻塞开播、默认字幕竞态修复、选集定位与「看到这里」、关闭字幕项、播放链路懒加载
- **设置**：设置项搜索（8 页签上百字段可定位跳转）、快速开始清单、下载器地址归一化与路径模板校验
- **移动端与无障碍**：移除 `user-scalable`、`aria-label` 补全、44px 触控、安全区/`100dvh`/`@supports`、状态色对比度；`index.html` 修正非法结构与子路径部署 + 首屏防白闪

**量化**：首屏 `main.js` 1007.7 KB → 316.7 KB（−69%），artplayer 不再预载，预载 gzip ≈556 KB → ≈448 KB。
**验证**：`vite build` ✓ / `mvn clean compile` BUILD SUCCESS ✓。

> 完整逐条清单与证据见仓库内《用户体验专项审计报告.md》与《用户体验修复状态总览.md》。

---


## 3.2.32 增量（2026-09）

### RSS 解析可靠度专项：tv 99.92% / movie·ova 100%（8553 条真实标题语料）

**误丢条目级 bug（生产影响）**
- **`parseInt("10.5")` 崩溃丢条目**：`isYearOrDate` 对长度恰为 4 的 "10.5" 直接 `parseInt` 抛异常，整个 x.5 小数集条目被 catch 后静默丢弃；两处加纯数字守卫
- **acg.rip 整站拒收**：其种子 URL 为数字 ID 非哈希，infoHash 安全校验将全部条目 continue 丢弃；改为以种子 URL 的 SHA-256 合成稳定且路径安全的 64 位 hex 标识（路径穿越载荷同步被中和）
- **编号特典挤掉正片**：SP01/OVA01 等原落 S01E01，与正片 E01 按 `season:episode` 去重先到先得；现统一落 **S00 特典季保留集数**（OVA02→S00E02、OAD3.5→S00E03.5），去重查询季号改为优先取 reName 中的 Sxx，特典与正片、特典之间互不碰撞
- **NCOP/NCED 不再下载**：无版权 OP/ED 单曲（含编号）在兜底链直接拒绝；`第01话+NCOPED` 混装包不受影响仍按正片下载

**解析能力补全（正确拒绝的前提下提成功率：38 → 5 条失败）**
- 小数集 `139.5`/`OAD3.5`、`第N回`、`467-B`/`490B`、`038_V2`、`084 V2`、`EPISODE.0`、柯南 `-P1` 分部
- 语义合集：`road to` 系列、欧语 `COMPLETA/COMPLETO`、`正片+SP`、整季 BD/WEB 质量词包（无集数时才触发，不引入新误匹配）
- 年份/8 位日期守卫贯穿全部新分支；`[1992] XXX_OVA` 年份首匹配即放弃修复

**XML 层护栏（真实 feed 样本入库测试）**
- mikan×2（420 条）/nyaa（75）/dmhy（500）/acg.rip（30）/bangumi.moe（50）六源 fixture 入库 `src/test/resources/feeds/`，13 项断言：字段提取、magnet 兜底、缺 channel 明确报错、XXE 不回显、enclosure 缺 url 单条跳过
- `ItemsUtil` 拆分 `buildItems`（纯 XML 层）/`parseItems`（rename 语义层），解析与 HTTP 解耦可测

**回归护栏**
- `RenameAccuracyTest` 精度语义测试（编号特典/合集/拒绝边界/占位符守卫）；`AdaptAnalyzeTest` 固化 tv 失败 ≤5、misMatch==0、movie/ova 零失败阈值
- 全量 28 个测试类通过；临时诊断测试清理

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
