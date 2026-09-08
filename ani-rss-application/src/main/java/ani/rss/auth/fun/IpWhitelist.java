package ani.rss.auth.fun;

import ani.rss.entity.Config;
import ani.rss.util.basic.CidrRangeChecker;
import ani.rss.util.other.AuthUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.lang.PatternPool;
import cn.hutool.core.net.Ipv4Util;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Slf4j
public class IpWhitelist implements Function<HttpServletRequest, Boolean> {
    /** 已提示过非 IPv4 表达式的白名单条目，避免每次请求重复刷日志 */
    private static final Set<String> SKIPPED_WHITELIST_ENTRIES = ConcurrentHashMap.newKeySet();

    @Override
    public Boolean apply(HttpServletRequest request) {
        String ip = AuthUtil.getIp();
        Config config = ConfigUtil.CONFIG;
        String ipWhitelistStr = config.getIpWhitelistStr();
        Boolean ipWhitelist = config.getIpWhitelist();
        if (!Boolean.TRUE.equals(ipWhitelist)) {
            return false;
        }
        if (StrUtil.isBlank(ipWhitelistStr)) {
            return false;
        }
        if (StrUtil.isBlank(ip)) {
            return false;
        }
        try {
            // 请求 ip 非 IPv4（如 IPv6）时，通配符/CIDR/区间等 IPv4 专用语义全部跳过，仅保留精确匹配
            boolean ipIsIpv4 = PatternPool.IPV4.matcher(ip).matches();
            List<String> list = StrUtil.split(ipWhitelistStr, "\n", true, true);
            for (String string : list) {
                if (string.equals(ip)) {
                    return true;
                }
                // 格式校验作用于白名单条目本身：非 IPv4 表达式（含 IPv6 字面量/非法文本）记录一次并跳过，保持 fail-closed
                if (!isIpv4Expression(string)) {
                    logSkippedEntryOnce(string);
                    continue;
                }
                if (!ipIsIpv4) {
                    continue;
                }
                // 通配符，如 192.168.*.1
                if (string.contains("*")) {
                    if (Ipv4Util.matches(string, ip)) {
                        return true;
                    }
                }
                // X.X.X.X/X
                if (CidrRangeChecker.CIDR_PATTERN.matcher(string).matches()) {
                    if (CidrRangeChecker.isIpInRange(ip, string)) {
                        return true;
                    }
                }
                // X.X.X.X-X.X.X.X
                if (isIpInRange(ip, string)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("ip白名单存在问题");
            log.error(e.getMessage(), e);
        }
        return false;
    }

    /**
     * 条目是否为 IPv4 表达式：精确 IPv4 / 通配符 / CIDR / 区间
     */
    private static boolean isIpv4Expression(String entry) {
        return PatternPool.IPV4.matcher(entry).matches()
                || entry.contains("*")
                || CidrRangeChecker.CIDR_PATTERN.matcher(entry).matches()
                || entry.contains("-");
    }

    /**
     * 同一条目仅提示一次，避免每次请求重复输出
     */
    private static void logSkippedEntryOnce(String entry) {
        if (SKIPPED_WHITELIST_ENTRIES.add(entry)) {
            log.debug("ip 白名单条目不是可识别的 IPv4 表达式，已跳过该条目: {}", entry);
        }
    }

    /**
     * 判断ip是否在指定范围内
     *
     * @param ip ip地址
     * @param s  x.x.x.x-x.x.x.x
     * @return 判断结果
     */
    public static boolean isIpInRange(String ip, String s) {
        if (!s.contains("-")) {
            return false;
        }

        List<String> split = StrUtil.split(s, "-", true, true);
        if (split.size() != 2) {
            return false;
        }

        String startIp = split.get(0);
        String endIp = split.get(1);

        if (!PatternPool.IPV4.matcher(startIp).matches()) {
            return false;
        }

        if (!PatternPool.IPV4.matcher(endIp).matches()) {
            return false;
        }

        long ipLong = Ipv4Util.ipv4ToLong(ip);
        long startIpLong = Ipv4Util.ipv4ToLong(startIp);
        long endIpLong = Ipv4Util.ipv4ToLong(endIp);

        return ipLong >= startIpLong && ipLong <= endIpLong;
    }

}
