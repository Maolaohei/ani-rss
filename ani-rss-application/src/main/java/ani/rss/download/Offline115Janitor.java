package ani.rss.download;

import ani.rss.entity.Config;
import ani.rss.entity.web.Header;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 115 离线任务看门狗。
 * <p>
 * 为什么需要它：115 的 BT 离线任务一旦卡在 <b>99%</b>，就会永久停在
 * {@code status=1 percentDone=99}，既不完成也不失败；而这条任务一直占着该
 * {@code info_hash}，之后每次提交都会被 115 的「任务已存在(10008)」挡住。
 * 更糟的是这条任务在 <b>AList 侧完全不可见</b>（{@code /api/task/offline_download/*}
 * 返回空列表），所以 ANI-RSS 以前无论怎么重试、怎么 {@code purgeHashTasks} 都删不掉它，
 * 只能每 35 分钟撞一次 10008，直到 24h 长冷却。
 * <p>
 * 唯一出口是<b>按 hash 删除 115 侧任务</b>。AList 没有这个能力，故这里直接用 115 的老
 * lixian 接口（AList 自己登录 115 也是走这套）：{@code webapi.115.com/offline/*} 已经 404，
 * {@code /web/lixian/?ct=lixian&ac=task_lists|task_del} 仍然可用。
 * <p>
 * Cookie 来源优先级：{@code offline115Cookie} 手填 → 从 AList 管理接口反查
 * （{@code GET /api/admin/storage/list}，用 {@code downloadToolPassword} 当 Authorization，
 * 找到 {@code offline115Mount} 挂载的 115 存储，读 {@code addition.cookie}）。
 * 后者让「客户只配了 AList」的常见部署开箱即用。
 * <p>
 * 注意两个易踩的坑（都在真实环境验证过）：
 * <ul>
 *   <li>{@code task_del} 一次只认一个 hash：{@code hash[0]} 生效，{@code hash[1]} 会被忽略，
 *       所以删除必须逐条调用，不能拼批量；</li>
 *   <li>{@code task_del} 即使返回 {@code {"state":true}} 也可能没真删，需要重新列举确认。</li>
 * </ul>
 */
@Slf4j
public class Offline115Janitor {

    /**
     * 115 老 lixian 接口前缀
     */
    private static final String API = "https://115.com/web/lixian/?ct=lixian&ac=";
    private static final String REFERER = "https://115.com/";
    /**
     * 115 会校验 UA，必须带浏览器 UA（{@code HttpReq} 默认 UA 是项目名，会被拒）
     */
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 15000;

    /**
     * 卡死判定默认阈值（分钟）：99% 且超过这么久没完成，就认为它不会再完成了
     */
    public static final int DEFAULT_STUCK_MINUTES = 180;
    /**
     * 配额告警默认阈值（百分比）
     */
    public static final int DEFAULT_QUOTA_WARN_PERCENT = 85;
    /**
     * AList 里 115 存储的默认挂载路径
     */
    public static final String DEFAULT_MOUNT = "/115";

    /**
     * 进行中（含"永久卡在 99%"）
     */
    public static final int STATUS_RUNNING = 1;
    /**
     * 已完成
     */
    public static final int STATUS_FINISHED = 2;

    /**
     * cookie 缓存：AList 反查是一次额外往返，没必要每次删除都查一遍
     */
    private static final long COOKIE_CACHE_MS = 10 * 60 * 1000L;
    private static volatile String cookieCache;
    private static volatile long cookieCacheAt;

    /**
     * 单条 115 离线任务
     *
     * @param infoHash    磁力 hash（115 侧唯一键，也是去重键）
     * @param name        任务名
     * @param status      1=进行中 2=已完成
     * @param percentDone 进度百分比
     * @param addTime     添加时间（秒级时间戳）
     */
    public record Task(String infoHash, String name, int status, int percentDone, long addTime) {
        public boolean finished() {
            return status == STATUS_FINISHED;
        }
    }

    /**
     * 一次配额 + 任务快照
     *
     * @param tasks   任务列表
     * @param quota   已用配额
     * @param total   配额上限
     * @param fetchedAt 抓取时间（毫秒）
     */
    public record Snapshot(List<Task> tasks, int quota, int total, long fetchedAt) {

        public int quotaPercent() {
            return Offline115Janitor.quotaPercent(quota, total);
        }

        public int runningCount() {
            return (int) tasks.stream().filter(t -> !t.finished()).count();
        }

        public int finishedCount() {
            return (int) tasks.stream().filter(Task::finished).count();
        }

        public List<Task> stuck(int stuckMinutes) {
            long now = System.currentTimeMillis();
            List<Task> list = new ArrayList<>();
            for (Task task : tasks) {
                if (isStuck(task, now, stuckMinutes)) {
                    list.add(task);
                }
            }
            return list;
        }
    }

    private Offline115Janitor() {
    }

    // ------------------------------------------------------------------
    // 纯逻辑（可单测，不碰网络）
    // ------------------------------------------------------------------

    /**
     * 配额占用百分比（{@code total<=0} 时返回 0，避免除零）
     */
    public static int quotaPercent(int quota, int total) {
        if (total <= 0) {
            return 0;
        }
        int percent = (int) Math.round(quota * 100.0 / total);
        return Math.max(0, Math.min(100, percent));
    }

    /**
     * 卡死判定：只有"进行中 + 已经 99% + 拖过了阈值"才算卡死。
     * <p>
     * 为什么不把"进行中且进度低"也算卡死：那可能只是刚开始跑，删了等于把正在下载的成果丢掉。
     * 115 的 99% 是明确的终态化停滞（实测两条任务分别停了 18 小时和 1.7 天），
     * 而低进度任务通常会在几分钟内推进。
     *
     * @param task         任务
     * @param nowMs        当前时间
     * @param stuckMinutes 阈值（分钟）
     */
    public static boolean isStuck(Task task, long nowMs, int stuckMinutes) {
        if (task == null || task.finished() || StrUtil.isBlank(task.infoHash())) {
            return false;
        }
        if (task.percentDone() < 99 || task.addTime() <= 0) {
            return false;
        }
        long elapsedMs = nowMs - task.addTime() * 1000L;
        return elapsedMs >= Math.max(1, stuckMinutes) * 60_000L;
    }

    /**
     * 解析 {@code task_lists} 响应
     */
    public static List<Task> parseTasks(String body) {
        if (StrUtil.isBlank(body)) {
            return Collections.emptyList();
        }
        try {
            JSONObject root = JSONUtil.parseObj(body);
            JSONArray array = root.getJSONArray("tasks");
            if (array == null || array.isEmpty()) {
                return Collections.emptyList();
            }
            List<Task> list = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                JSONObject item = array.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                String hash = item.getStr("info_hash");
                if (StrUtil.isBlank(hash)) {
                    continue;
                }
                list.add(new Task(
                        normalizeHash(hash),
                        StrUtil.nullToEmpty(item.getStr("name")),
                        item.getInt("status", 0),
                        item.getInt("percentDone", 0),
                        item.getLong("add_time", 0L)
                ));
            }
            return list;
        } catch (Exception e) {
            log.warn("解析 115 离线任务列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 解析配额
     */
    public static int[] parseQuota(String body) {
        if (StrUtil.isBlank(body)) {
            return new int[]{0, 0};
        }
        try {
            JSONObject root = JSONUtil.parseObj(body);
            return new int[]{root.getInt("quota", 0), root.getInt("total", 0)};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    /**
     * 从 AList 的 {@code /api/admin/storage/list} 响应里取 115 cookie。
     * <p>
     * AList 的 {@code addition} 是<b>字符串</b>（JSON 文本），不是对象——按对象取会拿到 null，
     * 这里两种形态都兼容。
     *
     * @param body      响应体
     * @param mountPath 115 存储的挂载路径
     */
    public static String parseAlistCookie(String body, String mountPath) {
        if (StrUtil.isBlank(body)) {
            return null;
        }
        String mount = StrUtil.blankToDefault(mountPath, DEFAULT_MOUNT);
        try {
            JSONObject root = JSONUtil.parseObj(body);
            JSONObject data = root.getJSONObject("data");
            if (data == null) {
                return null;
            }
            JSONArray content = data.getJSONArray("content");
            if (content == null || content.isEmpty()) {
                return null;
            }
            for (int i = 0; i < content.size(); i++) {
                JSONObject storage = content.getJSONObject(i);
                if (storage == null) {
                    continue;
                }
                if (!mount.equals(storage.getStr("mount_path"))) {
                    continue;
                }
                String driver = StrUtil.nullToEmpty(storage.getStr("driver"));
                if (!driver.contains("115")) {
                    return null;
                }
                Object addition = storage.get("addition");
                JSONObject additionObj = null;
                if (addition instanceof JSONObject obj) {
                    additionObj = obj;
                } else if (addition != null) {
                    String text = String.valueOf(addition);
                    if (JSONUtil.isTypeJSONObject(text)) {
                        additionObj = JSONUtil.parseObj(text);
                    }
                }
                if (additionObj == null) {
                    return null;
                }
                String cookie = additionObj.getStr("cookie");
                return StrUtil.isBlank(cookie) ? null : cookie;
            }
            return null;
        } catch (Exception e) {
            log.warn("解析 AList 存储列表失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 115 删除响应
     */
    public static boolean parseDeleteResult(String body) {
        if (StrUtil.isBlank(body)) {
            return false;
        }
        try {
            JSONObject root = JSONUtil.parseObj(body);
            return Boolean.TRUE.equals(root.getBool("state", false));
        } catch (Exception e) {
            return false;
        }
    }

    public static String normalizeHash(String infoHash) {
        return StrUtil.nullToEmpty(infoHash).trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // 配置读取
    // ------------------------------------------------------------------

    /**
     * 下载工具是否为 OpenList（AList）
     * <p>
     * 看门狗依赖 AList 侧反查 115 cookie，也只服务于 OpenList 的离线下载链路，
     * 其它下载器（qBittorrent / Transmission / Aria2）里该开关一律视为关闭。
     */
    public static boolean openListTool(Config config) {
        return config != null && "OpenList".equalsIgnoreCase(StrUtil.nullToEmpty(config.getDownloadToolType()));
    }

    /**
     * 看门狗是否真正生效：开关打开 <b>且</b> 下载工具是 OpenList
     */
    public static boolean enabled(Config config) {
        return openListTool(config) && Boolean.TRUE.equals(config.getOffline115Janitor());
    }

    public static int stuckMinutes(Config config) {
        Integer value = config == null ? null : config.getOffline115StuckMinutes();
        return value == null || value < 1 ? DEFAULT_STUCK_MINUTES : value;
    }

    public static int quotaWarnPercent(Config config) {
        Integer value = config == null ? null : config.getOffline115QuotaWarnPercent();
        if (value == null) {
            return DEFAULT_QUOTA_WARN_PERCENT;
        }
        return Math.max(1, Math.min(99, value));
    }

    // ------------------------------------------------------------------
    // 网络
    // ------------------------------------------------------------------

    /**
     * 解析 cookie：手填优先，否则从 AList 反查（带 10 分钟缓存）
     */
    public static String resolveCookie(Config config) {
        if (config == null) {
            return null;
        }
        String manual = config.getOffline115Cookie();
        if (StrUtil.isNotBlank(manual)) {
            return manual.trim();
        }
        long now = System.currentTimeMillis();
        String cached = cookieCache;
        if (StrUtil.isNotBlank(cached) && now - cookieCacheAt < COOKIE_CACHE_MS) {
            return cached;
        }
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        if (StrUtil.isBlank(host) || StrUtil.isBlank(password)) {
            return null;
        }
        try {
            String body = HttpReq.get(StrUtil.removeSuffix(host, "/") + "/api/admin/storage/list", TIMEOUT_MS)
                    .header(Header.AUTHORIZATION, password)
                    .execute()
                    .body();
            String cookie = parseAlistCookie(body, config.getOffline115Mount());
            if (StrUtil.isNotBlank(cookie)) {
                cookieCache = cookie;
                cookieCacheAt = now;
                return cookie;
            }
            log.debug("未能从 AList 存储列表解析出 115 cookie，mount={}", config.getOffline115Mount());
            return null;
        } catch (Exception e) {
            log.warn("从 AList 反查 115 cookie 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 清空 cookie 缓存（改配置后调用）
     */
    public static void invalidateCookie() {
        cookieCache = null;
        cookieCacheAt = 0L;
    }

    private static String post(String action, Map<String, Object> form, String cookie) {
        try {
            return HttpReq.post(API + action, TIMEOUT_MS)
                    .header(Header.USER_AGENT, BROWSER_UA)
                    .header("Referer", REFERER)
                    .header("Cookie", cookie)
                    .form(form)
                    .execute()
                    .body();
        } catch (Exception e) {
            log.warn("115 离线任务接口 {} 调用失败: {}", action, e.getMessage());
            return null;
        }
    }

    /**
     * 拉取 115 离线任务列表（一页 115 默认返回全部，够用；不翻页避免额外请求）
     */
    public static List<Task> listTasks(Config config) {
        String cookie = resolveCookie(config);
        if (StrUtil.isBlank(cookie)) {
            return Collections.emptyList();
        }
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("page", "1");
        return parseTasks(post("task_lists", form, cookie));
    }

    /**
     * 任务 + 配额快照
     */
    public static Snapshot snapshot(Config config) {
        String cookie = resolveCookie(config);
        if (StrUtil.isBlank(cookie)) {
            return null;
        }
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("page", "1");
        String body = post("task_lists", form, cookie);
        if (StrUtil.isBlank(body)) {
            return null;
        }
        int[] quota = parseQuota(body);
        return new Snapshot(parseTasks(body), quota[0], quota[1], System.currentTimeMillis());
    }

    /**
     * 按 hash 删除一条 115 离线任务
     * <p>
     * 一次只认一个 hash（{@code hash[1]} 会被静默忽略），删完重新列举确认。
     */
    public static boolean deleteByHash(Config config, String infoHash) {
        String hash = normalizeHash(infoHash);
        if (StrUtil.isBlank(hash)) {
            return false;
        }
        String cookie = resolveCookie(config);
        if (StrUtil.isBlank(cookie)) {
            return false;
        }
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("hash[0]", hash);
        form.put("flag", "0");
        String body = post("task_del", form, cookie);
        boolean ok = parseDeleteResult(body);
        if (!ok) {
            log.warn("115 离线任务删除未生效 hash={} 响应={}", hash, StrUtil.maxLength(body, 120));
            return false;
        }
        // 二次确认：接口返回 true 也可能没真删
        Map<String, Object> pageForm = new LinkedHashMap<>();
        pageForm.put("page", "1");
        List<Task> after = parseTasks(post("task_lists", pageForm, cookie));
        boolean gone = after.stream().noneMatch(t -> hash.equals(t.infoHash()));
        if (!gone) {
            log.warn("115 离线任务删除后仍存在 hash={}", hash);
        }
        return gone;
    }

    /**
     * 若该 hash 在 115 侧是"卡死任务"，删掉它
     * <p>
     * 这是 10008 去重死锁的唯一解法：任务不删，hash 永远被占。
     *
     * @return 是否真的删掉了
     */
    public static boolean deleteIfStuck(Config config, String infoHash) {
        if (!enabled(config) || StrUtil.isBlank(infoHash)) {
            return false;
        }
        try {
            String hash = normalizeHash(infoHash);
            List<Task> tasks = listTasks(config);
            Task target = tasks.stream()
                    .filter(t -> hash.equals(t.infoHash()))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                return false;
            }
            if (!isStuck(target, System.currentTimeMillis(), stuckMinutes(config))) {
                log.debug("115 离线任务未判为卡死，保留 hash={} status={} percentDone={}",
                        hash, target.status(), target.percentDone());
                return false;
            }
            log.warn("115 离线任务卡死（{}% 已超 {} 分钟未完成），按 hash 删除 hash={} name={}",
                    target.percentDone(), stuckMinutes(config), hash, target.name());
            return deleteByHash(config, hash);
        } catch (Exception e) {
            log.warn("115 离线任务看门狗执行失败 hash={}: {}", infoHash, e.getMessage());
            return false;
        }
    }

    /**
     * 清理全部卡死任务
     *
     * @param dryRun 只统计不删除
     * @return 命中的任务（dryRun 时即"将删除"清单）
     */
    public static List<Task> cleanStuck(Config config, boolean dryRun) {
        List<Task> stuck = new ArrayList<>();
        String cookie = resolveCookie(config);
        if (StrUtil.isBlank(cookie)) {
            return stuck;
        }
        List<Task> tasks = listTasks(config);
        int minutes = stuckMinutes(config);
        long now = System.currentTimeMillis();
        for (Task task : tasks) {
            if (!isStuck(task, now, minutes)) {
                continue;
            }
            stuck.add(task);
            if (dryRun) {
                continue;
            }
            deleteByHash(config, task.infoHash());
        }
        return stuck;
    }
}
