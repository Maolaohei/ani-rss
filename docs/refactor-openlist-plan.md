# OpenList / 115 云下载重构计划

## 目标

把当前集中在 `OpenList`、`DownloadService`、`RssTask` 的职责拆成可独立测试、
可独立演进的能力模块。重构期间 OpenList / 115 云下载继续作为核心功能保留。

## 非目标

- 不重写下载链路。
- 不改变现有用户配置、种子记录、失败队列和计划快照格式。
- 不为了减少行数强行拆出大量只有转发作用的类。
- 不在重构 PR 中混入 UI 改版或新功能。

## 依赖约束

1. `download/openlist/` 下的策略与文件模块不得依赖 `task.RssTask`、
   `service.DownloadService`。
2. 核心下载/调度路径不得新增 `SpringUtil.getBean`。
3. 新代码不得使用跨请求共享的静态可变状态；现有静态状态按阶段迁移。
4. 网络、文件系统、通知、持久化必须位于编排层或适配层，策略层只做纯判定。

## 目标边界

```text
download/openlist/
  policy/    纯路径、季集、标题、临时目录、残留分类
  api/       HTTP、认证、限流、重试
  offline/   提交、等待、重试、最终化
  files/     目录扫描、文件匹配、归位、重命名
  residual/  残留扫描与清理
  runtime/   实例化状态、线程池与生命周期
```

## 分阶段

### Phase 0：基线

- 记录当前测试与构建基线。
- 建立依赖边界检查。
- 冻结 OpenList 新功能，避免边拆边长。

### Phase 1：纯策略

- 路径归一化与目录归属判断。
- 季数/集数提取与匹配。
- 云下载标题守卫。
- 洗版目标判定。
- 临时目录名与残留分类。

策略迁移必须保持现有测试通过；测试应直接指向新策略类，避免继续把
`OpenList` 当作纯函数命名空间。

### Phase 2：编排层

- 从 `OpenList` 拆出离线提交/等待、文件归位、残留治理。
- 从 `DownloadService` 拆出本地存在判定、洗版、路径与失败恢复。
- 从 `RssTask` 拆出调度、执行、控制和状态投影。
- 删除核心链路的反向 `getBean`。

### Phase 3：状态实例化

- OpenList 静态集合、锁表和冷却表迁移到运行时对象。
- `RssJobState` 从静态状态改为实例状态。
- 配置读取通过注入的配置提供者完成。

### Phase 4：前端

- 优先拆 `TaskManagerView.vue` 的轮询、操作和面板组件。
- 保持现有交互和视觉，不做页面重设计。

## 安全边界

- 删除、移动、归位、临时目录清理在 Phase 1 不修改行为。
- “查询失败”与“确认不存在”必须继续区分；任何重构都不得把
  `UNVERIFIABLE` 合并成 `ABSENT`。
- 在途 hash、活动临时目录和清理保护必须在迁移前后保持一致。
- 每批重构必须可独立回滚。

## 验收

- OpenList 策略层不依赖调度层和下载服务。
- 核心路径不新增 `SpringUtil.getBean`。
- 现有 OpenList / 洗版 / 归位 / 残留测试全部通过。
- 前端构建通过。
- 每个阶段结束记录行数、依赖和测试结果，供下一阶段对比。

## 第一批落地

1. 建立 `OpenListPolicy`，迁移无 IO 的路径、季集、标题和洗版判定。
2. 保留 `OpenList` 中的兼容入口，先确保调用点零改动。
3. 将对应测试迁到策略类，随后再逐步删除兼容入口。

## 进展（2026-10-17）

### 已完成

- **Phase 1 纯策略**：`download/openlist/policy/OpenListPolicy` 落地，`OpenList`
  只保留转发入口；策略边界由 `OpenListPolicyBoundaryTest` 锁住。
- **Phase 2 编排层（DownloadService）**：`downloadAniLocked` 由 409 行收敛为
  15 行编排，逐条目拆成 `downloadItem` → `passGates`(223) → `submitItem`(116)，
  收尾独立为 `flushRunState`(33)；跨阶段字段由 `ItemPlan` 承载，闸门顺序与
  “占位删除推迟到主RSS提交成功之后”的语义未变。
- **Phase 2 编排层（RssTask）**：核心路径最后一处 `SpringUtil.getBean` 换成
  启动时注册的 `RssDownloadPortProvider`；`getJobStatus` 由 194 行降到 94 行，
  运行态读取收敛为 `OfflineRuntimeView` / `ResidualRuntimeView` 两个记录，
  `buildTaskItems` 参数由 33 个降到 18 个。
- **Phase 4 前端**：`TaskManagerView.vue` 由 544 行降到 458 行，总览区拆成
  `JobStatusPanel.vue`；残留/失败队列面板补上各自的作用域样式，避免拆分后
  子组件内部样式失效。
- **边界守卫**：新增 `CorePathBoundaryTest`——核心路径不得再出现 `SpringUtil`，
  `downloadAniLocked` 不得超过 60 行，`getJobStatus` 不得超过 200 行。

### 验证

- `mvn -o -pl ani-rss-application -am test -Dexec.skip=true`：1006 个测试通过，
  0 失败、1 跳过；`ani-rss-ui` 的 `pnpm build` 在同一轮构建中通过。
- 重构过程用“规范化语句 diff”（去注释、去空白、剥离 `run.`/`plan.` 前缀）逐行
  比对旧方法与新方法，确认没有语句丢失、没有顺序变化。

### 第二批（离线等待 / 残留扫描收敛）

- **离线等待**：`awaitAndFinalize` 从 347 行降到 **205 行**，其中
  10008「任务已存在」分支独立为 `handleDuplicateOfflineTask`(94 行)，结论用
  `DuplicateTaskOutcome`（`done` / `resubmitted` / `keepPolling`）显式表达，
  替换原来散落的 `continue` / `return`；
  等待窗口到期后的终检独立为 `confirmDeadlineInspection`（返回 `Optional<Outcome>`）。
  “存疑 ≠ 失败”“10008 优先于文件兜底”等注释语义随代码一起搬走，没有丢。
- **残留扫描**：`scanTempDirResiduals` 从 252 行降到 **118 行**，拆出
  `collectActiveTempDirs`(14)、`collectTempDirScanTargets`(41)、
  `collectTopVideoKeys`(18)、`probeTempDirCandidate`(72)，以及
  `TopVideoKeys` / `TempDirCandidateProbe` 两个记录。
  扫描侧只读、清理侧只写、结论仍由 `TempDirResidualPolicy.decide` 给出。
- **边界守卫**：`CorePathBoundaryTest` 新增三条上限——
  `awaitAndFinalize <= 210`、`handleDuplicateOfflineTask <= 110`、
  `scanTempDirResiduals <= 130`，防止再次长回石山。

### 验证（第二批）

- OpenList 定向：`-Dtest=OpenList*Test` → 138 个测试通过，0 失败。
- 全量：`mvn -o -pl ani-rss-application -am test -Dexec.skip=true`
  见下方“最新一次全量”记录。

### 第三批（残留治理模块化）

- **新包 `download/openlist/residual/`**：残留扫描与清理整块搬出 `OpenList`，
  收敛为 `OpenListResidualService`(698 行) + `OpenListResidualPort`(85 行) 和
  6 个顶层 DTO（`ResidualSnapshot`、`ResidualItem`、`CleanResult`、
  `TempDirResidualSnapshot`、`TempDirResidualItem`、`ResidualKind`）。
- **宿主边界**：`OpenList` 内部新增 `ResidualHost implements OpenListResidualPort`
  适配器，把网盘列举/删除、离线任务操作与运行态（在途 hash、活动临时目录名、
  单槽等待快照、下载路径解析、重启恢复）显式暴露给残留模块；残留模块只依赖端口，
  不再反向引用 `OpenList` 本体，也不再查询容器。
- **实例状态**：残留快照、临时目录快照、两个「清理中」标志与「启动已扫描」标志
  从 `OpenList` 静态字段改为 `OpenListResidualService` 实例字段，
  `OpenList` 只保留 6 个兼容转发入口（`scanOfflineResiduals`、`getResidualSnapshot`、
  `getTempDirResidualSnapshot`、`scanTempDirResiduals`、`cleanOfflineResiduals`、
  `cleanTempDirResiduals`）。
- **临时文件判定**：`isJunkTempFile` / `isProtectedTempFile` 移到
  `TempDirResidualPolicy`，与 `decide` 结论相邻，清理侧判定集中在一处。
- **`RssJobController`**：6 处 `SpringUtil.getBean` 换成构造器注入，
  全仓 `SpringUtil.getBean` 由 22 处降到 **16 处**。
- 行数：`OpenList.java` 5637 → **5005 行 / 169 个方法**。

### 验证（第三批）

- **迁移守恒（规范化语句比对）**：把 HEAD 版 `OpenList` 的残留相关方法逐个与
  迁移后的方法体比对（去注释/空白/访问修饰符，统一 `host.` 前缀与兼容入口）。
  13 个方法逐条一致，其余差异全部是计划内的等价改写：
  `config == null` → `!loggedIn()`（`ResidualHost.loggedIn()` 即 `config != null`）、
  `SpringUtil.getBean(DownloadService.class).getDownloadPath(ani)` →
  注入的 `pathResolver().resolve(ani)`（自定义路径分支与空值兜底保留）、
  在途临时目录与等待快照收集改为 `activeTempDirNames()` / `offlineWaitNames()`
  （循环与空值分支搬进 `ResidualHost`）、
  `normalizeOpenListPath` → `OpenListPolicy.normalizePath`、
  `taskCancel`/`taskDelete`/`fsList`/`fsListForCleanup` → 端口方法名。
- **`scanTempDirResiduals` 并集比对**：HEAD 版 186 条语句与迁移后
  `scanTempDirResiduals` + `collectActiveTempDirs` + `collectTempDirScanTargets`
  + `collectTopVideoKeys` + `probeTempDirCandidate` 的 185 条语句一一对应，
  差异仅为上面列出的宿主边界改写。
- **定向测试**：`-Dtest=OpenList*Test,DownloadJourneyE2ETest,RssJobControlTest,CorePathBoundaryTest`
  → 168 个测试 + E2E 6 个全部通过，0 失败。
- **全量**：`mvn -o -pl ani-rss-application -am test -Dexec.skip=true`
  → 1007 通过 / 0 失败 / 1 跳过，E2E 6 通过；同一轮 reactor 内
  `ani-rss-ui` 的 `pnpm build`（vite 8.1.5）通过；总耗时 8:20。
- **边界守卫**：`CorePathBoundaryTest` 的 `scanTempDirResiduals <= 130` 改为读取
  新文件 `download/openlist/residual/OpenListResidualService.java`；
  `awaitAndFinalize <= 210`、`handleDuplicateOfflineTask <= 110`
  仍指向 `OpenList`（等待链路尚未迁移）。

### 第四批（子代理并行：闸门链 / 前端 / 静态运行态 / files 归位）

四路子代理并行，写集合互不重叠——`OpenList` + 新包、`DownloadService` + 通知层、
前端、`RssTask` 守卫与 characterization；改动全部未提交。

**a. `DownloadService` 闸门链显式化（223 → 43 行）**

- `passGates` 改为显式 `GateResult` 链，调用顺序固定为：
  `versionGate` → `existingRecordGate` → `episodeDuplicateGate` → `rangeDuplicateGate` →
  `notDownloadGate` → `latestOnlyGate` → `delayGate` → `newTorrentWaitGate` →
  `standbyCleanupGate` → `existingTaskGate` → `pushedHashGate` → `localPresentGate` →
  `concurrencyGate`；状态准备抽到 `prepareGateState`。
- 备用 RSS 占位仍然只登记待删（`plan.pendingStandbyPlaceholder`），真正的删除留在
  主 RSS 提交成功之后。
- `notification` 99 → 27 行。
- 新增 `DownloadServiceStructureTest`：锁 `passGates <= 60`、每个 `*Gate <= 40`、
  闸门调用顺序（按方法体内调用次序逐个断言）、`notification <= 50`。
- 语句守恒：`passGates` 迁移前后规范化语句比对 4883 → 4883 字符一致。

**b. `OpenList` 静态运行态收编（`download/openlist/runtime/OpenListRuntimeState`，60 行）**

- `inFlightTasks`、`currentInfoHashes`、`duplicateMagnetUntil`(30min)、
  `duplicateMagnetLongUntil`(24h) 收进单例 `OpenListRuntimeState.INSTANCE`，
  跨实例共享语义不变；25 处读写改为 `runtimeState.*`，纯方法签名保持，
  另附 `resetForTests()`。
- 未动（留给 Phase 3）：`DOWNLOAD_LOCKS`、`offlineWaitSnapshot`、
  `ACTIVE_TEMP_DIRS_BY_HASH`、`CANCEL_REQUESTED_HASHES`、`OFFLINE_WAIT_POOL`、
  `PLAN_RESOLVE_POOL`。

**c. `files/` 归位链路拆包（`download/openlist/files/`）**

- `OpenListFileService`(1074) + `OpenListFilePort`(90) + `EpisodeScanResult`(20)；
  `OpenList` 内 `FileHost` 适配器把 `OpenListDirNotFoundException` 在端口边界转成
  `Optional.empty()`，其余失败照抛（「查不到 ≠ 确认没有」）。
- 迁移方法（HEAD → service + delegator）：`relocateEpisodeFiles` 202 → 171+4、
  `buildEpisodeRenameMap` 106 → 107+9、`recoverOnePlan` 98 → 88+3、
  `scanEpisodeFilesOnce` 97 → 102+6、`relocateResolved` 96 → 93、
  `finalizeFromScan` 95 → 91+13、`resolveFiles` 46 → 47、`planScan` 5/50 → 50+5/6、
  `reportPlanGaps` 23 → 13。
- `OpenList.java` 5005 → 4145 行（HEAD 5610 行）。

**d. 前端拆包**

- `SubtitleMatchView.vue` 821 → 57 行：`view/home/subtitle-match/` 10 个文件
  （`SubtitleImportDialog` / `SubtitleImportForm` / `SubtitleImportPreviewTable`、
  `SubtitleCandidateTable`、`SubtitleRecordsPanel`、`useSubtitleImport`(335)、
  `useSubtitleRecords`、2 个 CSS、`format.js`）。
- `DashboardView.vue` 694 → 89 行：`view/home/dashboard/` 5 个文件
  （`DashboardStats`、`DashboardAnalytics`、`useDashboard`(218)、`dashboard.css`、`format.js`）。

**e. 结构守卫与 characterization**

- `CorePathBoundaryTest` 新增：`RssTask.download <= 260`、`buildTaskItems <= 190`、
  `submitOffline <= 190`、`getDownloadPath <= 130`、`passGates <= 230`、
  `notification <= 110`（后两条用容错 helper，方法改名即跳过而不是误报）。
- 新增 `OpenListRenamePolicyTest`(96 行) 与 `WashContractTest`(123 行)。

### 验证（第四批）

- `mvn -o -q -pl ani-rss-application -am test-compile -Dexec.skip=true` → exit 0。
- 定向：`-Dtest=OpenList*Test,DownloadJourneyE2ETest,RssJobControlTest,CorePathBoundaryTest,DownloadServiceStructureTest,OpenListRenamePolicyTest,WashContractTest,ExistingRecordResultTest,RssSchedulerPolicyTest`
  → 188 个单元测试 + E2E 6 个，0 失败。
- **全量**：`mvn -o -pl ani-rss-application -am test -Dexec.skip=true`
  → **1022 通过 / 0 失败 / 1 跳过**，E2E 6 通过；同一轮 reactor 内
  `ani-rss-ui` 的 `pnpm build`（vite 8.1.5）通过；总耗时 8:24。
- 迁移守恒：`files/` 16 个方法规范化语句比对 `ALL_EQUAL`——扫描只读、清理只写、
  在途 hash 与临时目录保护、移动与删除顺序均未变。
- 验收审计（`%TEMP%\acceptance_audit.py`）：`OpenList` 不引用 `RssTask` / `DownloadService`、
  无 `SpringUtil`；核心路径无 `SpringUtil`；各方法行数上限全部 PASS；
  主代码 `SpringUtil.getBean` 仍为 16 处。
- `git diff --check` → exit 0；`OpenList.java` / docs / 前端 LF，
  `DownloadService` / `RssTask` / `RssJobController` / 部分测试 CRLF，与各自原风格一致。

### 第五批候选（按收益排序）

1. **`OpenList` `offline/` 拆包**（4145 行）：`awaitAndFinalize`(205)、
   `submitOffline`(179)、`handleDuplicateOfflineTask`(94)，以及 `relocate` / `wait`
   主流程与两个线程池；沿用 residual / files 的「端口 + 宿主适配器 + 兼容 delegator」模式。
2. **Phase 3 状态实例化**：`OpenList` 其余静态表（含锁与等待快照）与两个线程池、
   `RssJobState` 静态状态；同一批把 `util/other` 与通知层剩余 `SpringUtil.getBean`
   （16 处）换成注入。
3. **`RssTask`**：`download`(252) 按「准备 / 分批提交 / 收尾」收敛，
   `buildTaskItems`(181) 按卡片类型拆小构造方法。
4. **`DownloadService`**：`download`(119)、`getDownloadPath`(117) 收敛。
5. **Phase 4 前端**：`TaskManagerView.vue` 459 行继续按 `task-manager/` 拆。
