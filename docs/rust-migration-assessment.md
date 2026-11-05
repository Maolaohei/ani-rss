# ani-rss 迁移 Rust 可行性评估

> 评估对象：`D:\UGit\ani-rss` @ `5929e787`（v3.4.39 之后）
> 结论先行：**技术上完全可行，工程上不建议整体重写。** 若动机是"内存/镜像体积/免 JDK/ARM32"→ 先用 jlink/AppCDS 吃掉 60% 收益，再把 magnet 与 OpenList 扫描换成 Rust 侧车；若动机是"栈统一"→ 走绞杀者，预算 12–18 人月，第一年只追平不加功能。

---

## 1. 现状盘点（实测）

| 项 | 数值 |
|---|---|
| Java 文件 | 368 |
| Java 总行 / 非注释非空行 | 78,851 / **38,455** |
| 测试文件 / 测试行 | 119 / **22,471** |
| 最大单文件 | `download/OpenList.java` 4,568 行（3,351 代码行，203 方法） |
| 次大 | `service/DownloadService.java` 3,011 行（89 方法）、`task/RssTask.java` 2,477 行 |
| 包分布（代码行） | util 12,670 / download 12,136 / service 9,590 / controller 7,207 / entity 5,198 / task 3,236 / notification 2,291 |
| HTTP 端点 | 114 个 Mapping，32 个控制器 |
| 定时任务 | 3 个 `@Scheduled`（0 点备份 / 1 点 tracker 更新 / 6 点自更新） |
| 通知渠道 | 17 个（Bark/钉钉/飞书/Gotify/Ntfy/PushDeer/ServerChan/Telegram/企微/Mail/WebHook/Emby/Shell…） |
| 下载后端 | qBittorrent、Transmission、Aria2、OpenList、115 离线、magnet(jlibtorrent JNI) |
| 历史 | 1,032 commits，53 个版本，UPDATE.md 1,845 行 |
| 产物 | fat jar **83 MB**（含 4 平台 jlibtorrent + sqlite native），Docker 基于 temurin-21-jre-alpine，`-Xmx512m` |
| 前端 | Vue3 + Vite，`dist` 3.1 MB（迁移后原样静态托管，零改动） |

**关键判断**：这个仓库的价值不在架构，而在 53 个版本积累的行为细节。代码注释里全是战争故事（frostwire 仓库"假 200"导致传递依赖丢失、`IgnoreUnrecognizedVMOptions` 必须前置、`%SystemDrive%` 脏资源排除、不能给常驻服务加 `TieredStopAtLevel=1`）。重写等于把这份"坑位表"从零再踩一遍——**这是主要成本项，不是打字量**。

## 2. 逐模块难度与工作量

工作量口径：熟悉本项目的资深 Rust+Java 开发者，含单测，不含从零学业务规则。

| 模块 | 代码行 | Rust pd | 风险 | 说明 |
|---|---|---|---|---|
| 实体/DTO/序列化 | 5,198 | 8–12 | 中 | Gson 语义必须逐字节对齐（null 省略、DateAdapter、@SerializedName、Integer/长整型） |
| Web/控制器/鉴权 | 7,207 | 18–25 | 中 | axum + utoipa；`AuthAspect` 的切面语义要显式化为中间件 |
| 配置/日志/备份 | ~1,600 | 10–14 | **高** | config.json 必须与老版本双向兼容，atomic write、滚动备份语义 |
| SQLite 层 | — | 6–9 | 中 | `database.db` schema 必须保持可升级可回退 |
| RSS 核心（抓取/去重/偏移/合集记账/洗版/命名） | ~10,000 | 45–60 | **高** | `RssTask` 的 generation/pending-manual/cancel 状态机最易走样 |
| qB / TR / Aria2 | ~4,000 | 18–24 | 中 | 纯 HTTP/JSON-RPC，机械活 |
| OpenList 全家桶 | ~8,500 | 50–65 | **极高** | 上传/绕行/残留回扫/临时目录清理，业务规则最密 |
| 磁力/种子 | ~1,200 | 8–12 | 中 | `librqbit`（纯 Rust）替代 jlibtorrent，反而解掉 native 依赖 |
| 刮削（Mikan/BGM/TMDB/花园/AniBT/NFO/ICS） | ~6,000 | 25–32 | 高 | jsoup 选择器含 `:contains` 等，`scraper` 需自行补 |
| 字幕匹配（Assrt/季号解析） | ~3,000 | 12–16 | 高 | 大量 lookaround 正则 |
| 通知 17 渠道 | ~2,300 | 6–9 | 低 | 都是 HTTP 模板，模板仅 2 个内置、不支持用户自定义，迁移无兼容问题 |
| MCP（spring-ai → rmcp） | ~1,000 | 5–7 | 中 | 工具 schema 用 schemars 重建 |
| 构建/交叉编译/自更新/CI | ~1,500 | 12–16 | 高 | launch4j/exe/dmg 全部作废，自更新要改成"下载二进制+替换+重启" |
| 测试移植 | 22,471 | 40–55 | 高 | 只能重写，不能翻译 |
| 影子双跑 + 灰度收口 | — | 20–30 | 高 | 用 `.rss-samples` 做录放对比 |
| **合计** | 38,455 | **283–391 pd ≈ 13–18 人月** | | 到"功能对齐、可上线" |

再叠加"敢替换"的长尾回归（用户环境千奇百怪：群晖 ARM、Windows 长路径、GBK 文件名、IPv6-only NAS、自定义 tracker）：**+4–6 人月**。

**日历估算**：2 人 → 8–11 个月；3 人 → 6–7 个月（含协调损耗）。

## 3. Rust 生态映射（含缺口）

| 现依赖 | Rust 对应 | 结论 |
|---|---|---|
| Spring Boot 4.1 / Spring MVC | axum 0.8 或 actix-web | 够用，但要自己搭过滤器/异常映射 |
| Gson | serde / serde_json | 需手工对齐 null 与日期语义 |
| hutool-http | reqwest + rustls | 代理/重试要重写；**rustls 默认不读系统信任库**，现有 cacerts 裁剪逻辑需重做 |
| hutool-db + sqlite-jdbc | sqlx 或 rusqlite | 够用 |
| jsoup | scraper / html5ever | `:contains`、容错解析差异需补 |
| ical4j / commonmark | `ical` / `pulldown-cmark` | 够用 |
| jakarta.mail | lettre | 够用 |
| Thymeleaf（2 个内置模板） | minijinja / askama | 无用户自定义模板，风险低 |
| springdoc | utoipa | 114 端点 schema 是体力活 |
| spring-ai MCP server | `rmcp`（官方） | 够用 |
| jlibtorrent（JNI，4 平台 native） | `librqbit` | **净收益**：纯 Rust，ARM32/musl 不再缺库 |
| TinyPinyin | `pinyin` | 够用 |
| **Java 正则（lookaround/backref）** | `regex` **不支持** | **最大技术坑**，见 §4.1 |

## 4. 四个真实风险点

### 4.1 正则：32 处 lookaround 落在最敏感的路径上
`RenameUtil` 的巨型 alternation、`BaseDownload` 的集数识别、`AssrtSubtitleProvider` 全用了 `(?<!...)`、`(?!...)`、`(?=...)`。
Rust `regex` 是有限自动机，不支持这两类构造。可选：
1. `fancy-regex`（回溯引擎）——能编译，但病态输入下可能慢 10–100 倍，而这段代码正好是"每集每文件都跑一遍"的热路径；
2. 改写成手写状态机/分词器——最正确，但要为每条规则建黄金用例，工作量翻倍。

**必须先做正则审计与逐条改写方案，再谈周期。**

### 4.2 并发状态机不能直译
`RssTask` 有 global lock、`generation`、`PendingManual`、`requestCancel`/`cancelItem`、单订阅 5 分钟超时、90 分钟兜底、`shutdownAndAwaitPool`。
Java 侧是"AtomicBoolean + interrupt + 线程池"；tokio 是 "cancel-on-drop + JoinHandle::abort"，语义不同。直译会得到表面跑通、边界条件下重复下载/漏下载的实现。**这部分要重新设计，而不是翻译。**

### 4.3 用户数据的向后兼容
`config.json`、`database.db`、`ani-rss/` 目录布局、日志与备份命名，一旦不一致：老用户升级即炸，且无法回退到 Java 版。必须做双向兼容 + 版本化迁移，这也是最容易低估的一块。

### 4.4 部署产物全链路重做
launch4j 的 exe、macOS 打包脚本、三平台自更新（`WindowsUpdate/LinuxUpdate/MacUpdate`）、多架构 Docker（含 arm32v7、openj9 变体）全部作废。交叉编译 musl/arm64 + 自更新原子替换 + 签名，是 2–3 周的独立工程。

## 5. 收益量化（诚实版）

| 指标 | 现在（JVM） | Rust | 实际意义 |
|---|---|---|---|
| 常驻内存 RSS | 180–350 MB | 20–50 MB | **真实收益**，512MB NAS 上是刚需 |
| 镜像体积 | ~190–230 MB | 25–40 MB | 真实收益（拉取/存储） |
| 启动时间 | 2–5 s | <100 ms | 对 7×24 常驻服务几乎无意义 |
| Windows 部署 | 需用户装 JDK17 | 单 exe 免依赖 | **对普通用户最大收益** |
| ARM32 / musl | jlibtorrent 缺库，磁力不可用 | 可用 | 真实收益 |
| CPU 吞吐 | 基准 | 快 2–5× | 收益有限：负载是 IO 密集（HTTP 轮询、上传、文件移动），CPU 峰值只在解析/刮削瞬间 |

**结论**：性能不是迁移理由；**内存、体积、免 JDK、ARM 兼容**才是。

## 6. 三条路线与建议

### 路线 A：全量重写（不推荐直接上）
12–18 人月到可上线，16–22 人月到敢替换。期间 Java 版仍在修 bug，双线维护。只有"团队已决定全面转 Rust 且能给 6 个月以上预算"才成立。

### 路线 B：先榨干 JVM（2 周 PoC，推荐第一步）
- `jlink` 自定义运行时（只留 web/sqlite/security 模块）→ 基础镜像 60 MB 起；
- AppCDS 缩短启动，`-XX:MaxRAMPercentage` 替代固定 512m；
- 预计镜像 ~110 MB、启动 ~1.5 s、内存不变。
**用两周验证能否吃掉 60% 收益，再决定要不要动 13 人月。**

### 路线 C：绞杀者（若要 Rust，选这条）
按"纯函数 → 侧车 → Web"顺序切，每步都可独立回滚：

| 里程碑 | 内容 | 周期 | 验收 |
|---|---|---|---|
| M0 | **对比夹具**：用 `.rss-samples` + 真实站点录 200+ 用例，定义 golden 输出；把 Java 行为写成规格 | 2 周 | 规格可执行，Java 侧先跑通 |
| M1 | `ani-core` crate：命名/集数/偏移/合集记账/去重（纯函数，零 IO） | 6–8 周 | 与 Java 双跑 10 万条标题零差异 |
| M2 | Rust 侧车替换 magnet（librqbit）+ OpenList 残留扫描 | 6–8 周 | ARM32/musl 磁力可用；JNI 依赖删除 |
| M3 | axum 新 Web 层，路由级灰度，Java 兜底旧功能 | 8–10 周 | 前端零改动，接口逐字节一致 |
| M4 | 清退 Java，收尾打包/自更新 | 4 周 | 全平台产物 + 回退通道 |

### 建议
1. **不要大爆炸重写。** 先做路线 B 的 2 周 PoC；
2. 若 B 满足需求 → 结束，收益/成本比最高；
3. 若必须 Rust → 走 C，M1 是性价比最高的一步（立刻降低 Java 侧复杂度，且不改变部署）；
4. 无论哪条路，**先做正则审计**（4.1），它的结论会改变整个估算。

## 7. 不要做的事
- 不要直译 `RssTask` 的线程模型；用 tokio 的取消语义重新建模并单独写并发测试。
- 不要依赖 serde 默认行为"大概对齐"Gson；用真实 config.json + 真实 API 响应做字节级 diff。
- 不要在 Rust 里重建 hutool 全家桶；`util/` 12,670 行里真正需要迁的是业务规则，不是工具方法。
- 不要为了"纯 Rust"放弃 OpenList 的既有边界处理（那是 8,500 行实战规则的沉淀）。
