# 测试规范（ani-rss）

## 规则

1. **永远不要在写完代码之后补单元测试。**
2. **强烈优先端到端测试作为唯一的测试机制。** 用它验证复杂功能是否真的工作；
   端到端测试结束时生成一个**可验证且可重复的工件**。
3. **如果必须孤立地测试一个系统，先写下它可能失败的所有方式，再写代码。**
4. **冗余测试有害。**
5. **变更检测测试有害。**（断言文案、格式、常量、输出形态"没有变"——**删掉，不要替换**。）
6. **没有真正的行为测试缺口时，不要为错误修复创建回归测试。**

判据：**"删掉它，会不会有某种失败变得无法被发现？"** 答"不会" ⇒ 删。

本文件说明在这份代码库里这几条怎么落地。

---

## 一、端到端测试（首选机制）

### 位置与运行

| 项 | 说明 |
|---|---|
| 用例 | `ani-rss-application/src/test/java/ani/rss/e2e/`，类带 `@Tag("e2e")` |
| 运行 | `mvn -o -pl ani-rss-application test -Dexec.skip=true`（本机 Windows 需要跳过 `generate-resources`，它依赖 bash + curl） |
| 只跑 E2E | `mvn -o -pl ani-rss-application test -Dexec.skip=true -Dtest=*E2ETest -DfailIfNoSpecifiedTests=false` |
| 工件 | `ani-rss-application/target/e2e-artifact/`（`report.json` / `report.md` / `inputs/`），测试结束会打印绝对路径 |

> `-Dtest=` 过滤时必须用 **`-DfailIfNoSpecifiedTests=false`**（不是 `-DfailIfNoTests=false`）：
> Surefire 3.x 在"没有任何用例匹配"时抛的是 `No tests were executed!`，
> 由 `failIfNoSpecifiedTests` 控制，`failIfNoTests` 管不到它。
> 于是类名/通配符写错时会以一个**和"测试真的失败"极像**的 BUILD FAILURE 收场。
> 另外，按类名跑 e2e 时输出里会有**两行** `Tests run`：
> 默认执行段报 `Tests run: 0`（它被 `excludedGroups=e2e` 挡掉了，属正常），
> `e2e-tests` 段才报真实数字。

surefire 把测试分成两段执行（见 `ani-rss-application/pom.xml`）：

1. **默认执行** —— 普通单测，单个 JVM 复用，`excludedGroups=e2e`；
2. **`e2e-tests` 执行** —— `groups=e2e`、`reuseForks=false`，每个端到端类一个干净 JVM。

为什么必须分开：端到端测试会把整个应用跑起来（Spring 上下文、真实 Tomcat、后台任务线程），
还会设置 `-DCONFIG` 指向临时目录、把出网请求指向假公网。这些静态状态与单测共享 JVM 时
会互相污染（谁先跑谁说了算，结论不确定）。分开后单测保持快，端到端结论可复现。

### 被替换的只有「应用之外的世界」

端到端测试里**没有任何一处 mock 应用自己的类**。只有两个替身，且都替换在网络边界：

| 替身 | 替换什么 | 关键点 |
|---|---|---|
| `FakeOriginServer` | RSS 源 / 种子下载站（外网） | 见下 |
| `FakeOpenListServer` | 网盘（OpenList/AList + 115 云下载） | 真实 API 形状：`login`/`fs/list`/`fs/mkdir`/`fs/batch_rename`/`fs/move`/`fs/remove`/`add_offline_download`/`task/*` |

其它一切都是真的：Spring 上下文、Tomcat、鉴权、RSS 解析与命名、下载决策、
目录归位与清理、并发锁、订阅状态落盘、配置读写。

### 为什么假公网用 `203.0.113.10`

应用对 RSS 地址做了 SSRF 校验（`URLUtils.verify`）：回环、通配、链路本地、内网地址一律拒绝，
DNS 解析失败也拒绝。因此**本地起的服务不可能被直接当成"外网 RSS 源"**。

做法：

1. 用 RFC 5737 文档保留段里的公网 IP 字面量 `203.0.113.10` 当假域名 ——
   `InetAddress.getByName` 是字面量解析，**不需要 DNS**（离线/CI 同样成立），
   又不是内网，所以能通过 SSRF 校验；
2. 把这个 host 写进**应用自己的代理白名单**（`proxy=true` + `proxyList=203.0.113.10`）；
3. 于是应用用**真实的 HTTP 客户端**把请求发给本机那个替身服务（请求行是 absolute-form）。

结果：RSS 抓取、种子下载、失败重试走的都是生产代码路径，只有"对端是谁"被替换，
不需要 mock 框架、不碰 socket 层。其它 host（例如本机 OpenList）不在白名单里，仍然直连。

### 工件要求

`E2EArtifact` 落三样东西，缺一不可：

- `report.json` —— 机器可读：输入哈希（RSS/种子/配置的 SHA-256）、每个场景的结论与证据；
- `report.md` —— 人可读：同一份内容 + 最终文件树 + 请求明细；
- `inputs/` —— 本次真正喂给应用的原始输入（RSS XML、种子、脱敏配置、订阅落盘文件）。

判定"可验证、可重复"的标准：**别人拿 `inputs/` 里的东西重跑，应得到同样的结论**。
因此断言必须落在"外部可观察的终态"上——网盘最终文件、提交记录、
`ani.v2.json` 落盘内容、假公网收到的请求——而不是内部调用次数。

CI（`.github/workflows/build-test.yml`）会跑测试并上传 `target/e2e-artifact`。

---

## 二、孤立测试（例外，需要理由）

只有当某个系统**无法**通过端到端观察时才允许孤立测试，并且**必须在文件头先写清
"它可能失败的所有方式"**，再写代码。仓库里已有的合规示例：

- `RenameUtilTest` / `RenameAccuracyTest` / `RealTitleTest` —— 命名解析是纯函数，
  输入空间是"各站点真实标题形态"，端到端无法穷举；文件头写明数据来源与覆盖目标。
- `OpenListRateLimitTest` / `OpenListApiCacheTest` —— 限流与缓存是失败模式密集的逻辑，
  需要合成并发/超时/畸形响应，端到端只能覆盖成功路径。
- `DeleteGuardTest` —— 递归删除的安全闸门，必须先枚举"什么情况绝不能删"。

写新测试时的顺序：**先列失败模式 → 再写用例 → 最后才写实现**（如果是测试驱动新功能）。
没有失败模式清单的孤立测试，要么补上清单，要么把它改成端到端场景。

---

## 三、历史包袱与迁移方向

`ani-rss-application/src/test` 里仍有约 90 个单测类、约 1 万行，绝大多数是
"每个 bug 修完补一条回归"。它们的价值是**失败模式清单**，代价是维护面大。
迁移方向（渐进，不要求一次性完成）：

1. 用户可见的链路（订阅、下载、归位、洗版、媒体库、分享）→ 迁到 `e2e/` 场景；
2. 纯函数与解析表（命名、集数、模板）→ 保留，但**合并同类文件**，避免一个函数一个文件；
3. 只剩"内部实现细节"断言、端到端已覆盖的 → 删除。

新增功能时：**先加端到端场景**；只有在端到端确实观察不到时才写孤立测试，并附失败模式清单。

### 已执行的第一轮清理（2026-09-25，净减 28 个用例）

| 删除对象 | 理由 |
|---|---|
| `DoctorOutcomeEvidenceTest`(3) | 断言自检页的**中文字符串子串** → 变更检测 |
| `OpenListJudgementPathTest`(9) | 为**只在 1 处使用**的守卫写"机制自证"，测的是测试脚手架 |
| `LoginPasswordCheckTest`(9) | 纯函数逐分支穷举；登录与保存行为已由 e2e 覆盖 |
| `OpenListPlanEarlyCheckScheduleTest`(6) | 断言实现常量（`budget == 3`）；该行为缺口当初正是行为测试抓到的 |
| `OpenListPlanWorkflowTest` 的 busy 顺序守卫(1) | 该顺序已由 e2e `E2E-1` 覆盖 → 无真正行为缺口 |
| `OpenListWorkflowSimulationTest` 的 `taskDeleteDelayMs` 旋钮 | 只服务于上面那条被删的用例 |

**保留**（属于"必须孤立测试"，失败模式已写在文件头）：`OpenListOutcomePolicyTest`、
`TorrentUtilPendingLifecycleTest` —— 跨时间确认与 pending 状态机，端到端无法在秒级完成。

基线：**999 → 971**（另 e2e 4）。

### 已执行的第二、三轮清理（2026-09-25，净减 7 个用例 + 2 个孤儿资源）

第二轮的判据是「**类级**：这个类的存在目的就是钉死文案/格式 ⇒ 删」。第三轮把判据下沉到
**方法级**：类里有真行为用例、但个别方法只是"证明重构没改行为"，就只删那个方法。

| 删除对象 | 理由 |
|---|---|
| `DoctorLocalStateCacheTest`(1) | 自称验证"每个计数器都要露面"，实际只 `contains("增量追加 1 次")` —— **它没验证自己声称的不变量**，纯粹钉死一个中文字面量 |
| `DoctorUpstreamSuggestionTest`(2) | 6 个断言全是 `contains("DNS")`/`contains("curl -4")` 文案钉死 |
| `VcbAnalyzeTest`(1) | **零断言**，只往 stdout 打统计 —— 一个永远不会失败的测试不是测试 |
| `TvFailClassifyTest`(1) | 不引用任何生产类，断言只检查它自己的分类是否漏项 |
| `HttpRequestPlusNormalizeTest` 的 2 个方法 | 「与重构前的内联正则逐条对齐」= 变更检测（旧实现不是规格）；「钉住 quirk 防止误以为幂等」→ 注释写清即可 |
| 资源 `vcb-titles.json`(138K) / `tv-fail-titles.txt`(22K) | 随上述两个类一起成为孤儿 |

**顺带收口**：`DoctorController` 里三个"为测试而抽出的 package-private 纯函数"改回 `private`
—— 它们的用例已删，留着就是死面，而 javadoc 里那句"抽成 package-private 是为了能测"会变成假话。

**明确保留**（引用过线上事故，但守的是**通用契约**而非"一事一测"）：`AniLocksTest`（互斥矩阵）、
`OpenListRateLimitTest`（限流/预算）、`QuiescentWindowTest`（静默判定）、`DownloadPresenceDecisionTest`
（三态决策表）、`TaskServiceGenerationTest`（代际语义）、`LocalStateCacheTest`/`CacheUtilsTest`/
`TmdbUtilsCacheTest`/`MikanServiceCacheTest` 等（缓存语义与线程安全）、`TorrentUtilSaveTorrentTest`
（幂等 + 日志诚实）、`ConfigLocalStateInvalidationTest`（新增配置项忘了接失效逻辑）。

> 事故写在 javadoc 里**不构成**删除理由。判别方法只有一条：**这个断言去掉之后，
> 有没有某种失败会变得无法被发现？** 上表删掉的都是"答不会"，保留下来的都是"答会"。

基线：**971 → 964**（清理本身）；加上本轮新增的「归位对账定点列举」用例 1 个 → **965**（另 e2e 4）。

## 四、常见坑

- 本机/CI 必须用 `-Dexec.skip=true`：`generate-resources` 会跑 `bash ./generate-resources.sh`
  （需要 bash、curl 与网络），在 Windows 上通常会失败。
- 端到端测试不要用 `127.0.0.1` 当外网地址（SSRF 校验会拒绝）。
- 端到端断言不要依赖"内部调用次数"或"某函数被调用过"——那是实现细节，重构即坏。
- 加了 `@Tag("e2e")` 的类**不要**放进默认执行段，否则会与单测共享 JVM。
