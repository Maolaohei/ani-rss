<div align="center">
<img alt="icon-512.png" height="80" src="ani-rss-ui/public/icon-512.png"/>
<h1 align="center" style="margin-top: 0">ANI-RSS</h1>
<p align="center">
<strong>基于 RSS 自动追番、订阅、下载、刮削、洗版</strong>
</p>
<p align="center">
<strong>本仓库为社区 Fork</strong>：在上游能力之上强化 OpenList/AList 离线链路与 RSS 任务管理
</p>

[上游文档](https://docs.wushuo.top/start)
|
[本仓库 Releases](https://github.com/maolaohei/ani-rss/releases)
|
[上游项目](https://github.com/wushuo894/ani-rss)
|
[问题反馈](https://github.com/maolaohei/ani-rss/issues)

[![GitHub](https://img.shields.io/badge/-GitHub-181717?logo=github)](https://github.com/maolaohei/ani-rss)
![GitHub License](https://img.shields.io/github/license/maolaohei/ani-rss)
[![GitHub release](https://img.shields.io/github/v/release/maolaohei/ani-rss?color=blue&label=download&sort=semver)](https://github.com/maolaohei/ani-rss/releases/latest)

</div>

## 本 Fork 说明

基于上游 [wushuo894/ani-rss](https://github.com/wushuo894/ani-rss) 维护，版本号会阶段性同步（当前基线 **3.1.73**）。  
不是简单跟版：在 **OpenList/AList 离线下载可靠性** 和 **可观测/可干预的任务调度** 上做了较多落地增强。

| 能力 | 上游 | 本 Fork |
| --- | --- | --- |
| 常规 RSS 追番 / 多下载器 | ✅ | ✅（保留并持续合入有用上游改动） |
| OpenList/AList 离线硬超时与坏任务清理 | 基础支持 | ✅ 以「离线超时」为上限强制失败并清理 |
| 离线提交后的分级轮询 / 防重复提交 | - / 较弱 | ✅ 分级探测 + 10008 冷却 |
| 本集文件完成判定 | 易受同季其它集干扰 | ✅ 按本集目标文件判定 |
| 残留任务 adopt / 启动回扫 / 手动扫描清理 | 有限 | ✅ 任务管理器可扫可清；启动清终态 |
| RSS 任务管理器（状态/取消/排队/抢先） | - | ✅ 首页入口 |
| 手动刷新与添加订阅统一调度 | 入口分散 | ✅ 统一走任务管理器可观察路径 |
| Bark 通知（含 Level / Volume） | 视上游版本 | ✅ |
| 自动检查更新 | 默认有 | ❌ Fork 默认禁用（避免误升上游包） |

更细的变更见 [UPDATE.md](UPDATE.md)。

### 任务管理器（本 Fork）

首页可打开 **任务管理器**，用于观察与干预全局 RSS 调度：

- 总览：运行中 / 空闲、消息、已处理时间
- 多槽位卡片（非真并行历史队列）：
  - RSS 运行中
  - RSS 待执行（手动刷新排队，最多 1 个）
  - OpenList 当前离线占用
  - OpenList 离线残留（**仅有残留 / 清理中 / 扫描异常时显示**；「无离线残留」不占卡片）
  - 上一轮已处理
- 操作：刷新、扫描残留、清理残留、单条取消、全部取消
- 设计边界：
  - 仍是 **全局单 RSS 调度**（最多 1 running + 1 pending）
  - 重启丢失内存调度态可接受；OpenList 残留靠回扫/扫描恢复
  - 取消 OpenList/AList 会清理远端离线占用；qB / Aria2 / Transmission **只停 RSS 推进**，不误删远端种子

### OpenList / AList 离线增强（本 Fork）

面向「种子提交成功后长时间无结果 / 100 分钟被清 / 卡在未完成任务」等真实问题：

1. **硬超时**：超过配置的离线超时 → 认定失败，清理 OpenList 与 ani 侧占用，避免坏任务堵住流程  
2. **分级轮询**：提交成功后拉长探测间隔，减少频繁打 API 与重复提交  
3. **完成判定**：以本集目标文件为准，避免同季其它集已存在导致误成功  
4. **10008 / 重复提交**：识别任务内错误并冷却  
5. **残留 adopt**：可复用未完成任务；启动回扫默认只清终态；手动可扫描/清理（保护当前等待 hash）

使用建议：下载工具选 OpenList/AList 时，按网盘与线路合理设置 **离线超时**；异常卡住时先看任务管理器 → 扫描残留 → 再清理残留。

## 快速开始

部署与基础用法请优先参考上游文档（配置项大体兼容）：

- [快速开始](https://docs.wushuo.top/start)
- [添加订阅](https://docs.wushuo.top/add-rss)
- [Docker 部署](https://docs.wushuo.top/deploy/docker)
- [常见问题](https://docs.wushuo.top/faq)

本仓库构建：

```bash
# Linux / Git Bash
bash ./package.sh
```

Windows 可用 Git Bash 或按模块分别构建 `ani-rss-application` / `ani-rss-ui`。  
**升级本 Fork 后请完整重启**，否则可能仍加载旧前端，任务管理器表现会像「状态空白、只能看到 Hash」。

## 与上游的关系

- 上游：功能主线、文档站点、社区发行版  
- 本 Fork：选择性合入上游；OpenList 与任务调度相关改动以本仓库为准  
- 不建议把本 Fork 的 OpenList/任务管理器大改直接无审 PR 回上游；接口与产品形态已分叉  
- 反馈问题请带：下载工具类型、是否 OpenList、离线超时配置、任务管理器截图与相关日志

## 截图

上游界面示意（本 Fork 额外提供任务管理器入口与 OpenList 相关操作）：

![image](https://github.com/wushuo894/ani-rss-docs/raw/main/docs/image/screenshot/screenshot.webp#gh-light-mode-only)
![image](https://github.com/wushuo894/ani-rss-docs/raw/main/docs/image/screenshot/screenshot-dark.webp#gh-dark-mode-only)

## 免责声明

### 项目性质

本工具为中立性技术辅助工具，通过自动化程序抓取互联网公开分享的种子文件链接（非存储内容），并向用户指定的下载工具（如 qBittorrent、Transmission、Aria2、OpenList/AList 等）推送任务指令。工具本身不具备资源存储、分发及内容审查功能。

### 用户责任

- 合法性承诺：用户需确保下载行为及文件使用符合所在国家/地区法律法规，禁止用于盗版、非法传播等用途
- 自担风险：种子文件的合法性、安全性由资源提供方独立负责，用户需自行验证并承担相应风险

### 开发者免责

- 技术中立性：开发者仅维护工具功能实现，不参与种子内容控制，亦无法保证第三方链接有效性与网盘离线成功率
- OpenList/网盘离线受远端策略、配额、线路影响；本 Fork 的超时与清理逻辑用于避免本地调度卡死，不保证远端一定下载成功
- 因网络政策、源站限制、远端 API 变更造成的功能失效，需用户自行评估与调整配置

## License

见 [LICENSE](LICENSE)。上游项目版权归原作者及贡献者所有；本仓库在其许可框架下维护 Fork 改动。
