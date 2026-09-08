package ani.rss.util.other;

import ani.rss.annotation.Auth;
import ani.rss.auth.enums.AuthType;
import ani.rss.commons.CacheUtils;
import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.entity.Global;
import ani.rss.entity.Login;
import ani.rss.entity.web.Result;
import ani.rss.entity.web.ResultCode;
import ani.rss.exception.ResultException;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 鉴权工具
 */
@Slf4j
public class AuthUtil {
    private static final Map<String, Function<HttpServletRequest, Boolean>> MAP = new ConcurrentHashMap<>();

    static {
        resetKey();
    }

    /**
     * 刷新有效时间
     */
    public static void resetTime() {
        String key = CacheUtils.get("auth_key");
        if (StrUtil.isBlank(key)) {
            return;
        }
        Config config = ConfigUtil.CONFIG;
        Integer loginEffectiveHours = config.getLoginEffectiveHours();
        CacheUtils.put("auth_key", key, TimeUnit.HOURS.toMillis(loginEffectiveHours));
    }

    /**
     * 进程内随机密钥（不落盘、重启即失效），用于签发 token；
     * 旧实现把配置中的 uuid 直接作为 key，配置文件泄露即可离线伪造任意 token。
     */
    private static volatile String RANDOM_KEY = UUID.randomUUID().toString();

    /**
     * 刷新密钥
     */
    public static String resetKey() {
        Config config = ConfigUtil.CONFIG;

        // 登录有效时间/小时
        Integer loginEffectiveHours = config.getLoginEffectiveHours();
        Boolean multiLoginForbidden = config.getMultiLoginForbidden();

        String key;
        if (Boolean.TRUE.equals(multiLoginForbidden)) {
            // 禁止多端登录：每次登录签发新 key，旧 token 立即失效
            key = UUID.randomUUID().toString();
        } else {
            // 允许多端登录：共享进程内随机 key（重启后所有 token 失效，需重新登录）
            key = RANDOM_KEY;
        }
        CacheUtils.put("auth_key", key, TimeUnit.HOURS.toMillis(loginEffectiveHours));
        return key;
    }

    public static String getAuth(Login login) {
        String key = CacheUtils.get("auth_key");
        if (StrUtil.isBlank(key)) {
            key = resetKey();
        }
        login.setKey(key);
        return SecureUtil.sha256(GsonStatic.toJson(login));
    }

    public static Login getLogin() {
        Config config = ConfigUtil.CONFIG;
        Login login = ObjectUtil.clone(config.getLogin());
        if (config.getVerifyLoginIp()) {
            login.setIp(getIp());
        } else {
            login.setIp("");
        }
        return login;
    }

    /**
     * 获取ip地址
     *
     * @return
     */
    public static String getIp() {
        try {
            Config config = ConfigUtil.CONFIG;
            List<String> reverseProxyTrustIpList = config.getReverseProxyTrustIpList();
            Boolean reverseProxyTrustIpListEnabled = config.getReverseProxyTrustIpListEnabled();

            HttpServletRequest request = Global.REQUEST.get();
            String ip = request.getRemoteAddr();
            if (!reverseProxyTrustIpListEnabled) {
                // 未启用 受信任的反向代理IP
                return ip;
            }

            if (!reverseProxyTrustIpList.contains(ip)) {
                // 不在名单中, 受信任的反向代理IP
                return ip;
            }

            // https://developer.mozilla.org/zh-CN/docs/Web/HTTP/Reference/Headers/X-Forwarded-For
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (StrUtil.isNotBlank(forwardedFor)) {
                // 从右往左遍历 XFF 段：跳过命中受信代理名单的段，第一个非受信段即客户端 IP；
                // 全部为受信代理则回退直连地址，避免伪造 XFF 头绕过白名单等安全判定
                List<String> segments = StrUtil.split(forwardedFor, ",", true, true);
                for (int i = segments.size() - 1; i >= 0; i--) {
                    String segment = segments.get(i);
                    if (reverseProxyTrustIpList.contains(segment)) {
                        // 受信代理本身，继续向左找
                        continue;
                    }
                    return segment;
                }
                log.debug("X-Forwarded-For 全部为受信代理，回退使用直连地址: {}", ip);
            }

            return ip;
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
        }
        return "未知";
    }

    /**
     * 鉴权检测
     *
     * @param request
     * @param auth
     * @return
     */
    public static Boolean test(HttpServletRequest request, Auth auth) {
        if (!auth.value()) {
            // 不进行校验
            return true;
        }
        for (AuthType type : auth.type()) {
            Boolean test = test(request, type);
            if (test) {
                return true;
            }
        }
        return false;
    }

    /**
     * 鉴权检测
     *
     * @param request
     * @param authType
     * @return
     */
    public static Boolean test(HttpServletRequest request, AuthType authType) {
        Class<? extends Function<HttpServletRequest, Boolean>> clazz = authType.getClazz();
        String name = clazz.getName();
        // computeIfAbsent 原子懒加载，避免并发下重复实例化
        return MAP.computeIfAbsent(name, k -> {
            Function<HttpServletRequest, Boolean> instance = ReflectUtil.newInstance(clazz);
            return instance;
        }).apply(request);
    }

    /**
     * 登录限流记录：失败计数 + 首次失败时间戳（固定窗口，1 天过期）
     */
    private static final class AttemptRecord {
        final AtomicInteger count = new AtomicInteger(0);
        final long firstFailAt = System.currentTimeMillis();
    }

    /**
     * 登录限流独立存储：不再与业务缓存共用 CacheUtils 实例，避免驱逐干扰与计数丢失
     */
    private static final ConcurrentHashMap<String, AttemptRecord> LOGIN_ATTEMPTS = new ConcurrentHashMap<>();

    /** 登录限流阈值：失败 30 次后限制登录 */
    private static final int LOGIN_ATTEMPT_LIMIT = 30;

    /** 登录限流窗口：从首次失败起 1 天，失败不刷新计时 */
    private static final long LOGIN_ATTEMPT_WINDOW_MS = TimeUnit.DAYS.toMillis(1);

    /** 登录限流 Map 容量上限：超过后清理过期项 */
    private static final int LOGIN_ATTEMPT_MAX_ENTRIES = 10000;

    /**
     * 限制尝试次数（仅服务 /login 流程）
     *
     * @param isAdd false=检查锁定状态（达到阈值抛 403）；true=记录一次失败
     */
    public static void limitLoginAttempts(Boolean isAdd) {
        Config config = ConfigUtil.CONFIG;
        boolean limitLoginAttempts = config.getLimitLoginAttempts();
        if (!limitLoginAttempts) {
            return;
        }
        // "未知" 也允许计数，避免无 IP 来源绕过限流
        String ip = AuthUtil.getIp();
        String key = "LimitLoginAttempts#" + ip;

        if (!Boolean.TRUE.equals(isAdd)) {
            // 检查锁定：达到阈值或仍在 1 天窗口内且计数已满则拒绝
            AttemptRecord record = LOGIN_ATTEMPTS.get(key);
            if (record == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - record.firstFailAt >= LOGIN_ATTEMPT_WINDOW_MS) {
                // 固定窗口已过，惰性清除
                LOGIN_ATTEMPTS.remove(key, record);
                return;
            }
            if (record.count.get() < LOGIN_ATTEMPT_LIMIT) {
                return;
            }
            log.debug("失败次数过多, 已限制登录 {}", ip);
            Result<Void> result = new Result<Void>()
                    .setMessage(StrFormatter.format("失败次数过多, 已限制登录 {}", ip))
                    .setCode(ResultCode.HTTP_FORBIDDEN);
            throw new ResultException(result);
        }

        // 记录一次失败：原子累加，首败写入时间戳
        Runnable cleanup = LOGIN_ATTEMPTS.size() > LOGIN_ATTEMPT_MAX_ENTRIES
                ? () -> LOGIN_ATTEMPTS.values().removeIf(r -> System.currentTimeMillis() - r.firstFailAt >= LOGIN_ATTEMPT_WINDOW_MS)
                : null;
        boolean[] thresholdReached = {false};
        LOGIN_ATTEMPTS.compute(key, (k, record) -> {
            if (record == null) {
                record = new AttemptRecord();
            }
            int count = record.count.incrementAndGet();
            if (count >= LOGIN_ATTEMPT_LIMIT) {
                thresholdReached[0] = true;
            }
            return record;
        });
        if (cleanup != null) {
            cleanup.run();
        }
        if (thresholdReached[0]) {
            log.warn("登录失败次数达到 {} 次, 已限制登录 1 天: {}", LOGIN_ATTEMPT_LIMIT, ip);
        }
    }

    /**
     * 清除指定 ip 的登录失败计数（登录成功后调用）
     *
     * @param ip 客户端 ip
     */
    public static void clearLoginAttempts(String ip) {
        LOGIN_ATTEMPTS.remove("LimitLoginAttempts#" + ip);
    }

}
