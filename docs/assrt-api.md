# ASSRT（伪射手网）API 说明

本文档记录 ani-rss 字幕自动获取功能所依赖的 ASSRT 官方接口（`api.assrt.net`），
供维护与排错参考。实现代码位于
`ani-rss-application/src/main/java/ani/rss/service/subtitle/AssrtSubtitleProvider.java`。

> 接口以 HTTP GET 调用，返回 JSON。所有下载直链均带时效、且每次返回可能不同，**不要缓存下载链接**。

---

## 1. 基本信息

| 项 | 值 |
| --- | --- |
| 主域名 | `https://api.assrt.net` |
| 备用域名 | `https://api.makedie.me` |
| 协议 | HTTPS，GET |
| 鉴权 | Token，放在查询参数 `token` 中，或请求头 `Authorization: Bearer <token>` |
| 频率限制 | 同一 token + IP **默认 5 次/分钟**（可在 assrt.net 用户后台自行配置/提升） |
| 分页大小 | `cnt` 最大 **15**（超过无效） |

---

## 2. 通用响应结构

所有接口返回统一的 JSON 信封：

```json
{
  "status": 0,
  "sub": { ... },
  "err_msg": ""
}
```

- `status`：`0` 表示成功；非 `0` 表示失败。
- `sub`：业务数据（不同接口结构不同，详见下文）。
- `err_msg`：失败时的错误描述。

### 错误码

| 类别 | HTTP | status | 含义 |
| --- | --- | --- | --- |
| APIError | 200 | `1` | 用户不存在 |
| APIError | 200 | `101` | 关键词长度不足 3 个字符 |
| ClientFail | 4xx | `20001` | Token 无效 |
| ClientFail | 4xx | `20900` | 字幕未找到 |
| ServerFail | 5xx | `30900` | 超出接口调用限制（频率/配额） |

> 注：鉴权失败通常在 HTTP 层以 4xx 返回，业务层 `status` 为 `20001`。

---

## 3. 字幕搜索 `sub/search`

**请求**

```
GET /v1/sub/search?token=<TOKEN>&q=<关键词>&cnt=15&pos=0[&is_file=1][&no_muxer=1][&filelist=...]
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `token` | 是 | API Token |
| `q` | 是 | 搜索关键词。可为番剧标题，或视频文件名（配合 `is_file`/`no_muxer`） |
| `cnt` | 否 | 返回条数，最大 15，默认 15 |
| `pos` | 否 | 偏移量（分页起点），默认 0 |
| `is_file` | 否 | `1` 表示把 `q` 当作**视频文件名**来匹配（忽略番剧名/字幕组等参数） |
| `no_muxer` | 否 | `1` 隐含 `is_file=1`，且忽略字幕组/封装信息，仅按文件名精匹配。用于“按本集视频文件搜字幕” |
| `filelist` | 否 | 传入本地文件名列表，批量匹配 |

**响应（`sub` 字段）**

```json
{
  "sub": {
    "subs": [
      {
        "id": 123456,
        "videoname": "番剧名 / 视频名",
        "name": "...",
        "release": "...",
        "native_name": "...",
        "lang": "chs",
        "url": "https://.../package.zip",   // 整包直链（可选，通常仅压缩包）
        "files": [                          // 单个字幕文件列表（可选）
          { "f": "Ep01.chs.ass", "url": "https://.../dl/xxx" },
          { "f": "Ep01.cht.srt", "url": "https://.../dl/yyy" }
        ]
      }
    ]
  }
}
```

- `subs[]`：候选字幕条目数组。
- 每条可能直接给出 `files[]`（逐文件直链），或只给一个整包 `url`（zip/rar/7z）。
- 若搜索结果未内联 `files`，可用条目 `id` 调 `sub/detail` 补全。

> ani-rss 的取数策略（v3.4.6 起）：
> 1. **单次搜索**——以番剧<b>英文标题</b>（{@code themoviedbName} → 含拉丁字母的订阅标题 →
>    日文原名 → 订阅标题）发<b>一次</b> {@code sub/search}，不再按「每个视频 × 精确/宽泛两档」
>    拆分请求，也不再附加 {@code no_muxer}。旧实现一次批量匹配就能打满 5 次/分钟的配额并触发
>    {@code 30900}。
> 2. **用户选择**——候选条目原样返回给用户挑选，后端不做自动挑选，避免匹配到错误字幕。
> 3. **按需下载**——用户选中某条候选后才下载：条目内联 {@code files} 时直接展开（不消耗额外配额），
>    否则按 {@code id} 调一次 {@code sub/detail} 补全。合集包由解包逻辑按目标集数挑选并改名。

---

## 4. 字幕详情 `sub/detail`

**请求**

```
GET /v1/sub/detail?token=<TOKEN>&id=<SUB_ID>
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `token` | 是 | API Token |
| `id` | 是 | `sub/search` 返回的条目 `id` |

**响应（`sub` 字段）**

```json
{
  "sub": {
    "id": 123456,
    "filelist": [
      { "f": "S01E01.chs.ass", "url": "https://.../dl/xxx" },
      { "f": "S01E02.chs.ass", "url": "https://.../dl/yyy" }
    ],
    "url": "https://.../package.zip"   // 整包直链（可选）
  }
}
```

- `filelist[].f`：字幕文件名。
- `filelist[].url`：该文件的下载直链。
- 顶层 `url`：整包（压缩包）的直接下载链接。

---

## 5. 下载直链与重定向

- `files[].url` / `filelist[].url` / 顶层 `url` 均为**直链**，但可能带时效、且每次请求可能返回不同地址。
- 部分直链首次请求会返回一个 JSON 重定向体 `{ "url": "真实地址" }`，需再请求一次真实地址。
- 服务端对下载频率同样受限，失败多为 `30900`（超限），应退避重试。

---

## 6. 实现要点（ani-rss）

| 关注点 | 处理方式 |
| --- | --- |
| 搜索关键词 | **优先英文标题**：`themoviedbName` → 含拉丁字母的订阅标题 → 日文原名 → 订阅标题；先剔除 TMDB 附加的 `(2018)` / `{tmdb-12345}` 后缀 |
| 请求次数 | 搜索阶段**只发一次** `sub/search`（`cnt=15`）；用户选中候选后才按需下载，内联 `files` 时不额外调 `sub/detail` |
| 候选选择 | 搜索结果原样返回给用户挑选，后端不做自动挑选；选中后先下载预览，二次确认才落盘 |
| 语言偏好 | `chs` / `cht`，从文件名关键字与 `lang` 字段识别；仅用于候选**排序**，不做过滤 |
| 集数门槛 | 单文件候选：与目标视频都能解析出季/集且不一致时剔除；**压缩包（合集）不在此阶段剔除**，交给解包阶段按目标集数挑选 |
| 集数解析 | 先剔除分辨率/编码/帧率/位深/尺寸（如 `YUV420P10`、`1920x1080`）等干扰数字，再按优先级识别：`SxxExx` → `E/EPxx` → `第N话/集/期`（阿拉伯或中文数字）→ 末位独立数字（兼容 `05.ass`）；允许序号后直接跟扩展名的点 |
| 合集包处理 | 仅支持解压 **zip**；从包内按「目标集数（必要时季数）+ 语言 + 文件名相似度」挑选最匹配的一集；rar/7z 跳过并交由下一个候选 |
| 重命名 | 选中的字幕写为 `视频主名[.语言标签].扩展名`，与本地点播/播放侧的主名前缀匹配规则一致 |
| 落盘 | 本地下载器就地写入；OpenList 离线下载走 `fsPut` 上传到云端视频同目录 |
| 频率 | 默认配额 **5 次/分钟**（可在 assrt.net 后台自行配置，并在设置面板「调用频率限制（次/分钟）」中同步，代码据此全局限流避免触发 `30900`） |

### 6.1 超时与重试（v3.4.6 起）

旧实现所有请求共用 `HttpReq` 的 20s 单一超时，且**没有任何重试**——一次抖动或服务端慢响应
就直接失败，用户看到的是 `ConnectException: Connection timed out`。

现在 ASSRT 的 API 调用走独立的请求通道（`AssrtSubtitleProvider.getWithRetry`）：

| 项 | 默认值 | 配置项 | 说明 |
| --- | --- | --- | --- |
| 连接超时 | 15000 ms | `assrtConnectTimeoutMs` | TCP 握手 + TLS；链路握手通常很快，能较快暴露被防火墙丢包的情况 |
| 读取超时 | 30000 ms | `assrtReadTimeoutMs` | 等待响应体；ASSRT 库忙时出数据偏慢，旧 20s 共用超时偏紧 |
| 重试次数 | 2（最多请求 3 次） | `assrtRetryCount` | 按 1s / 2s / 4s 指数退避 |

重试策略：

- **可重试**：连接/读取超时、连接被重置、DNS 失败、HTTP 5xx、429，以及 ASSRT 的
  `30900`（超出接口调用限制，文档明确要求退避重试）。
- **不重试**：`20001`（Token 无效）、`101`（关键词过短）等 4xx 确定性错误——重试只会白白消耗配额。
- **域名回退**：重试时交替使用主/备域名，主域名持续不可用时下一次尝试直接切
  `api.makedie.me`（注意它只是 `api.assrt.net` 的 CNAME，IP 相同，因此只能绕过域名级封锁，
  不能绕过 IP 级封锁）。

### 6.2 排查 `ConnectException: Connection timed out`

按以下顺序定位：

1. **DNS**：`nslookup api.assrt.net` 应解析到 `43.133.211.58`（腾讯云）。解析异常或被污染 → 换 DNS。
2. **ICMP/TCP**：`ping api.assrt.net`、`curl -v --noproxy '*' https://api.assrt.net/v1/sub/search?token=x&q=x`。
   `curl` 返回 `HTTP 400` 属正常（token 无效），说明链路本身通畅。
3. **IPv6**：`api.assrt.net` 目前**没有 AAAA 记录**，可排除 IPv6 出口不通导致的连接超时。
4. **代理**：ani-rss 的代理是**按域名白名单**生效的（`proxy` + `proxyList`）。若 `proxyList`
   里含 `assrt.net` 而该代理不可达，就会稳定复现 `ConnectException: Connection timed out`——
   此时要么修好代理，要么把 `assrt.net` 从 `proxyList` 移除。
5. **服务器出网**：境外服务器访问国内 IP 可能被中间设备干扰。先直连测试；若确为出网封锁，
   只能走可用代理或换部署位置。

---

## 7. 获取 Token

登录 [assrt.net](https://assrt.net) → 用户中心 → API Token（免费）。
填入 ani-rss 设置「字幕自动获取 → ASSRT Token」。未填 Token 时即便开启开关也不会抓取。
默认调用配额 5 次/分钟（token + IP），可在用户后台调整；批量下载字幕时请留意配额，超限会返回 `30900`，ani-rss 已在代码中做全局限流规避。
