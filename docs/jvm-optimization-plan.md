# 榨干 JVM：两周方案（含实测数据）

> 目标：在**不改一行业务代码**的前提下，把镜像体积、常驻内存、启动时间压到接近上限，
> 用数据回答"到底还需不需要迁移 Rust"。
> 实测环境：Windows 11 / JDK 21.0.12 / `build/ani-rss-3.4.38-release.jar`（83 MB）/ 空配置启动。

## 0. 实测基线（不是估算）

| 配置 | 启动 | 常驻 WS | Private | 线程 |
|---|---|---|---|---|
| G1 + `-Xmx512m`（现状） | 6.50 s | 225.9 MB | 289.4 MB | 60 |
| SerialGC + `-Xmx256m` | 5.55 s | 206.1 MB | 221.9 MB | 37 |
| **jlink 运行时(52 MB) + SerialGC + `-Xmx256m`** | **4.03 s** | **206.7 MB** | **215.0 MB** | 37 |

NMT 拆解（G1，空载，committed 257 MB）：

```
Java Heap   committed  64 MB (reserved 512)
Class/Meta  committed  57 MB (12,593 classes)
Code cache  committed  25 MB
GC 结构     committed  66 MB   <-- SerialGC 就是省在这里
Symbol      committed  19 MB
malloc      ~62 MB
```

**关键结论：堆不是瓶颈，G1 的辅助结构 + 元空间 + 代码缓存才是。** 所以 `-Xmx` 从 512 调到 256 只省一点，换 GC 才省一大截。

## 1. 体积：83 MB 的 jar 是怎么来的

| 依赖 | 大小 | 可否裁 |
|---|---|---|
| sqlite-jdbc（20 个平台原生库） | 11.9 MB | 只留 1 个平台 → **省 ~10.5 MB** |
| jlibtorrent ×4 平台 | 17.1 MB | 每镜像只需 1 个 → **省 ~13 MB** |
| spring-ai + mcp + jtokkit + reactor + micrometer | 7.6 MB | MCP 默认关闭，可拆 → 省 7.6 MB |
| springdoc + swagger-ui | 2.2 MB | Swagger 默认关闭，可拆 → 省 2.2 MB |
| Jackson 2 **与** Jackson 3 并存 | 5.1 MB | 2.x 来自 MCP 栈，拆 MCP 后可能一起消失 |
| aspectjweaver + javassist | 2.8 MB | `@AuthAspect` 必需，留 |
| jre-min（jlink，含 desktop + zh/en locale） | 52 MB | 对照：现用 temurin-21-jre-alpine ≈ 135 MB |

> 注：`java.desktop` **不能删**。实测删掉后启动即 `NoClassDefFoundError: java/beans/PropertyEditorSupport`（Spring 的 bean 内省依赖 `java.beans`）。无桌面运行时 40 MB 不可用。

## 2. 六个可落地方案（按 ROI 排序）

### A. 平台原生库裁剪（1 天，-23.5 MB）
- `jlibtorrent` 本来就是 4 个独立 artifact：用 Maven profile（`-Pnative-linux-x86_64` 等）按 `TARGETARCH` 只引一个。
- `sqlite-jdbc` 是单 jar 内嵌全平台：构建期抽出目标平台的 `.so/.dll`，启动加
  `-Dorg.sqlite.lib.path=/usr/app/native -Dorg.sqlite.lib.name=libsqlitejdbc.so`，jar 内 `org/sqlite/native/**` 全删。
- 验收：多架构镜像分别 build，`unzip -l` 确认只含本平台 native。

### B. jlink 自定义运行时（2 天，-83 MB，启动 -2.5 s）
已完成模块推导与实机验证，命令：

```bash
jlink --add-modules java.base,java.compiler,java.desktop,java.instrument,java.logging,\
java.management,java.naming,java.net.http,java.prefs,java.scripting,java.security.jgss,\
java.security.sasl,java.sql,java.xml,jdk.charsets,jdk.crypto.ec,jdk.localedata,\
jdk.management,jdk.naming.dns,jdk.unsupported,jdk.zipfs \
  --include-locales=zh,en --strip-debug --no-header-files --no-man-pages \
  --compress=zip-6 --output /opt/jre
```

- 52 MB，实测启动成功、配置与日志写入正常。
- Docker：`eclipse-temurin:21-jdk-alpine` 里 jlink → `COPY` 到 `alpine:3.20`（或 distroless），基础层 8 MB。
- 注意：`--compress=2` 在 21 已废弃，用 `zip-6`。
- **待验证**：sqlite JNI（空配置下 `database.db` 懒创建，须用一个写库用例确认）、jlibtorrent（加一个 magnet 订阅确认加载）。

### C. JVM 档位（0.5 天，RSS -20 MB / Private -74 MB）
在镜像里预置三档，用 `JAVA_PROFILE` 选：

| 档 | 参数 | 适用 |
|---|---|---|
| `lowmem` | `-Xmx256m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -XX:MaxDirectMemorySize=64m` | ≤2 核 / ≤512 MB NAS |
| `default` | `-Xmx512m -XX:+UseG1GC`（现状） | 常规 |
| `perf` | `-Xmx1g -XX:+UseG1GC -XX:MaxRAMPercentage=75` | 多人/大批量 |

- SerialGC 下 `-XX:+UseStringDeduplication` 是空操作，去掉。
- Tomcat `threads.max: 100 → 40`（`application.yaml`），线程栈按需提交，省的不多但限流更稳。
- 并发冒烟：5 个订阅同时刷新，观察 SerialGC 是否出现长停顿（本应用是 IO 密集，风险低）。

### D. 可选功能拆分（2–3 天，-9.8 MB，且降元空间）
- MCP（7.6 MB：jtokkit 3.2 + reactor 1.9 + micrometer 0.9）默认 `MCP_ENABLED=false`，但类仍在包里。
  做法：给 `AniMcpTools` 加 `@ConditionalOnClass`/`@ConditionalOnProperty`，Maven profile 排除 starter，产出 `ani-rss-mcp.jar` 由 `PropertiesLauncher` 的 `loader.path` 加载。
- Swagger（2.2 MB）同理，`SwaggerConfig` 加 `@ConditionalOnProperty(swagger.enabled)`。
- **优先级低**：体积收益小于 A/B，但能顺带减少启动扫描。时间紧可以只做 MCP。

### E. Windows/macOS 免 JDK 分发（1–2 天，用户体感最大）
- 现状：launch4j 的 exe 要求用户自装 JDK 17，这是普通用户最大的门槛。
- 方案：随包附带 `jre/`（jlink 产物 52 MB），exe 用相对路径 `jre\bin\javaw.exe`；macOS 把 runtime 塞进 `.app/Contents/runtime`。
- 体积 83+52 ≈ 135 MB 的 zip，但**零依赖、双击即用**；比"装 JDK"友好一个数量级。
- 自更新只替换 jar，不动 runtime，`WindowsUpdate/LinuxUpdate/MacUpdate` 逻辑无需改。

### F. CDS / AOT（1 天，启动再 -20~30%，可选）
- `-XX:ArchiveClassesAtExit=app.jsa` 训练 + `-XX:SharedArchiveFile` 使用；JDK 25 起有 JEP 514 AOT cache（`-XX:AOTCache`），效果更好。
- **坑**：Windows 上 `kill` 是硬终止，归档不会写出；必须在 Linux 容器里用 `docker stop`（SIGTERM）优雅退出后取 `app.jsa`。
- 对 7×24 服务，启动从 4 s 到 3 s 没有业务价值，**除非同时上 JDK 25 的 compact object headers**（堆再省 5–10%）。建议只作为 Day 7 的时间盒实验。

### G. GraalVM native-image：不建议
- 阻碍：`sqlite-jdbc` JNI、`jlibtorrent` JNI、AWT/`java.beans`、`@AuthAspect` 的 AspectJ、hutool 反射、Spring AI、Thymeleaf 模板。
- 就算跑通，native-image **没有 JIT**，而本项目 CPU 峰值恰好在"每集每文件都跑"的正则匹配/命名解析上，会明显变慢。
- 结论：不投入。真要为内存付这笔账，Rust 比 native-image 更划算。

## 3. 两周排期

| 天 | 内容 | 产出 |
|---|---|---|
| D1 | 建立测量基线（脚本已就绪）：三档参数 × 冷/热启动 × 空载/50 订阅 | `bench.md` 数字表 |
| D2 | A：jlibtorrent 平台 profile + 多架构构建 | jar -13 MB |
| D3 | B：jlink 多阶段 Dockerfile，amd64/arm64/armv7 三架构 | 镜像可跑 |
| D4 | A2：sqlite 单平台抽取 + `org.sqlite.lib.path`；JNI 用例验证 | jar -10.5 MB |
| D5 | C：三档 JVM preset + 并发冒烟 | 参数矩阵 |
| D6 | E：Windows/macOS 自带运行时打包 | 免 JDK 包 |
| D7 | F（时间盒）：CDS / JDK 25 AOT + compact headers | 实验报告，成则合入 |
| D8 | 全量 e2e（`report.json`）+ 多架构镜像 + 老配置/老 DB 升级验证 | 回归结论 |
| D9 | 发布流水线、`lowmem` 标签、文档 | 可发布镜像 |
| D10 | 对比报告 + Go/No-Go 决策 | 决策纪要 |

## 4. 预期终局（按实测外推）

| 指标 | 现在 | 两周后 | 改善 |
|---|---|---|---|
| 镜像（未压缩） | ~226 MB | **~120 MB**（8 基础 + 52 runtime + 60 jar） | **-47%** |
| 镜像（拉取） | ~130 MB | ~65–75 MB | -45% |
| jar | 83 MB | 59.5 MB | -28% |
| 启动 | 6.5 s | 4.0 s（CDS 后 ~3.2 s） | -38% |
| 空载 WS | 226 MB | 206 MB | -9% |
| 空载 Private | 289 MB | 215 MB | **-26%** |
| Windows 部署 | 需装 JDK 17 | 双击即用 | 质变 |

> 诚实说明：206 MB 是**空配置**下限；50 个订阅 + 并发下载时堆会涨到接近 `-Xmx`。真机上按 `lowmem` 档估计 220–260 MB。

## 5. 决策门（D10 用）

| 判据 | 通过线 |
|---|---|
| 镜像 | ≤ 130 MB |
| 启动 | ≤ 5 s |
| 空载 WS | ≤ 210 MB |
| 功能 | e2e 全绿 + 老配置/老 DB 原地升级成功 |
| 多架构 | amd64 / arm64 / armv7 全部可跑（magnet 功能在 armv7 明确降级提示） |

- **全过** → 停止 Rust 重写，收益/成本比已经足够；把 12–18 人月省下来做功能。
- **内存仍不达标**（例如目标设备只有 256 MB）→ 只迁 Rust 侧车（magnet + OpenList 扫描），主程序保留。

## 6. 风险与验证清单

1. **TLS 信任库**：Dockerfile 手工裁剪过 cacerts（移除 eMudhra 根证书）。换成 jlink runtime 后必须重复这一步，否则那个 CVE 缓解措施丢失。
2. **`--enable-native-access=ALL-UNNAMED`**：JDK 24+ 的 JNI 警告/限制，jlink 运行时同样要带。
3. **`--add-opens java.base/java.net`** 等两个参数：hutool 的 HTTPS 代理逻辑依赖，别漏。
4. **alpine/musl**：jlink 必须在 musl 的 JDK 里做；用 glibc 的 runtime 配 alpine 会直接起不来。
5. **armv7**：Temurin 官方无 arm32 jmods，现有 `wushuo894/eclipse-temurin:arm32v7` 是自建镜像；jlink 方案要么自建 jmods，要么 armv7 继续用整包 JRE。
6. **sqlite 懒加载**：空配置下 `database.db` 不创建，必须用"新增订阅 → 重启 → 读回"验证 JNI 链路。
7. **GUI 托盘**：`--gui` 才走 AWT；Docker 无 GUI，但 `java.desktop` 因 Spring 内省必须保留。

---

# 落地记录（A + B + C 已实现）

## 改动清单

| 文件 | 改动 |
|---|---|
| `ani-rss-application/pom.xml` | 4 个 jlibtorrent 平台包从 `<dependencies>` 挪进 profile：默认 `native-all-platforms`（不传参数，行为不变），`-Dnative.platform=windows\|linux-x86_64\|linux-arm64\|macosx-arm64\|none` 只引目标平台 |
| `tools/slim_fat_jar.py` | 新增。裁 `BOOT-INF/lib/jlibtorrent-*` 平台包，把 sqlite-jdbc 的 20 个平台原生库裁到 1 个并抽到 jar 外（走 xerial 的 `org.sqlite.lib.path` / `org.sqlite.lib.name`）；不改原 jar |
| `docker/Dockerfile-jlink` | 新增。JDK 阶段先删 emSign 根证书再 `jlink`（21 模块 + zh/en locale，52 MB），运行时镜像只带 `/opt/jre`；支持 alpine(musl) 与 debian(glibc) 两种变体 |
| `docker/build-image.sh` | 新增。一条命令跑完 mvn（选平台）→ 精简 jar → buildx |
| `docker/run.sh` | 加 `JAVA_PROFILE=lowmem\|default\|perf` 三档预设、`JAVA_BIN`（指向 jlink 运行时）、`SQLITE_LIB_PATH` 外挂；SerialGC 下自动不加 `UseStringDeduplication` |
| `ani-rss-application/src/main/resources/application.yaml` | `server.tomcat.threads.max` 改为 `${TOMCAT_MAX_THREADS:100}`（默认值不变，lowmem 档由 run.sh 设 40） |

## 实测验证（本机，JDK 21.0.12，2026-11-05）

| 项 | 结果 |
|---|---|
| 默认构建（发布用，不带参数） | jar 84.66 MB，**仍含全部 4 个平台** jlibtorrent → 发布链路零变化 |
| `-Dnative.platform=linux-x86_64` | jar 71.79 MB，只剩 `jlibtorrent` + `jlibtorrent-linux-x86_64`（**-12.6 MB**） |
| 再跑 `slim_fat_jar.py --platform linux-x86_64` | jar 60.62 MB（**再 -11.2 MB**，累计 **-24.0 MB**）；未压缩字节 87.4 MB → 63.7 MB |
| sqlite 外挂原生库（决定性验证） | 把原生库从 jar 里**全部删掉**，只给 `-Dorg.sqlite.lib.path` + `-Dorg.sqlite.lib.name`：建表/插入/`select sum(x), sqlite_version()` 正常返回 `SUM=42 SQLITE=3.53.2`，`org.sqlite.tmpdir` 无解压残留 |
| jlink 运行时（Dockerfile 里那条命令原样） | 52 MB，`lib/security/cacerts` 存在（153 KB），模块清单无缺失 |
| 精简 jar + jlink 运行时 + `-Xmx256m -XX:+UseSerialGC` | 启动 **4.77 s**，`/` 返回 **HTTP 200**，配置与日志正常写入；日志里的 `Failed to select a proxy` 是空配置下 qBittorrent 地址为空的老噪音，与本次改动无关 |
| 三档预设 | `lowmem` → `-Xms32m -Xmx256m -XX:+UseSerialGC`、threads 40、无 StringDeduplication；`default` → Xmx512 + G1 + threads 100 + 有 dedup；`perf` → Xmx1g + threads 200 |

## 用法

```sh
# 镜像（musl，最小）：自动 mvn → 精简 jar → buildx --load
docker/build-image.sh                 # amd64
docker/build-image.sh arm64

# glibc 变体：磁力可用（jlibtorrent 只有 glibc 构建），代价 +~55 MB
LIBC=glibc docker/build-image.sh

# 只重打镜像、复用已有 jar
SKIP_MAVEN=1 docker/build-image.sh
```

**发布流程不要带 `-Dnative.platform`**：`package.sh` / `.github/workflows/build.yml` 保持原样，
产出的通用 jar 才同时支持 Windows / Linux / macOS 三端。

## 还没验证的（本机没有 docker，无法实跑）

1. `docker buildx build` 本身没跑过：镜像体积是按部件推算的 8（alpine）+ 52（jre）+ ~58（jar）= **约 120 MB**，必须在有 docker 的机器上实测一次。
2. musl 镜像里磁力功能应报可读的 `UnsatisfiedLinkError`（与改造前行为一致），未在容器内确认。
3. arm64 镜像没构建过（只验证了 jar 侧的平台选择逻辑）。
4. armv7 仍是 `Dockerfile-arm32v7` 的老路子：Temurin 无 arm32 jmods，jlink 用不了。
5. glibc 变体的 `gosu` → `su-exec` 软链只在 Dockerfile 里写了，未实跑（`gosu uid:gid` 与 `su-exec uid:gid` 参数形式一致）。

## 下一步（原方案 D / E / F）

- D 可选栈拆分（MCP 7.6 MB、Swagger 2.2 MB）——需要改 `@ConditionalOnClass` 与打包方式，收益小于 A+B。
- E Windows/macOS 自带 jlink 运行时免装 JDK——用户体感最大的一项，打包脚本工作量 1–2 天。
- F CDS/JDK25 AOT——只在有 Linux 容器时做（Windows 上硬 kill 写不出归档）。
