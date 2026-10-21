# ani-rss 重构总纲（Phase 0 基线）

> OpenList / 115 云下载的详细拆分方案见 `docs/refactor-openlist-plan.md`。
> 本文只记录基线、边界、危险操作与回滚口径，供每一批重构对照。

## 基线（HEAD → 现在，2026-10-21 用同一口径校准）

口径：行数 = 文件总行数；方法行数 = 从方法签名行到匹配右花括号（含嵌套体），
统一用 `%TEMP%\methods3.py` 度量、可复算。`HEAD` 即重构前提交，
本次重构的全部改动（含第一批至第四批）都还没提交。

| 目标 | HEAD（重构前） | 现在 |
| --- | --- | --- |
| `OpenList.java` | 5610 行 | 4145 行 |
| `OpenList.awaitAndFinalize` | 347 | 205 |
| `OpenList.scanTempDirResiduals` | 257 | 3（实现移入 `OpenListResidualService`，118） |
| `OpenList.relocateEpisodeFiles` | 202 | 4（实现移入 `OpenListFileService`，171） |
| `OpenList.finalizeFromScan` | 95 | 13（实现移入 `OpenListFileService`，91） |
| `OpenList.submitOffline` | 179 | 179（待拆） |
| `DownloadService.java` | 2769 行 | 3008 行（闸门链显式化换来可读性） |
| `DownloadService.downloadAniLocked` | 508 | 15 |
| `DownloadService` 闸门判定 | 内联在 `downloadAniLocked` | `passGates` 43 + 13 个 `*Gate` |
| `DownloadService.notification` | 99 | 27 |
| `RssTask.java` | 2405 行 | 2402 行 |
| `RssTask.getJobStatus` | 235 | 94 |
| `RssTask.download` | 252 | 252（已加 `<= 260` 守卫） |
| `RssTask.buildTaskItems` | 197 | 181 |
| `SubtitleMatchView.vue` | 821 行 | 57 行 |
| `DashboardView.vue` | 694 行 | 89 行 |
| `TaskManagerView.vue` | 1280 行 | 459 行 |
| 测试 | 1006 通过 / 0 失败（开工前记录） | 1022 通过 / 0 失败 / 1 跳过，E2E 6 通过 |

已建成的模块：`download/openlist/policy`（纯策略）、
`download/openlist/runtime`（下载路径解析、取消信号、运行态视图、
`OpenListRuntimeState` 静态表收编）、`download/openlist/residual`
（残留扫描与清理：`OpenListResidualService` + `OpenListResidualPort` + 6 个 DTO）、
`download/openlist/files`（归位链路：`OpenListFileService` + `OpenListFilePort` +
`EpisodeScanResult`）、`task/RssDownloadPort(Provider)`、`task/RssSchedulerPolicy`、
`task/RssJobStatusProjection`，前端 `view/home/task-manager/*`、
`view/home/subtitle-match/*`、`view/home/dashboard/*`。

## 待拆石山（按当前行数）

- `OpenList.java` 4145 行：`awaitAndFinalize`(205)、`submitOffline`(179)、
  `handleDuplicateOfflineTask`(94)；`relocate` / `wait` 主流程、其余静态表
  （锁、等待快照、活动临时目录、取消集合）与两个线程池仍在宿主。
- `DownloadService.java` 3008 行：`download`(119)、`getDownloadPath`(117)。
- `RssTask.java` 2402 行：`download`(252)、`buildTaskItems`(181)、
  `submitManualRefresh`(118)。
- 前端：`TaskManagerView.vue` 459 行。

## 依赖边界（已由测试锁定）

1. `download/openlist/**` 不得引用 `ani.rss.task.*`、`ani.rss.service.*`、
   `SpringUtil`（`OpenListPolicyBoundaryTest`）。
2. 核心调度/下载路径不得出现 `SpringUtil`；`downloadAniLocked <= 60`、
   `getJobStatus <= 200`、`awaitAndFinalize <= 210`、
   `handleDuplicateOfflineTask <= 110`、`scanTempDirResiduals <= 130`、
   `RssTask.download <= 260`、`buildTaskItems <= 190`、`submitOffline <= 190`、
   `getDownloadPath <= 130`、`passGates <= 230`、`notification <= 110`
   （`CorePathBoundaryTest`；`passGates` / `notification` 兼由
   `DownloadServiceStructureTest` 锁顺序与上限）。
3. 策略层（`download/openlist/policy`）只做纯判定：无实例状态、无网络、
   `java.net` 与 `java.util.concurrent` 都不允许。
4. 核心路径的重构不得新增 `SpringUtil.getBean`；现存 16 处（`AniUtil` 3、
   通知层 5、`TorrentUtil` 2、`ConfigUtil`/`CronConfig`/`TaskService`/
   `DoctorController`/`ConfigController`/`BgmUtil` 各 1）按 Phase 3 统一换成注入，
   `RssJobController`、`RssTask`、`DownloadService`、`download/**` 已清零。

## 危险操作（重构不得改语义）

- 删除/移动/归位/临时目录清理：扫描侧只读、清理侧只写，两者必须分离。
- 「查询失败」不等于「确认不存在」：`UNVERIFIABLE` 不得合并进 `NOT_FOUND`。
- 「存疑」（等待超时、任务 Failed/Error/Canceled、10008 去重死锁、读失败/限流）
  必须保留 pending、计划快照与网盘文件，不写失败队列、不清记录。
- 在途 hash、多槽临时目录保护集合、冷却表（磁力/列举/归位）在迁移前后一致。
- 10008 分支优先于文件兜底，顺序不得调换；`delete=true` 的收尾不得远程删除
  复用来的任务。
- 备用 RSS 占位的删除只在主 RSS 提交成功之后执行，闸门链内只登记待删。

## 回滚口径

- 方法拆分与目录迁移分批进行，每批可独立 `git revert` 或直接丢弃工作区改动。
- 行为保持靠两件事证明：① 定向测试（`OpenList*Test`、`DownloadJourneyE2ETest`、
  洗版/归位/残留相关测试、`DownloadServiceStructureTest`、`CorePathBoundaryTest`）；
  ② 规范化语句比对——去注释与空白后逐条比对旧方法与新方法，确认没有语句丢失、
  没有顺序变化。
- 每批结束必须跑 `mvn -o -pl ani-rss-application -am test -Dexec.skip=true`
  （同一轮 reactor 会顺带构建前端），记录测试数与耗时。

## 阶段状态

- Phase 0 基线：本文（2026-10-21 重新校准口径）。
- Phase 1 纯策略：已完成（`OpenListPolicy` + 边界测试）。
- Phase 2 编排层：进行中。`DownloadService` 闸门链已显式化（`passGates` 43 行 +
  13 个 `*Gate`，顺序由 `DownloadServiceStructureTest` 锁定）；`RssTask` 主干已收敛；
  `OpenList` 目录级拆分已落地 `policy/`、`runtime/`、`residual/`、`files/`，
  `offline/`（提交、等待、最终化）未开始。
- Phase 3 状态实例化：进行中。`OpenListRuntimeState` 已收编在途 hash、
  当前 info hash 与两张磁力冷却表；`DOWNLOAD_LOCKS`、`offlineWaitSnapshot`、
  `ACTIVE_TEMP_DIRS_BY_HASH`、`CANCEL_REQUESTED_HASHES`、两个线程池与
  `RssJobState` 仍为静态，剩余 16 处 `SpringUtil.getBean` 未换注入。
- Phase 4 前端：第二轮完成（`TaskManagerView.vue` 1280 → 459，
  `SubtitleMatchView.vue` 821 → 57，`DashboardView.vue` 694 → 89）。
