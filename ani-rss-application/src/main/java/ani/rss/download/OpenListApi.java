package ani.rss.download;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.entity.OpenListFileInfo;
import ani.rss.entity.OpenListTaskInfo;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.basic.HttpRequestPlus;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OpenList/Alist 网盘 API 客户端：认证、限流、文件系统与离线任务接口。
 * <p>
 * 从 OpenList 上帝类中拆出的纯 API 层，不含业务编排（下载等待/重命名/残留治理）。
 * 无状态：host/token 通过 {@link #setConfig(Config)} 注入（登录时同步）。
 */
@Slf4j
public class OpenListApi {

    private volatile Config config;

    /**
     * 登录成功后注入配置（host/token/provider）
     */
    public void setConfig(Config config) {
        this.config = config;
    }

    // ---- 网盘 API 限流：令牌桶（替代原先的固定最小间隔）----
    private static final Object API_RATE_LOCK = new Object();
    /**
     * 默认速率（次/秒）。
     * <p>
     * 取 1 而不是更激进的值：网盘按账号限流，列举又是"1 + 子目录数"次往返的递归调用，
     * 速率过高只会把账号打进服务端限流（然后被熔断，反而更慢）。
     * 需要更快可以调大 {@code openListApiPerSecond}（上限 20），但单轮列举预算
     * 与熔断阈值都随速率联动，请一并确认（见 {@code RssTask.resolveAffordableListingsPerRound}）。
     */
    private static final double DEFAULT_API_PER_SECOND = 1.0;
    private static final double DEFAULT_API_BURST = 1.0;
    private static final double MAX_API_PER_SECOND = 20.0;
    private static final double MAX_API_BURST = 5.0;
    /**
     * 单次限流最长等待。配置过小（如 1 次/秒）时等待会很长，
     * 但绝不能变成"无上限挂起"——那会让整个轮次静默卡死。
     */
    private static final long MAX_THROTTLE_WAIT_MS = 5000L;
    /** 上次补充令牌的时间（nanoTime：不受系统时钟回拨影响） */
    private static long lastTokenAtNanos = 0L;
    /** 当前可用令牌（浮点累积，避免小速率下取整归零） */
    private static double availableTokens = 0.0;
    /** 限流累计等待毫秒数（可观测：到底被限流拖了多少时间） */
    private static final java.util.concurrent.atomic.AtomicLong throttleWaitMs =
            new java.util.concurrent.atomic.AtomicLong(0L);

    // ---- 熔断：连续失败到阈值后进入冷却，冷却期内不再发起网盘请求 ----
    private static final java.util.concurrent.atomic.AtomicInteger consecutiveListingFailures =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicInteger cooldownLevel =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static volatile long cooldownUntilMs = 0L;
    private static final long MAX_COOLDOWN_MS = java.util.concurrent.TimeUnit.MINUTES.toMillis(10);
    /** 熔断触发次数（可观测） */
    private static final java.util.concurrent.atomic.AtomicLong cooldownTriggered =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /**
     * 已经为哪个冷却窗口打过"本轮跳过"的 INFO。
     * <p>
     * 冷却期逐条打会刷满日志，一条不打则用户只能看到"网盘不可用"——两头都不行，
     * 所以按"冷却窗口"去重：一次冷却最多一条 INFO，带剩余秒数（2026-09-20 排查所加）。
     */
    private static final java.util.concurrent.atomic.AtomicLong cooldownSkipLoggedFor =
            new java.util.concurrent.atomic.AtomicLong(0L);

    // ---- 失败记忆（不缓存"结果"，但要缓存"失败本身"）----
    /**
     * 同一路径的失败记忆存活时长。
     * <p>
     * 与成功列举的短缓存（{@code FIND_FILES_TTL_MS} = 30s）同量级：这里是"这次没查成"，
     * 不该像成功结果一样被信任很久——上游一恢复，下一分钟就该重新真查。
     */
    private static final long LISTING_FAILURE_TTL_MS = java.util.concurrent.TimeUnit.SECONDS.toMillis(30);
    /**
     * path -> 最近的失败。
     * <p>
     * <b>为什么必须记失败</b>：一个订阅有 N 条已下载记录时，每条都会为同一个目录调一次列举；
     * 上游一抖，N 次全部失败——每条都要等上游 10~30s 超时，熔断计数还会被直接顶到阈值，
     * 于是整批订阅一起被冷却。记忆之后：同一目录在窗口内失败一次就够。
     * <p>
     * 与"目录不存在"无关：那是业务结果（确认空），不进本表（新目录随时可能被创建）。
     */
    private static final Map<String, FailedListing> listingFailures = new ConcurrentHashMap<>();
    /** 因失败记忆而省下的重复请求次数（可观测） */
    private static final java.util.concurrent.atomic.AtomicLong listingFailureMemoHit =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /** 最近一次列举失败（供自检页回答"到底哪一跳坏了"） */
    private static volatile long lastListingFailureAt = 0L;
    private static volatile String lastListingFailureMessage = "";
    /**
     * 本轮已强制刷新过的云下载目录（归位对账用）。
     * <p>
     * 每条 item 都 `invalidate + list` 一次云下载目录是纯浪费：一个订阅 12 集就是 12 次请求，
     * 网盘一抖则 12 次失败。现在只在本轮首次读时强制刷新，其余复用共享列举缓存。
     */
    private static final Set<String> cloudListingRefreshedThisRound = ConcurrentHashMap.newKeySet();

    /**
     * 登记"本轮已强制刷新过该目录"。首次调用返回 {@code true}（调用方据此真实刷新），
     * 之后返回 {@code false}。
     */
    public static boolean markCloudListingRefreshedThisRound(String path) {
        return cloudListingRefreshedThisRound.add(path);
    }

    /** 一次被记住的失败：到期时间 + 现场 */
    private static final class FailedListing {
        final long expireAt;
        final RuntimeException error;

        FailedListing(long expireAt, RuntimeException error) {
            this.expireAt = expireAt;
            this.error = error;
        }
    }

    // findFiles 短缓存，轮询期间减少递归 list
    private static final long FIND_FILES_TTL_MS = java.util.concurrent.TimeUnit.SECONDS.toMillis(30);
    private static final Map<String, CachedFileList> findFilesCache = new ConcurrentHashMap<>();

    /**
     * 缓存世代号：每次失效自增。构建开始时快照，写缓存前比对，防止"失效后旧结果回填"。
     * <p>
     * 场景：线程 A 正在递归列举（HTTP 慢），期间目录发生 rename/move 触发失效；
     * 若 A 回来后仍把变更前的旧结果写进缓存，失效就白做了，调用方会读到过期数据。
     */
    private static final AtomicLong cacheEpoch = new AtomicLong(0L);

    // listFileNames 长缓存: "本地已下载"判断用, 文件列表变化不频繁
    private static final long LIST_NAMES_TTL_MS = java.util.concurrent.TimeUnit.SECONDS.toMillis(300);
    private static final Map<String, List<String>> listNamesCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> listNamesExpire = new ConcurrentHashMap<>();

    // ---- 可观测计数（F7-7）----
    // 目的：限流是否生效、缓存是否真的省下了请求、熔断何时被触发，必须能被看见。
    // 否则"网盘慢"只能靠猜，调参也无从验证。
    /** 累计 API 调用次数（不清零，看长期趋势） */
    private static final java.util.concurrent.atomic.AtomicLong apiCallCount =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /** 本轮 API 调用次数（每轮 RSS 扫描开始时复位） */
    private static final java.util.concurrent.atomic.AtomicLong apiCallCountRound =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /** 目录列举缓存命中次数 */
    private static final java.util.concurrent.atomic.AtomicLong listingCacheHit =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /** 目录列举缓存未命中次数（= 真的发了请求） */
    private static final java.util.concurrent.atomic.AtomicLong listingCacheMiss =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /**
     * 本轮真正发出的「目录列举」请求数（每轮 RSS 扫描开始时复位）。
     * <p>
     * 与 {@link #apiCallCountRound} 的区别：只有列举才计入——fs/mkdir、fs/move、上传、
     * 下载器查询等都不算。这样"每轮列举预算"才真的等于"还能为确认本地文件列举多少次"。
     * 缓存命中与被合并的等待方不计入（它们没有发请求）。
     */
    private static final AtomicLong listingCallCountRound = new AtomicLong(0L);

    // ---- v3 判定路径守卫：判定只允许「单层列举」，绝不允许递归 ----
    // 背景：一次网盘列举是"1 + 子目录数"次往返（见 buildFileList 的递归）。
    // 判定路径（回答"网盘上到底有没有这个文件"的链路：本地已存在判定 / 归位对账 / 收尾确认）
    // 一旦走递归列举，请求数就随集数放大；上游一抖，任意一个子目录超时都会把整条判定链拖垮，
    // 而全局熔断又把它放大成"所有订阅一起瘫痪"。
    // v3 的硬约束：判定路径的递归列举次数恒为 0，只允许 fsListStrict（单层、1 次请求）。
    // 这两个计数器就是这个不变量的观测面——埋了指标必须有人看（自检页见 DoctorController）。
    /**
     * 判定路径标记（可重入计数）。
     * <p>
     * 只能通过 {@link #inJudgementPath(Supplier)} / {@link #inJudgementPath(Runnable)} 进入，
     * 保证必然清理；不暴露裸的 enter/exit——判定标记在 ThreadLocal 上，漏掉一次 finally
     * 就会把同一工作线程上后续无关的列举也算成违规。
     */
    private static final ThreadLocal<Integer> JUDGEMENT_DEPTH = new ThreadLocal<>();
    /**
     * 递归列举自身深度（同线程内递增）。
     * <p>
     * 用来把"判定路径发生了几次递归列举"数成<b>次数</b>而不是"1 + 子目录数"：
     * 只有最外层那一次才算，递归进去的子目录不算。否则一次 12 集的递归会数成 13 次，
     * 指标就失去意义（测试断言"== 0"仍成立，但报表上读不出真实违规次数）。
     */
    private static final ThreadLocal<Integer> RECURSION_DEPTH = new ThreadLocal<>();
    /** 判定路径上发生过的递归列举次数（v3 不变量：必须恒为 0） */
    private static final AtomicLong judgementRecursiveListing = new AtomicLong(0L);
    /** 判定路径上的单层列举次数（= v3 允许的唯一读操作） */
    private static final AtomicLong judgementDirectListing = new AtomicLong(0L);
    /** 违规日志上限：不变量被破坏时会连续触发，限流避免刷满日志（计数照常累计） */
    private static final int JUDGEMENT_VIOLATION_LOG_LIMIT = 20;

    // ---- F7-2 请求合并（coalescing）----
    // 缓存只能挡住"已经算过"的请求，挡不住"正在算"的请求：两个线程同时问同一个目录，
    // 缓存都未命中，于是同一份列举被做两遍。请求合并让后来者等第一个人的结果。
    /** 同一 path 正在构建中的列举结果 */
    private static final Map<String, java.util.concurrent.CompletableFuture<List<String>>> listNamesInFlight =
            new ConcurrentHashMap<>();
    /**
     * findFilesStrict 的在途请求：同一 path 的并发递归列举只做一次。
     * <p>
     * 去实例锁（P1-5）之前，这份"去重"是靠 synchronized 顺带实现的；去掉锁之后必须显式做，
     * 否则两个线程会对同一目录各做一遍递归列举（请求数翻倍，正是限流最怕的）。
     * <p>
     * 不会自死锁：递归时 key 只会变长（{@code path + "/" + name}），
     * 线程不可能等待一个需要自己结果才能完成的 future。
     */
    private static final Map<String, CompletableFuture<List<OpenListFileInfo>>> findFilesInFlight =
            new ConcurrentHashMap<>();
    /** 等待上限：等待方绝不无限期挂起，超时按"查询失败"处理（→ 存疑） */
    private static final long MAX_COALESCE_WAIT_MS = 60_000L;
    /**
     * 递归列举（findFilesStrict）专用的合并等待上限，比单目录列举宽松得多。
     * <p>
     * 一次递归列举天然是"1 + 子目录数"次往返（默认 1 次/秒限流），大目录树超过 60s 很正常；
     * 沿用 60s 会让等待方无谓超时，而超时会向上抛（buildFileList 只吞 OpenListDirNotFoundException），
     * 整个父目录列举失败、findFiles 再把"查不到"报成"目录是空的"。
     * <p>
     * 3 分钟的依据：去掉实例锁之前，等待方是<b>无超时地</b>阻塞在实例监视器上的——
     * 一个有界等待严格优于它所取代的行为。超时仍抛异常：真正的"无法确定"绝不能变成"目录为空"。
     */
    private static final long MAX_FIND_FILES_COALESCE_WAIT_MS = 180_000L;
    /** 被合并掉（省下）的列举次数 */
    private static final java.util.concurrent.atomic.AtomicLong listingCoalesced =
            new java.util.concurrent.atomic.AtomicLong(0L);

    // ---- F7-5 每轮 API 预算 ----
    // 预算 = 0 表示不限制（非轮次场景，如用户手动预览）。由 RssTask 在轮次开始时设置、结束时清除。
    /** 本轮预算（0 = 不限制） */
    private static final java.util.concurrent.atomic.AtomicInteger roundBudget =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** 因预算耗尽而放弃校验的次数（可观测） */
    private static final java.util.concurrent.atomic.AtomicLong budgetExhausted =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /** 本轮预算上限的硬顶：无论订阅多少，单轮最多 200 次，防止"订阅 500 个就允许打 500 次" */
    public static final int MAX_API_BUDGET_PER_ROUND = 200;

    private static final int IDEMPOTENT_API_MAX_ATTEMPTS = 3;
    private static final long[] IDEMPOTENT_API_RETRY_DELAYS_MS = {500L, 1500L};

    private static final class CachedFileList {
        final long expireAt;
        final List<OpenListFileInfo> files;

        CachedFileList(List<OpenListFileInfo> files, long ttlMs) {
            this.files = files;
            this.expireAt = System.currentTimeMillis() + ttlMs;
        }
    }

    /**
     * 列出网盘目录下文件路径(递归, 60s 缓存), 供"本地已下载"判断使用。
     * 下载目录是网盘虚拟路径(本地文件系统不可见), 需通过 API 检查文件真实存在。
     * <p>
     * 查询失败时返回空列表(保持旧行为)。调用方若需要区分"目录确实为空"与"查询失败"
     * (例如预览要显示"存疑"), 请改用 {@link #listFileNamesStrict(String)}。
     */
    public List<String> listFileNames(String dirPath) {
        try {
            return listFileNamesStrict(dirPath);
        } catch (Exception e) {
            // API 故障: 不缓存, 下次重试; 记日志避免静默误判"目录无文件"
            log.warn("列出网盘目录失败 {}: {}", dirPath, ExceptionUtils.getMessage(e));
            return List.of();
        }
    }

    /**
     * 严格版列举：查询失败时抛出而非返回空列表, 且失败结果不写缓存。
     * 用于必须区分"目录确实为空"与"查询失败"的场景, 避免网盘抖动被误判成"文件都不存在"。
     * <p>
     * F7-2 请求合并：同一 path 的并发列举只发一次请求，其余调用等待同一份结果。
     */
    public List<String> listFileNamesStrict(String dirPath) {
        Long expire = listNamesExpire.get(dirPath);
        if (expire != null && expire > System.currentTimeMillis()) {
            List<String> cached = listNamesCache.get(dirPath);
            if (cached != null) {
                listingCacheHit.incrementAndGet();
                return cached;
            }
        }

        // 缓存未命中：先看有没有人正在算同一个目录
        java.util.concurrent.CompletableFuture<List<String>> mine = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<List<String>> existing = listNamesInFlight.putIfAbsent(dirPath, mine);
        if (existing != null) {
            listingCoalesced.incrementAndGet();
            return awaitCoalesced(dirPath, existing);
        }
        try {
            List<String> names = buildFileNames(dirPath);
            mine.complete(names);
            return names;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            listNamesInFlight.remove(dirPath, mine);
        }
    }

    /**
     * 等待同一 path 的列举结果。
     * <p>
     * 超时/被中断一律抛异常而不是返回空列表——空列表会被下游当成"目录里没有文件"，
     * 从而把"查不到"说成"不存在"，正是本需求要消灭的误判。
     */
    private static List<String> awaitCoalesced(String dirPath,
                                               java.util.concurrent.CompletableFuture<List<String>> future) {
        try {
            return future.get(MAX_COALESCE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("等待同一目录列举结果失败 path=" + dirPath, cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("等待同一目录列举结果超时（" + MAX_COALESCE_WAIT_MS
                    + "ms） path=" + dirPath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待同一目录列举结果被中断 path=" + dirPath, e);
        }
    }

    /**
     * 等待同一 path 的递归列举结果（findFilesStrict 的请求合并）。
     * <p>
     * 超时/被中断/上游失败一律抛异常，<b>绝不返回空列表</b>——空列表会被下游读成
     * "这个目录里没有文件"，从而把"查不到"说成"不存在"，直接导致重复下载。
     * <p>
     * 等待上限用 {@link #MAX_FIND_FILES_COALESCE_WAIT_MS}（递归列举往返次数多，比单目录宽松）。
     */
    private static List<OpenListFileInfo> awaitCoalescedFiles(
            String path, CompletableFuture<List<OpenListFileInfo>> future) {
        try {
            return future.get(MAX_FIND_FILES_COALESCE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("等待同一目录列举结果失败 path=" + path, cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("等待同一目录列举结果超时（" + MAX_FIND_FILES_COALESCE_WAIT_MS
                    + "ms） path=" + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待同一目录列举结果被中断 path=" + path, e);
        }
    }

    /**
     * 真正执行列举（含 300s 长缓存写入）。只在缓存未命中且无同 path 在途请求时进入。
     */
    private List<String> buildFileNames(String dirPath) {
        // 构建开始时快照世代号：期间若有目录变更触发失效，本次结果不得回填缓存
        long epoch = cacheEpoch.get();
        List<OpenListFileInfo> files;
        try {
            files = findFilesStrict(dirPath);
        } catch (OpenListDirNotFoundException e) {
            // 目录不存在 = 确认没有文件。这是正常结果而非查询失败，
            // 否则新订阅（下载目录还没被创建）一进预览就会被标成"存疑"。
            // 不写缓存：目录随时可能因首个下载落地而被创建。
            log.debug("网盘目录不存在，视为空目录 {}", dirPath);
            return List.of();
        }
        List<String> names = files.stream()
                .filter(f -> !Boolean.TRUE.equals(f.getIsDir()))
                .map(f -> {
                    String dir = f.getPath();
                    String name = f.getName();
                    return StrUtil.isBlank(dir) ? name : dir + "/" + name;
                })
                .collect(Collectors.toList());
        if (cacheEpoch.get() != epoch) {
            // 构建期间目录已变更：这次拿到的是变更前的旧数据，丢弃不缓存
            log.debug("列举期间缓存已失效，跳过回填 {}", dirPath);
            return names;
        }
        listNamesCache.put(dirPath, names);
        long expireAt = System.currentTimeMillis() + LIST_NAMES_TTL_MS;
        listNamesExpire.put(dirPath, expireAt);
        if (cacheEpoch.get() != epoch) {
            // 上面"检查→写入"之间仍可能被抢占：失效线程的 removeIf 先跑完，我们再写进去，
            // 旧数据就会存活整整 300s。写后复核并精确回滚自己刚写的条目。
            // remove(key, value) 只删自己写的那个值，绝不会误删新构建者的结果。
            log.debug("回填后检测到缓存已失效，回滚 {}", dirPath);
            listNamesCache.remove(dirPath, names);
            listNamesExpire.remove(dirPath, expireAt);
        }
        return names;
    }

    /**
     * 列出网盘目录下文件信息(递归, 30s 缓存), 供媒体库等需要大小/修改时间的场景使用。
     * 查询失败时抛出, 调用方可据此保守处理(不把"查不到"当成"没有")。
     */
    public List<OpenListFileInfo> listFilesStrict(String dirPath) {
        return findFilesStrict(dirPath);
    }

    /**
     * 创建文件夹
     *
     * @param path 路径
     */
    public void mkdir(String path) {
        invalidateFindFilesCache(path);
        retryIdempotent("fs/mkdir " + path, () -> {
            postApi("fs/mkdir")
                    .body(GsonStatic.toJson(Map.of(
                            "path", path
                    )))
                    .then(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        int code = jsonObject.get("code").getAsInt();
                        String message = jsonObject.has("message") ? jsonObject.get("message").getAsString() : "";
                        if (code == 200) {
                            log.info("创建文件夹: {}", path);
                            return;
                        }

                        if (!message.startsWith("failed to check if dir exists")) {
                            throw new IllegalStateException("fs/mkdir 失败 code=" + code + " " + message);
                        }

                        Path pathObj = Path.of(path);
                        if (pathObj.getNameCount() <= 1) {
                            throw new IllegalStateException("fs/mkdir 失败 code=" + code + " " + message);
                        }

                        String parentPath = pathObj
                                .getParent()
                                .toString()
                                .replace('\\', '/');
                        mkdir(parentPath);
                        mkdir(path);
                    });
            return null;
        });
    }

    /**
     * 移动文件
     *
     * @param srcDir 原目录
     * @param dstDir 目标目录
     * @param names  文件名
     */
    public void fsMove(String srcDir, String dstDir, List<String> names) {
        invalidateFindFilesCache(srcDir, dstDir);
        retryIdempotent("fs/move " + srcDir + " -> " + dstDir, () -> {
            postApi("fs/move")
                    .body(GsonStatic.toJson(Map.of(
                            "src_dir", srcDir,
                            "dst_dir", dstDir,
                            "names", names
                    ))).then(res -> {
                        log.info(res.body());
                        assertOpenListOk(res, "fs/move " + srcDir + " -> " + dstDir);
                    });
            return null;
        });
    }

    /**
     * 删除文件
     *
     * @param dir   目录
     * @param names 文件名
     */
    public void fsRemove(String dir, List<String> names) {
        invalidateFindFilesCache(dir);
        retryIdempotent("fs/remove " + dir, () -> {
            postApi("fs/remove")
                    .body(GsonStatic.toJson(Map.of(
                            "dir", dir,
                            "names", names
                    ))).then(res -> assertOpenListOk(res, "fs/remove " + dir));
            return null;
        });
    }

    /**
     * 上传文件到网盘（fs/put），用于把字幕写入与视频相同的云端目录。
     * 复用 OpenListUploadNotification 的 PUT 模式，但使用下载器自身的 host/token。
     *
     * @param dir      目录
     * @param fileName 文件名（含扩展名）
     * @param content  文件内容
     */
    public void fsPut(String dir, String fileName, byte[] content) {
        invalidateFindFilesCache(dir);
        retryIdempotent("fs/put " + dir + "/" + fileName, () -> {
            String url = config.getDownloadToolHost() + "/api/fs/put";
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofMinutes(5))
                    .header("Authorization", config.getDownloadToolPassword())
                    .header("As-Task", "false")
                    .header("File-Path", URLUtil.encode(dir + "/" + fileName))
                    .header("Content-Type", "application/octet-stream")
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(content))
                    .build();
            java.net.http.HttpResponse<String> response;
            try {
                response = SUBTITLE_HTTP_CLIENT.send(
                        request, java.net.http.HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            Assert.isTrue(response.statusCode() == 200,
                    "上传字幕失败 {} 状态码:{}", dir + "/" + fileName, response.statusCode());
            JsonObject jsonObject = GsonStatic.fromJson(response.body(), JsonObject.class);
            int code = jsonObject.get("code").getAsInt();
            Assert.isTrue(code == 200, "上传字幕失败 {} 状态码:{}", dir + "/" + fileName, code);
            log.info("OpenList 字幕上传完成 {}", fileName);
            return null;
        });
    }

    /**
     * fs/put 复用单例 HttpClient，避免逐文件新建导致 selector 线程泄漏
     */
    private static final java.net.http.HttpClient SUBTITLE_HTTP_CLIENT =
            java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(30)).build();

    /**
     * 批量重命名
     *
     * @param mapList 重命名列表
     * @param srcDir  目录
     */
    public void fsBatchRename(List<Map<String, String>> mapList, String srcDir) {
        invalidateFindFilesCache(srcDir);
        retryIdempotent("fs/batch_rename " + srcDir, () -> {
            postApi("fs/batch_rename")
                    .body(GsonStatic.toJson(Map.of(
                            "src_dir", srcDir,
                            "rename_objects", mapList
                    ))).then(res -> {
                        log.info(res.body());
                        assertOpenListOk(res, "fs/batch_rename " + srcDir);
                    });
            return null;
        });
    }

    /**
     * 添加离线下载
     *
     * @param magnet 磁力链接
     * @param path   离线位置
     * @return tid
     */
    public String fsAddOfflineDownload(String magnet, String path) {
        invalidateFindFilesCache(path);
        return postApi("fs/add_offline_download")
                .body(GsonStatic.toJson(Map.of(
                        "path", path,
                        "urls", List.of(magnet),
                        "tool", config.getProvider(),
                        "delete_policy", "delete_on_upload_succeed"
                )))
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    log.debug(jsonObject.toString());
                    int code = jsonObject.get("code").getAsInt();
                    // 10008: 任务已存在，视为成功（幂等）
                    if (code == 10008) {
                        log.info("离线任务已存在，跳过重复提交 {}", path);
                        return null;
                    }
                    Assert.isTrue(code == 200);
                    return jsonObject.getAsJsonObject("data")
                            .getAsJsonArray("tasks")
                            .get(0).getAsJsonObject()
                            .get("id").getAsString();
                });
    }

    /**
     * 文件列表
     *
     * @param path 目录
     * @return 文件列表
     */
    public List<OpenListFileInfo> fsList(String path, Boolean refresh) {
        try {
            return fsListStrict(path, refresh);
        } catch (Exception e) {
            log.warn("OpenList fs/list 调用失败 path={}: {}", path, ExceptionUtils.getMessage(e));
            return List.of();
        }
    }

    /**
     * 严格版文件列表：HTTP 或业务错误码失败时抛出，而非静默返回空列表。
     * <p>
     * 供必须区分"目录确实为空"与"查询失败"的场景使用（预览的「本地存在」列、媒体库），
     * 否则网盘抖动会被当成"文件都不存在"，误导用户重新下载。
     */
    public List<OpenListFileInfo> fsListStrict(String path, Boolean refresh) {
        // 熔断冷却期内直接拒绝，不再发请求：网盘已明确在限流/故障，
        // 继续打只会加重限流并拖长整轮时间。调用方据此把条目标为"存疑"。
        long cooldownRemaining = listingCooldownRemainingMs();
        if (cooldownRemaining > 0L) {
            throw new IllegalStateException("网盘接口冷却中（剩余 " + ((cooldownRemaining + 999L) / 1000L)
                    + "s），本次不发起请求 path=" + path);
        }
        // 失败记忆：同一目录刚失败过就不再打一次（每次都要等上游超时，且会重复计入熔断）
        FailedListing remembered = listingFailures.get(path);
        if (remembered != null) {
            if (remembered.expireAt > System.currentTimeMillis()) {
                listingFailureMemoHit.incrementAndGet();
                throw remembered.error;
            }
            listingFailures.remove(path, remembered);
        }
        try {
            List<OpenListFileInfo> result = retryIdempotent("fs/list " + path, () -> postApi("fs/list")
                    .body(GsonStatic.toJson(Map.of(
                            "path", path,
                            "page", 1,
                            "per_page", 0,
                            "refresh", refresh
                    )))
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        int code = jsonObject.get("code").getAsInt();
                        if (code != 200) {
                            String message = jsonObject.has("message") ? jsonObject.get("message").getAsString() : "";
                            // 目录不存在是"确认没有"，不是故障：既不该重试，也不该计入熔断
                            if (isDirNotFoundMessage(message)) {
                                throw new OpenListDirNotFoundException(
                                        "fs/list 目录不存在 path=" + path + " message=" + message);
                            }
                            throw new IllegalStateException(
                                    "fs/list 失败 code=" + code + " path=" + path + " message=" + message);
                        }
                        JsonElement data = jsonObject.get("data");
                        if (Objects.isNull(data) || data.isJsonNull()) {
                            return List.of();
                        }
                        JsonElement content = data.getAsJsonObject().get("content");
                        if (Objects.isNull(content) || content.isJsonNull()) {
                            return List.of();
                        }
                        List<OpenListFileInfo> infos = GsonStatic.fromJsonList(content.getAsJsonArray(), OpenListFileInfo.class);
                        for (OpenListFileInfo info : infos) {
                            info.setPath(path);
                        }
                        return ListUtil.sort(new ArrayList<>(infos), Comparator.comparing(fileInfo -> {
                            Long size = fileInfo.getSize();
                            return Long.MAX_VALUE - ObjectUtil.defaultIfNull(size, 0L);
                        }));
                    }));
            onListingSuccess();
            listingFailures.remove(path);
            return result;
        } catch (RuntimeException e) {
            // 目录不存在不是故障，不记、也不参与熔断（见 onListingFailure）
            if (!(e instanceof OpenListDirNotFoundException)) {
                listingFailures.put(path, new FailedListing(
                        System.currentTimeMillis() + LISTING_FAILURE_TTL_MS, e));
            }
            onListingFailure(e);
            throw e;
        }
    }

    /**
     * 递归列出目录下所有文件（30s 短缓存）
     * <p>
     * 不再持有实例锁（P1-5）：原先 synchronized 会把整个网盘客户端串行化，
     * 一次慢目录（60s 超时 + 重试退避）会阻塞其它订阅的列举与前端 5s 一次的轮询。
     * 限流顺序仍由 {@link #throttleApi()} 的静态锁保证，语义不变。
     *
     * @param path 目录
     * @return 文件列表
     */
    public List<OpenListFileInfo> findFiles(String path) {
        try {
            return findFilesStrict(path);
        } catch (Exception e) {
            log.warn("递归列出网盘目录失败 {}: {}", path, ExceptionUtils.getMessage(e));
            return List.of();
        }
    }

    /**
     * 严格版递归列举：查询失败时抛出，供必须区分"目录确实为空"与"查询失败"的调用方使用。
     * <p>
     * 请求合并（原先是靠实例锁顺带实现的）：同一 path 的并发递归列举只发一次请求，
     * 后来者等待同一份结果。等待超时/失败一律抛异常，不会把"查不到"变成"没有文件"。
     */
    public List<OpenListFileInfo> findFilesStrict(String path) {
        noteJudgementRecursiveListing(path);
        CachedFileList cached = findFilesCache.get(path);
        if (cached != null && cached.expireAt > System.currentTimeMillis()) {
            listingCacheHit.incrementAndGet();
            return cached.files;
        }
        CompletableFuture<List<OpenListFileInfo>> mine = new CompletableFuture<>();
        CompletableFuture<List<OpenListFileInfo>> existing = findFilesInFlight.putIfAbsent(path, mine);
        if (existing != null) {
            listingCoalesced.incrementAndGet();
            return awaitCoalescedFiles(path, existing);
        }
        try {
            List<OpenListFileInfo> files;
            // 标记"已进入递归内部"：子目录的 findFilesStrict 不该再被记成一次判定路径违规
            enterRecursion();
            try {
                files = buildFileList(path);
            } finally {
                exitRecursion();
            }
            mine.complete(files);
            return files;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            findFilesInFlight.remove(path, mine);
        }
    }

    /**
     * 记录"判定路径上出现了递归列举"（v3 不变量：应为 0）。
     * <p>
     * 只数最外层一次：递归进去的子目录（同线程、{@link #RECURSION_DEPTH} &gt; 0）不算，
     * 否则一次 12 集的递归会数成 13 次，指标就失去意义。
     * <p>
     * 为什么只是"记账 + 告警"而不是直接抛异常：这里是被动观测点，
     * 在判定链上抛异常会把"能查清楚"变成"查不清"（→ 全量「存疑」），
     * 而 v3 的要求是"读失败也不许产生破坏性动作"。真正的消除靠调用方改走单层列举。
     */
    private static void noteJudgementRecursiveListing(String path) {
        if (!isInJudgementPath() || recursionDepth() > 0) {
            return;
        }
        long n = judgementRecursiveListing.incrementAndGet();
        if (n <= JUDGEMENT_VIOLATION_LOG_LIMIT) {
            log.warn("判定路径出现递归列举（v3 不变量应为 0）path={}，第 {} 次。"
                            + "递归列举是「1 + 子目录数」次请求，一次网盘抖动就能拖垮整条判定链",
                    path, n);
        }
    }

    private static int recursionDepth() {
        Integer depth = RECURSION_DEPTH.get();
        return depth == null ? 0 : depth;
    }

    private static void enterRecursion() {
        RECURSION_DEPTH.set(recursionDepth() + 1);
    }

    private static void exitRecursion() {
        int depth = recursionDepth();
        if (depth <= 1) {
            RECURSION_DEPTH.remove();
        } else {
            RECURSION_DEPTH.set(depth - 1);
        }
    }

    /**
     * 在「判定路径」中执行一段逻辑。
     * <p>
     * 判定路径 = 回答"网盘上到底有没有这个文件"的链路：本地已存在判定、归位对账、收尾确认。
     * 在这段逻辑里出现的任何递归列举都会被记为一次违规
     * （见 {@link #getJudgementRecursiveListing()}，可用 {@code DoctorController} 查看）。
     * <p>
     * 为什么用 {@code Supplier}/{@code Runnable} 包裹而不是暴露裸的 enter/exit：
     * 标记存在 ThreadLocal 上，手工 enter/exit 一旦漏掉 finally，就会污染同一工作线程上
     * 后续无关的列举（把正常业务算成违规），而这种污染只在指标上体现、极难排查。
     */
    public static <T> T inJudgementPath(Supplier<T> action) {
        Integer current = JUDGEMENT_DEPTH.get();
        JUDGEMENT_DEPTH.set(current == null ? 1 : current + 1);
        try {
            return action.get();
        } finally {
            Integer depth = JUDGEMENT_DEPTH.get();
            if (depth == null || depth <= 1) {
                JUDGEMENT_DEPTH.remove();
            } else {
                JUDGEMENT_DEPTH.set(depth - 1);
            }
        }
    }

    /**
     * 无返回值的 {@link #inJudgementPath(Supplier)}。
     */
    public static void inJudgementPath(Runnable action) {
        inJudgementPath(() -> {
            action.run();
            return null;
        });
    }

    /**
     * 当前线程是否处于判定路径。
     */
    public static boolean isInJudgementPath() {
        Integer depth = JUDGEMENT_DEPTH.get();
        return depth != null && depth > 0;
    }

    /**
     * 判定路径上发生过的递归列举次数。
     * <p>
     * v3 的目标是<b>恒为 0</b>：判定路径只允许单层列举。一旦不为 0，
     * 说明又有调用点退回了递归列举，网盘抖动会重新被放大成"整条判定链失败"。
     * <p>
     * <b>但它只是"已包守卫的那部分"的指标，不能当成整条判定链的合规证明</b>：
     * {@link #noteJudgementRecursiveListing} 在 {@code !isInJudgementPath()} 时直接 return，
     * 因此<b>没被 {@link #inJudgementPath} 包住的调用点不记账</b>。
     * 目前判定路径上仍有未包裹的递归列举（启动恢复 / 归位对账 / 云下载兜底），
     * 详见 {@code OpenList判定回退方案.md} §12.6。
     */
    public static long getJudgementRecursiveListing() {
        return judgementRecursiveListing.get();
    }

    /**
     * 判定路径上的单层列举次数（v3 允许的唯一读操作）。
     * <p>
     * 与 {@link #getJudgementRecursiveListing()} 对照看：单层次数随订阅条数线性增长是正常的，
     * 递归次数必须为 0。
     */
    public static long getJudgementDirectListing() {
        return judgementDirectListing.get();
    }

    /**
     * 单层列举（严格版）：只读一个目录的<b>直接子项</b>，不做任何递归，恒定 1 次请求。
     * <p>
     * 这是 v3 允许判定路径使用的<b>唯一</b>读操作。与 {@link #findFilesStrict(String)} 的区别：
     * <ul>
     *   <li>请求数恒为 1，不随子目录数放大（递归版是 "1 + 子目录数"）；</li>
     *   <li>不读写 {@code findFilesCache}，不会把"变更前的旧快照"喂给判定；</li>
     *   <li>失败仍然抛出（严格语义），调用方据此把条目标为「存疑」而不是「不存在」。</li>
     * </ul>
     * 失败记忆（30s）与熔断冷却照旧生效：同一目录刚失败过不会立刻再打一次。
     *
     * @param path    目录
     * @param refresh 是否要求网盘强制刷新目录缓存
     * @return 直接子项
     */
    public List<OpenListFileInfo> listDirectChildrenStrict(String path, boolean refresh) {
        judgementDirectListing.incrementAndGet();
        return fsListStrict(path, refresh);
    }

    /**
     * 单层列举并取直接子项的名字集合（严格版）。
     * <p>
     * 收尾确认回答的是"计划里的目标文件名是否都在顶层"——一个名字集合足够，
     * 不需要大小/时间，也就没有任何理由去递归。
     */
    public Set<String> directChildNamesStrict(String path, boolean refresh) {
        return listDirectChildrenStrict(path, refresh).stream()
                .map(OpenListFileInfo::getName)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 真正执行一次递归列举（缓存未命中且无同 path 在途请求时进入）。
     * <p>
     * 递归子目录仍走 {@link #findFilesStrict(String)}：子目录的列举同样要进缓存、同样要合并，
     * 因此 findFilesCache 里既有订阅的下载根目录，也有递归访问到的每个子目录。
     * key 只会变长，不会出现"等自己"的死锁。
     */
    private List<OpenListFileInfo> buildFileList(String path) {
        listingCacheMiss.incrementAndGet();
        // 只有真的要发列举请求时才消耗"每轮列举预算"（缓存命中/被合并的等待方不消耗）
        listingCallCountRound.incrementAndGet();
        // 构建开始时快照世代号：期间若有目录变更触发失效，本次结果不得回填缓存
        long epoch = cacheEpoch.get();

        List<OpenListFileInfo> openListFileInfos = fsListStrict(path, true);
        List<OpenListFileInfo> list = openListFileInfos.stream()
                .flatMap(openListFileInfo -> {
                    if (Boolean.TRUE.equals(openListFileInfo.getIsDir())) {
                        // 子目录可能在列举与递归之间被删/改名：那只是"这个子目录没有文件"，
                        // 不该让整个目录的列举失败（否则一次并发删除就会把整订阅判成"存疑"）
                        try {
                            return findFilesStrict(path + "/" + openListFileInfo.getName()).stream();
                        } catch (OpenListDirNotFoundException e) {
                            log.debug("子目录已不存在，跳过 {}/{}", path, openListFileInfo.getName());
                            return Stream.empty();
                        }
                    }
                    return Stream.of(openListFileInfo);
                }).toList();

        List<OpenListFileInfo> sorted = ListUtil.sort(new ArrayList<>(list), Comparator.comparing(fileInfo -> {
            Long size = fileInfo.getSize();
            return Long.MAX_VALUE - ObjectUtil.defaultIfNull(size, 0L);
        }));
        if (cacheEpoch.get() != epoch) {
            // 构建期间目录已变更：这次拿到的是变更前的旧数据，丢弃不缓存
            log.debug("递归列举期间缓存已失效，跳过回填 {}", path);
            return sorted;
        }
        CachedFileList published = new CachedFileList(sorted, FIND_FILES_TTL_MS);
        findFilesCache.put(path, published);
        if (cacheEpoch.get() != epoch) {
            // 上面"检查→写入"之间仍可能被抢占：失效线程的 removeIf 先跑完，我们再写进去，
            // 旧数据就会存活整整 30s。写后复核并精确回滚自己刚写的条目。
            // remove(key, value) 只删自己写的那个值，绝不会误删新构建者的结果。
            log.debug("回填后检测到缓存已失效，回滚 {}", path);
            findFilesCache.remove(path, published);
        }
        return sorted;
    }

    /**
     * 查看任务
     *
     * @param tid 任务id
     * @return 任务信息
     */
    public Optional<OpenListTaskInfo> taskInfo(String tid) {
        try {
            return taskInfoStrict(tid);
        } catch (Exception e) {
            log.warn("OpenList task/info 调用失败 tid={}: {}", tid, ExceptionUtils.getMessage(e));
            return Optional.empty();
        }
    }

    /**
     * P0-3：严格版 task/info，失败抛而非 empty，供写路径使用。
     */
    public Optional<OpenListTaskInfo> taskInfoStrict(String tid) {
        String safeTid = requireSafeTid(tid);
        OpenListTaskInfo taskInfo = retryIdempotent("task/info " + safeTid,
                () -> postApi("task/offline_download/info?tid=" + safeTid)
                        .thenFunction(res -> {
                            HttpReq.assertStatus(res);
                            JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                            int code = jsonObject.get("code").getAsInt();
                            if (code != 200 || !jsonObject.has("data") || jsonObject.get("data").isJsonNull()) {
                                throw new IllegalStateException("task/info 失败 code=" + code);
                            }
                            return GsonStatic.fromJson(jsonObject.get("data").getAsJsonObject(), OpenListTaskInfo.class);
                        }));
        return Optional.ofNullable(taskInfo);
    }

    /**
     * 未完成的离线任务
     * (E8) 对齐 fsList 容错: 先校验 HTTP 状态; data 缺失/isJsonNull 返回空列表,
     * 调用失败返回空列表, 不再让脏响应打挂整条链路
     *
     * @return 任务列表
     */
    public List<OpenListTaskInfo> taskUnDoneList() {
        try {
            return taskUnDoneListStrict();
        } catch (Exception e) {
            log.warn("OpenList task/undone 调用失败: {}", ExceptionUtils.getMessage(e));
            return List.of();
        }
    }

    /**
     * P0-3：严格版未完成任务列表。写路径（adopt/复用判定）必须区分"确实无任务"
     * 与"查询失败"，失败抛而非返回空，否则一次抖动就会重复提交撞 10008。
     * 读/展示路径继续用非严格版。
     */
    public List<OpenListTaskInfo> taskUnDoneListStrict() {
        return getApi("task/offline_download/undone")
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonElement data = jsonObject == null ? null : jsonObject.get("data");
                    if (data == null || data.isJsonNull()) {
                        log.warn("OpenList task/undone 返回缺少 data, 按空任务处理");
                        return List.of();
                    }
                    return GsonStatic.fromJsonList(data.getAsJsonArray(), OpenListTaskInfo.class);
                });
    }

    /**
     * 已完成的离线任务
     * (E8) 对齐 fsList 容错: 先校验 HTTP 状态; data 缺失/isJsonNull 返回空列表,
     * 调用失败返回空列表, 不再让脏响应打挂整条链路
     *
     * @return 任务列表
     */
    public List<OpenListTaskInfo> taskDoneList() {
        try {
            return taskDoneListStrict();
        } catch (Exception e) {
            log.warn("OpenList task/done 调用失败: {}", ExceptionUtils.getMessage(e));
            return List.of();
        }
    }

    /**
     * P0-3：严格版已完成任务列表，语义同 {@link #taskUnDoneListStrict()}。
     */
    public List<OpenListTaskInfo> taskDoneListStrict() {
        return getApi("task/offline_download/done")
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonElement data = jsonObject == null ? null : jsonObject.get("data");
                    if (data == null || data.isJsonNull()) {
                        log.warn("OpenList task/done 返回缺少 data, 按空任务处理");
                        return List.of();
                    }
                    return GsonStatic.fromJsonList(data.getAsJsonArray(), OpenListTaskInfo.class);
                });
    }

    /**
     * 重试任务
     *
     * @param tid 任务id
     */
    public void taskRetry(String tid) {
        if (StrUtil.isBlank(tid)) {
            return;
        }
        if (!tid.matches("[A-Za-z0-9_\\-]+")) {
            // 非法 tid（服务端异常数据）：静默跳过，避免把重试升级为整体失败；剥离换行防日志伪造
            log.warn("taskRetry 跳过非法任务ID: {}", StrUtil.maxLength(tid.replaceAll("[\\r\\n]", " "), 64));
            return;
        }
        postApi("task/offline_download/retry")
                .form("tid", tid)
                .thenFunction(HttpResponse::isOk);
    }

    /**
     * 取消任务（运行中任务应先 cancel 再 delete）
     *
     * @param tid 任务id
     */
    public void taskCancel(String tid) {
        if (StrUtil.isBlank(tid)) {
            return;
        }
        String safeTid = requireSafeTid(tid);
        // 现网 OpenList/AList：query tid 有效；form 常返回 HTTP 200 + body code=404 且任务仍在
        if (tryTaskAction("task/offline_download/cancel?tid=" + safeTid, null, "cancel/query", safeTid)) {
            return;
        }
        if (tryTaskAction("task/offline_download/cancel", safeTid, "cancel/form", safeTid)) {
            return;
        }
        log.debug("cancel 任务失败 {}", safeTid);
    }

    /**
     * 删除任务
     *
     * @param tid 任务id
     */
    public void taskDelete(String tid) {
        if (StrUtil.isBlank(tid)) {
            return;
        }
        String safeTid = requireSafeTid(tid);
        // 现网有效路径：POST delete?tid=
        // form delete_some 常返回 code=200 data={} 但 running 任务仍残留，不能优先也不能只信 HTTP 200
        if (tryTaskAction("task/offline_download/delete?tid=" + safeTid, null, "delete/query", safeTid)) {
            return;
        }
        if (tryTaskAction("task/offline_download/delete", safeTid, "delete/form", safeTid)) {
            return;
        }
        if (tryTaskAction("task/offline_download/delete_some", safeTid, "delete_some/form", safeTid)) {
            return;
        }
        // 兼容旧版：JSON 数组 body（部分服务器会 400 invalid request format）
        try {
            HttpResponse res = postApi("task/offline_download/delete_some")
                    .body(GsonStatic.toJson(List.of(safeTid)))
                    .execute();
            if (isOpenListCodeOk(res)) {
                return;
            }
            log.debug("delete_some/json 失败 {}: {}", safeTid, res.body());
        } catch (Exception e) {
            log.debug("delete_some/json 异常 {}: {}", safeTid, e.getMessage());
        }
        log.warn("删除离线任务失败 {}", safeTid);
    }

    /**
     * tid 会拼入 URL query，白名单校验防恶意服务端响应注入额外参数
     */
    private static String requireSafeTid(String tid) {
        if (StrUtil.isBlank(tid) || !tid.matches("[A-Za-z0-9_\\-]+")) {
            throw new IllegalArgumentException("非法任务ID: " + tid);
        }
        return tid;
    }

    /**
     * 执行 cancel/delete 类动作，并校验 body.code==200（不能只看 HTTP 200）。
     *
     * @param action  API path（可含 query）
     * @param formTid 非空时用 form tid；空则只发 path
     */
    private boolean tryTaskAction(String action, String formTid, String label, String tid) {
        try {
            HttpRequest req = postApi(action);
            if (StrUtil.isNotBlank(formTid)) {
                req.form("tid", formTid);
            }
            HttpResponse res = req.execute();
            if (isOpenListCodeOk(res)) {
                return true;
            }
            log.debug("{} 未生效 tid={} body={}", label, tid, res.body());
        } catch (Exception e) {
            log.debug("{} 异常 tid={}: {}", label, tid, e.getMessage());
        }
        return false;
    }

    /**
     * 校验 OpenList API 返回 code==200
     */
    private void assertOpenListOk(HttpResponse res, String action) {
        HttpReq.assertStatus(res);
        String body = res.body();
        if (StrUtil.isBlank(body)) {
            // 少数实现只回 HTTP 200 空 body：视为成功
            return;
        }
        JsonObject jsonObject = GsonStatic.fromJson(body, JsonObject.class);
        if (jsonObject == null || !jsonObject.has("code")) {
            // 少数实现只回 HTTP 200 无 code：视为成功
            return;
        }
        int code = jsonObject.get("code").getAsInt();
        if (code != 200) {
            String message = jsonObject.has("message") ? jsonObject.get("message").getAsString() : "";
            // 115/OpenList 异步操作：errno 990009/"操作尚未执行完成"表示删除/移动正在异步处理中，
            // 容忍而非抛异常，避免并发操作冲突（如洗版连续删除）导致整流程失败
            if (code == 990009 || message.contains("990009")
                    || message.contains("尚未执行完成") || message.contains("操作尚未执行完成")) {
                log.debug("OpenList 异步操作进行中(990009) {}: {}", action, message);
                return;
            }
            throw new IllegalStateException(action + " 失败 code=" + code + " " + message);
        }
    }

    static boolean isOpenListCodeOk(HttpResponse res) {
        if (res == null) {
            return false;
        }
        return isOpenListBusinessOk(res.isOk(), res.body());
    }

    static boolean isOpenListBusinessOk(boolean httpOk, String body) {
        if (!httpOk) {
            return false;
        }
        try {
            if (StrUtil.isBlank(body)) {
                // 少数实现只回 HTTP 200
                return true;
            }
            JsonObject jsonObject = GsonStatic.fromJson(body, JsonObject.class);
            if (jsonObject == null || !jsonObject.has("code")) {
                return true;
            }
            return jsonObject.get("code").getAsInt() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    <T> T retryIdempotent(String action, Supplier<T> supplier) {
        return retryIdempotent(action, supplier, IDEMPOTENT_API_RETRY_DELAYS_MS);
    }

    static <T> T retryIdempotent(String action, Supplier<T> supplier, long[] retryDelaysMs) {
        int attempts = Math.max(1, Math.min(IDEMPOTENT_API_MAX_ATTEMPTS,
                (retryDelaysMs == null ? 0 : retryDelaysMs.length) + 1));
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            // 重试期间抑制 HttpRequestPlus 的 ERROR（瞬时故障会在此重试并由 WARN 记录），
            // 避免单次故障在日志里刷出「ERROR + WARN」两条重复记录
            HttpRequestPlus.setRetryMode(true);
            try {
                return supplier.get();
            } catch (RuntimeException e) {
                last = e;
                if (!isTransientOpenListFailure(e) || attempt >= attempts) {
                    throw e;
                }
                long delay = Math.max(0L, retryDelaysMs[attempt - 1]);
                log.warn("OpenList 临时故障，准备重试 action={} attempt={}/{} delayMs={} error={}",
                        action, attempt, attempts, delay, ExceptionUtils.getMessage(e));
                if (delay > 0) {
                    ThreadUtil.sleep(delay);
                }
            } finally {
                HttpRequestPlus.setRetryMode(false);
            }
        }
        throw last == null ? new IllegalStateException(action + " failed") : last;
    }

    static boolean isTransientOpenListFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String message = StrUtil.blankToDefault(current.getMessage(), "").toLowerCase(Locale.ROOT);
            if (className.contains("sockettimeout")
                    || className.contains("connectexception")
                    || className.contains("noroutetohost")
                    || isTransientMessage(message)
                    || message.contains("read timed out")
                    || message.contains("connect timed out")
                    || message.contains("connection reset")
                    || message.contains("connection refused")
                    || message.contains("status: 502")
                    || message.contains("status: 503")
                    || message.contains("status: 504")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 目录不存在（业务错误码，不是故障）。
     * <p>
     * 与"查询失败"必须区分开：
     * <ul>
     *   <li>语义上：目录不存在 = <b>确认没有</b>；查询失败 = <b>不知道</b>；</li>
     *   <li>熔断上：一个不存在的目录绝不能把整个网盘接口判为故障，
     *       否则新订阅（下载目录还没被创建）一进预览就会触发熔断。</li>
     * </ul>
     */
    public static class OpenListDirNotFoundException extends IllegalStateException {
        public OpenListDirNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * 业务错误文案是否表示"目录不存在"。不同网盘/版本的文案不一致，故做包含匹配。
     * <p>
     * <b>网络层故障一律先被排除</b>（{@link #isTransientMessage}）：115 把超时/连不上
     * 层层包在 {@code failed get dir} 里，与真正的目录不存在（{@code failed to get dir}）
     * 只差一个 "to"。一旦这里把"没查成"读成"确认没有"，代价就是删种子记录 + 整季重新下单。
     */
    static boolean isDirNotFoundMessage(String message) {
        if (StrUtil.isBlank(message) || isTransientMessage(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("not found")
                || lower.contains("failed to get dir")
                || lower.contains("object not exist")
                || lower.contains("no such file")
                || lower.contains("dir not exist");
    }

    /**
     * 文案里是否含网络层故障标记（超时 / 握手 / 连接层错误 / 限流）。
     * <p>
     * 出现的就说明"这次没能查成"，任何"确认没有"类判定都得先让路。真实日志：
     * <pre>
     * fs/list 失败 code=500 path=/115/… /Season 2
     * message=failed get objs: failed get dir: failed get parent list: failed to list objs:
     *         Get "https://webapi.115.com/files?…": net/http: TLS handshake timeout
     * </pre>
     * 注意只认网络层可携带的短语，<b>不认裸数字</b>（502/429 之类）——调用方常把
     * 整个异常串（含 path= / tmdbid=）传进来，裸数字会在路径上假阳性。
     */
    static boolean isTransientMessage(String message) {
        if (StrUtil.isBlank(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("tls handshake")
                || lower.contains("connection reset")
                || lower.contains("connection refused")
                || lower.contains("no route to host")
                || lower.contains("network is unreachable")
                || lower.contains("unexpected eof")
                || lower.contains("broken pipe")
                || lower.contains("i/o error")
                || lower.contains("too many requests");
    }

    /**
     * 网盘列举是否处于熔断冷却期。剩余毫秒数，0 表示可用。
     */
    public static long listingCooldownRemainingMs() {
        long until = cooldownUntilMs;
        long now = System.currentTimeMillis();
        return until > now ? until - now : 0L;
    }

    public static boolean isListingCoolingDown() {
        return listingCooldownRemainingMs() > 0L;
    }

    /**
     * 同一个冷却窗口只打一条「本轮跳过」INFO。
     * <p>
     * 调用点应在确认 {@link #isListingCoolingDown()} 之后。冷却重新触发时
     * {@code cooldownUntilMs} 会变成新的时间戳，于是下一条 INFO 又能打出来。
     * <p>
     * 为什么需要它：冷却期逐条打会把日志刷满，一条不打则用户只能看到一句
     * "网盘不可用"，分不清是"等一会儿会自愈"还是"网盘坏了"。
     *
     * @param what 被跳过的动作（如"归位对账""本地状态校验"）
     */
    public static void logCooldownSkipOnce(String what) {
        long until = cooldownUntilMs;
        if (until <= 0L || cooldownSkipLoggedFor.getAndSet(until) == until) {
            return;
        }
        log.info("网盘接口熔断冷却中（剩余 {}s），{} 本轮跳过且不发起任何请求",
                (listingCooldownRemainingMs() + 999L) / 1000L, what);
    }

    /**
     * 限流累计等待毫秒数（诊断用）
     */
    public static long getThrottleWaitMs() {
        return throttleWaitMs.get();
    }

    /**
     * 熔断触发次数（诊断用）
     */
    public static long getCooldownTriggeredCount() {
        return cooldownTriggered.get();
    }

    /**
     * 累计 API 调用次数（诊断用）
     */
    public static long getApiCallCount() {
        return apiCallCount.get();
    }

    /**
     * 本轮 API 调用次数（诊断用）
     */
    public static long getApiCallCountRound() {
        return apiCallCountRound.get();
    }

    /**
     * 本轮真正发出的「目录列举」请求数（诊断用）。
     * <p>
     * 每轮预算看的就是这个数：只有列举（= 为确认本地文件而查询）才消耗预算，
     * 缓存命中、被合并的等待方、以及 fs/mkdir、上传、下载器查询等都不算。
     */
    public static long getListingCallCountRound() {
        return listingCallCountRound.get();
    }

    /**
     * 目录列举缓存命中次数（诊断用）
     */
    public static long getListingCacheHit() {
        return listingCacheHit.get();
    }

    /**
     * 目录列举缓存未命中次数（诊断用，= 真正发出去的列举请求数）
     */
    public static long getListingCacheMiss() {
        return listingCacheMiss.get();
    }

    /**
     * 目录列举缓存命中率，[0,1]；样本为 0 时返回 0。
     */
    public static double getListingCacheHitRate() {
        long hit = listingCacheHit.get();
        long total = hit + listingCacheMiss.get();
        return total == 0L ? 0.0 : (double) hit / (double) total;
    }

    /**
     * 复位本轮调用计数（每轮 RSS 扫描开始时调用）。累计值与缓存命中率不清零。
     */
    public static void resetRoundApiStats() {
        apiCallCountRound.set(0L);
        listingCallCountRound.set(0L);
        // 新一轮重新真查：上一轮的失败/刷新记录不该把本轮的列举拦住
        listingFailures.clear();
        cloudListingRefreshedThisRound.clear();
    }

    // ---- F7-5 每轮 API 预算 ----

    /**
     * 开启本轮预算限制（由 {@code RssTask} 在轮次开始时调用）。
     * <p>
     * 预算 = 0 表示不限制；负值按 0 处理。
     * <p>
     * 这里<b>不再做硬顶钳制</b>：钳制由 {@code RssTask} 负责——它按订阅规模算出的默认值
     * 本就需要突破 {@link #MAX_API_BUDGET_PER_ROUND}（大库一轮的列举次数天然超过 200），
     * 用户显式配置的值仍由 RssTask 钳到硬顶。若在这里再钳一次，等于把那条修复静默废掉。
     */
    public static void startRoundBudget(int budget) {
        roundBudget.set(Math.max(0, budget));
    }

    /**
     * 关闭预算限制（轮次结束时调用）。不关闭的话，轮次结束后用户手动预览会被上一轮的预算卡住。
     */
    public static void clearRoundBudget() {
        roundBudget.set(0);
    }

    public static int getRoundBudget() {
        return roundBudget.get();
    }

    /**
     * 本轮预算是否已耗尽。
     * <p>
     * 语义是"<b>还能不能再为确认本地文件而列举</b>"：耗尽后应停止 Phase B，
     * 剩余条目保持「存疑」。所以只看<b>列举</b>次数（{@link #listingCallCountRound}），
     * 不看 {@code apiCallCountRound}——后者把 fs/mkdir、上传、下载器查询也算进去，
     * 会让"确认本地文件"的额度被无关请求提前吃光。
     * <p>
     * 注意这里只看次数，不看成功与否——失败的列举同样消耗了配额，
     * 继续打只会更快触发限流。
     */
    public static boolean isRoundBudgetExhausted() {
        int budget = roundBudget.get();
        return budget > 0 && listingCallCountRound.get() >= budget;
    }

    /**
     * 记录一次"因预算耗尽而放弃校验"，供诊断页展示
     */
    public static void markBudgetExhausted() {
        budgetExhausted.incrementAndGet();
    }

    public static long getBudgetExhaustedCount() {
        return budgetExhausted.get();
    }

    /**
     * 被请求合并省下的列举次数（可观测 F7-2 的实际收益）
     */
    public static long getListingCoalesced() {
        return listingCoalesced.get();
    }

    /**
     * 列举成功 → 复位熔断计数与退避级别。
     */
    static void onListingSuccess() {        if (consecutiveListingFailures.get() != 0 || cooldownLevel.get() != 0) {
            consecutiveListingFailures.set(0);
            cooldownLevel.set(0);
            cooldownUntilMs = 0L;
        }
    }

    /**
     * 列举失败 → 累计；达到阈值进入冷却（指数退避，上限 10 分钟）。
     * <p>
     * 冷却期内所有列举直接抛错、<b>不发请求</b>：网盘已明确在限流或故障，
     * 继续打只会加重限流并拖长整轮时间。调用方据此把条目标为"存疑"而不是"不存在"。
     */
    static void onListingFailure(Exception e) {
        if (e instanceof OpenListDirNotFoundException) {
            // 目录不存在不是故障，不参与熔断
            return;
        }
        lastListingFailureAt = System.currentTimeMillis();
        lastListingFailureMessage = StrUtil.blankToDefault(ExceptionUtils.getMessage(e), e.getClass().getSimpleName());
        int threshold = intConfig(ConfigUtil.CONFIG == null ? null : ConfigUtil.CONFIG.getOpenListFailThreshold(),
                3, 1, 10);
        int failures = consecutiveListingFailures.incrementAndGet();
        if (failures < threshold) {
            return;
        }
        int level = Math.min(cooldownLevel.incrementAndGet(), 6);
        long baseMs = intConfig(ConfigUtil.CONFIG == null ? null : ConfigUtil.CONFIG.getOpenListCooldownSeconds(),
                60, 10, 600) * 1000L;
        long cooldownMs = Math.min(baseMs * (1L << (level - 1)), MAX_COOLDOWN_MS);
        cooldownUntilMs = System.currentTimeMillis() + cooldownMs;
        consecutiveListingFailures.set(0);
        cooldownTriggered.incrementAndGet();
        log.warn("网盘列举连续失败 {} 次，进入冷却 {}s（第 {} 级）。冷却期内不再发起网盘请求，"
                        + "本地状态一律标记为「存疑」。原因: {}{}",
                threshold, cooldownMs / 1000L, level, ExceptionUtils.getMessage(e), upstreamHint(e));
    }

    /**
     * 给失败日志补上"到底哪一跳坏了"：上游域名 + 错误类别。
     * <p>
     * 用户看到的原始文案长这样：
     * <pre>
     * fs/list 失败 code=500 path=/115/… message=failed get objs: failed to list objs:
     *         Get "https://webapi.115.com/files?…": net/http: TLS handshake timeout
     * </pre>
     * 其中真正的因果在最后一段（OpenList → 115），而不是 ani-rss → OpenList 那一跳。
     */
    static String upstreamHint(Exception e) {
        String message = ExceptionUtils.getMessage(e);
        String host = extractUpstreamHost(message);
        String category = classifyUpstreamFailure(message);
        if (StrUtil.isBlank(host) && StrUtil.isBlank(category)) {
            return "";
        }
        return "（上游=" + StrUtil.blankToDefault(host, "未识别") + "，类别=" + StrUtil.blankToDefault(category, "未知")
                + "；这是 OpenList 到上游网盘的连接问题，不是 ani-rss 到 OpenList）";
    }

    /**
     * 从错误文案里揠出上游服务的 host（如 {@code webapi.115.com}），识别不出返回空串。
     */
    public static String extractUpstreamHost(String message) {
        if (StrUtil.isBlank(message)) {
            return "";
        }
        java.util.regex.Matcher matcher = UPSTREAM_URL_PATTERN.matcher(message);
        if (!matcher.find()) {
            return "";
        }
        String host = matcher.group(1);
        int colon = host.indexOf(':');
        return colon > 0 ? host.substring(0, colon) : host;
    }

    private static final java.util.regex.Pattern UPSTREAM_URL_PATTERN =
            java.util.regex.Pattern.compile("https?://([^/\\s\"'?]+)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 上游失败的类别（纯字符串判定，供日志与自检页给具体建议）。
     */
    public static String classifyUpstreamFailure(String message) {
        if (StrUtil.isBlank(message)) {
            return "";
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("tls handshake")) {
            return "TLS 握手超时（网络层丢包 / MTU / IPv6 / 代理）";
        }
        if (lower.contains("no such host") || lower.contains("dns") || lower.contains("lookup ")) {
            return "DNS 解析失败";
        }
        if (lower.contains("connection refused")) {
            return "连接被拒";
        }
        if (lower.contains("connection reset") || lower.contains("unexpected eof") || lower.contains("broken pipe")) {
            return "连接被重置";
        }
        if (lower.contains("too many requests") || lower.contains("429")) {
            return "上游限流";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "超时";
        }
        // 认不出就不编：空串让调用方显示"未知"，也避免日志里出现假的归因
        return "";
    }

    /**
     * 失败记忆命中次数（诊断用）
     */
    public static long getListingFailureMemoHit() {
        return listingFailureMemoHit.get();
    }

    /**
     * 最近一次列举失败的时刻（0 = 本次运行还没失败过）
     */
    public static long getLastListingFailureAt() {
        return lastListingFailureAt;
    }

    /**
     * 最近一次列举失败的原始文案
     */
    public static String getLastListingFailureMessage() {
        return lastListingFailureMessage;
    }

    /**
     * 仅供测试/诊断：复位熔断、令牌桶与全部可观测计数
     */
    public static void resetRateLimitState() {
        synchronized (API_RATE_LOCK) {
            lastTokenAtNanos = 0L;
            availableTokens = 0.0;
        }
        consecutiveListingFailures.set(0);
        cooldownLevel.set(0);
        cooldownUntilMs = 0L;
        cooldownSkipLoggedFor.set(0L);
        listingFailures.clear();
        listingFailureMemoHit.set(0L);
        lastListingFailureAt = 0L;
        lastListingFailureMessage = "";
        cloudListingRefreshedThisRound.clear();
        throttleWaitMs.set(0L);
        cooldownTriggered.set(0L);
        apiCallCount.set(0L);
        apiCallCountRound.set(0L);
        listingCallCountRound.set(0L);
        listingCacheHit.set(0L);
        listingCacheMiss.set(0L);
        listingCoalesced.set(0L);
        budgetExhausted.set(0L);
        roundBudget.set(0);
        judgementRecursiveListing.set(0L);
        judgementDirectListing.set(0L);
        // 清掉当前线程可能残留的判定/递归标记：正常路径由 inJudgementPath 的 finally 与
        // findFilesStrict 的 finally 保证，这里只是给测试与诊断一个"从干净状态开始"的兜底
        JUDGEMENT_DEPTH.remove();
        RECURSION_DEPTH.remove();
    }

    private static int intConfig(Integer value, int fallback, int min, int max) {
        int v = value == null ? fallback : value;
        return Math.max(min, Math.min(v, max));
    }

    /**
     * 实际生效的令牌桶速率（次/秒，含默认值与钳制）。
     * 抽出为公共方法，让"每轮预算"的换算与限流器共用同一份默认值。
     */
    public static int effectiveApiPerSecond(Config config) {
        return intConfig(config == null ? null : config.getOpenListApiPerSecond(),
                (int) DEFAULT_API_PER_SECOND, 1, (int) MAX_API_PER_SECOND);
    }

    /**
     * 令牌桶限流：保证的是全局<b>速率</b>上限，<b>不是</b>端到端串行。
     * <p>
     * 调用时机是 {@link #getApi(String)}/{@link #postApi(String)} 的<b>开头</b>，
     * 所以 {@link #API_RATE_LOCK} 只覆盖"取令牌"这一段（含最多 5s 的等待）；
     * 真正的 HTTP 请求是调用方随后在 {@code .then(...)}/{@code .thenFunction(...)} 里发出的，
     * 在锁<b>之外</b>。非列举类请求（task 查询、fs/mkdir 等）本来就是这个形态。
     * <p>
     * 变化点（P1-5）：<b>递归列举</b>以前额外被 {@code findFilesStrict} 的实例监视器串起来，
     * 全局同时只有 1 个在飞；去掉那把锁后，并发度上限变成调用方规模——
     * 3~8 个 RSS 工作线程 + 前端每 5s 的任务列表轮询 + OpenList 的离线等待池。
     * 因此"请求不再互相排队"是本次的有意结果，而不是回归。
     * <p>
     * 要降低对网盘的压力，请调 {@code openListApiPerSecond}（令牌桶速率）与
     * {@code openListApiBurst}（突发额度），<b>不要</b>再加请求级锁——
     * 那会把 P1-5 刚移除的"一个慢目录卡住所有人"原样加回来。
     */
    static void throttleApi() {
        apiCallCount.incrementAndGet();
        apiCallCountRound.incrementAndGet();
        synchronized (API_RATE_LOCK) {
            double perSecond = effectiveApiPerSecond(ConfigUtil.CONFIG);
            double burst = intConfig(ConfigUtil.CONFIG == null ? null : ConfigUtil.CONFIG.getOpenListApiBurst(),
                    (int) DEFAULT_API_BURST, 1, (int) MAX_API_BURST);

            long now = System.nanoTime();
            if (lastTokenAtNanos == 0L) {
                availableTokens = burst;
            } else {
                double elapsedSec = (now - lastTokenAtNanos) / 1_000_000_000.0;
                availableTokens = Math.min(burst, availableTokens + elapsedSec * perSecond);
            }
            lastTokenAtNanos = now;

            if (availableTokens < 1.0) {
                long waitMs = (long) Math.ceil((1.0 - availableTokens) / perSecond * 1000.0);
                waitMs = Math.max(1L, Math.min(waitMs, MAX_THROTTLE_WAIT_MS));
                ThreadUtil.sleep(waitMs);
                throttleWaitMs.addAndGet(waitMs);

                long after = System.nanoTime();
                double sleptSec = (after - lastTokenAtNanos) / 1_000_000_000.0;
                availableTokens = Math.min(burst, availableTokens + sleptSec * perSecond);
                lastTokenAtNanos = after;
            }
            availableTokens -= 1.0;
        }
    }

    /**
     * 目录变更后清理 findFiles/listFileNames 缓存（清空全部）。
     * <p>
     * 保留"全清"语义：路径未知的调用方、以及测试都依赖它。已知变更路径时请改用
     * {@link #invalidateFindFilesCache(String...)}，避免订阅 A 的一次改名把 B/C/D 的
     * 已构建列举全部打掉（下载期间 30s 列举缓存因此形同虚设）。
     */
    void invalidateFindFilesCache() {
        invalidateFindFilesCache((Collection<String>) null);
    }

    /**
     * 目录变更后只失效受影响的子树（含祖先与后代）。
     *
     * @param changedPaths 发生变更的目录；为 null 或全为空白时退化为清空全部
     */
    void invalidateFindFilesCache(String... changedPaths) {
        invalidateFindFilesCache(changedPaths == null ? null : java.util.Arrays.asList(changedPaths));
    }

    /**
     * 目录变更后只失效受影响的子树。
     * <p>
     * 判定：归一化后（反斜杠转正斜杠、去尾部斜杠、折叠重复斜杠、空路径视为根）缓存键 k 受变更根 r 影响，当且仅当
     * <ul>
     *   <li>{@code k.equals(r)} —— 该目录本身变了；</li>
     *   <li>{@code k} 在 {@code r} 之下 —— 递归列举会把子目录一并缓存；</li>
     *   <li>{@code k} 是 {@code r} 的祖先 —— 祖先的递归结果里包含该目录的内容，同样过期。
     *       例：改名 {@code /downloads/Show/Season 1} 里的文件，{@code /downloads/Show} 的缓存也失效。</li>
     * </ul>
     * 同时丢弃受影响的在途请求：失效<b>之后才到达</b>的调用方会重新构建，
     * 而不是搭上一个变更前就开始的构建。
     * <p>
     * <b>已经在途的等待方仍会拿到变更前的结果</b>——它握着的 future 引用无法被取消，
     * 丢掉 in-flight 条目只能阻止"后来者"，不能让"已在等的人"改口。这是<b>刻意接受</b>的：
     * 误差方向是"看到的文件比实际少"→ 最多多下载一次（保守），
     * 绝不会反过来把"其实不存在"说成"已下载"；且窗口只有一个构建的时间。
     * 缓存本身不会被污染（世代号守卫 + 写后回滚），之后的读取都是新数据。
     * <p>
     * 路径缺失（null/空白）时退化为清空全部：宁可多清，也不能留下过期缓存——
     * 过期缓存会把"其实存在"读成"不存在"，直接导致重复下载。
     */
    void invalidateFindFilesCache(Collection<String> changedPaths) {
        // 先自增世代号：在途构建回来后比对失败，不会把变更前的旧结果回填
        cacheEpoch.incrementAndGet();
        if (changedPaths == null || changedPaths.stream().allMatch(StrUtil::isBlank)) {
            findFilesCache.clear();
            listNamesCache.clear();
            listNamesExpire.clear();
            findFilesInFlight.clear();
            listNamesInFlight.clear();
            return;
        }
        List<String> roots = changedPaths.stream()
                .filter(StrUtil::isNotBlank)
                .map(OpenListApi::normalizeCachePath)
                .distinct()
                .toList();
        findFilesCache.keySet().removeIf(k -> isAffectedByChange(k, roots));
        listNamesCache.keySet().removeIf(k -> isAffectedByChange(k, roots));
        listNamesExpire.keySet().removeIf(k -> isAffectedByChange(k, roots));
        findFilesInFlight.keySet().removeIf(k -> isAffectedByChange(k, roots));
        listNamesInFlight.keySet().removeIf(k -> isAffectedByChange(k, roots));
        // 目录已经变了，失败记忆也必须跟着失效：否则一次抖动会把"新目录已经能列了"也拦 30s
        listingFailures.keySet().removeIf(k -> isAffectedByChange(k, roots));
    }

    /**
     * 缓存键归一化：反斜杠转正斜杠、折叠重复斜杠、去掉尾部斜杠、空路径视为根。
     * <p>
     * 折叠重复斜杠是必需的：{@code /media/A//S01} 与 {@code /media/A/S01} 若按原样比较，
     * 三个判定（自身/后代/祖先）全部落空，缓存条目会被<b>静默漏掉</b>——
     * 静默漏失效是这里最坏的失败模式，必须让它不可能发生。
     * <p>
     * 不解析 {@code .} / {@code ..} 段（现状下无调用方能产出，见下）；若将来出现，
     * 需要在此补上真正的路径规范化。
     * 已核查全部生产调用点（OpenList.java:1137/1253/2128/2677/3978/3981、
     * DownloadService:1460、LibraryController:502）都不会产生尾部或重复斜杠，故此处属加固而非在修线上缺陷。
     */
    private static String normalizeCachePath(String path) {
        if (StrUtil.isBlank(path)) {
            return "/";
        }
        String p = path.replace('\\', '/');
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * 缓存键是否受某个变更根影响：自身 / 后代 / 祖先（根目录视为一切的祖先）。
     */
    private static boolean isAffectedByChange(String key, List<String> roots) {
        String k = normalizeCachePath(key);
        for (String r : roots) {
            if (k.equals(r)) {
                return true;
            }
            boolean descendant = "/".equals(r) ? k.startsWith("/") : k.startsWith(r + "/");
            boolean ancestor = "/".equals(k) || r.startsWith(k + "/");
            if (descendant || ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * fs/ 目录操作（fs/list、fs/mkdir 等）响应慢：放宽超时，避免 20s 读超时引发重试风暴
     */
    private static final int OPENLIST_FS_TIMEOUT_MS = 60 * 1000;

    /**
     * get api
     * <p>
     * 不再 synchronized（P1-5）：实例锁会把整个网盘客户端串行化，
     * 限流顺序由 {@link #throttleApi()} 的静态锁保证，这里不需要实例锁。
     *
     * @param action
     * @return
     */
    public HttpRequest getApi(String action) {
        throttleApi();
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        HttpRequest req = action != null && action.startsWith("fs/")
                ? HttpReq.get(host + "/api/" + action, OPENLIST_FS_TIMEOUT_MS)
                : HttpReq.get(host + "/api/" + action);
        return req.header(Header.AUTHORIZATION, password);
    }

    /**
     * post api
     * <p>
     * 不再 synchronized（P1-5），理由同 {@link #getApi(String)}。
     *
     * @param action
     * @return
     */
    public HttpRequest postApi(String action) {
        throttleApi();
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        HttpRequest req = action != null && action.startsWith("fs/")
                ? HttpReq.post(host + "/api/" + action, OPENLIST_FS_TIMEOUT_MS)
                : HttpReq.post(host + "/api/" + action);
        return req.header(Header.AUTHORIZATION, password);
    }

}
